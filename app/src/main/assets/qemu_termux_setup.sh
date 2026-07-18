#!/data/data/com.termux/files/usr/bin/bash
# QEMU on Termux Setup (Debian)
# Uses password login instead of SSH key authentication

set -e

echo "=== QEMU on Termux Setup (Debian) ==="

# Step 1: Install Termux repository packages
echo ""
echo "[1/5] Installing Termux repository packages..."
pkg install -y unstable-repo x11-repo 2>/dev/null || {
    pkg update -y
    pkg install -y unstable-repo x11-repo
}

# Step 2: Update package list
echo ""
echo "[2/5] Updating package list..."
pkg update -y

# Step 3: Install QEMU and tools
echo ""
echo "[3/5] Installing QEMU and dependencies..."
pkg install -y qemu-system-aarch64 qemu-utils python curl wget openssh termux-api 2>/dev/null || {
    echo "  Trying x86_64 version..."
    pkg install -y qemu-system-x86-64 qemu-utils python curl wget openssh termux-api
}

# Get QEMU binary name
if command -v qemu-system-aarch64 &>/dev/null; then
    QEMU_BIN="qemu-system-aarch64"
    QEMU_ARCH="aarch64"
else
    QEMU_BIN="qemu-system-x86_64"
    QEMU_ARCH="x86_64"
fi
echo "  Using $QEMU_BIN"

# Step 4: Setup VM directory and download image
echo ""
echo "[4/5] Setting up VM environment..."
VM_DIR="$HOME/qemu-vm"
mkdir -p "$VM_DIR"
cd "$VM_DIR" || exit 1

IMG="debian-12-arm64.qcow2"
DEBIAN_URL="https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-genericcloud-arm64.qcow2"

if [[ ! -f "$IMG" ]]; then
    echo "  Downloading Debian 12 arm64 cloud image (~1.2 GB)..."
    wget -q --show-progress -O "$IMG" "$DEBIAN_URL" || {
        echo "  Failed to download, trying mirror..."
        wget -q --show-progress -O "$IMG" "https://mirrors.tuna.tsinghua.edu.cn/debian-cloud/images/cloud/bookworm/latest/debian-12-genericcloud-arm64.qcow2" || {
            echo "  ERROR: Failed to download Debian image!"
            exit 1
        }
    }
    
    echo "  Resizing to 20 GB..."
    qemu-img resize "$IMG" 20G
    
    echo "  Creating cloud-init seed (password login only)..."
    python -c "
import sys, os
try:
    import pycdlib
except ImportError:
    os.system('pip install pycdlib -q')
    import pycdlib

iso = pycdlib.PyCdlib()
iso.new(vol_ident=b'CIDATA', joliet=True, rock_ridge=True)

user_data = b'''#cloud-config
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
'''
meta_data = b'''instance-id: docker-phone-001
local-hostname: docker-phone
'''

import io
for name, data in [('user-data', user_data), ('meta-data', meta_data)]:
    fp = io.BytesIO(data)
    iso.add_fp(fp, len(data), '/' + name.upper() + ';1', joliet_path='/' + name)

iso.write('seed.iso')
iso.close()
print('seed.iso created')
"
    echo "  Debian VM ready with credentials"
else
    echo "  Debian image already exists: $IMG"
fi

# Step 5: Prepare boot script
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
    -drive file="seed.iso",media=cdrom,format=raw \\
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
