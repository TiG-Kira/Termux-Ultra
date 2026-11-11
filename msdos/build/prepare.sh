#!/usr/bin/env bash
# 以 vendor 的微软原始源码为基底，叠加固定版本的社区修复层，产出可 nmake 的构建树。
# 上游 v4.0/src 有 CP437 转码损坏、CRLF 混乱和 SETENV.BAT 盘符硬编码，直接构建会失败。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${1:-$ROOT/.build}"
FIX_REF="c8d7ddfe720df811e8c124810a16e34a71cc63d4"
FIX_URL="https://github.com/tgies/MS-DOS/archive/${FIX_REF}.tar.gz"

if [[ ! -f "$ROOT/v4.0/LICENSE" || ! -d "$ROOT/v4.0/src" ]]; then
    echo "vendor 源码缺失：$ROOT/v4.0" >&2
    exit 1
fi

rm -rf "$WORK"
mkdir -p "$WORK/v4.0" "$WORK/fix"
cp -a "$ROOT/v4.0/." "$WORK/v4.0/"

echo "拉取修复层 $FIX_REF ..."
curl -fsSL --retry 3 --max-time 180 "$FIX_URL" -o "$WORK/fix.tar.gz"
tar -xzf "$WORK/fix.tar.gz" -C "$WORK/fix" --strip-components=1

FIX_SRC="$WORK/fix/v4.0/src"
if [[ ! -d "$FIX_SRC" ]]; then
    echo "修复层结构异常，找不到 $FIX_SRC" >&2
    exit 1
fi

# 只覆盖内容不同的文件，其余保留微软原始字节；打印差异数便于审计。
CHANGED=$(rsync -rcni "$FIX_SRC/" "$WORK/v4.0/src/" | grep -c '^>f' || true)
echo "修复层覆盖 $CHANGED 个与上游不同的文件"
rsync -rc "$FIX_SRC/" "$WORK/v4.0/src/"

for f in mak.sh mak.bat mkhdimg.sh; do
    cp "$WORK/fix/v4.0/$f" "$WORK/v4.0/$f"
    chmod +x "$WORK/v4.0/$f"
done

# DOS 工具链要求 CRLF 与 8.3 大写路径，且 SETENV.BAT 里的 BAKROOT 必须指向 dosemu 的盘符。
find "$WORK/v4.0/src" -type f \( -iname '*.bat' -o -iname '*.asm' -o -iname '*.inc' -o -iname '*.mak' \) -print0 \
    | xargs -0 -r sed -i 's/\r$//; s/$/\r/'
sed -i 's/\r$//; s/^set BAKROOT=.*/set BAKROOT=c:/; s/$/\r/' "$WORK/v4.0/src/SETENV.BAT"

echo "构建树就绪：$WORK/v4.0"
