# DexAdapt

给 LSPosed / Xposed 模块用的**通用版本适配工具链和运行时库**：宿主 App 一升级、混淆名全变，模块就得重新适配——
DexAdapt 让 Hook 点只写一次、跟着版本走，升级时告诉你变了什么、哪里会坏，坏了给出修复线索。

设计方案见 [docs/DESIGN.md](docs/DESIGN.md)，查询语法见 [docs/SPEC.md](docs/SPEC.md)。第一个使用方是
[红薯助手](https://github.com/wzgrx/redbook-helper-offline)（小红书模块，512 个 Hook 点、4 个内置版本）。

## 思路

不写混淆名，写**特征**：字符串常量、没混淆的类名、Gson 字段名、注解（Retrofit 路径）、源文件名、调用关系、字段读写、
方法形状、指令顺序……每个 Hook 点是一条“查询”，**唯一命中**才算找到。同一套查询：

- 电脑上用 dexlib2 全量索引解析，生成随模块打包的内置表、做分析；
- 手机上用 DexKit 现场解析，宿主升级了也能当场适配。

两个引擎共用一个解析器，结果逐条一致（小红书 4 个版本 × 512 个符号实测）。

## 模块

| 模块 | 内容 |
|---|---|
| `core` | 查询模型（Kotlin DSL / YAML / JSON）、解析器、符号表、目录指纹、依赖图检查、余量（脆弱度）分析、版本矩阵 |
| `apk` | 安装包档案：二进制清单、签名（apksig）、拆分包、原生库、另外加载的 dex |
| `dexlib` | dexlib2 全量索引（电脑） |
| `dexkit` | DexKit 数据源（手机；电脑上配 `dexkit-desktop`） |
| `dexkit-desktop` | 电脑上用 DexKit：JVM 类 + 桌面原生库加载 |
| `diff` | 升级分析：包和清单、代码规模、网络接口、字符串、混淆映射（旧类 → 新类）、模块视角 |
| `cli` | 命令行：`inspect` / `resolve` / `matrix` / `diff` / `export` |

## 命令行

```bash
./gradlew :cli:installDist                       # 生成 cli/build/install/dexadapt/bin/dexadapt
dexadapt inspect app.apk                         # 安装包档案（签名是否被改过、清单、dex、原生库）
dexadapt resolve --spec hooks.yaml --apk app.apk # 按 spec 解析一个版本
dexadapt matrix --spec hooks.yaml --apk v1.apk --apk v2.apk --parity   # 多版本矩阵 + 漂移 + 余量 + DexKit 一致性
dexadapt diff old.apk new.apk --spec hooks.yaml  # 升级分析报告 + mapping.txt（jadx 可加载）
dexadapt export --spec hooks.yaml --out hooks.json
```

## 开发

- JDK 17+ 运行 Gradle（本机用 Temurin 27），`./gradlew test`。
- 用真实 APK 的测试读 `DEXADAPT_SAMPLES`（默认 `~/projects/xhs-apks`），没有就跳过。
- DexKit 相关测试需要桌面原生库：`scripts/build-dexkit-desktop.sh`（需要 git、cmake、ninja、clang）。
- 旧的 x-clean-harness 桌面验证台在 `legacy/`，微信 spec 在 `specs/wechat-legacy/`（以后再接）。
