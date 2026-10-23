// Linux 等其他平台：不读取任何应用里的文字，Lua 回落到 commit_history。
#include "platform/ctxlink.h"

namespace suzu::ctxlink {

bool pick(std::string &, const char *&) { return false; }
void kick() {}
void edit_reset() {}
void invalidate() {}
bool fg_changed() { return false; }
bool click_happened() { return false; }

}  // namespace suzu::ctxlink
