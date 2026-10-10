# X-Clean 通用桌面验证台（x-clean-harness）

> 在 Linux 桌面对 APK 直接跑 DexKit 目标解析——不装微信、不连手机、不跑 Hook。
> **通用设计**：任何 app / 任何模块的版本适配验证与数据生成（X-Clean / X 3.0 型名字表 / WeKit 型 matcher / 其他应用）。

## 环境（本机已验证）

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | temurin26（`~/tools/temurin26`）| 构建 + 运行均用它（DexKit 类目标 Java 11）|
| CMake | 4.4.4 | 需 `-DCMAKE_POLICY_VERSION_MINIMUM=3.5`（DexKit 声明最低 3.4.1 的逃生舱）|
| Ninja / clang / ccache | 1.13.2 / 23.x / 4.14 | 原生构建链 |
| Gradle | 9.8.1（wrapper，已缓存）| 与天工开物同款 |

## 目录结构

```
x-clean-harness/
├── third_party/DexKit/          ← DexKit 2.3.0 源码 + build/desktop/libdexkit.so（gitignore）
├── libs/dexkit-2.3.0.jar        ← AAR 提取的 JVM 类（gitignore）
├── apks/<app>/                  ← APK 样本（gitignore）
├── specs/                       ← 目标定义（YAML，数据驱动）
│   ├── x-clean/wechat.yaml      X-Clean 守卫族（首个真 spec）
│   ├── wekit-ref/wechat.yaml    WeKit PreventXposedDetection 对照复刻
│   └── discovery/wechat-signals.yaml  发现探测（探索性）
├── runner/                      ← 引擎（Kotlin/JVM）
│   └── src/main/kotlin/
│       ├── Main.kt              CLI: probe / matrix / worker
│       └── core/{Spec,Engine,Report,Matrix}.kt
├── tools/
│   ├── diff_reports.py          版本间漂移对比
│   └── export_nametable.py      名字表导出（X3 型适配数据生成器）
└── results/                     ← 报告输出（小 JSON，随仓库提交）
```

## 快速开始

```bash
cd ~/projects/x-clean-harness/runner
export JAVA_HOME=/home/wzgrx/tools/temurin26

# 单 APK 探测
./gradlew run --args="probe --spec ../specs/x-clean/wechat.yaml --apk ../apks/wechat/wechat-8.0.79.apk --out ../results/smoke/8079.json"

# 全矩阵（目录内所有 *.apk，逐 APK 独立 JVM worker）
./gradlew run --args="matrix --spec ../specs/x-clean/wechat.yaml --apks-dir ../apks/wechat --out ../results/run-xxx"

# 版本间 diff + 名字表导出
python3 ../tools/diff_reports.py ../results/run-xxx/wechat-8.0.65.json ../results/run-xxx/wechat-8.0.79.json
python3 ../tools/export_nametable.py "../results/run-xxx/wechat-*.json" --out ../results/run-xxx/nametable.json
```

### 环境搭建（首次，全部实测命令）

```bash
# ① 克隆 DexKit 2.3.0
cd ~/projects/x-clean-harness/third_party
git clone --depth 1 --branch 2.3.0 https://github.com/LuckyPray/DexKit

# ② 构建 Linux 原生库（输出 build/desktop/libdexkit.so）
cd DexKit
export JAVA_HOME=/home/wzgrx/tools/temurin26
cmake -S dexkit/src/main/cpp -B build/desktop -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
  -DCMAKE_CXX_FLAGS_RELEASE="-O3 -DNDEBUG" \
  -DCMAKE_C_FLAGS_RELEASE="-O3 -DNDEBUG" \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5
ninja -C build/desktop

# ③ JVM 类（Maven AAR → classes.jar）
cd ~/projects/x-clean-harness/libs
curl -LO https://repo1.maven.org/maven2/org/luckypray/dexkit/2.3.0/dexkit-2.3.0.aar
unzip -o dexkit-2.3.0.aar classes.jar && mv -f classes.jar dexkit-2.3.0.jar
```

