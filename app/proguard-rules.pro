# ============================================================================
#  Termux Ultra — R8 / ProGuard 规则
#
#  release 构建默认【启用】R8（app/build.gradle 中 minifyEnabled 由 termux.enableR8 控制，默认 true）。
#  关闭方式：
#      ./gradlew assembleRelease -Ptermux.enableR8=false
#      或 CI 设置环境变量 TERMUX_ENABLE_R8=false
#
#  本文件按「反向调用面」分节。
#  反向调用 = 调用方在字节码里看不出被调用方的调用：native 侧按名字 GetMethodID 查
#  Java 方法、Gson 按字段名读写、Class.forName 按字符串加载、JS 调
#  @JavascriptInterface。R8 的静态分析看不见这些边，默认会把它们当成无用代码裁掉或
#  改写，编译期不报错、运行期才炸。新增这类调用点时必须在本文件对应小节补规则。
#
#  改动本文件或升级依赖后，请在真机上回归以下链路：
#      终端会话、AI 助手（在线 + 本地 llama）、插件中心（安装/页面渲染）、
#      VNC、SSH、QEMU 配置、资源页一键部署、日志查看器、备份恢复。
# ============================================================================

# ---- 不重命名 --------------------------------------------------------------
# 保留原有类名 / 字段名 / 方法名，原因：
#   1) Gson 按【字段名】读写 JSON，重命名会导致反序列化静默失败（字段为 null）；
#   2) native 层按【方法名 + 签名】GetMethodID，重命名 = 查不到 = 崩溃；
#   3) Class.forName / getDeclaredField 这类字符串反射会直接失效；
#   4) 线上崩溃堆栈可读。
# 保留该项后 R8 依然会执行「删除未使用代码 + 优化」，而本项目体积的最大收益来自
# 裁剪第三方依赖中未被引用的部分（material-icons-extended ~5000 个图标等），
# 这部分收益与是否重命名无关。
-dontobfuscate

# ---- 依赖库 / 序列化所需属性 ------------------------------------------------
# Signature：Gson TypeToken 靠匿名子类的泛型签名还原 ArrayList<VncConnection> 这类
#            参数化类型，缺了它 fromJson 拿不到元素类型。
# InnerClasses / EnclosingMethod：嵌套类与匿名类的定位信息（同上）。
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod,MethodParameters,Exceptions

# ===========================================================================
#  1. JNI —— Java → native（native 方法本体）
# ===========================================================================
# native 方法没有 Java 实现体，R8 看不到任何引用点，必须按声明保留。
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ===========================================================================
#  2. JNI —— native → Java（回调），反向调用里最脆的一层
# ===========================================================================

# --- 2.1 VNC（app/src/main/cpp_avnc/native-vnc.cpp）-------------------------
# native 侧在 Java_com_gaurav_avnc_vnc_VncClient_initLibrary 里缓存 VncClient 的
# Class，之后用 GetMethodID(cls, "<名字>", "<签名>") 查方法、GetFieldID 查字段。
# 这些 cb* 方法在 Java 侧没有任何调用点（唯一调用方在 C++），R8 会整批裁掉，
# 表现为「连上服务器就崩 / 整个 VNC 功能不可用」。
-keep class com.gaurav.avnc.vnc.VncClient { *; }
-keepclassmembers class com.gaurav.avnc.vnc.VncClient {
    *** cb*(...);
}
# onGetCredential() 用 GetFieldID(jCredentialCls, "username" / "password") 读字段
-keep class com.gaurav.avnc.vnc.UserCredential { *; }
-keepclassmembers class com.gaurav.avnc.vnc.UserCredential {
    *** username;
    *** password;
}
# PointerButton 的位掩码在 native 与 UI 两侧共用，整体保留避免成员被裁
-keep class com.gaurav.avnc.vnc.PointerButton { *; }

# --- 2.2 native 崩溃信号处理器（app/src/main/cpp/native_crash_handler.c）-----
# FindClass("com/termux/app/utils/NativeCrashBridge")
#   + GetStaticMethodID(..., "nativeCrashDetected", "(I)V")
-keep class com.termux.app.utils.NativeCrashBridge { *; }
-keepclassmembers class com.termux.app.utils.NativeCrashBridge {
    public static void nativeCrashDetected(int);
}

# --- 2.3 libtermux 在 native 崩溃时回调的 Java 类 ----------------------------
-keep class com.termux.shared.crash.** { *; }

