#!/data/data/com.termux/files/usr/bin/bash
# QEMU on Termux Setup (Debian)

set -e

echo "=== QEMU on Termux Setup (Debian) ==="

echo ""
echo "[1/5] Installing Termux repository packages..."
pkg install -y unstable-repo x11-repo 2>/dev/null || {
    pkg update -y
    pkg install -y unstable-repo x11-repo
}

echo ""
echo "[2/5] Updating package list..."
pkg update -y

echo ""
echo "[3/5] Installing QEMU and dependencies..."
pkg install -y qemu-system-aarch64 qemu-utils python curl wget openssh termux-api 2>/dev/null || {
    echo "  Trying x86_64 version..."
    pkg install -y qemu-system-x86-64 qemu-utils python curl wget openssh termux-api
}

if command -v qemu-system-aarch64 &>/dev/null; then
    QEMU_BIN="qemu-system-aarch64"
    QEMU_ARCH="aarch64"
else
    QEMU_BIN="qemu-system-x86_64"
    QEMU_ARCH="x86_64"
fi
echo "  Using $QEMU_BIN"

echo ""
echo "[4/5] Setting up VM environment..."
VM_DIR="$HOME/qemu-vm"
IMG="$VM_DIR/debian-12-arm64.qcow2"
DEBIAN_URL="https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-genericcloud-arm64.qcow2"
MIRROR_URL="https://mirrors.tuna.tsinghua.edu.cn/debian-cloud/images/cloud/bookworm/latest/debian-12-genericcloud-arm64.qcow2"

if [[ -f "$IMG" ]]; then
    echo ""
    echo "  WARNING: Debian QEMU container already exists!"
    read -p "  Do you want to reinstall (delete existing and start fresh)? [y/N] " -n1 -r
    echo ""
    if [[ "$REPLY" =~ ^[Yy]$ ]]; then
        echo "  Removing existing container..."
        if [[ -f "$IMG" ]]; then
            read -p "  Delete downloaded image file ($(du -h "$IMG" | awk '{print $1}'))? [y/N] " -n1 -r
            echo ""
            if [[ "$REPLY" =~ ^[Yy]$ ]]; then
                rm -rf "$VM_DIR"
                mkdir -p "$VM_DIR"
                DOWNLOAD_NEEDED=1
            else
                rm -rf "$VM_DIR/seed.iso" "$VM_DIR/edk2-vars.fd" 2>/dev/null || true
                DOWNLOAD_NEEDED=0
            fi
        else
            rm -rf "$VM_DIR"
            mkdir -p "$VM_DIR"
            DOWNLOAD_NEEDED=1
        fi
    else
        echo "  Skipping download, using existing container..."
        cd "$VM_DIR" || exit 1
        create_boot_script
        exit 0
    fi
else
    DOWNLOAD_NEEDED=1
    mkdir -p "$VM_DIR"
    cd "$VM_DIR" || exit 1
fi

if [[ "$DOWNLOAD_NEEDED" == "1" ]]; then
    echo "  Downloading Debian 12 arm64 cloud image (~1.2 GB)..."
    echo "  ========================================================"
    curl -L --progress-bar -o "$IMG" "$DEBIAN_URL" || {
        echo ""
        echo "  Failed to download, trying mirror..."
        curl -L --progress-bar -o "$IMG" "$MIRROR_URL" || {
            echo ""
            echo "  ERROR: Failed to download Debian image!"
            exit 1
        }
    }
    echo ""
fi

echo "  Resizing to 20 GB..."
qemu-img resize "$IMG" 20G

echo "  Creating cloud-init seed (password login only)..."

cat > "$HOME/user-data" <<EOF
#cloud-config
hostname: docker-phone
users:
  - name: debian
    lock_passwd: false
    sudo: ALL=(ALL) NOPASSWD:ALL
    shell: /bin/bash
chpasswd:
  list: |
    root:dockerphone
    debian:dockerphone
  expire: False
ssh_pwauth: true
disable_root: false
runcmd:
  - sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
  - sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config
  - systemctl restart sshd
