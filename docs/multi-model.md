# 多模型重排（设计草案）

本文定义“模型包”的格式和多个模型的组合方式。当前代码只支持单个模型，本文描述的内容尚未实现。

## 目标

- 允许同时加载多个输入法候选打分模型，由用户自行选择和组合。
- 每个模型独立发布，各自使用自己的许可证。例如，用维基百科训练的 Suzu 使用 CC BY-SA 4.0，用 CC BY-NC-SA 语料训练的模型使用 CC BY-NC-SA。
- 只放一个模型时，行为与现在完全一致。

## 模型包

一个模型包是一个目录，内含三个文件：

```text
suzu-2/
  suzu-2.q8_0.gguf          模型权重
  suzu-2.q8_0.gguf.vocab.txt 词表，一行一个字符，行号即编号
  manifest.json             描述文件
```

`manifest.json` 的字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| `schema` | 是 | 格式版本，目前为 `1` |
| `id` | 是 | 英文标识，如 `suzu-2`，用于配置里引用 |
| `name` | 是 | 显示名，如 `Suzu-2` |
| `version` | 是 | 模型版本 |
| `type` | 是 | 打分方式。目前只有 `char`：字符级词表，候选得分为各字对数概率之和 |
| `model` | 是 | GGUF 文件名 |
| `vocab` | 是 | 词表文件名 |
| `license` | 是 | SPDX 标识，如 `CC-BY-SA-4.0` |
| `license_url` | 是 | 许可证文本的地址 |
| `domains` | 否 | 擅长的领域，如 `["written"]`、`["colloquial"]` |
| `default_weight` | 否 | 默认权重，缺省为 1.0 |
| `context_chars` | 否 | 上文最多取最后几个字，缺省为 30 |
| `data` | 否 | 训练数据的来源与许可证，供署名使用 |
| `homepage` | 否 | 模型主页 |

示例：

```json
{
  "schema": 1,
  "id": "suzu-2",
  "name": "Suzu-2",
  "version": "2",
  "type": "char",
  "model": "suzu-2.q8_0.gguf",
  "vocab": "suzu-2.q8_0.gguf.vocab.txt",
  "license": "CC-BY-SA-4.0",
  "license_url": "https://creativecommons.org/licenses/by-sa/4.0/",
  "domains": ["written"],
  "default_weight": 1.0,
  "context_chars": 30,
  "data": [{"name": "Chinese Wikipedia", "license": "CC-BY-SA-3.0, GFDL"}],
  "homepage": "https://huggingface.co/IzumiThe3rd/Suzu-2"
}
```

## 组合方式

对同一个候选，每个模型各自给出得分 `s_m`，即各字对数概率之和。融合得分为：

```text
S = Σ w_m · s_m + 现有的其他项（词频、最近使用、个人词对、纠错记忆）
```

- 各模型对同一候选打分，字数相同，因此不需要按长度归一。
- 模型之间不必共用词表，组合发生在分数层。
- 权重 `w_m` 取配置值，没有配置时取 `default_weight`，再按总和归一。
- 置信度门控、两阶段推理等后续处理，作用于融合后的得分，与单模型时相同。

## 配置

在 `rime_ice.custom.yaml` 的 `llm_rerank` 下，用 `models` 列出要用的模型包：

```yaml
llm_rerank:
  models:
    - { pack: "~/Library/Rime/models/suzu-2", weight: 1.0 }
    - { pack: "~/Library/Rime/models/extra-domain", weight: 0.5 }
```

未配置 `models` 时，沿用现有行为：用户目录里有 `suzu.gguf` 和 `suzu.vocab.txt` 就使用 Suzu，否则回退到 Qwen。

## 运行时要求

- 每个模型独立加载，各有自己的上下文和 KV 缓存。同一次打分里，多个模型并行计算。
- 某个模型加载失败或打分超时，只跳过该模型，用剩下的模型继续。全部失败时不重排，输入不受影响。
- 内存和耗电随模型数增加。手机端守护进程要设内存上限，超出时拒绝再加载新模型。
- `rime_llm.so`（桌面）和 `rimellmd`（Android）都需要支持多模型。

## 许可证与分发

- 权重只通过 Hugging Face 等渠道单独发布，不放进本仓库，也不打进 APK 或其他安装包。这样带有 NC 等限制的模型，不会让安装包整体受限。
- 本仓库的代码按 GPL-3.0 发布，只负责加载和组合，不内嵌任何模型。
- 用户自行下载和组合模型时，须遵守每个模型的许可证。`manifest.json` 里的 `license` 与 `data` 字段用于提示。
- 每个模型只用一种许可证体系的语料训练，不要把 CC BY-SA 与 CC BY-NC-SA 的语料混进同一个模型，两者的“相同方式共享”条款无法同时满足。

## 调权

- 提供离线脚本，在评测题上为一组模型搜索权重，输出推荐值。脚本尚未编写。
- 各模型的分数标度不同，权重须实测，不能直接取相等。
- 可选的进阶做法：按最近上屏的内容动态调整权重，哪个模型对上文预测得更好，就多给它权重。目前不实现。

## 未决问题

- 并行打分在手机上的延迟和耗电，须实测。
- 动态权重是否值得做，取决于离线对比的结果。
- 是否需要支持字符级以外的打分类型，目前没有计划。
