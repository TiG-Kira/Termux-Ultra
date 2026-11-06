#!/usr/bin/env python3
"""把 RustDesk 上游的 flutter/android/app 从 application 模块改造成 library 模块。

改造内容：

- `com.android.application` → `com.android.library`，并移除只有 application 才能拥有的
  `applicationId` / `versionCode` / `versionName` / `signingConfigs` / `buildTypes`；
- 清单里的 `<application>` 去掉 `android:name`（Application 由宿主接管，参考 LorieApp 做法）、
  图标与名称（避免顶掉本应用的图标与 App 名）；
- `MainActivity` 摘掉 LAUNCHER 入口并置 `exported="false"`：进入会话一律由宿主显式拉起，
  不再对外暴露第二个入口；同时摘掉 `rustdesk://` 深链过滤器——否则宿主会被注册成
  RustDesk 链接的全局处理者，而那个组件并不是我们真正运行的会话页；
- `BootReceiver` 置 `enabled="false"`：开机自启属于 RustDesk 独立 App 的行为，宿主里不该生效；
- `MainActivity` 声明改为 `open class`：宿主会话页直接继承它复用上游初始化，Kotlin 类默认 final；
- 注入 `RustDeskHostBridge.kt`：上游 `DocumentStorage` 是 `internal class`，跨 Gradle 模块
  不可见，桥接必须落在本模块内；同时把 `MainActivity` 里对 `MainApplication` 的强转改走
  桥接，避免宿主 Application 不是 `MainApplication` 时抛 `ClassCastException`。

脚本按词法配平花括号，不裸计数，重复执行结果一致（幂等）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path


def drop_gradle_block(text: str, keyword: str) -> str:
    """删除形如 `keyword { ... }` 的 Gradle 块，按花括号配平定位结尾。"""
    pattern = re.compile(rf"^[ \t]*{keyword}\s*\{{", re.MULTILINE)
    while True:
        match = pattern.search(text)
        if match is None:
            return text
        open_index = text.index("{", match.start())
        depth = 0
        cursor = open_index
        while cursor < len(text):
            char = text[cursor]
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    break
            cursor += 1
        else:
            raise SystemExit(f"未配平的 Gradle 块：{keyword}")
        line_end = text.find("\n", cursor)
        line_end = len(text) if line_end == -1 else line_end + 1
        text = text[: match.start()] + text[line_end:]


def patch_build_gradle(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    text = text.replace('id "com.android.application"', 'id "com.android.library"')
    for key in ("applicationId", "versionCode", "versionName"):
        text = re.sub(rf"^[ \t]*{key}\b[^\n]*\n", "", text, flags=re.MULTILINE)
    for key in ("signingConfigs", "buildTypes"):
        text = drop_gradle_block(text, key)
    path.write_text(text, encoding="utf-8")


def strip_application_attributes(text: str) -> str:
    """`<application>` 上的 name/icon/roundIcon/label 由宿主决定，这里只保留 RTL 支持。"""
    pattern = re.compile(r"<application\b[^>]*>", re.DOTALL)
    match = pattern.search(text)
    if match is None:
        raise SystemExit("清单里找不到 <application> 标签")
    tag = match.group(0)
    for attribute in ("android:name", "android:icon", "android:roundIcon", "android:label"):
        tag = re.sub(rf'\s+{attribute}="[^"]*"', "", tag)
    return text[: match.start()] + tag + text[match.end() :]


def patch_manifest(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    text = strip_application_attributes(text)

    # LAUNCHER 入口摘掉，否则装进宿主后会多出一个桌面图标
    text = re.sub(
        r"\s*<intent-filter>\s*<action android:name=\"android\.intent\.action\.MAIN\"\s*/>"
        r"\s*<category android:name=\"android\.intent\.category\.LAUNCHER\"\s*/>\s*</intent-filter>",
        "",
        text,
    )

    # MainActivity 只允许宿主显式启动
    def narrow_activity(match: re.Match[str]) -> str:
        return match.group(0).replace('android:exported="true"', 'android:exported="false"', 1)

    text = re.sub(
        r'<activity\b(?:(?!</activity>).)*?android:name="\.MainActivity"(?:(?!</activity>).)*?</activity>',
        narrow_activity,
        text,
        flags=re.DOTALL,
    )

    # `rustdesk://` 深链过滤器摘掉：注册它会让宿主变成全局 RustDesk 链接处理者，而链接进来
    # 打开的是上游 MainActivity，不是宿主自己的会话页（两者 Application 契约不同）。
    text = re.sub(
        r'\s*<intent-filter\b(?:(?!</intent-filter>).)*?android:scheme="rustdesk"(?:(?!</intent-filter>).)*?</intent-filter>',
        "",
        text,
        flags=re.DOTALL,
    )

    text = re.sub(
        r'(<receiver\b(?:(?!</receiver>).)*?android:name="\.BootReceiver"(?:(?!</receiver>).)*?)'
        r'android:enabled="true"',
        r'\1android:enabled="false"',
        text,
        flags=re.DOTALL,
    )

    path.write_text(text, encoding="utf-8")


def patch_main_activity(path: Path) -> None:
    """放开 final、并把 Application 强转改走宿主桥接。"""
    text = path.read_text(encoding="utf-8")

    text, opened = re.subn(
        r"^class MainActivity\s*:\s*FlutterActivity\(\)",
        "open class MainActivity : FlutterActivity()",
        text,
        flags=re.MULTILINE,
    )
    if opened != 1:
        raise SystemExit("MainActivity 声明未命中，上游可能已改动类声明")

    text, casts = re.subn(
        r"\(application as MainApplication\)\.documentStorage",
        "RustDeskHostBridge.documentStorage(application)",
        text,
    )
    if casts == 0:
        raise SystemExit("MainActivity 里没找到 `(application as MainApplication).documentStorage`")

    path.write_text(text, encoding="utf-8")


HOST_BRIDGE_SOURCE = '''package com.carriez.flutter_hbb

import android.content.Context
import android.util.Log
import ffi.FFI

/**
 * 宿主接入点（由宿主仓库 tools/rustdesk-vendor/patch_android_library.py 生成，勿手改）。
 *
 * 上游把进程级初始化放在 MainApplication.onCreate，并用 @Keep 的 rustDocumentStorage
 * 方法接受 Rust 侧 JNI 反射调用。宿主的 Application 必须继承 LorieApp（Termux:X11 的
 * checkcast 要求），无法同时继承 MainApplication，因此把这两件事收敛到这里：
 *
 * - [attach] 等价于 MainApplication.onCreate 里的 FFI.onAppStart
 * - [request] 等价于 MainApplication.rustDocumentStorage 的实现
 *
 * DocumentStorage 是 internal class，宿主模块看不到，所以这段桥接必须编译在本模块内。
 */
object RustDeskHostBridge {

    private const val TAG = "RustDeskHostBridge"

    @Volatile
    private var storage: DocumentStorage? = null

    private fun storageOf(context: Context): DocumentStorage =
        storage ?: synchronized(this) {
            storage ?: DocumentStorage(context.applicationContext).also { storage = it }
        }

    @JvmStatic
    fun documentStorage(context: Context): DocumentStorage = storageOf(context)

    /** 幂等。native 库缺失（该 ABI 没有产物）时只记日志，不阻断宿主启动。 */
    @JvmStatic
    fun attach(context: Context) {
        storageOf(context)
        try {
            FFI.onAppStart(context.applicationContext)
        } catch (error: Throwable) {
            Log.e(TAG, "RustDesk native 初始化失败，内嵌会话不可用", error)
        }
    }

    @JvmStatic
    fun request(context: Context, payload: String): String = storageOf(context).request(payload)
}
'''


def write_host_bridge(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(HOST_BRIDGE_SOURCE, encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("用法：patch_android_library.py <rustdesk 源码根目录>")
    root = Path(sys.argv[1]).resolve()
    patch_build_gradle(root / "flutter/android/app/build.gradle")
    patch_manifest(root / "flutter/android/app/src/main/AndroidManifest.xml")
    patch_main_activity(root / "flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MainActivity.kt")
    write_host_bridge(root / "flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/RustDeskHostBridge.kt")
    print(f"已改造成 library：{root}")


if __name__ == "__main__":
    main()