# --- 2.4 libterminal: 外部 aar 存在反射 / native 回调 ------------------------
# libterminal aar 自带的 proguard.txt 是空模板，未提供 keep 规则。
# 代码中 TerminalDetailScreenCompose.shareTranscript() 通过反射读取
# com.awkoo.libterminal.engine.TerminalSession.emulator 字段；native 层也会按签名
# 查找 Java 回调。该 aar 体积不大，整体保留以避免 R8 删除运行时需要的方法/字段。
-keep class com.awkoo.libterminal.** { *; }

# --- 2.5 termux-shared local-socket.cpp: JniResult 构造 -----------------------
# local-socket.cpp 在 JNI 层 FindClass("com/termux/shared/jni/models/JniResult")
# 然后 GetMethodID("<init>", "(IILjava/lang/String;I)V") 构造返回值对象。
# R8 侧看不到 native 调用，默认整类删除 → native 侧 FindClass 失败。
-keep class com.termux.shared.jni.models.JniResult { *; }

# --- 2.6 libjpeg-turbo (extern) —— 如果启用 extern/jpeg-turbo --------------------
# turbojpeg-jni.c 里对 com/libjpegturbo/TJ* 的 JNI 回调；与 wolfssl 同属 extern，
# 目前项目并未直接使用，保留 -dontwarn 兜底即可，无需 keep。

# ===========================================================================
#  3. Gson —— 按字段名读写 + TypeToken 泛型签名
# ===========================================================================
# TypeToken 的匿名子类（new TypeToken<ArrayList<VncConnection>>() {}）是 Gson 还原
# 参数化类型的唯一依据，靠 getGenericSuperclass() 的 Signature 属性。匿名类在 Java
# 侧除了传给 fromJson() 之外没有别的引用点，容易被判为可裁剪。
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# 按字段名读写的模型：R8 看不到 Gson 的反射读取，会把「代码里未直接读取」的字段
# 当作无用字段删除，导致 JSON 反序列化拿到 null 且不报错。
# 已设置 -dontobfuscate 后字段名不会被改写，因此这里只需阻止字段被删除。
# 按包覆盖，新增 Gson 模型无需改本文件。
-keepclassmembers class com.termux.** {
    <fields>;
}
# AVNC 侧模型（ServerProfile 走 Room + Parcelable，LoginInfo 走 Gson）
-keep class com.gaurav.avnc.model.** { *; }

# 明确走 TypeToken 的模型类（整类保留，含嵌套类型）。
# 注意：部分模型是所在文件的嵌套类（如 ThirdPartyResource 嵌套于
# ThirdPartyCenterActivity、InstallRecord 嵌套于 PluginLoader），keep 必须写到
# 外部类$嵌套类 的完整形式，否则规则静默失效、Gson 反序列化拿到的字段会被 R8 删空。
-keep class com.termux.app.vnc.VncConnection { *; }
-keep class com.termux.app.ssh.SshConnection { *; }
-keep class com.termux.app.compose.QemuVmConfig { *; }
-keep class com.termux.app.compose.QemuVmConfig$* { *; }
-keep class com.termux.app.activities.ThirdPartyCenterActivity$ThirdPartyResource { *; }
-keep class com.termux.app.plugin.PluginLoader$InstallRecord { *; }

# 插件清单 PluginManifest / 插件 Compose JSON DSL ComposeUiNode 均为整棵嵌套结构，
# 嵌套类型必须显式保留（否则会被 shrink 删除成员类型）。
-keep class com.termux.app.plugin.PluginManifest { *; }
-keep class com.termux.app.plugin.PluginManifest$* { *; }
-keep class com.termux.app.plugin.ComposeUiNode { *; }
-keep class com.termux.app.plugin.ComposeUiNode$* { *; }

# --- Gson 在 Android 上的 Unsafe 实例化路径（Kotlin data class 兜底）----------
# Gson 的 ConstructorConstructor 找不到 Kotlin data class 的无参构造函数时，
# 会 fallback 到 UnsafeAllocator：Class.forName("sun.misc.Unsafe") → getField("theUnsafe")
# → allocateInstance(cls)。R8 看不到 Gson 内部这条字符串反射链，会把 Unsafe 的可调用
# 方法当作无用代码裁掉，导致 release 下 Gson 报 "Abstract classes can't be instantiated"。
# （debug 下没这问题，因为没开启 R8。）
-keep class sun.misc.Unsafe { *; }
-keep class dalvik.system.VMRuntime { *; }
# Gson 内部执行 Unsafe 反射的核心类也整体保留，避免 R8 做激进方法内联 / 重写
-keep class com.google.gson.internal.UnsafeAllocator { *; }
-keep class com.google.gson.internal.ConstructorConstructor { *; }

