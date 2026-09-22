# ============================================================================
#  Termux Ultra — R8 / ProGuard 规则
#
#  默认构建【不启用】R8（app/build.gradle 中 minifyEnabled 由 termux.enableR8 控制）。
#  启用方式：
#      ./gradlew assembleRelease -Ptermux.enableR8=true
#      或 CI 设置环境变量 TERMUX_ENABLE_R8=true
#
#  启用前请先读完本文件，并在真机上回归以下链路：
#      终端会话、AI 助手（在线 + 本地 llama）、插件中心（安装/页面渲染）、
#      VNC、SSH、QEMU 配置、资源页一键部署、日志查看器、备份恢复。
# ============================================================================

# ---- 不重命名 --------------------------------------------------------------
# 保留原有类名 / 字段名 / 方法名，原因：
#   1) Gson 按【字段名】读写 JSON，重命名会导致反序列化静默失败（字段为 null）；
#   2) Class.forName / getDeclaredField 这类字符串反射会直接失效；
#   3) 线上崩溃堆栈可读。
# 保留该项后 R8 依然会执行「删除未使用代码 + 优化」，而本项目体积的最大收益来自
# 裁剪第三方依赖中未被引用的部分（material-icons-extended ~5000 个图标等），
# 这部分收益与是否重命名无关。
-dontobfuscate
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable

# ---- 依赖库 / 序列化所需属性 ------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod,MethodParameters

# ---- 反射：以字符串方式加载的类 ---------------------------------------------
# TerminalRuntimeCore.kt 通过 Class.forName("com.termux.app.TermuxService") 启动服务
-keep class com.termux.app.TermuxService { *; }

# ---- 反射：按字段名访问（Gson 等） ------------------------------------------
# R8 看不到 Gson 的反射读取，会把「代码里未直接读取」的字段当作无用字段删除，
# 导致 JSON 反序列化拿到 null 且不报错。已设置 -dontobfuscate 后字段名不会被改写，
# 因此这里只需阻止字段被删除。按包覆盖，新增 Gson 模型无需改本文件。
-keepclassmembers class com.termux.** {
    <fields>;
}

# ---- Gson 反序列化的具体模型类（含嵌套类） ----------------------------------
# 插件清单 PluginManifest / 插件 Compose JSON DSL ComposeUiNode 均为整棵嵌套结构，
# 嵌套类型必须显式保留（否则会被 shrink 删除成员类型）。
-keep class com.termux.app.plugin.PluginManifest { *; }
-keep class com.termux.app.plugin.PluginManifest$* { *; }
-keep class com.termux.app.plugin.ComposeUiNode { *; }
-keep class com.termux.app.plugin.ComposeUiNode$* { *; }

# ---- JNI -------------------------------------------------------------------
# 被 native 层按方法名查找的 native 方法（libtermux.so / libnative-vnc.so）
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# libtermux 在 native 崩溃时回调的 Java 类
-keep class com.termux.shared.crash.** { *; }

# ---- 既有规则（保留） --------------------------------------------------------
# Temp fix for androidx.window:window:1.0.0-alpha09 imported by termux-shared
# https://issuetracker.google.com/issues/189001730
# https://android-review.googlesource.com/c/platform/frameworks/support/+/1757630
-keep class androidx.window.** { *; }
