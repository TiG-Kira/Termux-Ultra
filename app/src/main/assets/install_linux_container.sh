#!/data/data/com.termux/files/usr/bin/bash

CONTAINER_DIR="$HOME/debian-container"

if [ -d "$CONTAINER_DIR" ] && [ -f "$CONTAINER_DIR/rootfs/bin/bash" ] && [ -f "$CONTAINER_DIR/run.sh" ]; then
    echo "Linux container already exists, skipping installation."
    exit 0
fi

echo "============================================"
echo "  Linux Container Installation"
echo "  Downloading Ubuntu 24.04 (Noble) rootfs"
echo "  from LinuxContainers.org"
echo "============================================"
echo ""

echo "Installing required packages in Termux..."
pkg update -y 2>/dev/null || true
pkg install -y proot wget tar xz-utils 2>/dev/null || true

echo "Creating container directory..."
mkdir -p "$CONTAINER_DIR"
cd "$CONTAINER_DIR" || { echo "Error: Cannot cd to $CONTAINER_DIR"; exit 1; }

echo "Detecting architecture..."
ARCH=$(dpkg --print-architecture 2>/dev/null || uname -m)

case "$ARCH" in
    aarch64|arm64)
        LXC_ARCH="arm64"
        ;;
    arm|armv7l|armhf)
        LXC_ARCH="armhf"
        ;;
    x86_64|amd64)
        LXC_ARCH="amd64"
        ;;
    i686|i386|x86)
        LXC_ARCH="i386"
        ;;
    *)
        LXC_ARCH="arm64"
        ;;
esac

echo "Architecture: $ARCH -> $LXC_ARCH"

# Try Ubuntu Noble (24.04 LTS) first, fallback to Jammy (22.04 LTS)
for DISTRO in "ubuntu/noble" "ubuntu/jammy" "debian/bookworm"; do
    BASE_URL="https://images.linuxcontainers.org/images/${DISTRO}/${LXC_ARCH}/default"

    echo ""
    echo "Trying: ${DISTRO} (${LXC_ARCH})"
    echo "Fetching latest image directory..."

    LATEST_DIR=$(wget -qO- "$BASE_URL/" 2>/dev/null | grep -oE '[0-9]{8}_[0-9]{2}:[0-9]{2}' | sort -r | head -1)

    if [ -z "$LATEST_DIR" ]; then
        echo "  Cannot find latest image, trying next source..."
        continue
    fi

    ROOTFS_URL="${BASE_URL}/${LATEST_DIR}/rootfs.tar.xz"
    echo "  Latest image: $LATEST_DIR"
    echo "  URL: $ROOTFS_URL"

    echo "  Downloading rootfs (this may take a while)..."
    if wget -O rootfs.tar.xz "$ROOTFS_URL" 2>&1; then
        # Verify it's a valid xz file
        if xz -t rootfs.tar.xz 2>/dev/null; then
            echo "  Download verified, breaking out of loop."
            DISTRO_FOUND=1
            DISTRO_NAME=$(echo "$DISTRO" | cut -d'/' -f2)
            break
        else
            echo "  Downloaded file is corrupted, trying next source..."
            rm -f rootfs.tar.xz
            continue
        fi
    else
        echo "  Download failed, trying next source..."
        rm -f rootfs.tar.xz
        continue
    fi
done

if [ -z "$DISTRO_FOUND" ]; then
    echo ""
    echo "Error: All download sources failed."
    echo "Please check your network connection and try again."
    exit 1
fi

echo ""
echo "Extracting rootfs..."
rm -rf rootfs
mkdir -p rootfs

if proot --link2symlink tar -xJf rootfs.tar.xz -C rootfs 2>/dev/null; then
    echo "  Extracted with proot."
elif tar -xJf rootfs.tar.xz -C rootfs 2>/dev/null; then
    echo "  Extracted with tar."
else
    echo "  Warning: Extraction had issues, trying without proot..."
    tar -xJf rootfs.tar.xz -C rootfs || {
        echo "Error: Cannot extract rootfs."
        exit 1
    }
fi

rm -f rootfs.tar.xz

# Verify rootfs
if [ ! -f "rootfs/bin/bash" ]; then
    echo "Error: rootfs/bin/bash not found after extraction."
    echo "Contents of rootfs:"
    ls -la rootfs/ 2>/dev/null || echo "(empty or inaccessible)"
    exit 1
fi

echo "Rootfs extracted successfully."

echo "Configuring container..."
mkdir -p rootfs/tmp rootfs/dev rootfs/sys rootfs/proc rootfs/root/shared 2>/dev/null || true
mkdir -p rootfs/etc 2>/dev/null || true

