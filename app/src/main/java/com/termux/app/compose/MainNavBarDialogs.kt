package com.termux.app.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import top.yukonga.miuix.kmp.layout.BottomSheetDefaults
import top.yukonga.miuix.kmp.layout.DialogDefaults

/**
 * 主页用的弹窗转发 —— 签名与 miuix 同名函数完全一致，主页页面只需把 import 指向这里。
 *
 * miuix 的弹层不知道主页底栏的存在，这里在 `show` 为 true 的整段时间里登记一次隐藏，交给
 * [mainNavBarSlide] 做下沉 / 上浮。放在转发里而不是逐个调用点包一层，是为了让之后新增的弹
 * 窗不可能漏掉这一行为。
 */

@Composable
fun OverlayDialog(
    show: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleColor: Color = DialogDefaults.titleColor(),
    summary: String? = null,
    summaryColor: Color = DialogDefaults.summaryColor(),
    backgroundColor: Color = DialogDefaults.backgroundColor(),
    enableWindowDim: Boolean = true,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    outsideMargin: DpSize = DialogDefaults.outsideMargin,
    insideMargin: DpSize = DialogDefaults.insideMargin,
    defaultWindowInsetsPadding: Boolean = true,
    renderInRootScaffold: Boolean = true,
    maxWidth: Dp = DialogDefaults.MaxWidth,
    largeScreen: Boolean? = null,
    cornerRadius: Dp? = null,
    content: @Composable () -> Unit,
) {
    MainNavBarHideEffect(show)
    // 超级柔光：走玻璃弹层；默认样式（或没有取景层）原样走 miuix，连参数都不动。
    val uiStyle by UiStylePrefs.uiStyle.collectAsState()
    // 主页取景层优先；子页面/独立 Activity 没有它，但玻璃顶栏自己有一份页面取景层（
    // LocalGlassTopAppBarBackdrop），弹层直接复用 —— 这样 48 个子页面的弹窗无需逐个改造。
    val glassBackdrop = LocalHyperGlassBackdrop.current ?: LocalGlassTopAppBarBackdrop.current
    if (uiStyle is HyperGlassStyle.Soft && glassBackdrop != null) {
        HyperGlassDialog(
            show = show,
            style = uiStyle,
            backdrop = glassBackdrop,
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
            cornerRadius = cornerRadius ?: DialogDefaults.CornerRadius,
            content = content,
        )
        return
    }
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
        outsideMargin = outsideMargin,
        insideMargin = insideMargin,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        renderInRootScaffold = renderInRootScaffold,
        maxWidth = maxWidth,
        largeScreen = largeScreen,
        cornerRadius = cornerRadius,
        content = content,
    )
}

@Composable
fun WindowDialog(
    show: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleColor: Color = DialogDefaults.titleColor(),
    summary: String? = null,
    summaryColor: Color = DialogDefaults.summaryColor(),
    backgroundColor: Color = DialogDefaults.backgroundColor(),
    enableWindowDim: Boolean = true,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    outsideMargin: DpSize = DialogDefaults.outsideMargin,
    insideMargin: DpSize = DialogDefaults.insideMargin,
    defaultWindowInsetsPadding: Boolean = true,
    maxWidth: Dp = DialogDefaults.MaxWidth,
    largeScreen: Boolean? = null,
    cornerRadius: Dp? = null,
    content: @Composable () -> Unit,
) {
    MainNavBarHideEffect(show)
    top.yukonga.miuix.kmp.window.WindowDialog(
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
        outsideMargin = outsideMargin,
        insideMargin = insideMargin,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        maxWidth = maxWidth,
        largeScreen = largeScreen,
        cornerRadius = cornerRadius,
        content = content,
    )
}

@Composable
fun OverlayBottomSheet(
    show: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
    startAction: @Composable (() -> Unit)? = null,
    endAction: @Composable (() -> Unit)? = null,
    backgroundColor: Color = BottomSheetDefaults.backgroundColor(),
    enableWindowDim: Boolean = true,
    cornerRadius: Dp = BottomSheetDefaults.cornerRadius,
    sheetMaxWidth: Dp = BottomSheetDefaults.maxWidth,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    outsideMargin: DpSize = BottomSheetDefaults.outsideMargin,
    insideMargin: DpSize = BottomSheetDefaults.insideMargin,
    defaultWindowInsetsPadding: Boolean = true,
    dragHandleColor: Color = BottomSheetDefaults.dragHandleColor(),
    allowDismiss: Boolean = true,
    enableNestedScroll: Boolean = true,
    renderInRootScaffold: Boolean = true,
    glassEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    MainNavBarHideEffect(show)
    // 超级柔光：底部弹层同样玻璃化；默认样式（或没有取景层）原样走 miuix。
    val sheetStyle by UiStylePrefs.uiStyle.collectAsState()
    val sheetBackdrop = LocalHyperGlassBackdrop.current ?: LocalGlassTopAppBarBackdrop.current
    if (glassEnabled && sheetStyle is HyperGlassStyle.Soft && sheetBackdrop != null) {
        HyperGlassBottomSheet(
            show = show,
            style = sheetStyle,
            backdrop = sheetBackdrop,
            title = title,
            cornerRadius = cornerRadius,
            dragHandleColor = dragHandleColor,
            onDismissRequest = onDismissRequest,
            onDismissFinished = onDismissFinished,
            content = content,
        )
        return
    }
    top.yukonga.miuix.kmp.overlay.OverlayBottomSheet(
        show = show,
        modifier = modifier,
        title = title,
        startAction = startAction,
        endAction = endAction,
        backgroundColor = backgroundColor,
        enableWindowDim = enableWindowDim,
        cornerRadius = cornerRadius,
        sheetMaxWidth = sheetMaxWidth,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        outsideMargin = outsideMargin,
        insideMargin = insideMargin,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        dragHandleColor = dragHandleColor,
        allowDismiss = allowDismiss,
        enableNestedScroll = enableNestedScroll,
        renderInRootScaffold = renderInRootScaffold,
        content = content,
    )
}
