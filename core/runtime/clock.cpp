#include "runtime/clock.h"

#include <thread>

namespace suzu {

double now_ms() {
    return std::chrono::duration<double, std::milli>(Clock::now().time_since_epoch()).count();
}

void sleep_ms(double ms) {
    if (ms > 0) std::this_thread::sleep_for(std::chrono::duration<double, std::milli>(ms));
}

}  // namespace suzu
