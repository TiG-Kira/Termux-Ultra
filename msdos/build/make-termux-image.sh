#!/usr/bin/env bash
# 从 dosemu2 编译产物生成面向移动端 QEMU 的精简可启动硬盘镜像。
# 用法: make-termux-image.sh <build/v4.0 目录> <输出目录>
set -euo pipefail

BUILD_DIR="${1:?需要编译产物目录 (v4.0)}"
OUT_DIR="${2:?需要输出目录}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_DIR="$ROOT/termux/config"

command -v mcopy >/dev/null || { echo "缺少 mtools" >&2; exit 1; }
[[ -f "$BUILD_DIR/io.sys" && -f "$BUILD_DIR/msdos.sys" && -f "$BUILD_DIR/command.com" ]] || {
    echo "缺少系统文件，请先运行 mak.sh --flavor=msdos" >&2; exit 1
}

mkdir -p "$OUT_DIR" "$BUILD_DIR/staging"

# 16MB FAT16 单分区；mkfatimage16 负责 MBR + 真实 MS-DOS 引导扇区（vvfat 做不到）。
"$BUILD_DIR/mkhdimg.sh" --size 16 -o "$OUT_DIR/msdos4.img" || {
    echo "基础镜像生成失败" >&2; exit 1
}
IMG="$OUT_DIR/msdos4.img"

# 精简外部命令：只保留常用 20 个，其余从 C:\DOS 删除。
KEEP="attrib.exe chkdsk.com command.com comp.com debug.com diskcopy.com edlin.com \
      exe2bin.exe fc.exe fdisk.exe find.exe format.com label.com mem.exe mode.com \
      more.com replace.exe sort.exe subst.exe sys.com tree.com xcopy.exe"
mdel -i "$IMG" '::/DOS/*.*' >/dev/null 2>&1 || true
for name in $KEEP; do
    src="$BUILD_DIR/$name"
    [[ -f "$src" ]] || continue
    mcopy -i "$IMG" -o "$src" "::/DOS/$name" || true
done

# 覆盖为精简配置。仓库里的源文件是 LF，DOS 需要 CRLF，落盘前统一转换。
for cfg in CONFIG.SYS AUTOEXEC.BAT; do
    sed 's/\r$//; s/$/\r/' "$CONFIG_DIR/$cfg" > "$BUILD_DIR/staging/$cfg"
    mcopy -i "$IMG" -o "$BUILD_DIR/staging/$cfg" "::/$cfg"
done
mdir -i "$IMG" '::/DOS' | tail -3 || true

# 压缩后发布；App 侧校验 .sha256 再 gzip 解压到 $HOME/msdos/msdos4.img。
# -n：不写入源文件 mtime，保证逐字节相同的镜像每次产物哈希一致，用户缓存才能命中。
gzip -9 -n -c "$IMG" > "$OUT_DIR/msdos4.img.gz"
sha256sum "$OUT_DIR/msdos4.img.gz" | cut -d' ' -f1 > "$OUT_DIR/msdos4.img.gz.sha256"

echo "产物："
ls -lh "$OUT_DIR/msdos4.img" "$OUT_DIR/msdos4.img.gz" "$OUT_DIR/msdos4.img.gz.sha256"
