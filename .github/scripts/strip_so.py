#!/usr/bin/env python3
"""
APK 后处理：对 .so 做 strip --strip-debug，其余条目按 Android APK 规范选择压缩方式重打包。

⚠️  关键设计：哪些文件**必须**用 ZIP_STORED（未压缩）写回
   ──────────────────────────────────────────────────────
   ART 运行时直接 mmap APK 内的 DEX / 资源表 / 清单文件，
   这些条目一旦被 DEFLATE 压缩，Android 在加载时会：
     - ART 尝试直接从 APK mmap DEX → 读到的是乱码 → SIGSEGV / VerifyError
     - ResourceManager 按固定 offset 读取 resources.arsc → 偏移错位 → 资源加载失败
     - PackageManager 读取 AndroidManifest.xml → 无法解析
   zipalign 只能对齐 STORED 条目的物理偏移，对 DEFLATED 条目没有意义。

用法: strip_so.py <in.apk> <out.apk> <strip-tool>
"""
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile


# ── 1. 必须以 ZIP_STORED（未压缩）写入的条目 ────────────────────────
# 这些是 Android runtime / ART / ResourceManager 直接按 offset 访问的
MUST_STORED_PATTERNS = [
    lambda arc: arc.startswith("classes") and arc.endswith(".dex"),  # classes.dex / classes2.dex ...
    lambda arc: arc == "resources.arsc",                              # 资源表
    lambda arc: arc == "AndroidManifest.xml",                         # 清单
]

# ── 2. 本身已是压缩格式、再套 DEFLATE 零收益的条目 ───────────────
# 用 STORED 避免无意义的 CPU 开销，减小重打包时间
ALREADY_COMPRESSED_EXTS = {
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp",
    ".7z", ".zip", ".rar", ".apkx",
    ".mp3", ".mp4", ".m4a", ".aac", ".ogg", ".flac", ".wav",
    ".ttf", ".otf", ".woff", ".woff2", ".eot",
    ".wasm", ".ico", ".ktx", ".ktx2", ".astc",
}

# ── 3. 跳过的条目 ──────────────────────────────────────────────
# 旧签名残留，AndResGuard 会重新签名生成全新的 META-INF，
# strip_so.py 里带着这些旧文件容易引起签名校验逻辑混乱
SKIP_PATTERNS = [
    lambda arc: arc.startswith("META-INF/"),
]


def choose_compression(arcname: str) -> int:
    """根据条目路径决定用 ZIP_STORED 还是 ZIP_DEFLATED 写回。"""
    # 先检查必须 STORED 的条目
    for fn in MUST_STORED_PATTERNS:
        if fn(arcname):
            return zipfile.ZIP_STORED

    # .so — 永远 STORED（AGP useLegacyPackaging=true 的语义）
    if arcname.endswith(".so"):
        return zipfile.ZIP_STORED

    # 已压缩格式 — 不再套 DEFLATE
    ext = os.path.splitext(arcname)[1].lower()
    if ext in ALREADY_COMPRESSED_EXTS:
        return zipfile.ZIP_STORED

    # 其余：文本 / JSON / XML 非资源表 / 小资产 — DEFLATED 能省空间
    return zipfile.ZIP_DEFLATED


def main():
    if len(sys.argv) != 4:
        print("usage: strip_so.py <in.apk> <out.apk> <strip-tool>")
        sys.exit(1)

    src, dst, strip = sys.argv[1], sys.argv[2], sys.argv[3]
    tmp = tempfile.mkdtemp(prefix="stripso_")
    skipped = []
    stripped_count = 0
    stored_count = 0
    deflated_count = 0
    stored_bytes_saved = 0
    deflated_bytes_saved = 0

    try:
        with zipfile.ZipFile(src) as z:
            z.extractall(tmp)

        # ── strip .so ──
        for root, _, files in os.walk(tmp):
            for f in files:
                if not f.endswith(".so"):
                    continue
                p = os.path.join(root, f)
                # 已 stripped 的再 strip 会报错，忽略非 0 退出
                subprocess.run(
                    [strip, "--strip-debug", p],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                stripped_count += 1

        # ── 重打包（按策略挑选压缩方式）──
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as out:
            for root, _, files in os.walk(tmp):
                for f in files:
                    p = os.path.join(root, f)
                    arc = os.path.relpath(p, tmp).replace(os.sep, "/")

                    # 跳过匹配 SKIP_PATTERNS 的条目（当前只有 META-INF/）
                    skip = False
                    for fn in SKIP_PATTERNS:
                        if fn(arc):
                            skip = True
                            break
                    if skip:
                        skipped.append(arc)
                        continue

                    mode = choose_compression(arc)
                    orig_size = os.path.getsize(p)
                    out.write(p, arc, mode)
                    new_size = out.getinfo(arc).compress_size

                    if mode == zipfile.ZIP_STORED:
                        stored_count += 1
                        stored_bytes_saved += (orig_size - new_size)
                    else:
                        deflated_count += 1
                        deflated_bytes_saved += (orig_size - new_size)

        print(
            f"stripped {stripped_count} .so files | "
            f"STORED {stored_count} ({stored_bytes_saved:+d} B) | "
            f"DEFLATED {deflated_count} ({deflated_bytes_saved:+d} B) | "
            f"skipped {len(skipped)} (META-INF signature)"
        )

        if skipped:
            print(f"  skipped entries: {', '.join(skipped[:10])}{' ...' if len(skipped) > 10 else ''}")

    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