package_update: true
packages:
  - qemu-guest-agent
  - ca-certificates
  - curl
growpart:
  mode: auto
  devices: ['/']
EOF

cat > "$HOME/meta-data" <<EOF
instance-id: docker-phone-001
local-hostname: docker-phone
EOF

GENISOIMAGE_RPM="$HOME/genisoimage.rpm"
GENISOIMAGE_BIN="$PREFIX/bin/genisoimage"

if ! command -v genisoimage &>/dev/null; then
    if [[ -f "$GENISOIMAGE_RPM" ]]; then
        echo "  Installing genisoimage from rpm package..."
        if ! command -v rpm &>/dev/null; then
            echo "  Installing rpm tool..."
            pkg install -y rpm 2>/dev/null || true
        fi
        if command -v rpm &>/dev/null; then
            rpm -i --nodeps --force "$GENISOIMAGE_RPM" 2>/dev/null || {
                echo "  rpm install failed, extracting manually..."
                cd "$PREFIX"
                rpm2cpio "$GENISOIMAGE_RPM" 2>/dev/null | cpio -idmv 2>/dev/null || {
                    echo "  Manual extraction failed, trying cpio..."
                    mkdir -p "$PREFIX/tmp/rpm-extract"
                    cd "$PREFIX/tmp/rpm-extract"
                    rpm2cpio "$GENISOIMAGE_RPM" | cpio -idmv
                    find . -name "genisoimage" -exec cp {} "$GENISOIMAGE_BIN" \;
                    cd "$PREFIX"
                    rm -rf "$PREFIX/tmp/rpm-extract"
                }
                find "$PREFIX" -name "genisoimage" -not -path "*/home/*" | head -1 | while read -r f; do
                    cp "$f" "$GENISOIMAGE_BIN"
                done
                chmod +x "$GENISOIMAGE_BIN"
            }
        else
            echo "  rpm not available, trying manual extraction..."
            mkdir -p "$PREFIX/tmp/rpm-extract"
            cd "$PREFIX/tmp/rpm-extract"
            rpm2cpio "$GENISOIMAGE_RPM" | cpio -idmv 2>/dev/null
            find . -name "genisoimage" -exec cp {} "$GENISOIMAGE_BIN" \;
            cd "$PREFIX"
            rm -rf "$PREFIX/tmp/rpm-extract"
            chmod +x "$GENISOIMAGE_BIN"
        fi
    else
        echo "  genisoimage.rpm not found, trying package install..."
        pkg install -y genisoimage 2>/dev/null || true
    fi
fi

if command -v genisoimage &>/dev/null; then
    GENISOIMAGE_BIN="genisoimage"
fi

if [[ -f "$GENISOIMAGE_BIN" ]]; then
    chmod +x "$GENISOIMAGE_BIN"
fi

if command -v "$GENISOIMAGE_BIN" &>/dev/null || [[ -x "$GENISOIMAGE_BIN" ]]; then
    "$GENISOIMAGE_BIN" -output "$VM_DIR/seed.iso" -volid "CIDATA" -joliet -rock "$HOME/user-data" "$HOME/meta-data" || {
        echo "  genisoimage execution failed, trying Python fallback..."
        python -c "
import sys, os, io

try:
    import pycdlib
except ImportError:
    os.system('pip install pycdlib -q')
    import pycdlib

iso = pycdlib.PyCdlib()
iso.new(vol_ident='CIDATA', joliet=True, rock_ridge='1.09')

with open('$HOME/user-data', 'rb') as f:
    user_data = f.read()
with open('$HOME/meta-data', 'rb') as f:
    meta_data = f.read()

fp = io.BytesIO(user_data)
iso.add_fp(fp, len(user_data), '/USER-DAT;1', rr_name='user-data', joliet_path='/user-data')

fp = io.BytesIO(meta_data)
iso.add_fp(fp, len(meta_data), '/META-DAT;1', rr_name='meta-data', joliet_path='/meta-data')

iso.write('$VM_DIR/seed.iso')
iso.close()
print('seed.iso created')
" || {
            echo "  ERROR: Failed to create seed.iso!"
            echo "  Please install genisoimage manually and rerun."
            exit 1
        }
    }
