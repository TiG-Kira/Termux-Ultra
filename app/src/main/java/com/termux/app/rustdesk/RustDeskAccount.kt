package com.termux.app.rustdesk

import android.content.Context
import org.json.JSONObject

/**
 * RustDesk 账户状态门面。
 *
 * 身份边界：登录请求一律由核心的 `main_account_auth` 以 RustDesk 原生身份发起，这里只传
 * `op` 与 `rememberMe`；授权 URL、id、uuid、deviceInfo、access_token 全部由核心生成与保存。
 * 本类不发起任何 HTTP、不自建账号体系、不缓存 token，只读取核心已落盘的 user_info 用于展示。
 */
internal object RustDeskAccount {

    /** `/api/login-options` 返回的 op 由服务端决定，这里给出官方客户端常见的入口。 */
    val providers: List<Pair<String, String>> = listOf(
        "rustdesk" to "RustDesk",
        "github" to "GitHub",
        "google" to "Google",
        "microsoft" to "Microsoft",
        "gitlab" to "GitLab",
        "apple" to "Apple",
        "okta" to "Okta",
        "auth0" to "Auth0",
    )

    data class State(val userName: String?, val signedIn: Boolean)

    private const val KEY_LAST_OP = "rustdesk_last_auth_op"

    @Volatile
    private var attached = false

    /** 快速登录复用上次成功的登录方式，首次默认走 RustDesk 账户授权页。 */
    fun lastOp(context: Context): String = context.applicationContext
        .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        .getString(KEY_LAST_OP, "").orEmpty().ifBlank { "rustdesk" }

    fun saveLastOp(context: Context, op: String) {
        context.applicationContext
            .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_OP, op.trim()).apply()
    }

    /** 核心未加载时所有账户操作都是空操作，UI 据此隐藏账户入口。 */
    fun available(context: Context): Boolean {
        if (!RustDeskCore.isAvailable()) return false
        if (!attached) {
            RustDeskCore.attach(context.applicationContext)
            attached = true
        }
        return true
    }

    fun state(context: Context): State {
        if (!RustDeskCore.isAvailable()) return State(null, false)
        val token = runCatching { RustDeskCore.nativeGetLocalOption("access_token") }
            .getOrNull().orEmpty()
        if (token.isBlank()) return State(null, false)
        val info = runCatching { RustDeskCore.nativeGetLocalOption("user_info") }
            .getOrNull().orEmpty()
        val name = runCatching { JSONObject(info).optString("name") }
            .getOrNull()?.takeIf { it.isNotEmpty() }
        return State(name, true)
    }

    /** 必须在后台线程调用：核心 `account_auth` 会等待上一次轮询结束。 */
    fun signIn(context: Context, op: String, rememberMe: Boolean) {
        if (!available(context) || op.isBlank()) return
        RustDeskCore.nativeAccountAuthCancel()
        RustDeskCore.nativeAccountAuth(op.trim(), rememberMe)
    }

    fun cancel(context: Context) {
        if (!available(context)) return
        RustDeskCore.nativeAccountAuthCancel()
    }

    /** 退出只清核心保存的凭据，不碰任何 Termux Ultra 自有账号数据。 */
    fun signOut(context: Context) {
        if (!available(context)) return
        RustDeskCore.nativeAccountAuthCancel()
        RustDeskCore.nativeSetLocalOption("access_token", "")
        RustDeskCore.nativeSetLocalOption("user_info", "")
    }
}
