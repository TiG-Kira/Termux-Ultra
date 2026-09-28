#!/usr/bin/env bash
# 在 CI（ubuntu-26.04）从源码构建 facebook/redex 并安装到 ~/redex-install。
# best-effort：任何环节失败只发 ::warning 并 exit 0，且不设置 REDEX_BIN →
# optimize_release_apk.sh 检测到 REDEX_BIN 为空会自动跳过 ReDex，回退到安全流水线。
# 依赖 actions/cache 把 ~/redex-install 跨运行恢复（命中则免构建）；本脚本自身也做存在性检查。
set -uo pipefail

PREFIX="$HOME/redex-install"
BIN="$PREFIX/bin/redex"
REDEX_TAG="${REDEX_TAG:-v2026.04.30}"

if [ -x "$BIN" ]; then
  echo ">> ReDex 已存在（$BIN），跳过构建"
  echo "$PREFIX/bin" >> "$GITHUB_PATH"
  echo "REDEX_BIN=$BIN" >> "$GITHUB_ENV"
  exit 0
fi

echo ">> 构建 ReDex @ $REDEX_TAG（best-effort）"

# 构建依赖（与 ReDex 官方文档一致，另加 flex/bison 以防解析器生成失败）
sudo apt-get update -qq || echo "::warning::apt update 失败，继续尝试"
if ! sudo apt-get install -y autoconf automake g++ libboost-all-dev libevent-dev \
     libgflags-dev libjsoncpp-dev libtool libjemalloc-dev libsqlite3-dev make \
     zlib1g-dev flex bison git; then
  echo "::warning::ReDex 依赖安装失败，跳过 ReDex"
  exit 0
fi

SRC="$(mktemp -d)"
if ! git clone --depth 1 --branch "$REDEX_TAG" https://github.com/facebook/redex.git "$SRC"; then
  echo "::warning::ReDex 源码克隆失败，跳过 ReDex"
  rm -rf "$SRC"
  exit 0
fi

cd "$SRC"
if ! autoreconf -ivf; then
  echo "::warning::autoreconf 失败，跳过 ReDex"
  rm -rf "$SRC"
  exit 0
fi
if ! ./configure --prefix="$PREFIX" --disable-tests --disable-kotlin-tests; then
  echo "::warning::configure 失败，跳过 ReDex"
  rm -rf "$SRC"
  exit 0
fi
if ! make -j"$(nproc)"; then
  echo "::warning::make 失败，跳过 ReDex"
  rm -rf "$SRC"
  exit 0
fi
if ! make install; then
  echo "::warning::make install 失败，跳过 ReDex"
  rm -rf "$SRC"
  exit 0
fi
rm -rf "$SRC"

if [ -x "$BIN" ]; then
  echo "$PREFIX/bin" >> "$GITHUB_PATH"
  echo "REDEX_BIN=$BIN" >> "$GITHUB_ENV"
  echo ">> ReDex 构建完成: $BIN"
else
  echo "::warning::ReDex 构建后未找到二进制，跳过 ReDex"
  exit 0
fi
