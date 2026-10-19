#include "runtime/engine.h"

#include <cstdio>
#include <cstdlib>

#include "llama.h"
#include "runtime/clock.h"
#include "runtime/log.h"

#ifndef SUZU_REPACK_DEFAULT
#define SUZU_REPACK_DEFAULT 0
#endif

namespace suzu {

namespace {

constexpr double kRetryFailedLoadMs = 30000;   // 同一配置加载失败后，30 秒内不再重试（避免每次按键都去读盘）
constexpr int kCandidateMaxChars = 12;
constexpr int kContextSlots = 2;               // 第一阶段与第二阶段的上文各占一条

bool load_affecting_changed(const EngineConfig & a, const EngineConfig & b) {
    return a.model_path != b.model_path || a.vocab_path != b.vocab_path || a.n_threads != b.n_threads ||
           a.context_chars != b.context_chars || a.max_candidates != b.max_candidates || a.repack != b.repack;
}

void quiet_llama_log() {
    // llama.cpp 默认把每次加载的细节打到 stderr：只留错误
    llama_log_set([](ggml_log_level lvl, const char * text, void *) {
        if (lvl == GGML_LOG_LEVEL_ERROR) fputs(text, stderr);
    }, nullptr);
}

}  // namespace

const char * score_reason(ScoreStatus s) {
    switch (s) {
        case ScoreStatus::Ok: return "";
        case ScoreStatus::NoModel: return "no_model";
        case ScoreStatus::Loading: return "loading";
        case ScoreStatus::Disabled: return "off";
        case ScoreStatus::NoContext: return "no_context";
        case ScoreStatus::FewCandidates: return "few_candidates";
        case ScoreStatus::Timeout: return "timeout";
        case ScoreStatus::Error: return "error";
    }
    return "error";
}

const char * state_name(EngineState s) {
    switch (s) {
        case EngineState::Idle: return "idle";
        case EngineState::Loading: return "loading";
        case EngineState::Ready: return "ready";
        case EngineState::Failed: return "failed";
        case EngineState::Disabled: return "off";
    }
    return "idle";
}

struct Engine::ScoreJob {
    TokenSeq ctx;
    std::vector<TokenSeq> cand_tokens;
    std::vector<std::string> cands;
    std::string key;
    Clock::time_point submitted;
    std::condition_variable done_cv;
    bool done = false;
    bool abandoned = false;    // 等待方已超时返回
    bool ok = false;
    std::vector<double> scores;
};

Engine::Engine() {
    cfg_.repack = SUZU_REPACK_DEFAULT != 0;
}

Engine::~Engine() { shutdown(); }

EngineConfig Engine::config() const {
    std::lock_guard<std::mutex> lk(mu_);
    return cfg_;
}

void Engine::set_config(const EngineConfig & cfg) {
    std::lock_guard<std::mutex> lk(mu_);
    bool reload = load_affecting_changed(cfg, cfg_);
    cfg_ = cfg;
    if (reload) {
        cfg_gen_++;
        // 调用方立即看到“未就绪”；真正的释放在工作线程上做
        vocab_.reset();
        cache_.clear();
        if (state_ != EngineState::Disabled) state_ = EngineState::Idle;
        if (worker_running_) unload_requested_ = true;
        last_prepared_.clear();
    }
    cv_.notify_all();   // 空闲时限可能变了：让工作线程重算
}

void Engine::set_enabled(bool on) {
    std::lock_guard<std::mutex> lk(mu_);
    if (enabled_ == on) return;
    enabled_ = on;
    if (!on) {
        vocab_.reset();
        cache_.clear();
        last_prepared_.clear();
        state_ = EngineState::Disabled;
        if (worker_running_) unload_requested_ = true;
        log_event("disabled");
    } else {
        state_ = EngineState::Idle;
        log_event("enabled");
        request_load_locked();   // 手动开启 = 马上要用：立刻开始加载
    }
    cv_.notify_all();
}

bool Engine::enabled() const {
    std::lock_guard<std::mutex> lk(mu_);
    return enabled_;
}

void Engine::ensure_worker_locked() {
    if (worker_running_) return;
    static bool atexit_registered = false;
    if (!atexit_registered) {
        atexit_registered = true;
        quiet_llama_log();
        // 进程退出时先停工作线程、释放模型，再让 ggml 的静态对象析构
        std::atexit([] { shared_engine().shutdown(); });
    }
    stop_ = false;
    worker_running_ = true;
    worker_ = std::thread([this] { worker_main(); });
}

void Engine::request_load_locked() {
    if (!enabled_) return;
    if (state_ == EngineState::Ready || state_ == EngineState::Loading) return;
    if (cfg_.model_path.empty() || cfg_.vocab_path.empty()) return;
    if (state_ == EngineState::Failed && failed_gen_ == cfg_gen_ && now_ms() - failed_at_ms_ < kRetryFailedLoadMs) return;
    state_ = EngineState::Loading;
    load_requested_ = true;
    ensure_worker_locked();
    cv_.notify_all();
}

void Engine::touch() {
    std::lock_guard<std::mutex> lk(mu_);
    last_use_ms_ = now_ms();
}

bool Engine::is_ready() const {
    std::lock_guard<std::mutex> lk(mu_);
    return state_ == EngineState::Ready;
}

EngineState Engine::state() const {
    std::lock_guard<std::mutex> lk(mu_);
    return state_;
}

EngineStats Engine::stats() const {
    std::lock_guard<std::mutex> lk(mu_);
    return stats_;
}

std::string Engine::status_fields() const {
    std::lock_guard<std::mutex> lk(mu_);
    char b[256];
    snprintf(b, sizeof b,
             "\"loaded\":%s,\"student\":true,\"state\":\"%s\",\"enabled\":%s,\"threads\":%d,\"max_candidates\":%d,"
             "\"idle_unload_sec\":%d,",
             state_ == EngineState::Ready ? "true" : "false", state_name(state_), enabled_ ? "true" : "false",
             cfg_.n_threads, cfg_.max_candidates, cfg_.idle_unload_sec);
    return std::string(b) + stats_.json();
}

bool Engine::prepare(const std::string & context) {
    std::shared_ptr<const Vocab> vocab;
    int chars;
    {
        std::lock_guard<std::mutex> lk(mu_);
        last_use_ms_ = now_ms();
        if (!enabled_) return false;
        if (state_ != EngineState::Ready) { request_load_locked(); return false; }
        vocab = vocab_;
        chars = cfg_.context_chars;
    }
    TokenSeq ctx = vocab->encode_context(context, chars);
    if (ctx.empty()) return false;
    std::lock_guard<std::mutex> lk(mu_);
    if (state_ != EngineState::Ready) return false;
    stats_.prepares++;
    if (ctx == last_prepared_ || (prepare_pending_ && ctx == prepare_ctx_)) return true;
    prepare_ctx_ = std::move(ctx);
    prepare_pending_ = true;
    cv_.notify_all();
    return true;
}

ScoreResult Engine::score(const std::string & context, const std::vector<std::string> & cands, double budget_ms) {
    const Clock::time_point t0 = Clock::now();
    ScoreResult res;
    std::shared_ptr<const Vocab> vocab;
    int chars, max_cands;
    {
        std::lock_guard<std::mutex> lk(mu_);
        stats_.requests++;
        last_use_ms_ = now_ms();
        if (!enabled_) { stats_.skip_off++; res.status = ScoreStatus::Disabled; return res; }
        if (cands.size() < 2) { stats_.few_candidates++; res.status = ScoreStatus::FewCandidates; return res; }
        if (state_ != EngineState::Ready) {
            request_load_locked();
            if (state_ == EngineState::Loading) { stats_.skip_loading++; res.status = ScoreStatus::Loading; }
            else { stats_.skip_no_model++; res.status = ScoreStatus::NoModel; }
            return res;
        }
        vocab = vocab_;
        chars = cfg_.context_chars;
        max_cands = cfg_.max_candidates;
    }

    // 分词在锁外做（词表是只读的共享对象）
    auto job = std::make_shared<ScoreJob>();
    job->ctx = vocab->encode_context(context, chars);
    job->cands.assign(cands.begin(), cands.begin() + (long)std::min(cands.size(), (size_t)max_cands));
    for (auto & c : job->cands) job->cand_tokens.push_back(vocab->encode_candidate(c));
    res.cands = job->cands;

    std::unique_lock<std::mutex> lk(mu_);
    if (job->ctx.empty()) { stats_.no_context++; res.status = ScoreStatus::NoContext; return res; }
    job->key = ResultCache::make_key(job->ctx, job->cands);
    if (cache_.get(job->key, &res.scores)) {
        stats_.ok++;
        stats_.cache_hits++;
        stats_.wait_ms.add(ms_between(t0, Clock::now()));
        res.status = ScoreStatus::Ok;
        res.cache_hit = true;
        return res;
    }
    if (state_ != EngineState::Ready) { stats_.skip_loading++; res.status = ScoreStatus::Loading; return res; }
    job->submitted = Clock::now();
    score_queue_.push_back(job);
    cv_.notify_all();
    auto pred = [&] { return job->done; };
    if (budget_ms > 0) job->done_cv.wait_until(lk, t0 + std::chrono::duration<double, std::milli>(budget_ms), pred);
    else job->done_cv.wait(lk, pred);
    double waited = ms_between(t0, Clock::now());
    stats_.wait_ms.add(waited);
    if (!job->done) {
        job->abandoned = true;
        stats_.skip_timeout++;
        res.status = ScoreStatus::Timeout;
        log_event("score timeout wait=%.1f budget=%.0f queue=%zu", waited, budget_ms, score_queue_.size());
        return res;
    }
    if (!job->ok) { stats_.skip_error++; res.status = ScoreStatus::Error; return res; }
    stats_.ok++;
    res.scores = job->scores;
    res.status = ScoreStatus::Ok;
    return res;
}

void Engine::shutdown() {
    std::thread t;
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (!worker_running_) return;
        stop_ = true;
        cv_.notify_all();
        t = std::move(worker_);
    }
    if (t.joinable()) t.join();
    std::lock_guard<std::mutex> lk(mu_);
    worker_running_ = false;
    stop_ = false;
    load_requested_ = unload_requested_ = prepare_pending_ = false;
    vocab_.reset();
    cache_.clear();
    last_prepared_.clear();
    if (state_ != EngineState::Disabled) state_ = EngineState::Idle;
}

