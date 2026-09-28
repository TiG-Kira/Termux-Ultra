#!/usr/bin/env python3
"""
APK 后处理：对 .so 做 strip --strip-debug，其余条目按 Android APK 规范选择压缩方式重打包。

关键设计：哪些文件必须以 ZIP_STORED（未压缩）写回
  ART 运行时直接 mmap APK 内的 DEX / 资源表 / 清单文件：
    - classes*.dex 被压缩 → ART 读到乱码 → SIGSEGV / VerifyError（闪退）
    - resources.arsc 被压缩 → targetSdk>=30 安装失败（Failed parse ... stored uncompressed）
    - AndroidManifest.xml 被压缩 → PackageManager 无法解析
  .so 被压缩 → native lib 无法加载 → 闪退
  zipalign 只对 STORED 条目的物理偏移有意义。

用法: strip_so.py <in.apk> <out.apk> <strip-tool>
"""
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile


# ── 必须以 ZIP_STORED 写入的条目 ──
MUST_STORED = (
    lambda a: a.startswith("classes") and a.endswith(".dex"),  # classes.dex / classes2.dex ...
    lambda a: a == "resources.arsc",                            # 资源表
    lambda a: a == "AndroidManifest.xml",                       # 清单
    lambda a: a.endswith(".so"),                                # native 库
)

# ── 本身已是压缩格式、再套 DEFLATE 零收益，保持 STORED 省 CPU ──
ALREADY_COMPRESSED_EXTS = {
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp",
    ".7z", ".zip", ".rar", ".apkx",
    ".mp3", ".mp4", ".m4a", ".aac", ".ogg", ".flac", ".wav",
    ".ttf", ".otf", ".woff", ".woff2", ".eot",
    ".wasm", ".ico", ".ktx", ".ktx2", ".astc",
}

# ── 跳过的条目（旧签名残留，重签时由 apksigner 重新生成）──
SKIP = (lambda a: a.startswith("META-INF/"),)


def choose_compression(arcname: str) -> int:
    for fn in MUST_STORED:
        if fn(arcname):
            return zipfile.ZIP_STORED
    ext = os.path.splitext(arcname)[1].lower()
    if ext in ALREADY_COMPRESSED_EXTS:
        return zipfile.ZIP_STORED
    return zipfile.ZIP_DEFLATED


def main():
    if len(sys.argv) != 4:
        print("usage: strip_so.py <in.apk> <out.apk> <strip-tool>", file=sys.stderr)
        sys.exit(1)

    src, dst, strip = sys.argv[1], sys.argv[2], sys.argv[3]
    tmp = tempfile.mkdtemp(prefix="stripso_")
    stripped_count = 0
    stored_count = 0
    deflated_count = 0
    skipped = []
    try:
        with zipfile.ZipFile(src) as z:
            z.extractall(tmp)

        # strip .so（工具缺失时跳过，仍完成重打包；当前构建 .so 多已 stripped）
        have_strip = bool(strip) and os.path.exists(strip)
        if not have_strip:
            print(">> strip 工具缺失，跳过 .so strip（仅重打包）", file=sys.stderr)
        for root, _, files in os.walk(tmp):
            for f in files:
                if not f.endswith(".so"):
                    continue
                p = os.path.join(root, f)
                if have_strip:
                    try:
                        subprocess.run([strip, "--strip-debug", p],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
                    except (OSError, subprocess.SubprocessError):
                        pass
                stripped_count += 1

        # 重打包：关键条目 STORED，其余 DEFLATE（level 9）
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as out:
            for root, _, files in os.walk(tmp):
                for f in files:
                    p = os.path.join(root, f)
                    arc = os.path.relpath(p, tmp).replace(os.sep, "/")
                    if any(fn(arc) for fn in SKIP):
                        skipped.append(arc)
                        continue
                    mode = choose_compression(arc)
                    out.write(p, arc, mode)
                    if mode == zipfile.ZIP_STORED:
                        stored_count += 1
                    else:
                        deflated_count += 1

        print(f"stripped {stripped_count} .so | STORED {stored_count} | DEFLATED {deflated_count} | "
              f"skipped {len(skipped)} (META-INF)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
