#!/usr/bin/env bash
# 构建电脑上用的 libdexkit.so（DexKit 原生库的 Linux 版），给电脑上的 DexKit 一致性校验用：
# 在电脑上用和手机同一份 DexKitSource 解析 APK，确认手机上的本机适配和离线解析一致。
# 版本取 gradle/libs.versions.toml 里的 dexkit，和模块打包的一致。产物放在仓库外：
#   ${DEXKIT_DESKTOP_HOME:-$HOME/tools/dexkit-desktop}/<版本>/libdexkit.so
# 需要 git、cmake、ninja、clang。用法：scripts/build-dexkit-desktop.sh
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n 's/^dexkit = "\(.*\)"/\1/p' gradle/libs.versions.toml)
[ -n "$version" ] || { echo "错误：libs.versions.toml 里没有 dexkit 版本" >&2; exit 1; }
home="${DEXKIT_DESKTOP_HOME:-$HOME/tools/dexkit-desktop}/$version"
lib="$home/libdexkit.so"
if [ -f "$lib" ]; then echo "已有：$lib"; exit 0; fi
for tool in git cmake ninja clang clang++; do command -v "$tool" >/dev/null || { echo "错误：缺少 $tool" >&2; exit 1; }; done
src="$home/src"
rm -rf "$src"; mkdir -p "$home"
git -c advice.detachedHead=false clone -q --depth 1 --branch "$version" https://github.com/LuckyPray/DexKit "$src"
# DexKit 声明的 CMake 最低版本是 3.4.1，新版 CMake 需要 CMAKE_POLICY_VERSION_MINIMUM 才肯配置。
cmake -S "$src/dexkit/src/main/cpp" -B "$src/build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ -DCMAKE_POLICY_VERSION_MINIMUM=3.5 >/dev/null
ninja -C "$src/build" >/dev/null
cp "$src/build/libdexkit.so" "$lib"
rm -rf "$src"
echo "完成：$lib"
