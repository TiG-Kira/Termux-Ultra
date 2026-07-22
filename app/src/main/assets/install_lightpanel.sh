#!/bin/bash
set -e

# 朱雀面板 (LightPanel) 独立安装脚本
# 在 Linux 容器内以 root 权限运行

export DEBIAN_FRONTEND=noninteractive

echo "=========================================="
echo "  朱雀面板 (LightPanel) 安装程序"
echo "=========================================="
echo ""

# 确保依赖工具可用
if ! command -v curl >/dev/null 2>&1; then
    echo "[0/5] 正在安装依赖 (curl, wget, ca-certificates)..."
    apt update -y >/dev/null 2>&1
    apt install -y --no-install-recommends curl wget ca-certificates >/dev/null 2>&1
fi

INSTALL_DIR="/root/lightpanel"
mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"

GITHUB_API="https://api.github.com/repos/MyUI0/lightpanel/releases/latest"
GITHUB_API_PROXY="https://gh.llkk.cc/https://api.github.com/repos/MyUI0/lightpanel/releases/latest"

# 步骤1: 获取最新版本号
echo "[1/5] 正在获取最新版本号..."
VERSION=""
for api_url in "$GITHUB_API" "$GITHUB_API_PROXY"; do
    VERSION=$(curl -fsSL "$api_url" 2>/dev/null | grep -o '"tag_name"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')
    if [ -n "$VERSION" ]; then
        echo "    最新版本: $VERSION"
        break
    fi
done

if [ -z "$VERSION" ]; then
    echo "错误: 无法获取最新版本号，请检查网络连接后重试"
    exit 1
fi

# 步骤2: 下载 ARM64 安装包
echo "[2/5] 正在下载 $VERSION ARM64 安装包..."
TARBALL="lightpanel-${VERSION}-linux-arm64.tar.gz"
DOWNLOAD_URL="https://github.com/MyUI0/lightpanel/releases/download/${VERSION}/${TARBALL}"
DOWNLOAD_URL_PROXY="https://gh.llkk.cc/${DOWNLOAD_URL}"

DOWNLOAD_SUCCESS=false
for url in "$DOWNLOAD_URL" "$DOWNLOAD_URL_PROXY"; do
    echo "    尝试下载: $url"
    if curl -fsSL -o "$TARBALL" "$url"; then
        DOWNLOAD_SUCCESS=true
        echo "    下载完成"
        break
    fi
done

if [ "$DOWNLOAD_SUCCESS" = false ]; then
    echo "错误: 下载失败，请检查网络连接后重试"
    exit 1
fi

# 步骤3: 解压
echo "[3/5] 正在解压..."
tar -xzf "$TARBALL"
echo "    解压完成"

# 步骤4: 赋予执行权限
echo "[4/5] 正在配置执行权限..."
chmod +x lightpanel
echo "    权限配置完成"

# 步骤5: 启动朱雀面板
echo "[5/5] 正在启动朱雀面板..."
echo "=========================================="
echo ""
./lightpanel
