#include "model/suzu_model.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <thread>

#include "ggml-cpu.h"
#include "llama.h"
#include "model/prefix_tree.h"

namespace suzu {

namespace {

double ms_since(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

// log-softmax 的归一化项：max 与 log(sum(exp(x - max)))，与旧核心同样用 double 累加
void log_normalizer(const float * logits, int n, float * m_out, double * lse_out) {
    float m = -1e30f;
    for (int k = 0; k < n; k++) if (logits[k] > m) m = logits[k];
    double se = 0;
    for (int k = 0; k < n; k++) se += std::exp((double)(logits[k] - m));
    *m_out = m;
    *lse_out = std::log(se);
}

double log_prob(const float * logits, Token target, float m, double lse) {
    return (double)(logits[target] - m) - lse;
}

// 简单的批次封装：llama_batch 需要成对释放
struct Batch {
    llama_batch b;
    explicit Batch(int n) : b(llama_batch_init(n, 0, 1)) { b.n_tokens = 0; }
    ~Batch() { llama_batch_free(b); }
    void add(Token tok, int pos, int seq, bool logits) {
        int i = b.n_tokens++;
        b.token[i] = tok;
        b.pos[i] = pos;
        b.n_seq_id[i] = 1;
        b.seq_id[i][0] = seq;
        b.logits[i] = logits ? 1 : 0;
    }
};

// 所有解码都经过这里。测量构建（SUZU_TEST_HOOKS）可按环境变量 SUZU_SLOWDOWN 的倍数拉长每次解码，
// 模拟低频设备：拉长期间工作线程照样被占着，排队与超时的结构与真慢的设备一致。
int decode(llama_context * ctx, const llama_batch & batch) {
#ifdef SUZU_TEST_HOOKS
    static const double factor = [] {
        const char * e = std::getenv("SUZU_SLOWDOWN");
        double f = e ? std::atof(e) : 1.0;
        return f > 1.0 ? f : 1.0;
    }();
    auto t0 = std::chrono::steady_clock::now();
    int rc = llama_decode(ctx, batch);
    if (factor > 1.0) std::this_thread::sleep_for((std::chrono::steady_clock::now() - t0) * (factor - 1.0));
    return rc;
#else
    return llama_decode(ctx, batch);
#endif
}

std::string meta(llama_model * m, const char * key) {
    char buf[128] = {0};
    if (llama_model_meta_val_str(m, key, buf, sizeof buf) < 0) return "";
    return buf;
}

}  // namespace

std::unique_ptr<SuzuModel> SuzuModel::load(const ModelOptions & opt, std::string * err) {
    std::unique_ptr<SuzuModel> self(new SuzuModel());
    self->opt_ = opt;
    if (!self->vocab_.load(opt.vocab_path, err)) return nullptr;

    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    mp.n_gpu_layers = 0;              // Suzu 很小，CPU 比 GPU 快（见设计说明）
    mp.use_extra_bufts = opt.repack;
    self->model_ = llama_model_load_from_file(opt.model_path.c_str(), mp);
    if (!self->model_) {
        if (err) *err = "model unreadable: " + opt.model_path;
        return nullptr;
    }
    std::string arch = meta(self->model_, "general.architecture");
    self->n_vocab_ = llama_vocab_n_tokens(llama_model_get_vocab(self->model_));
    self->n_ctx_train_ = llama_model_n_ctx_train(self->model_);
    if (arch != "gpt2") {
        if (err) *err = "not a Suzu model (architecture " + arch + ")";
        return nullptr;
    }
    // 护栏：词表行数必须等于模型的词表大小，否则说明模型与词表不配套，打分毫无意义
    if ((size_t)self->n_vocab_ != self->vocab_.size()) {
        if (err) *err = "vocab has " + std::to_string(self->vocab_.size()) + " entries but model has " + std::to_string(self->n_vocab_);
        return nullptr;
    }

    int slots = std::max(1, opt.context_slots);
    int cands = self->seq_pool();
    int seq_max = slots + cands;
    // 每条上文至多 n_ctx_train 格；候选路径每条至多 candidate_max_chars 格
    int cells = slots * self->n_ctx_train_ + cands * opt.candidate_max_chars;
    llama_context_params cp = llama_context_default_params();
    cp.n_seq_max = (uint32_t)seq_max;
    cp.kv_unified = opt.kv_unified;
    // 非共享存储时每条序列独占 n_ctx / n_seq_max 格，必须容得下一整条上文加候选
    cp.n_ctx = opt.kv_unified ? (uint32_t)((cells + 255) / 256 * 256)
                              : (uint32_t)(seq_max * (self->n_ctx_train_ + opt.candidate_max_chars));
    // llama.cpp 要求输出缓冲（n_batch）不小于序列数：候选生成的序列池较大时 seq_max 可能超过 n_ctx_train
    cp.n_batch = (uint32_t)std::max(self->n_ctx_train_, seq_max);
    cp.n_ubatch = cp.n_batch;
    cp.n_threads = opt.n_threads;
    cp.n_threads_batch = opt.n_threads;
    cp.no_perf = true;
    self->ctx_ = llama_init_from_model(self->model_, cp);
    if (!self->ctx_) {
        if (err) *err = "cannot create context";
        return nullptr;
    }

    if (opt.persistent_threads) {
        // llama.cpp 不给线程池时，每次解码都临时创建并回收 n_threads 个线程；常驻的线程池省掉这笔开销与调度抖动
        ggml_threadpool_params tp = ggml_threadpool_params_default(opt.n_threads);
        tp.poll = (uint32_t)std::max(0, std::min(100, opt.poll));
        self->pool_ = ggml_threadpool_new(&tp);
        if (self->pool_) llama_attach_threadpool(self->ctx_, self->pool_, self->pool_);
    }

    self->slots_.resize((size_t)slots);
    for (int i = 0; i < slots; i++) self->slots_[i].seq = i;
    for (int s = seq_max - 1; s >= slots; s--) self->free_seqs_.push_back(s);
    return self;
}

SuzuModel::~SuzuModel() {
    if (ctx_) {
        if (pool_) llama_detach_threadpool(ctx_);
        llama_free(ctx_);
    }
    if (pool_) ggml_threadpool_free(pool_);
    if (model_) llama_model_free(model_);
}

int SuzuModel::acquire_seq() {
    if (free_seqs_.empty()) return -1;
    int s = free_seqs_.back();
    free_seqs_.pop_back();
    return s;
}

void SuzuModel::release_seq(int seq) {
    llama_memory_seq_rm(llama_get_memory(ctx_), seq, -1, -1);
    free_seqs_.push_back(seq);
}

void SuzuModel::reset() {
    llama_memory_clear(llama_get_memory(ctx_), false);
    for (auto & s : slots_) { s.valid = false; s.tokens.clear(); s.logits.clear(); }
    int slots = (int)slots_.size();
    int seq_max = slots + seq_pool();
    free_seqs_.clear();
    for (int s = seq_max - 1; s >= slots; s--) free_seqs_.push_back(s);
}

bool SuzuModel::has_context(const TokenSeq & ctx) const {
    for (auto & s : slots_) if (s.valid && s.tokens == ctx) return true;
    return false;
}

int SuzuModel::ensure_context(const TokenSeq & ctx, DecodeStats * st) {
    if (ctx.empty() || (int)ctx.size() > n_ctx_train_) return -1;
    tick_++;
    // 1. 完全命中
    for (size_t i = 0; i < slots_.size(); i++) {
        if (slots_[i].valid && slots_[i].tokens == ctx) {
            slots_[i].last_use = tick_;
            if (st) st->ctx_hit = true;
            return (int)i;
        }
    }
    // 2. 来源与目标分开选。
    //    来源：与新上文共享前缀最长的有效槽。绝对位置编码：只有从位置 0 起相同的前缀才能复用
    //    （上文超过 30 字后窗口滑动，前缀从第 1 个字起就变了，通常只剩句首符可复用）。
    //    目标：最久没用的槽（空槽优先），所以最近使用的那段上文不会被覆盖。旧做法直接改写来源槽，
    //    两段只共享句首符的上文（例如第一阶段与第二阶段的上文）交替出现时，每次都要整段重算。
    //    目标就是来源时原地增量。不是同一个槽时，先清空目标，再把公共前缀的 KV 复制过去（共享存储下只是打标记，不搬数据）。
    int src = -1;
    size_t src_keep = 0;
    if (opt_.incremental) {
        for (size_t i = 0; i < slots_.size(); i++) {
            if (!slots_[i].valid) continue;
            const TokenSeq & t = slots_[i].tokens;
            size_t k = 0;
            while (k < t.size() && k < ctx.size() && t[k] == ctx[k]) k++;
            if (k > src_keep) { src_keep = k; src = (int)i; }
        }
    }
    int best = 0;
    for (size_t i = 1; i < slots_.size(); i++) {
        const auto & a = slots_[i], & b = slots_[best];
        if (!a.valid && b.valid) best = (int)i;
        else if (a.valid == b.valid && a.last_use < b.last_use) best = (int)i;
    }
    // 末位要重新解码才拿得到它的 logits（新上文是旧上文的前缀时，例如退格）
    size_t keep = src < 0 ? 0 : std::min(src_keep, ctx.size() - 1);
    ContextSlot & slot = slots_[best];
    auto * mem = llama_get_memory(ctx_);
    if (src == best) {
        llama_memory_seq_rm(mem, slot.seq, (llama_pos)keep, -1);
    } else {
        llama_memory_seq_rm(mem, slot.seq, -1, -1);
        if (keep > 0) llama_memory_seq_cp(mem, slots_[src].seq, slot.seq, 0, (llama_pos)keep);
    }
    slot.valid = false;

    auto t0 = std::chrono::steady_clock::now();
    Batch batch((int)(ctx.size() - keep));
    for (size_t j = keep; j < ctx.size(); j++) batch.add(ctx[j], (int)j, slot.seq, j + 1 == ctx.size());
    int rc = decode(ctx_, batch.b);
    if (st) {
        st->ctx_ms += ms_since(t0);
        st->ctx_tokens += (int)(ctx.size() - keep);
        st->decode_calls++;
        st->ctx_partial = keep > 1;   // 只复用句首符的算整段解码
    }
    float * lg = rc == 0 ? llama_get_logits_ith(ctx_, batch.b.n_tokens - 1) : nullptr;
    if (!lg) {
        llama_memory_seq_rm(mem, slot.seq, -1, -1);
        slot.tokens.clear();
        return -1;
    }
    slot.tokens = ctx;
    slot.logits.assign(lg, lg + n_vocab_);
    slot.valid = true;
    slot.last_use = tick_;
    return best;
}

bool SuzuModel::prepare(const TokenSeq & ctx, DecodeStats * st) {
    return ensure_context(ctx, st) >= 0;
}

bool SuzuModel::score(const TokenSeq & ctx, const std::vector<TokenSeq> & cands, std::vector<double> * out,
                      DecodeStats * st) {
    out->assign(cands.size(), -1e10);
    if (cands.empty()) return true;
    if ((int)cands.size() > opt_.max_candidates) return false;
    int si = ensure_context(ctx, st);
    if (si < 0) return false;
    const ContextSlot & slot = slots_[si];
    const int ctx_len = (int)ctx.size();
    const int max_chars = std::min(opt_.candidate_max_chars, n_ctx_train_ - ctx_len);
    if (max_chars < 1) return false;

    PrefixTree tree(cands, max_chars, opt_.share_prefix);
    const auto & nodes = tree.nodes();
    std::vector<double> lp(nodes.size(), 0.0);   // 从根到该节点的对数概率和

    // 第 1 个字：直接用上文末位的 logits
    {
        float m; double lse;
        log_normalizer(slot.logits.data(), n_vocab_, &m, &lse);
        for (int ch : nodes[0].children) lp[ch] = log_prob(slot.logits.data(), nodes[ch].token, m, lse);
    }

    // 逐层：有子节点的节点需要送进模型，拿到它之后一个字的分布。
    // 每个待解码节点占一条序列：同一父节点的第一个子节点沿用父节点的序列，其余的从父节点复制（共享存储时只是打标记）。
    struct Pending { int node; int seq; };
    std::vector<Pending> frontier;
    auto * mem = llama_get_memory(ctx_);
    bool ok = true;
    for (int ch : nodes[0].children) {
        if (nodes[ch].children.empty()) continue;
        int s = acquire_seq();
        if (s < 0) { ok = false; break; }
        llama_memory_seq_cp(mem, slot.seq, s, -1, -1);
        frontier.push_back({ch, s});
    }

    auto t0 = std::chrono::steady_clock::now();
    while (ok && !frontier.empty()) {
        Batch batch((int)frontier.size());
        for (auto & p : frontier) batch.add(nodes[p.node].token, ctx_len + nodes[p.node].depth - 1, p.seq, true);
        int rc = decode(ctx_, batch.b);
        if (st) { st->decode_calls++; st->cand_tokens += (int)frontier.size(); }
        if (rc != 0) { ok = false; break; }

        std::vector<Pending> next;
        for (size_t k = 0; k < frontier.size() && ok; k++) {
            const Pending & p = frontier[k];
            const float * lg = llama_get_logits_ith(ctx_, (int)k);
            if (!lg) { ok = false; break; }
            float m; double lse;
            log_normalizer(lg, n_vocab_, &m, &lse);
            bool seq_taken = false;
            for (int ch : nodes[p.node].children) {
                lp[ch] = lp[p.node] + log_prob(lg, nodes[ch].token, m, lse);
                if (nodes[ch].children.empty()) continue;
                if (!seq_taken) { next.push_back({ch, p.seq}); seq_taken = true; continue; }
                int s = acquire_seq();
                if (s < 0) { ok = false; break; }
                llama_memory_seq_cp(mem, p.seq, s, -1, -1);   // 在追加子节点之前复制，副本里只有到 p 为止的路径
                next.push_back({ch, s});
            }
            if (!seq_taken) release_seq(p.seq);
        }
        if (!ok) break;   // 用到的序列由下面的 reset() 统一归还
        frontier.swap(next);
    }
    if (st) st->cand_ms += ms_since(t0);

    if (!ok) {
        reset();   // 失败自愈：清掉全部 KV 与上文缓存，下一次从头解码
        return false;
    }
    for (size_t i = 1; i < nodes.size(); i++)
        for (int ci : nodes[i].ends_here) (*out)[ci] = lp[i];
    return true;
}

bool SuzuModel::generate(const TokenSeq & ctx, const std::vector<TokenSeq> & words, int beam, std::vector<double> * out,
                         DecodeStats * st) {
    out->assign(words.size(), kNotReached);
    if (words.empty()) return true;
    beam = std::min(beam, opt_.gen_beam);
    if (beam < 1) return false;
    int si = ensure_context(ctx, st);
    if (si < 0) return false;
    const ContextSlot & slot = slots_[si];
    const int ctx_len = (int)ctx.size();
    const int max_chars = std::min(opt_.candidate_max_chars, n_ctx_train_ - ctx_len);
    if (max_chars < 1) return false;

    PrefixTree tree(words, max_chars, true);
    const auto & nodes = tree.nodes();
    std::vector<double> lp(nodes.size(), kNotReached);
    lp[0] = 0;

    // 在有子节点的候选节点里取对数概率最高的 beam 个
    auto pick = [&](std::vector<int> cand) {
        size_t k = std::min(cand.size(), (size_t)beam);
        std::partial_sort(cand.begin(), cand.begin() + (long)k, cand.end(), [&](int a, int b) { return lp[a] > lp[b]; });
        cand.resize(k);
        return cand;
    };

    {   // 第 1 个字：上文末位的 logits 已在缓存里，不需要解码
        float m; double lse;
        log_normalizer(slot.logits.data(), n_vocab_, &m, &lse);
        for (int ch : nodes[0].children) lp[ch] = log_prob(slot.logits.data(), nodes[ch].token, m, lse);
    }
    struct Pending { int node; int seq; };
    std::vector<Pending> frontier;
    auto * mem = llama_get_memory(ctx_);
    bool ok = true;
    {
        std::vector<int> cand;
        for (int ch : nodes[0].children) if (!nodes[ch].children.empty()) cand.push_back(ch);
        for (int n : pick(cand)) {
            int s = acquire_seq();
            if (s < 0) { ok = false; break; }
            llama_memory_seq_cp(mem, slot.seq, s, -1, -1);
            frontier.push_back({n, s});
        }
    }

    auto t0 = std::chrono::steady_clock::now();
    while (ok && !frontier.empty()) {
        Batch batch((int)frontier.size());
        for (auto & p : frontier) batch.add(nodes[p.node].token, ctx_len + nodes[p.node].depth - 1, p.seq, true);
        int rc = decode(ctx_, batch.b);
        if (st) { st->decode_calls++; st->cand_tokens += (int)frontier.size(); }
        if (rc != 0) { ok = false; break; }

        std::vector<int> cand;
        std::vector<int> parent_seq(nodes.size(), -1);
        for (size_t k = 0; k < frontier.size(); k++) {
            const Pending & p = frontier[k];
            const float * lg = llama_get_logits_ith(ctx_, (int)k);
            if (!lg) { ok = false; break; }
            float m; double lse;
            log_normalizer(lg, n_vocab_, &m, &lse);
            for (int ch : nodes[p.node].children) {
                lp[ch] = lp[p.node] + log_prob(lg, nodes[ch].token, m, lse);
                if (!nodes[ch].children.empty()) { cand.push_back(ch); parent_seq[ch] = p.seq; }
            }
        }
        if (!ok) break;
        // 下一层：先从父节点的序列复制出新序列，再归还这一层的序列
        std::vector<Pending> next;
        for (int n : pick(cand)) {
            int s = acquire_seq();
            if (s < 0) { ok = false; break; }
            llama_memory_seq_cp(mem, parent_seq[n], s, -1, -1);
            next.push_back({n, s});
        }
        if (!ok) break;
        for (auto & p : frontier) release_seq(p.seq);
        frontier.swap(next);
    }
    if (st) st->cand_ms += ms_since(t0);

    if (!ok) {
        reset();
        return false;
    }
    for (size_t i = 1; i < nodes.size(); i++)
        if (lp[i] > kNotReached)
            for (int wi : nodes[i].ends_here) (*out)[wi] = lp[i];
    return true;
}

bool SuzuModel::score_generate(const TokenSeq & ctx, const std::vector<TokenSeq> & cands, const std::vector<TokenSeq> & words,
                               int beam, std::vector<double> * score_out, std::vector<double> * gen_out, DecodeStats * st) {
    beam = std::min(beam, opt_.gen_beam);
    // 共享解码依赖前缀合并；对照测量（share_prefix = false）或缺一方时，分开计算
    auto separate = [&] {
        return score(ctx, cands, score_out, st) && generate(ctx, words, beam, gen_out, st);
    };
    if (cands.empty() || words.empty() || beam < 1 || !opt_.share_prefix) return separate();
    score_out->assign(cands.size(), -1e10);
    gen_out->assign(words.size(), kNotReached);
    if ((int)cands.size() > opt_.max_candidates) return false;
    int si = ensure_context(ctx, st);
    if (si < 0) return false;
    const ContextSlot & slot = slots_[si];
    const int ctx_len = (int)ctx.size();
    const int max_chars = std::min(opt_.candidate_max_chars, n_ctx_train_ - ctx_len);
    if (max_chars < 1) return false;

    // 原生候选在前、生成词在后，建一棵树。nat：在某个原生候选的路径上；wrd：在某个生成词的路径上
    const int nc = (int)cands.size();
    std::vector<TokenSeq> all(cands);
    all.insert(all.end(), words.begin(), words.end());
    PrefixTree tree(all, max_chars, true);
    const auto & nodes = tree.nodes();
    const size_t N = nodes.size();
    std::vector<char> nat(N, 0), wrd(N, 0), nch(N, 0), wch(N, 0);
    for (size_t i = 1; i < N; i++)
        for (int ci : nodes[i].ends_here)
            for (int n = (int)i; n > 0 && !(ci < nc ? nat[n] : wrd[n]); n = nodes[n].parent) (ci < nc ? nat[n] : wrd[n]) = 1;
    for (size_t i = 1; i < N; i++) {
        int p = nodes[i].parent;
        if (nat[i]) nch[p] = 1;   // 原生打分要解码 p（单独 score 时 p 在原生树里有子节点）
        if (wrd[i]) wch[p] = 1;   // 生成可以展开 p（单独 generate 时 p 在生成树里有子节点）
    }
    std::vector<double> lp(N, kNotReached);
    std::vector<char> greach(N, 0);   // 生成可达：根的子节点，或被生成展开过的节点的子节点
    lp[0] = 0;
    {
        float m; double lse;
        log_normalizer(slot.logits.data(), n_vocab_, &m, &lse);
        for (int ch : nodes[0].children) { lp[ch] = log_prob(slot.logits.data(), nodes[ch].token, m, lse); greach[ch] = 1; }
    }
    auto pick = [&](std::vector<int> cand) {   // 与 generate 相同：取对数概率最高的 beam 个
        size_t k = std::min(cand.size(), (size_t)beam);
        std::partial_sort(cand.begin(), cand.begin() + (long)k, cand.end(), [&](int a, int b) { return lp[a] > lp[b]; });
        cand.resize(k);
        return cand;
    };

    // 每层待解码 = 原生需要的节点 ∪ 生成选中的节点；同一个节点只解码一次，输出供两路取下一字的分布
    struct Pending { int node; int seq; bool gen; };
    std::vector<Pending> frontier;
    auto * mem = llama_get_memory(ctx_);
    bool ok = true, short_of_seqs = false;
    // 按父节点分配序列：第一个子节点沿用父节点的序列，其余从父节点复制（在追加子节点之前复制）
    auto build_next = [&](const std::vector<int> & want, const std::vector<char> & is_gen, const std::vector<int> & parent_seq,
                          std::vector<Pending> * next) {
        std::vector<char> taken(N, 0);   // 按父节点下标：父序列是否已被某个子节点沿用
        for (int n : want) {
            int p = nodes[n].parent;
            int ps = parent_seq[p];
            if (!taken[p] && p != 0) { taken[p] = 1; next->push_back({n, ps, is_gen[n] != 0}); continue; }
            int s = acquire_seq();
            if (s < 0) { short_of_seqs = true; return false; }
            llama_memory_seq_cp(mem, ps, s, -1, -1);
            next->push_back({n, s, is_gen[n] != 0});
        }
        return true;
    };
    std::vector<int> parent_seq(N, -1);
    parent_seq[0] = slot.seq;
    {
        std::vector<int> gc;
        for (int ch : nodes[0].children) if (wrd[ch] && wch[ch]) gc.push_back(ch);
        std::vector<char> is_gen(N, 0);
        for (int n : pick(gc)) is_gen[n] = 1;
        std::vector<int> want;
        for (int ch : nodes[0].children) if ((nat[ch] && nch[ch]) || is_gen[ch]) want.push_back(ch);
        ok = build_next(want, is_gen, parent_seq, &frontier);
    }

    auto t0 = std::chrono::steady_clock::now();
    while (ok && !frontier.empty()) {
        Batch batch((int)frontier.size());
        for (auto & p : frontier) batch.add(nodes[p.node].token, ctx_len + nodes[p.node].depth - 1, p.seq, true);
        int rc = decode(ctx_, batch.b);
        if (st) { st->decode_calls++; st->cand_tokens += (int)frontier.size(); }
        if (rc != 0) { ok = false; break; }

        std::vector<int> gc, want;
        for (size_t k = 0; k < frontier.size(); k++) {
            const Pending & p = frontier[k];
            const float * lg = llama_get_logits_ith(ctx_, (int)k);
            if (!lg) { ok = false; break; }
            float m; double lse;
            log_normalizer(lg, n_vocab_, &m, &lse);
            parent_seq[p.node] = p.seq;
            for (int ch : nodes[p.node].children) {
                lp[ch] = lp[p.node] + log_prob(lg, nodes[ch].token, m, lse);
                if (p.gen) {
                    greach[ch] = 1;
                    if (wrd[ch] && wch[ch]) gc.push_back(ch);
                }
            }
        }
        if (!ok) break;
        std::vector<char> is_gen(N, 0);
        for (int n : pick(gc)) is_gen[n] = 1;
        for (auto & p : frontier)
            for (int ch : nodes[p.node].children)
                if ((nat[ch] && nch[ch]) || is_gen[ch]) want.push_back(ch);
        std::vector<Pending> next;
        ok = build_next(want, is_gen, parent_seq, &next);
        if (!ok) break;
        // 没有子节点沿用的父序列：归还
        std::vector<char> kept(N, 0);
        for (int n : want) kept[nodes[n].parent] = 1;
        for (auto & p : frontier) if (!kept[p.node]) release_seq(p.seq);
        frontier.swap(next);
    }
    if (st) st->cand_ms += ms_since(t0);

    if (!ok) {
        reset();   // 用到的序列与 KV 一并清掉
        // 序列池不够（不该发生：池按“候选上限 + 2 × 束宽”分配）：退回分开计算
        return short_of_seqs ? separate() : false;
    }
    for (size_t i = 1; i < N; i++)
        for (int ci : nodes[i].ends_here) {
            if (ci < nc) (*score_out)[ci] = lp[i];
            else if (greach[i] && lp[i] > kNotReached) (*gen_out)[ci - nc] = lp[i];
        }
    return true;
}

}  // namespace suzu
