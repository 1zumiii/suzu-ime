#include "runtime/stats.h"

#include <algorithm>
#include <cmath>
#include <cstdio>

namespace suzu {

void LatencyStats::add(double ms) {
    if (ring_.size() < kWindow) ring_.push_back((float)ms);
    else ring_[next_] = (float)ms;
    next_ = (next_ + 1) % kWindow;
    count_++;
    sum_ += ms;
    if (ms > max_) max_ = ms;
}

double LatencyStats::percentile(double p) const {
    if (ring_.empty()) return 0;
    std::vector<float> v(ring_);
    size_t k = (size_t)std::max(0.0, std::ceil(p * (double)v.size()) - 1);
    if (k >= v.size()) k = v.size() - 1;
    std::nth_element(v.begin(), v.begin() + (long)k, v.end());
    return v[k];
}

std::string LatencyStats::json() const {
    char b[160];
    snprintf(b, sizeof b, "{\"n\":%llu,\"mean\":%.2f,\"p50\":%.2f,\"p99\":%.2f,\"max\":%.2f}",
             (unsigned long long)count_, mean(), percentile(0.5), percentile(0.99), max_);
    return b;
}

std::string EngineStats::json() const {
    char b[1024];
    snprintf(b, sizeof b,
             "\"requests\":%llu,\"ok\":%llu,\"cache_hits\":%llu,\"skipped\":%llu,"
             "\"skip\":{\"timeout\":%llu,\"loading\":%llu,\"no_model\":%llu,\"off\":%llu,\"error\":%llu},"
             "\"no_context\":%llu,\"few_candidates\":%llu,"
             "\"prepares\":%llu,\"jobs_dropped\":%llu,\"loads\":%llu,\"load_failures\":%llu,\"unloads\":%llu,"
             "\"decode_failures\":%llu,\"ctx\":{\"hit\":%llu,\"partial\":%llu,\"full\":%llu},\"last_load_ms\":%.0f,",
             (unsigned long long)requests, (unsigned long long)ok, (unsigned long long)cache_hits,
             (unsigned long long)skipped(), (unsigned long long)skip_timeout, (unsigned long long)skip_loading,
             (unsigned long long)skip_no_model, (unsigned long long)skip_off, (unsigned long long)skip_error,
             (unsigned long long)no_context, (unsigned long long)few_candidates, (unsigned long long)prepares,
             (unsigned long long)jobs_dropped, (unsigned long long)loads, (unsigned long long)load_failures,
             (unsigned long long)unloads, (unsigned long long)decode_failures, (unsigned long long)ctx_hit,
             (unsigned long long)ctx_partial, (unsigned long long)ctx_full, last_load_ms);
    return std::string(b) + "\"wait_ms\":" + wait_ms.json() + ",\"queue_ms\":" + queue_ms.json() +
           ",\"compute_ms\":" + compute_ms.json() + ",\"prepare_ms\":" + prepare_ms.json();
}

}  // namespace suzu