// ---------------------------------------------------------------- 工作线程

void Engine::worker_main() {
    std::unique_lock<std::mutex> lk(mu_);
    while (!stop_) {
        if (unload_requested_) {
            unload_requested_ = false;
            lk.unlock();
            do_unload("config");
            lk.lock();
            continue;
        }
        if (load_requested_) {
            load_requested_ = false;
            EngineConfig cfg = cfg_;
            lk.unlock();
            do_load(cfg);
            lk.lock();
            continue;
        }
        if (!score_queue_.empty()) {
            auto job = score_queue_.front();
            score_queue_.pop_front();
            if (job->abandoned && !score_queue_.empty()) {   // 没人等了，后面还有更新的请求：不算
                job->done = true;
                stats_.jobs_dropped++;
                continue;
            }
            stats_.queue_ms.add(ms_between(job->submitted, Clock::now()));
            lk.unlock();
            run_score(job);
            lk.lock();
            continue;
        }
        if (prepare_pending_) {
            prepare_pending_ = false;
            TokenSeq ctx = std::move(prepare_ctx_);
            lk.unlock();
            run_prepare(ctx);
            lk.lock();
            continue;
        }
        // 空闲：按需等到空闲卸载的时刻
        if (state_ == EngineState::Ready && cfg_.idle_unload_sec > 0) {
            double due = last_use_ms_ + cfg_.idle_unload_sec * 1000.0;
            double left = due - now_ms();
            if (left <= 0) {
                lk.unlock();
                do_unload("idle");
                lk.lock();
                continue;
            }
            cv_.wait_for(lk, std::chrono::duration<double, std::milli>(left + 20));
        } else {
            cv_.wait(lk);
        }
    }
    // 退出：还在排队的请求一律以失败结束
    for (auto & job : score_queue_) { job->done = true; job->ok = false; job->done_cv.notify_all(); }
    score_queue_.clear();
    lk.unlock();
    model_.reset();
}

