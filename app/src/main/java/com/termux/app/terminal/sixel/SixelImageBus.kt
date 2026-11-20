// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT
//
// 按会话投递解码后的 sixel 图像（Issue #401）。
//
// 解码在 IO 线程完成（sixel 出现频率低，不值得常驻协程管道），结果放进
// 每会话一个的 StateFlow；终端页收集当前会话的流，有新图就弹浮层。
// 只保留最新一张：sixel 是"预览"语义，历史图随新图覆盖、点按关闭。

package com.termux.app.terminal.sixel

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object SixelImageBus {

    /** 终端页消费的最小单元：位图 + 原始宽高（Compose 侧按需缩放）。 */
    data class SixelImage(val bitmap: Bitmap, val sessionId: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val images = ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<SixelImage?>>()

    /** 取某会话的图像流；sessionId 为 null 时返回空的共享流。 */
    fun imageFor(sessionId: Int?): kotlinx.coroutines.flow.StateFlow<SixelImage?> =
        sessionId?.let { flowFor(it) } ?: EMPTY_FLOW

    /** 消除某会话当前展示的图像（用户点按浮层关闭）。 */
    fun dismiss(sessionId: Int) {
        flowFor(sessionId).value = null
    }

    /** 扫描器回调入口：负载解码后投递。解码失败静默丢弃。 */
    fun post(sessionId: Int, payload: ByteArray) {
        scope.launch {
            val bitmap = runCatching { SixelDecoder.decode(payload) }.getOrNull() ?: return@launch
            flowFor(sessionId).value = SixelImage(bitmap, sessionId)
        }
    }

    private fun flowFor(sessionId: Int): kotlinx.coroutines.flow.MutableStateFlow<SixelImage?> =
        images.getOrPut(sessionId) {
            kotlinx.coroutines.flow.MutableStateFlow(null)
        }

    private val EMPTY_FLOW = kotlinx.coroutines.flow.MutableStateFlow<SixelImage?>(null)
}
