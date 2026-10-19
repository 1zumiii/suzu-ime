// 线路协议：请求与应答的字段约定。第一个字段的第一个字节是操作码。
//
//   请求                                  应答
//   B                                     PONG                    心跳，不需要认证（监督进程用）
//   H <令牌>                              OK <pid> <版本> | ERR auth
//   P <上文>                              1 | 0                   预解码（立即返回）
//   S <allow_sync> <上文> <候选>…         R <候选> <分数>… | N <原因>      打分，时限由守护进程按 allow_sync 取默认值（版本 1 的客户端）
//   Q <时限毫秒> <上文> <候选>…           同上                    打分，时限由客户端给出（版本 2）
//   I                                     1 | 0                   模型是否就绪
//   T                                     OK                      刷新“最近使用”时间
//   C <键> <整数>                         OK                      配置：enabled、idle_unload_sec、n_threads、max_ctx、min_tokens
//   Z <0|1>                               OK                      屏幕亮/灭
//   G                                     <状态 JSON>
//   K <计数>…                             OK                      客户端上报自己看到的计数（版本 2），并入状态文件
//
// 守护进程保证打分请求在时限内应答（超时就应答 N timeout），因此连接上不会残留迟到的应答；
// 客户端只有在守护进程整个卡住时才会等到自己的超时，那时直接断开重连。
#pragma once

namespace suzu::ipc {

constexpr int kDefaultPort = 17920;
constexpr const char * kVersion = "2";

// 客户端上报的计数，按这个顺序放在 K 帧里
constexpr const char * kClientCounterNames[] = {
    "score_calls", "score_ok", "skipped", "timeouts", "down", "wait_max_ms", "wait_over_50ms", "wait_over_100ms",
};
constexpr int kClientCounterCount = sizeof(kClientCounterNames) / sizeof(kClientCounterNames[0]);

}  // namespace suzu::ipc
