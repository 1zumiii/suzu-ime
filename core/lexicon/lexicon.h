// 词典索引：候选生成用的词集合，直接读雾凇拼音的词典文件（cn_dicts/base.dict.yaml、ext.dict.yaml）。
// 词典本来就随方案部署到各平台的 Rime 用户目录，因此不另外导出、分发索引文件，换词库也不会与索引脱节。
//
// 只收字数在 [min_chars, max_chars] 之间、每个字都在模型词表里、读音音节数与字数相同的词。
// 同一个词有多种读音时各存一条（多音词），匹配时按词去重、取词频最高的一条。
//
// 输入串的切分：每个位置是一个完整音节，或一个声母（音节的首字母，以及 zh、ch、sh），对应雾凇的简拼规则。
// 音节表取自词典本身出现过的音节，不写死在代码里。
//
// 本层只做查表，不涉及模型与线程；加载后只读，可在多个线程上同时查询。
#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

#include "model/vocab.h"

namespace suzu {

struct InputUnit {
    std::string text;     // 音节（如 yang）或声母（如 s、zh）
    bool initial = false; // true：声母，匹配以它开头的任何音节
};
using Segmentation = std::vector<InputUnit>;

class Lexicon {
public:
    // 读取词典文件；词典里的字须能用 vocab 编码（含词表外字的词不收）。失败时 err 里写原因（只含路径与数字）。
    bool load(const std::vector<std::string> & dict_files, const Vocab & vocab, int min_chars, int max_chars,
              std::string * err);

    size_t size() const { return entries_.size(); }
    int min_chars() const { return min_chars_; }
    int max_chars() const { return max_chars_; }

    // 枚举输入串的切分，单元数在 [min_chars, max_chars] 之间，至多 max_segs 种。
    // full_pinyin：输入能否完整切成纯音节（不限单元数）。能切成纯音节的输入视为全拼。
    std::vector<Segmentation> segment(const std::string & input, bool * full_pinyin, int max_segs = 32) const;

    // 匹配所有切分的词，按词去重，按词频降序返回词条下标（即词频名次的顺序）。
    std::vector<uint32_t> match(const std::vector<Segmentation> & segs) const;

    TokenSeq tokens(uint32_t e) const;
    int weight(uint32_t e) const { return entries_[e].weight; }

private:
    struct Entry {
        uint16_t tok[3];   // 字的编号（词表不超过 65535 个字）
        uint16_t syl[3];   // 音节编号
        int32_t weight;
        uint8_t n;         // 字数
    };

    bool unit_matches(const InputUnit & u, uint16_t syl) const;
    void segment_from(const std::string & s, size_t pos, Segmentation & cur, std::vector<Segmentation> * out,
                      int max_segs) const;
    bool can_split_full(const std::string & s) const;

    std::vector<Entry> entries_;
    std::vector<std::string> syllables_;                       // 编号 → 音节
    std::unordered_map<std::string, uint16_t> syllable_id_;
    std::vector<char> initial_ok_;                             // 可作声母的首字母（a～z）
    std::unordered_map<std::string, std::vector<uint32_t>> buckets_;   // 各位置首字母拼成的键 → 词条（按词频降序）
    size_t max_syllable_len_ = 0;
    int min_chars_ = 2, max_chars_ = 3;
};

}  // namespace suzu
