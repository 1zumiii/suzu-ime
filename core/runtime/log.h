// 轻量日志。隐私约束：只记事件名、数字与文件路径，调用方不得传入任何输入内容、拼音或候选文字。
// 文件为 <目录>/rime_llm_log.txt，超过 1 MB 改名为 .old（只留一份）。目录为空时写到系统临时目录。
#pragma once

#include <string>

namespace suzu {

void log_set_dir(const std::string & dir);
std::string log_dir();
void log_event(const char * fmt, ...)
#if defined(__GNUC__)
    __attribute__((format(printf, 1, 2)))
#endif
    ;

}  // namespace suzu
