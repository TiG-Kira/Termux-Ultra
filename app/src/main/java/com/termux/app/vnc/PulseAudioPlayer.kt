package com.termux.app.vnc

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PulseAudio 简单协议（Simple Protocol）播放器。
 *
 * 当用户选择 PA_FOLLOW_SCREEN 模式时，容器/原生端的 QEMU 会把声音输出到 PulseAudio daemon，
 * PA daemon 会在 4714 端口开启 Simple Protocol（raw PCM：44100Hz，16bit，双声道小端）。
 * 本类负责在 VNC 页面可见时连接该端口并播放，页面不可见时断开释放资源。
 *
 * 对于 PA_PERSIST 模式，此播放器不介入，任由外部 PulseAudio 客户端接管。
 */
object PulseAudioPlayer {

    private const val TAG = "PulseAudioPlayer"
    private const val PA_SIMPLE_PROTO_PORT = 4714
    private const val SAMPLE_RATE = 44100
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private var playJob: Job? = null
    private val playing = AtomicBoolean(false)

    /**
     * 启动 PulseAudio Simple Protocol 播放。
     * 调用方应在 VNC 页面进入时调用。
     *
     * @param host PulseAudio 所在主机（一般是 127.0.0.1）
     */
    fun start(host: String = "127.0.0.1") {
        if (playing.getAndSet(true)) return
        playJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            runCatching { playLoop(host) }
                .onFailure { Log.e(TAG, "PulseAudio 播放异常", it) }
        }
    }

    /**
     * 停止播放并释放 AudioTrack/Socket 等资源。
     * 调用方应在 VNC 页面退出时调用。
     */
    fun stop() {
        if (!playing.getAndSet(false)) return
        runCatching { playJob?.cancel() }
        playJob = null
    }

    /**
     * 当前是否正在播放（或至少在尝试连接播放）。
     */
    fun isPlaying(): Boolean = playing.get()

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------
    private suspend fun playLoop(host: String) = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var audioTrack: AudioTrack? = null
        try {
            // 反复尝试连接（VM 里的 PulseAudio 可能还没启动好）
            val connectStart = System.currentTimeMillis()
            while (playing.get() && System.currentTimeMillis() - connectStart < 15000) {
                runCatching {
                    socket = Socket(host, PA_SIMPLE_PROTO_PORT).apply {
                        soTimeout = 1000
                        tcpNoDelay = true
                        receiveBufferSize = 8192 * 8
                    }
                }.onSuccess { break }
                delay(300)
            }
            val s = socket ?: run {
                Log.w(TAG, "无法连接到 PulseAudio Simple Protocol ($host:$PA_SIMPLE_PROTO_PORT)")
                return@withContext
            }

            val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = (minBuffer * 4).coerceAtLeast(8192)
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize,
                AudioTrack.MODE_STREAM
            ).apply { play() }
            audioTrack = track

            val input = s.getInputStream()
            val buffer = ByteArray(4096)
            while (playing.get() && isActive) {
                val read = input.read(buffer)
                if (read <= 0) {
                    delay(10)
                    continue
                }
                track.write(buffer, 0, read)
            }
        } finally {
            runCatching { audioTrack?.apply { stop(); flush(); release() } }
            runCatching { socket?.close() }
        }
    }
}
