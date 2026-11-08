// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT
//
// 移植自 Kiba（TiG-Kira/Kiba, ui/settings/AboutEffects.kt），原始设计仿 HyperIsland 关于页。

package com.termux.app.compose

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.Text

/**
 * 关于页 / OOBE 共用的动效层（仿 HyperIsland 关于页）。
 *
 * 三块内容：
 *  1. [AboutGradientBackground]：动态变色背景 —— 线性底色 + 4 个正弦漂移的径向光斑，
 *     调色板每 [COLOR_INTERPOLATION_SECONDS] 秒以 smoothstep 交叉过渡；
 *  2. [AboutGradientLogo]：Logo 特效 —— 用图标 alpha 作蒙版，把流动的渐变"灌进" Logo 形状，
 *     外层再叠一圈随呼吸缩放的径向辉光；
 *  3. [GradientWordmark]：文字特效 —— 同一个渐变时钟驱动的渐变字 + 扫光。
 *
 * 性能约定：
 * - 时间轴是 [State]，**只在 draw 阶段读取**，逐帧只失效绘制，不触发重组；
 * - 调色板插值每帧只算 4 个颜色，不做 bitmap / shader 重建；
 * - 时间轴随页面组合存活（页面出栈即销毁，不会在后台空转）。
 *
 * 本仓替代了旧的 `AboutBgEffect`（AndroidView + RuntimeShader + 120dp blur）：
 * 那套既贵又只能当背景，现在整页共用一个时间轴，背景 / Logo / 字标吃同一组调色板。
 */

private const val COLOR_INTERPOLATION_SECONDS = 12f

/** 背景光斑漂移速度（时间为秒，乘该系数后驱动三角/正弦函数）。 */
private const val BACKGROUND_SPEED = 0.12f

/** 亮色三套调色板（不透明）。 */
private val LightPalettes: List<List<Color>> = listOf(
    listOf(Color(0xFFFFE6F0), Color(0xFFFFD6E3), Color(0xFFF7BAD1), Color(0xFFA3A6FA)),
    listOf(Color(0xFF94BDFF), Color(0xFFFFE6ED), Color(0xFFBCC2FF), Color(0xFFF7C4D6)),
    listOf(Color(0xFFFADBE6), Color(0xFF99BAFA), Color(0xFFEBEDFF), Color(0xFF8FB0FF)),
)

/** 暗色三套调色板（带 alpha，叠在深色底上形成柔光）。 */
private val DarkPalettes: List<List<Color>> = listOf(
    listOf(
        Color(0.20f, 0.06f, 0.88f, 0.40f),
        Color(0.30f, 0.14f, 0.55f, 0.50f),
        Color(0f, 0.64f, 0.96f, 0.50f),
        Color(0.11f, 0.16f, 0.83f, 0.40f),
    ),
    listOf(
        Color(0.07f, 0.15f, 0.79f, 0.50f),
        Color(0.62f, 0.21f, 0.67f, 0.50f),
        Color(0.06f, 0.25f, 0.84f, 0.50f),
        Color(0f, 0.20f, 0.78f, 0.50f),
    ),
    listOf(
        Color(0.58f, 0.30f, 0.74f, 0.40f),
        Color(0.27f, 0.18f, 0.60f, 0.50f),
        Color(0.66f, 0.26f, 0.62f, 0.50f),
        Color(0.12f, 0.16f, 0.70f, 0.60f),
    ),
)

/**
 * 页面动画时钟：逐帧累加真实时间（秒）。
 *
 * 返回值刻意做成 [State] 而不是 `Float` —— 调用方在 draw lambda 里 `.value`，
 * 这样每帧只让绘制失效，整个页面不会每帧重组。
 *
 * @param running 页面不可见时传 false，时钟停摆（不 `withFrameNanos` 空转）。
 */
@Composable
fun rememberAboutAnimationTime(running: Boolean = true): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var previous = 0L
        while (true) {
            withFrameNanos { now ->
                if (previous != 0L) {
                    time.floatValue += (now - previous) / 1_000_000_000f
                }
                previous = now
            }
        }
    }
    return time
}

