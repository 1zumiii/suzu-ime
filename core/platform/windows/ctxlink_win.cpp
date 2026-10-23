// Windows（小狼毫）的光标上文旁路：经 COM（Word/WPS 文档模型）或 UIA（TextPattern）读取前台应用光标前的文字，
// 另有前台窗口切换与鼠标点击检测，供 Lua 侧在这些时刻重置上文。
//
// 来源：原项目 rime-llm-rerank（https://github.com/zhanghaozhecn/rime-llm-rerank，署名许可，作者 zhanghaozhecn）
// cpp/rime_llm.cpp 的 ctxlink 一节与 lua_fg_changed、lua_click_happened，原样移入，只做了四处改动：
//   1. 命名空间改为 suzu::ctxlink::upstream，对外只暴露 platform/ctxlink.h 的接口；
//   2. 日志改用 runtime/log.h（只记应用名与事件，不含任何文字）；
//   3. 合入本仓库 windows 补丁里的隐私保护：焦点是密码框时不读上文；
//   4. 快照过期时补一次读取（见下面的 pick），填上 kick 之后 2.5～4 秒的空档；
//   5. 去掉每 2 秒一次的周期读取（2026-10-23）：只在事件发生时读（上屏、编辑键、点击、英文模式直接打字、
//      按键时快照缺失或过期），快照有效期相应放宽到 10 秒。周期读会定时打断前台应用的界面线程，
//      还让读取时机与输入无关，容易出难以复现的问题。
// 注意：这个文件只在 Windows 上编译，本仓库的持续验证环境没有 Windows，改动后须在小狼毫上实测。
#define NOMINMAX
#include <windows.h>
#include <objbase.h>
#include <oleauto.h>
#include <UIAutomationClient.h>
#include <UIAutomationCore.h>
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")
#pragma comment(lib, "UIAutomationCore.lib")

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "platform/ctxlink.h"
#include "runtime/log.h"

