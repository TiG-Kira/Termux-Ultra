#!/usr/bin/env python3
"""把 RustDesk 的 Flutter 工程重构为 editable-module 拓扑，让 `flutter build aar` 能产出
可被宿主（本应用）依赖的 AAR（X11 式 vendoring 的前提）。

拓扑（Flutter 3.24.5 的 flutter build aar 标准 editable 模式）：
  flutter/                         <- pubspec.yaml 含 flutter.module:
  flutter/android/app              <- 保留为 com.android.application（构建期宿主，flutter 插件
                                    需要它来读 applicationVariants；运行时不需要它的 MainActivity）
  flutter/.android/Flutter         <- flutter pub get 生成的 ephemeral 库，AAR 真正从这里出
  flutter/.android/include_flutter.groovy  <- 把 :flutter 指向 .android/Flutter

关键修正（与早期 thin-module 方案相反）：
  * 不再把 app 改名成 flutter（那会让 :flutter 永不解析 -> assert moduleProject != null）
  * 而是给 flutter/android/settings.gradle 追加 setBinding + evaluate(include_flutter.groovy)，
    让 flutter build aar 在 editable 模式下把 :flutter 解析到 .android/Flutter
  * flutter build aar 走 --no-pub，原生 so 在 pub get 生成 .android 之后注入 .android/Flutter/jniLibs

用法：prepare_module.py <rustdesk 根> <abi> <native_so_dir>
  abi:           arm64-v8a | armeabi-v7a | x86_64
  native_so_dir: 含 librustdesk.so 与 libc++_shared.so 的目录（cargo ndk 产物）
"""
import os
import subprocess
import sys

ABI_GRADLE = {
    "arm64-v8a": "arm64-v8a",
    "armeabi-v7a": "armeabi-v7a",
    "x86_64": "x86_64",
}


def add_pubspec_module(pubspec_path):
    """pubspec 必须声明 flutter.module，否则 flutter build aar 不识别为模块。

    module 必须是 flutter: 下的对象；其字段需再缩进一级（4 空格），否则会被当成 flutter 的
    直属子项导致校验 `module is null`。
    """
    with open(pubspec_path, encoding="utf-8") as f:
        s = f.read()
    if "module:" in s:
        return
    module_block = (
        "  module:\n"
        "    androidX: true\n"
        "    androidPackage: com.carriez.flutter_hbb.vendored\n"
        "    moduleName: flutter_hbb\n"
    )
    marker = "  uses-material-design: true\n"
    if marker in s:
        s = s.replace(marker, marker + module_block, 1)
    elif "flutter:\n" in s:
        s = s.replace("flutter:\n", "flutter:\n" + module_block, 1)
    else:
        s = s.rstrip() + "\nmodule:\n  androidX: true\n  androidPackage: com.carriez.flutter_hbb.vendored\n  moduleName: flutter_hbb\n"
    with open(pubspec_path, "w", encoding="utf-8") as f:
        f.write(s)


def add_settings_evaluate(settings_path):
    """editable-module 模式的关键：让 :flutter 解析到 .android/Flutter。

    settingsDir 是 flutter/android，其 parentFile 即 flutter/，故 .android/include_flutter.groovy
    解析为 flutter/.android/include_flutter.groovy（由 flutter pub get 生成）。
    """
    with open(settings_path, encoding="utf-8") as f:
        s = f.read()
    marker = "include_flutter.groovy"
    if marker in s:
        return
    s = s.rstrip() + "\n\nsetBinding(new Binding([gradle: this]))\n"
    s += "evaluate(new File(settingsDir.parentFile, '.android/include_flutter.groovy'))\n"
    with open(settings_path, "w", encoding="utf-8") as f:
        f.write(s)


def inject_native_libs(flutter_root, abi, native_so_dir):
    """把 Rust 核心 so 注入 ephemeral 库的 jniLibs，使其编进 AAR。

    .android/Flutter 由 flutter pub get 生成，故必须在 pub get 之后注入。
    """
    lib_dir = os.path.join(
        flutter_root, ".android", "Flutter", "src", "main", "jniLibs", abi
    )
    os.makedirs(lib_dir, exist_ok=True)
    for so in ("librustdesk.so", "libc++_shared.so"):
        src = os.path.join(native_so_dir, so)
        if not os.path.isfile(src):
            raise SystemExit("缺少原生库：%s" % src)
        dst = os.path.join(lib_dir, so)
        with open(src, "rb") as fsrc, open(dst, "wb") as fdst:
            fdst.write(fsrc.read())
    print("injected native libs -> %s" % lib_dir)


def main():
    root = sys.argv[1]
    abi = sys.argv[2] if len(sys.argv) > 2 else "arm64-v8a"
    native_so_dir = sys.argv[3] if len(sys.argv) > 3 else None
    abi_gradle = ABI_GRADLE.get(abi, "arm64-v8a")
    flutter = os.path.join(root, "flutter")
    android = os.path.join(flutter, "android")
    settings = os.path.join(android, "settings.gradle")
    pubspec = os.path.join(flutter, "pubspec.yaml")
    assert os.path.isdir(android), "expected %s (RustDesk android dir)" % android
    assert os.path.isfile(settings), "missing %s" % settings
    assert os.path.isfile(pubspec), "missing %s" % pubspec

    # 1) settings.gradle 追加 evaluate（editable 模式让 :flutter 解析）
    add_settings_evaluate(settings)
    # 2) pubspec 声明 flutter.module
    add_pubspec_module(pubspec)

    # 3) flutter pub get 生成 .android/（含 Flutter 库 + include_flutter.groovy）
    #    shell=True 跨平台最稳：Linux 走 /bin/sh，Windows 走 cmd.exe 能正确解析 flutter.bat
    print("running flutter pub get ...")
    r = subprocess.run("flutter pub get", cwd=flutter, shell=True)
    if r.returncode != 0:
        raise SystemExit("flutter pub get 失败（returncode=%d）" % r.returncode)

    # 4) 注入原生 so（必须在 pub get 之后）
    if native_so_dir:
        inject_native_libs(flutter, abi_gradle, native_so_dir)
    else:
        print("warn: 未传 native_so_dir，跳过原生 so 注入（AAR 将缺 librustdesk.so）")

    print("editable-module prepared: AAR 将从 %s/.android/Flutter 产出" % flutter)


if __name__ == "__main__":
    main()
