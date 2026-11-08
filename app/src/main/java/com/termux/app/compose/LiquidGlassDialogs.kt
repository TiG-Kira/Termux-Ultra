package com.termux.app.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.termux.app.compose.liquid.LiquidDialogMotion
import com.termux.app.compose.liquid.LiquidGlassDialog
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils

/**
 * 「液态玻璃」外观样式的弹层宿主。
 *
 * 材质用 kyant `Backdrop`（[LiquidGlassDialog]），排版沿用 miuix 对话框规范
 * （居中标题 / 说明、宽度上限），并借 [MiuixPopupUtils.DialogLayout] 送进根 Scaffold
 * 的 overlay 容器——弹层必须渲染在内容取景层记录的子树之外，否则背景捕获成环。
 *
 * [LiquidGlassDialog] 自己播退场动画，宿主可见性必须比 show 多留一段时间，
 * 否则容器先卸载会把退场整个砍掉。
 */
@Composable
private fun LiquidDialogHost(
    show: Boolean,
    onDismissRequest: (() -> Unit)?,
    onDismissFinished: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    BackHandler(enabled = show) { onDismissRequest?.invoke() }

    var hostVisible by remember { mutableStateOf(false) }
    if (!show && !hostVisible) return
    LaunchedEffect(show) {
        if (show) {
            hostVisible = true
        } else {
            // LiquidGlassDialog 没有退出完成回调：animVisible 延迟一帧 + slideOut/scaleOut
            // tween(220) + scrim tween(200)，合计约 236ms。留出充足余量（低端机掉帧、
            // 后续调大 tween 都不会把退场砍掉），再卸载容器并通知调用方。
            delay(400L)
            onDismissFinished?.invoke()
            hostVisible = false
        }
    }

    MiuixPopupUtils.DialogLayout(
        visible = remember { mutableStateOf(false) }.also { it.value = hostVisible },
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
        enableWindowDim = false,
        enableAutoLargeScreen = false,
        renderInRootScaffold = true,
    ) {
        content()
    }
}

/** 液态玻璃对话框（落位与动效对齐柔光样式的 miuix GlassDialog：底部居中、上滑 1/4 进场）。 */
@Composable
fun HyperLiquidDialog(
    show: Boolean,
    backdrop: Backdrop,
    title: String? = null,
    titleColor: Color = DialogDefaults.titleColor(),
    summary: String? = null,
    summaryColor: Color = DialogDefaults.summaryColor(),
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    maxWidth: Dp = DialogDefaults.MaxWidth,
    cornerRadius: Dp? = null,
    content: @Composable () -> Unit,
) {
    // 与 miuix 对话框同源：显式指定优先，否则按屏幕圆角推导（贴底呈现那一档）。
    val dialogCornerRadius = cornerRadius ?: miuixDialogCornerRadius()
    LiquidDialogHost(show, onDismissRequest, onDismissFinished) {
        LiquidGlassDialog(
            visible = show,
            onDismissRequest = { onDismissRequest?.invoke() },
            backdrop = backdrop,
            alignment = Alignment.BottomCenter,
            cornerRadius = dialogCornerRadius,
            motion = LiquidDialogMotion.Dialog,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
            ) {
                DialogHeading(title, titleColor, summary, summaryColor)
                content()
            }
        }
    }
}

/** 液态玻璃底部弹层（底部贴边、自下而上滑入）。 */
@Composable
fun HyperLiquidBottomSheet(
    show: Boolean,
    backdrop: Backdrop,
    title: String? = null,
    cornerRadius: Dp = 28.dp,
    dragHandleColor: Color = MiuixTheme.colorScheme.onSurfaceVariantActions,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    LiquidDialogHost(show, onDismissRequest, onDismissFinished) {
        LiquidGlassDialog(
            visible = show,
            onDismissRequest = { onDismissRequest?.invoke() },
            backdrop = backdrop,
            alignment = Alignment.BottomCenter,
            cornerRadius = cornerRadius,
        ) {
            // 底弹要通栏：Kiba 的 LiquidGlassDialog 内部 Column 是 wrap content，
            // 撑满后宽度上限仍放到屏幕之外（与 HyperGlassBottomSheet 口径一致）。
            Column(modifier = Modifier.fillMaxWidth().widthIn(max = 2000.dp)) {
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
}

@Composable
private fun DialogHeading(
    title: String?,
    titleColor: Color,
    summary: String?,
    summaryColor: Color,
) {
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
}