void Engine::do_load(const EngineConfig & cfg) {
    uint64_t gen;
    {
        std::lock_guard<std::mutex> lk(mu_);
        gen = cfg_gen_;
        stats_.loads++;
    }
    model_.reset();
    log_event("loading model: %s threads=%d", cfg.model_path.c_str(), cfg.n_threads);
    const Clock::time_point t0 = Clock::now();
    ModelOptions mo;
    mo.model_path = cfg.model_path;
    mo.vocab_path = cfg.vocab_path;
    // 线程数不超过核数减一：线程数等于核数时，任何一个线程被别的进程抢占都会让整批解码停下来等它（实测偶发 50~90 ms 的毛刺）
    unsigned hw = std::thread::hardware_concurrency();
    mo.n_threads = cfg.n_threads;
    if (hw > 1 && (unsigned)mo.n_threads > hw - 1) mo.n_threads = (int)hw - 1;
    mo.max_candidates = cfg.max_candidates;
    mo.context_slots = kContextSlots;
    mo.candidate_max_chars = kCandidateMaxChars;
    mo.repack = cfg.repack;
    std::string err;
    std::unique_ptr<SuzuModel> m = SuzuModel::load(mo, &err);
    if (m) {
        // 预热：第一次解码要分配计算缓冲区，别让它落在用户的第一次按键上
        TokenSeq warm = {kBosToken, kUnkToken, kUnkToken};
        std::vector<double> sc;
        m->score(warm, {{kUnkToken}, {kUnkToken, kUnkToken}}, &sc, nullptr);
        m->reset();
    }
    double ms = ms_between(t0, Clock::now());

    std::lock_guard<std::mutex> lk(mu_);
    if (gen != cfg_gen_ || !enabled_) {      // 加载期间配置变了或被关闭：作废，按新配置再来
        log_event("load discarded (config changed)");
        if (state_ == EngineState::Loading) state_ = EngineState::Idle;
        if (enabled_) request_load_locked();
        return;
    }
    if (!m) {
        stats_.load_failures++;
        state_ = EngineState::Failed;
        failed_at_ms_ = now_ms();
        failed_gen_ = gen;
        log_event("ERROR: load failed: %s", err.c_str());
        return;
    }
    model_ = std::move(m);
    vocab_ = std::make_shared<Vocab>(model_->vocab());
    loaded_gen_ = gen;
    state_ = EngineState::Ready;
    stats_.last_load_ms = ms;
    last_use_ms_ = now_ms();
    log_event("model ready in %.0f ms (threads=%d max_candidates=%d)", ms, model_->options().n_threads, cfg.max_candidates);
}

