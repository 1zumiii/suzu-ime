#!/bin/bash
# keysim.lua 的故障注入脚本（守护进程模式）：keysim 每 10 个词调用一次「chaos_daemon.sh <词序号>」。
# 环境变量：CHAOS_PORT（守护进程端口）、CHAOS_MODEL（模型文件路径，用于删除/恢复）。
# 词序号  20：kill -9 工作进程（监督进程应在数秒内重启它，客户端自动重连）
#         40：SIGSTOP 冻结工作进程 3 秒后恢复（客户端在时限内放弃，不卡输入）
#         60：删除模型文件并杀掉工作进程（重启后无模型：照常应答，不重排）
#         80：恢复模型文件并杀掉工作进程（重启后恢复重排）
w=$1; P=${CHAOS_PORT:-17920}
worker() { pgrep -f "rimellmd --child.*--port $P" | head -1; }
case $w in
  20) kill -9 "$(worker)" ;;
  40) pid=$(worker); kill -STOP "$pid"; sleep 3; kill -CONT "$pid" ;;
  60) mv "$CHAOS_MODEL" "$CHAOS_MODEL.gone"; kill -9 "$(worker)" ;;
  80) mv "$CHAOS_MODEL.gone" "$CHAOS_MODEL"; kill -9 "$(worker)" ;;
esac
