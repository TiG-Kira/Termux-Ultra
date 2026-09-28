# ReDex 安全网：保护动态引用点，避免因 InterDex 重排 / 任何意外移除导致运行时崩溃。
# 仅保留，不做混淆（与 R8 关闭策略一致：dontobfuscate）。
# 注意：当前 redex-config.json 未启用 RemoveUnused* / Obfuscate 等移除类 Pass，
# 故本 keep 规则当前为 inert（仅作文档与未来兜底）。保留以防后续有人开启移除 Pass。
-keep class com.termux.app.TermuxService { *; }
-keepclassmembers class com.termux.** { <fields>; }
-keep class com.termux.app.plugin.PluginManifest { *; }
-keep class com.termux.app.plugin.PluginManifest$* { *; }
-keep class com.termux.app.plugin.ComposeUiNode { *; }
-keep class com.termux.app.plugin.ComposeUiNode$* { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.termux.shared.crash.** { *; }
-keep class com.awkoo.libterminal.** { *; }
-keep class androidx.window.** { *; }
