package com.termux.app.github

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * GitHub 登录会话的本地持久化。
 *
 * Token 属于敏感凭据，优先写入 [EncryptedSharedPreferences]（AES256-SIV 加密键名 /
 * AES256-GCM 加密键值，主密钥存放于 Android Keystore）。
 *
 * 若加密存储因厂商 Keystore 异常不可用（这在部分定制 ROM 上会发生），
 * 会降级到普通 SharedPreferences，保证「登录态不丢失」这一基本体验；
 * 降级仅影响静态加密强度，不影响功能正确性。
 */
object GitHubSessionStore {

    private const val TAG = "GitHubSessionStore"
    private const val PREFS_NAME = "github_session_store"
    private const val PLAIN_PREFS_NAME = "github_session_store_plain"

    private const val KEY_TOKEN = "token"
    private const val KEY_TOKEN_TYPE = "token_type"
    private const val KEY_SCOPE = "scope"
    private const val KEY_LOGIN = "login"
    private const val KEY_NAME = "name"
    private const val KEY_AVATAR = "avatar_url"
    private const val KEY_ID = "id"
    private const val KEY_LOGIN_AT = "login_at_millis"

    /** 加密存储句柄；初始化失败时为 null。进程内缓存，避免每次读写都重新派生主密钥。 */
    @Volatile
    private var encryptedPrefs: SharedPreferences? = null

    /** 加密存储是否已尝试初始化（失败后不再反复重试，改用降级通道） */
    @Volatile
    private var encryptedInitDone = false

    @Volatile
    private var plainPrefs: SharedPreferences? = null

    fun load(context: Context): GitHubSession? {
        val app = context.applicationContext
        // 先读加密通道，再读降级通道，任一命中即可
        val fromEncrypted = readSession(encryptedPrefs(app))
        if (fromEncrypted != null) return fromEncrypted
        return readSession(plainPrefs(app))
    }

    fun save(context: Context, session: GitHubSession) {
        val app = context.applicationContext
        val encrypted = encryptedPrefs(app)
        if (encrypted != null) {
            // 使用 commit()（同步落盘）而非 apply()：登录成功后立即返回设置页读取，
            // 异步写入存在读到旧值的窗口，会造成「刚登录却提示未登录」。
            val ok = writeSession(encrypted, session)
            if (ok) {
                // 加密通道写入成功，清掉可能存在的降级副本
                runCatching { plainPrefs(app)?.edit()?.clear()?.commit() }
                return
            }
            Log.w(TAG, "encrypted prefs write failed, falling back to plain prefs")
        }
        writeSession(plainPrefs(app), session)
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        runCatching { encryptedPrefs(app)?.edit()?.clear()?.commit() }
        runCatching { plainPrefs(app)?.edit()?.clear()?.commit() }
    }

    /** 校验 token 是否仍有效（401 / 403 视为失效），失效时清除本地登录态。 */
    suspend fun validate(context: Context): Boolean {
        val session = load(context) ?: return false
        val ok = runCatching { GitHubApi(session.token).fetchUser() }.isSuccess
        if (!ok) clear(context)
        return ok
    }

    // ------------------------------------------------------------------ 内部实现

    private fun readSession(prefs: SharedPreferences?): GitHubSession? {
        if (prefs == null) return null
        return runCatching {
            val token = prefs.getString(KEY_TOKEN, null) ?: return null
            val login = prefs.getString(KEY_LOGIN, null) ?: return null
            GitHubSession(
                token = token,
                tokenType = prefs.getString(KEY_TOKEN_TYPE, "bearer") ?: "bearer",
                scope = prefs.getString(KEY_SCOPE, "") ?: "",
                user = GitHubUser(
                    login = login,
                    name = prefs.getString(KEY_NAME, null)?.ifEmpty { null },
                    avatarUrl = prefs.getString(KEY_AVATAR, null)?.ifEmpty { null },
                    id = prefs.getLong(KEY_ID, 0L)
                ),
                loginAtMillis = prefs.getLong(KEY_LOGIN_AT, 0L)
            )
        }.getOrNull()
    }

    private fun writeSession(prefs: SharedPreferences?, session: GitHubSession): Boolean {
        if (prefs == null) return false
        return runCatching {
            prefs.edit()
                .putString(KEY_TOKEN, session.token)
                .putString(KEY_TOKEN_TYPE, session.tokenType)
                .putString(KEY_SCOPE, session.scope)
                .putString(KEY_LOGIN, session.user.login)
                .putString(KEY_NAME, session.user.name.orEmpty())
                .putString(KEY_AVATAR, session.user.avatarUrl.orEmpty())
                .putLong(KEY_ID, session.user.id)
                .putLong(KEY_LOGIN_AT, session.loginAtMillis)
                .commit()
        }.getOrDefault(false)
    }

    private fun encryptedPrefs(context: Context): SharedPreferences? {
        encryptedPrefs?.let { return it }
        if (encryptedInitDone) return encryptedPrefs
        synchronized(this) {
            if (encryptedInitDone) return encryptedPrefs
            encryptedInitDone = true
            encryptedPrefs = runCatching {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.onFailure {
                Log.w(TAG, "EncryptedSharedPreferences unavailable, will use plain prefs fallback", it)
            }.getOrNull()
            return encryptedPrefs
        }
    }

    private fun plainPrefs(context: Context): SharedPreferences? {
        plainPrefs?.let { return it }
        synchronized(this) {
            if (plainPrefs == null) {
                plainPrefs = runCatching {
                    context.applicationContext.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)
                }.getOrNull()
            }
            return plainPrefs
        }
    }
}
