package com.termux.app.github

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import com.termux.R
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GitHub 登录：Authorization Code + PKCE 回环回调 **与** Device Flow 双通道并行。
 *
 * 两条链路同时启动，先拿到 Access Token 的一方胜出并被用于建立登录状态：
 * - 主通道：本机 6241 端口监听 `http://127.0.0.1:6241/callback`，浏览器完成授权后回调携带 code；
 * - 备用通道：Device Flow 轮询，若回环端口不可用或用户迟迟未通过浏览器返回，则它自动接管。
 *
 * 全程使用 PKCE（S256），因此不依赖 Client Secret。
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
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var cancelled = false

    private val finished = AtomicBoolean(false)

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
            } finally {
                closeServerSocket()
            }
        }
    }

    fun cancel() {
        cancelled = true
        closeServerSocket()
        runCatching { scope.cancel() }
    }

    // ---------------------------------------------------------------- 竞速主体

    private suspend fun doLogin(
        onStatus: (String) -> Unit,
        onDeviceAuth: (GitHubDeviceAuth) -> Unit
    ): GitHubSession {
        val uiDeviceAuth: (GitHubDeviceAuth) -> Unit = { auth -> postToMain { onDeviceAuth(auth) } }
        status(onStatus, R.string.github_login_preparing)

        val server = withContext(Dispatchers.IO) {
            runCatching {
                ServerSocket(GitHubConfig.CALLBACK_PORT, 1, InetAddress.getByName("127.0.0.1")).apply {
                    soTimeout = 0
                    reuseAddress = true
                }
            }.getOrNull()
        }
        serverSocket = server

        // 端口被占用时没有回环可用，直接进入 Device Flow 单通道
        if (server == null) {
            status(onStatus, R.string.github_login_port_busy)
            val bundle = runDeviceFlow(uiDeviceAuth) { launchBrowser(it) }
            return completeLogin(bundle, onStatus)
        }

        return coroutineScope {
            val verifier = randomUrlSafeString(32)
            val challenge = sha256Base64Url(verifier)
            val state = randomUrlSafeString(16)

            var loopback: Deferred<Result<TokenBundle>>? = null
            var device: Deferred<Result<TokenBundle>>? = null
            try {
                val socket = server
                loopback = async(Dispatchers.IO) { runCatching { awaitLoopbackCode(socket, state, verifier) } }
                device = async(Dispatchers.IO) {
                    // 备用通道：不主动打开设备认证页，避免与主通道的浏览器页面互相覆盖
                    runCatching { runDeviceFlow(uiDeviceAuth) { /* no-op */ } }
                }

                status(onStatus, R.string.github_login_waiting_browser)
                launchBrowser(authorizeUrl(challenge, state))

                val first = select<Pair<Boolean, Result<TokenBundle>>> {
                    loopback!!.onAwait { Pair(false, it) }
                    device!!.onAwait { Pair(true, it) }
                }
                val bundle = if (first.second.isSuccess) {
                    first.second.getOrThrow()
                } else if (first.first) {
                    // Device Flow 先失败（授权被拒 / 设备码过期），等回环
                    loopback!!.await().getOrThrow()
                } else {
                    // 回环先失败（用户未授权返回 / 端口通讯被中断），等 Device Flow
                    device!!.await().getOrThrow()
                }
                completeLogin(bundle, onStatus)
            } finally {
                loopback?.cancel()
                device?.cancel()
                closeServerSocket()
            }
        }
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
        GitHubSessionStore.save(appContext, session)
        return session
    }

    // ---------------------------------------------------------------- 回环通道

    private suspend fun awaitLoopbackCode(server: ServerSocket, state: String, verifier: String): TokenBundle =
        withContext(Dispatchers.IO) {
            var client: Socket? = null
            try {
                client = server.accept()
                client.soTimeout = 8_000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine().orEmpty()
                // 丢弃请求头，读到空行为止
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                respond(client)

                if (!requestLine.startsWith("GET")) error("unexpected request: $requestLine")
                val target = requestLine.split(" ").getOrNull(1).orEmpty()
                val params = queryParams(target)
                if (params["error"].isNullOrBlank()) {
                    val code = params["code"] ?: error(appContext.getString(R.string.github_login_no_code))
                    val returnedState = params["state"].orEmpty()
                    if (returnedState != state) error(appContext.getString(R.string.github_login_state_mismatch))
                    return@withContext exchangeCodeForToken(code, verifier)
                } else {
                    error(params["error_description"].takeIf { !it.isNullOrBlank() } ?: params["error"]!!)
                }
            } finally {
                runCatching { client?.close() }
            }
        }

    private fun respond(client: Socket) {
        runCatching {
            val html = "<html><head><meta charset=\"utf-8\"><title>${GitHubConfig.REPO_NAME}</title></head>" +
                "<body style=\"font-family:sans-serif;text-align:center;padding-top:80px\">" +
                "<h2>${appContext.getString(R.string.github_login_callback_done)}</h2></body></html>"
            val out: OutputStream = client.getOutputStream()
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${html.toByteArray(StandardCharsets.UTF_8).size}\r\nConnection: close\r\n\r\n" +
                html).toByteArray(StandardCharsets.UTF_8))
            out.flush()
            out.close()
        }
    }

    private suspend fun exchangeCodeForToken(code: String, verifier: String): TokenBundle = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", GitHubConfig.CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", GitHubConfig.REDIRECT_URI)
            .add("grant_type", "authorization_code")
            .add("code_verifier", verifier)
            .build()
        parseToken(executeForm(GitHubConfig.TOKEN_URL, body))
    }

    // ---------------------------------------------------------------- 设备码通道

    private suspend fun runDeviceFlow(
        onDeviceAuth: (GitHubDeviceAuth) -> Unit,
        onOpenVerification: (String) -> Unit
    ): TokenBundle = withContext(Dispatchers.IO) {
        val hello = requestDeviceCode()
        onDeviceAuth(hello.second)
        onOpenVerification(hello.second.verificationUri)

        val deadline = System.currentTimeMillis() + hello.first.expiresInSeconds * 1000L
        var waitSeconds = hello.first.intervalSeconds
        while (System.currentTimeMillis() < deadline) {
            if (cancelled) throw kotlinx.coroutines.CancellationException("cancelled")
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

    private fun parseToken(text: String): TokenBundle {
        val json = JSONObject(text)
        val token = json.optString("access_token", "")
        if (token.isEmpty()) {
            error(json.optString("error_description", "").ifBlank {
                json.optString("error", "").ifBlank { appContext.getString(R.string.github_login_failed_unexpected) }
            })
        }
        return TokenBundle(
            token = token,
            tokenType = json.optString("token_type", "bearer"),
            scope = json.optString("scope", "")
        )
    }

    private fun authorizeUrl(challenge: String, state: String): String {
        val encodedRedirect = URLEncoder.encode(GitHubConfig.REDIRECT_URI, StandardCharsets.UTF_8.name())
        val encodedScope = URLEncoder.encode(GitHubConfig.SCOPES, StandardCharsets.UTF_8.name())
        return "${GitHubConfig.AUTHORIZE_URL}?client_id=${GitHubConfig.CLIENT_ID}" +
            "&redirect_uri=$encodedRedirect" +
            "&scope=$encodedScope" +
            "&state=$state" +
            "&code_challenge=$challenge" +
            "&code_challenge_method=S256"
    }

    private fun launchBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(intent) }
    }

    private fun queryParams(target: String): Map<String, String> {
        val query = target.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        query.split('&').forEach { part ->
            val keyValue = part.split('=', limit = 2)
            val key = keyValue.getOrNull(0) ?: return@forEach
            val raw = keyValue.getOrNull(1).orEmpty()
            map[key] = runCatching { java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)
        }
        return map
    }

    private fun randomUrlSafeString(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256Base64Url(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun closeServerSocket() {
        runCatching { serverSocket?.close() }
        serverSocket = null
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