void Engine::do_unload(const char * why) {
    bool had = model_ != nullptr;
    model_.reset();
    std::lock_guard<std::mutex> lk(mu_);
    vocab_.reset();
    cache_.clear();
    last_prepared_.clear();
    for (auto & job : score_queue_) { job->done = true; job->ok = false; job->done_cv.notify_all(); }
    score_queue_.clear();
    prepare_pending_ = false;
    if (state_ == EngineState::Ready) state_ = EngineState::Idle;
    if (had) {
        stats_.unloads++;
        log_event("model unloaded (%s)", why);
    }
    // 配置变更引起的卸载：之后由下一次请求按新配置加载
}

void Engine::run_score(const std::shared_ptr<ScoreJob> & job) {
    DecodeStats st;
    std::vector<double> scores;
    const Clock::time_point t0 = Clock::now();
    bool ok = model_ && model_->score(job->ctx, job->cand_tokens, &scores, &st);
    double ms = ms_between(t0, Clock::now());

    std::lock_guard<std::mutex> lk(mu_);
    stats_.jobs_done++;
    stats_.compute_ms.add(ms);
    if (st.ctx_hit) stats_.ctx_hit++;
    else if (st.ctx_partial) stats_.ctx_partial++;
    else stats_.ctx_full++;
    if (ok) {
        cache_.put(job->key, scores);
        last_prepared_ = job->ctx;          // 打分时上文已在缓存里
    } else if (model_) {
        stats_.decode_failures++;
        log_event("WARN: decode failed ctx_tok=%zu cands=%zu", job->ctx.size(), job->cands.size());
    }
    job->ok = ok;
    job->scores = std::move(scores);
    job->done = true;
    job->done_cv.notify_all();
    if (ms > 50 || job->abandoned)
        log_event("slow score compute=%.1f ctx=%.1f(%d tok, hit=%d) cand=%.1f(%d tok, %d decodes) abandoned=%d",
                  ms, st.ctx_ms, st.ctx_tokens, st.ctx_hit ? 1 : 0, st.cand_ms, st.cand_tokens, st.decode_calls,
                  job->abandoned ? 1 : 0);
}

void Engine::run_prepare(const TokenSeq & ctx) {
    if (!model_) return;
    DecodeStats st;
    const Clock::time_point t0 = Clock::now();
    bool ok = model_->prepare(ctx, &st);
    double ms = ms_between(t0, Clock::now());
    std::lock_guard<std::mutex> lk(mu_);
    stats_.prepare_done++;
    stats_.prepare_ms.add(ms);
    if (ok) last_prepared_ = ctx;
}

Engine & shared_engine() {
    static Engine * e = new Engine;   // 故意不析构：退出顺序由 atexit 里的 shutdown 控制
    return *e;
}

}  // namespace suzu
