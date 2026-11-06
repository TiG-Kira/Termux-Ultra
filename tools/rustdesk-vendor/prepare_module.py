#!/usr/bin/env python3
"""把 RustDesk 的 Flutter application 重构为标准 add-to-app 模块拓扑，让 `flutter build aar` 能产出
干净且功能完整的 AAR。

拓扑：
  flutter/android/app   -> 重命名为 flutter/android/flutter（RustDesk Flutter 库，com.android.library）
  flutter/android/app   <- 新建宿主 application（最小壳，由它应用 Flutter gradle 插件驱动 AAR 构建）

前提：调用前应先对 flutter/android/app 施加 rustdesk-library.patch（application->library、剥清单、
注入 RustDeskHostBridge 等）。本脚本幂等兜底：若发现 app/build.gradle 仍是 application 则自动施加补丁。

用法：prepare_module.py <rustdesk checkout 根> [abi]
  abi: arm64-v8a | armeabi-v7a | x86_64
"""
import os
import re
import shutil
import subprocess
import sys

ABI_GRADLE = {
    "arm64-v8a": "arm64-v8a",
    "armeabi-v7a": "armeabi-v7a",
    "x86_64": "x86_64",
}

ABI_TARGET_PLATFORM = {
    "arm64-v8a": "android-arm64",
    "armeabi-v7a": "android-arm",
    "x86_64": "android-x64",
}


def apply_patch(root):
    """若 flutter/android/app 仍是 application，则施加仓库内的 rustdesk-library.patch。"""
    patch = os.path.join(os.path.dirname(os.path.abspath(__file__)), "rustdesk-library.patch")
    if not os.path.isfile(patch):
        return
    bg = os.path.join(root, "flutter", "android", "app", "build.gradle")
    if os.path.isfile(bg) and "com.android.library" in open(bg, encoding="utf-8").read():
        return  # 已改造，跳过
    r = subprocess.run(["git", "apply", "--ignore-whitespace", patch], cwd=root)
    if r.returncode != 0:
        raise SystemExit("rustdesk-library.patch 施加失败（returncode=%d）" % r.returncode)


def read_android_int(build_gradle, key):
    with open(build_gradle, encoding="utf-8") as f:
        s = f.read()
    m = re.search(r'%s(?:Version)?\s*=?\s*(\d+)' % re.escape(key), s)
    return m.group(1) if m else None


def strip_library_flutter_plugin(build_gradle):
    """模块拓扑下，Flutter gradle 插件由宿主 :app 应用，库 :flutter 不能再用。"""
    with open(build_gradle, encoding="utf-8") as f:
        s = f.read()
    s = re.sub(r'\n\s*id ["\']dev\.flutter\.flutter-gradle-plugin["\']\s*\n', '\n', s)
    s = re.sub(r'\n\s*flutter\s*\{[^}]*\}\s*\n', '\n', s)
    with open(build_gradle, "w", encoding="utf-8") as f:
        f.write(s)


def write_host_app(android_dir, abi_gradle, compile_sdk, min_sdk, target_sdk):
    app = os.path.join(android_dir, "app")
    kotlin_dir = os.path.join(
        app, "src", "main", "kotlin", "com", "carriez", "flutter_hbb", "host"
    )
    os.makedirs(kotlin_dir, exist_ok=True)
    with open(os.path.join(app, "build.gradle"), "w", encoding="utf-8") as f:
        f.write(
            """plugins {
    id "com.android.application"
    id "kotlin-android"
    id "dev.flutter.flutter-gradle-plugin"
}

android {
    namespace = "com.carriez.flutter_hbb.host"
    compileSdk = %s
    defaultConfig {
        applicationId "com.carriez.flutter_hbb.host"
        minSdk = %s
        targetSdk = %s
        versionCode 1
        versionName "1.0"
        ndk { abiFilters '%s' }
    }
    buildTypes {
        release {
            minifyEnabled false
        }
    }
}

flutter {
    source '../..'
}

dependencies {
    implementation project(':flutter')
}
"""
            % (compile_sdk, min_sdk, target_sdk, abi_gradle)
        )
    with open(
        os.path.join(app, "src", "main", "AndroidManifest.xml"), "w", encoding="utf-8"
    ) as f:
        f.write(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="false"
        android:name="android.app.Application">
        <activity
            android:name=".HostMainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:theme="@android:style/Theme.NoTitleBar"
            android:configChanges="orientation|keyboardHidden|keyboard|screenSize|smallestScreenSize|locale|layoutDirection|fontScale|screenLayout|density|uiMode|navigation">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""
        )
    with open(
        os.path.join(kotlin_dir, "HostMainActivity.kt"), "w", encoding="utf-8"
    ) as f:
        f.write(
            """package com.carriez.flutter_hbb.host

import io.flutter.embedding.android.FlutterActivity

class HostMainActivity : FlutterActivity()
"""
        )


