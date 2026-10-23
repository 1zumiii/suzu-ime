// 光标上文旁路：从前台应用读取光标前的文字。Windows 经 UIA/COM（代码来自原项目 rime-llm-rerank），
// macOS 经辅助功能接口（platform/macos/ctxlink_mac.cpp）。Android 由客户端库另行提供（Trime 经 JNI 写入）。
// 其他平台是桩：不读取任何应用里的文字，Lua 侧回落到 Rime 自己的 commit_history。
#pragma once

#include <string>

namespace suzu::ctxlink {

// 取当前快照：成功返回 true，text 为光标前文字，source 为 "com"、"uia"（Windows）或 "ax"（macOS）
bool pick(std::string & text, const char *& source);
void kick();         // 上屏后：立刻刷新快照
void edit_reset();   // 编辑键或点击后：快照作废并延迟重读
void invalidate();   // 快照作废但不重读（英文模式直接打字：文字一直在变，等下次 pick 时再读）
bool fg_changed();   // 前台窗口自上次询问以来是否切换过
bool click_happened();   // 自上次询问以来是否有鼠标点击

}  // namespace suzu::ctxlink
