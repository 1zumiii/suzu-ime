// macOS、Linux、Android：不读取任何应用里的文字。鼠须管与同文的每个输入框是独立会话，commit_history 本就不跨会话。
#include "platform/ctxlink.h"

namespace suzu::ctxlink {

bool pick(std::string &, const char *&) { return false; }
void kick() {}
void edit_reset() {}
bool fg_changed() { return false; }
bool click_happened() { return false; }

}  // namespace suzu::ctxlink
