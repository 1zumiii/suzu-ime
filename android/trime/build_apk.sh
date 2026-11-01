#!/bin/bash
# 从源码编出带 AI 重排的 Trime APK（arm64-v8a）：
#   本目录的 Trime 源码（硬分叉自上游 ad5a14be）+ 客户端库 librime_llm.so + 守护进程 librimellmd.so（均由 core/ 构建）→ Gradle → 签名
# 用法：android/trime/build_apk.sh [输出.apk]
# 前提：JDK 17（brew install openjdk@17）、Android SDK（Gradle 会自动装 NDK 28 / 平台）、NDK r27d（交叉编译推理部分）、
#       CMake 3.31.6 的官方预编译包、llama.cpp 与 Lua 源码（见 core/README.md）、签名密钥（见 sign_apk.sh）。
# 路径都可以用环境变量覆盖：工作目录 WORK 默认是仓库旁边的 rime-android-port/；CMake 与第三方源码在 IME_DEPS（默认 ~/Dev/ime-deps）；NDK 在 Android SDK 的 ndk/27.3.13750724。
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
W="${WORK:-$(dirname "$REPO")/rime-android-port}"
IME_DEPS="${IME_DEPS:-$HOME/Dev/ime-deps}"
NDK="${NDK:-$HOME/Library/Android/sdk/ndk/27.3.13750724}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
CMAKE_DIR="${CMAKE_DIR:-$IME_DEPS/cmake-3.31.6-macos-universal/CMake.app/Contents}"
OUT="${1:-$W/apk_work/trime-mod.apk}"
SRC="$REPO/android/trime"
STRIP="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
# 推理部分的指令集：默认通用版（约 2018 年以后的 arm64）；只装自己手机时可换成更高的，
# 如骁龙 8 Gen 2 用 ARM_ARCH=armv8.6-a+dotprod+fp16+i8mm（没有 i8mm 的芯片运行会直接崩溃）
ARM_ARCH="${ARM_ARCH:-armv8.2-a+dotprod+fp16}"

# 1. 第三方源码（librime、OpenCC 等）是固定提交的子模块，缺了就拉取
if [[ ! -f "$SRC/app/src/main/jni/librime/deps/glog/CMakeLists.txt" ]]; then
  git -C "$REPO" submodule update --init --recursive --depth 1
fi
printf 'sdk.dir=%s\ncmake.dir=%s\n' "$HOME/Library/Android/sdk" "$CMAKE_DIR" > "$SRC/local.properties"
# 版本名：上游基线 + 本仓库提交，工作区有未提交改动时加 -dirty
REV="$(git -C "$REPO" rev-parse --short HEAD)"
git -C "$REPO" diff --quiet HEAD -- android core 2>/dev/null || { REV="$REV-dirty"; echo "警告：android/ 或 core/ 有未提交的改动，版本名带 -dirty"; }
export BUILD_VERSION_NAME="v3.3.12-suzu.$REV"

# 方案与默认词库包：rime-suzu 的固定提交（rime-suzu.ref）导出后生成 assets；
# RIME_SUZU_REF=worktree 时直接用 rime-suzu 工作区（开发用，有未提交改动会警告）
RIME_SUZU="${RIME_SUZU:-$(dirname "$REPO")/rime-suzu}"
RIME_SUZU_REF="${RIME_SUZU_REF:-$(cat "$SRC/rime-suzu.ref")}"
[[ -d "$RIME_SUZU/.git" ]] || git clone -q https://github.com/1zumiii/rime-suzu.git "$RIME_SUZU"
RS="$(mktemp -d)"; trap 'rm -rf "$RS"' EXIT
if [[ "$RIME_SUZU_REF" == worktree ]]; then
  RS_REV="$(git -C "$RIME_SUZU" rev-parse --short HEAD)"
  if ! git -C "$RIME_SUZU" diff --quiet HEAD; then RS_REV="$RS_REV-dirty"; echo "警告：rime-suzu 工作区有未提交的改动，APK 来源记为 $RS_REV"; fi
  rsync -a --exclude=.git/ "$RIME_SUZU"/ "$RS"/
else
  git -C "$RIME_SUZU" cat-file -e "$RIME_SUZU_REF^{commit}" 2>/dev/null || git -C "$RIME_SUZU" fetch -q origin
  git -C "$RIME_SUZU" archive "$RIME_SUZU_REF" | tar -x -C "$RS"
  RS_REV="$(git -C "$RIME_SUZU" rev-parse --short "$RIME_SUZU_REF")"
fi
python3 -I "$SRC/gen_assets.py" --rime-suzu "$RS" --assets "$SRC/app/src/main/assets" --pack "${PACK:-frost}" --pack-out "$W/dict-packs" \
  --sources "suzu-ime $REV" "rime-suzu $RS_REV"

# 2. 客户端库与守护进程（由 core/ 交叉编译，客户端链接到 Trime 自己的 librime_jni.so 以解析 lua_*）
JNI_HOST="$W/trime-built/lib/arm64-v8a/librime_jni.so"
[[ -f "$JNI_HOST" ]] || { echo "缺少 ${JNI_HOST}：先编一次 APK，从中解出 librime_jni.so（见 README）"; exit 1; }
DEPS="${DEPS:-$IME_DEPS}"     # llama.cpp 与 Lua 源码所在目录（布局见 core/README.md）
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
