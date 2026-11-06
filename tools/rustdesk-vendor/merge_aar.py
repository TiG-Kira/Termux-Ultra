#!/usr/bin/env python3
"""校验并规整 RustDesk library AAR（只含 RustDesk 自身产物，不含 Flutter 引擎）。

library 变体的 assembleRelease 已把 Dart AOT 快照（libapp.so）、flutter_assets 与
Kotlin 类打包进 AAR；librustdesk.so 由「Build rust core」步骤 cp 到 jniLibs 后一并进入。
本脚本只做：把 app-release.aar 规整为统一命名（rustdesk-release.aar），并断言必要条目
齐全、且未混进 Flutter 引擎（libflutter.so 由宿主从 maven 依赖，不应打包）。

引擎与 libapp.so 必须严格同版本：CI 会在构建前比对 gradle.properties 与 Flutter SDK 的
bin/internal/engine.version，不一致直接失败——配错的后果不是编译报错，而是运行时黑屏。
"""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

ENGINE_ENTRIES = {"libflutter.so"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aar", required=True)
    parser.add_argument("--abi", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    aar_path = Path(args.aar)
    output = Path(args.output)
    if not aar_path.is_file():
        raise SystemExit(f"AAR 不存在：{aar_path}")

    # 逐条复制，规整为统一命名（便于后续二次处理）
    with zipfile.ZipFile(aar_path) as source:
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as target:
            for entry in source.infolist():
                target.writestr(entry, source.read(entry.filename))

    with zipfile.ZipFile(output) as archive:
        final = set(archive.namelist())
        sizes = {name: archive.getinfo(name).file_size for name in final}

    required = {
        f"jni/{args.abi}/libapp.so",
        f"jni/{args.abi}/librustdesk.so",
        "classes.jar",
    }
    missing = sorted(required - final)
    if missing:
        raise SystemExit(f"AAR 缺少必要条目：{missing}")
    if not any(n.startswith("assets/flutter_assets/") for n in final):
        raise SystemExit("AAR 缺少 assets/flutter_assets")
    if f"jni/{args.abi}/libflutter.so" in final:
        raise SystemExit(
            "AAR 里混进了 libflutter.so：引擎应由宿主从 maven 依赖，不应打包"
        )

    print(f"规整完成：{output}")
    for name in sorted(sizes):
        if name.startswith("jni/"):
            print(f"  {name}  {sizes[name] / 1024 / 1024:.1f} MiB")
    print(f"输出：{output} ({output.stat().st_size / 1024 / 1024:.1f} MiB)")


if __name__ == "__main__":
    sys.exit(main())
