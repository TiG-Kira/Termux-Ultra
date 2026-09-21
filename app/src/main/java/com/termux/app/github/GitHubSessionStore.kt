package com.termux.app.github

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * GitHub 登录会话的本地持久化。
 *
 * Token 属于敏感凭据，因此写入 [EncryptedSharedPreferences]（AES256-SIV 加密键名 /
 * AES256-GCM 加密键值，主密钥存放于 Android Keystore），而不是普通 SharedPreferences。
 */
object GitHubSessionStore {

    private const val PREFS_NAME = "github_session_store"
    private const val KEY_TOKEN = "token"
    private const val KEY_TOKEN_TYPE = "token_type"
    private const val KEY_SCOPE = "scope"
    private const val KEY_LOGIN = "login"
    private const val KEY_NAME = "name"
    private const val KEY_AVATAR = "avatar_url"
    private const val KEY_ID = "id"
    private const val KEY_LOGIN_AT = "login_at_millis"

    fun load(context: Context): GitHubSession? {
        val prefs = prefs(context) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val login = prefs.getString(KEY_LOGIN, null) ?: return null
        return GitHubSession(
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
    }

    fun save(context: Context, session: GitHubSession) {
        val prefs = prefs(context) ?: return
        prefs.edit()
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_TOKEN_TYPE, session.tokenType)
            .putString(KEY_SCOPE, session.scope)
            .putString(KEY_LOGIN, session.user.login)
            .putString(KEY_NAME, session.user.name.orEmpty())
            .putString(KEY_AVATAR, session.user.avatarUrl.orEmpty())
            .putLong(KEY_ID, session.user.id)
            .putLong(KEY_LOGIN_AT, session.loginAtMillis)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context)?.edit()?.clear()?.apply()
    }

    private fun prefs(context: Context): SharedPreferences? = runCatching {
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
    }.getOrNull()
}
