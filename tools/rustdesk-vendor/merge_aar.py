#!/usr/bin/env python3
"""用上游 release APK 的内容补齐 AAR（只补 RustDesk 自己的产物，不含 Flutter 引擎）。

library 变体只产出 Kotlin 类与 jniLibs，Dart AOT 快照（`libapp.so`）与 `flutter_assets`
都不会进去，这里从参考 APK 里搬出来：

- `lib/<abi>/libapp.so` 等 → `jni/<abi>/`（AAR 已有的条目以 AAR 为准）
- `assets/flutter_assets/**` → 同名目录

**Flutter 引擎不进 AAR**：`libflutter.so` 与 `io.flutter.*` 嵌入类改由宿主 Gradle 依赖
官方 maven 产物（`io.flutter:arm64_v8a_release` / `flutter_embedding_release`，版本号取自
`gradle.properties` 的 `rustdesk.flutterEngine`）。这样仓库里只留 RustDesk 自己的约 20MB
产物，不必为每个 ABI 存一份 9–12MB 的引擎副本。

引擎与 `libapp.so` 必须严格同版本：CI 会在构建前比对 `gradle.properties` 与 Flutter SDK 的
`bin/internal/engine.version`，不一致直接失败——配错的后果不是编译报错，而是运行时黑屏。
"""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

# 引擎产物由宿主 Gradle 依赖提供，不写进 AAR
ENGINE_ENTRIES = {"libflutter.so"}


def copy_missing(
    source: zipfile.ZipFile,
    target: zipfile.ZipFile,
    mapping: list[tuple[str, str]],
    skip_names: set[str] | None = None,
) -> list[str]:
    """把 source 中命中的条目按 (前缀映射) 写进 target，已存在的跳过；返回写入清单。"""
    existing = set(target.namelist())
    skip = skip_names or set()
    written: list[str] = []
    for entry in source.namelist():
        if entry.endswith("/") or Path(entry).name in skip:
            continue
        for source_prefix, target_prefix in mapping:
            if not entry.startswith(source_prefix):
                continue
            relative = entry[len(source_prefix):]
            name = target_prefix + relative
            if name in existing:
                break
            target.writestr(name, source.read(entry))
            written.append(name)
            break
    return written


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aar", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--abi", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    aar_path = Path(args.aar)
    apk_path = Path(args.apk)
    output = Path(args.output)
    if not aar_path.is_file():
        raise SystemExit(f"AAR 不存在：{aar_path}")
    if not apk_path.is_file():
        raise SystemExit(f"APK 不存在：{apk_path}")

    with zipfile.ZipFile(aar_path) as source, zipfile.ZipFile(apk_path) as apk:
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as target:
            for entry in source.infolist():
                target.writestr(entry, source.read(entry.filename))

            written = copy_missing(
                apk,
                target,
                [
                    (f"lib/{args.abi}/", f"jni/{args.abi}/"),
                    ("assets/flutter_assets/", "assets/flutter_assets/"),
                ],
                skip_names=ENGINE_ENTRIES,
            )

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
    if f"jni/{args.abi}/libflutter.so" in final:
        raise SystemExit("AAR 里混进了 libflutter.so：引擎应由宿主从 maven 依赖，不应打包")

    print(f"补齐 {len(written)} 个条目")
    for name in sorted(sizes):
        if name.startswith("jni/"):
            print(f"  {name}  {sizes[name] / 1024 / 1024:.1f} MiB")
    print(f"输出：{output} ({output.stat().st_size / 1024 / 1024:.1f} MiB)")


if __name__ == "__main__":
    sys.exit(main())
