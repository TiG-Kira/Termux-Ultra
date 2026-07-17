#!/data/data/com.termux/files/usr/bin/bash
set -e

CONTAINER_DIR="$HOME/debian-container"

if [ -d "$CONTAINER_DIR" ] && [ -f "$CONTAINER_DIR/rootfs/bin/bash" ]; then
    echo "Linux container already exists, skipping installation."
    exit 0
fi

echo "============================================"
echo "  Linux Container Installation"
echo "  Downloading from GitHub..."
echo "============================================"
echo ""

echo "Installing required packages in Termux..."
pkg update -y 2>/dev/null || true
pkg install -y proot wget tar

echo "Creating container directory..."
mkdir -p "$CONTAINER_DIR"
cd "$CONTAINER_DIR"

echo "Detecting architecture..."
ARCH=$(dpkg --print-architecture 2>/dev/null || uname -m)

case $ARCH in
    aarch64|arm64) DEB_ARCH="arm64" ;;
    arm|armv7l|armhf) DEB_ARCH="armhf" ;;
    x86_64|amd64) DEB_ARCH="amd64" ;;
    i686|i386) DEB_ARCH="i386" ;;
    *) DEB_ARCH="arm64" ;;
esac

echo "Architecture: $ARCH -> $DEB_ARCH"

ROOTFS_URL="https://github.com/termux/proot-distro/releases/download/v4.18.0/debian-${DEB_ARCH}-pd-v4.18.0.tar.xz"

echo "Downloading Debian rootfs from GitHub..."
echo "URL: $ROOTFS_URL"
echo "This may take a while depending on your network..."
echo ""

wget -O rootfs.tar.xz "$ROOTFS_URL" || {
    echo "Download failed, trying mirror..."
    wget -O rootfs.tar.xz "https://mirrors.tuna.tsinghua.edu.cn/osdn/storage/g/t/te/termux-proot-distro/debian-${DEB_ARCH}-pd-v4.18.0.tar.xz"
}

echo "Extracting rootfs..."
mkdir -p rootfs
proot --link2symlink tar -xJf rootfs.tar.xz -C rootfs --exclude='dev' 2>/dev/null || tar -xJf rootfs.tar.xz -C rootfs --exclude='dev'

rm -f rootfs.tar.xz

echo "Configuring container..."
mkdir -p rootfs/tmp
mkdir -p rootfs/dev
mkdir -p rootfs/sys
mkdir -p rootfs/proc

echo "nameserver 8.8.8.8" > rootfs/etc/resolv.conf
echo "nameserver 8.8.4.4" >> rootfs/etc/resolv.conf

cat > "$CONTAINER_DIR/run.sh" << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")"

unset LD_PRELOAD
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
    TERM="$TERM" \
    LANG=C.UTF-8 \
    /bin/bash --login "$@"
EOF

chmod +x "$CONTAINER_DIR/run.sh"

echo "Updating container packages..."
"$CONTAINER_DIR/run.sh" apt update -y
"$CONTAINER_DIR/run.sh" apt install -y curl wget ca-certificates genisoimage qemu-system-x86 qemu-utils

echo ""
echo "============================================"
echo "  Container installation complete!"
echo "  Path: $CONTAINER_DIR"
echo "  Packages: genisoimage, qemu, curl, wget"
echo "============================================"
