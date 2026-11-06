#!/usr/bin/env python3
"""用上游 release APK 的内容补齐 AAR。

library 变体只产出 Kotlin 类与 jniLibs，Flutter 引擎（`libflutter.so`）、Dart AOT 快照
（`libapp.so`）、`flutter_assets` 与 `io.flutter.*` 嵌入类都不会进去。这里把 APK 里已有的
这几样搬进 AAR，让宿主只依赖一个文件：

- `lib/<abi>/*.so` → `jni/<abi>/*.so`（AAR 已有的条目以 AAR 为准）
- `assets/flutter_assets/**` → 同名目录
- Flutter SDK 缓存里的 `flutter.jar`（嵌入类）合并进 `classes.jar`
"""

from __future__ import annotations

import argparse
import shutil
import sys
import zipfile
from pathlib import Path


def copy_missing(source: zipfile.ZipFile, target: zipfile.ZipFile, mapping: list[tuple[str, str]]) -> list[str]:
    """把 source 中命中的条目按 (前缀映射) 写进 target，已存在的跳过；返回写入清单。"""
    existing = set(target.namelist())
    written: list[str] = []
    for entry in source.namelist():
        if entry.endswith("/"):
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


def merge_embedding_jar(classes_jar: Path, engine_jars: list[Path]) -> int:
    """把 Flutter 引擎的嵌入类并进 classes.jar，返回新增条目数。"""
    if not engine_jars:
        return 0
    staging = classes_jar.parent / "_merge"
    shutil.rmtree(staging, ignore_errors=True)
    staging.mkdir(parents=True)
    with zipfile.ZipFile(classes_jar) as jar:
        jar.extractall(staging)
    names = {str(p.relative_to(staging)) for p in staging.rglob("*") if p.is_file()}
    added = 0
    for engine_jar in engine_jars:
        with zipfile.ZipFile(engine_jar) as jar:
            for entry in jar.namelist():
                if entry.endswith("/") or entry in names:
                    continue
                destination = staging / entry
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(jar.read(entry))
                names.add(entry)
                added += 1
    rebuilt = classes_jar.with_suffix(".jar.new")
    with zipfile.ZipFile(rebuilt, "w", zipfile.ZIP_DEFLATED) as jar:
        for path in sorted(staging.rglob("*")):
            if path.is_file():
                jar.write(path, str(path.relative_to(staging)))
    rebuilt.replace(classes_jar)
    shutil.rmtree(staging, ignore_errors=True)
    return added


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aar", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--abi", required=True)
    parser.add_argument("--flutter-root", required=True)
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
            )

    with zipfile.ZipFile(output) as archive:
        names = archive.namelist()
        if "classes.jar" not in names:
            raise SystemExit("AAR 里没有 classes.jar")
        staging = output.parent / "_classes"
        staging.mkdir(parents=True, exist_ok=True)
        (staging / "classes.jar").write_bytes(archive.read("classes.jar"))

    engine_root = Path(args.flutter_root) / "bin/cache/artifacts/engine"
    engine_jars = sorted(engine_root.glob("android-*release*/flutter.jar"))
    merged = merge_embedding_jar(staging / "classes.jar", engine_jars)

    with zipfile.ZipFile(output) as archive:
        entries = [(e, archive.read(e.filename)) for e in archive.infolist() if e.filename != "classes.jar"]
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for entry, data in entries:
            archive.writestr(entry, data)
        archive.write(staging / "classes.jar", "classes.jar")
    shutil.rmtree(staging, ignore_errors=True)

    with zipfile.ZipFile(output) as archive:
        final = set(archive.namelist())
    print(f"补齐 {len(written)} 个条目，嵌入类 {merged} 条，引擎 jar：{[j.name for j in engine_jars]}")

    required = {
        f"jni/{args.abi}/libapp.so",
        f"jni/{args.abi}/libflutter.so",
        f"jni/{args.abi}/librustdesk.so",
    }
    missing = sorted(required - final)
    if missing:
        raise SystemExit(f"AAR 缺少必要条目：{missing}")
    print(f"输出：{output} ({output.stat().st_size / 1024 / 1024:.1f} MiB)")


if __name__ == "__main__":
    sys.exit(main())