else
    echo "  genisoimage not available, using Python fallback..."
    python -c "
import sys, os, io

try:
    import pycdlib
except ImportError:
    os.system('pip install pycdlib -q')
    import pycdlib

iso = pycdlib.PyCdlib()
iso.new(vol_ident='CIDATA', joliet=True, rock_ridge='1.09')

with open('$HOME/user-data', 'rb') as f:
    user_data = f.read()
with open('$HOME/meta-data', 'rb') as f:
    meta_data = f.read()

fp = io.BytesIO(user_data)
iso.add_fp(fp, len(user_data), '/USER-DAT;1', rr_name='user-data', joliet_path='/user-data')

fp = io.BytesIO(meta_data)
iso.add_fp(fp, len(meta_data), '/META-DAT;1', rr_name='meta-data', joliet_path='/meta-data')

iso.write('$VM_DIR/seed.iso')
iso.close()
print('seed.iso created')
" || {
        echo "  ERROR: Failed to create seed.iso!"
        exit 1
    }
fi

rm -f "$HOME/user-data" "$HOME/meta-data"
echo "  Debian VM ready with credentials"

create_boot_script() {
echo ""
echo "[5/5] Creating boot script..."

if [[ "$QEMU_ARCH" == "aarch64" ]]; then
    CODE_FD="$PREFIX/share/qemu/edk2-aarch64-code.fd"
    VARS_FD="$VM_DIR/edk2-vars.fd"
    
    if [[ ! -f "$VARS_FD" ]]; then
        if [[ -f "$PREFIX/share/qemu/edk2-aarch64-vars.fd" ]]; then
            cp "$PREFIX/share/qemu/edk2-aarch64-vars.fd" "$VARS_FD"
        elif [[ -f "$PREFIX/share/qemu/edk2-aarch64-code.fd" ]]; then
            echo "  Creating UEFI vars file..."
            dd if=/dev/zero of="$VARS_FD" bs=1M count=64 2>/dev/null || truncate -s 64M "$VARS_FD"
        else
            echo "  WARNING: UEFI firmware not found, QEMU may not boot"
        fi
    fi
    
    cat > "$HOME/boot-qemu.sh" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
cd "$VM_DIR"
termux-wake-lock 2>/dev/null

$QEMU_BIN \\
    -M virt -m 2G -cpu cortex-a72 -smp 2 \\
    -drive file="$CODE_FD",format=raw,if=pflash,readonly=on \\
    -drive file="$VARS_FD",format=raw,if=pflash \\
    -drive file="$IMG",format=qcow2 \\
    -drive file="$VM_DIR/seed.iso",media=cdrom,format=raw \\
    -nic user,model=virtio-net-pci,hostfwd=tcp::2222-:22 \\
    -serial mon:stdio \\
    -nographic
EOF
else
    cat > "$HOME/boot-qemu.sh" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
cd "$VM_DIR"
termux-wake-lock 2>/dev/null

$QEMU_BIN \\
    -M pc -m 2G -smp 2 \\
    -drive file="$IMG",format=qcow2 \\
    -nic user,hostfwd=tcp::2222-:22 \\
    -nographic
EOF
fi

chmod +x "$HOME/boot-qemu.sh"

echo ""
echo "========================================="
echo "QEMU setup complete!"
echo "========================================="
echo ""
echo "VM directory: $VM_DIR"
echo "Boot script: $HOME/boot-qemu.sh"
echo ""
echo "To start VM:"
echo "  bash $HOME/boot-qemu.sh"
echo ""
echo "========================================="
echo "DEBIAN LOGIN CREDENTIALS"
echo "========================================="
echo "  Username: debian"
echo "  Password: dockerphone"
echo ""
echo "  OR"
echo ""
echo "  Username: root"
echo "  Password: dockerphone"
echo "========================================="
echo ""
echo "SSH access (after VM boots):"
echo "  ssh debian@localhost -p 2222"
echo ""
read -p "Press any key to continue..." -n1 -s
echo ""
}

create_boot_script
