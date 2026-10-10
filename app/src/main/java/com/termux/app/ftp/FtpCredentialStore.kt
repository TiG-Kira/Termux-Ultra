package com.termux.app.ftp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * FTP 口令的本地持久化。
 *
 * 口令属于敏感凭据，写入 [EncryptedSharedPreferences]（AES256-SIV 加密键名 /
 * AES256-GCM 加密键值，主密钥存放于 Android Keystore）。
 *
 * 若加密存储因厂商 Keystore 异常不可用（部分定制 ROM 上会发生），
 * 会降级到普通 SharedPreferences，保证「凭据不丢失」这一基本体验；
 * 降级仅影响静态加密强度，不影响功能正确性。
 *
 * 迁移：历史上口令以明文写在 [LEGACY_PREFS]（`termux_prefs`）中，
 * 首次读取时单向搬移到加密通道并清除明文副本（幂等）。
 */
object FtpCredentialStore {

    private const val TAG = "FtpCredentialStore"
    private const val ENC_PREFS = "ftp_credential_store"
    private const val PLAIN_PREFS = "ftp_credential_store_plain"
    private const val LEGACY_PREFS = "termux_prefs"
    private const val KEY_PASSWORD = "sftp_password"

    @Volatile
    private var encryptedPrefs: SharedPreferences? = null

    @Volatile
    private var encryptedInitDone = false

    @Volatile
    private var plainPrefs: SharedPreferences? = null

    /** 读取口令；自动处理从明文 [LEGACY_PREFS] 的一次性迁移。无口令时返回空串。 */
    fun getPassword(context: Context): String {
        val app = context.applicationContext
        val enc = encryptedPrefs(app)
        val fromEnc = enc?.getString(KEY_PASSWORD, null)
        if (!fromEnc.isNullOrEmpty()) return fromEnc

        // 迁移：从明文 termux_prefs 搬运一次
        val legacy = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val legacyPw = legacy.getString(KEY_PASSWORD, null)
        if (!legacyPw.isNullOrEmpty()) {
            setPassword(context, legacyPw)
            runCatching { legacy.edit().remove(KEY_PASSWORD).apply() }
            return legacyPw
        }

        val fromPlain = plainPrefs(app)?.getString(KEY_PASSWORD, null)
        return fromPlain ?: ""
    }

    /** 写入口令；优先加密通道，失败降级明文。 */
    fun setPassword(context: Context, password: String) {
        val app = context.applicationContext
        val enc = encryptedPrefs(app)
        if (enc != null) {
            val ok = runCatching {
                enc.edit().putString(KEY_PASSWORD, password).commit()
            }.getOrDefault(false)
            if (ok) {
                runCatching { plainPrefs(app)?.edit()?.remove(KEY_PASSWORD)?.apply() }
                return
            }
            Log.w(TAG, "encrypted prefs write failed, falling back to plain prefs")
        }
        plainPrefs(app)?.edit()?.putString(KEY_PASSWORD, password)?.apply()
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        runCatching { encryptedPrefs(app)?.edit()?.clear()?.commit() }
        runCatching { plainPrefs(app)?.edit()?.clear()?.apply() }
    }

    // ------------------------------------------------------------------ 内部实现

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
                    ENC_PREFS,
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
                    context.applicationContext.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)
                }.getOrNull()
            }
            return plainPrefs
        }
    }
}
