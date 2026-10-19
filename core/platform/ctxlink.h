// 光标上文旁路：从前台应用读取光标前的文字（只有 Windows 版实现，经 UIA/COM，代码来自上游 rime-llm-rerank）。
// 其他平台是桩：不读取任何应用里的文字，Lua 侧回落到 Rime 自己的 commit_history。
#pragma once

#include <string>

namespace suzu::ctxlink {

// 取当前快照：成功返回 true，text 为光标前文字，source 为 "com" 或 "uia"
bool pick(std::string & text, const char *& source);
void kick();         // 上屏后：立刻刷新快照
void edit_reset();   // 编辑键或点击后：快照作废并延迟重读
bool fg_changed();   // 前台窗口自上次询问以来是否切换过
bool click_happened();   // 自上次询问以来是否有鼠标点击

}  // namespace suzu::ctxlink
