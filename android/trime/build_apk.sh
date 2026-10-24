#!/bin/bash
# 从源码编出带 AI 重排的 Trime APK（arm64-v8a）：
#   上游 Trime（固定提交）+ patches/*.patch + 客户端库 librime_llm.so + 守护进程 librimellmd.so（均由 core/ 构建）→ Gradle → 签名
# 用法：android/trime/build_apk.sh [输出.apk]
# 前提：JDK 17（brew install openjdk@17）、Android SDK（Gradle 会自动装 NDK 28 / 平台）、NDK r27d（交叉编译推理部分）、
#       CMake 3.31.6 的官方预编译包、llama.cpp 与 Lua 源码（见 core/README.md）、签名密钥（见 sign_apk.sh）。
# 路径都可以用环境变量覆盖，默认放在 ~/Documents/rime-android-port/。
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
W="${WORK:-$HOME/Documents/rime-android-port}"
BASE=ad5a14be                                         # 手机上装的 Trime v3.3.12-73-gad5a14be
NDK="${NDK:-$W/ndk/android-ndk-r27d}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
CMAKE_DIR="${CMAKE_DIR:-$W/cmake-dl/cmake-3.31.6-macos-universal/CMake.app/Contents}"
OUT="${1:-$W/apk_work/trime-mod.apk}"
SRC="$W/trime-src"
STRIP="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
# 推理部分的指令集：默认通用版（约 2018 年以后的 arm64）；只装自己手机时可换成更高的，
# 如骁龙 8 Gen 2 用 ARM_ARCH=armv8.6-a+dotprod+fp16+i8mm（没有 i8mm 的芯片运行会直接崩溃）
ARM_ARCH="${ARM_ARCH:-armv8.2-a+dotprod+fp16}"

# 1. 源码：固定提交 + 补丁（已打过就跳过）
[[ -d "$SRC/.git" ]] || git clone https://github.com/osfans/trime.git "$SRC"
git -C "$SRC" fetch -q --all 2>/dev/null || true
# 全部补丁都已应用就跳过；否则回到固定提交，按文件名顺序重新应用全部补丁
need=0
for p in "$REPO"/android/trime/patches/*.patch; do
  git -C "$SRC" apply --check -R "$p" 2>/dev/null || need=1
done
if [[ $need -eq 1 ]]; then
  git -C "$SRC" checkout -q -f "$BASE"; git -C "$SRC" clean -fdq   # 补丁新增的文件不在跟踪里，不清掉会让重新应用报“已存在”
  git -C "$SRC" submodule update --init --recursive --depth 1 -q
  for p in "$REPO"/android/trime/patches/*.patch; do
    git -C "$SRC" apply "$p" || { echo "补丁 $p 无法应用（源码有别的改动？）"; exit 1; }
    echo "已应用 $(basename "$p")"
  done
else
  echo "补丁都已应用过"
fi
printf 'sdk.dir=%s\ncmake.dir=%s\n' "$HOME/Library/Android/sdk" "$CMAKE_DIR" > "$SRC/local.properties"

# 2. 客户端库与守护进程（由 core/ 交叉编译，客户端链接到 Trime 自己的 librime_jni.so 以解析 lua_*）
JNI_HOST="$W/trime-built/lib/arm64-v8a/librime_jni.so"
[[ -f "$JNI_HOST" ]] || { echo "缺少 ${JNI_HOST}：先用未打补丁的构建解出 librime_jni.so（见 README）"; exit 1; }
DEPS="${DEPS:-$HOME/Documents/rime-llm-mac-port}"     # llama.cpp 与 Lua 源码所在目录（布局见 core/README.md）
cmake -S "$REPO/core" -B "$W/build_host" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DCMAKE_BUILD_TYPE=Release \
  -DSUZU_ANDROID_ARM_ARCH="$ARM_ARCH" -DSUZU_DEPS_ROOT="$DEPS" -DSUZU_BUILD_DAEMON=ON -DSUZU_BUILD_LUA_MODULE=OFF -DSUZU_LUA_HOST_LIB="$JNI_HOST" >/dev/null
cmake --build "$W/build_host" --target rimellmd rime_llm_client -j8
JL="$SRC/app/src/main/jniLibs/arm64-v8a"; mkdir -p "$JL"
"$STRIP" --strip-unneeded -o "$JL/librime_llm.so" "$W/build_host/client/librime_llm.so"
"$STRIP" --strip-unneeded -o "$JL/librimellmd.so" "$W/build_host/rimellmd"

# 3. Gradle（只编 arm64；未签名）
( cd "$SRC" && JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:$PATH" ANDROID_HOME="$HOME/Library/Android/sdk" BUILD_ABI=arm64-v8a \
    ./gradlew assembleRelease --no-daemon --console=plain )

# 4. 对齐 + 签名
mkdir -p "$(dirname "$OUT")"
"$REPO/android/trime/sign_apk.sh" "$(ls "$SRC"/app/build/outputs/apk/release/*-unsigned.apk | head -1)" "$OUT"
echo "完成：$OUT"
