// macOS 的光标上文旁路：经辅助功能接口（AXUIElement）读前台应用里焦点输入框光标前的文字。
// 需要用户在“系统设置 → 隐私与安全性 → 辅助功能”里给鼠须管授权；没授权时不读，Lua 回落到上屏历史。
//
// 做法（与 Windows 版同一思路，简化）：
//   1. 后台线程读，输入线程只取快照，不等跨进程调用；每次 AX 调用的超时为 50 ms。
//   2. 上屏（kick）、编辑键（edit_reset）之后延迟约 50 ms 再读，等应用先处理完；edit_reset 先让旧快照作废。
//   3. 快照只在 1.5 秒内有效；pick 发现快照超过 0.2 秒就请后台线程补读，读到之前先用旧快照或回落历史。
//      停顿后的第一键因此没有上文，但一个字母的输入不触发模型（code_pattern 至少 2 个字母），
//      第二键时后台读取已经完成（2026-10-22 TextEdit 实测）。不在输入线程上同步读，避免慢应用拖住按键。
//   4. 鼠须管开了 inline_preedit 时，打字中的拼音会显示在应用里，读到的文字末尾可能带着拼音：
//      去掉末尾连续的英文字母、撇号和空格（末尾真有英文单词时一并去掉，字符级模型本来也用不上英文）。
//   5. 密码框（AXSecureTextField）不读。读到的文字只在内存里，不写文件、不记日志。
#include "platform/ctxlink.h"

#include <ApplicationServices/ApplicationServices.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

