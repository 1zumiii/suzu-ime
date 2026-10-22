#!/bin/bash
# 把 AI 重排需要的推理核心与 Suzu 模型放进鼠须管用户目录。Lua 与配置由配置仓库的 install.sh 安装。
# 用法：macos/install_llm.sh [rime_llm.so 路径] [suzu.gguf 路径] [suzu.vocab.txt 路径]
#   rime_llm.so 由 core/ 构建（见 core/README.md），默认取 core/build/rime_llm.so
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${RIME_DIR:-$HOME/Library/Rime}"
SO="${1:-$REPO/core/build/rime_llm.so}"
MODEL="${2:-$HOME/Documents/rime-llm-mac-port/models/suzu.gguf}"
VOCAB="${3:-${MODEL%.gguf}.vocab.txt}"

[[ -f "$SO" ]] || { echo "找不到 ${SO}，请先按 core/README.md 构建"; exit 1; }
[[ -f "$MODEL" ]] || { echo "找不到模型 $MODEL"; exit 1; }
[[ -f "$VOCAB" ]] || { echo "找不到词表 $VOCAB"; exit 1; }

# 只放行 Mach-O arm64 的 Lua 模块
file "$SO" | grep -q "Mach-O 64-bit bundle arm64" || { echo "$SO 不是 arm64 的 Mach-O bundle"; exit 1; }
# 词表行数必须等于模型的词表大小（核心加载时也会检查，这里提前给出明确的提示）
lines=$(wc -l < "$VOCAB" | tr -d ' ')
[[ "$lines" -ge 8 ]] || { echo "$VOCAB 行数过少（${lines}）"; exit 1; }

mkdir -p "$DEST/lua"
# 重要：不要原地覆盖已存在的 .so！macOS 会缓存旧文件的代码签名，覆盖后加载会被内核以
# "Code Signature Invalid (Invalid Page)" 直接 SIGKILL，输入法一启动就崩，系统随后会把它从已启用列表里移除。
# 所以先退出输入法，删掉旧文件，再以新文件（新 inode）的方式复制并重新签名。
"/Library/Input Methods/Squirrel.app/Contents/MacOS/Squirrel" --quit 2>/dev/null || true
sleep 1
rm -f "$DEST/lua/rime_llm.so"
cp "$SO" "$DEST/lua/rime_llm.so"
codesign --force --sign - "$DEST/lua/rime_llm.so" 2>/dev/null
codesign -v "$DEST/lua/rime_llm.so"
echo "已安装 $DEST/lua/rime_llm.so"

# 处理器在用户目录里看到 suzu.gguf 与 suzu.vocab.txt 就启用 Suzu；模型文件同样先删后拷
for pair in "$MODEL:suzu.gguf" "$VOCAB:suzu.vocab.txt"; do
  src="${pair%%:*}"; dst="$DEST/${pair##*:}"
  if [[ -f "$dst" ]] && cmp -s "$src" "$dst"; then echo "已是最新：$dst"; continue; fi
  rm -f "$dst"; cp "$src" "$dst"; echo "已复制 $dst"
done
echo "完成。重启输入法后生效；AI 重排默认开启，Control+Shift+5 可在当前输入框临时开关。"
