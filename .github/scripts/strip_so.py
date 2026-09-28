#!/usr/bin/env python3
# 对 APK 内所有 .so 执行 strip --strip-debug，重新打包为未签名 APK。
# --strip-debug 只删 .debug_*（DWARF 调试信息），保留 .symtab（静态符号表），
# 这样 native 崩溃的 tombstone 仍能解析出函数名，方便排查；.so 仍以 STORED（未压缩）写入，
# 与 app/build.gradle 的 jniLibs.useLegacyPackaging = true 保持一致。
#
# 用法: strip_so.py <in.apk> <out.apk> <strip-tool>
#   strip-tool: NDK 的 llvm-strip，或 binutils 的 strip（跨架构剥离 ELF 同样安全）。
import sys, os, zipfile, subprocess, tempfile, shutil


def main():
    if len(sys.argv) != 4:
        print("usage: strip_so.py <in.apk> <out.apk> <strip-tool>")
        sys.exit(1)
    src, dst, strip = sys.argv[1], sys.argv[2], sys.argv[3]
    tmp = tempfile.mkdtemp(prefix="stripso_")
    try:
        with zipfile.ZipFile(src) as z:
            z.extractall(tmp)
        so_count = 0
        for root, _, files in os.walk(tmp):
            for f in files:
                if not f.endswith(".so"):
                    continue
                p = os.path.join(root, f)
                # 已 stripped 的再 strip 会报错，忽略非 0 退出
                subprocess.run([strip, "--strip-debug", p],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                so_count += 1
        # 重新打包：.so 用 STORED（与 AGP useLegacyPackaging 一致），其余 DEFLATED
        with zipfile.ZipFile(dst, "w") as out:
            for root, _, files in os.walk(tmp):
                for f in files:
                    p = os.path.join(root, f)
                    arc = os.path.relpath(p, tmp)
                    if f.endswith(".so"):
                        out.write(p, arc, zipfile.ZIP_STORED)
                    else:
                        out.write(p, arc, zipfile.ZIP_DEFLATED)
        print(f"stripped {so_count} .so files")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
