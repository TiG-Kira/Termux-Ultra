package com.termux.app.compose

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RawRes
import com.termux.R
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * HyperCeiler About-page background effect port.
 *
 * On API 33+ : uses RuntimeShader + RenderEffect for the animated gradient.
 * On API 33- : callers should fall back to a Compose Brush gradient.
 *
 * Color parameters are 1:1 copied from HyperCeiler's BgEffectDataManager.
 */
object AboutBgEffect {

    private const val TAG = "HyperCeilerBg"

    private val BOUND = floatArrayOf(0f, 0f, 1f, 1f)

    // Point positions (same for all modes)
    private val POINTS = floatArrayOf(0.8f, 0.2f, 1.0f, 1f, 0.9f, 1.0f, 0.2f, 1f, 1.0f, 0.2f, 0.2f, 1f)

    // ---------------------------------------------------------------
    // Color parameter bundles (RGB+A in 0..1 range), copied verbatim
    // from HyperCeiler's BgEffectDataManager inner class.
    // ---------------------------------------------------------------

    data class BgParams(
        val gradientColors1: FloatArray,
        val gradientColors2: FloatArray,
        val gradientColors3: FloatArray,
        val colorInterpPeriod: Float,
        val gradientSpeedChange: Float,
        val gradientSpeedRest: Float,
        val uAlphaMulti: Float,
        val uAlphaOffset: Float,
        val uLightOffset: Float,
        val uNoiseScale: Float,
        val uPointOffset: Float,
        val uPointRadiusMulti: Float,
        val uSaturateOffset: Float,
        val uShadowColorMulti: Float,
        val uShadowColorOffset: Float,
        val uShadowNoiseScale: Float,
        val uShadowOffset: Float,
        val uTranslateY: Float,
    ) {
        override fun equals(other: Any?) = other is BgParams &&
            gradientColors1.contentEquals(other.gradientColors1) &&
            gradientColors2.contentEquals(other.gradientColors2) &&
            gradientColors3.contentEquals(other.gradientColors3)

        override fun hashCode() =
            gradientColors1.contentHashCode() * 31 + gradientColors2.contentHashCode()
    }

    private val PHONE_LIGHT = BgParams(
        gradientColors1 = floatArrayOf(1f, 0.9f, 0.94f, 0.4f, 1f, 0.84f, 0.89f, 0.5f, 0.97f, 0.73f, 0.82f, 0.5f, 0.64f, 0.65f, 0.98f, 0.4f),
        gradientColors2 = floatArrayOf(0.58f, 0.74f, 1f, 0.4f, 1f, 0.9f, 0.93f, 0.5f, 0.74f, 0.76f, 1f, 0.5f, 0.97f, 0.77f, 0.84f, 0.4f),
        gradientColors3 = floatArrayOf(0.98f, 0.86f, 0.9f, 0.4f, 0.6f, 0.73f, 0.98f, 0.5f, 0.92f, 0.93f, 1f, 0.5f, 0.56f, 0.69f, 1f, 0.4f),
        colorInterpPeriod = 5f, gradientSpeedChange = 1.6f, gradientSpeedRest = 1.05f,
        uAlphaMulti = 1f, uAlphaOffset = 0.5f, uLightOffset = 0f, uNoiseScale = 1.5f,
        uPointOffset = 0.2f, uPointRadiusMulti = 1f, uSaturateOffset = 0f,
        uShadowColorMulti = 0.3f, uShadowColorOffset = 0.3f, uShadowNoiseScale = 5f,
        uShadowOffset = 0.01f, uTranslateY = 0f,
    )

    private val PHONE_DARK = BgParams(
        gradientColors1 = floatArrayOf(0.2f, 0.06f, 0.88f, 0.4f, 0.3f, 0.14f, 0.55f, 0.5f, 0f, 0.64f, 0.96f, 0.5f, 0.11f, 0.16f, 0.83f, 0.4f),
        gradientColors2 = floatArrayOf(0.07f, 0.15f, 0.79f, 0.4f, 0.62f, 0.21f, 0.67f, 0.5f, 0.06f, 0.25f, 0.84f, 0.5f, 0f, 0.2f, 0.78f, 0.4f),
        gradientColors3 = floatArrayOf(0.58f, 0.3f, 0.74f, 0.4f, 0.27f, 0.18f, 0.6f, 0.5f, 0.66f, 0.26f, 0.62f, 0.5f, 0.12f, 0.16f, 0.7f, 0.4f),
        colorInterpPeriod = 8f, gradientSpeedChange = 1f, gradientSpeedRest = 1f,
        uAlphaMulti = 1f, uAlphaOffset = 0.5f, uLightOffset = 0f, uNoiseScale = 1.5f,
        uPointOffset = 0.4f, uPointRadiusMulti = 1f, uSaturateOffset = 0f,
        uShadowColorMulti = 0.3f, uShadowColorOffset = 0.3f, uShadowNoiseScale = 5f,
        uShadowOffset = 0.01f, uTranslateY = 0f,
    )

    /** Build a params bundle for phone light/dark. */
    fun getParams(isDark: Boolean): BgParams = if (isDark) PHONE_DARK else PHONE_LIGHT

    // ----------------------------------------------------------------
    // RuntimeShader driver
    // ----------------------------------------------------------------

