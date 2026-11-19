// 线路协议：请求与应答的字段约定。第一个字段的第一个字节是操作码。
//
//   请求                                  应答
//   B                                     PONG                    心跳，不需要认证（监督进程用）
//   H <令牌>                              OK <pid> <版本> [shell|app] | ERR auth
//   P <上文>                              1 | 0                   预解码（立即返回）
//   S <allow_sync> <上文> <候选>…         R <候选> <分数>… | N <原因>      打分，时限由守护进程按 allow_sync 取默认值（版本 1 的客户端）
//   Q <时限毫秒> <上文> <候选>…           同上                    打分，时限由客户端给出（版本 2）
//   I                                     1 | 0                   模型是否就绪
//   T                                     OK                      刷新“最近使用”时间
//   C <键> <整数>                         OK                      配置：enabled、idle_unload_sec、n_threads、gen_beam；其余的键（旧客户端的 max_ctx、min_tokens）应答 OK 并忽略
//   Z <0|1>                               OK                      屏幕亮/灭
//   G                                     <状态 JSON>
//   K <计数>… [客户端 pid]                OK                      客户端上报本工作进程的计数，并入状态文件
//   W <时限毫秒> <k> <上文> <输入串>      R <词> <分数> <名次>… | N <原因>     候选生成（版本 3）：按模型分降序的前 k 个词
//   U <时限毫秒> <b|g> <k> <上文> <输入串> <候选>…
//                                         U <打分状态> <生成状态> <匹配数> <n> <候选> <分数>…（n 对，按分数降序） <词> <分数> <名次>…
//                                                                第一阶段合并请求（版本 4）：打分与候选生成一次提交，共用一个时限。
//                                                                b = 打分与生成一起排队（打分当场失败时不生成），g = 先生成，生成成功才打分。
//                                                                状态为 ok、原因码（同 N 应答），或 not_tried（没有尝试）。
//                                                                客户端看握手应答里的版本号：低于 4 时不发 U，改发 Q 与 W
//   X <目录名>                            OK | ERR               切换候选生成的词典目录（版本 3 的可选请求）：rime 目录下的子目录名，
//                                                                只收字母、数字、下划线、连字符，且目录里须有 base.dict.yaml；只重读词典索引，模型不重载。
//                                                                目录与当前相同时核对词典文件（含旁边 suzu_addons/ 的附加词库）的清单、大小与修改时间，变了才重读。
//                                                                不认识 X 的旧守护进程应答 ERR op，客户端不当作错误，生成继续用守护进程启动时的 cn_dicts；
//                                                                客户端记下最后一次 X，守护进程重启后重连时重新下发
//
// 守护进程保证打分请求在时限内应答（超时就应答 N timeout），因此连接上不会残留迟到的应答；
// 客户端只有在守护进程整个卡住时才会等到自己的超时，那时直接断开重连。
#pragma once

namespace suzu::ipc {

constexpr int kDefaultPort = 17920;
constexpr const char * kVersion = "4";

// 客户端上报的计数，按这个顺序放在 K 帧里
constexpr const char * kClientCounterNames[] = {
    "score_calls", "score_ok", "skipped", "timeouts", "down", "wait_max_ms", "wait_over_50ms", "wait_over_100ms",
};
constexpr int kClientCounterCount = sizeof(kClientCounterNames) / sizeof(kClientCounterNames[0]);

}  // namespace suzu::ipc
