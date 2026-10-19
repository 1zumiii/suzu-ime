// Suzu 模型的推理层：封装 llama.cpp，提供“上文预解码”和“候选打分”两个操作。
//
// 这一层是单线程的、不加锁：同一时刻只能有一个调用者（由 runtime/ 的工作线程独占）。
// 上下层分工：本层只关心算得对、算得省；什么时候算、等多久、算不完怎么办，由 runtime/ 决定。
//
// 打分口径（与旧核心一致）：输入为 <bos> + 上文最后至多 30 个字 + 候选各字；
// 候选得分 = 各字对数概率之和（每个候选至多前 12 个字），越大越优。
//
// 省计算的两处做法：
//  - 上文缓存（ContextSlot）：保留若干条上文序列的 KV。新上文与某条缓存共享前缀时只解码新增的字；
//    第一阶段的上文与第二阶段的上文各占一条，互不覆盖。
//  - 候选前缀树（PrefixTree）：首字相同的候选共享解码。
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "model/vocab.h"

struct llama_model;
struct llama_context;
struct ggml_threadpool;

namespace suzu {

struct ModelOptions {
    std::string model_path;
    std::string vocab_path;
    int n_threads = 4;
    int max_candidates = 11;     // 一次最多打分的候选数
    int context_slots = 2;       // 缓存几条上文
    int candidate_max_chars = 12;
    bool repack = false;         // llama.cpp 的 CPU 权重重排（更快一些，但权重变成私有内存）
    // 以下用于对照测量，正常使用保持默认
    bool share_prefix = true;    // false = 每个候选单独解码（旧核心的做法）
    bool incremental = true;     // false = 上文每次从头解码
    bool kv_unified = true;      // KV 缓存在各序列间共享存储（序列复制只是打标记，不搬数据）
    bool persistent_threads = true;   // 常驻线程池；false = llama.cpp 默认，每次解码临时建线程再销毁
    int poll = 0;                // 常驻线程池做完一批后自旋等待的程度（0~100）。0 = 立即睡眠：省电，实测延迟不变
};

// 一次操作的计时与计数（只含数字）
struct DecodeStats {
    double ctx_ms = 0;           // 上文解码耗时（命中缓存为 0）
    double cand_ms = 0;          // 候选解码耗时
    int ctx_tokens = 0;          // 本次实际解码的上文字数
    int cand_tokens = 0;         // 本次实际解码的候选字数
    int decode_calls = 0;
    bool ctx_hit = false;        // 上文完全命中缓存
    bool ctx_partial = false;    // 上文部分命中（只解码了新增的字）
};

class SuzuModel {
public:
    // 加载模型与词表。失败返回空指针，err 里写原因（只含路径与数字）。
    static std::unique_ptr<SuzuModel> load(const ModelOptions & opt, std::string * err);
    ~SuzuModel();
    SuzuModel(const SuzuModel &) = delete;
    SuzuModel & operator=(const SuzuModel &) = delete;

    const Vocab & vocab() const { return vocab_; }
    const ModelOptions & options() const { return opt_; }
    int n_ctx_train() const { return n_ctx_train_; }

    // 确保这条上文的 KV 与末位 logits 已在缓存里。ctx 须为 Vocab::encode_context 的结果（非空）。
    bool prepare(const TokenSeq & ctx, DecodeStats * st);

    // 已缓存（完全命中）的上文
    bool has_context(const TokenSeq & ctx) const;

    // 给候选打分：out[i] 为候选 i 的对数概率和。失败返回 false（缓存会被清空，下次自愈）。
    bool score(const TokenSeq & ctx, const std::vector<TokenSeq> & cands, std::vector<double> * out, DecodeStats * st);

    // 丢弃全部缓存的上文
    void reset();

private:
    SuzuModel() = default;

    struct ContextSlot {
        int seq = 0;
        TokenSeq tokens;
        std::vector<float> logits;   // 上文末位的 logits
        uint64_t last_use = 0;
        bool valid = false;
    };

    int ensure_context(const TokenSeq & ctx, DecodeStats * st);   // 返回槽位下标，失败 -1
    int acquire_seq();
    void release_seq(int seq);

    ModelOptions opt_;
    Vocab vocab_;
    llama_model * model_ = nullptr;
    llama_context * ctx_ = nullptr;
    ggml_threadpool * pool_ = nullptr;
    int n_vocab_ = 0;
    int n_ctx_train_ = 0;
    std::vector<ContextSlot> slots_;
    std::vector<int> free_seqs_;
    uint64_t tick_ = 0;
};

}  // namespace suzu
