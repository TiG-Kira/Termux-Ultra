package com.termux.app.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDefaults
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassOverlayDefaults
import top.yukonga.miuix.kmp.glass.GlassShape
import top.yukonga.miuix.kmp.glass.GlassShadows
import top.yukonga.miuix.kmp.glass.GlassStrokes
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils

/**
 * 全局 UI 外观样式偏好（设置-外观组）。
 *
 * 与 navigation_bar_style 相互独立：不同 key、不同设置项，互不覆盖。
 * 写盘同时推 StateFlow，页面下一次重组即应用新样式。
 */
object UiStylePrefs {

    private const val PREFS_NAME = "app_settings"
    private const val KEY_UI_STYLE = "ui_appearance_style"

    const val VALUE_DEFAULT = "default"
    const val VALUE_SOFT = "soft"

    private val _uiStyle = MutableStateFlow<HyperGlassStyle>(HyperGlassStyle.Default)
    val uiStyle: StateFlow<HyperGlassStyle> = _uiStyle.asStateFlow()

    @Volatile
    private var loaded = false

    /** 从磁盘加载；幂等。必须在首次读取 StateFlow 前调用。 */
    @Synchronized
    fun init(context: android.content.Context) {
        if (loaded) return
        _uiStyle.value = styleOf(
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getString(KEY_UI_STYLE, VALUE_DEFAULT)
        )
        loaded = true
    }

    @Synchronized
    fun setUiStyle(context: android.content.Context, style: HyperGlassStyle) {
        _uiStyle.value = style
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(
                KEY_UI_STYLE,
                when (style) {
                    is HyperGlassStyle.Soft -> VALUE_SOFT
                    HyperGlassStyle.Default -> VALUE_DEFAULT
                }
            )
            .apply()
    }

    /** 历史值 "liquid" 一并迁到柔光，避免升级后落回默认。 */
    fun styleOf(raw: String?): HyperGlassStyle = when (raw) {
        VALUE_SOFT, "liquid" -> HyperGlassStyle.Soft()
        else -> HyperGlassStyle.Default
    }
}

/**
 * UI 外观样式：默认（现有 UI）/ 超级柔光。
 *
 * @param cornerRadius 分组卡片圆角：柔光样式用连续曲率的大圆角
 */
sealed interface HyperGlassStyle {

    /** 默认：完全沿用现有 UI。 */
    data object Default : HyperGlassStyle

    /** 超级柔光：分组卡大圆角 + 弹层玻璃化。 */
    data class Soft(
        val cornerRadius: Dp = 26.dp
    ) : HyperGlassStyle
}

/** 内容根的取景层：玻璃弹层靠它拿到身背后的页面内容。 */
val LocalHyperGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 把当前 UI 样式同步到只能在组合期读取的静态 token（顶栏渐进模糊等）。
 *
 * 必须在界面建立之前调用（Application.onCreate）；样式切换后由重启流程重新走一遍。
 */
fun applyUiStyleTokens(context: android.content.Context) {
    UiStylePrefs.init(context)
    applyUiStyleTokens(UiStylePrefs.uiStyle.value)
}

/**
 * 按给定样式同步顶栏 token。
 *
 * 必须在组合期调用（页面里 collected state 变化时立刻同步）：切换样式只重启 Activity
 * 不重启进程，只靠 Application.onCreate 会导致第二次切换失效。
 */
fun applyUiStyleTokens(style: HyperGlassStyle) {
    val soft = style is HyperGlassStyle.Soft
    // 顶栏：柔光 = 真模糊 + 白板整体上移；默认 = 不模糊、底板原位
    GlassTopAppBarDefaults.BandBlurRadius = if (soft) 20.dp else 0.dp
    GlassTopAppBarDefaults.BandScrimOffset = if (soft) 24.dp else 0.dp
}

/**
 * 弹层统一入口（主页转发层 OverlayDialog 调用）。
 *
 * 默认样式或拿不到取景层时原样回落 miuix 弹窗，行为与改动前完全一致；
 * 柔光样式下渲染为玻璃弹层：真实背景模糊 + 边缘高光 + Scrim + 自下而上滑入。
 *
 * 关键约束：玻璃弹层必须渲染在取景层记录的子树**之外**，否则背景捕获会成环。
 * 这里借 [MiuixPopupUtils.DialogLayout] 把它送进根 Scaffold 的 overlay 容器，
 * 与取景层所在的内容 Box 互不包含。
 */
