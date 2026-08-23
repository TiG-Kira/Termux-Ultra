# Termux Ultra UI 修改指南

## 概述

本文档记录了对 Termux Ultra 应用的 4 项 UI/功能修改，涉及 2 个文件。

---

## 文件 1: PluginWebViewActivity.kt

**路径**: `app/src/main/java/com/termux/app/plugin/PluginWebViewActivity.kt`

### 修改点 1: 添加 MiuixIcons 导入（第 34 行）

```kotlin
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back  // 必须! MiuixIcons.Back 依赖此导入
```

### 修改点 2: 返回按钮统一为 MiuixIcons.Back（第 142-156 行）

替换原来的 IconButton + ArrowBack 为 Box + MiuixIcons.Back:

```kotlin
navigationIcon = {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable { onBack() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = MiuixIcons.Back,
            contentDescription = "返回",
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
    }
}
```

### 修改点 3: WebView 透明背景（第 169 行）

```kotlin
setBackgroundColor(0)
```

### 修改点 4: 添加 onPageFinished 回调（第 202-205 行）

在 webViewClient 对象内添加:

```kotlin
override fun onPageFinished(view: WebView?, url: String?) {
    super.onPageFinished(view, url)
    view?.injectBackgroundFix()
}
```

### 修改点 5: 添加 injectBackgroundFix 函数（第 246-261 行）

在类末尾（inner class PluginJsBridge 之前）添加:

```kotlin
private fun WebView.injectBackgroundFix() {
    val js = """
        (function() {
            var style = document.createElement('style');
            style.textContent = 'html,body{margin:0;padding:0;height:100%}html{background:transparent!important}';
            document.head.appendChild(style);
            if(document.body && document.body.style && !document.body.style.background) {
                var computed = window.getComputedStyle(document.body);
                if(computed && computed.backgroundImage && computed.backgroundImage !== 'none') {
                    document.documentElement.style.background = computed.backgroundImage;
                }
            }
        })();
    """.trimIndent()
    evaluateJavascript(js, null)
}
```

### 添加的导入

```kotlin
import androidx.compose.foundation.clickable          // 第 16 行
import androidx.compose.foundation.layout.size      // 第 20 行
import androidx.compose.ui.draw.clip              // 第 27 行
```

---

## 文件 2: PluginCenterActivity.kt

**路径**: `app/src/main/java/com/termux/app/plugin/PluginCenterActivity.kt`

### 修改点 1: 替换图标导入（第 23-24 行）

替换 ArrowForward 导入为:

```kotlin
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
```

### 修改点 2: 替换图标使用（第 509 行）

替换 `Icons.AutoMirrored.Rounded.ArrowForward` 为:

```kotlin
Icons.Rounded.Info
```

### 修改点 3: PluginItemCard 添加 onOpenH5Home 参数（第 329 行）

函数签名添加:

```kotlin
private fun PluginItemCard(
    ...
    onViewContent: () -> Unit,
    onOpenH5Home: () -> Unit    // 新增参数
)
```

### 修改点 4: PluginItemCard 调用处添加回调（第 277-287 行）

在 onViewContent 后面添加:

```kotlin
onOpenH5Home = {
    val h5Home = plugin.manifest.entryPoints?.h5Home
    if (h5Home?.enabled == true) {
        PluginWebViewActivity.start(
            context = context,
            pluginId = plugin.id,
            entryPath = h5Home.entry,
            title = h5Home.title ?: plugin.manifest.name
        )
    }
}
```

### 修改点 5: PluginItemCard 添加 hasH5Home 变量（第 334 行）

在 val isDark 后面添加:

```kotlin
val hasH5Home = plugin.manifest.entryPoints?.h5Home?.enabled == true
```

### 修改点 6: 按钮行添加上侧"插件主页"按钮（第 473-494 行）

在按钮行 Row 内，Info 按钮组之前添加:

```kotlin
Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    if (hasH5Home) {
        Button(
            onClick = onOpenH5Home,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)),
            colors = ButtonDefaults.buttonColors(
                color = if (isDark) Color(0xFF424242) else Color(0xFFE0E0E0)
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.Home,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = onSurface
            )
            Text(
                text = "插件主页",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = onSurface
            )
        }
    }
}
```

---

## 编译命令

```powershell
cd D:\KiTerminal-UX
.\gradlew.bat :app:assembleDebug
```

## APK 输出路径

```
D:\KiTerminal-UX\app\build\outputs\apk\debug\termux-ultra_debug_universal.apk
```

## 修改效果验证清单

- [ ] H5 Viewer 页面背景正确（紫色渐变，非黑屏）
- [ ] WebView 返回按钮使用 MiuixIcons.Back，样式与其他页面一致
- [ ] 插件中心卡片右箭头变为信息图标（Info）
- [ ] 有 H5 主页的插件卡片左侧显示"插件主页"按钮
- [ ] 点击"插件主页"按钮跳转到对应的 H5 页面

## 验证记录 (2026-08-24)

- [x] 两个文件修改已确认写入磁盘
- [x] `:app:compileDebugKotlin` BUILD SUCCESSFUL
- [x] `:app:assembleDebug` BUILD SUCCESSFUL
- [x] APK 内确认包含 `插件主页` (classes21.dex) 和 `injectBackgroundFix` (classes21.dex)
- APK: `D:\KiTerminal-UX\app\build\outputs\apk\debug\termux-ultra_debug_universal.apk` (180.88 MB)

> 注意: 之前的失败是因为 Android Studio/FileSyncHelper 同步回滚 + 编译时 MiuixIcons.Back 缺 extended.Back 导入。本次通过 Python 脚本直接写文件 + 添加扩展导入解决。

## 注意事项

1. **路径**: 项目路径 `D:\KiTerminal-UX` 是 Junction，指向 `D:\Local Datas\Sources\KiTerminal-UX`
2. **NDK**: 使用无空格路径 `D:\Android-NDK`（已创建的 Junction）
3. **编码**: 文件使用 UTF-8 无 BOM，CRLF 换行符
4. **不要让 Android Studio 同步覆盖文件**（如果 Android Studio 正在运行，先关闭它）