    /**
     * Wraps an animated RuntimeShader driven by postOnAnimation.
     *
     * Usage:
     *   val controller = AboutBgEffect.createFor(view, isDark)
     *   controller.start()   // onAttached / onResume
     *   controller.stop()    // onDetached / onPause
     */
    class ShaderController(
        private val target: View,
        private val params: BgParams,
        @RawRes shaderResId: Int,
    ) {
        private val shader: RuntimeShader =
            RuntimeShader(target.context.resources.openRawResource(shaderResId)
                .bufferedReader().use { it.readText() })

        private var running = false
        private var lastNanos = 0L
        private var animTime = 0f
        private var speed = params.gradientSpeedRest
        private var cycleCount = 0f
        private var prevFloor = 0f
        private var colorInterpT = 0f

        // Color state
        private val uColors = FloatArray(16)
        private var startColors = params.gradientColors2.copyOf()
        private var endColors = params.gradientColors2.copyOf()

        init {
            // Initial interpolate
            linearInterpolate(uColors, startColors, endColors, 0f)
            applyStaticUniforms()
        }

        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val now = System.nanoTime()
                val dt = if (lastNanos > 0) (now - lastNanos) * 1e-9f else 0f
                lastNanos = now
                animTime += dt * speed
                computeGradientColor()
                shader.setFloatUniform("uResolution", floatArrayOf(target.width.toFloat().coerceAtLeast(1f),
                    target.height.toFloat().coerceAtLeast(1f)
                ))
                shader.setFloatUniform("uAnimTime", animTime)
                shader.setFloatUniform("uColors", uColors)
                try {
                    target.setRenderEffect(RenderEffect.createShaderEffect(shader))
                } catch (_: Throwable) {
                    try { target.setRenderEffect(null) } catch (_: Throwable) {}
                }
                target.postOnAnimation(this)
            }
        }

        fun start() {
            if (running) return
            running = true
            lastNanos = 0L
            animTime = 0f
            cycleCount = 0f
            colorInterpT = 0f
            speed = params.gradientSpeedRest
            startColors = params.gradientColors2.copyOf()
            endColors = params.gradientColors2.copyOf()
            linearInterpolate(uColors, startColors, endColors, 0f)
            applyStaticUniforms()
            // Initial resolution — view may not be laid out yet, tick will correct
            shader.setFloatUniform("uResolution", floatArrayOf(target.width.toFloat().coerceAtLeast(1f),
                target.height.toFloat().coerceAtLeast(1f)
            ))
            target.postOnAnimation(tick)
        }

        fun stop() {
            running = false
            target.removeCallbacks(tick)
            try { target.setRenderEffect(null) } catch (_: Throwable) {}
        }

        /** Rebind uniforms when dark-mode changes without tearing down the View. */
        fun updateParams(newParams: BgParams) {
            startColors = newParams.gradientColors2.copyOf()
            endColors = newParams.gradientColors2.copyOf()
            linearInterpolate(uColors, startColors, endColors, 0f)
            cycleCount = 0f
            colorInterpT = 0f
            speed = newParams.gradientSpeedRest
            animTime = 0f
            applyStaticUniforms()
        }

        private fun applyStaticUniforms() {
            shader.setFloatUniform("uBound", BOUND)
            shader.setFloatUniform("uTranslateY", params.uTranslateY)
            shader.setFloatUniform("uPoints", POINTS)
            shader.setFloatUniform("uAlphaMulti", params.uAlphaMulti)
            shader.setFloatUniform("uNoiseScale", params.uNoiseScale)
            shader.setFloatUniform("uPointOffset", params.uPointOffset)
            shader.setFloatUniform("uPointRadiusMulti", params.uPointRadiusMulti)
            shader.setFloatUniform("uSaturateOffset", params.uSaturateOffset)
            shader.setFloatUniform("uLightOffset", params.uLightOffset)
            shader.setFloatUniform("uAlphaOffset", params.uAlphaOffset)
            shader.setFloatUniform("uShadowColorMulti", params.uShadowColorMulti)
            shader.setFloatUniform("uShadowColorOffset", params.uShadowColorOffset)
            shader.setFloatUniform("uShadowNoiseScale", params.uShadowNoiseScale)
            shader.setFloatUniform("uShadowOffset", params.uShadowOffset)
        }

        private fun computeGradientColor() {
            val d = animTime / params.colorInterpPeriod
            val floor = (d - floor(d)) * 2f
            if (kotlin.math.abs(prevFloor - floor) > 0.5f) {
                when {
                    cycleCount % 4f == 0f -> {
                        startColors = params.gradientColors2.copyOf(); endColors = params.gradientColors1.copyOf()
                    }
                    cycleCount % 4f == 1f -> {
                        startColors = params.gradientColors1.copyOf(); endColors = params.gradientColors2.copyOf()
                    }
                    cycleCount % 4f == 2f -> {
                        startColors = params.gradientColors2.copyOf(); endColors = params.gradientColors3.copyOf()
                    }
                    else -> {
                        startColors = params.gradientColors3.copyOf(); endColors = params.gradientColors2.copyOf()
                    }
                }
                cycleCount += 1f
            }
            prevFloor = floor
            // 三角波 floor 作为插值进度 (0->1->0->1...)
            val t = floor.coerceIn(0f, 1f)
            linearInterpolate(uColors, startColors, endColors, t)
        }

        companion object {
            fun linearInterpolate(out: FloatArray, a: FloatArray, b: FloatArray, t: Float) {
                for (i in a.indices) out[i] = a[i] + (b[i] - a[i]) * t
            }
        }
    }

    /**
     * Entry point — returns null on API < 33 (caller should fall back to Brush).
     */
    fun createFor(target: View, isDark: Boolean): ShaderController? {
        if (Build.VERSION.SDK_INT < 31) return null
        return ShaderController(target, getParams(isDark), R.raw.about_bg_shader)
    }
}
