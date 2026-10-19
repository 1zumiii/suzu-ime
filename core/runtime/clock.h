// 单调时钟（毫秒，带小数）
#pragma once

#include <chrono>

namespace suzu {

using Clock = std::chrono::steady_clock;

double now_ms();
inline double ms_between(Clock::time_point a, Clock::time_point b) {
    return std::chrono::duration<double, std::milli>(b - a).count();
}
void sleep_ms(double ms);

}  // namespace suzu
