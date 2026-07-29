#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")" || exit 1

unset LD_PRELOAD

# Ensure resolv.conf exists for DNS resolution
# Ubuntu's resolv.conf may be a broken symlink, remove and recreate
mkdir -p rootfs/etc 2>/dev/null
if [ ! -f rootfs/etc/resolv.conf ] || [ ! -s rootfs/etc/resolv.conf ]; then
    rm -f rootfs/etc/resolv.conf 2>/dev/null || true
    printf "nameserver 8.8.8.8\nnameserver 8.8.4.4\n" > rootfs/etc/resolv.conf 2>/dev/null || true
fi

exec proot --link2symlink \
    -0 \
    -r rootfs \
    -b /dev \
    -b /proc \
    -b /sys \
    -b /data/data/com.termux/files/home:/root/shared \
    -w /root \
    /usr/bin/env -i \
    HOME=/root \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    TERM="${TERM:-xterm-256color}" \
    LANG=C.UTF-8 \
    /bin/bash --login "$@"
