package com.termux.app.github

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GitHub 登录：仅使用 **Device Flow**（设备码）。
 *
 * 用户点击登录后，弹窗展示一次性设备码与授权地址，由用户自行在浏览器/其他设备完成授权；
 * 应用只负责轮询令牌，不再主动拉起浏览器，也不再监听任何本地端口。
 *
 * 全程不使用 Client Secret——Device Flow 只依赖公开的 Client ID。
 */
class GitHubLoginRunner(private val context: Context) {

    /** 换取到的 Access Token 与伴随信息 */
    private data class TokenBundle(val token: String, val tokenType: String, val scope: String)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cancelled = false

    private val finished = AtomicBoolean(false)

    /**
     * @param onDeviceAuth 拿到设备码后回调，UI 据此展示设备码与授权地址
     */
    fun start(
        onStatus: (String) -> Unit,
        onDeviceAuth: (GitHubDeviceAuth) -> Unit,
        onSuccess: (GitHubSession) -> Unit,
        onFailure: (String) -> Unit
    ) {
        scope.launch {
            try {
                val session = doLogin(onStatus, onDeviceAuth)
                if (!cancelled && finished.compareAndSet(false, true)) {
                    postToMain { onSuccess(session) }
                }
            } catch (t: Throwable) {
                if (!cancelled && finished.compareAndSet(false, true)) {
                    postToMain { onFailure(t.message ?: t.javaClass.simpleName) }
                }
            }
        }
    }

    fun cancel() {
        cancelled = true
        runCatching { scope.cancel() }
    }

    // ---------------------------------------------------------------- 主流程

    private suspend fun doLogin(
        onStatus: (String) -> Unit,
        onDeviceAuth: (GitHubDeviceAuth) -> Unit
    ): GitHubSession {
        val uiDeviceAuth: (GitHubDeviceAuth) -> Unit = { auth -> postToMain { onDeviceAuth(auth) } }
        status(onStatus, R.string.github_login_preparing)
        val bundle = runDeviceFlow(uiDeviceAuth)
        return completeLogin(bundle, onStatus)
    }

    private suspend fun completeLogin(bundle: TokenBundle, onStatus: (String) -> Unit): GitHubSession {
        status(onStatus, R.string.github_login_finalizing)
        val user = GitHubApi(bundle.token).fetchUser()
        val session = GitHubSession(
            token = bundle.token,
            tokenType = bundle.tokenType,
            scope = bundle.scope,
            user = user,
            loginAtMillis = System.currentTimeMillis()
        )
        // 同步落盘：写完再回调成功，避免 UI 先进入已登录态而存储尚未生效
        GitHubSessionStore.save(appContext, session)
        return session
    }

    // ---------------------------------------------------------------- 设备码通道

    private suspend fun runDeviceFlow(
        onDeviceAuth: (GitHubDeviceAuth) -> Unit
    ): TokenBundle = withContext(Dispatchers.IO) {
        val hello = requestDeviceCode()
        onDeviceAuth(hello.second)

        val deadline = System.currentTimeMillis() + hello.first.expiresInSeconds * 1000L
        var waitSeconds = hello.first.intervalSeconds
        while (System.currentTimeMillis() < deadline) {
            if (cancelled) throw CancellationException("cancelled")
            delay(waitSeconds * 1000L)
            val body = FormBody.Builder()
                .add("client_id", GitHubConfig.CLIENT_ID)
                .add("device_code", hello.first.deviceCode)
                .add("grant_type", DEVICE_GRANT_TYPE)
                .build()
            val text = executeForm(GitHubConfig.TOKEN_URL, body)
            val json = JSONObject(text)
            val token = json.optString("access_token", "")
            if (token.isNotEmpty()) {
                return@withContext TokenBundle(
                    token = token,
                    tokenType = json.optString("token_type", "bearer"),
                    scope = json.optString("scope", "")
                )
            }
            when (val errorText = json.optString("error", "")) {
                "slow_down" -> waitSeconds += 5
                "expired_token", "access_denied" -> {
                    error(json.optString("error_description", "").ifBlank { errorText })
                }
                "authorization_pending" -> Unit
                else -> {
                    if (errorText.isNotBlank()) {
                        error(json.optString("error_description", "").ifBlank { errorText })
                    }
                    error(appContext.getString(R.string.github_login_failed_unexpected))
                }
            }
        }
        error(appContext.getString(R.string.github_login_expired))
    }

    private data class DeviceCodeBundle(
        val deviceCode: String,
        val expiresInSeconds: Int,
        val intervalSeconds: Int
    )

    private suspend fun requestDeviceCode(): Pair<DeviceCodeBundle, GitHubDeviceAuth> = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", GitHubConfig.CLIENT_ID)
            .add("scope", GitHubConfig.SCOPES)
            .build()
        val json = JSONObject(executeForm(GitHubConfig.DEVICE_CODE_URL, body))
        val bundle = DeviceCodeBundle(
            deviceCode = json.optString("device_code", "").also {
                if (it.isBlank()) error(appContext.getString(R.string.github_login_failed_unexpected))
            },
            expiresInSeconds = json.optInt("expires_in", 900),
            intervalSeconds = json.optInt("interval", 5).coerceAtLeast(5)
        )
        val auth = GitHubDeviceAuth(
            userCode = json.optString("user_code", ""),
            verificationUri = json.optString("verification_uri", "https://github.com/login/device")
        )
        Pair(bundle, auth)
    }

    // ---------------------------------------------------------------- 工具

    private fun executeForm(url: String, body: FormBody): String {
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "application/json")
            .header("User-Agent", "Termux-Ultra-Android")
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "HTTP ${response.code}"
                error(msg)
            }
            return text
        }
    }

    private suspend fun status(onStatus: (String) -> Unit, textRes: Int) {
        val text = appContext.getString(textRes)
        withContext(Dispatchers.Main) { onStatus(text) }
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }
}

private const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
