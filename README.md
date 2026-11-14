# DexAdapt

当前版本：[0.13.0](https://github.com/wzgrx/dexadapt/releases/tag/v0.13.0)。

已验证完整表可通过宿主桥接直达，缺表才进入后台扫描。

- 恢复回执区分宿主绑定和首次实际效果，重复确认不重复触发重试；安装摘要按宿主缓存，换包时失效。
- `UpgradeCoordinator` 统一宿主首次打开、升级、模块启动、定时检查与重试；重复事件合并，过时结果不发布。
- 运行时按包名、版本码与完整 DEX 选表，先解析已开启且可热安装的功能；降级可复用完全匹配的完整表。
- `.apks` / `.xapk` 解包缓存按实际 APK 内容定位；同名同大小换包不会读取旧拆包，损坏或缺失的成员会重新提取。
- 矩阵按符号和递归依赖增量解析及计算余量；整表命中时不打开 Dexlib 索引。
- 发布使用哔哩净界与红薯净界的真实 APK 验证。详见 [更新日志](CHANGELOG.md)。

给 LSPosed / Xposed 模块用的**通用版本适配工具链和运行时库**。宿主 App 一升级，混淆名全变，模块就得重新适配。
DexAdapt 让 Hook 点只写一次、跟着版本走：升级时告诉你变了什么、哪里会坏，坏了直接给出修好的查询；
用户手机上的宿主升级了，模块当场重新适配，不用等发版。

使用方：

- [红薯净界](https://github.com/wzgrx/ShuClear)（小红书模块）：本轮验证 15 个国内版和 Google Play 版 APK；
- [哔哩净界](https://github.com/wzgrx/BiliClear)（哔哩哔哩模块）：本轮验证 9 个国内版和 Google Play 版 APK，
  宿主里本来就没有的部分用 `absentIn` 标出来，不算适配失败。

设计方案见 [docs/DESIGN.md](docs/DESIGN.md)，查询语法见 [docs/SPEC.md](docs/SPEC.md)。

## 思路

不写混淆名，写**特征**：字符串常量、没混淆的类名、Gson 字段名、注解（Retrofit 路径）、源文件名、数字常量、
调用关系、字段读写、方法形状、指令顺序……每个 Hook 点是一条“查询”，**唯一命中**才算找到，多命中、零命中都不猜。

同一套查询可用两个引擎逐条对照：

- **电脑上**用 dexlib2 全量索引：生成随模块打包的内置表、做升级分析；
- **手机上**用 DexKit 现场解析：宿主升级到没内置的版本，模块 App 在后台扫一遍，宿主当场热替换。

## 能做什么

| 问题 | 命令 / 模块 | 效果（小红书实测） |
|---|---|---|
| 升级了什么 | `inspect`、`diff` | 签名、清单、原生库、开源库、网络接口、字符串的变化；旧类 → 新类的混淆映射（9.49.1 → 9.50.0：27 万个类配上 97%，已知的 206 个类精度 100%、召回 99.5%）；输出 jadx 能加载的 `mapping.txt` |
| 模块哪里会坏 | `diff --spec` | 每个 Hook 点：位置不变 / 改名但跟上了 / 找不到（附推测位置）/ 方法体变化大 / Hook 可能不生效（会被内联、抽象方法） |
| 自动适配 | `runtime`、`dexkit` | 内置表 → 本机 DexKit 适配 → 只停用找不到的功能；表和安装包身份不符时不用 |
| 写查询、修查询 | `suggest`、`repair` | 从 jadx 里看到的目标自动写出最稳的查询（抽样 55 个类，写出 49 个，全部正确）；新版找不到的符号诊断原因、给出修好的查询（复现了历史上手工修的 attachBrowser） |
| 查询够不够稳 | `matrix` | 多版本矩阵、漂移、余量（去掉一个条件就不唯一的“单点依赖”）、DexKit 一致性 |
| 打包的开源库 | `libs` | 认出 19 个常用库的版本，把混淆后的库类配回原名：小红书里认出 OkHttp 3.14.9、Retrofit 2.8～2.9、RxJava 2、Gson 2.9.1、协程 1.7.3 等 12 个；R8 混淆的 OkHttp 版本认对、类对应 56/60 正确 |
| 给 Agent 用 | `mcp` | 以上命令作为 MCP 工具，Claude 这类 Agent 可以直接跑“出报告 → 修查询 → 验证” |

## 模块

| 模块 | 内容 |
|---|---|
| `core` | 查询模型（Kotlin DSL / YAML / JSON）、解析器、符号表、目录指纹、依赖图检查、余量分析、版本矩阵 |
| `apk` | 安装包档案：二进制清单、签名（apksig）、拆分包 / `.apks` / `.xapk`、原生库、另外加载的 dex |
| `dexlib` | dexlib2 全量索引（电脑） |
| `dexkit` | DexKit 数据源和 `DexKitScan`（手机；电脑上配 `dexkit-desktop`） |
| `dexkit-desktop` | 电脑上用 DexKit：JVM 类 + 桌面原生库加载 |
| `runtime` | 手机运行时：`SymbolLinker`（表 → 反射对象，热替换）、`AdaptPolicy`（选表、要不要扫、本机表的身份、自检） |
| `diff` | 升级分析：包和清单、代码、网络接口、字符串、混淆映射、模块视角、方法体相似度、Hook 风险、原生库导出符号 |
| `synth` | `suggest`（自动写查询）、`repair`（自动修查询） |
| `libs` | 开源库识别：Maven 上官方构件的指纹库、版本判断、类对应 |
| `hooks` | Hook 框架适配：libxposed、传统 Xposed API、YukiHookAPI、EzXHelper（框架 compileOnly，不打包） |
| `cli` | 命令行和 MCP 服务 |
| `jadx-plugin` | jadx 插件：右键类 / 方法 / 字段生成查询 |

## 命令行

```bash
./gradlew :cli:installDist      # 生成 cli/build/install/dexadapt/bin/dexadapt
```

```bash
dexadapt inspect app.apk                                   # 安装包档案
dexadapt resolve --spec hooks.yaml --apk app.apk            # 按 spec 解析一个版本
dexadapt matrix --spec hooks.yaml --apk v1.apk --apk v2.apk --parity
dexadapt diff old.apk new.apk --spec hooks.yaml --out report/
dexadapt suggest --apk new.apk --target 'a.b.C#m(int)void' --other old.apk
dexadapt repair --spec hooks.yaml --old old.apk --new new.apk
dexadapt upgrade --spec hooks.yaml --old old.apk --new new.apk --out up/   # 一条龙：diff → matrix → repair → summary.md
dexadapt libs app.apk --out libs/
dexadapt export --spec hooks.yaml --out hooks.json
dexadapt lint --spec hooks.yaml --apk app.apk                 # spec 检查器
dexadapt import --from revanced fingerprints.kt --out imported.yaml   # 也支持 morphe、qauxiliary
dexadapt export --spec hooks.yaml --to revanced --out fingerprints.kt
dexadapt codegen --spec hooks.yaml --package my.mod --out HostAccess.kt # 类型化访问器
dexadapt keygen --out table.key && dexadapt sign --key table.key 1234-abcd.json   # 共享适配表
dexadapt mcp                                                # MCP 服务（标准输入输出）
```

jadx 插件（jadx 1.5+）：

```bash
./gradlew :jadx-plugin:pluginJar
jadx plugins --install-jar jadx-plugin/build/libs/dexadapt-jadx-plugin.jar
```

在 jadx 里右键一个类、方法或字段 →“DexAdapt：生成查询”，查询（YAML）弹窗显示并复制到剪贴板。

接到 AI 助手（MCP，标准输入输出；任何支持 MCP 的客户端都能用，下面是常见的几种，字段以各客户端文档为准）：

```bash
claude mcp add dexadapt -- /path/to/dexadapt/cli/build/install/dexadapt/bin/dexadapt mcp    # Claude Code
```

```toml
# Codex CLI：~/.codex/config.toml（diff、matrix 要几分钟，把工具超时调大）
[mcp_servers.dexadapt]
command = "/path/to/dexadapt/cli/build/install/dexadapt/bin/dexadapt"
args = ["mcp"]
tool_timeout_sec = 900
```

```json
{ "mcpServers": { "dexadapt": { "command": "/path/to/dexadapt/cli/build/install/dexadapt/bin/dexadapt", "args": ["mcp"] } } }
```

最后这种写法用于 Cursor、Gemini CLI、Cline 等。工具有 inspect、resolve、matrix、diff、suggest、repair、upgrade、libs、decompile、lint、export；
每次调用在子进程里跑（原生库的日志不会混进通道），客户端带进度令牌时长任务每 10 秒报一次进度。给 AI 的使用说明见
[AGENTS.md](AGENTS.md)。

## 在模块里用

1. 用 Kotlin DSL 或 YAML 写查询（[docs/SPEC.md](docs/SPEC.md)），模块构建时引用 `dexadapt-core`；
2. 电脑上对已知版本生成内置表（`resolve` / `matrix`），打包进模块；
3. 宿主进程里用 `runtime` 的 `AdaptPolicy.choose` 选表、`SymbolLinker` 拿 `Method` / `Field`；
4. 模块 App 里用 `AdaptPolicy.decide` 判断要不要扫、`DexKitScan` + `SymbolResolver` 本机适配、`AdaptPolicy.stamp` 盖身份，
   再把表交给宿主进程热替换。

宿主升级、安装摘要与恢复回执接入见 [运行时升级指南](docs/UPGRADE_RUNTIME.md)；旧选表 API 保持兼容，新接入必须使用严格身份入口。

已经用了某个 Hook 框架的模块不用改写法，用 `hooks` 模块的适配（成员按符号表找、找不到不挂、观察模式自动接上）：

```kotlin
val hooks = SymbolHooks(linker, contracts = { monitor })                       // runtime 模块
xposed.hook(hooks, MySymbols.bind) { chain -> chain.proceed() }               // libxposed
hooks.hookMethod(MySymbols.bind, after = { param -> param.result = null })     // 传统 XposedBridge
hookSymbol(hooks, MySymbols.bind) { after { result = null } }                 // YukiHookAPI，写在 loadApp { } 里
hooks.createHook(MySymbols.bind) { before { param -> } }                      // EzXHelper
linker.clazz(MySymbols.feed).resolve().firstField { name = "poi" }            // KavaRef：拿到 Class 后照常用
```

红薯助手是完整的例子：`HostSymbols`、`FeatureRuntime`（宿主进程）和 `HostAdapter`（模块 App）；
哔哩净界是 libxposed + 多宿主（国内版、国际版）的例子。

### 使用方固定版本

使用方用复合构建（`includeBuild("../dexadapt")`）直接引用源码，DexAdapt 一改，旧的发版就重建不出来。所以：

- DexAdapt 按 tag 发版（`v0.2.0` …，见 [CHANGELOG.md](CHANGELOG.md)）；
- 使用方在 `gradle/dexadapt.commit` 记下用的提交，发版门禁核对 `../dexadapt` 正好在这个提交、没有未提交的改动
  （例如哔哩净界的 `scripts/verify.sh`）；
- 改了 `SymbolResolver` / `SymbolSource` / `DexKitSource` 后，使用方的解析代码摘要（`RESOLVER_SOURCES`）要跟着更新，
  各手机上的本机表才会重扫。

## 开源库识别

指纹库来自 Maven 上各库的官方构件（`dexadapt libs-db` 下载、建库，随发布打包一份常用库的）。每个版本记下用到的
“有特征的字符串”：R8 会内联、合并类，方法体整段的指纹在 App 里只剩两三成对得上，字符串即使被内联也还在。

- 版本：每个版本按“它的字符串出现了多少、只有别的版本才有的字符串出现了多少”打分，分数并列的给出一段版本；
- 类对应：库类的字符串在 App 里被哪些类用到，投票，票数明显领先才算。
- 一些“附带”的发现：小红书 Play 版的 Google 广告 SDK 里内嵌了一份改了包名的 ExoPlayer，腾讯人脸 SDK 自带一份
  改了包名的 OkHttp——报告的“包被改到了哪里”会列出来。
- 局限：R8 把一个类的代码内联进调用者后，它的字符串跟着搬家，这种类会配错或配不上（基准里 4/60 配错都是这种）。

## 开发

- JDK 17+ 运行 Gradle，`./gradlew test`；GitHub Actions 每次推送跑一遍（没有 APK 样本，相关测试跳过）。
- APK 索引记录：每个 APK 只用 dexlib2 解析一次，结果存在 `~/.cache/dexadapt/index`（小红书一个约 180 MB，读回来约 6 秒，
  直接解析约 40 秒；最多留 8 个）。`DEXADAPT_CACHE` 改目录，设成 `off` 关掉。
- 用真实 APK 的测试读 `DEXADAPT_SAMPLES`（默认 `~/projects/xhs-apks`），没有就跳过。
- DexKit 相关测试需要桌面原生库：`scripts/build-dexkit-desktop.sh`（需要 git、cmake、ninja、clang）。
- 开源库指纹库：`scripts/build-libdb.sh`（下载 Maven 构件，缓存在 `~/.cache/dexadapt/maven`）。

## 0.3.0 维护增强

索引缓存使用真实DEX内容摘要和载荷校验，默认最多64条/4GiB；`DEXADAPT_CACHE_MAX_ENTRIES`、`DEXADAPT_CACHE_MAX_BYTES`（正整数）可调整，`DEXADAPT_CACHE=off`仍可禁用。缓存格式升级后旧记录不会误用。

- `probe --spec hooks.yaml --apk old.apk --baseline previous.yaml --id player.menu --json`：只解析指定符号及其下游，不做余量扫描。对同一个包比较旧、新 spec，报告目标变化、缺失、依赖链、DEX 摘要和耗时；有缺失、退化或目标变化时返回 3。CLI 和 MCP 均可用。脚本使用 DexKit 时请加 `--out report.json`，原生库可能往终端输出日志。
- `matrix --incremental`：以包名、构建、完整 DEX 摘要和引擎隔离缓存。目录未变时直接复用表与余量，无需打开 Dexlib 索引；改目录后只解析定义或递归依赖变动的符号，并只重算失效的余量。默认仍全量计算。manifest.json 记录 `fresh/partial/reused`、是否打开索引、分段耗时、解析及余量的重算数和复用数；`--parity` 始终用 DexKit 重新验证。若使用方有意只要求已知构建一致，可以显式加 `--parity-known-only`，未知构建需另测。
- `repair --apk <额外样本> --channel <路径>=cn --out <目录> --json`：使用真实old/new-build，保留pins/absentIn/contract；候选依赖重新绑定，额外样本逐个验证原目标不变，输出repair.json和candidate-spec.json。
- `upgrade --verify-repairs --parity [--incremental]`：对独立候选重新跑全版本矩阵，原spec不覆盖。Kotlin目录作为源的使用方仍需把经审查的查询写回Kotlin，而不是只改生成YAML。上述选项亦由MCP暴露。
- 完整代码split可做桌面diff；桌面多代码表使用复合身份，base-only消费者会安全拒绝不匹配表，需要消费者支持完整代码身份后才可部署。单代码APK的身份与匹配语义不变。
- 方法差异保留操作码分数，另列字符串加载顺序、常量/调用/字段变化证据；1.0不代表语义等价。lint提示Kotlin实现名的脆弱依赖。

本次不改SymbolResolver/SymbolSource/DexKitSource匹配规则，旧消费者不必因解析语义变化强制重扫；升级依赖仍应固定提交并执行各自矩阵回归。

Android 传输模块 `android-runtime` 只依赖运行时与编译期 android.jar，提供阶段通知，不引入 Hook 框架。
