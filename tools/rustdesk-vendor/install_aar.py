#!/usr/bin/env python3
"""把 CI 产出的 RustDesk AAR 装进宿主工程。

两个 ABI 的 AAR 不能同时作为依赖：它们的 classes.jar 完全一样，同时引入会重复类。因此：

- **主 AAR**（arm64-v8a）放进 `app/libs/rustdesk-release.aar`，随仓库分发，所有构建都用它；
- **x86_64 原生库**只在需要跑 x86_64 模拟器时，以 jniLibs 形式注入 `app/src/debug/`
  源集——debug 走它，release 不受影响，且不会和 AAR 的类冲突（只是不同 ABI 的 .so）。

用法：

    # 装主 AAR
    python3 tools/rustdesk-vendor/install_aar.py --aar rustdesk-release.aar

    # 注入 x86_64 会话库（模拟器实测用）
    python3 tools/rustdesk-vendor/install_aar.py --aar rustdesk-x86_64.aar --stage x86_64
"""

from __future__ import annotations

import argparse
import shutil
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MAIN_AAR = REPO_ROOT / "app/libs/rustdesk-release.aar"
DEBUG_JNILIBS = REPO_ROOT / "app/src/debug/jniLibs"


def install_main(aar: Path) -> None:
    MAIN_AAR.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(aar, MAIN_AAR)
    print(f"已安装主 AAR：{MAIN_AAR} ({MAIN_AAR.stat().st_size / 1024 / 1024:.1f} MiB)")


def stage_native_libs(aar: Path, abi: str) -> None:
    destination = DEBUG_JNILIBS / abi
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    written: list[str] = []
    with zipfile.ZipFile(aar) as archive:
        for entry in archive.namelist():
            if not entry.startswith(f"jni/{abi}/") or not entry.endswith(".so"):
                continue
            target = destination / Path(entry).name
            target.write_bytes(archive.read(entry))
            written.append(f"{target.name} ({target.stat().st_size / 1024 / 1024:.1f} MiB)")
    if not written:
        raise SystemExit(f"AAR 里没有 jni/{abi}/*.so")
    print(f"已注入 {abi} 原生库到 {destination}：")
    for line in written:
        print(f"  {line}")
    print("提示：该目录不入库，仅供本机 x86_64 调试；构建时用 -Ptermux.abi=x86_64。")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aar", required=True)
    parser.add_argument("--stage", metavar="ABI", help="把该 ABI 的原生库注入 debug 源集，而不是覆盖主 AAR")
    args = parser.parse_args()

    aar = Path(args.aar)
    if not aar.is_file():
        raise SystemExit(f"AAR 不存在：{aar}")

    with zipfile.ZipFile(aar) as archive:
        names = set(archive.namelist())
    for required in ("classes.jar", "R.txt"):
        if required not in names:
            raise SystemExit(f"{aar} 不像一个 AAR（缺少 {required}）")
    if any(name.endswith("libflutter.so") for name in names):
        raise SystemExit(f"{aar} 里含 libflutter.so：引擎应由 maven 依赖提供，不该打包")

    if args.stage:
        stage_native_libs(aar, args.stage)
    else:
        install_main(aar)


if __name__ == "__main__":
    sys.exit(main())
