#!/data/data/com.termux/files/usr/bin/bash

echo "=== Windows 7 QEMU 启动器 ==="

WIN7_DIR="$HOME/win7-qemu"
TAR_FILE="$HOME/win7P.tar.gz"
DOWNLOAD_URL="https://od.ixcmstudio.cn/p/repository/main/windows/win7P.tar.gz"

# 检查容器目录是否已存在
if [ -d "$WIN7_DIR/bullseye-qemu" ] && [ -f "$WIN7_DIR/start-win7.sh" ]; then
    echo "检测到已解压的容器，VNC端口是0。直接启动..."
    cd "$WIN7_DIR"
    bash start-win7.sh
    exit 0
fi

# 检查 tar.gz 是否已存在
if [ ! -f "$TAR_FILE" ]; then
    echo "正在下载 Windows 7 镜像..."
    echo "下载地址：$DOWNLOAD_URL"
    curl -sSL -o "$TAR_FILE" "$DOWNLOAD_URL"
    if [ $? -ne 0 ]; then
        echo "下载失败，请检查网络连接"
        exit 1
    fi
    echo "下载完成"
else
    echo "检测到已有压缩包，跳过下载"
fi

# 确保有 tar 工具
if ! command -v tar &> /dev/null; then
    echo "未检测到 tar 工具，正在安装..."
    pkg install tar -y
fi

# 创建目录并解压
mkdir -p "$WIN7_DIR"
echo "正在解压...TIPS: 正常执行后，VNC端口是0。请使用VNC工具连接。"
tar -xzf "$TAR_FILE" -C "$WIN7_DIR"
if [ $? -ne 0 ]; then
    echo "解压失败"
    exit 1
fi

# 应用补丁（替换修改后的脚本文件）
if [ -f "$HOME/win7_patch.zip" ]; then
    echo "正在应用补丁..."
    if ! command -v unzip &> /dev/null; then
        echo "未检测到 unzip 工具，正在安装..."
        pkg install unzip -y
    fi
    cd "$WIN7_DIR"
    unzip -o "$HOME/win7_patch.zip"
    if [ $? -ne 0 ]; then
        echo "警告: 补丁应用失败，将使用原始文件"
    else
        echo "补丁应用完成"
    fi
else
    echo "未找到补丁文件，使用原始文件"
fi

# 创建容器内 /root/share 文件夹
echo "正在创建容器内共享目录..."
mkdir -p "$WIN7_DIR/bullseye-qemu/root/share"

# 授予 win7.sh 文件 755 权限
echo "正在设置权限..."
chmod 755 "$WIN7_DIR/bullseye-qemu/usr/local/bin/win7"

# 检查容器目录
if [ -d "$WIN7_DIR/bullseye-qemu" ] && [ -f "$WIN7_DIR/start-win7.sh" ]; then
    echo "解压完成，VNC端口是0。正在启动..."
    cd "$WIN7_DIR"
    bash start-win7.sh
else
    echo "解压后未找到容器目录"
    echo "解压目录内容："
    ls -la "$WIN7_DIR"
fi
