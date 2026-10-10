# X-Clean 桌面验证台（x-clean-harness）

> 在 Linux 桌面对微信 APK 直接跑 DexKit 目标解析——不装微信、不连手机、不跑 Hook。
> 为 X-Clean 模块的 resolver 提供"版本矩阵预验证"能力（学自 WeKit 的 ./x dex-test 设计）。

## 环境（本机已验证）

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | temurin26（`~/tools/temurin26`）| 构建 + 运行均用它（DexKit 类目标 Java 11）|
| CMake | 4.4.4 | 需 `-DCMAKE_POLICY_VERSION_MINIMUM=3.5`（DexKit 声明最低 3.4.1 的逃生舱）|
| Ninja | 1.13.2 | |
| clang | 23.x | 与 DexKit CI 一致 |
| ccache | 4.14 | 构建自动加速 |
| Gradle | 9.8.1（wrapper，已缓存）| 与天工开物同款 |

## 目录结构

```
x-clean-harness/
├── third_party/DexKit/          ← 源码克隆（2.3.0，浅克隆，gitignore）
│   └── build/desktop/libdexkit.so   ← 编译产物
├── libs/dexkit-2.3.0.jar        ← 从 Maven AAR 提取的 JVM 类（gitignore）
├── libs/dexkit-2.3.0.aar        ← Maven 原件
├── apks/                        ← 微信 APK 样本（gitignore）
├── runner/                      ← 验证台代码（Kotlin/JVM + Gradle）
└── results/                     ← 报告输出（预留）
```

## 快速开始（全部实测命令）

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

# ④ 运行（APK 路径 + 任意个搜索字符串）
cd ~/projects/x-clean-harness/runner
JAVA_HOME=/home/wzgrx/tools/temurin26 ./gradlew run --args="../apks/wechat-device-8079.apk de.robv.android.xposed.XposedBridge com.zte.heartyservice.SCC.FrameworkBridge"
```

## 验证记录（2026-10-11 冒烟通过）

```
样本:  微信 8.0.79 (versionCode 3200) · 285,284,760 B（红米拉取）
原生:  libdexkit.so 1,463,320 B（clang 23 / CMake 4.4.4 构建，29 targets）
运行:  bridge 创建 121ms / 18 dex → 搜索 34ms
结果:  findClass(com.tencent.mm.app, {XposedBridge, FrameworkBridge}) = 1 hit
       → com.tencent.mm.app.j3   ★即 WeKit PreventXposedDetection 的同款目标
```

## 关键纪律（学自 WeKit）

1. resolver 只用 DexKit metadata（禁止 JVM 反射）→ 同套代码真机+桌面双跑
2. 每个 APK 独立 JVM worker（native 崩溃隔离）——待 Phase C 落地
3. 报告五态分类（SUCCESS / EXPECTED_FAILURE / UNEXPECTED_FAILURE / BLOCKED / INCOMPLETE）——待 Phase C
4. 缓存键 = resolver 源码哈希——待 Phase C

## 下一步（Phase C/D）

- [ ] APK 样本矩阵（8.0.65–8.0.79，官方站下载 + 校验）
- [ ] resolver 注册机制（把搜索逻辑抽成可枚举的 resolver 列表）
- [ ] JSON 报告输出 + summary + 版本间 diff（Python 工具）
- [ ] 移植 X 3.0 checkHook 目标族为 DexKit matcher，跑全矩阵
