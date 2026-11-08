// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT

package com.termux.app.compose.liquid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/** 设备倾斜重力向量；Kiba 上游由较新 miuix 的 `blur.sensor` 包提供。 */
data class DeviceGravity(val gravityX: Float, val gravityY: Float)

/**
 * 宿主降级：Termux Ultra vendored 的 miuix 没有 tilt 传感器实现，这里返回稳定的竖直重力。
 * 镜面主光因此固定方向，其余液态底栏逻辑与 Kiba 上游保持一致；后续若补上传感器实现，
 * 只需替换本函数，调用方无需改动。
 */
@Composable
fun rememberDeviceTilt(): State<DeviceGravity> = remember {
    mutableStateOf(DeviceGravity(gravityX = 0f, gravityY = 1f))
}
