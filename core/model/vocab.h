// Suzu 的字符级词表：词表文件一行一个字符，行号即编号（0 填充、1 未知、2 句首）。
// 本层只做“文字 → 编号”的确定性转换，不涉及模型与线程。
#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

namespace suzu {

using Token = int32_t;
using TokenSeq = std::vector<Token>;

constexpr Token kPadToken = 0;
constexpr Token kUnkToken = 1;
constexpr Token kBosToken = 2;

class Vocab {
public:
    // 读取词表文件；失败时返回 false 并在 err 里写原因（只含路径与数字）。
    bool load(const std::string & path, std::string * err);

    size_t size() const { return size_; }

    // 按 UTF-8 字符逐个查表，查不到记 <unk>。
    TokenSeq encode(const std::string & text) const;

    // 上文：句首符 + 最后至多 max_chars 个字。不足 2 个字返回空（与训练时的最短上文一致，不重排）。
    TokenSeq encode_context(const std::string & text, int max_chars) const;

    // 候选：各字的编号；空串记为一个填充符（与旧核心一致）。
    TokenSeq encode_candidate(const std::string & text) const;

    // 编号对应的字（越界返回空串）。
    const std::string & piece(Token id) const;

    // UTF-8 首字节对应的字符长度（非法字节按 1 处理，与旧核心一致）。
    static int utf8_len(unsigned char lead);

private:
    std::unordered_map<std::string, Token> map_;
    std::vector<std::string> pieces_;   // 编号 → 字
    size_t size_ = 0;
};

}  // namespace suzu
