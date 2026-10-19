// 调度层：一个独占模型的工作线程，外加带时限的请求接口。
//
// 线程模型
//   - 模型（SuzuModel）只在工作线程上创建、使用和释放，别的线程从不碰它，因此没有模型锁，也没有忙等。
//   - 调用方（输入法线程、守护进程的连接线程）只做三件事：查结果缓存、提交任务、在时限内等待。
//     Engine 的锁只保护队列与状态，持锁时间是微秒级，工作线程计算时不持锁。
//   - 每个调用都有明确的上限：prepare / is_ready / touch / status 立即返回；score 最多等 budget_ms。
//     超时的打分任务继续算完，结果进缓存，下一次同样的请求直接命中。
//
// 任务优先级：配置变更（卸载/重载）> 加载 > 打分（先来先算；等待方已放弃且后面还有新请求的直接丢弃）> 预解码（只留最新一个）。
// 模型的生命周期：按需加载（第一次 prepare / score 时）；可选的空闲卸载由工作线程自己计时完成。
#pragma once

#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "model/suzu_model.h"
#include "runtime/result_cache.h"
#include "runtime/stats.h"

namespace suzu {

enum class ScoreStatus {
    Ok,
    NoModel,         // 未配置模型、文件缺失或加载失败
    Loading,         // 正在加载（或刚触发加载）
    Disabled,        // 手动关闭
    NoContext,       // 上文不足 2 个字
    FewCandidates,   // 候选不足 2 个
    Timeout,         // 时限内没算完
    Error,           // 推理出错
};

// 返回给 Lua 的原因码（Ok 为空串）
const char * score_reason(ScoreStatus s);

struct ScoreResult {
    ScoreStatus status = ScoreStatus::Error;
    std::vector<std::string> cands;    // 实际参与打分的候选（超过上限的被截掉）
    std::vector<double> scores;        // 与 cands 对齐
    bool cache_hit = false;
};

struct EngineConfig {
    std::string model_path;
    std::string vocab_path;
    int n_threads = 4;
    int context_chars = 30;
    int max_candidates = 11;
    bool repack = false;
    int idle_unload_sec = 0;           // 0 = 不自动卸载
};

enum class EngineState { Idle, Loading, Ready, Failed, Disabled };
const char * state_name(EngineState s);

class Engine {
public:
    Engine();
    ~Engine();
    Engine(const Engine &) = delete;
    Engine & operator=(const Engine &) = delete;

    // ---- 配置（线程安全）。影响加载的项（路径、线程数、重排、候选上限、上文字数）变化后，已加载的模型在工作线程上卸载，下次按需重载。
    EngineConfig config() const;
    void set_config(const EngineConfig & cfg);
    void set_enabled(bool on);         // 关 = 立即卸载并拒绝推理
    bool enabled() const;

    // ---- 请求
    // 预解码：不阻塞。返回 true 表示已排上（模型就绪且上文合格）；模型未加载时触发加载并返回 false。
    bool prepare(const std::string & context);
    // 打分：最多等待 budget_ms（<= 0 表示一直等到算完，只用于测试与工具）。
    ScoreResult score(const std::string & context, const std::vector<std::string> & cands, double budget_ms);
    void touch();                      // 刷新“最近使用”时间（空闲卸载用）
    bool is_ready() const;
    EngineState state() const;

    // 状态：JSON 字段列表（不含外层花括号），只含数字与状态名
    std::string status_fields() const;
    EngineStats stats() const;

    // 停止工作线程并释放模型（可再次使用：下一个请求会重新启动工作线程）
    void shutdown();

private:
    struct ScoreJob;

    void ensure_worker_locked();
    void request_load_locked();
    void worker_main();
    void do_load(const EngineConfig & cfg);
    void do_unload(const char * why);
    void run_score(const std::shared_ptr<ScoreJob> & job);
    void run_prepare(const TokenSeq & ctx);

    mutable std::mutex mu_;
    std::condition_variable cv_;       // 唤醒工作线程
    std::thread worker_;
    bool worker_running_ = false;
    bool stop_ = false;

    EngineConfig cfg_;
    uint64_t cfg_gen_ = 0;             // 影响加载的配置每变一次加一
    uint64_t loaded_gen_ = 0;          // 当前模型对应的配置代次
    bool enabled_ = true;
    EngineState state_ = EngineState::Idle;
    bool load_requested_ = false;
    bool unload_requested_ = false;
    double failed_at_ms_ = 0;
    uint64_t failed_gen_ = 0;

    std::shared_ptr<const Vocab> vocab_;   // 调用方用它分词；模型就绪时发布，卸载时撤回
    std::unique_ptr<SuzuModel> model_;     // 只在工作线程上访问

    std::deque<std::shared_ptr<ScoreJob>> score_queue_;
    bool prepare_pending_ = false;
    TokenSeq prepare_ctx_;
    TokenSeq last_prepared_;

    ResultCache cache_;
    EngineStats stats_;
    double last_use_ms_ = 0;
};

// 进程内共用的实例（Lua 模块与守护进程都用它；宿主里可能有多个 Lua 状态，但只该有一份模型）
Engine & shared_engine();

}  // namespace suzu
