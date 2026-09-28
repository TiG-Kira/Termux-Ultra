#!/usr/bin/env bash
# 发布 APK 体积优化后处理（best-effort，失败则保留原 Gradle 已签名 APK）。
#
# 设计约束（Android 硬性要求，违反即闪退 / 无法安装 / 丢图标）：
#   classes*.dex / resources.arsc / *.so / AndroidManifest.xml 必须 ZIP_STORED（未压缩）且对齐。
#     - classes.dex 被压缩 → ART 无法 mmap 加载 → 闪退
#     - resources.arsc 被压缩 → targetSdk>=30 安装失败（Failed parse ... stored uncompressed）
#     - .so 被压缩 → native lib 无法加载 → 闪退
#     - launcher 图标（res/mipmap-*/ic_launcher*.png）被 7zip(LZMA) 压缩 → PackageManager 无法 mmap → 图标丢失
#   因此本脚本只做「安全」操作：
#     STEP1  strip_so.py：重打包（关键条目保持 STORED，其余 DEFLATE）+ 对 .so 做 strip --strip-debug
#     STEP2  zipalign -p 4096（页对齐未压缩条目，满足 arsc 4 字节 / .so 页对齐要求）
#     STEP3  apksigner 重签（v1+v2+v3，保证可安装、可增量更新）
#   不使用 AndResGuard 的 7zip 压缩：v2/v3 签名下 7zip 本身近乎失效，
#   且会把 resources.arsc / 图标 PNG 压坏（崩溃 + 丢图标），得不偿失。
#   真正的大幅减重来自 bootstrap 在线下载（独立规划项），本步骤只做安全、可验证的后处理。
#
# 用法: optimize_release_apk.sh <input.apk> <output.apk> <keystore> <storepass> <keypass> <alias>
# 前置依赖: java, python3, binutils(strip), Android SDK build-tools(zipalign, apksigner)
# 可选环境变量: ANDROID_SDK_ROOT / ANDROID_HOME / STRIP_TOOL / ZIPALIGN / APKSIGNER
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

INPUT="${1:-}"; OUTPUT="${2:-}"; KEYSTORE="${3:-}"
STOREPASS="${4:-}"; KEYPASS="${5:-}"; ALIAS="${6:-}"
if [ -z "$INPUT" ] || [ -z "$OUTPUT" ] || [ -z "$KEYSTORE" ] || [ -z "$ALIAS" ]; then
  echo "usage: $0 <input.apk> <output.apk> <keystore> <storepass> <keypass> <alias>" >&2
  exit 1
fi

# 定位 Android SDK 根目录：环境变量 → local.properties 的 sdk.dir → 常见默认路径
detect_sdk_root() {
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then echo "$ANDROID_SDK_ROOT"; return; fi
  if [ -n "${ANDROID_HOME:-}" ]; then echo "$ANDROID_HOME"; return; fi
  local lp="$SCRIPT_DIR/../../local.properties"
  if [ -f "$lp" ]; then
    local dir
    dir="$(grep -E '^sdk\.dir=' "$lp" | head -1 | cut -d= -f2- | tr '\\' '/')"
    if [ -n "$dir" ]; then echo "$dir"; return; fi
  fi
  if [ -d "$HOME/Android/Sdk" ]; then echo "$HOME/Android/Sdk"; return; fi
  echo ""
}

# 1. 定位 strip 工具（缺省回退 binutils strip；缺失时 strip_so.py 会跳过 strip 仍完成重打包）
STRIP_TOOL="${STRIP_TOOL:-}"
if [ -z "$STRIP_TOOL" ]; then
  SDK="$(detect_sdk_root)"
  if [ -n "$SDK" ]; then
    STRIP_TOOL="$(ls -d "$SDK"/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip 2>/dev/null | head -1 || true)"
  fi
  if [ -z "$STRIP_TOOL" ] && command -v strip >/dev/null 2>&1; then
    STRIP_TOOL="$(command -v strip)"
  fi
  # 允许缺失：置空，交给 strip_so.py 容错
fi
echo ">> strip 工具: ${STRIP_TOOL:-<未找到，跳过 strip>}"

# 2. 定位 zipalign / apksigner（来自 Android build-tools）
ZIPALIGN="${ZIPALIGN:-}"
APKSIGNER="${APKSIGNER:-}"
SDK="$(detect_sdk_root)"
if [ -n "$SDK" ]; then
  [ -z "$ZIPALIGN" ]  && ZIPALIGN="$(ls -d "$SDK"/build-tools/*/zipalign 2>/dev/null | sort -V | tail -1 || true)"
  [ -z "$APKSIGNER" ] && APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
fi
[ -z "$ZIPALIGN" ]  && command -v zipalign  >/dev/null 2>&1 && ZIPALIGN="$(command -v zipalign)"
[ -z "$APKSIGNER" ] && command -v apksigner >/dev/null 2>&1 && APKSIGNER="$(command -v apksigner)"
[ -z "$ZIPALIGN" ]  && { echo "ERROR: 找不到 zipalign（需 Android build-tools）" >&2; exit 1; }
[ -z "$APKSIGNER" ] && { echo "ERROR: 找不到 apksigner（需 Android build-tools）" >&2; exit 1; }
echo ">> zipalign: $ZIPALIGN"
echo ">> apksigner: $APKSIGNER"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# STEP1: 安全重打包（关键条目 STORED，其余 DEFLATE；丢弃旧 META-INF 签名）
REPACKED="$WORK/repacked.apk"
python3 "$SCRIPT_DIR/strip_so.py" "$INPUT" "$REPACKED" "${STRIP_TOOL:-}"
echo ">> 重打包后: $(stat -c%s "$REPACKED") bytes（原 $(stat -c%s "$INPUT") bytes）"

# STEP2: 页对齐（4096），使未压缩条目满足 arsc 4 字节 / .so 页对齐要求
ALIGNED="$WORK/aligned.apk"
"$ZIPALIGN" -p 4096 "$REPACKED" "$ALIGNED"

# STEP3: 重签（v1+v2+v3，保证可安装、可增量更新）
SIGNED="$WORK/signed.apk"
"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$STOREPASS" \
  --key-pass "pass:$KEYPASS" \
  --out "$SIGNED" \
  "$ALIGNED"

# 校验通过后才覆盖原包：任一环节失败则 OUTPUT 保持不变 → CI 上传原 Gradle 已签名包
"$ZIPALIGN" -c -p 4096 "$SIGNED" || { echo "ERROR: zipalign 校验失败" >&2; exit 1; }
"$APKSIGNER" verify "$SIGNED" || { echo "ERROR: apksigner 校验失败" >&2; exit 1; }

TMP_OUT="$OUTPUT.tmp.$$"
cp -f "$SIGNED" "$TMP_OUT"
mv -f "$TMP_OUT" "$OUTPUT"
echo ">> 优化完成: $OUTPUT ($(stat -c%s "$OUTPUT") bytes)"
