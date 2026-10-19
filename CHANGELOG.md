# 更新日志

版本号跟 tag（`v0.2.0` …）。使用方用复合构建引用源码，各自记下用的是哪个提交（见 README“使用方固定版本”）。

## 0.2.1（2026-10-20）

- 去掉微信相关：删除 `specs/wechat-legacy/`（微信 Xposed / root 检测面 spec）和 `legacy/`（x-clean 旧桌面验证台，
  已被 `cli` 取代）；README、DESIGN 不再提微信、x-clean、WeKit、“检测面分析”。开源库识别里的腾讯 MMKV 指纹保留。
- 解析代码（`SymbolResolver` / `SymbolSource` / `DexKitSource`）和 0.2.0 相同，使用方的解析代码摘要不用改。
- AGENTS.md 写明发版步骤和两个使用方。

## 0.2.0（2026-10-19）

- 余量分析更准：类查询里的方法条件拆成子条件（`methods[0].params`、`methods[0].strings["x"]` …）逐个去掉，
  不再整条去掉。以前方法条件里写了字符串、数字、注解，照样被标成“只靠形状”（⚠），现在按子条件本身判断；
  修复（`repair`）和推测仍然可以整条去掉方法条件，先试子条件，诊断更准。
- 符号表新增 `absent`：`absentIn` 标了“这个构建没有”的符号记在这里（没有时 JSON 不变）。运行时
  `SymbolLinker.absent(symbol)` 和 `SymbolUnavailable.absent` 据此区分“这个版本本来就没有”和“没适配上”。
- 新命令 `upgrade`（MCP 同名工具）：diff → matrix → repair 一条龙，写 `summary.md`，可以直接当更新 spec 的 PR 说明（设计 10.7）。
- `repair` 支持 `--old-build` / `--new-build`（用构建的钉子和“没有”的标注）。
- GitHub Actions：每次推送编译全部模块、跑测试。
- 测试的 JVM 加 `--enable-native-access=ALL-UNNAMED`（JDK 24 起 DexKit 桌面库 `System.load` 会警告，以后会被拦）。
- asm 9.9.1 → 9.11.1；构建脚本去掉 Gradle 10 要删的 `by configurations.creating` 写法。
- README 加上第二个使用方哔哩净界。

## 0.1.0（2026-10-16）

第一版：设计方案 M1–M8 和第 10、11 节全部完成——查询模型和解析器、dexlib / DexKit 两个引擎（逐条一致）、spec 文件、
版本矩阵和余量、升级分析（混淆映射）、suggest / repair、Android 运行时（选表、链接、契约和观察模式、共享表、远程开关、
按需解析、分级回退）、Hook 框架适配、开源库识别、lint、jadx 插件、原生层、MCP 服务。使用方：红薯助手。
