package com.termux.app.rustdesk

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * 把会话交接给官方 RustDesk Android 客户端（`com.carriez.flutter_hbb`）。
 *
 * 为什么不内置：RustDesk 的会话核心是 Rust + Flutter，且为 AGPL-3.0，没有可作为库链接的
 * AAR，整包 vendor 进来还会把本应用整体拖成 AGPL。因此这里走官方 `rustdesk://` 深链协议
 * （RustDesk 的 `urlLinkToCmdArgs()`）把 ID / 密码 / 服务器 / 公钥 / 强制中继交给客户端：
 *
 * - `rustdesk://<id>` 直连
 * - `rustdesk://<id>@<server>?key=<公钥>` 走自建服务器
 * - `rustdesk://<id>/r@<server>` 强制中继
 * - `?password=` 客户端会直接用于登录；`?request-approval=true` 时客户端会丢弃密码改为请求对方确认
 */
object RustDeskClient {

    const val PACKAGE_NAME = "com.carriez.flutter_hbb"

    private const val DOWNLOAD_URL = "https://rustdesk.com/download"
    private const val TAG = "RustDeskClient"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE_NAME, 0) }.isSuccess

    fun versionName(context: Context): String? =
        runCatching { context.packageManager.getPackageInfo(PACKAGE_NAME, 0).versionName }.getOrNull()

    /**
     * 拼出交接用的深链。
     *
     * 记录里的 server/key 优先于全局默认；路径部分不加百分号转义——客户端把 authority 里的
     * `@` 当作「ID@服务器」分隔符解析，转义后反而认不出来，只有查询值做转义。
     */
    fun buildUri(connection: RustDeskConnection, defaults: RustDeskSettings.Defaults): String {
        val server = connection.server.ifBlank { defaults.server }.trim()
        val key = connection.key.ifBlank { defaults.publicKey }.trim()
        val forceRelay = connection.forceRelay || defaults.forceRelay

        val target = buildString {
            append(connection.remoteId.trim())
            if (forceRelay) append("/r")
            if (server.isNotEmpty()) append("@").append(server)
        }

        val params = buildList {
            // 请求对方确认时客户端会丢掉密码，这里就不再传，免得密码白留在 URI 里
            if (!defaults.requestApproval && connection.password.isNotEmpty()) {
                add("password=" + Uri.encode(connection.password))
            }
            if (key.isNotEmpty()) add("key=" + Uri.encode(key))
            if (defaults.requestApproval) add("request-approval=true")
        }

        return buildString {
            append("rustdesk://").append(target)
            if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
        }
    }

    /** 交接成功返回 true；客户端未安装或系统没有可处理的应用返回 false。 */
    fun launch(context: Context, connection: RustDeskConnection): Boolean {
        val uri = Uri.parse(buildUri(connection, RustDeskSettings.defaults(context)))
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isInstalled(context)) setPackage(PACKAGE_NAME)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "RustDesk 客户端无法处理深链：$uri", e)
            false
        } catch (e: SecurityException) {
            // 部分 ROM 在后台启动外部 Activity 时会抛这个异常，按未交接处理
            Log.w(TAG, "启动 RustDesk 被系统拒绝", e)
            false
        }
    }

    /** 直接打开客户端首页（已安装时用于「打开 RustDesk」入口）。 */
    fun openClient(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 官方下载页（RustDesk 不分发到 Google Play，Android 包在 releases / F-Droid）。 */
    fun openDownloadPage(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
