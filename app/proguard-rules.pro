# ============================================================================
#  Termux Ultra — R8 / ProGuard 规则
#
#  release 构建默认【启用】R8（app/build.gradle 中 minifyEnabled 由 termux.enableR8 控制，默认 true）。
#  关闭方式：
#      ./gradlew assembleRelease -Ptermux.enableR8=false
#      或 CI 设置环境变量 TERMUX_ENABLE_R8=false
#
#  改动本文件或升级依赖后，请在真机上回归以下链路：
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

# ---- tink 引用了依赖图中不存在的 protobuf ----
# sshlib 带来的 JVM 版 tink 1.20.0 依赖 com.google.protobuf，而 protobuf 并未进入
# 本项目依赖图。security-crypto 只用到 tink 的 Aead 接口，protobuf 相关分支运行时
# 不会被触达（即便触达，GitHubSessionStore 也有明文 prefs 兜底）。
# 缺这条 R8 会把 missing class 判定为错误并中断构建。
-dontwarn com.google.protobuf.**

# ---- libterminal: 外部 aar 存在反射 / native 回调 ----
# libterminal aar 自带的 proguard.txt 是空模板，未提供 keep 规则。
# 代码中 TerminalDetailScreenCompose.shareTranscript() 通过反射读取
# com.awkoo.libterminal.engine.TerminalSession.emulator 字段；native 层也会按签名
# 查找 Java 回调。该 aar 体积不大，整体保留以避免 R8 删除运行时需要的方法/字段。
-keep class com.awkoo.libterminal.** { *; }

# ---- 既有规则（保留） --------------------------------------------------------
# Temp fix for androidx.window:window:1.0.0-alpha09 imported by termux-shared
# https://issuetracker.google.com/issues/189001730
# https://android-review.googlesource.com/c/platform/frameworks/support/+/1757630
-keep class androidx.window.** { *; }
