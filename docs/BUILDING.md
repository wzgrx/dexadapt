# 固定工具链与依赖构建

源码版本 0.13.1 使用 JDK 27.0.1.1、Gradle 9.9.2、Kotlin 2.4.21，字节码目标继续为 JVM 17。主要依赖采用 Serialization 1.13.0、apksig 9.5.3、jadx 1.5.7、dexlib2 3.0.10、ASM 9.11.1 和 SnakeYAML Engine 3.2。

## 首次构建

模块源码构建环境为 Linux 或 WSL。准备 git、CMake、Ninja、Clang、Maven、JDK 17 及 Android NDK 30.0.16248370。依赖源码的上游构建使用 JDK 17，DexAdapt 本身使用当前主工具链。

设置 DEXKIT_BUILD_JAVA_HOME 指向 JDK 17，然后执行以下命令。

    python3 scripts/build-dexkit.py
    python3 scripts/build-jadx.py
    ./gradlew test :cli:installDist :jadx-plugin:pluginJar

依赖缓存默认在 ~/tools/dexkit-source，可用 DEXKIT_SOURCE_HOME 指定。生成的本地 Maven 仓库位于该目录下的 maven，也可通过 DEXKIT_MAVEN_REPO 单独指定。使用方需要在自己的 settings.gradle.kts 加入该文件仓库，限制为 org.luckypray:dexkit、com.google.flatbuffers:flatbuffers-java 及 io.github.skylot 的构件。

## 源码版本与兼容接口

DexKit 使用官方主线 a855c58e54538c6926940491a72616044e38264a，包含 2.3.0 发布后的 5 项修复。构件标记为 2.3.0-a855c58e-fb25.12.19-r2，固定输入在 gradle/dexkit-source.json。

FlatBuffers 使用官方 25.12.19 发布源码，flatc、Java 运行库及 Kotlin/C++ schema 同步构建，版本断言继续生效。构建过程执行上游 Java 和 DexKit 测试。

jadx 1.5.7 当前 Maven 未提供对应构件，本仓库从官方发布提交 c1ef5019bb62f7d490149a53619918a23a59178e 构建四个独立模块及其 POM。固定输入在 gradle/jadx-source.json。构建脚本过滤继承的 JADX_* 环境设置，再传入公开的版本和 JDK 配置。

Hook 适配器按各自公开 API 线编译。YukiHookAPI 1.3 的坐标使用 1.3.2，EzXHelper 2.x 的坐标使用 2.2.1。它们与后来重写或改坐标的 YukiHook、EzXHelper 3.x 属于不同兼容接口，现有适配器调用方继续使用原有类型签名。

## 缓存与使用方

构建回执记录固定提交、构建脚本摘要和输出 SHA-256，写入过程使用锁及原子替换。后续构建先核对产物哈希，再复用缓存。Android APK 可用 python3 scripts/build-dexkit.py --verify-apk 路径核对其原生库与源 AAR。

使用方更新依赖后，要跟进版本目录及 gradle/dexadapt.commit。改动 SymbolResolver、SymbolSource 或 DexKitSource 时仍按原约定更新解析代码摘要。固定代码与版本目录让已发布版本可以按旧提交重新构建。
