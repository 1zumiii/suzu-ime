// glyph_scan.swift — 找出本机字体画不出的字，写成鼠须管用户目录的 glyph_missing.txt（rime/lua/glyph_guard.lua 读取）。
// 含这些字的候选不再显示（否则会显示成方框或问号）。升级 macOS 或装了新字体后重跑一次；install_llm.sh 会自动运行。
// 用法：swift macos/glyph_scan.swift [输出文件]   默认 ~/Library/Rime/glyph_missing.txt
// 判断方法：从 PingFang SC 出发让 CoreText 选回退字体，回退到 LastResort 或取不到字形的算画不出。
// 扫描范围与 Android 的 GlyphScan.kt 一致：符号与表情、CJK 基本区与扩展区、兼容汉字。
import Foundation
import CoreText

let ranges: [ClosedRange<UInt32>] = [
    0x2600...0x27BF, 0x2B00...0x2BFF,          // 杂项符号、装饰符号、箭头补充
    0x3400...0x4DBF, 0x4E00...0x9FFF,          // 扩展 A、基本区
    0xF900...0xFAFF,                           // 兼容汉字
    0x1F000...0x1FAFF,                         // 表情与符号
    0x20000...0x323AF,                         // 扩展 B 到 H、I，兼容汉字补充
]

let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1]
    : NSHomeDirectory() + "/Library/Rime/glyph_missing.txt"
let base = CTFontCreateWithName("PingFang SC" as CFString, 16, nil)

func drawable(_ cp: UInt32) -> Bool {
    guard let sc = Unicode.Scalar(cp) else { return false }
    let s = String(Character(sc))
    var units = Array(s.utf16)
    let font = CTFontCreateForString(base, s as CFString, CFRange(location: 0, length: units.count))
    if (CTFontCopyPostScriptName(font) as String) == "LastResort" { return false }
    var glyphs = [CGGlyph](repeating: 0, count: units.count)
    return CTFontGetGlyphsForCharacters(font, &units, &glyphs, units.count)
}

var lines: [String] = []
var total = 0
for r in ranges {
    var start: UInt32? = nil
    var cp = r.lowerBound
    while true {
        let miss = !drawable(cp)
        if miss { total += 1; if start == nil { start = cp } }
        if (!miss || cp == r.upperBound), let s = start {
            let end = miss ? cp : cp - 1
            lines.append(s == end ? String(s, radix: 16, uppercase: true)
                                  : "\(String(s, radix: 16, uppercase: true))-\(String(end, radix: 16, uppercase: true))")
            start = nil
        }
        if cp == r.upperBound { break }
        cp += 1
    }
}

let os = ProcessInfo.processInfo.operatingSystemVersionString
let header = "# 本机字体画不出的字（macOS \(os)，\(ISO8601DateFormatter().string(from: Date()))），由 glyph_scan.swift 生成\n"
try! (header + lines.joined(separator: "\n") + "\n").write(toFile: out, atomically: true, encoding: .utf8)
print("已写入 \(out)：画不出 \(total) 个码位，\(lines.count) 段")