/** 当前时刻的 4 色调色板：12 秒一个循环，段间用 smoothstep 过渡，序列 P1→P0→P1→P2→P1。 */
private fun aboutGradientColors(animationTime: Float, dark: Boolean): List<Color> {
    val palettes = if (dark) DarkPalettes else LightPalettes
    val segmentValue = animationTime / COLOR_INTERPOLATION_SECONDS
    val segment = floor(segmentValue).toInt() % 4
    val raw = segmentValue - floor(segmentValue)
    val progress = raw * raw * (3f - 2f * raw)
    val start = when (segment) {
        0 -> palettes[1]
        1 -> palettes[0]
        2 -> palettes[1]
        else -> palettes[2]
    }
    val end = when (segment) {
        0 -> palettes[0]
        1 -> palettes[1]
        2 -> palettes[2]
        else -> palettes[1]
    }
    return start.indices.map { i -> lerpColor(start[i], end[i], progress) }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

/**
 * 前景（Logo / 字标）用的"墨水色"：同一组调色板，但亮色主题下压深、暗色主题下提亮。
 *
 * 直接用背景调色板画前景会"同色叠同色"——亮色下 Logo 只剩一团浅粉、
 * 暗色下（背景本来就是深蓝紫）Logo 线条几乎彻底看不见。所以两个方向都要拉明度差：
 * - 亮色：乘低亮度系数，保持色相、压深；
 * - 暗色：朝白色混 [DARK_INK_WHITEN]（并给每通道留一个亮度下限），
 *   得到明快的粉彩色，在深蓝背景上是清晰的"发光描线"。
 */
private const val DARK_INK_WHITEN = 0.58f

private fun aboutInkColor(color: Color, dark: Boolean): Color = if (dark) {
    fun lift(c: Float) = (c + (1f - c) * DARK_INK_WHITEN).coerceIn(0f, 1f)
    Color(lift(color.red), lift(color.green), lift(color.blue), 1f)
} else {
    Color(color.red * 0.46f, color.green * 0.42f, color.blue * 0.64f, 1f)
}

/** 围绕灰度均值放大饱和度（×1.18）并压暗一点，让渐变更"有色"但不刺眼。 */
private fun strengthenAboutColor(color: Color): Color {
    val average = (color.red + color.green + color.blue) / 3f
    fun adjust(c: Float) = ((c - average) * 1.18f + average - 0.015f).coerceIn(0f, 1f)
    return Color(adjust(color.red), adjust(color.green), adjust(color.blue), color.alpha)
}

/**
 * 动态变色背景。
 *
 * @param animationTime 由 [rememberAboutAnimationTime] 提供（draw 阶段读取）。
 * @param dark 暗色主题：改用半透明深色调色板。
 * @param alpha 整体不透明度（关于页随头部滚出视口淡出）。做成 lambda 是为了让
 *   滚动回调在 graphicsLayer 里读，而不是把新值当参数传进来触发整页重组。
 */
@Composable
fun AboutGradientBackground(
    animationTime: State<Float>,
    dark: Boolean,
    modifier: Modifier = Modifier,
    alpha: () -> Float = { 1f },
) {
    Canvas(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha().coerceIn(0f, 1f)
            // DstIn 遮罩必须落在独立图层里，否则会把底下页面一起裁掉
            compositingStrategy = CompositingStrategy.Offscreen
        },
    ) {
        val t = animationTime.value
        drawAboutGradientField(t, aboutGradientColors(t, dark).map(::strengthenAboutColor))
        // 下沿淡出：卡片区域不需要彩色底，避免整页被染成一片
        drawRect(
            brush = Brush.verticalGradient(
                0.00f to Color.White,
                0.60f to Color.White,
                1.00f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
}

/** 线性底色 + 4 个互相不整除的正弦漂移光斑，形成近似非周期的缓慢流动。 */
private fun DrawScope.drawAboutGradientField(animationTime: Float, colors: List<Color>) {
    val translucent = colors.any { it.alpha < 0.8f }
    val radius = size.maxDimension * 0.62f
    val motion = animationTime * BACKGROUND_SPEED
    val w = size.width
    val h = size.height

    drawRect(
        brush = Brush.linearGradient(
            colors = colors.map {
                it.copy(alpha = if (translucent) it.alpha * 0.72f else 0.58f)
            },
            start = Offset.Zero,
            end = Offset(w, h),
        ),
    )

    val centers = listOf(
        Offset(w * (0.18f + 0.10f * sin(motion)), h * (0.20f + 0.08f * cos(motion * 0.8f))),
        Offset(w * (0.82f + 0.10f * cos(motion * 0.9f)), h * (0.78f + 0.10f * sin(motion * 0.7f))),
        Offset(w * (0.22f + 0.12f * cos(motion * 0.65f)), h * (0.80f + 0.08f * sin(motion * 0.85f))),
        Offset(w * (0.80f + 0.12f * sin(motion * 0.72f)), h * (0.20f + 0.08f * cos(motion * 0.62f))),
    )
    centers.forEachIndexed { index, center ->
        val color = colors[index]
        val centerAlpha = if (translucent) color.alpha * 0.96f else 0.88f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = centerAlpha), color.copy(alpha = 0f)),
                center = center,
                radius = radius,
            ),
            center = center,
            radius = radius,
        )
    }
}

