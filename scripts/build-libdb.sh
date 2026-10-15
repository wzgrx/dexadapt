#!/usr/bin/env bash
# 重建随 DexAdapt 发布的开源库指纹库（libs/src/main/resources/dexadapt/libs.json.gz）：
# 从 Maven 仓库下载 KnownLibraries 里每个库的各个版本（缓存在 ~/.cache/dexadapt/maven），提取特征字符串。
# 用法：scripts/build-libdb.sh
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew -q :cli:installDist
out=libs/src/main/resources/dexadapt/libs.json.gz
mkdir -p "$(dirname "$out")"
cli/build/install/dexadapt/bin/dexadapt libs-db --out "$out"
