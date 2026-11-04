# DexAdapt — 给 AI 助手的说明

DexAdapt 是 LSPosed / Xposed 模块的版本适配工具链和运行时库：宿主 App 升级、混淆名全变之后，分析变了什么、
模块哪里会坏、给出修好的查询，手机上还能当场重新适配。设计见 [docs/DESIGN.md](docs/DESIGN.md)，查询语法见
[docs/SPEC.md](docs/SPEC.md)。所有说明、日志外的文字、提交信息用中文。

## 两种用法

**当工具用**（帮人适配某个 App 的新版本）：优先用 MCP 服务，工具和命令行一一对应。

```bash
./gradlew :cli:installDist                         # 生成 cli/build/install/dexadapt/bin/dexadapt
cli/build/install/dexadapt/bin/dexadapt mcp        # MCP 服务（标准输入输出）
```

新版本来了的标准流程（路径都用绝对路径）：

1. `inspect` 看安装包档案（签名是否被改过、清单、原生库）；
2. `diff 旧 新 --spec 模块的spec` 出升级报告：模块每个符号在新版的位置、找不到的、方法体变化大的、Hook 风险；
3. 找不到的用 `repair`（自动诊断并给出修好的查询），或在 jadx 里看到目标后用 `suggest` 重新写；
4. 改好的 spec 先 `lint`，再 `resolve` / `matrix` 在每个版本上验证唯一命中、0 缺失；
5. 想知道打包的开源库和版本用 `libs`。

1～4 可以用 `upgrade` 一次跑完（diff → matrix → repair），`summary.md` 直接当更新 spec 的 PR 说明。

注意：`diff`、`matrix` 一次要几分钟、十几 GB 内存，一次只跑一个；第一次读某个 APK 要解析约 40 秒，缓存默认64条/4GiB（可配置）；
之后读索引记录（`~/.cache/dexadapt/index`）只要几秒。工具的结果是建议，生成的查询必须经过 `resolve` 验证。

**当代码改**（改 DexAdapt 本身）：

- JDK 17+，`./gradlew test` 跑全部测试；编译开着 `allWarningsAsErrors`，有警告就是失败。
- 用真实 APK 的测试读 `DEXADAPT_SAMPLES`（默认 `~/projects/xhs-apks`），没有样本就跳过；DexKit 相关测试要先
  `scripts/build-dexkit-desktop.sh`；`hooks` 模块要 Android SDK（没有就不构建）。
- 改了 `SymbolResolver` / `SymbolSource` / `DexKitSource`：使用方（红薯助手、哔哩净界）的解析代码摘要会变，要通知它们更新。
- 发版：改 `build.gradle.kts` 的 version 和 CHANGELOG，打 tag（`v0.3.0` …）并建 GitHub Release；使用方跟着改 `gradle/dexadapt.commit`。
- 每个模块的职责见 README 的模块表；新功能先在 `docs/DESIGN.md` 里有位置，再写代码和测试。

## 模块速查

`core` 查询模型、解析器、符号表、契约、spec、lint、代码生成 · `apk` 安装包档案、ELF · `dexlib` 电脑上的全量索引 ·
`dexkit` / `dexkit-desktop` DexKit 数据源 · `diff` 升级分析 · `synth` suggest / repair · `libs` 开源库识别 ·
`runtime` 手机运行时（选表、链接、观察模式、共享表、远程开关、Hook 闸门）· `hooks` Hook 框架适配 · `cli` 命令行和 MCP ·
`jadx-plugin` jadx 插件。
