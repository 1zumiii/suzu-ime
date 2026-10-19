// 推理层基准与对照：直接调用 SuzuModel（不经过调度层与 Lua），逐题打分并计时。
// 用法：suzu_bench <suzu.gguf> <suzu.vocab.txt> <cases.tsv> [选项]
//   --threads N        线程数（默认 4）
//   --no-share         不合并候选前缀（旧核心的做法）
//   --no-incremental   上文每次从头解码
//   --no-unified       KV 不共享存储
//   --slots N          上文缓存条数（默认 2）
//   --repack           打开 CPU 权重重排
//   --no-pool          不用常驻线程池（llama.cpp 默认：每次解码临时建线程）
//   --poll N           常驻线程池的自旋程度 0~100（默认 0）
//   --mode cold|warm   cold：每题上文都是新的（现场解码上文）；warm：先 prepare 再打分，只计打分（默认 cold）
//   --repeat R         整套重复 R 轮（默认 1），报每轮的分位数
//   --out PATH         写出与 res_q8.tsv 同格式的打分结果（只在第一轮写）
// cases.tsv 每行：编号 \t 0 \t 上文 \t 候选1|候选2|…
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "llama.h"
#include "model/suzu_model.h"

namespace {

struct Case {
    std::string id, ctx;
    std::vector<std::string> cands;
};

std::vector<Case> read_cases(const char * path) {
    std::vector<Case> out;
    std::ifstream in(path);
    std::string line;
    while (std::getline(in, line)) {
        std::vector<std::string> f;
        std::stringstream ss(line);
        std::string x;
        while (std::getline(ss, x, '\t')) f.push_back(x);
        if (f.size() < 4) continue;
        Case c;
        c.id = f[0];
        c.ctx = f[2];
        std::stringstream cs(f[3]);
        while (std::getline(cs, x, '|')) if (!x.empty()) c.cands.push_back(x);
        out.push_back(c);
    }
    return out;
}

double pct(std::vector<double> v, double p) {
    if (v.empty()) return -1;
    std::sort(v.begin(), v.end());
    size_t i = (size_t)std::max(0.0, std::ceil(p * v.size()) - 1);
    return v[std::min(i, v.size() - 1)];
}

}  // namespace

int main(int argc, char ** argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: suzu_bench <model> <vocab> <cases.tsv> [options]\n");
        return 64;
    }
    suzu::ModelOptions opt;
    opt.model_path = argv[1];
    opt.vocab_path = argv[2];
    const char * cases_path = argv[3];
    std::string mode = "cold", out_path;
    int repeat = 1;
    for (int i = 4; i < argc; i++) {
        std::string a = argv[i];
        auto next = [&]() { return i + 1 < argc ? argv[++i] : ""; };
        if (a == "--threads") opt.n_threads = atoi(next());
        else if (a == "--no-share") opt.share_prefix = false;
        else if (a == "--no-incremental") opt.incremental = false;
        else if (a == "--no-unified") opt.kv_unified = false;
        else if (a == "--slots") opt.context_slots = atoi(next());
        else if (a == "--repack") opt.repack = true;
        else if (a == "--no-pool") opt.persistent_threads = false;
        else if (a == "--poll") opt.poll = atoi(next());
        else if (a == "--mode") mode = next();
        else if (a == "--repeat") repeat = atoi(next());
        else if (a == "--out") out_path = next();
        else { fprintf(stderr, "unknown option %s\n", a.c_str()); return 64; }
    }
    llama_log_set([](ggml_log_level lvl, const char * text, void *) {
        if (lvl == GGML_LOG_LEVEL_ERROR) fputs(text, stderr);
    }, nullptr);

    std::string err;
    auto t0 = std::chrono::steady_clock::now();
    auto model = suzu::SuzuModel::load(opt, &err);
    if (!model) { fprintf(stderr, "load failed: %s\n", err.c_str()); return 1; }
    double load_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    auto cases = read_cases(cases_path);
    printf("loaded in %.0f ms, %zu cases, threads=%d share=%d incremental=%d unified=%d slots=%d mode=%s\n", load_ms,
           cases.size(), opt.n_threads, opt.share_prefix, opt.incremental, opt.kv_unified, opt.context_slots, mode.c_str());

    for (int r = 0; r < repeat; r++) {
        FILE * out = (r == 0 && !out_path.empty()) ? fopen(out_path.c_str(), "w") : nullptr;
        std::vector<double> wall, ctx_ms, cand_ms;
        long cand_tokens = 0, decode_calls = 0;
        model->reset();
        for (auto & c : cases) {
            auto ctx = model->vocab().encode_context(c.ctx, 30);
            if (ctx.empty() || c.cands.size() < 2) continue;
            std::vector<suzu::TokenSeq> cands;
            for (auto & w : c.cands) cands.push_back(model->vocab().encode_candidate(w));
            if (mode == "warm") model->prepare(ctx, nullptr);
            suzu::DecodeStats st;
            std::vector<double> sc;
            auto a = std::chrono::steady_clock::now();
            bool ok = model->score(ctx, cands, &sc, &st);
            wall.push_back(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - a).count());
            if (getenv("BENCH_SLOW") && wall.back() > atof(getenv("BENCH_SLOW")))
                printf("  slow #%s wall=%.1f ctx=%.1f(%d tok) cand=%.1f(%d tok, %d decodes) ncands=%zu\n", c.id.c_str(), wall.back(),
                       st.ctx_ms, st.ctx_tokens, st.cand_ms, st.cand_tokens, st.decode_calls, cands.size());
            ctx_ms.push_back(st.ctx_ms);
            cand_ms.push_back(st.cand_ms);
            cand_tokens += st.cand_tokens;
            decode_calls += st.decode_calls;
            if (out && ok) {
                std::vector<int> order(sc.size());
                for (size_t i = 0; i < order.size(); i++) order[i] = (int)i;
                std::stable_sort(order.begin(), order.end(), [&](int x, int y) { return sc[x] > sc[y]; });
                fprintf(out, "%s\t0\t", c.id.c_str());
                for (size_t k = 0; k < order.size(); k++)
                    fprintf(out, "%s%s:%.3f", k ? "|" : "", c.cands[order[k]].c_str(), sc[order[k]]);
                fprintf(out, "\n");
            }
        }
        if (out) fclose(out);
        printf("round %d: score wall p50=%.2f p90=%.2f p99=%.2f max=%.2f ms | ctx p50=%.2f | cand p50=%.2f p99=%.2f | cand_tokens/case=%.2f decodes/case=%.2f\n",
               r + 1, pct(wall, .5), pct(wall, .9), pct(wall, .99), pct(wall, 1), pct(ctx_ms, .5), pct(cand_ms, .5),
               pct(cand_ms, .99), (double)cand_tokens / wall.size(), (double)decode_calls / wall.size());
    }
    return 0;
}
