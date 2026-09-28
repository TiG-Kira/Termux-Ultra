#!/usr/bin/env bash
# 发布 APK 体积优化后处理：
#   STEP1  strip_so.py：重打包压缩（把原本近乎未压缩的 DEX/arsc 用 DEFLATE 重压，主要体积来源）
#          + 对 .so 执行 strip --strip-debug（当前构建 .so 多已 stripped，此步近乎空转但保留符号表）
#   STEP2  AndResGuard 7zip 压缩资源 + 重签名 + zipalign（保持可安装、可更新）
#
# 用法:
#   optimize_release_apk.sh <input.apk> <output.apk> <keystore> <storepass> <keypass> <alias>
#
# 前置依赖（CI 步骤里先装好）: java, python3, p7zip-full(7za), binutils(strip)
# 可选环境变量:
#   ANDROID_SDK_ROOT / ANDROID_HOME  用于定位 ndk/llvm-strip 与 build-tools/zipalign
#   STRIP_TOOL   强制指定 strip 工具（默认优先 NDK llvm-strip，回退 binutils strip）
#   ZIPALIGN     强制指定 zipalign（默认定位 SDK build-tools 下的）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

INPUT="${1:-}"; OUTPUT="${2:-}"; KEYSTORE="${3:-}"
STOREPASS="${4:-}"; KEYPASS="${5:-}"; ALIAS="${6:-}"
if [ -z "$INPUT" ] || [ -z "$OUTPUT" ] || [ -z "$KEYSTORE" ] || [ -z "$ALIAS" ]; then
  echo "usage: $0 <input.apk> <output.apk> <keystore> <storepass> <keypass> <alias>" >&2
  exit 1
fi

CONFIG="$SCRIPT_DIR/andresguard_config.xml"
[ -f "$CONFIG" ] || { echo "ERROR: 找不到 andresguard_config.xml: $CONFIG" >&2; exit 1; }

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

# 1. 定位 strip 工具
STRIP_TOOL="${STRIP_TOOL:-}"
if [ -z "$STRIP_TOOL" ]; then
  SDK="$(detect_sdk_root)"
  if [ -n "$SDK" ]; then
    STRIP_TOOL="$(ls -d "$SDK"/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip 2>/dev/null | head -1 || true)"
  fi
  if [ -z "$STRIP_TOOL" ] && command -v strip >/dev/null 2>&1; then
    STRIP_TOOL="$(command -v strip)"
  fi
  [ -z "$STRIP_TOOL" ] && { echo "ERROR: 找不到可用的 llvm-strip / strip" >&2; exit 1; }
fi
echo ">> strip 工具: $STRIP_TOOL"

# 2. 定位 zipalign（useLegacyPackaging=true 必须重新 4K 对齐）
ZIPALIGN="${ZIPALIGN:-}"
if [ -z "$ZIPALIGN" ]; then
  SDK="$(detect_sdk_root)"
  if [ -n "$SDK" ]; then
    ZIPALIGN="$(ls -d "$SDK"/build-tools/*/zipalign 2>/dev/null | sort -V | tail -1 || true)"
  fi
  if [ -z "$ZIPALIGN" ] && command -v zipalign >/dev/null 2>&1; then
    ZIPALIGN="$(command -v zipalign)"
  fi
  [ -z "$ZIPALIGN" ] && { echo "ERROR: 找不到 zipalign（需要 Android build-tools，且 ANDROID_SDK_ROOT 需指向 SDK）" >&2; exit 1; }
fi
echo ">> zipalign: $ZIPALIGN"

# 3. 定位 7za
command -v 7za >/dev/null 2>&1 || { echo "ERROR: 找不到 7za（需安装 p7zip-full）" >&2; exit 1; }
SEVENZIP="$(command -v 7za)"

