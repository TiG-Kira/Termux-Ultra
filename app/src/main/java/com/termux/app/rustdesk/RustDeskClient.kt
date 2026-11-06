package com.termux.app.rustdesk

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * RustDesk 会话入口。
 *
 * 首选**内嵌会话**：Rust 核心（`rustdesk-core` 产出的 librustdesk.so）经 [RustDeskCore] 的
 * JNI 桥驱动，会话页由本应用自研 Compose 实现，不经 RustDesk 主页、不依赖外部客户端。
 *
 * 仅在核心不可用（该 ABI 没有原生库）时，退回官方客户端（`com.carriez.flutter_hbb`）的
 * `rustdesk://` 深链（RustDesk 的 `urlLinkToCmdArgs()`）：
 *
 * - `rustdesk://<id>` 直连
 * - `rustdesk://<id>@<server>?key=<公钥>` 走自建服务器
 * - `rustdesk://<id>/r@<server>` 强制中继
 * - `?password=` 客户端会直接用于登录；`?request-approval=true` 时客户端会丢弃密码改为请求对方确认
 *
 * 身份边界：内嵌路径只透传用户填的密码，RustDesk 账号/OIDC 登录的请求身份由核心生成；
 * 外部兜底路径由官方客户端自行完成登录。本类不代发任何登录请求。
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

    /** 核心可用时走内嵌会话页；否则退回官方客户端深链。 */
    fun launch(context: Context, connection: RustDeskConnection): Boolean {
        val defaults = RustDeskSettings.defaults(context)
        // 记录里的 server/key 优先于全局默认，内嵌路径同样需要合并后再交给核心
        val effective = connection.copy(
            server = connection.server.ifBlank { defaults.server },
            key = connection.key.ifBlank { defaults.publicKey },
            forceRelay = connection.forceRelay || defaults.forceRelay,
            // 请求对方确认时不带密码，交给核心走审批流程
            password = if (defaults.requestApproval) "" else connection.password,
        )

        if (RustDeskCore.isAvailable()) {
            return try {
                context.startActivity(RustDeskSessionActivity.intent(context, effective))
                true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "无法启动内嵌会话页", e)
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "启动内嵌会话页被系统拒绝", e)
                false
            }
        }

        // 兜底：核心不可用（该 ABI 无 librustdesk.so）时，交给官方客户端
        if (!isInstalled(context)) return false
        val uri = Uri.parse(buildUri(connection, defaults))
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(PACKAGE_NAME)
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