# Copy resolv.conf from pre-extracted assets
# Note: Ubuntu's /etc/resolv.conf is often a symlink to /run/systemd/resolve/...
# Must remove it first, otherwise writing follows the broken symlink and fails.
echo "Setting up resolv.conf..."
rm -f rootfs/etc/resolv.conf 2>/dev/null || true
if [ -f "$HOME/resolv.conf" ]; then
    cp "$HOME/resolv.conf" rootfs/etc/resolv.conf 2>/dev/null && echo "  resolv.conf copied from assets." || \
    { printf "nameserver 8.8.8.8\nnameserver 8.8.4.4\n" > rootfs/etc/resolv.conf 2>/dev/null || true; echo "  resolv.conf created inline (fallback)."; }
else
    printf "nameserver 8.8.8.8\nnameserver 8.8.4.4\n" > rootfs/etc/resolv.conf 2>/dev/null || true
    echo "  resolv.conf created inline (fallback)."
fi

# Configure apt sources based on distro and architecture
echo "Configuring apt sources..."
mkdir -p rootfs/etc/apt/sources.list.d 2>/dev/null || true

# Remove any existing source files to avoid conflicts
rm -f rootfs/etc/apt/sources.list 2>/dev/null || true
rm -f rootfs/etc/apt/sources.list.d/*.sources 2>/dev/null || true
rm -f rootfs/etc/apt/sources.list.d/*.list 2>/dev/null || true

if [ "$DISTRO_NAME" = "noble" ] || [ "$DISTRO_NAME" = "jammy" ]; then
    # Ubuntu: amd64/i386 use archive.ubuntu.com, arm* use ports.ubuntu.com
    if [ "$LXC_ARCH" = "amd64" ] || [ "$LXC_ARCH" = "i386" ]; then
        APT_MIRROR="http://archive.ubuntu.com/ubuntu"
    else
        APT_MIRROR="http://ports.ubuntu.com/ubuntu-ports"
    fi

    cat > rootfs/etc/apt/sources.list << SRCEOF
deb ${APT_MIRROR} ${DISTRO_NAME} main restricted universe multiverse
deb ${APT_MIRROR} ${DISTRO_NAME}-updates main restricted universe multiverse
deb ${APT_MIRROR} ${DISTRO_NAME}-security main restricted universe multiverse
SRCEOF
    echo "  Ubuntu sources configured: $APT_MIRROR ($DISTRO_NAME)"
elif [ "$DISTRO_NAME" = "bookworm" ]; then
    # Debian
    cat > rootfs/etc/apt/sources.list << SRCEOF
deb http://deb.debian.org/debian ${DISTRO_NAME} main contrib non-free non-free-firmware
deb http://deb.debian.org/debian ${DISTRO_NAME}-updates main contrib non-free non-free-firmware
deb http://deb.debian.org/debian-security ${DISTRO_NAME}-security main contrib non-free non-free-firmware
SRCEOF
    echo "  Debian sources configured: deb.debian.org ($DISTRO_NAME)"
fi

# Copy run.sh from pre-extracted assets
echo "Setting up run.sh..."
if [ -f "$HOME/container_run.sh" ]; then
    cp "$HOME/container_run.sh" "$CONTAINER_DIR/run.sh" 2>/dev/null || true
    echo "  run.sh copied from assets."
else
    echo "  ERROR: container_run.sh not found in \$HOME!"
    echo "  Falling back to inline generation..."
    cat > "$CONTAINER_DIR/run.sh" << 'FALLBACK'
#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")" || exit 1
unset LD_PRELOAD
mkdir -p rootfs/etc 2>/dev/null
if [ ! -s rootfs/etc/resolv.conf ]; then
    printf "nameserver 8.8.8.8\nnameserver 8.8.4.4\n" > rootfs/etc/resolv.conf 2>/dev/null || true
fi
exec proot --link2symlink -0 -r rootfs -b /dev -b /proc -b /sys -b /data/data/com.termux/files/home:/root/shared -w /root /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM="${TERM:-xterm-256color}" LANG=C.UTF-8 /bin/bash --login "$@"
FALLBACK
fi

chmod +x "$CONTAINER_DIR/run.sh"

# Verify run.sh was created
if [ ! -f "$CONTAINER_DIR/run.sh" ]; then
    echo "Error: Failed to create run.sh!"
    exit 1
fi

echo ""
echo "============================================"
echo "  Container installation complete!"
echo "  Path: $CONTAINER_DIR"
echo "  Distro: $DISTRO_NAME ($LXC_ARCH)"
echo "============================================"
echo ""
echo "To start the container:"
echo "  $CONTAINER_DIR/run.sh"
echo ""
echo "To run a command inside the container:"
echo "  $CONTAINER_DIR/run.sh <command>"