# 4. AndResGuard jar（优先用仓库内已提交的，缺失再下载）
JAR="${ANDRESGUARD_JAR:-$SCRIPT_DIR/AndResGuard-cli-1.2.15.jar}"
if [ ! -s "$JAR" ]; then
  JAR="$HOME/.cache/andresguard/AndResGuard-cli-1.2.15.jar"
  mkdir -p "$(dirname "$JAR")"
  echo ">> 仓库内 jar 缺失，尝试下载 ..."
  for url in \
    "https://github.com/shwenzhang/AndResGuard/raw/master/tool_output/AndResGuard-cli-1.2.15.jar" \
    "https://raw.githubusercontent.com/shwenzhang/AndResGuard/master/tool_output/AndResGuard-cli-1.2.15.jar" \
    "https://cdn.jsdelivr.net/gh/shwenzhang/AndResGuard@master/tool_output/AndResGuard-cli-1.2.15.jar" ; do
    if curl -fsSL "$url" -o "$JAR"; then echo ">> 下载成功: $url"; break; fi
  done
  [ -s "$JAR" ] || { echo "ERROR: AndResGuard jar 下载失败" >&2; exit 1; }
fi
echo ">> AndResGuard jar: $JAR"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 5. STEP1: 剥离 .so 调试符号 + 重打包压缩
STRIPPED="$WORK/stripped.apk"
python3 "$SCRIPT_DIR/strip_so.py" "$INPUT" "$STRIPPED" "$STRIP_TOOL"
echo ">> strip 后: $(stat -c%s "$STRIPPED") bytes（原 $(stat -c%s "$INPUT") bytes）"

# 6. STEP1.5: DEX debug_info 瘦身（删除局部变量/栈帧标记，只保留行号表）
#    先从 ZIP 中解压所有 classes*.dex → 逐个瘦身 → 写回 APK（保持 ZIP_STORED）
STRIPPED_DEX="$WORK/stripped_dex.apk"
python3 - <<PYEOF
import os, sys, zipfile
sys.path.insert(0, "$SCRIPT_DIR")
from dex_strip import main as dex_strip_main  # noqa

src = "$STRIPPED"
dst = "$STRIPPED_DEX"

with zipfile.ZipFile(src, 'r') as zin, zipfile.ZipFile(dst, 'w') as zout:
    for info in zin.infolist():
        data = zin.read(info.filename)
        if info.filename.startswith('classes') and info.filename.endswith('.dex'):
            # 瘦身 DEX
            tmp_in  = "$WORK/_dex_in.dex"
            tmp_out = "$WORK/_dex_out.dex"
            open(tmp_in, 'wb').write(data)
            sys.argv = ["dex_strip.py", tmp_in, tmp_out]
            try:
                dex_strip_main()
                data = open(tmp_out, 'rb').read()
                print(f"  [DEX] {info.filename}: {os.path.getsize(tmp_in):,} → {len(data):,} bytes")
            except SystemExit:
                print(f"  [DEX] {info.filename}: dex_strip 失败，原样保留", file=sys.stderr)
            finally:
                for f in (tmp_in, tmp_out):
                    if os.path.exists(f): os.remove(f)
        # 写回（保持原始 compression 方式；DEX 已经是 STORED）
        zout.writestr(info, data)

print(f">> DEX 瘦身完成 → $(stat -c%s "$dst") bytes")
PYEOF

# 7. STEP2: AndResGuard（7zip 压缩 + 重签名 + zipalign）
AR_OUT="$WORK/ar"
mkdir -p "$AR_OUT"
java -jar "$JAR" "$STRIPPED_DEX" \
  -config "$CONFIG" \
  -out "$AR_OUT" \
  -signature "$KEYSTORE" "$STOREPASS" "$KEYPASS" "$ALIAS" \
  -7zip "$SEVENZIP" \
  -zipalign "$ZIPALIGN" 2>&1 | tail -20

# AndResGuard 在 -signature -7zip -zipalign 同时启用时，最终产物为 *_signed_7zip_aligned.apk
FINAL="$(ls "$AR_OUT"/*_7zip_aligned.apk 2>/dev/null | head -1)"
[ -n "$FINAL" ] || { echo "ERROR: AndResGuard 未产出 *_7zip_aligned.apk" >&2; ls -la "$AR_OUT" >&2; exit 1; }

# 先写到临时文件，再原子 rename 覆盖原包：保证失败时原 APK 不被破坏（CI 中原样上传）
TMP_OUT="$OUTPUT.tmp.$$"
cp -f "$FINAL" "$TMP_OUT"
mv -f "$TMP_OUT" "$OUTPUT"
echo ">> 优化完成: $OUTPUT ($(stat -c%s "$OUTPUT") bytes)"