def rewrite_settings(settings_path):
    with open(settings_path, encoding="utf-8") as f:
        s = f.read()
    if ":flutter" in s:
        return
    if re.search(r'include\s+["\']:app["\']', s):
        s = re.sub(
            r'(include\s+["\']:app["\'])', r'\1, ":flutter"', s, count=1
        )
    else:
        s = s.rstrip() + '\ninclude ":flutter"\n'
    with open(settings_path, "w", encoding="utf-8") as f:
        f.write(s)


def add_pubspec_module(pubspec_path):
    with open(pubspec_path, encoding="utf-8") as f:
        s = f.read()
    if "module:" in s:
        return
    block = (
        "  androidX: true\n"
        "  androidPackage: com.carriez.flutter_hbb\n"
        "  moduleName: flutter_hbb\n"
    )
    marker = "  uses-material-design: true\n"
    if marker in s:
        # 标准 Flutter app：module 与 uses-material-design 同挂 flutter: 下
        s = s.replace(marker, marker + "  module:\n" + block, 1)
    elif "flutter:\n" in s:
        s = s.replace("flutter:\n", "flutter:\n  module:\n" + block, 1)
    else:
        # 兜底：作为顶层键追加（极少数情况）
        s = s.rstrip() + "\nmodule:\n" + block
    with open(pubspec_path, "w", encoding="utf-8") as f:
        f.write(s)


def main():
    root = sys.argv[1]
    abi = sys.argv[2] if len(sys.argv) > 2 else "arm64-v8a"
    abi_gradle = ABI_GRADLE.get(abi, "arm64-v8a")
    android = os.path.join(root, "flutter", "android")
    app = os.path.join(android, "app")
    flut = os.path.join(android, "flutter")
    assert os.path.isdir(app), "expected %s (RustDesk app module)" % app

    # 0) 兜底施加 library 补丁（CI 通常已单独施加）
    apply_patch(root)

    # 1) 读取库（原 app）的 SDK 版本，供宿主对齐（AGP 要求所有模块同 compileSdk）
    app_bg = os.path.join(app, "build.gradle")
    compile_sdk = read_android_int(app_bg, "compileSdk") or "36"
    min_sdk = read_android_int(app_bg, "minSdk") or "22"
    target_sdk = read_android_int(app_bg, "targetSdk") or "36"

    # 2) 库 build.gradle 移除 Flutter 插件与 flutter { source }（模块拓扑）
    strip_library_flutter_plugin(app_bg)

    # 3) application 重命名为 library
    if os.path.exists(flut):
        shutil.rmtree(flut)
    shutil.move(app, flut)

    # 4) 新建宿主 application（最小壳，应用 Flutter gradle 插件驱动构建）
    write_host_app(android, abi_gradle, compile_sdk, min_sdk, target_sdk)

    # 5) settings.gradle include :flutter
    rewrite_settings(os.path.join(android, "settings.gradle"))

    # 6) pubspec.yaml 声明 flutter.module（flutter build aar 依赖它识别模块）
    add_pubspec_module(os.path.join(root, "flutter", "pubspec.yaml"))

    print("module topology prepared: host :app (application) + library :flutter")


if __name__ == "__main__":
    main()