namespace suzu::ctxlink {
namespace {

constexpr long kMaxChars = 60;          // 读光标前多少个 UTF-16 单元
constexpr long long kValidMs = 1500;    // 快照有效期
constexpr long long kRefreshMs = 200;   // 快照超过这个年龄，pick 就请后台线程补读
constexpr long long kSettleMs = 50;     // kick 与 edit_reset 之后等应用处理完再读
constexpr float kAxTimeoutSec = 0.05f;
constexpr long long kTrustRecheckMs = 30000;

long long now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::mutex mu;
std::condition_variable cv;
std::string snap;              // 最近一次读到的文字（已去掉末尾拼音）
bool snap_ok = false;          // 快照是否可用（读失败、作废后为 false）
long long snap_at = 0;
bool want_read = false;        // 有读取请求
long long read_not_before = 0; // 请求的最早执行时间（kick、edit_reset 要等应用处理完）
std::once_flag started;

bool trusted() {
    static std::atomic<long long> checked_at{-kTrustRecheckMs};
    static std::atomic<bool> ok{false};
    static std::atomic<bool> prompted{false};
    long long t = now_ms();
    if (ok.load()) return true;
    if (t - checked_at.load() < kTrustRecheckMs) return false;
    checked_at.store(t);
    bool prompt = !prompted.exchange(true);   // 只在第一次弹系统的授权提示
    const void * keys[] = {kAXTrustedCheckOptionPrompt};
    const void * vals[] = {prompt ? kCFBooleanTrue : kCFBooleanFalse};
    CFDictionaryRef opts = CFDictionaryCreate(nullptr, keys, vals, 1, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    bool r = AXIsProcessTrustedWithOptions(opts);
    CFRelease(opts);
    ok.store(r);
    return r;
}

std::string to_utf8(CFStringRef s) {
    if (!s) return {};
    CFIndex n = CFStringGetLength(s);
    CFIndex cap = CFStringGetMaximumSizeForEncoding(n, kCFStringEncodingUTF8) + 1;
    std::string out((size_t)cap, '\0');
    if (!CFStringGetCString(s, &out[0], cap, kCFStringEncodingUTF8)) return {};
    out.resize(strlen(out.c_str()));
    return out;
}

// 去掉末尾连续的英文字母、撇号与空白（inline_preedit 显示在应用里的拼音）
std::string strip_preedit(std::string t) {
    size_t n = t.size();
    while (n > 0) {
        unsigned char c = (unsigned char)t[n - 1];
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '\'' || c == ' ' || c == '\t') n--;
        else break;
    }
    t.resize(n);
    return t;
}

// 读一次；成功返回 true。失败（没授权、没有焦点输入框、密码框、应用不支持）返回 false
bool read_once(std::string & out) {
    if (!trusted()) return false;
    AXUIElementRef sys = AXUIElementCreateSystemWide();
    AXUIElementSetMessagingTimeout(sys, kAxTimeoutSec);
    AXUIElementRef el = nullptr;
    bool ok = false;
    if (AXUIElementCopyAttributeValue(sys, kAXFocusedUIElementAttribute, (CFTypeRef *)&el) == kAXErrorSuccess && el) {
        AXUIElementSetMessagingTimeout(el, kAxTimeoutSec);
        CFStringRef sub = nullptr;
        bool secure = false;
        if (AXUIElementCopyAttributeValue(el, kAXSubroleAttribute, (CFTypeRef *)&sub) == kAXErrorSuccess && sub) {
            secure = CFGetTypeID(sub) == CFStringGetTypeID() && CFEqual(sub, kAXSecureTextFieldSubrole);
            CFRelease(sub);
        }
        AXValueRef rv = nullptr;
        CFRange sel{0, 0};
        if (!secure && AXUIElementCopyAttributeValue(el, kAXSelectedTextRangeAttribute, (CFTypeRef *)&rv) == kAXErrorSuccess && rv) {
            if (AXValueGetType(rv) == kAXValueTypeCFRange && AXValueGetValue(rv, kAXValueTypeCFRange, &sel) && sel.location >= 0) {
                CFRange want{sel.location > kMaxChars ? sel.location - kMaxChars : 0, 0};
                want.length = sel.location - want.location;
                if (want.length == 0) {
                    out.clear();
                    ok = true;
                } else {
                    AXValueRef wv = AXValueCreate(kAXValueTypeCFRange, &want);
                    CFTypeRef str = nullptr;
                    if (AXUIElementCopyParameterizedAttributeValue(el, kAXStringForRangeParameterizedAttribute, wv, &str) == kAXErrorSuccess &&
                        str && CFGetTypeID(str) == CFStringGetTypeID()) {
                        out = to_utf8((CFStringRef)str);
                        ok = true;
                    } else {
                        // 有的应用不支持按范围取字：退回整段值再截取（过长的不取）
                        CFTypeRef val = nullptr;
                        if (AXUIElementCopyAttributeValue(el, kAXValueAttribute, &val) == kAXErrorSuccess && val &&
                            CFGetTypeID(val) == CFStringGetTypeID() && CFStringGetLength((CFStringRef)val) >= sel.location &&
                            CFStringGetLength((CFStringRef)val) < 200000) {
                            CFStringRef part = CFStringCreateWithSubstring(nullptr, (CFStringRef)val, want);
                            out = to_utf8(part);
                            CFRelease(part);
                            ok = true;
                        }
                        if (val) CFRelease(val);
                    }
                    if (str) CFRelease(str);
                    CFRelease(wv);
                }
            }
            CFRelease(rv);
        }
        CFRelease(el);
    }
    CFRelease(sys);
    if (ok) out = strip_preedit(out);
    return ok;
}

void worker() {
    std::unique_lock<std::mutex> lk(mu);
    for (;;) {
        cv.wait(lk, [] { return want_read; });
        long long wait = read_not_before - now_ms();
        if (wait > 0) {
            lk.unlock();
            std::this_thread::sleep_for(std::chrono::milliseconds(wait));
            lk.lock();
            if (read_not_before > now_ms()) continue;   // 等待期间又来了新的延迟请求
        }
        want_read = false;
        lk.unlock();
        std::string t;
        bool ok = read_once(t);
        long long at = now_ms();
        lk.lock();
        if (want_read && read_not_before > at) continue;   // 读的期间有新的 kick 或 edit_reset，这次结果作废
        snap = ok ? t : std::string();
        snap_ok = ok;
        snap_at = at;
    }
}

void request(long long delay_ms) {
    std::call_once(started, [] { std::thread(worker).detach(); });
    std::lock_guard<std::mutex> lk(mu);
    long long t = now_ms() + delay_ms;
    if (!want_read || t > read_not_before) read_not_before = t;
    want_read = true;
    cv.notify_one();
}

}  // namespace

bool pick(std::string & text, const char *& source) {
    bool refresh = false, have = false;
    {
        std::lock_guard<std::mutex> lk(mu);
        long long age = now_ms() - snap_at;
        refresh = !want_read && (!snap_ok || age > kRefreshMs);
        if (snap_ok && age <= kValidMs && !snap.empty()) {
            text = snap;
            have = true;
        }
    }
    if (refresh) request(0);
    if (have) source = "ax";
    return have;
}

void kick() { request(kSettleMs); }

void edit_reset() {
    {
        std::lock_guard<std::mutex> lk(mu);
        snap_ok = false;
    }
    request(kSettleMs);
}

bool fg_changed() { return false; }
bool click_happened() { return false; }

}  // namespace suzu::ctxlink