@Composable
fun HyperGlassDialog(
    show: Boolean,
    style: HyperGlassStyle,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleColor: Color = DialogDefaults.titleColor(),
    summary: String? = null,
    summaryColor: Color = DialogDefaults.summaryColor(),
    backgroundColor: Color = DialogDefaults.backgroundColor(),
    enableWindowDim: Boolean = true,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    maxWidth: Dp = DialogDefaults.MaxWidth,
    cornerRadius: Dp = DialogDefaults.CornerRadius,
    content: @Composable () -> Unit,
) {
    if (style == HyperGlassStyle.Default || backdrop == null) {
        top.yukonga.miuix.kmp.overlay.OverlayDialog(
            show = show,
            modifier = modifier,
            title = title,
            titleColor = titleColor,
            summary = summary,
            summaryColor = summaryColor,
            backgroundColor = backgroundColor,
            enableWindowDim = enableWindowDim,
            onDismissRequest = onDismissRequest,
            onDismissFinished = onDismissFinished,
            maxWidth = maxWidth,
            cornerRadius = cornerRadius,
            content = content,
        )
        return
    }

    BackHandler(enabled = show) { onDismissRequest?.invoke() }

    // 宿主可见性独立于 show：show=false 先让玻璃弹层播完退场动画
    // （onDismissFinished）再卸载 DialogLayout 容器，否则退场被直接掉。
    var hostVisible by remember { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (show) hostVisible = true
    }
    if (!show && !hostVisible) return

    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f

    MiuixPopupUtils.DialogLayout(
        visible = remember { mutableStateOf(false) }.also { it.value = hostVisible },
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
        enableWindowDim = false,
        enableAutoLargeScreen = false,
        renderInRootScaffold = true,
    ) {
        GlassDialog(
            visible = show,
            onDismissRequest = { onDismissRequest?.invoke() },
            backdrop = backdrop,
            maxWidth = maxWidth,
            scrimAlpha = if (isDark) {
                GlassOverlayDefaults.ScrimAlphaDark
            } else {
                GlassOverlayDefaults.ScrimAlphaLight
            },
            style = GlassDefaults.style(isDark),
            shape = GlassShape(cornerRadius),
            stroke = if (isDark) GlassStrokes.BigDark else GlassStrokes.BigLight,
            shadow = GlassShadows.ExtraHigh,
            onDismissFinished = {
                onDismissFinished?.invoke()
                hostVisible = false
            },
        ) {
            Column(modifier = Modifier.widthIn(max = maxWidth)) {
                if (title != null) {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        text = title,
                        fontSize = MiuixTheme.textStyles.title4.fontSize,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        color = titleColor,
                    )
                }
                if (summary != null) {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        text = summary,
                        style = MiuixTheme.textStyles.body1,
                        textAlign = TextAlign.Center,
                        color = summaryColor,
                    )
                }
                content()
            }
        }
    }
}

/**
 * 底部弹层的玻璃版本（柔光样式）。
 *
 * 与 miuix [OverlayBottomSheet] 同职责：底部贴边、可下滑关闭、带标题与操作位。
 * 这里只做视觉替换，交互（点遮罩 / 返回键）保持不变；拖拽把手保留但仅视觉。
 */
@Composable
fun HyperGlassBottomSheet(
    show: Boolean,
    style: HyperGlassStyle,
    backdrop: Backdrop?,
    title: String? = null,
    cornerRadius: Dp = 28.dp,
    dragHandleColor: Color = MiuixTheme.colorScheme.onSurfaceVariantActions,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (style == HyperGlassStyle.Default || backdrop == null) {
        top.yukonga.miuix.kmp.overlay.OverlayBottomSheet(
            show = show,
            title = title,
            onDismissRequest = onDismissRequest,
            onDismissFinished = onDismissFinished,
            content = content,
        )
        return
    }

    BackHandler(enabled = show) { onDismissRequest?.invoke() }

    // 宿主可见性独立于 show：show=false 先让玻璃弹层播完退场动画
    // （onDismissFinished）再卸载 DialogLayout 容器，否则退场被直接掉。
    var hostVisible by remember { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (show) hostVisible = true
    }
    if (!show && !hostVisible) return

    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f

    MiuixPopupUtils.DialogLayout(
        visible = remember { mutableStateOf(false) }.also { it.value = hostVisible },
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
        enableWindowDim = false,
        enableAutoLargeScreen = false,
        renderInRootScaffold = true,
    ) {
        GlassDialog(
            visible = show,
            onDismissRequest = { onDismissRequest?.invoke() },
            backdrop = backdrop,
            // 弹层要通栏，宽度上限放到屏幕之外
            maxWidth = 2000.dp,
            scrimAlpha = if (isDark) {
                GlassOverlayDefaults.ScrimAlphaDark
            } else {
                GlassOverlayDefaults.ScrimAlphaLight
            },
            style = GlassDefaults.style(isDark),
            shape = GlassShape(cornerRadius),
            stroke = if (isDark) GlassStrokes.BigDark else GlassStrokes.BigLight,
            shadow = GlassShadows.ExtraHigh,
            onDismissFinished = {
                onDismissFinished?.invoke()
                hostVisible = false
            },
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(dragHandleColor, RoundedCornerShape(2.dp))
            )
            if (title != null) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 8.dp),
                    text = title,
                    fontSize = MiuixTheme.textStyles.title4.fontSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
            content()
        }
    }
}

/**
 * 设置页的分组卡片。
 *
 * 柔光样式下换成参考实现的分组几何：更大圆角（连续曲率），外边距由调用点 padding 控制；
 * 默认样式原样交给 miuix Card，现有一切行为不变。
 *
 * 注意：分组卡本身**不做玻璃**——同屏卡片数量多，逐卡重玻璃会撞上渲染线程上限。
 */
@Composable
fun HyperGlassCard(
    style: HyperGlassStyle,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val cornerRadius = when (style) {
        is HyperGlassStyle.Soft -> style.cornerRadius
        HyperGlassStyle.Default -> DefaultCardCornerRadius
    }
    Card(modifier = modifier, cornerRadius = cornerRadius) { content() }
}

/** 与 miuix Card 默认圆角一致的兜底值。 */
private val DefaultCardCornerRadius: Dp = 16.dp
