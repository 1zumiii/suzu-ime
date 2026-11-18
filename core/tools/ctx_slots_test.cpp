// 上文缓存（两个槽）的回归测试：直接调用 SuzuModel，检查每一步实际解码的上文字数，
// 并核对缓存路径给出的分数与清空缓存后从头计算的分数一致。
// 用法：suzu_ctx_test <suzu.gguf> <suzu.vocab.txt>
// 输出：每项一行 ok 或 FAIL，全部通过时退出码为 0。
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "llama.h"
#include "model/suzu_model.h"

using namespace suzu;

namespace {

int g_fail = 0;

void check(bool cond, const std::string & what) {
    printf("%s  %s\n", cond ? "ok  " : "FAIL", what.c_str());
    if (!cond) g_fail++;
}

// 句首符 + 编号从 first 起的 n 个字（常用字区间，内容无关紧要）
TokenSeq seq(int first, int n) {
    TokenSeq t{kBosToken};
    for (int i = 0; i < n; i++) t.push_back(first + i);
    return t;
}

TokenSeq plus(TokenSeq t, std::initializer_list<Token> more) {
    t.insert(t.end(), more);
    return t;
}

const std::vector<TokenSeq> kCands = {{20, 30}, {21, 31}, {22}, {23, 33}};

// 用一个从未缓存过任何上文的模型算出参考分数
std::vector<double> reference(SuzuModel & m, const TokenSeq & ctx) {
    m.reset();
    std::vector<double> out;
    m.score(ctx, kCands, &out, nullptr);
    m.reset();
    return out;
}

bool same(const std::vector<double> & a, const std::vector<double> & b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); i++)
        if (std::fabs(a[i] - b[i]) > 1e-4) return false;
    return true;
}

// 打分并返回本次实际解码的上文字数
int score_tokens(SuzuModel & m, const TokenSeq & ctx, std::vector<double> * out) {
    DecodeStats st;
    out->clear();
    if (!m.score(ctx, kCands, out, &st)) return -1;
    return st.ctx_tokens;
}

int prepare_tokens(SuzuModel & m, const TokenSeq & ctx) {
    DecodeStats st;
    if (!m.prepare(ctx, &st)) return -1;
    return st.ctx_tokens;
}

}  // namespace

int main(int argc, char ** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: suzu_ctx_test <suzu.gguf> <suzu.vocab.txt>\n");
        return 64;
    }
    llama_log_set([](ggml_log_level lvl, const char * text, void *) {
        if (lvl == GGML_LOG_LEVEL_ERROR) fputs(text, stderr);
    }, nullptr);
    ModelOptions opt;
    opt.model_path = argv[1];
    opt.vocab_path = argv[2];
    opt.n_threads = 2;
    std::string err;
    auto m = SuzuModel::load(opt, &err);
    if (!m) {
        printf("FAIL  load: %s\n", err.c_str());
        return 1;
    }

    // 1. 两段只共享句首符的上文交替出现（Astra 方案中的 A→B→A）：两段都应留在缓存里
    {
        TokenSeq a = seq(10, 30), b = seq(100, 8);
        auto ref_a = reference(*m, a);
        std::vector<double> sc;
        check(prepare_tokens(*m, a) == 31, "A->B->A: prepare A decodes 31 tokens");
        check(prepare_tokens(*m, b) == 8, "A->B->A: prepare B decodes its 8 tokens (BOS reused)");
        check(m->has_context(a) && m->has_context(b), "A->B->A: both A and B cached");
        check(score_tokens(*m, a, &sc) == 0, "A->B->A: score A decodes 0 context tokens");
        check(same(sc, ref_a), "A->B->A: scores of A match a fresh model");
        check(score_tokens(*m, b, &sc) == 0, "A->B->A: score B decodes 0 context tokens");
        m->reset();
    }

    // 2. 逐次上屏：每段上文是上一段加一个词，只解码新增的字
    {
        TokenSeq c = seq(10, 6);
        prepare_tokens(*m, c);
        bool inc = true;
        for (int i = 0; i < 6; i++) {
            c = plus(c, {(Token)(200 + 2 * i), (Token)(201 + 2 * i)});
            inc = inc && prepare_tokens(*m, c) == 2;
        }
        check(inc, "commit chain: each new word decodes only its 2 tokens");
        std::vector<double> sc;
        check(score_tokens(*m, c, &sc) == 0 && same(sc, reference(*m, c)), "commit chain: final scores match a fresh model");
        m->reset();
    }

    // 3. 第一阶段与第二阶段的上文交替（后者是前者加一个词）：两段都留在缓存里
    {
        TokenSeq s1 = seq(10, 20), s2 = plus(s1, {300, 301});
        prepare_tokens(*m, s1);
        check(prepare_tokens(*m, s2) == 2, "stage1/stage2: stage2 decodes only the 2 extra tokens");
        std::vector<double> sc;
        bool hits = true;
        for (int i = 0; i < 3; i++) {
            hits = hits && score_tokens(*m, s1, &sc) == 0;
            hits = hits && score_tokens(*m, s2, &sc) == 0;
        }
        check(hits, "stage1/stage2: alternating scores decode 0 context tokens");
        check(same(sc, reference(*m, s2)), "stage1/stage2: stage2 scores match a fresh model");
        m->reset();
    }

    // 4. 退格：新上文是旧上文的前缀，只重算末位
    {
        TokenSeq c = seq(10, 12);
        TokenSeq shorter(c.begin(), c.end() - 3);
        prepare_tokens(*m, c);
        std::vector<double> sc;
        check(score_tokens(*m, shorter, &sc) == 1, "backspace: shorter context re-decodes only its last token");
        check(same(sc, reference(*m, shorter)), "backspace: scores match a fresh model");
        m->reset();
    }

    // 5. 窗口滑动后的上文与旧上文只共享句首符：整段解码，但不覆盖最近使用的那段
    {
        TokenSeq x = seq(10, 30), y = seq(11, 30), z = seq(12, 30);
        prepare_tokens(*m, x);
        prepare_tokens(*m, y);
        check(m->has_context(x) && m->has_context(y), "slide: two slid contexts both cached");
        std::vector<double> sc;
        score_tokens(*m, y, &sc);              // y 最近使用
        check(prepare_tokens(*m, z) == 30, "slide: third context decodes in full except BOS");
        check(m->has_context(y) && m->has_context(z) && !m->has_context(x), "slide: least recently used context is evicted");
        check(score_tokens(*m, z, &sc) == 0 && same(sc, reference(*m, z)), "slide: scores match a fresh model");
    }

    printf(g_fail ? "%d FAILED\n" : "ALL OK\n", g_fail);
    return g_fail ? 1 : 0;
}