## Spec 语法（YAML）

```yaml
app: com.tencent.mm
label: my-targets
targets:
  - key: xposed-check-class          # 结构匹配型（WeKit 派）
    type: class                      # class | method | field | name
    unique: true                     # 期望唯一命中（>1 → AMBIGUOUS）
    allowFailure: false              # 允许不存在（0 命中 → EXPECTED_FAILURE）
    match:
      packages: [com.tencent.mm.app]
      strings_eq: ["...", "..."]     # 全部条件为 AND
      # 可用条件：strings_eq / strings_contains(_ignore_case) / strings_starts /
      #          strings_ends / class_name_eq / class_name_contains /
      #          method_name_eq / method_name_contains / param_count(_min/_max) / return_type_eq
  - key: my-method
    type: method
    in: xposed-check-class           # 组合链：在该 class 目标解析结果内搜索
    match: { strings_eq: ["..."] }
  - key: x3-name-check               # 名字表型（X 3.0 派）
    type: name
    class: com.tencent.mm.app.j3
    member_kind: method              # 可选：验证类内成员
    member_name: a
    member_param_count: 1
```

**状态分类**：`SUCCESS` / `NOT_FOUND` / `EXPECTED_FAILURE` / `AMBIGUOUS` / `ERROR`
**纪律**：resolver 只用 DexKit metadata（桌面安全）；空 matcher 直接判 ERROR（防全量扫库）。

## 验证记录（2026-10-11 全链实测）

**矩阵运行（5 个微信版本，5/5 成功）**：
```
wechat-8.0.65  xposed-check-class → com.tencent.mm.app.n6   方法 n6#a(1)
wechat-8.0.69  xposed-check-class → com.tencent.mm.app.l6   方法 l6#a(1)
wechat-8.0.74  xposed-check-class → com.tencent.mm.app.j3   方法 j3#a(1)
wechat-8.0.78  xposed-check-class → com.tencent.mm.app.j3   方法 j3#a(1)
wechat-8.0.79  xposed-check-class → com.tencent.mm.app.j3   方法 j3#a(1)
```
★ **版本漂移活教材**：检测类每版改名（n6→l6→j3），结构 matcher 全版本追踪成功；
   这就是"结构探测 > 名字表"的实测证据（WeKit 路线验证）。

**其他实测结论**：
- `root-signal-su-path`（"/system/bin/su"）→ 每版 3 命中：`com.unionpay.a`（稳定）+ 2 个混淆类（每版漂移）
- `sig-xposed-generic`（"xposed" 包含）→ 282 个类引用（微信的 Xposed 检测面规模）
- `magisk` / `frida` / `supersu` / `busybox` 字符串 → 0 命中（负知识）
- 方法名 `isHooked` / `checkHook` → 0 命中（X3 时代内部名，现版不用）

**工具实测**：
- `diff_reports.py` 8.0.65→8.0.79：正确输出 n6→j3 漂移 + unionpay 稳定
- `export_nametable.py`：5 版本名字表导出成功（X3 型适配数据自动生成）

## 通用性（面向所有项目）

| 场景 | 用法 |
|---|---|
| **X-Clean** | specs/x-clean/ + apks/wechat/（当前）|
| **X 3.0 型名字表验证/生成** | `type: name` 目标验证已有表；`export_nametable.py` 反向生成每版新表 |
| **WeKit 型 matcher** | specs/wekit-ref/ 复刻对照（可交叉验证其 Dex-Test 报告）|
| **其他 app（红果/酷我/…）** | 新建 `specs/<project>/` + `apks/<app>/` 即可，引擎零改动 |

## 下一步

- [ ] x-clean spec 收敛：root 信号目标（unionpay/混淆类）深挖 → 正式守卫目标
- [ ] 更多微信版本（8.0.66-77 补全矩阵）
- [ ] 其他 app spec（红果等）验证通用性
- [ ] 报告 schema 对齐 WeKit 五态 + CI 化