# ===========================================================================
#  4. Class.forName / getMethod —— 按字符串加载的类
# ===========================================================================
# 字符串类名 R8 分析不到，类被裁掉后反射直接 ClassNotFoundException。
# TerminalRuntimeCore 启动服务
-keep class com.termux.app.TermuxService { *; }
# CrashHandler 弹崩溃对话框 / 写日志
-keep class com.termux.app.activities.AlertDialogActivity { *; }
-keep class com.termux.app.utils.LogManager { *; }
# NavigationHelper 用 getDeclaredConstructor(Function0, Function1) 反射构造
-keep class androidx.navigationevent.NavigationEventDispatcher { *; }
-keep class kotlin.jvm.functions.Function0 { *; }
-keep class kotlin.jvm.functions.Function1 { *; }

# ===========================================================================
#  5. WebView JS Bridge
# ===========================================================================
# JS 侧按方法名调用，方法被裁掉后插件页面直接失效。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ===========================================================================
#  6. Android 框架约定入口
# ===========================================================================
# Bundle 读写 Parcelable 靠静态 CREATOR 字段反射
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
# VirtualKey / SkillType / PluginState 等靠 valueOf(字符串) 从 prefs 还原；
# values() 与 valueOf() 是编译器合成方法，R8 默认会当作无用代码删掉。
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ===========================================================================
#  7. Room
# ===========================================================================
# Room 用 Class.forName("<Database 类名>_Impl") 反射加载生成实现，
# AutoMigrationSpec 同理；ServerProfile 是唯一 entity。
-keep class com.gaurav.avnc.model.db.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * implements androidx.room.migration.AutoMigrationSpec { *; }

# ===========================================================================
#  8. kotlinx-serialization —— 编译期生成的 serializer 类
# ===========================================================================
# kotlinx-serialization-json 为每个 @Serializable 类在编译期生成一个
# <Class>$serializer 内部类（Kotlin 1.7+ 起叫 <Class>$$serializer），
# 运行时由 Json 引擎通过 generatedSerializer() 或 SerializersModule 查找。
# R8 看不到这种「间接注册」路径，会把 serializer 当成无引用类裁掉 → release 下
#     SerializationException: Serializer for class 'Xxx' is not found.
# 来源：https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serialization-and-code-minification.md
# 涉及的 @Serializable 类：
#   QuickCommandStore.QuickCommand / QuickCommandGroup
#   AgentScriptJudge.JudgeHistoryEntry / AgentScriptJudgeResult
#   PrefsViewModel (嵌套)
#   ServerProfile (com.gaurav.avnc.model)

# Signature 是 kotlinx.serialization 还原泛型的关键（同 Gson）
# Annotation 保留 @Serializable / @SerialName 等标记
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# kotlinx.serialization 运行时本身 —— 内部有 SerializersModule 反射路径
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# 保留编译器生成的 serializer 内部类（命名：Outer$$serializer 或 Outer$serializer）
# 这是 Json.encodeToString/decodeFromString 在没有显式 SerializersModule 时
# 通过 generatedSerializer() fallback 找到 serializer 的唯一入口
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class **$serializer { *; }

# 保留 companion object 的 serializer() 方法 —— kotlinx.serialization 也会
# 按 "serializer" 方法名反射查找
-keepclassmembers class ** {
    public static kotlinx.serialization.KSerializer serializer(...);
}

# ===========================================================================
#  9. 既有规则（保留） -------------------------------------------------------
# ===========================================================================
# tink 引用了依赖图中不存在的 protobuf
# sshlib 带来的 JVM 版 tink 1.20.0 依赖 com.google.protobuf，而 protobuf 并未进入
# 本项目依赖图。security-crypto 只用到 tink 的 Aead 接口，protobuf 相关分支运行时
# 不会被触达（即便触达，GitHubSessionStore 也有明文 prefs 兜底）。
# 缺这条 R8 会把 missing class 判定为错误并中断构建。
-dontwarn com.google.protobuf.**

# Temp fix for androidx.window:window:1.0.0-alpha09 imported by termux-shared
# https://issuetracker.google.com/issues/189001730
# https://android-review.googlesource.com/c/platform/frameworks/support/+/1757630
-keep class androidx.window.** { *; }
