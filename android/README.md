# Android：改版 Trime 与 AI 服务

本目录包含改版 Trime、AI 推理守护进程和客户端库的源码。方案、Lua 和默认词库包随 APK 提供，词库可在应用内下载和切换。AI 推理在独立进程中运行，界面统一称为“AI 服务”。

构建时，`trime/rime-suzu.ref` 固定配置仓库 [rime-suzu](https://github.com/1zumiii/rime-suzu) 的提交，再叠加本仓库的 `rime/lua` 和配置仓库的 `llm-android/trime/rime_ice.overlay.yaml`。

目标设备：ASUS Zenfone 10（骁龙 8 Gen 2，16 GB，Android 15）。目标前端：[Trime（同文输入法）](https://github.com/osfans/trime)。

## 安装后设置

安装改版 APK 后，按引导启用并选择输入法。后续三个步骤均可跳过：下载 Suzu 模型、下载或导入语法模型、通过 Shizuku 启动 AI 服务。模型与词库可在“设置 → 词库”中继续管理。

手机重启后，须先启动 Shizuku，再通过工具栏的 AI 徽标启动 AI 服务。未启动服务时仍可正常输入，只是不使用 AI 重排。也可从电脑通过 adb 启动，技术说明见下文。

工具栏三个点的面板提供“配色”“词库”“重新加载配置”和“功能状态”等入口。“重新加载配置”只读取本机配置；获取新版内置配置须更新 APK。

## 候选生成

简拼和混合输入时，守护进程从词典中按上文筛选 Rime 未给出的词，补充到候选中。此功能默认开启（`W` 请求，见 `core/ipc/protocol.h`）：

- 启动参数 `--gen-beam N` 控制每层展开的首字数，`0` 为关闭；`rimellmctl.sh` 用环境变量 `GEN_BEAM`，默认 10（手机实测 10 与 20 的准确率相同）。
- 词典是 `--rime-dir` 下的 `cn_dicts/`，读 `base.dict.yaml`、`ext.dict.yaml`，以及有的话 `extra.dict.yaml`（本项目自己的补充词条，由词库包安装脚本放入）。词典索引在模型就绪后由单独的线程加载，打分不必等它，常驻内存约多 19 MB。
- 不同方案可以用不同的词库：方案的补丁里设 `llm_rerank/lexicon_dir: <rime 目录下的子目录名>`，Lua 经 `set_lexicon` 把目录名发给守护进程，守护进程只重读词典索引，模型不重载。守护进程重启后客户端会重新下发。不认识该请求的旧守护进程会忽略它，继续用 `cn_dicts`。

## 方案、词库包与升级

- 方案只有一份，ID 和主词典名都是 `suzu`，由 `trime/gen_assets.py` 从 rime-suzu 的 `rime_ice` 基座生成。构建时记录来源提交，显示在状态页。工作区有未提交的改动时，版本名带 `-dirty`。
- 词库包保存在用户目录，每包独立存放。默认包随 APK 提供，其他包按 `suzu_packs.tsv` 下载并校验 sha256。在“设置 → 词库”中切换后，应用会重新编译词库；失败时尝试恢复原词库包。候选生成的 `lexicon_dir` 跟随当前包。
- 首次升级时，App 把 `rime_frost`、`rime_ice` 的用户词典合并到 `suzu.userdb`，核对条数后按固定清单清理旧配置。旧的 `*.userdb` 保留。迁移状态写在 `suzu_migration.txt`，结果显示在状态页。
- 首次启动时，引导页提供三个可选步骤：下载 Suzu 模型（约 42 MB）、下载语法模型（约 399 MB，也可选择已下载的 `.gram` 文件）、通过 Shizuku 启动 AI 服务。跳过的功能保持关闭，之后可在“设置 → 词库”中下载或删除模型。应用根据模型文件是否完整安装判断可用状态，代码见 `suzu/SuzuAssets.kt`。
- 附加词库叠加在词库包之上，目前只有萌娘百科。App 从上游 GitHub Release 按固定版本下载并校验 sha256，再在手机上过滤（保留 2 到 8 字，去掉同字重复）并统一权重，写入用户目录的 `suzu_addons/`。守护进程的候选生成也读这个目录：开关附加词库后 App 重新部署，输入法随之再次下发词典目录，守护进程发现文件有变化就重读词典，不必重启 AI 服务。代码见 `suzu/DictAddons.kt`。
- 语法模型也可以用 rime-suzu 的 `llm-android/deploy_trime.sh` 从电脑推送。

用户目录中的同名配置文件优先于 APK 内置配置。调试时可临时推送文件，“功能状态”页会列出覆盖项；调试完成后须删除临时文件。

## 方案：常驻推理进程与改版 Trime

Trime 的数据目录为 `noexec`，应用本身也不可调试（`run-as` 被拒），因此 `rime_llm.so` 无法从外部放入，只能打包进 APK。为提高稳定性，推理在独立进程中运行，Trime 内仅保留轻量客户端：

```
Trime（改版 APK）                              rimellmd（shell 身份，由 adb 或 Shizuku 启动）
  Lua 脚本（与 Mac、PC 为同一份）                 监督进程：看护工作进程，被终止、崩溃或无响应时退避重启并记录退出原因
    └ librime_llm.so（客户端）          ──socket──▶  工作进程：推理核心 core/（与 Mac、PC 为同一份）+ 状态文件
  工具栏 AI 徽标（状态、启停、经 Shizuku 启动）      127.0.0.1:17920，令牌认证
```

- 客户端（`core/client/`）向 Lua 暴露的接口与进程内的 `rime_llm.so` 相同，因此 `ai_rerank_*.lua` 无需改动。每次调用都有总时限（含重连）：第一阶段打分 65 ms，第二阶段 90 ms，预解码 50 ms。无法连接时立即返回，候选保持原顺序，客户端按 0.5 秒到 10 秒指数退避重连。
- 守护进程（`core/daemon/`）直接调用推理核心的调度层 Engine。每个连接一个线程，连接线程从不自己计算：打分交给工作线程并在时限内等待，其余请求立即应答。守护进程保证在时限内应答打分请求，超时就应答 `timeout`，结果进缓存，下一次按键直接用上。
- 认证：每次启动生成随机令牌，写入 Trime 的应用专属目录（其他应用不可读）。守护进程仅监听 127.0.0.1。Trime 需要 `INTERNET` 权限，改版 APK 已加入。
- 手动启停：点按工具栏的 `● AI` 徽标，切换方案开关 `ai_rerank`。关闭时守护进程立即卸载模型并拒绝推理，仅保留数 MB 的待命进程；开启时立即加载。不自动卸载。新装时默认关闭。守护进程把最后一次手动选择记在用户目录的 `ai_enabled.state`，Trime 重启、重建会话、重新部署或守护进程重启后保持不变；Lua 在会话启动时不向守护进程推送开关，而是读它的状态来对齐方案开关，启动后 3 秒内方案开关因会话重置产生的变化也不推送。长按徽标可查看详情。
- 状态面板：守护进程每 5 秒写一次 `ai_status.json`（手动切换后的 15 秒内，每 0.3 秒写一次），徽标读取该文件。绿色为在线，黄色为加载或重启中，灰色为已关闭或待机，红色为离线。详情包含内存、打分延迟、跳过和错误次数、累计重启次数以及上次退出原因。`skipped` 只统计真正的跳过，上文不足 2 个字的请求单列为 `no_context`；`engine` 对象记录排队、计算、等待的分布与跳过原因，`client` 对象记录客户端看到的超时与连接失败。
- 自动启动：徽标为红色（离线）时，点按即可经 Shizuku 以 shell 身份启动守护进程，首次会弹出 Shizuku 的授权请求。键盘弹出时若发现离线，每分钟最多自动尝试一次。手机重启后，须先启动 Shizuku。
- 孤儿保护：Trime 被卸载后，其数据目录随之消失，无法再通过它关闭守护进程。守护进程检测到该目录连续 10 分钟不存在（可用 `--orphan-sec` 调整）时自行退出，监督进程收到退出码 77 后同样结束，不再重启。重新安装 Trime 后，点按红色徽标即可再次启动。已用临时目录和另一个端口验证：目录删除 15 秒后，两个进程均退出。
- 熄屏联动：Trime 在亮屏或熄屏时设置 Rime 选项 `screen_off`，由 Lua 转交给守护进程。默认不设单独的熄屏时限，仅保留该接口。

## 文件与代码入口

| 路径 | 作用 |
| --- | --- |
| `core/daemon/`、`core/client/`、`core/ipc/` | 守护进程、客户端库与线路协议（源码在推理核心目录） |
| `daemon/rimellmctl.sh` | 电脑端的 `deploy`、`start`、`stop`、`restart`、`status`、`log` |
| `tests/test_daemon.py` | 端到端测试：认证、非法输入、时限、正确性、并发；加 `--chaos` 还会做 `kill -9`、`SIGSTOP` 与删除模型的恢复测试；`--host` 在电脑上跑 |
| `tools/bench_daemon.py` | 真实打分延迟基准（每次更换新候选） |
| `trime/` | 改版 Trime 的完整源码，2026-11-01 由上游 `ad5a14be` 硬分叉而来，不再跟随上游。librime、OpenCC 等第三方源码是固定提交的子模块 |
| `trime/app/src/main/java/com/osfans/trime/suzu/` | 本项目新增的代码：AI 徽标、Shizuku 启动、光标上文、选错修正、模块状态页。上游文件中的其他改动可用 `git diff 9338bb3 -- android/trime` 查看 |
| `trime/app/src/main/java/com/osfans/trime/suzu/SuzuAppContext.kt` | 光标上文：没有未上屏编码时用 `getTextBeforeCursor` 读光标前最多 60 个字，经 JNI 写进客户端库，Lua 的 `llm_context` 读取（`rime/lua/ai_rerank_app_ctx.lua`），读不到退回上屏历史；密码框、数字框、电话框和无痕模式不读，文字只在内存里 |
| `trime/app/src/main/java/com/osfans/trime/suzu/SuzuFix.kt`、`FixChoicesUi.kt` | 选错修正建议（试验）：每次整段选词，Lua 把所选词和同页候选登记到客户端库（`fix_note`），上屏时由 `SuzuFix` 领取；停顿 1.5 秒后补上右侧已打的文字，给句中最近的几个词与各自的候选打整句分（经单独的连接请求守护进程），某个候选高出门槛时在工具栏提示“A → B”，点了才核对并替换。门槛：选了 AI 改过的首位为 5，其他为 8。光标换词：光标点到文字上时，在候选栏列出可替换的同音候选，点了只替换对应的一段。词库分不清光标处是词还是单字，要改的文字也可能本身有错，所以不判断边界：Lua 查出从光标处开始和跨过光标的 1 到 4 字各段的同音词，`SuzuFix` 逐段打整句分，统一按“比原文高多少”排序。替换结果相同的只留范围小的，比原文低 8 分以上的不列（暂定值），至少列 3 个，最多 8 个。最近整段选过的词还会列出选词时的同页候选。领取、定位、打分、出现、采纳、忽略等事件只记数字，写入用户目录的 `fix_stats.tsv`；密码框和无痕模式不查 |
| `trime/app/src/main/java/com/osfans/trime/ime/keyboard/` | 键盘：`KeyModel.kt` 是按键数据、类型化的动作和纯函数的布局计算，`MainPages.kt` 是有独立布局的页面，`SymbolBoard.kt` 是表情、标点和颜文字共用的组件（内容在 `SymbolData.kt`），`SuzuKeyboard.kt` 管理当前页、Shift 状态并执行动作，`KeyCapView.kt` 只负责手势和绘制。数字页和符号页用 `AsciiPolicy.Follow`：进出时不改中英文状态，中文模式下进入时先上屏未完成的输入，避免数字键选中候选 |
| `trime/app/src/main/java/com/osfans/trime/data/theme/BuiltinTheme.kt` | 内置主题：样式、工具栏、液体键盘和浅色、深色两套配色。配色可选跟随系统、浅色或深色（`AppPrefs.Keyboard.colorMode`） |
| `trime/gen_assets.py` | 构建时生成 `assets/shared` 和词库包，由 `build_apk.sh` 调用 |
| `trime/build_apk.sh` | 从源码到签名 APK 的完整流程；`trime/sign_apk.sh` 仅负责对齐和签名 |
| `docs/preview.png` | README 使用的 Android 预览图 |

覆盖安装改版 APK 后，键盘首次弹出时才会启动输入法服务。部署广播只有在服务运行时才会被接收，因此安装后须先打开任意输入框，再发部署广播。

**更换签名密钥须先卸载原有的 Trime。** 卸载会清空其数据目录，应事先备份用户词库。

## 学生模型 Suzu（输入法专用小模型）

代号 Suzu：Suzu-1 是第一轮（8 层、384 维、约 16.5M 参数），Suzu-2 是第二轮（12 层、512 维、约 40.9M 参数），Suzu-2.1 在 Suzu-2 的权重上续训，Suzu-2.4 再补充简拼训练，结构都不变。手机上的文件名是 `suzu.gguf` 和 `suzu.vocab.txt`（旧版本叫 `student.*`，升级后需要改名）。另可放一个 `suzu.name`（一行文字，如 `Suzu-2.4`），长按 AI 徽标的详情会显示它；没有这个文件就显示 `Suzu`。

早期 Suzu-1 为约 16.5M 参数的字符级 Transformer（GPT-2 结构），在 Windows 侧蒸馏，用于替代 Qwen3.5-0.8B 做候选排序。当时评测的混合准确率为 92.1%（0.8B 为 92.7%）；手机上 Q8_0、2 线程、6 个候选含进程间通信的打分中位约 11 ms（0.8B 约 60~80 ms，且只打 3 个候选）。这些数据保留为历史记录。

- 启用：把 `suzu.gguf`（建议 Q8_0）和词表 `suzu.vocab.txt` 放进 Trime 的用户目录，然后让守护进程重启（先结束旧进程，再点工具栏的 AI 徽标启动）。徽标长按的详情会显示当前模型。
- 仅支持 Suzu：缺少模型或词表时，启动提示会引导到“设置 → 词库”重新下载，AI 服务不启动，输入照常。已不再回退 Qwen3.5-0.8B，用户目录无需保留该模型；启动时会删除 `/data/local/tmp/rimellm/model.gguf` 这份旧副本。
- 实现：见 [core/README.md](../core/README.md) 与 [docs/design/core-design.md](../docs/design/core-design.md)。输入为 `<bos>` + 上文最后 30 个字 + 候选的字，不使用 llama 的分词器；打分为候选各字对数概率之和，与蒸馏评测同口径。上文未预解码时在时限内现场解码，不跳过。
- 注意：学生的训练上下文上限为 64，上文最多取 30 个字；候选最多打分前 12 个字。
- 验证：`daemon/test_student.py` 在手机上单独起一个守护进程（端口 17921），核对几组同音词的排序并测延迟。
- 早期实测的未决事项：Q8_0 量化对准确率的影响当时尚待评测确认；难例上学生比 0.8B 弱约 4 个百分点。

## 构建与测试

整套产物（改版 APK、客户端库、守护进程）由 `trime/build_apk.sh` 一次完成。前提：JDK 17、Android NDK r28c（`28.2.13676358`，默认取 Android SDK 的 `ndk/` 目录）、CMake 3.31.6，以及 llama.cpp 与 Lua 源码（布局见 [core/README.md](../core/README.md)）。CMake 和第三方源码默认放在 `~/Dev/ime-deps`，可用环境变量 `IME_DEPS` 指定；工作目录默认是仓库旁边的 `rime-android-port/`，可用 `WORK` 指定。推理部分默认按通用指令集 `armv8.2-a+dotprod+fp16` 编译；只装支持 i8mm 的手机时，可用环境变量 `ARM_ARCH` 指定更高的指令集，如骁龙 8 Gen 2 用 `ARM_ARCH=armv8.6-a+dotprod+fp16+i8mm`。没有 i8mm 的芯片运行这种包会直接崩溃。

单独编译守护进程和客户端的命令见 core/README.md 的 Android 一节。`SUZU_LUA_HOST_LIB` 指向 Trime 的 `librime_jni.so`，使加载器把客户端中的 `lua_*` 符号解析到它。

没有 NDK 时，守护进程与客户端可以在 Linux 上做主机构建并测试（`tests/run_all.sh`、`tests/test_daemon.py --host`），协议与行为与手机上相同。

`bench_android.lua`、`sweep.sh`、`prep_bench.sh` 是手机端的基准脚本，经 `adb push` 放到 `/data/local/tmp` 后由 `adb shell` 运行。`elfsyms.py` 用于在没有 `nm` 时读取 ELF 动态符号表。

线程数不得超过绑定的核数，手机端也不宜开过多线程。

## 内核性能与调优实测

前端重构本身不影响内核实测的有效性。以下保留各组数据及原测量条件，比较结果时须核对模型、推理核心、线程数和候选生成配置。Suzu-2.1 与 Suzu-2.4 在共用核心下的延迟和内存见 [技术报告](../docs/technical-report.md)。

### 移植时的推理核心测试

推理核心在手机上可运行（`rime_llm.so` 用 NDK r28c 交叉编译，arm64，`+dotprod+i8mm+fp16`；现在默认按通用指令集编译，见上文“构建与测试”）：

| 项目 | 结果 |
| --- | --- |
| 正确性 | 10 条同音词用例首选 10/10，与 Mac 一致 |
| 加载 | 约 0.7 秒（系统缓存已预热时） |
| 内存 | 私有脏页约 362 MB，模型文件映射约 501 MB（可回收） |
| 预解码之后只打分 | 约 6 ms（P95 6.6 ms） |
| 预解码本身（上屏后在后台运行） | 约 100~150 ms（4 线程） |
| 同步全流程（现场解码上文再打分） | 约 63~130 ms，同一配置前后相差一倍（手机调度和限频波动大） |

线程数与绑核（同步全流程，P50 / P95）：

- 4 线程、不绑核最稳：约 64 / 90 ms。
- 线程越多越差：6 线程 93 / 225 ms，7 线程 113 / 334 ms，8 线程 241 / 1799 ms（最大 2.4 秒）。原因是 3 个 A510 小核，加上 ggml 线程池的忙等待。
- **线程数超过绑定的核数会导致无响应**（绑定 4 个核、开 5 个线程，运行 8 分钟仍无结果）。
- 绑定大核比不绑核更慢。
- 权重重排（`use_repack`）在手机上收益为零：延迟没有改善（P50 85 ms），私有内存却多约 500 MB。保持关闭。

### 早期手机端调优

本节包含旧模型的调优记录，其中第二阶段明确列出了 0.8B 与学生模型的对照。下列候选数和内存设置属于当时的测试条件。

测试条件：Zenfone 10（骁龙 8 Gen 2），手机空闲，每次更换一组新候选。

- 该模型含按顺序处理的循环层，打分约 100 ms 的下限与线程数基本无关（1、2、3 线程均约 133 ms）。4 线程会因预解码与打分同时忙等而出现偶发的数秒卡顿，2~3 线程没有，因此默认使用 2 线程。
- 候选数影响最大：5 个候选 P50 约 118 ms，3 个约 78 ms，2 个约 61 ms。手机端使用 3 个。
- 四字词的第二阶段需要现场解码：0.8B 每次 100~500 ms，学生模型约 37 ms（上文未预解码时的中位数）。该阶段由方案开关 `ai_stage2`（“单段/两段”）控制，默认“两段”，可在输入法左侧三个点打开的开关面板中切换为“单段”。配置项 `llm_rerank/stage2` 为总开关，须同时为 true。Suzu-2 在手机上实测（393 次按键）第二阶段中位 3.7 ms、最大 19.6 ms。
- 引擎默认按 128 上下文、12 并发序列预留，手机端收紧为 96 和 6：堆内存由 374 MB 降至 234 MB，延迟不变。RSS 中另有约 512 MB 为模型文件映射页（干净页，内核可随时回收）。
- 上文 token 数由 10 降为 6，缩短上屏后的后台预解码。

这些手机端专属设置不改动仓库中 Mac、PC 使用的配置，而是写在 rime-suzu 的 `llm-android/trime/rime_ice.overlay.yaml` 中，构建 APK 时追加到方案补丁。

## 早期移植与配置验证

Trime 的 nightly 构建（arm64，v3.3.12-73）：

- 内嵌 librime 1.17.0、librime-lua（Lua 5.4.6）、librime-octagram 和 librime-predict，均静态链接在 `librime_jni.so` 中。
- `librime_jni.so` 导出 155 个 Lua 符号（`lua_*`、`luaL_*`、`luaopen_*`），`rime_llm.so` 所需符号全部在内。它同时导入了 `dlopen` 和 `dlsym`，说明内嵌的 Lua 启用了 C 模块的动态加载。
- 用户数据目录：`/sdcard/Android/data/com.osfans.trime/files/rime`。
- 该数据目录挂载为 `noexec`，`.so` 不能放在其中加载。将其打包进 APK 的原生库目录，再用 `package.loadlib("librime_llm.so", ...)` 即可加载，后面的方案以此为基础。

### 早期配置编译对照

手机端编译（骁龙 8 Gen 2，约 12~20 秒）的结果与 Mac 一致：

| 检查项 | 手机 | Mac |
| --- | --- | --- |
| 主词典 `table.bin` 大小 | 60,656,260 字节 | 60,656,260 字节 |
| 拼写规则 / 简拼规则 | 55 / 2 | 55 / 2 |
| 过滤器、处理器顺序 | 一致 | |
| librime 版本 | 1.17.0 | 1.16.0 |

Trime 的 librime 1.17.0 比鼠须管的 1.16.0 新，因此 `translator/max_sentences`（长句候选数）可在手机端启用。
