package com.termux.app.rustdesk

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 内嵌 RustDesk 核心的 JNI 门面，对应 `rustdesk-core` 仓库 `src/android_jni.rs` 导出的
 * `Java_com_termux_app_rustdesk_RustDeskCore_*` 符号。
 *
 * 身份边界（硬约束）：
 *  - 只有 RustDesk 账号/OIDC 登录与会话级密码/2FA 由这里发起，且**只透传参数**：
 *    `id` / `uuid` / `deviceInfo` / `apiDomain` 与授权 URL 全部由 Rust 核心生成，
 *    本类不自建 HTTP、不缓存 token、不改写授权地址。
 *  - Termux Ultra 自有账号体系、AI 服务与其它集成工具的认证不经过本类，
 *    不与 RustDesk 链路共用存储或回调。
 *
 * 所有 `external` 方法必须带 `@JvmStatic`：Rust 侧 JNI 函数的第二参数是 `JClass`（静态），
 * 若编译成实例方法（JObject）会导致符号/签名不匹配。
 */
object RustDeskCore {

    private const val LIB = "rustdesk"

    /** 核心是否可用（该 ABI 是否带 librustdesk.so）。加载失败只记日志，不阻断启动。 */
    @Volatile
    private var loaded: Boolean? = null

    fun isAvailable(): Boolean = loaded ?: synchronized(this) {
        loaded ?: runCatching {
            System.loadLibrary(LIB)
        }.isSuccess.also {
            loaded = it
            if (!it) android.util.Log.w("RustDeskCore", "librustdesk.so 不可用（该 ABI 无产物）")
        }
    }

    /** 核心事件：sessionId 可能为空串（全局事件），kind 见 [EventKind]，payload 为 JSON 或纯值。 */
    data class CoreEvent(val sessionId: String, val kind: String, val payload: String)

    object EventKind {
        const val EVENT = "event"      // 会话内业务事件，payload 为 JSON（含 name 字段）
        const val GLOBAL = "global"    // 全局事件（连接状态、账号登录结果等）
        const val RGBA = "rgba"        // 新帧就绪，payload 为 display 下标
    }

    private val _events = MutableStateFlow<CoreEvent?>(null)
    val events: StateFlow<CoreEvent?> = _events.asStateFlow()

    /**
     * 由 Rust 网络线程经 JNI 回调。必须保持 public + `@JvmStatic` 的方法签名：
     * `onEvent(String, String, String) : Unit`，改动会让 native 侧找不到入口。
     */
    @JvmStatic
    @androidx.annotation.Keep
    fun onEvent(sessionId: String, kind: String, payload: String) {
        _events.tryEmit(CoreEvent(sessionId, kind, payload))
    }

    /** 初始化核心并注册事件回调。appDir/homeDir 供核心读写配置与日志。 */
    @JvmStatic
    external fun nativeInit(
        ctx: Context,
        appDir: String,
        homeDir: String,
        callback: Any,
    )

    /** 幂等启动：在会话页 onCreate 前调用，加载失败的架构上不得进入会话页。 */
    fun attach(context: Context) {
        if (!isAvailable()) return
        nativeInit(
            context.applicationContext,
            context.filesDir.absolutePath,
            context.filesDir.absolutePath,
            this,
        )
    }

    @JvmStatic external fun nativeShutdown()

    // ── 会话生命周期 ──────────────────────────────────────────────
    /** 返回空串表示成功，否则为错误信息。 */
    @JvmStatic external fun nativeSessionAdd(
        sessionId: String, peerId: String, password: String,
        forceRelay: Boolean, switchUuid: String, connToken: String,
    ): String?

    @JvmStatic external fun nativeSessionStart(sessionId: String, peerId: String): Boolean
    @JvmStatic external fun nativeSessionClose(sessionId: String)
    @JvmStatic external fun nativeSessionRefresh(sessionId: String, display: Int)
    @JvmStatic external fun nativeSessionReconnect(sessionId: String, forceRelay: Boolean)

    // ── 认证（只透传，身份由核心生成）──────────────────────────────
    @JvmStatic external fun nativeSessionLogin(
        sessionId: String, osUsername: String, osPassword: String,
        password: String, remember: Boolean,
    )
    @JvmStatic external fun nativeSessionSend2fa(sessionId: String, code: String, trustThisDevice: Boolean)
    @JvmStatic external fun nativeAccountAuth(op: String, rememberMe: Boolean)
    @JvmStatic external fun nativeAccountAuthCancel()
    /** OIDC 登录状态/授权 URL 的 JSON，UI 只负责展示与打开浏览器。 */
    @JvmStatic external fun nativeAccountAuthResult(): String?

    // ── 帧 / 输入 ─────────────────────────────────────────────────
    @JvmStatic external fun nativeGetRgbaSize(sessionId: String, display: Int): Long
    /** 帧像素尺寸：高 32 位为宽、低 32 位为高，0 表示没有可用帧。 */
    @JvmStatic external fun nativeGetRgbaDims(sessionId: String, display: Int): Long
    /** 把当前帧拷进复用缓冲（长度需 ≥ 宽*高*4），返回字节数，0 表示没有有效帧。 */
    @JvmStatic external fun nativeCopyRgba(sessionId: String, display: Int, dst: ByteArray): Int
    @JvmStatic external fun nativeNextRgba(sessionId: String, display: Int)
    @JvmStatic external fun nativeSetSize(sessionId: String, display: Int, width: Int, height: Int)
    @JvmStatic external fun nativeSendMouse(sessionId: String, msg: String)
    @JvmStatic external fun nativeInputKey(
        sessionId: String, name: String, down: Boolean, press: Boolean,
        alt: Boolean, ctrl: Boolean, shift: Boolean, command: Boolean,
    )

    // ── 配置 ──────────────────────────────────────────────────────
    @JvmStatic external fun nativeGetLocalOption(key: String): String?
    @JvmStatic external fun nativeSetLocalOption(key: String, value: String)

    /** 把核心事件 JSON 里的 name 取出来，便于 UI 分发。 */
    fun nameOf(payload: String): String = runCatching {
        JSONObject(payload).optString("name")
    }.getOrDefault("")
}
