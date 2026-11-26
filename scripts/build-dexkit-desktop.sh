#!/usr/bin/env bash
# 固定源码构建 DexKit、FlatBuffers 运行库及两端生成绑定。
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/build-dexkit.py
