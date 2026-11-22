#include "runtime/engine.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#if defined(__GLIBC__)
#include <malloc.h>
#endif

#if defined(__APPLE__)
#include <pthread/qos.h>
#endif

#include "llama.h"
#include "runtime/clock.h"
#include "runtime/log.h"

#ifndef SUZU_REPACK_DEFAULT
#define SUZU_REPACK_DEFAULT 0
#endif

namespace suzu {

constexpr double kOovScore = -1e12;   // 低于 Lua 端的有效分下限（-1e9）

namespace {

constexpr double kRetryFailedLoadMs = 30000;   // 同一配置加载失败后，30 秒内不再重试（避免每次按键都去读盘）
constexpr int kCandidateMaxChars = 12;
constexpr int kContextSlots = 2;               // 第一阶段与第二阶段的上文各占一条
constexpr size_t kGenMaxWords = 4000;          // 生成时一次输入最多考虑的词（按词频取前面的），防止极端输入拖慢
constexpr int kGenMinChars = 2, kGenMaxChars = 3;

// 生成任务的结果在缓存里的存法：[词条下标, 分数, 词条下标, 分数, …]，按分数降序
std::vector<double> top_k_flat(const std::vector<double> & sc, int k) {
    std::vector<int> idx;
    for (size_t i = 0; i < sc.size(); i++) if (sc[i] > SuzuModel::kNotReached / 2) idx.push_back((int)i);
    size_t n = std::min(idx.size(), (size_t)std::max(0, k));
    std::partial_sort(idx.begin(), idx.begin() + (long)n, idx.end(), [&](int a, int b) { return sc[a] > sc[b]; });
    std::vector<double> out;
    for (size_t j = 0; j < n; j++) { out.push_back(idx[j]); out.push_back(sc[idx[j]]); }
    return out;
}

bool load_affecting_changed(const EngineConfig & a, const EngineConfig & b) {
    return a.model_path != b.model_path || a.vocab_path != b.vocab_path || a.n_threads != b.n_threads ||
           a.context_chars != b.context_chars || a.max_candidates != b.max_candidates || a.repack != b.repack ||
           a.poll != b.poll || a.identify_model != b.identify_model ||
           a.lexicon_dir.empty() != b.lexicon_dir.empty() || a.gen_beam != b.gen_beam;   // 词典目录换成另一个不在此列：只重读词典
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
        case ScoreStatus::NoLexicon: return "no_lexicon";
        case ScoreStatus::NotApplicable: return "not_applicable";
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
    Clock::time_point t0;      // 调用方开始等待的时刻
    std::vector<char> oov;     // 打分：含词表外字的候选
    // 生成任务
    bool gen = false;
    int beam = 0, k = 0;
    std::shared_ptr<const Vocab> vocab;   // 生成：把词条编号还原成词
    std::shared_ptr<ScoreJob> partner;    // 打分任务捎带的生成任务：两者共用一次解码，一起完成
    int miss_kind = -1;        // 工作线程开始处理时判定的归因（上文真没命中时才计数）
};

namespace {
constexpr size_t kPrepTrack = 8;          // 记最近几段上文的准备状态
constexpr double kOtherCtxWindowMs = 3000; // 这段时间内有过别的预解码请求：算 other_ctx
constexpr double kLateWindowMs = 2000;     // 任务之后这段时间内才到的同一段上文的预解码：算 late
constexpr double kMissLogIntervalMs = 600000;   // 归因计数写进日志的最短间隔
}  // namespace

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
    bool relex = !reload && cfg.lexicon_dir != cfg_.lexicon_dir;
    cfg_ = cfg;
    if (relex && worker_running_) {
        lexicon_.reset();            // 旧词典的词不属于新方案：立即撤回，新索引读完后再发布
        relex_requested_ = true;
    }
    if (reload) {
        cfg_gen_++;
        // 调用方立即看到“未就绪”；真正的释放在工作线程上做
        vocab_.reset();
        cache_.clear();
        if (state_ != EngineState::Disabled) state_ = EngineState::Idle;
        if (worker_running_) unload_requested_ = true;
        last_prepared_.clear();
        clear_prep_track_locked();
    }
    cv_.notify_all();   // 空闲时限可能变了：让工作线程重算
}

// 候选生成读哪些文件：base 与 ext；目录里有 extra.dict.yaml（本项目自己的补充词条，由词库包安装脚本放入）就一并读；
// 再加上词库目录旁边 suzu_addons/ 里的附加词库（App 下载，所有词库包共用）。顺序固定，签名才可比较
static std::vector<std::string> lexicon_files(const std::string & dir) {
    std::vector<std::string> files = {dir + "/base.dict.yaml", dir + "/ext.dict.yaml"};
    if (FILE * fx = fopen((dir + "/extra.dict.yaml").c_str(), "rb")) { fclose(fx); files.push_back(dir + "/extra.dict.yaml"); }
    std::error_code ec;
    std::vector<std::string> extra;
    const std::filesystem::path addons = std::filesystem::path(dir).parent_path() / "suzu_addons";
    for (std::filesystem::directory_iterator it(addons, ec), end; !ec && it != end; it.increment(ec)) {
        if (it->path().extension() == ".yaml") extra.push_back(it->path().string());
    }
    std::sort(extra.begin(), extra.end());
    files.insert(files.end(), extra.begin(), extra.end());
    return files;
}

// 各文件的路径、大小与修改时间；不存在的文件记为 -
static std::string lexicon_signature(const std::string & dir) {
    std::string sig;
    for (const std::string & f : lexicon_files(dir)) {
        std::error_code e1, e2;
        auto size = std::filesystem::file_size(f, e1);
        auto mtime = std::filesystem::last_write_time(f, e2);
        sig += f;
        sig += '\t';
        if (e1 || e2) sig += "-";
        else sig += std::to_string((unsigned long long)size) + "," + std::to_string((long long)mtime.time_since_epoch().count());
        sig += '\n';
    }
    return sig;
}

void Engine::set_lexicon_dir(const std::string & dir) {
    EngineConfig c = config();
    if (c.lexicon_dir != dir) {
        c.lexicon_dir = dir;
        set_config(c);
        return;
    }
    // 目录没变。输入法每次初始化都会再下发一次目录名，绝大多数时候文件没动：只做几次 stat。
    // 没读过词典（模型未加载、未开生成）时不用管，之后加载时读到的就是当前的文件
    if (dir.empty()) return;
    std::string sig = lexicon_signature(dir);
    std::lock_guard<std::mutex> lk(mu_);
    if (!worker_running_ || lexicon_sig_.empty() || sig == lexicon_sig_ || cfg_.lexicon_dir != dir) return;
    log_event("lexicon files changed: rereading");
    relex_requested_ = true;
    cv_.notify_all();
}

void Engine::set_enabled(bool on) {
    std::lock_guard<std::mutex> lk(mu_);
    if (enabled_ == on) {
        // 已开启但模型没在内存里（空闲卸载后的待机）：再“开启”一次 = 立刻加载，徽标点待机就是走这里
        if (on) { request_load_locked(); cv_.notify_all(); }
        return;
    }
    enabled_ = on;
    if (!on) {
        vocab_.reset();
        cache_.clear();
        last_prepared_.clear();
        clear_prep_track_locked();
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
    static bool log_quieted = false;
    if (!log_quieted) { log_quieted = true; quiet_llama_log(); }
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

int Engine::word_weight(const std::string & word) const {
    std::shared_ptr<const Lexicon> lex;
    std::shared_ptr<const Vocab> vocab;
    {
        std::lock_guard<std::mutex> lk(mu_);
        lex = lexicon_;
        vocab = vocab_;
    }
    if (!lex || !vocab) return -1;
    return lex->word_weight(vocab->encode(word));
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

ModelIdentity Engine::model_identity() const {
    std::lock_guard<std::mutex> lk(mu_);
    return state_ == EngineState::Ready ? identity_ : ModelIdentity{};
}

std::string Engine::status_fields() const {
    std::lock_guard<std::mutex> lk(mu_);
    char b[320];
    snprintf(b, sizeof b,
             "\"loaded\":%s,\"student\":true,\"state\":\"%s\",\"enabled\":%s,\"threads\":%d,\"max_candidates\":%d,"
             "\"idle_unload_sec\":%d,\"repack\":%s,\"poll\":%d,",
             state_ == EngineState::Ready ? "true" : "false", state_name(state_), enabled_ ? "true" : "false",
             cfg_.n_threads, cfg_.max_candidates, cfg_.idle_unload_sec, cfg_.repack ? "true" : "false", cfg_.poll);
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
    const double t = now_ms();
    for (auto it = miss_recent_.begin(); it != miss_recent_.end(); ++it) {
        if (it->second == ctx && t - it->first <= kLateWindowMs) {
            stats_.ctx_miss_late++;
            miss_recent_.erase(it);
            break;
        }
    }
    if (ctx == last_prepared_) { track_prep_locked(ctx, kPrepDone, t); return true; }
    if (prepare_pending_ && ctx == prepare_ctx_) { track_prep_locked(ctx, kPrepPending, t); return true; }
    if (prepare_pending_) track_prep_locked(prepare_ctx_, kPrepReplaced, t);   // 还没轮到就被新的上文顶掉
    track_prep_locked(ctx, kPrepPending, t);
    prepare_ctx_ = std::move(ctx);
    prepare_pending_ = true;
    cv_.notify_all();
    return true;
}

void Engine::track_prep_locked(const TokenSeq & ctx, PrepState state, double t_ms, bool by_job) {
    for (auto it = prep_recent_.begin(); it != prep_recent_.end(); ++it) {
        if (it->ctx == ctx) {
            // 已有记录：预解码请求的记录不因任务算过同一段上文而改成 by_job，时刻也保留请求到达时的值。
            // 否则任务算完会把旧请求“刷新”成刚发生，紧接着打另一段上文就被误计为 other_ctx
            const bool keep_request = by_job && !it->by_job;
            PrepRec r{ctx, keep_request ? it->t_ms : t_ms, state, by_job && it->by_job};
            prep_recent_.erase(it);
            prep_recent_.push_back(std::move(r));
            return;
        }
    }
    prep_recent_.push_back(PrepRec{ctx, t_ms, state, by_job});
    while (prep_recent_.size() > kPrepTrack) prep_recent_.pop_front();
}

int Engine::classify_miss_locked(const TokenSeq & ctx, double t_ms) const {
    for (auto it = prep_recent_.rbegin(); it != prep_recent_.rend(); ++it) {
        if (it->ctx != ctx) continue;
        switch (it->state) {
            case kPrepPending: return EngineStats::kMissQueued;     // 同一段上文的预解码还在排队，被任务抢先
            case kPrepReplaced: return EngineStats::kMissReplaced;  // 没轮到执行就被更新的请求顶掉
            default: return EngineStats::kMissEvicted;              // 准备好（或算过）了，槽位后来又被改写
        }
    }
    for (const auto & r : prep_recent_)
        if (!r.by_job && t_ms - r.t_ms <= kOtherCtxWindowMs) return EngineStats::kMissOtherCtx;   // 准备的是另一段上文
    return EngineStats::kMissNoPrepare;
}

void Engine::clear_prep_track_locked() {
    prep_recent_.clear();
    miss_recent_.clear();
}

// ---- 打分与生成都分两段：begin_* 在调用方线程上分词、查缓存、入队，不等待；finish_* 等到截止时刻并填写结果。
// begin_* 返回 nullptr 表示结果已经确定（失败或缓存命中），不必等待。score、generate、score_gen 共用这两段。

void Engine::wait_job(std::unique_lock<std::mutex> & lk, const std::shared_ptr<ScoreJob> & job, Clock::time_point deadline,
                      double budget_ms) {
    auto pred = [&] { return job->done; };
    if (budget_ms > 0) job->done_cv.wait_until(lk, deadline, pred);
    else job->done_cv.wait(lk, pred);
}

std::shared_ptr<Engine::ScoreJob> Engine::begin_score(const std::string & context, const std::vector<std::string> & cands,
                                                      Clock::time_point t0, ScoreResult * res, bool enqueue) {
    std::shared_ptr<const Vocab> vocab;
    int chars, max_cands;
    {
        std::lock_guard<std::mutex> lk(mu_);
        stats_.requests++;
        last_use_ms_ = now_ms();
        if (!enabled_) { stats_.skip_off++; res->status = ScoreStatus::Disabled; return nullptr; }
        if (cands.size() < 2) { stats_.few_candidates++; res->status = ScoreStatus::FewCandidates; return nullptr; }
        if (state_ != EngineState::Ready) {
            request_load_locked();
            if (state_ == EngineState::Loading) { stats_.skip_loading++; res->status = ScoreStatus::Loading; }
            else { stats_.skip_no_model++; res->status = ScoreStatus::NoModel; }
            return nullptr;
        }
        vocab = vocab_;
        chars = cfg_.context_chars;
        max_cands = cfg_.max_candidates;
    }

    // 分词在锁外做（词表是只读的共享对象）
    auto job = std::make_shared<ScoreJob>();
    job->t0 = t0;
    job->ctx = vocab->encode_context(context, chars);
    job->cands.assign(cands.begin(), cands.begin() + (long)std::min(cands.size(), (size_t)max_cands));
    for (auto & c : job->cands) job->cand_tokens.push_back(vocab->encode_candidate(c));
    res->cands = job->cands;
    // 含词表外字（编成 <unk>）的候选：模型分不可信。<unk> 在训练语料里很常见，单个 <unk> 的对数概率远高于
    // 任何真实的词，生僻字候选会因此排到首位。这类候选记为无效分，由 Lua 排到同组末尾。
    job->oov.assign(job->cand_tokens.size(), 0);
    for (size_t i = 0; i < job->oov.size(); i++)
        for (Token t : job->cand_tokens[i]) if (t == kUnkToken) { job->oov[i] = 1; break; }

    std::lock_guard<std::mutex> lk(mu_);
    if (job->ctx.empty()) { stats_.no_context++; res->status = ScoreStatus::NoContext; return nullptr; }
    job->key = ResultCache::make_key(job->ctx, job->cands);
    if (cache_.get(job->key, &res->scores)) {
        stats_.ok++;
        stats_.cache_hits++;
        stats_.wait_ms.add(ms_between(t0, Clock::now()));
        mark_oov(*job, &res->scores);
        res->status = ScoreStatus::Ok;
        res->cache_hit = true;
        return nullptr;
    }
    if (state_ != EngineState::Ready) { stats_.skip_loading++; res->status = ScoreStatus::Loading; return nullptr; }
    job->submitted = Clock::now();
    if (enqueue) {
        score_queue_.push_back(job);
        cv_.notify_all();
    }
    return job;
}

void Engine::finish_score(const std::shared_ptr<ScoreJob> & job, Clock::time_point deadline, double budget_ms,
                          ScoreResult * res) {
    std::unique_lock<std::mutex> lk(mu_);
    wait_job(lk, job, deadline, budget_ms);
    double waited = ms_between(job->t0, Clock::now());
    stats_.wait_ms.add(waited);
    if (!job->done) {
        job->abandoned = true;
        stats_.skip_timeout++;
        res->status = ScoreStatus::Timeout;
        log_event("score timeout wait=%.1f budget=%.0f queue=%zu", waited, budget_ms, score_queue_.size());
        return;
    }
    if (!job->ok) { stats_.skip_error++; res->status = ScoreStatus::Error; return; }
    stats_.ok++;
    res->scores = job->scores;
    mark_oov(*job, &res->scores);
    res->status = ScoreStatus::Ok;
}

void Engine::mark_oov(const ScoreJob & job, std::vector<double> * sc) {
    for (size_t i = 0; i < sc->size() && i < job.oov.size(); i++) if (job.oov[i]) (*sc)[i] = kOovScore;
}

void Engine::fill_generated(const ScoreJob & job, const std::vector<double> & flat, GenResult * res) {
    for (size_t j = 0; j + 1 < flat.size(); j += 2) {
        size_t i = (size_t)flat[j];
        if (i >= job.cand_tokens.size()) continue;
        std::string w;
        for (Token t : job.cand_tokens[i]) w += job.vocab->piece(t);
        res->words.push_back(std::move(w));
        res->scores.push_back(flat[j + 1]);
        res->ranks.push_back((int)i + 1);
    }
}

std::shared_ptr<Engine::ScoreJob> Engine::begin_generate(const std::string & context, const std::string & input, int k,
                                                         Clock::time_point t0, GenResult * res, bool enqueue) {
    std::shared_ptr<const Vocab> vocab;
    std::shared_ptr<const Lexicon> lex;
    int chars, beam;
    uint64_t lex_gen;
    {
        std::lock_guard<std::mutex> lk(mu_);
        stats_.gen_requests++;
        last_use_ms_ = now_ms();
        if (!enabled_) { res->status = ScoreStatus::Disabled; return nullptr; }
        if (state_ != EngineState::Ready) {
            request_load_locked();
            res->status = state_ == EngineState::Loading ? ScoreStatus::Loading : ScoreStatus::NoModel;
            return nullptr;
        }
        vocab = vocab_;
        lex = lexicon_;
        lex_gen = lexicon_gen_;
        chars = cfg_.context_chars;
        beam = cfg_.gen_beam;
    }
    if (!lex || beam <= 0) { res->status = ScoreStatus::NoLexicon; return nullptr; }

    // 切分与匹配在锁外做（词典索引加载后只读）
    bool full = false;
    auto segs = lex->segment(input, &full);
    if (full || segs.empty()) { res->status = ScoreStatus::NotApplicable; return nullptr; }
    std::vector<uint32_t> entries = lex->match(segs);
    res->matched = (int)entries.size();
    if (entries.empty()) { res->status = ScoreStatus::FewCandidates; return nullptr; }   // 只匹配到一个词也生成：它可能正是 Rime 没给的那个
    if (entries.size() > kGenMaxWords) entries.resize(kGenMaxWords);
    auto job = std::make_shared<ScoreJob>();
    job->t0 = t0;
    job->gen = true;
    job->beam = beam;
    job->k = std::max(1, k);
    job->vocab = vocab;
    job->ctx = vocab->encode_context(context, chars);
    for (uint32_t e : entries) job->cand_tokens.push_back(lex->tokens(e));

    std::lock_guard<std::mutex> lk(mu_);
    if (job->ctx.empty()) { res->status = ScoreStatus::NoContext; return nullptr; }
    // 缓存的值是词条在这份词典匹配结果里的下标：键里必须带词典的代次，否则换词典后会把旧分数套到新词上
    job->key = ResultCache::make_key(job->ctx, {"\x01gen", input, std::to_string(beam), std::to_string(job->k),
                                                std::to_string(lex_gen)});
    std::vector<double> flat;
    if (cache_.get(job->key, &flat)) {
        stats_.gen_ok++;
        fill_generated(*job, flat, res);
        res->status = ScoreStatus::Ok;
        res->cache_hit = true;
        return nullptr;
    }
    if (state_ != EngineState::Ready) { res->status = ScoreStatus::Loading; return nullptr; }
    job->submitted = Clock::now();
    if (enqueue) {
        score_queue_.push_back(job);
        cv_.notify_all();
    }
    return job;
}

void Engine::finish_generate(const std::shared_ptr<ScoreJob> & job, Clock::time_point deadline, double budget_ms,
                             GenResult * res) {
    std::unique_lock<std::mutex> lk(mu_);
    wait_job(lk, job, deadline, budget_ms);
    if (!job->done) {
        job->abandoned = true;
        stats_.gen_timeout++;
        res->status = ScoreStatus::Timeout;
        return;
    }
    if (!job->ok) { res->status = ScoreStatus::Error; return; }
    stats_.gen_ok++;
    fill_generated(*job, job->scores, res);
    res->status = ScoreStatus::Ok;
}

ScoreResult Engine::score(const std::string & context, const std::vector<std::string> & cands, double budget_ms) {
    const Clock::time_point t0 = Clock::now();
    ScoreResult res;
    auto job = begin_score(context, cands, t0, &res);
    if (job) finish_score(job, t0 + std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double, std::milli>(budget_ms)),
                          budget_ms, &res);
    return res;
}

GenResult Engine::generate(const std::string & context, const std::string & input, int k, double budget_ms) {
    const Clock::time_point t0 = Clock::now();
    GenResult res;
    auto job = begin_generate(context, input, k, t0, &res);
    if (job) finish_generate(job, t0 + std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double, std::milli>(budget_ms)),
                             budget_ms, &res);
    return res;
}

ScoreGenResult Engine::score_gen(const std::string & context, const std::vector<std::string> & cands,
                                 const std::string & input, int k, ScoreGenMode mode, double budget_ms) {
    const Clock::time_point t0 = Clock::now();
    const Clock::time_point deadline =
        t0 + std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double, std::milli>(budget_ms));
    ScoreGenResult out;
    {
        std::lock_guard<std::mutex> lk(mu_);
        stats_.sg_requests++;
    }
    if (mode == ScoreGenMode::GenFirst) {
        // 编码不合打分规则（如两个字母的简拼）：先生成，能生成才打分
        out.gen_tried = true;
        auto gj = begin_generate(context, input, k, t0, &out.gen);
        if (gj) finish_generate(gj, deadline, budget_ms, &out.gen);
        if (out.gen.status != ScoreStatus::Ok) return out;
        out.score_tried = true;
        auto sj = begin_score(context, cands, t0, &out.score);
        if (sj) finish_score(sj, deadline, budget_ms, &out.score);
        return out;
    }
    // 打分与生成一起入队（打分在前），共用一个截止时刻。打分当场失败时不生成：Lua 只在有模型分时使用生成结果
    out.score_tried = true;
    auto sj = begin_score(context, cands, t0, &out.score, false);
    std::shared_ptr<ScoreJob> gj;
    if (sj || out.score.status == ScoreStatus::Ok) {
        out.gen_tried = true;
        gj = begin_generate(context, input, k, t0, &out.gen, false);
    }
    {
        // 两边都要算：合成一个任务，工作线程共用一次解码（share_decode 关闭时照旧各排一个）
        std::lock_guard<std::mutex> lk(mu_);
        if ((sj || gj) && state_ != EngineState::Ready) {
            if (sj) { stats_.skip_loading++; out.score.status = ScoreStatus::Loading; sj.reset(); }
            if (gj) { out.gen.status = ScoreStatus::Loading; gj.reset(); }
        }
        if (sj && gj && cfg_.share_decode && sj->ctx == gj->ctx) {
            sj->partner = gj;
            stats_.shared_jobs++;
            score_queue_.push_back(sj);
        } else {
            if (sj) score_queue_.push_back(sj);
            if (gj) score_queue_.push_back(gj);
        }
        if (sj || gj) cv_.notify_all();
    }
    if (sj) finish_score(sj, deadline, budget_ms, &out.score);
    if (!gj) return out;
    if (out.score.status == ScoreStatus::Timeout) {
        // 打分超时：还在排队的生成任务撤掉。否则工作线程会因为“后面还有任务”丢弃已被放弃的打分任务，
        // 下一次按键就拿不到这次打分的缓存（单独调用 score 时，超时的任务会算完进缓存）
        std::lock_guard<std::mutex> lk(mu_);
        auto it = std::find(score_queue_.begin(), score_queue_.end(), gj);
        if (it != score_queue_.end()) {
            score_queue_.erase(it);
            gj->done = true;
            stats_.gen_timeout++;
            out.gen.status = ScoreStatus::Timeout;
            return out;
        }
    }
    finish_generate(gj, deadline, budget_ms, &out.gen);
    return out;
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
    if (lexicon_thread_.joinable()) lexicon_thread_.join();
    std::lock_guard<std::mutex> lk(mu_);
    worker_running_ = false;
    stop_ = false;
    load_requested_ = unload_requested_ = prepare_pending_ = false;
    vocab_.reset();
    lexicon_.reset();
    cache_.clear();
    last_prepared_.clear();
    clear_prep_track_locked();
    if (state_ != EngineState::Disabled) state_ = EngineState::Idle;
}

// ---------------------------------------------------------------- 工作线程

void Engine::worker_main() {
#if defined(__APPLE__)
    // macOS 上新线程不继承创建者的 QoS，一律从 DEFAULT 开始（2026-11-19 实测）。输入法线程是 USER_INTERACTIVE，
    // 它在等这个线程的结果，所以把工作线程提到同一级。ggml 线程池的线程由 llama.cpp 创建，仍是 DEFAULT，暂不处理
    pthread_set_qos_class_self_np(QOS_CLASS_USER_INTERACTIVE, 0);
#endif
    std::unique_lock<std::mutex> lk(mu_);
    while (!stop_) {
        if (unload_requested_) {
            unload_requested_ = false;
            lk.unlock();
            do_unload("config");
            lk.lock();
            continue;
        }
        if (relex_requested_) {
            relex_requested_ = false;
            if (state_ == EngineState::Ready && model_ && !cfg_.lexicon_dir.empty() && cfg_.gen_beam > 0) {
                EngineConfig cfg = cfg_;
                uint64_t gen = loaded_gen_;
                lk.unlock();
                if (lexicon_thread_.joinable()) lexicon_thread_.join();   // 上一个索引线程（可能是旧目录的）读完再换
                lk.lock();
                if (state_ == EngineState::Ready && loaded_gen_ == gen) start_lexicon_load_locked(cfg, gen);
            }
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
                job->done_cv.notify_all();
                if (job->partner) { job->partner->done = true; job->partner->done_cv.notify_all(); }
                stats_.jobs_dropped++;
                continue;
            }
            stats_.queue_ms.add(ms_between(job->submitted, Clock::now()));
            job->miss_kind = classify_miss_locked(job->ctx, now_ms());   // 按开始这一刻的准备状态归因
            lk.unlock();
            run_score(job);
            lk.lock();
            continue;
        }
        if (prepare_pending_) {
            prepare_pending_ = false;
            TokenSeq ctx = std::move(prepare_ctx_);
            if (ctx == last_prepared_) {   // 排队期间打分任务已经算过同一段上文（前台接手）：不再做
                track_prep_locked(ctx, kPrepDone, now_ms());
                continue;
            }
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
    fail_queue_locked();
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
    if (lexicon_thread_.joinable()) lexicon_thread_.join();   // 上一次加载的词典索引线程（它结束时要拿 mu_，此处不持锁）
    model_.reset();
    log_event("loading model: %s threads=%d", cfg.model_path.c_str(), cfg.n_threads);
    const Clock::time_point t0 = Clock::now();
    ModelOptions mo;
    mo.identify_model = cfg.identify_model;
    mo.model_path = cfg.model_path;
    mo.vocab_path = cfg.vocab_path;
    // 线程数不超过核数减一：线程数等于核数时，任何一个线程被别的进程抢占都会让整批解码停下来等它（实测偶发 50~90 ms 的毛刺）
    unsigned hw = std::thread::hardware_concurrency();
    mo.n_threads = cfg.n_threads;
    if (hw > 1 && (unsigned)mo.n_threads > hw - 1) mo.n_threads = (int)hw - 1;
    mo.max_candidates = cfg.max_candidates;
    mo.gen_beam = cfg.lexicon_dir.empty() ? 0 : std::max(0, cfg.gen_beam);
    mo.context_slots = kContextSlots;
    mo.candidate_max_chars = kCandidateMaxChars;
    mo.repack = cfg.repack;
    mo.poll = cfg.poll;
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
    // 进程退出时先停工作线程、释放模型，再让 ggml 的静态对象析构。atexit 的处理函数按注册的逆序执行，
    // 所以必须在 ggml 初始化（第一次加载模型）之后注册，否则它会排在 ggml 的析构之后，退出时正在算的解码会崩溃。
    static std::once_flag exit_hook;
    if (this == &shared_engine()) std::call_once(exit_hook, [] { std::atexit([] { shared_engine().shutdown(); }); });
    model_ = std::move(m);
    identity_ = model_->identity();
    vocab_ = std::make_shared<Vocab>(model_->vocab());
    loaded_gen_ = gen;
    state_ = EngineState::Ready;
    stats_.last_load_ms = ms;
    last_use_ms_ = now_ms();
    log_event("model ready in %.0f ms (threads=%d max_candidates=%d)", ms, model_->options().n_threads, cfg.max_candidates);
    if (mo.gen_beam > 0) start_lexicon_load_locked(cfg, gen);
}

// 候选生成的词典索引：模型就绪之后在单独的线程里读，打分与预解码不必等它；读不到或加载失败只影响生成。
// 读完若模型已换代或已卸载就丢弃。调用时持有 mu_。
void Engine::start_lexicon_load_locked(const EngineConfig & cfg, uint64_t gen) {
    std::shared_ptr<const Vocab> vocab = vocab_;
    lexicon_sig_ = lexicon_signature(cfg.lexicon_dir);   // 在读之前取：读的途中文件又变了的话，下一次核对会发现
    lexicon_thread_ = std::thread([this, cfg, gen, vocab] {
        const Clock::time_point tl = Clock::now();
        auto lex = std::make_shared<Lexicon>();
        std::string err;
        std::vector<std::string> files = lexicon_files(cfg.lexicon_dir);
        bool ok = lex->load(files, *vocab, kGenMinChars, kGenMaxChars, &err);
        double ms = ms_between(tl, Clock::now());
        std::lock_guard<std::mutex> lk(mu_);
        if (!ok) { log_event("WARN: %s", err.c_str()); return; }
        if (gen != loaded_gen_ || state_ != EngineState::Ready) return;
        if (cfg_.lexicon_dir != cfg.lexicon_dir) return;   // 读的期间方案又换了：丢弃，新目录的读取已排队
        lexicon_ = std::move(lex);
        lexicon_gen_++;
#if defined(__GLIBC__)
        malloc_trim(0);   // 解析词典时的临时内存还给系统（glibc 默认留着不还）
#endif
        log_event("lexicon ready: %zu words in %.0f ms", lexicon_->size(), ms);
    });
}

void Engine::do_unload(const char * why) {
    bool had = model_ != nullptr;
    model_.reset();
    std::lock_guard<std::mutex> lk(mu_);
    vocab_.reset();
    lexicon_.reset();
    cache_.clear();
    last_prepared_.clear();
    clear_prep_track_locked();
    fail_queue_locked();
    prepare_pending_ = false;
    if (state_ == EngineState::Ready) state_ = EngineState::Idle;
    if (had) {
        stats_.unloads++;
        log_event("model unloaded (%s)", why);
    }
    // 配置变更引起的卸载：之后由下一次请求按新配置加载
}

// 排队的任务一律以失败结束。捎带的生成任务不在队列里，要一并唤醒，否则它的等待方要等满时限
void Engine::fail_queue_locked() {
    auto fail = [](ScoreJob & j) { j.done = true; j.ok = false; j.done_cv.notify_all(); };
    for (auto & job : score_queue_) {
        fail(*job);
        if (job->partner) fail(*job->partner);
    }
    score_queue_.clear();
}

void Engine::run_score(const std::shared_ptr<ScoreJob> & job) {
    // 上文已在槽里、原生候选都只有一个 token 时，单独打分不需要解码，合成任务省不下任何计算，
    // 反而让打分结果等生成的逐层解码算完，时限紧时两边一起超时。这时拆开：先交付打分，再单独生成
    if (job->partner && model_ && model_->has_context(job->ctx) &&
        std::all_of(job->cand_tokens.begin(), job->cand_tokens.end(), [](const TokenSeq & t) { return t.size() <= 1; })) {
        auto gen = std::move(job->partner);
        {
            std::lock_guard<std::mutex> lk(mu_);
            stats_.shared_jobs--;
            stats_.split_jobs++;
        }
        run_score(job);
        gen->miss_kind = -1;   // 上文已命中，不参与归因
        run_score(gen);
        return;
    }
    DecodeStats st;
    std::vector<double> scores, gscores;
    const Clock::time_point t0 = Clock::now();
    const auto & pj = job->partner;
    bool ok = model_ && (pj ? model_->score_generate(job->ctx, job->cand_tokens, pj->cand_tokens, pj->beam, &scores, &gscores, &st)
                         : job->gen ? model_->generate(job->ctx, job->cand_tokens, job->beam, &scores, &st)
                                    : model_->score(job->ctx, job->cand_tokens, &scores, &st));
    if (ok && job->gen) scores = top_k_flat(scores, job->k);
    if (ok && pj) gscores = top_k_flat(gscores, pj->k);
    double ms = ms_between(t0, Clock::now());

    std::lock_guard<std::mutex> lk(mu_);
    stats_.jobs_done++;
    stats_.compute_ms.add(ms);
    stats_.cand_tokens += (uint64_t)st.cand_tokens;
    stats_.decode_calls += (uint64_t)st.decode_calls;
    if (pj) {
        if (ok) cache_.put(pj->key, gscores);
        pj->ok = ok;
        pj->scores = std::move(gscores);
        pj->done = true;
        pj->done_cv.notify_all();
    }
    if (st.ctx_hit) stats_.ctx_hit++;
    else if (st.ctx_partial) stats_.ctx_partial++;
    else stats_.ctx_full++;
    if (!st.ctx_hit && !st.ctx_partial && st.ctx_tokens >= 20) stats_.ctx_full_long++;
    if (!st.ctx_hit && st.ctx_tokens > 0 && job->miss_kind >= 0) {
        auto & m = stats_.ctx_miss[job->miss_kind];
        m.n++;
        if (st.ctx_tokens >= 20) m.long_n++;
        m.ms += st.ctx_ms;
        if (job->miss_kind == EngineStats::kMissNoPrepare || job->miss_kind == EngineStats::kMissOtherCtx) {
            miss_recent_.emplace_back(now_ms(), job->ctx);
            while (miss_recent_.size() > kPrepTrack) miss_recent_.pop_front();
        }
        // 每 10 分钟至多记一行累计值（进程内模块没有状态文件，桌面端靠日志看）。格式：次数/其中长上文
        const double t = now_ms();
        if (t - miss_logged_ms_ >= kMissLogIntervalMs) {
            miss_logged_ms_ = t;
            const auto * c = stats_.ctx_miss;
            log_event("ctx_miss no_prepare=%llu/%llu other_ctx=%llu/%llu queued=%llu/%llu replaced=%llu/%llu "
                      "evicted=%llu/%llu late=%llu hit=%llu",
                      (unsigned long long)c[0].n, (unsigned long long)c[0].long_n, (unsigned long long)c[1].n,
                      (unsigned long long)c[1].long_n, (unsigned long long)c[2].n, (unsigned long long)c[2].long_n,
                      (unsigned long long)c[3].n, (unsigned long long)c[3].long_n, (unsigned long long)c[4].n,
                      (unsigned long long)c[4].long_n, (unsigned long long)stats_.ctx_miss_late,
                      (unsigned long long)stats_.ctx_hit);
        }
    }
    if (ok) {
        cache_.put(job->key, scores);
        last_prepared_ = job->ctx;          // 打分时上文已在缓存里
        track_prep_locked(job->ctx, kPrepDone, now_ms(), true);
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
    if (st.ctx_hit) stats_.prep_hit++;
    else if (st.ctx_partial) stats_.prep_partial++;
    else if (ok) stats_.prep_full++;
    if (ok) last_prepared_ = ctx;
    track_prep_locked(ctx, ok ? kPrepDone : kPrepFailed, now_ms());
}

Engine & shared_engine() {
    static Engine * e = new Engine;   // 故意不析构：退出顺序由 atexit 里的 shutdown 控制
    return *e;
}

}  // namespace suzu
