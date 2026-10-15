#!/usr/bin/env bash
# 本机模拟器冒烟环境（设计 10.9）：建一个 x86_64 的 AVD、无界面启动、adb root，之后可以装多个宿主版本自动跑冒烟。
#
# 默认只检查环境、打印要做的事，不启动任何东西。加 --start 才真正创建并启动：
#   scripts/emulator.sh [--start] [--api 36] [--name dexadapt-api36]
#
# 注意（共享机器）：模拟器起来后 `adb devices` 会多一台设备，别的会话里不带 -s 的 adb 命令会因为“多台设备”报错。
# 启动前确认没人在用 adb；用完 `adb -s emulator-5554 emu kill` 关掉。
#
# 装 LSPosed：google_apis 镜像能 adb root，但 LSPosed 要 Magisk + Zygisk。用 rootAVD
# （https://gitlab.com/newbit/rootAVD）给这个 AVD 的 ramdisk 打 Magisk 补丁，冷启动后在 Magisk 里刷 LSPosed（Zygisk 版），
# 再在 LSPosed 管理器里给模块勾上宿主。之后每个宿主版本：adb install 宿主 APK → 模块的冒烟脚本（红薯助手是 scripts/smoke.sh）。
# 宿主只有 arm64 库时靠镜像自带的 ARM 翻译运行，比真机慢。
set -euo pipefail
api=36
name=""
start=false
while [ $# -gt 0 ]; do
  case "$1" in
    --start) start=true; shift ;;
    --api) api="$2"; shift 2 ;;
    --name) name="$2"; shift 2 ;;
    *) echo "不认识的参数 $1" >&2; exit 2 ;;
  esac
done
name="${name:-dexadapt-api$api}"
sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
image="system-images;android-$api;google_apis;x86_64"
image_dir="$sdk/system-images/android-$api/google_apis/x86_64"
avdmanager=$(ls "$sdk"/cmdline-tools/*/bin/avdmanager 2>/dev/null | head -1 || true)

ok=true
check() { if eval "$2"; then echo "  ✓ $1"; else echo "  ✗ $1"; ok=false; fi; }
echo "环境："
check "KVM（/dev/kvm 可读写）" '[ -r /dev/kvm ] && [ -w /dev/kvm ]'
check "模拟器（$sdk/emulator）" '[ -x "$sdk/emulator/emulator" ]'
check "系统镜像（$image）" '[ -d "$image_dir" ]'
check "avdmanager" '[ -n "$avdmanager" ]'
existing=$("$sdk/emulator/emulator" -list-avds 2>/dev/null | grep -x "$name" || true)
echo "  AVD $name：${existing:+已存在}${existing:-还没有}"
echo "  adb 上现在的设备："; adb devices | tail -n +2 | sed '/^$/d; s/^/    /'

if ! $start; then
  echo
  echo "要做的事（加 --start 执行）：创建 AVD $name（$image，4 核 6 GB）→ 无界面冷启动 → 等开机 → adb root"
  $ok || echo "环境不全，先补上打 ✗ 的项"
  exit 0
fi
$ok || { echo "环境不全，不启动" >&2; exit 1; }

if [ -z "$existing" ]; then
  echo no | "$avdmanager" create avd -n "$name" -k "$image" -d pixel_6 >/dev/null
  cfg="$HOME/.android/avd/$name.avd/config.ini"
  sed -i 's/^hw.ramSize=.*/hw.ramSize=6144/; s/^hw.cpu.ncore=.*/hw.cpu.ncore=4/' "$cfg"
  grep -q '^hw.ramSize' "$cfg" || echo "hw.ramSize=6144" >> "$cfg"
fi
before=$(adb devices | grep -c "^emulator-" || true)
nohup "$sdk/emulator/emulator" -avd "$name" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect >"/tmp/$name.log" 2>&1 &
echo "启动中（日志 /tmp/$name.log）…"
for _ in $(seq 1 120); do
  serial=$(adb devices | awk '/^emulator-/{print $1}' | tail -1)
  if [ -n "$serial" ] && [ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
    adb -s "$serial" root >/dev/null
    echo "开机完成：$serial（已 adb root）。关掉：adb -s $serial emu kill"
    exit 0
  fi
  sleep 5
done
echo "10 分钟内没开机，看 /tmp/$name.log" >&2
exit 1
