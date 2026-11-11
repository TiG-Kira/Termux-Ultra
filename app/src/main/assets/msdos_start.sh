#!/data/data/com.termux/files/usr/bin/bash
# MS-DOS 4.00 在 Termux / proot 容器里的启动脚本。
# 精简目标：纯 TCG、无 KVM 的手机也能快速进入 DOS 提示符，不挂网卡/USB/9p/声卡。
set -u

TERMUX_HOME=/data/data/com.termux/files/home
if [ -d /root/shared/msdos ]; then
    BASE=/root/shared/msdos
    IN_CONTAINER=1
else
    BASE="$TERMUX_HOME/msdos"
    IN_CONTAINER=0
fi

MODE=console
VNC_PORT=5900
MOUNT_DIR=-
if [ -f "$BASE/msdos_args" ]; then
    while IFS='=' read -r key value; do
        case "$key" in
            MODE) MODE="$value" ;;
            VNC_PORT) VNC_PORT="$value" ;;
            MOUNT_DIR) MOUNT_DIR="$value" ;;
        esac
    done < "$BASE/msdos_args"
fi
case "$VNC_PORT" in
    ''|*[!0-9]*) VNC_PORT=5900 ;;
    *) if [ "$VNC_PORT" -lt 5900 ] || [ "$VNC_PORT" -gt 5999 ]; then VNC_PORT=5900; fi ;;
esac
[ "$MODE" = vnc ] || MODE=console

IMG="$BASE/msdos4.img"
DATA_IMG="$BASE/userdata.img"
LOG="$BASE/qemu.log"

if [ ! -s "$IMG" ]; then
    echo "缺少系统盘 $IMG"
    echo "请先回到 MS-DOS 模拟器页面下载磁盘资产。"
    exit 1
fi

ensure_qemu() {
    for b in qemu-system-i386 qemu-system-x86_64; do
        if command -v "$b" >/dev/null 2>&1; then QEMU_BIN="$b"; return 0; fi
    done
    echo "正在安装 QEMU 与 mtools ..."
    if command -v apt-get >/dev/null 2>&1; then
        apt-get update -qq && apt-get install -y -qq qemu-system-x86 qemu-utils mtools 2>&1 | tail -20
    else
        pkg install -y qemu-system-i386-headless qemu-utils mtools 2>&1 | tail -20
    fi
    for b in qemu-system-i386 qemu-system-x86_64; do
        if command -v "$b" >/dev/null 2>&1; then QEMU_BIN="$b"; return 0; fi
    done
    echo "QEMU 安装失败，请手动执行：pkg install qemu-system-i386-headless mtools"
    exit 1
}
ensure_qemu