/**
 * Logo 特效：图标 alpha 作蒙版，把流动渐变灌进 Logo 形状，外面套一层呼吸辉光。
 *
 * @param logo 已剥成"白色图形 + alpha"的图标位图（见 [rememberGradientLogoMask]）。
 * @param logoSize Logo 本体的边长；画布会比它大一圈，用于容纳辉光。
 */
@Composable
fun AboutGradientLogo(
    logo: ImageBitmap,
    animationTime: State<Float>,
    dark: Boolean,
    logoSize: Dp,
    modifier: Modifier = Modifier,
) {
    val haloSize = logoSize * 1.2f
    Box(modifier.size(haloSize), contentAlignment = Alignment.Center) {
        // ① 辉光：独立一层（不参与下面对 Logo 的 DstIn 蒙版）
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = animationTime.value
            val colors = aboutGradientColors(t, dark).map(::strengthenAboutColor)
            val pulse = 1f + 0.06f * sin(t * 1.15f)
            val glow = colors[3].copy(alpha = if (dark) 0.48f else 0.26f)
            val r = size.minDimension * 0.5f * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glow, glow.copy(alpha = 0f)),
                    center = center,
                    radius = r,
                ),
                radius = r,
                center = center,
                blendMode = BlendMode.Plus,
            )
        }
        // ② Logo 本体：渐变 + 图标 alpha 蒙版（DstIn 只作用于图标矩形内）。
        //    浮动的 sin 必须写在 graphicsLayer 的 lambda 里：那是绘制阶段读取，
        //    只失效图层；写在组合阶段会让 Logo 每帧重组一次。
        Canvas(
            modifier = Modifier
                .size(logoSize)
                .graphicsLayer {
                    // 浮动用 dp：直接写 2f 是像素，高密度屏上几乎看不见
                    translationY = 2.dp.toPx() * sin(animationTime.value * 0.9f)
                    compositingStrategy = CompositingStrategy.Offscreen
                },
        ) {
            val t = animationTime.value
            val colors = aboutGradientColors(t, dark)
                .map(::strengthenAboutColor)
                .map { aboutInkColor(it, dark) }
            val shift = sin(t * 0.42f) * size.width * 0.30f
            drawRect(
                brush = Brush.linearGradient(
                    colors = colors + colors.first(),
                    start = Offset(shift - size.width * 0.2f, 0f),
                    end = Offset(shift + size.width * 1.1f, size.height),
                ),
                topLeft = Offset.Zero,
                size = Size(size.width, size.height),
            )
            // 按纵横比塞进画布并居中：蒙版已在 [rememberGradientLogoMask] 里裁到图形紧边界，
            // 直接拉伸成正方形会把 `>_` 压扁
            val fit = min(size.width / logo.width, size.height / logo.height)
            val dstW = (logo.width * fit).roundToInt().coerceAtLeast(1)
            val dstH = (logo.height * fit).roundToInt().coerceAtLeast(1)
            drawImage(
                image = logo,
                dstOffset = IntOffset(
                    (size.width.toInt() - dstW) / 2,
                    (size.height.toInt() - dstH) / 2,
                ),
                dstSize = IntSize(dstW, dstH),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

/**
 * 渐变字标：文字本身作为蒙版，draw 阶段用 `SrcIn` 灌入流动渐变。
 *
 * 用 [drawWithContent] 而不是 `TextStyle(brush = ...)`：后者要求每帧重组换 brush，
 * 前者只在绘制阶段失效，滚动时不会拖着整页一起重组。
 */
@Composable
fun GradientWordmark(
    text: String,
    animationTime: State<Float>,
    dark: Boolean,
    modifier: Modifier = Modifier,
    fontSize: Int = 34,
) {
    Box(
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val t = animationTime.value
                val colors = aboutGradientColors(t, dark)
                    .map(::strengthenAboutColor)
                    .map { aboutInkColor(it, dark) }
                val sweep = (sin(t * 0.55f) * 0.5f + 0.5f)
                val startX = -size.width * 0.4f + sweep * size.width * 0.8f
                drawRect(
                    brush = Brush.linearGradient(
                        colors = colors + colors.first(),
                        start = Offset(startX, 0f),
                        end = Offset(startX + size.width * 1.2f, size.height),
                    ),
                    blendMode = BlendMode.SrcIn,
                )
                // 扫光：一条极窄的高光随着渐变一起掠过文字。
                // 必须用 SrcAtop —— Plus 会把高光画在文字外的透明区域，成一条白带。
                val sheenX = -size.width + ((t * 0.28f) % 1f) * size.width * 3f
                drawRect(
                    brush = Brush.linearGradient(
                        0.00f to Color.White.copy(alpha = 0f),
                        0.45f to Color.White.copy(alpha = if (dark) 0.32f else 0.22f),
                        1.00f to Color.White.copy(alpha = 0f),
                        start = Offset(sheenX, 0f),
                        end = Offset(sheenX + size.width * 0.55f, 0f),
                    ),
                    blendMode = BlendMode.SrcAtop,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = fontSize.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
            ),
        )
    }
}

/**
 * [AboutGradientLogo] 的蒙版源：把单色矢量 Logo 渲染成"白色图形 + 原 alpha"的位图。
 *
 * 直接拿彩色 app 图标当蒙版不行——`DstIn` 吃的是 alpha，整块不透明图标（比如
 * Termux Ultra 的深色 squircle 底）会让渐变灌成一块实心色板、图形细节全丢。
 * 这里用 `ic_foreground_mono`（只有 `>_` 提示符描线、背景透明）当形状来源。
 */
@Composable
fun rememberGradientLogoMask(@DrawableRes resId: Int, sizePx: Int = 512): ImageBitmap? {
    val context = LocalContext.current
    return remember(resId, sizePx) {
        runCatching {
            val source = ContextCompat.getDrawable(context, resId)
                ?.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                ?: return@runCatching null
            val copy = source.copy(Bitmap.Config.ARGB_8888, true)
                ?: return@runCatching null
            val w = copy.width
            val h = copy.height
            val pixels = IntArray(w * h)
            copy.getPixels(pixels, 0, w, 0, 0, w, h)
            var left = w
            var top = h
            var right = -1
            var bottom = -1
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    // 只留 alpha（形状），颜色交给渐变层；抗锯齿边缘的 alpha 原样保留
                    val a = (pixels[row + x] ushr 24) and 0xFF
                    if (a == 0) {
                        pixels[row + x] = 0
                        continue
                    }
                    pixels[row + x] = (a shl 24) or 0x00FFFFFF
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
            if (right < left || bottom < top) return@runCatching null
            copy.setPixels(pixels, 0, w, 0, 0, w, h)
            // 裁到图形紧边界：`ic_foreground_mono` 的 `>_` 只占 viewport 中间一块，
            // 不裁的话 logoSize 里大半是空气，挂上光晕就成了"大光斑 + 小字形"
            Bitmap.createBitmap(copy, left, top, right - left + 1, bottom - top + 1)
                .asImageBitmap()
        }.getOrNull()
    }
}

/**
 * 头部元素的入场动画（淡入 + 轻微上移），[delayMillis] 让各元素依次浮现。
 *
 * 返回 [State] 而不是 `Float`：进度在 [heroEnter] 的 graphicsLayer 里读，
 * 入场这 0.6 秒只失效图层，不会让整页每帧重组。
 */
@Composable
fun rememberHeroEnter(delayMillis: Int = 0): State<Float> {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entered = true
    }
    return animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = 620,
            delayMillis = delayMillis,
            easing = FastOutSlowInEasing,
        ),
        label = "aboutHeroEnter",
    )
}

/** 入场态修饰：透明度 + 上移 10dp。 */
fun Modifier.heroEnter(progress: State<Float>): Modifier =
    this.graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 10.dp.toPx()
    }
