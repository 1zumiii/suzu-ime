// 安卓 JNI 把 Rime 的 UTF-8 字节偏移换成 Kotlin 字符串下标（UTF-16 单元）。
// 主机上编译运行，不需要 NDK：
//   J=android/trime/app/src/main/jni
//   c++ -std=c++17 -I$J/librime/src -I$J/librime/include -I$J/librime_jni \
//       tests/android_jni_offsets_test.cc -o /tmp/jni_offsets && /tmp/jni_offsets
#include <cstdio>
#include <cstring>

#include "helper-types.h"

static int failures = 0;

static void expect(const char* what, int got, int want) {
  if (got != want) {
    std::printf("FAIL %s: got %d, want %d\n", what, got, want);
    ++failures;
  }
}

// 模拟“选了覆盖 xiaok 的候选，剩下 i 未转换”的 composition
static ContextProto partial(const char* selected, const char* rest) {
  static std::string buf;
  buf = std::string(selected) + rest;
  RIME_STRUCT(RimeContext, ctx);
  ctx.composition.preedit = const_cast<char*>(buf.c_str());
  ctx.composition.length = static_cast<int>(buf.size());
  ctx.composition.sel_start = static_cast<int>(std::strlen(selected));
  ctx.composition.sel_end = ctx.composition.length;
  ctx.composition.cursor_pos = ctx.composition.length;
  return ContextProto(&ctx, "xiaoki", 6, false);
}

int main() {
  // 汉字在 BMP 内：一个字一个单元
  auto han = partial("笑哭", "i");
  expect("han length", han.composition.length, 3);
  expect("han selStart", han.composition.selStart, 2);
  expect("han cursorPos", han.composition.cursorPos, 3);

  // emoji 在 BMP 外：Java 里是代理对，占两个单元
  auto emoji = partial("\xF0\x9F\xA4\xA3", "i");  // 🤣
  expect("emoji length", emoji.composition.length, 3);
  expect("emoji selStart", emoji.composition.selStart, 2);
  expect("emoji selEnd", emoji.composition.selEnd, 3);
  expect("emoji cursorPos", emoji.composition.cursorPos, 3);

  // 混合：😂 + 哭 + 🤣 + ki
  auto mixed = partial("\xF0\x9F\x98\x82哭\xF0\x9F\xA4\xA3", "ki");
  expect("mixed length", mixed.composition.length, 7);
  expect("mixed selStart", mixed.composition.selStart, 5);

  if (failures == 0) std::printf("ok android_jni_offsets\n");
  return failures == 0 ? 0 : 1;
}