# mtools 快照盘：用户目录写进独立 FAT16 数据盘，绝不作为启动盘。
# 必须有 MBR 分区表，否则 DOS 4.0 不会给这块 BIOS 硬盘分配 D: 盘符。
# vvfat 没有引导扇区注入，且根目录 512 项 / 8.3 短名限制，不用于此处。
EXTRA=()
if [ "$MOUNT_DIR" != "-" ]; then
    MOUNT_SRC="$MOUNT_DIR"
    if [ "$IN_CONTAINER" = 1 ]; then
        case "$MOUNT_DIR" in
            /data/data/com.termux/files/home)
                MOUNT_SRC="/root/shared" ;;
            /data/data/com.termux/files/home/*)
                MOUNT_SRC="/root/shared/${MOUNT_DIR#/data/data/com.termux/files/home/}" ;;
            /sdcard)
                MOUNT_SRC="/root/shared/storage/shared" ;;
            /sdcard/*)
                MOUNT_SRC="/root/shared/storage/shared/${MOUNT_DIR#/sdcard/}" ;;
            /storage/emulated/0)
                MOUNT_SRC="/root/shared/storage/shared" ;;
            /storage/emulated/0/*)
                MOUNT_SRC="/root/shared/storage/shared/${MOUNT_DIR#/storage/emulated/0/}" ;;
            *)
                MOUNT_SRC="" ;;
        esac
    fi
    if [ -z "$MOUNT_SRC" ]; then
        echo "跳过挂载：容器内不支持该目录 $MOUNT_DIR"
    elif [ ! -d "$MOUNT_SRC" ]; then
        echo "跳过挂载：目录不存在 $MOUNT_SRC"
    elif ! command -v mformat >/dev/null 2>&1 || ! command -v mcopy >/dev/null 2>&1; then
        echo "跳过挂载：缺少 mtools（pkg install mtools）"
    else
        echo "正在把 $MOUNT_DIR 打包为数据盘 D: ..."
        DATA_MB=16
        PART_LBA=63
        PART_OFFSET=$((PART_LBA * 512))
        TOTAL_SECTORS=$((DATA_MB * 2048))
        PART_SECTORS=$((TOTAL_SECTORS - PART_LBA))
        dd if=/dev/zero of="$DATA_IMG" bs=1M count=$DATA_MB 2>/dev/null
        # 分区表项：非活动 / 起始 CHS (cyl0,head1,sect1) / FAT16 <32MB(0x04) /
        # 起始 LBA 63 / 扇区数 32705 = 0x7FC1。DOS 4.0 走 int13h CHS，
        # 不认后来才有的 0x06(LBA FAT16)，起始 CHS 填 0 会读不到分区引导扇区。
        printf '\x00\x01\x01\x00\x04\xfe\xff\xff\x3f\x00\x00\x00\xc1\x7f\x00\x00' \
            | dd of="$DATA_IMG" bs=1 seek=446 conv=notrunc 2>/dev/null
        printf '\x55\xaa' | dd of="$DATA_IMG" bs=1 seek=510 conv=notrunc 2>/dev/null
        if mformat -i "$DATA_IMG@@$PART_OFFSET" -F 16 :: >/dev/null 2>&1; then
            COPY_OK=1
            if [ -n "$(ls -A "$MOUNT_SRC" 2>/dev/null)" ]; then
                (cd "$MOUNT_SRC" && mcopy -i "$DATA_IMG@@$PART_OFFSET" -s ./* ::/) \
                    >/dev/null 2>&1 || COPY_OK=0
            fi
            if [ "$COPY_OK" = 1 ]; then
                EXTRA=(-hdb "$DATA_IMG")
                echo "数据盘就绪：$DATA_IMG（$DATA_MB MB，分区 $PART_SECTORS 扇区）"
            else
                echo "跳过挂载：mcopy 写入失败（检查 8.3 短名冲突与目录体积）"
            fi
        else
            echo "跳过挂载：mformat 分区格式化失败"
        fi
    fi
fi

QEMU_ARGS=(
    -M isapc
    -cpu 486
    -m 8
    -smp 1
    -accel tcg,thread=single
    -vga std
    -no-reboot
    -rtc base=localtime
    -hda "$IMG"
    -boot c
    "${EXTRA[@]}"
)

command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock >/dev/null 2>&1 || true

if [ "$MODE" = "console" ]; then
    export TERM="${TERM:-xterm-256color}"
    echo "控制台模式：QEMU curses（80x25 文本）。Alt+2 进 monitor 输入 quit 退出。"
    if "$QEMU_BIN" "${QEMU_ARGS[@]}" -display curses,charset=CP437 2>"$BASE/qemu.err"; then
        exit 0
    fi
    echo "curses 不可用，回退 -nographic："
    tail -5 "$BASE/qemu.err"
    "$QEMU_BIN" "${QEMU_ARGS[@]}" -nographic -serial mon:stdio
    exit $?
fi

VNC_DISPLAY=$((VNC_PORT - 5900))
[ "$VNC_DISPLAY" -lt 0 ] && VNC_DISPLAY=0
echo "VNC 模式：127.0.0.1:$VNC_PORT（仅绑定回环）"
nohup "$QEMU_BIN" "${QEMU_ARGS[@]}" -vnc "127.0.0.1:$VNC_DISPLAY" > "$LOG" 2>&1 &
echo $! > "$BASE/qemu.pid"
for _ in $(seq 1 40); do
    if (exec 3<>/dev/tcp/127.0.0.1/"$VNC_PORT") 2>/dev/null; then
        echo "端口已就绪，返回 App 即可连接 VNC。"
        exit 0
    fi
    sleep 0.5
done
echo "VNC 端口未就绪，日志："
tail -30 "$LOG"
exit 1
