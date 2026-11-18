// 打分与生成共用一次解码（SuzuModel::score_generate）的回归测试：随机构造上文、原生候选与生成词（前缀大量重叠），
// 逐组核对结果与分别调用 score、generate 相同：生成词是否被展开到（束宽边缘近乎同分时会换位，不一致的不超过 1%），原生分数与展开到的生成分数
// （差值不超过 kTol：llama.cpp 的结果随同批的其他序列浮动，共享解码改变了批量组成）。另报两种做法送进模型的字数。
// 用法：suzu_sg_test <suzu.gguf> <suzu.vocab.txt> [组数，默认 300]
// 输出：汇总一行，全部一致时退出码为 0。
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <string>
#include <vector>

#include "llama.h"
#include "model/suzu_model.h"

using namespace suzu;

int main(int argc, char ** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: suzu_sg_test <suzu.gguf> <suzu.vocab.txt> [trials]\n");
        return 64;
    }
    int trials = argc > 3 ? atoi(argv[3]) : 300;
    llama_log_set([](ggml_log_level lvl, const char * text, void *) {
        if (lvl == GGML_LOG_LEVEL_ERROR) fputs(text, stderr);
    }, nullptr);
    ModelOptions opt;
    opt.model_path = argv[1];
    opt.vocab_path = argv[2];
    opt.n_threads = 2;
    opt.max_candidates = 11;
    opt.gen_beam = 10;
    std::string err;
    auto m = SuzuModel::load(opt, &err);
    if (!m) { printf("FAIL  load: %s\n", err.c_str()); return 1; }

    std::mt19937 rng(20261118);
    auto rnd = [&](int lo, int hi) { return std::uniform_int_distribution<int>(lo, hi)(rng); };
    int bad_score = 0, bad_reach = 0, bad_gen = 0, failed = 0, reached = 0;
    double max_diff = 0, pad_diff = 0;
    const double kTol = 0.15;   // 批量组成引起的浮动实测 0.04～0.06；repack 的最大分差为 0.18
    long tok_sep = 0, tok_shared = 0;
    for (int t = 0; t < trials; t++) {
        TokenSeq ctx{kBosToken};
        for (int i = rnd(2, 30); i > 0; i--) ctx.push_back(rnd(10, 2000));
        // 首字从一个小集合里取，制造原生候选与生成词之间、各自内部的共享前缀
        std::vector<Token> heads;
        for (int i = rnd(2, 6); i > 0; i--) heads.push_back(rnd(10, 600));
        auto word = [&](int lo, int hi) {
            TokenSeq w{heads[rnd(0, (int)heads.size() - 1)]};
            for (int i = rnd(lo, hi) - 1; i > 0; i--) w.push_back(rnd(10, 300));
            return w;
        };
        std::vector<TokenSeq> cands, words;
        for (int i = rnd(2, 11); i > 0; i--) cands.push_back(word(1, 4));
        for (int i = rnd(1, 80); i > 0; i--) words.push_back(word(2, 3));
        if (rnd(0, 3) == 0) words.push_back(cands[0]);   // 原生候选与生成词完全相同
        int beam = (int[]){1, 3, 10}[rnd(0, 2)];

        std::vector<double> s1, g1, s2, g2;
        DecodeStats a, b;
        m->reset();
        bool ok1 = m->score(ctx, cands, &s1, &a) && m->generate(ctx, words, beam, &g1, &a);
        m->reset();
        bool ok2 = m->score_generate(ctx, cands, words, beam, &s2, &g2, &b);
        // 参照量：只打分，混入无关候选（批量变大）后原候选分数的变化。llama.cpp 的结果随同批的其他序列有微小浮动，
        // 共享解码改变了批量组成，分数差应与它同一量级
        {
            std::vector<TokenSeq> padded(cands);
            while (padded.size() < 11) padded.push_back({(Token)rnd(1000, 1900), (Token)rnd(10, 300), (Token)rnd(10, 300)});
            std::vector<double> sp;
            m->reset();
            if (m->score(ctx, padded, &sp, nullptr))
                for (size_t i = 0; i < s1.size(); i++) pad_diff = std::max(pad_diff, std::fabs(s1[i] - sp[i]));
        }
        if (!ok1 || !ok2) { failed++; continue; }
        tok_sep += a.cand_tokens;
        tok_shared += b.cand_tokens;
        for (size_t i = 0; i < s1.size(); i++) {
            double d = std::fabs(s1[i] - s2[i]);
            max_diff = std::max(max_diff, d);
            if (d > kTol) bad_score++;
        }
        for (size_t i = 0; i < g1.size(); i++) {
            bool r1 = g1[i] > SuzuModel::kNotReached, r2 = g2[i] > SuzuModel::kNotReached;
            if (r1 != r2) { bad_reach++; continue; }
            if (!r1) continue;
            reached++;
            double d = std::fabs(g1[i] - g2[i]);
            max_diff = std::max(max_diff, d);
            if (d > kTol) bad_gen++;
        }
    }
    // 束宽边缘有分数几乎相同的节点时，批量浮动会让它们换位，展开到的词随之不同：只允许极少数
    bool pass = !bad_score && bad_reach * 100 <= reached && !bad_gen && !failed;
    printf("%s  %d trials: score mismatches %d, reach mismatches %d, gen mismatches %d (reached %d), failures %d, "
           "max diff %.3f (padding-only reference %.3f, tolerance %.2f); candidate tokens decoded %ld separate vs %ld shared (%.1f%% fewer)\n",
           pass ? "ok  " : "FAIL", trials, bad_score, bad_reach, bad_gen, reached, failed, max_diff, pad_diff, kTol, tok_sep, tok_shared,
           tok_sep ? 100.0 * (tok_sep - tok_shared) / tok_sep : 0.0);
    return pass ? 0 : 1;
}