namespace suzu::ctxlink {

#define log_msg(...) ::suzu::log_event(__VA_ARGS__)

//（探针 scripts/probe_wps_ctx.cpp / probe_uia_ctx.cpp，源码版 llm_filter.cc
//  comctx 同源实现已真机验证）。单后台线程 STA：只由 kick 唤醒（commit
// 后 / 编辑键 / 点击，kick 唤醒统一延迟 ~50ms 再读；按键时补读不延迟），没有周期读；编辑键/点击
// 经 edit_reset 先失效再 kick（2026-09-12 信号层统一——快照制对非
// commit 编辑事件原本是盲区，旧快照 2.5s 新鲜窗内冒充真文）。
// lua 调用线程经 mutex 快照消费，COM/UIA 调用绝不在按键同步路径。
// ============================================================
namespace upstream {

static std::mutex g_mu;
static std::string g_com_text;                 // UTF-8
static unsigned long long g_com_stamp = 0;
static std::wstring g_com_title;               // 发布时的前台标题（消费端指纹）
static std::string g_uia_text;                 // UTF-8
static unsigned long long g_uia_stamp = 0;
static DWORD g_uia_pid = 0;                    // 快照来源焦点元素进程
static std::wstring g_uia_title;               // 发布时的前台标题（消费端指纹）

static std::mutex g_cv_mu;
static std::condition_variable g_cv;
static std::atomic<bool> g_kick{false};
// 最早可读的时刻：上屏、编辑键、点击的 kick 把它推到 50ms 之后（等应用处理完编辑）；按键时补读不动它。
// 用时刻而不是“这次立即读”的标志：同一次按键里常是 edit_reset 之后紧跟补读，标志会让补读把编辑的等待抹掉，
// 赶在应用处理退格之前读到旧文字（2026-10-24 审查发现）。
static std::atomic<unsigned long long> g_settle_until{0};
// 作废计数：edit_reset 与 invalidate 各加 1。读取开始时记下，发布时不一致就丢弃结果，
// 免得作废之前开始的读取把旧文字带着新时间戳发布出去
static std::atomic<unsigned> g_epoch{0};
static std::atomic<bool> g_started{false};
static std::atomic<unsigned long long> g_last_kick_ms{0};  // WPS 附着重试只在最近一次 kick 后的 3 秒内进行
static const unsigned long long kSnapshotMs = 10000;       // 快照有效期：文字变化都有事件，10 秒只是兜底

// 前台 Office 判定（2026-09-11 扩展演示）：OpusApp 同属 MS Word（开发代号
// Opus）与 WPS 文字（复刻 Word 窗口体系）；PP12FrameClass 同属 MS
// PowerPoint（2010+）与 WPS 演示（实测 12.1.0.28505）→ 两个类名圈定
// "文字处理器 + 演示文档"。表格 XLMAIN（复刻 Excel）刻意不含——单元格
// 编辑态 COM 盲区（Office 系通病：编辑态对象模型挂起、无 Selection.Start
// 等价物），输入法打字恰在盲区，落 UIA/历史兜底。候选窗不抢前台。
enum class OfficeKind { NONE, WRITER, PPT };
static OfficeKind foreground_office() {
    HWND fg = GetForegroundWindow();
    if (!fg) return OfficeKind::NONE;
    wchar_t cls[64];
    if (GetClassNameW(fg, cls, 64) <= 0) return OfficeKind::NONE;
    if (wcscmp(cls, L"OpusApp") == 0) return OfficeKind::WRITER;
    if (wcscmp(cls, L"PP12FrameClass") == 0) return OfficeKind::PPT;
    return OfficeKind::NONE;
}
static DWORD foreground_pid() {
    HWND fg = GetForegroundWindow();
    DWORD pid = 0;
    if (fg) GetWindowThreadProcessId(fg, &pid);
    return pid;
}

// ---- COM 读链（源码版 llm_filter.cc comctx 同源）----
static HRESULT disp_invoke(IDispatch * obj, const wchar_t * name, WORD flags,
                           DISPPARAMS * pdp, VARIANT * ret) {
    DISPID dispid = 0;
    HRESULT hr = obj->GetIDsOfNames(IID_NULL, (LPOLESTR *)&name, 1,
                                    LOCALE_USER_DEFAULT, &dispid);
    if (FAILED(hr)) return hr;
    VariantInit(ret);
    return obj->Invoke(dispid, IID_NULL, LOCALE_USER_DEFAULT, flags, pdp,
                       ret, nullptr, nullptr);
}
static HRESULT disp_get(IDispatch * obj, const wchar_t * name, VARIANT * ret) {
    DISPPARAMS dp;
    ZeroMemory(&dp, sizeof(dp));
    return disp_invoke(obj, name, DISPATCH_PROPERTYGET, &dp, ret);
}

// BSTR → UTF-8（COM 两读链与 UIA 读链共用；失败时 out 不变返回 false）
static bool bstr_to_utf8(BSTR s, std::string & out) {
    if (!s) return false;
    int n = WideCharToMultiByte(CP_UTF8, 0, s, -1, nullptr, 0,
                                nullptr, nullptr);
    if (n <= 0) return false;
    out.resize(n - 1);
    if (n > 1)
        WideCharToMultiByte(CP_UTF8, 0, s, -1, &out[0], n,
                            nullptr, nullptr);
    return true;
}

static void wlower(wchar_t * s) {
    for (; *s; ++s)
        if (*s >= L'A' && *s <= L'Z') *s = *s + 32;
}

// 文档名 ⊂ 前台标题校验（WPS 统一标签页防串档，2026-09-11 真机两轮定案）：
// WPS 多组件标签共用单一框架窗口，前台类名恒为 OpusApp（实测演示标签激活
// 仍 OpusApp、标题变"演示文稿1 - WPS Office"）——类名门控区分不了活动
// 组件。帧标题随活动标签文档名变，组件模型的 ActiveDocument.Name /
// ActivePresentation.Name 固定指本组件自己的活动文档：名字（全名与去
// 扩展名两种形态）都不在前台标题中 = 活动标签是另一组件（或前台是微软
// Office 而后台 WPS 同开），本组件读出的文本不属于当前光标处，判
// TAB_IDLE 落下一层。锚点不可用 Window.Caption——实测带修改标记尾缀
// " *" 且无扩展名剥不掉（"文字文稿1 *" ⊄ "文字文稿1 - WPS Office"）恒
// 误杀。名字取不到/去扩展名后过短（单字符易误配）→ 保守放行。
static bool name_matches_fg_title(const VARIANT & vname) {
    if (vname.vt != VT_BSTR || !vname.bstrVal || !*vname.bstrVal)
        return true;
    wchar_t full[128] = {0}, stem[128] = {0};
    const wchar_t * base = wcsrchr(vname.bstrVal, L'\\');
    base = base ? base + 1 : vname.bstrVal;
    wcsncpy_s(full, base, _TRUNCATE);
    wcsncpy_s(stem, base, _TRUNCATE);
    wchar_t * dot = wcsrchr(stem, L'.');
    if (dot && dot != stem) *dot = 0;
    if (wcslen(stem) < 2) return true;
    HWND fg = GetForegroundWindow();
    wchar_t title[256] = {0};
    if (!fg || GetWindowTextW(fg, title, 256) <= 0)
        return true;  // 标题取不到（罕见），保守放行
    wlower(full);
    wlower(stem);
    wlower(title);
    return wcsstr(title, full) != nullptr || wcsstr(title, stem) != nullptr;
}

enum class ComRead { OK, NO_DOC, DEAD, TAB_IDLE };

// 活动标签判定（不依赖文件名，2026-09-11 三轮真机定案）：文字组件视图
// 窗口（_WwB 类，ActiveWindow.Hwnd）的根窗口 == 前台窗口 && 视图可见
// = 文字标签/文字窗口活动。统一标签页：视图随活动标签显隐（实测即时
// 翻转）；多独立窗口：视图都可见，但只有活动窗口的根 == 前台（他窗/
// 他应用前台时根不等——微软 Word 前台场景由此天然排除，无需名字）。
// 句柄取不到 = UNKNOWN（回落名字校验兜底）。
enum class TabActive { YES, NO, UNKNOWN };
static TabActive writer_tab_active(IDispatch * app_w) {
    if (!app_w) return TabActive::UNKNOWN;
    VARIANT vwin, vh;
    VariantInit(&vwin); VariantInit(&vh);
    long long h = 0;
    if (SUCCEEDED(disp_get(app_w, L"ActiveWindow", &vwin)) &&
        vwin.vt == VT_DISPATCH && vwin.pdispVal &&
        SUCCEEDED(disp_get(vwin.pdispVal, L"Hwnd", &vh))) {
        if (vh.vt == VT_I4)      h = vh.lVal;
        else if (vh.vt == VT_I8) h = vh.llVal;
        else if (vh.vt == VT_R8) h = (long long)vh.dblVal;
    }
    VariantClear(&vwin); VariantClear(&vh);
    if (!h) return TabActive::UNKNOWN;
    HWND root = GetAncestor((HWND)(LONG_PTR)h, GA_ROOT);
    HWND fg = GetForegroundWindow();
    if (!root || !fg) return TabActive::UNKNOWN;
    if (root != fg) return TabActive::NO;
    return IsWindowVisible((HWND)(LONG_PTR)h) ? TabActive::YES : TabActive::NO;
}

static ComRead com_read_chain(IDispatch * app, std::string & out,
                              TabActive wta) {
    VARIANT vwin, vsel, vstart, vdoc, vrng, vtxt, vname;
    VariantInit(&vwin); VariantInit(&vsel); VariantInit(&vstart);
    VariantInit(&vdoc); VariantInit(&vrng); VariantInit(&vtxt);
    VariantInit(&vname);
    auto vt_ok = [](const VARIANT & v) {
        return v.vt == VT_DISPATCH && v.pdispVal;
    };
    HRESULT hr;
    auto cleanup = [&]() {
        VariantClear(&vwin); VariantClear(&vsel); VariantClear(&vstart);
        VariantClear(&vdoc); VariantClear(&vrng); VariantClear(&vtxt);
        VariantClear(&vname);
    };
    // 串档拦截①：视图窗口判定非活动（统一标签页切走/他窗前台）——
    // 不依赖文件名，同基名文档也能正确区分
    if (wta == TabActive::NO) {
        cleanup();
        return ComRead::TAB_IDLE;
    }
    if (FAILED(hr = disp_get(app, L"ActiveWindow", &vwin)) || !vt_ok(vwin)) {
        cleanup();
        if (hr == DISP_E_EXCEPTION || hr == DISP_E_MEMBERNOTFOUND)
            return ComRead::NO_DOC;
        return ComRead::DEAD;
    }
    // ActiveDocument 前移：名字校验（串档拦截②兜底）与下方 Range 共用
    if (FAILED(hr = disp_get(app, L"ActiveDocument", &vdoc)) ||
        !vt_ok(vdoc)) {
        cleanup();
        if (hr == DISP_E_EXCEPTION || hr == DISP_E_MEMBERNOTFOUND)
            return ComRead::NO_DOC;
        return ComRead::DEAD;
    }
    if (wta == TabActive::UNKNOWN) {
        // 视图句柄取不到时回落名字校验（YES 时有 root==fg 硬判据，跳过
        // 免受标题格式差异影响——Caption 轮误杀的教训）
        disp_get(vdoc.pdispVal, L"Name", &vname);
        if (!name_matches_fg_title(vname)) {
            cleanup();
            return ComRead::TAB_IDLE;
        }
    }
    if (FAILED(hr = disp_get(vwin.pdispVal, L"Selection", &vsel)) ||
        !vt_ok(vsel) ||
        FAILED(hr = disp_get(vsel.pdispVal, L"Start", &vstart)) ||
        vstart.vt != VT_I4) {
        cleanup();
        if (hr == DISP_E_EXCEPTION || hr == DISP_E_MEMBERNOTFOUND)
            return ComRead::NO_DOC;
        return ComRead::DEAD;
    }
    long pos = vstart.lVal, from = pos - 64;
    if (from < 0) from = 0;
    ComRead res = ComRead::OK;
    do {
        VARIANT args[2];  // rgvark 反序：末参数 (pos) 在前
        VariantInit(&args[0]); args[0].vt = VT_I4; args[0].lVal = pos;
        VariantInit(&args[1]); args[1].vt = VT_I4; args[1].lVal = from;
        DISPPARAMS dp;
        ZeroMemory(&dp, sizeof(dp));
        dp.rgvarg = args; dp.cArgs = 2;
        if (FAILED(hr = disp_invoke(vdoc.pdispVal, L"Range",
                                    DISPATCH_METHOD | DISPATCH_PROPERTYGET,
                                    &dp, &vrng)) || !vt_ok(vrng)) {
            res = (hr == DISP_E_EXCEPTION) ? ComRead::NO_DOC : ComRead::DEAD;
            break;
        }
        if (FAILED(hr = disp_get(vrng.pdispVal, L"Text", &vtxt)) ||
            vtxt.vt != VT_BSTR) {
            res = (hr == DISP_E_EXCEPTION) ? ComRead::NO_DOC : ComRead::DEAD;
            break;
        }
        bstr_to_utf8(vtxt.bstrVal, out);
    } while (false);
    cleanup();
    return res;
}

// ---- 演示读链（WPP/PowerPoint，2026-09-11 本机实测验证）----
// app.ActiveWindow.Selection.Type==3(文本编辑) 时：start = TextRange.Start
//（框内 1-based 偏移）；前文 = ShapeRange.TextFrame.TextRange.Characters(
// max(1,start-64), min(64, start-1)).Text。Type!=3 → NO_DOC 语义
//（无文本编辑：形状/幻灯片选择态）。表格 XLMAIN 不在此链（编辑态盲区）。
static ComRead ppt_read_chain(IDispatch * app, std::string & out,
                              TabActive wta) {
    VARIANT vwin, vsel, vtype, vstart, vshape, vframe, vrng, vtxt;
    VARIANT vpres, vname;
    VariantInit(&vwin); VariantInit(&vsel); VariantInit(&vtype);
    VariantInit(&vstart); VariantInit(&vshape); VariantInit(&vframe);
    VariantInit(&vrng); VariantInit(&vtxt);
    VariantInit(&vpres); VariantInit(&vname);
    auto vt_ok = [](const VARIANT & v) {
        return v.vt == VT_DISPATCH && v.pdispVal;
    };
    HRESULT hr;
    auto cleanup = [&]() {
        VariantClear(&vwin); VariantClear(&vsel); VariantClear(&vtype);
        VariantClear(&vstart); VariantClear(&vshape); VariantClear(&vframe);
        VariantClear(&vrng); VariantClear(&vtxt);
        VariantClear(&vpres); VariantClear(&vname);
    };
    // 串档拦截①：文字视图判定活动 = 活动标签必是文字（演示/表格都不是）
    if (wta == TabActive::YES) {
        cleanup();
        return ComRead::TAB_IDLE;
    }
    if (FAILED(hr = disp_get(app, L"ActiveWindow", &vwin)) || !vt_ok(vwin)) {
        cleanup();
        if (hr == DISP_E_EXCEPTION) return ComRead::NO_DOC;
        return ComRead::DEAD;
    }
    // 串档拦截②：演示无窗口句柄（WPP Window.Hwnd/HWND 均空，实测），
    // 名字校验是演示链主判据——挡表格标签/微软 Office 前台；同基名文档
    // 场景由拦截①的 wta 覆盖
    if (FAILED(hr = disp_get(app, L"ActivePresentation", &vpres)) ||
        !vt_ok(vpres)) {
        cleanup();
        if (hr == DISP_E_EXCEPTION) return ComRead::NO_DOC;
        return ComRead::DEAD;
    }
    disp_get(vpres.pdispVal, L"Name", &vname);
    if (!name_matches_fg_title(vname)) {
        cleanup();
        return ComRead::TAB_IDLE;
    }
    if (FAILED(hr = disp_get(vwin.pdispVal, L"Selection", &vsel)) ||
        !vt_ok(vsel) ||
        FAILED(hr = disp_get(vsel.pdispVal, L"Type", &vtype)) ||
        vtype.vt != VT_I4) {
        cleanup();
        if (hr == DISP_E_EXCEPTION) return ComRead::NO_DOC;
        return ComRead::DEAD;
    }
    if (vtype.lVal != 3) {  // ppSelectionText = 3：非文本编辑态
        cleanup();
        return ComRead::NO_DOC;
    }
    ComRead res = ComRead::OK;
    do {
        if (FAILED(hr = disp_get(vsel.pdispVal, L"TextRange", &vrng)) ||
            !vt_ok(vrng) ||
            FAILED(hr = disp_get(vrng.pdispVal, L"Start", &vstart)) ||
            vstart.vt != VT_I4) {
            res = (hr == DISP_E_EXCEPTION) ? ComRead::NO_DOC : ComRead::DEAD;
            break;
        }
        long start = vstart.lVal;               // 1-based
        long len = start - 1 > 64 ? 64 : start - 1;  // 前文字数（截 64）
        if (len <= 0) { out.clear(); break; }   // 光标在框首：前文真空
        if (FAILED(hr = disp_get(vsel.pdispVal, L"ShapeRange", &vshape)) ||
            !vt_ok(vshape) ||
            FAILED(hr = disp_get(vshape.pdispVal, L"TextFrame", &vframe)) ||
            !vt_ok(vframe)) {
            res = (hr == DISP_E_EXCEPTION) ? ComRead::NO_DOC : ComRead::DEAD;
            break;
        }
        VARIANT vfull;
        VariantInit(&vfull);
        if (FAILED(hr = disp_get(vframe.pdispVal, L"TextRange", &vfull)) ||
            !vt_ok(vfull)) {
            VariantClear(&vfull);
            res = (hr == DISP_E_EXCEPTION) ? ComRead::NO_DOC : ComRead::DEAD;
            break;
        }
        // Characters(start_index, length)：rgvark 反序 [length, start_index]
        VARIANT args[2];
        VariantInit(&args[0]); args[0].vt = VT_I4; args[0].lVal = len;
        VariantInit(&args[1]); args[1].vt = VT_I4;
        args[1].lVal = start - len;             // 起始字符 = start-len（含尾部）
        DISPPARAMS dp;
        ZeroMemory(&dp, sizeof(dp));
        dp.rgvarg = args; dp.cArgs = 2;
        VariantClear(&vrng);  // 复用作出参：先释放首个 TextRange 引用
        if (FAILED(hr = disp_invoke(vfull.pdispVal, L"Characters",
                                    DISPATCH_METHOD | DISPATCH_PROPERTYGET,
                                    &dp, &vrng)) || !vt_ok(vrng)) {
            VariantClear(&vfull);
            res = (hr == DISP_E_EXCEPTION) ? ComRead::NO_DOC : ComRead::DEAD;
            break;
        }
        VariantClear(&vfull);
        if (FAILED(hr = disp_get(vrng.pdispVal, L"Text", &vtxt)) ||
            vtxt.vt != VT_BSTR) {
            res = (hr == DISP_E_EXCEPTION) ? ComRead::NO_DOC : ComRead::DEAD;
            break;
        }
        bstr_to_utf8(vtxt.bstrVal, out);
    } while (false);
    cleanup();
    return res;
}

// ---- UIA 读链（2026-09-11 瘦身：~10 个跨进程往返 → 6 个）----
// 省法：①GetPattern 失败即无 TextPattern（去掉 IsTextPatternAvailable
// 属性往返）；②selection range 直接 Clone 后把 Start 端点前移 64 字
//（数学上等价于 [doc.Start, sel.Start) 截尾 64——去掉 DocumentRange +
// 2×MoveEndpointByRange 共 3 个往返）。每次跨进程往返 1-3ms 且打断
// 目标应用 UI 线程，读链瘦身同时降两边开销（真机 A/B：ctxlink 活跃
// 期 score 均值 +36%，2026-09-11 排查）。
static bool uia_read(IUIAutomation * uia, std::string & out, DWORD & pid) {
    IUIAutomationElement * el = nullptr;
    if (FAILED(uia->GetFocusedElement(&el)) || !el)
        return false;
    bool ret = false;
    int elpid = 0;
    el->get_CurrentProcessId(&elpid);
    // [本地补丁] 隐私：焦点是密码框（IsPassword）一律不读上文；返回 false 使调用方清空快照
    {
        BOOL is_pw = FALSE;
        if (SUCCEEDED(el->get_CurrentIsPassword(&is_pw)) && is_pw) {
            el->Release();
            return false;
        }
    }
    IUIAutomationTextPattern * tp = nullptr;
    if (SUCCEEDED(el->GetCurrentPattern(UIA_TextPatternId,
                                        (IUnknown **)&tp)) && tp) {
        IUIAutomationTextRangeArray * sel = nullptr;
        if (SUCCEEDED(tp->GetSelection(&sel)) && sel) {
            IUIAutomationTextRange * s0 = nullptr;
            int nsel = 0;
            sel->get_Length(&nsel);
            if (nsel > 0) sel->GetElement(0, &s0);
            sel->Release();
            if (s0) {
                IUIAutomationTextRange * r = nullptr;
                if (SUCCEEDED(s0->Clone(&r))) {
                    // r = [光标(或选区起点)-64 字, 光标]：Start 端点前移
                    int moved = 0;
                    r->MoveEndpointByUnit(TextPatternRangeEndpoint_Start,
                                          TextUnit_Character, -64, &moved);
                    BSTR txt = nullptr;
                    if (SUCCEEDED(r->GetText(-1, &txt)) && txt) {
                        ret = bstr_to_utf8(txt, out);
                        SysFreeString(txt);
                    }
                    r->Release();
                }
                s0->Release();
            }
        }
        tp->Release();
    }
    if (ret && elpid) pid = (DWORD)elpid;
    el->Release();
    return ret;
}

static void thread_proc() {
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

    IUIAutomation * uia = nullptr;
    bool uia_ok = SUCCEEDED(CoCreateInstance(CLSID_CUIAutomation, nullptr,
                                             CLSCTX_INPROC_SERVER,
                                             IID_IUIAutomation,
                                             (void **)&uia));
    // 仅 WPS 系 ProgID（2026-09-11 定案）：COM 旁路只补 TSF/UIA 墙上唯一
    // 缺口=WPS（微软 Word/PPT 的 TSF/UIA 完整，无需 COM）。类名二义由
    // 附着成败天然消解：Kwps 附着成功=WPS 文字（用 COM）；失败=微软 Word
    // 或无文档（落 UIA/历史）——Word/PPT 的 ProgID 回落已撤（共存双注册
    // 误附风险随之归零）。
    CLSID clsid_kwps, clsid_kwpp;
    bool has_kwps = SUCCEEDED(CLSIDFromProgID(L"Kwps.Application",
                                              &clsid_kwps));
    bool has_kwpp = SUCCEEDED(CLSIDFromProgID(L"KWPP.Application",
                                              &clsid_kwpp));
    if (!uia_ok && !has_kwps && !has_kwpp) {
        CoUninitialize();  // 与 CoInitializeEx 配对（无任何通道，极罕见）
        return;
    }

    // 双组件持久附着 + 探读（2026-09-11 串档修复）：WPS 统一标签页的框架
    // 类名不随活动标签组件变（真机实测演示标签激活仍 OpusApp），旧"前台
    // 类名选单附着"设计失效——文字附着会一直读出文字文档全文冒充演示上
    // 文。改为 Kwps/KWPP 双附着，每轮按"前台类型对位优先、另一组件兜底"
    // 顺序读，由读链标题校验（TAB_IDLE）裁决哪个组件的活动文档才是当前
    // 光标处。DEAD（宿主退出）单独释放该组件下轮懒重附；双双无产出 →
    // 清缓存（消费端落 UIA/历史），未附着时 500ms 快速重试不变。
    IDispatch * app_w = nullptr;  // Kwps.Application（文字）
    IDispatch * app_p = nullptr;  // KWPP.Application（演示）
    bool logged_w = false, logged_p = false;
    bool com_pending = true;  // 前台 Office 且未附着 → 500ms 快速重试
                              //（文档打开瞬间 WPS 才注册 ROT，缩短发现间隙；
                              //  成功即回 2s，开销仅微秒级 ROT 查询）
    for (;;) {
        {
            // 没有周期读：只等 kick。唯一的定时唤醒是 WPS 附着重试（前台是 Office 但还没附着上，
            // 文档刚打开时 WPS 才注册 ROT），且只在最近一次 kick 后的 3 秒内，每 500ms 一次
            std::unique_lock<std::mutex> lk(g_cv_mu);
            bool retry = com_pending && GetTickCount64() - g_last_kick_ms.load() < 3000;
            if (retry)
                g_cv.wait_for(lk, std::chrono::milliseconds(500), [] { return g_kick.load(); });
            else
                g_cv.wait(lk, [] { return g_kick.load(); });
        }
        bool woken_by_kick = g_kick.exchange(false);
        com_pending = false;
        // 编辑键/点击/上屏的 kick 延迟 ~50ms 再读（2026-09-12 信号层统一）：立即读会赶在应用处理完
        // 退格/点击之前拿到旧文本。下一键远晚于 100ms（人手速度），延迟无感。等待期间又来的编辑 kick
        // 会把时刻往后推，这里一直等到最后那个时刻。按键时补读（快照缺失或过期）不推时刻，没有编辑在等时立即读。
        for (;;) {
            unsigned long long now = GetTickCount64(), until = g_settle_until.load();
            if (now >= until) break;
            Sleep((DWORD)(until - now));
        }
        unsigned epoch = g_epoch.load();

        // COM：前台 Office 才读（读端门控省后台空转；切标签/切窗由发布
        // 标题指纹在消费端即刻拦截，kick/周期兜住换新）
        if (has_kwps || has_kwpp) {
            OfficeKind fg = foreground_office();
            if (fg != OfficeKind::NONE) {
                // 文字附着先行：ActiveWindow.Hwnd 的视图窗口判定是活动
                // 标签主信号（无文字文档时 UNKNOWN 回落名字校验）
                if (has_kwps && !app_w) {
                    IUnknown * punk = nullptr;
                    if (SUCCEEDED(GetActiveObject(clsid_kwps, nullptr,
                                                  &punk)) &&
                        punk) {
                        punk->QueryInterface(IID_IDispatch, (void **)&app_w);
                        punk->Release();
                        if (app_w && !logged_w) {
                            log_msg("ctx probe: com attached (Kwps.Application)");
                            logged_w = true;
                        }
                    }
                }
                TabActive wta = writer_tab_active(app_w);
                if (wta == TabActive::NO && app_w) {
                    // 跨进程标签切换重附（2026-09-11 四轮真机定案）：新建
                    // 文档/演示标签各起新 wps.exe、各持帧窗口，仅活动标签
                    // 所属进程的帧可见——旧附着实例的视图 root≠前台恒 NO。
                    // 实测 GetActiveObject 返回最新激活实例（sees 跟随活动
                    // 标签切换），重附一次即取到活动实例，仍 NO 才真非活动
                    //（表格标签/他应用前台）。GetActiveObject 微秒级，每轮
                    // 至多重附一次。
                    app_w->Release();
                    app_w = nullptr;
                    if (has_kwps) {
                        IUnknown * punk = nullptr;
                        if (SUCCEEDED(GetActiveObject(clsid_kwps, nullptr,
                                                      &punk)) &&
                            punk) {
                            punk->QueryInterface(IID_IDispatch,
                                                 (void **)&app_w);
                            punk->Release();
                        }
                    }
                    wta = writer_tab_active(app_w);
                }
                OfficeKind order[2] = {
                    fg, (fg == OfficeKind::WRITER) ? OfficeKind::PPT
                                                   : OfficeKind::WRITER};
                bool published = false;
                for (int i = 0; i < 2 && !published; i++) {
                    bool is_p = (order[i] == OfficeKind::PPT);
                    if (is_p ? !has_kwpp : !has_kwps) continue;
                    IDispatch *& app = is_p ? app_p : app_w;
                    bool & logged = is_p ? logged_p : logged_w;
                    if (!app) {
                        IUnknown * punk = nullptr;
                        if (SUCCEEDED(GetActiveObject(
                                is_p ? clsid_kwpp : clsid_kwps, nullptr,
                                &punk)) &&
                            punk) {
                            punk->QueryInterface(IID_IDispatch, (void **)&app);
                            punk->Release();
                        }
                        if (app && !logged) {
                            log_msg("ctx probe: com attached (%s)",
                                    is_p ? "KWPP.Application"
                                         : "Kwps.Application");
                            logged = true;
                        }
                    }
                    if (!app) continue;
                    std::string txt;
                    ComRead r = is_p ? ppt_read_chain(app, txt, wta)
                                     : com_read_chain(app, txt, wta);
                    if (r == ComRead::OK) {
                        wchar_t wt[256] = {0};
                        HWND fh = GetForegroundWindow();
                        if (fh) GetWindowTextW(fh, wt, 256);
                        std::lock_guard<std::mutex> lk(g_mu);
                        published = true;
                        if (g_epoch.load() != epoch) break;   // 读的期间快照被作废：丢弃，已排队的 kick 会重读
                        g_com_text = txt;
                        g_com_stamp = GetTickCount64();
                        g_com_title = wt;  // 消费端指纹：标题变=切标签/切窗
                    } else if (r == ComRead::DEAD) {
                        bool & logged = is_p ? logged_p : logged_w;
                        if (logged) {
                            log_msg("ctx probe: office app gone, "
                                    "will re-attach");
                            logged = false;
                        }
                        app->Release();
                        app = nullptr;
                    }
                    // NO_DOC（文档全关/演示非文本编辑态）与 TAB_IDLE（活动
                    // 标签非本组件）：保留附着，试另一组件
                }
                if (!published) {
                    std::lock_guard<std::mutex> lk(g_mu);
                    g_com_text.clear();
                    g_com_stamp = 0;
                    g_com_title.clear();
                }
            } else {
                std::lock_guard<std::mutex> lk(g_mu);
                g_com_text.clear();
                g_com_stamp = 0;  // 前台非 Office：COM 缓存即刻失效
                g_com_title.clear();
            }
            com_pending = (fg != OfficeKind::NONE) && !app_w && !app_p;
        }

        // UIA：读当前焦点元素（不限前台是谁——消费时校验进程一致），只在 kick 时读（WPS 附着重试的唤醒不读 UIA）。
        if (uia_ok && woken_by_kick) {
            std::string txt;
            DWORD pid = 0;
            if (uia_read(uia, txt, pid)) {
                wchar_t ut[256] = {0};
                HWND ufh = GetForegroundWindow();
                if (ufh) GetWindowTextW(ufh, ut, 256);
                std::lock_guard<std::mutex> lk(g_mu);
                if (g_epoch.load() != epoch) continue;   // 读的期间快照被作废：丢弃，已排队的 kick 会重读
                g_uia_text = txt;
                g_uia_stamp = GetTickCount64();
                g_uia_pid = pid;
                g_uia_title = ut;  // 消费端指纹：同进程多窗/标签切换即变
            } else {
                std::lock_guard<std::mutex> lk(g_mu);
                g_uia_text.clear();
                g_uia_stamp = 0;
                g_uia_pid = 0;
                g_uia_title.clear();
            }
        }
    }
}

static void ensure_started() {
    bool expect = false;
    if (g_started.compare_exchange_strong(expect, true))
        std::thread(thread_proc).detach();
}
// settle：上屏、编辑键、点击的 kick 要等应用处理完再读（推后最早可读时刻）；按键时补读传 false
static void kick(bool settle = true) {
    unsigned long long now = GetTickCount64();
    g_last_kick_ms.store(now);
    if (settle) {
        unsigned long long until = now + 50, cur = g_settle_until.load();
        while (cur < until && !g_settle_until.compare_exchange_weak(cur, until)) {}
    }
    {
        std::lock_guard<std::mutex> lk(g_cv_mu);   // 与 wait 的谓词配对，避免置位与等待之间的唤醒丢失
        g_kick.store(true);
    }
    g_cv.notify_all();
}

// 任何"光标前文本可能变了"的已知信号（编辑键/点击，2026-09-12 信号层
// 统一）：成对执行 (a) 快照失效 (b) kick 延迟重读。失效保证 50ms 读回
// 窗口内旧快照不冒充（pick 拒用 → lua 落历史）；kick 保证应用处理完编辑
// 后读到新真文。缺失效则读早竞态冒充，缺 kick 则退格后首词无上文。
// 点击候选窗选词场景：click 信号废掉 commit kick 刚刷新的正确快照，
// 但随后的 kick 读回同样正确的文本（点击已处理完、含新上屏词），自愈。
static void invalidate() {
    std::lock_guard<std::mutex> lk(g_mu);
    g_epoch.fetch_add(1);
    g_com_text.clear();
    g_com_stamp = 0;
    g_com_title.clear();
    g_uia_text.clear();
    g_uia_stamp = 0;
    g_uia_pid = 0;
    g_uia_title.clear();
}
static void edit_reset() {
    invalidate();
    kick();
}

// lua 调用线程消费：三层前两层判定（历史兜底由 lua 侧完成）。
// 返回 true 时 text/src 有效（src = "com" | "uia"）。
static bool pick(std::string & text, const char *& src) {
    unsigned long long now = GetTickCount64();
    if (foreground_office() != OfficeKind::NONE) {
        // 标题指纹：发布后前台标题变了（WPS 切标签/切窗即换标题）→ 旧快照
        // 属于旧标签，拒用——挡住 ≤2s 轮询间隙的串档（commit kick 会立刻
        // 换上新标签文本）。任一侧标题取不到 → 保守放行
        wchar_t t[256] = {0};
        HWND fh = GetForegroundWindow();
        bool title_ok =
            (!fh || GetWindowTextW(fh, t, 256) <= 0);
        std::lock_guard<std::mutex> lk(g_mu);
        if (g_com_stamp && now - g_com_stamp <= kSnapshotMs && !g_com_text.empty() &&
            (title_ok || g_com_title.empty() || g_com_title == t)) {
            text = g_com_text;
            src = "com";
            return true;
        }
    }
    {
        // UIA 快照：新鲜 && 前台进程 == 快照来源进程（防切窗残留文本误用）
        // + 标题指纹（2026-09-12）：pid 校验挡不住同进程多窗/多标签（Chrome
        // 双窗、Win11 记事本标签同 pid）——发布后标题变 = 已切窗/标签 →
        // 拒用，与 COM 同款；任一侧取不到标题 → 保守放行
        DWORD fg = foreground_pid();
        wchar_t t[256] = {0};
        HWND fh = GetForegroundWindow();
        bool title_ok = (!fh || GetWindowTextW(fh, t, 256) <= 0);
        std::lock_guard<std::mutex> lk(g_mu);
        if (g_uia_stamp && now - g_uia_stamp <= kSnapshotMs && !g_uia_text.empty() &&
            g_uia_pid != 0 && g_uia_pid == fg &&
            (title_ok || g_uia_title.empty() || g_uia_title == t)) {
            text = g_uia_text;
            src = "uia";
            return true;
        }
    }
    return false;
}

}  // namespace upstream

#undef log_msg

// ---------------------------------------------------------------- 对外接口

// 按键时快照缺失或过期（新窗口、停顿超过 10 秒、编辑后快照已失效）：补读一次（每秒最多一次），这一键回落到上屏历史，
// 下一键（一般 100 ms 以后）就能拿到新快照。没有周期读之后，这是“停顿后第一个键”拿到上文的唯一途径。
// 补读不推后最早可读时刻：没有编辑在等时立即读；刚有编辑键时仍等满它的 50ms（同一次按键里 edit_reset 之后紧跟补读是常态）。
bool pick(std::string & text, const char *& source) {
    upstream::ensure_started();
    if (upstream::pick(text, source)) return true;
    static std::atomic<unsigned long long> last_refresh{0};
    unsigned long long now = GetTickCount64();
    if (now - last_refresh.load() >= 1000) {
        last_refresh.store(now);
        upstream::kick(false);
    }
    return false;
}

void kick() { upstream::kick(); }

void edit_reset() {
    upstream::ensure_started();
    upstream::edit_reset();
}

void invalidate() { upstream::invalidate(); }

// 前台窗口切换（跨应用，以及同一应用的多窗口/多标签：pid 加 HWND 双比对）
bool fg_changed() {
    static DWORD last_pid = 0;
    static HWND last_hwnd = nullptr;
    DWORD cur_pid = upstream::foreground_pid();
    HWND cur_hwnd = GetForegroundWindow();
    bool changed = last_pid != 0 && cur_pid != 0 && (cur_pid != last_pid || cur_hwnd != last_hwnd);
    last_pid = cur_pid;
    last_hwnd = cur_hwnd;
    return changed;
}

// 鼠标点击：GetAsyncKeyState 的最低位表示自本进程上次查询以来按下过；首次调用只建立基线
bool click_happened() {
    static bool inited = false;
    short b = GetAsyncKeyState(VK_LBUTTON) | GetAsyncKeyState(VK_RBUTTON) | GetAsyncKeyState(VK_MBUTTON);
    bool clicked = inited && (b & 1);
    inited = true;
    return clicked;
}

}  // namespace suzu::ctxlink
