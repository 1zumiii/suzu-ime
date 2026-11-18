// 统计：计数与延迟分布（只含数字）。用于状态查询、日志与测量报告。
// 非线程安全，由 Engine 在自己的锁内更新与读取。
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace suzu {

// 延迟分布：累计次数、总和、最大值，加最近 kWindow 个样本（用来算中位数与 p99）
class LatencyStats {
public:
    static constexpr size_t kWindow = 2048;
    void add(double ms);
    uint64_t count() const { return count_; }
    double max() const { return max_; }
    double mean() const { return count_ ? sum_ / (double)count_ : 0.0; }
    double percentile(double p) const;     // 基于最近的窗口
    std::string json() const;              // {"n":..,"mean":..,"p50":..,"p99":..,"max":..}

private:
    std::vector<float> ring_;
    size_t next_ = 0;
    uint64_t count_ = 0;
    double sum_ = 0, max_ = 0;
};

struct EngineStats {
    // 打分请求（score 调用）的去向
    uint64_t requests = 0;        // 全部 score 调用
    uint64_t ok = 0;              // 拿到结果（含命中结果缓存）
    uint64_t cache_hits = 0;
    uint64_t skip_timeout = 0;    // 在时限内没算完（结果会进缓存，下一次按键可直接用）
    uint64_t skip_loading = 0;    // 模型还在加载
    uint64_t skip_no_model = 0;   // 没有模型文件、加载失败或未配置
    uint64_t skip_off = 0;        // 手动关闭
    uint64_t skip_error = 0;      // 推理出错（已自愈）
    uint64_t no_context = 0;      // 上文不足 2 个字：不打分，不算跳过
    uint64_t few_candidates = 0;  // 候选不足 2 个：不打分，不算跳过
    uint64_t gen_requests = 0;    // 候选生成的调用（不计入上面的 requests）
    uint64_t gen_ok = 0;
    uint64_t gen_timeout = 0;
    uint64_t shared_jobs = 0;     // score_gen 合成一个任务、共用一次解码的次数
    uint64_t cand_tokens = 0, decode_calls = 0;   // 工作线程打分与生成送进模型的候选字数、解码调用次数（累计）
    uint64_t sg_requests = 0;     // 第一阶段合并请求（score_gen）的调用；其中的打分与生成照常计入上面两类
    // 工作线程
    uint64_t prepares = 0, prepare_done = 0;
    uint64_t jobs_done = 0, jobs_dropped = 0;   // 被放弃（等待方已超时且有更新的请求）的任务
    uint64_t loads = 0, load_failures = 0, unloads = 0, decode_failures = 0;
    // 打分与生成任务里上文的命中情况。hit：完全命中。partial：复用了句首符以外的前缀，只解码新增的字。full：整段解码（最多复用句首符）。
    // full_long：full 里解码不少于 20 个字的次数（窗口满后滑动等，手机亮屏时每次约 32 ms）
    uint64_t ctx_hit = 0, ctx_partial = 0, ctx_full = 0, ctx_full_long = 0;
    uint64_t prep_hit = 0, prep_partial = 0, prep_full = 0;   // 预解码自身的同口径计数
    // 打分与生成任务没有完全命中上文时的归因（任务开始时判定，见 Engine::classify_miss_locked）。
    // n：次数。long_n：其中解码不少于 20 个字的次数。ms：这些任务的上文解码耗时之和
    struct MissCause { uint64_t n = 0, long_n = 0; double ms = 0; };
    enum MissKind { kMissNoPrepare, kMissOtherCtx, kMissQueued, kMissReplaced, kMissEvicted, kMissKinds };
    MissCause ctx_miss[kMissKinds];
    uint64_t ctx_miss_late = 0;   // no_prepare 或 other_ctx 之后 2 秒内，同一段上文的预解码才到达
    LatencyStats wait_ms;         // 调用方（输入法线程）等待的墙钟时间：score 调用从进入到返回
    LatencyStats queue_ms;        // 任务排队：提交到工作线程开始处理
    LatencyStats compute_ms;      // 工作线程处理一个打分任务的时间
    LatencyStats prepare_ms;      // 工作线程处理一次预解码的时间
    double last_load_ms = 0;

    uint64_t skipped() const { return skip_timeout + skip_loading + skip_no_model + skip_off + skip_error; }
    std::string json() const;     // 不含外层花括号的字段列表
};

}  // namespace suzu
