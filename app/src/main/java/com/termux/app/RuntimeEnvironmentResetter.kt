package com.termux.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.termux.app.terminal.shell.ComposeSessionManager

/**
 * 「重置运行环境」—— 通过系统「清除应用数据」把应用恢复到全新安装状态。
 *
 * ## 为什么不再手写删除
 * $HOME 下的 ~/storage 是通过符号链接挂到**真实用户存储**的（shared → /storage/emulated/0、
 * downloads、dcim、可移动存储 /storage/<uuid> 等）。任何手写递归删除，只要底层跟随符号链接，
 * 就会把用户「下载 / 相册 / 音乐 / 电影」乃至整台设备的内部存储一并清空——灾难级且不可恢复。
 * 手写删除无论怎么加白名单，都依赖底层 FileUtils 的实现细节，风险无法彻底消除。
 *
 * ## 现方案
 * 直接调用系统 [ActivityManager.clearApplicationUserData]，等同于系统设置里的「清除数据」。
 * 其作用域被 Android 框架严格限定在本应用私有数据目录（/data/data/<pkg> 及本应用专属外部目录），
 * **结构上不可能触及共享存储中的用户文件**。清除后本进程被强杀，下次启动即走全新安装流程
 * （OobeActivity → TermuxInstaller.setupBootstrapIfNeeded 重新初始化环境并重建存储链接）。
 *
 * ## 回落
 * 若系统清除不可用（ROM 裁剪、策略禁用、SecurityException 等），跳转「应用信息」设置页，
 * 提示用户手动执行「清除数据」。
 */
object RuntimeEnvironmentResetter {

    private const val TAG = "RuntimeEnvResetter"

    /** 重置管线步骤，按执行顺序排列。 */
    enum class Step {
        /** 关闭所有会话，准备清除。 */
        PREPARING,
        /** 调用系统清除应用数据。 */
        CLEARING,
        /** 系统清除不可用，已回落跳转应用设置页。 */
        FALLBACK_SETTINGS,
        /** 清除已发起，进程即将被强杀。 */
        DONE
    }

    /**
     * 重置结果回调，所有方法都在主线程执行。
     * 调用顺序：onStepUpdate(×N) → (onSuccess | onFailBeforePurge | onFallbackToSettings)
     */
    interface Listener {
        fun onStepUpdate(step: Step, message: String)
        /** 系统清除已发起，应用进程即将被强杀，下次启动回到全新安装状态。 */
        fun onSuccess()
        /** 清除尚未开始即失败（应用数据未受影响），可重试。 */
        fun onFailBeforePurge(message: String)
        /** 系统清除不可用，已回落跳转应用设置页，需用户手动清除数据。 */
        fun onFallbackToSettings(message: String)
    }

    /**
     * 启动重置管道：关闭会话 → 调用系统清除应用数据 →（失败则）回落跳转应用设置页。
     *
     * 注意：系统清除一旦成功发起，本进程会被强制结束，因此 [Listener.onSuccess] 之后
     * 不一定还有机会继续渲染 UI；应用会在用户下次启动时进入全新安装流程。
     *
     * @param context 用于 ActivityManager、getMainExecutor、startActivity 等
     * @param listener 主线程回调
     */
    fun reset(context: Context, listener: Listener) {
        Thread {
            val mainExecutor = context.mainExecutor
            fun post(block: () -> Unit) { mainExecutor.execute(block) }

            try {
                // ---------- 1. 关闭所有会话（两套会话体系都要清干净） ----------
                post { listener.onStepUpdate(Step.PREPARING, "正在关闭所有会话...") }
                try {
                    // Java 体系：通过广播通知 TermuxService
                    context.sendBroadcast(Intent("com.termux.kill_sessions"))
                } catch (_: Throwable) { /* 无 TermuxService 时静默忽略 */ }
                try {
                    // Compose Kotlin 体系
                    ComposeSessionManager.getInstance(context).killAllSessions()
                } catch (_: Throwable) { /* Compose 体系不存在时忽略 */ }
                // 给会话一点时间收尾（系统清除本身也会强杀，这里只为体验更平滑）
                Thread.sleep(300)

                // ---------- 2. 调用系统清除应用数据 ----------
                post { listener.onStepUpdate(Step.CLEARING, "正在清除应用数据...") }
                val cleared = try {
                    clearApplicationUserData(context)
                } catch (t: Throwable) {
                    Log.e(TAG, "系统清除应用数据调用异常: ${t.message ?: t.javaClass.simpleName}", t)
                    false
                }

                if (cleared) {
                    // 清除已发起：进程随后会被强杀，下次启动即全新安装状态
                    Log.i(TAG, "系统清除应用数据已发起，进程即将结束")
                    post {
                        listener.onStepUpdate(Step.DONE, "清除完成，应用即将关闭")
                        listener.onSuccess()
                    }
                    return@Thread
                }

                // ---------- 3. 回落：跳转应用设置页，提示用户手动清除 ----------
                val opened = openAppDetailsSettings(context)
                val msg = if (opened) {
                    "无法自动清除应用数据，已打开应用信息页，请手动点击「清除数据」"
                } else {
                    "无法自动清除应用数据，请在系统设置 → 应用信息中手动清除本应用数据"
                }
                Log.w(TAG, msg)
                post {
                    listener.onStepUpdate(Step.FALLBACK_SETTINGS, msg)
                    listener.onFallbackToSettings(msg)
                }

            } catch (t: Throwable) {
                val msg = "重置异常: ${t.message ?: t.javaClass.simpleName}"
                Log.e(TAG, msg, t)
                // 异常发生在调用系统清除之前，数据未受影响，可重试
                post { listener.onFailBeforePurge(msg) }
            }
        }.start()
    }

    /**
     * 调用系统「清除应用数据」，等同于系统设置里的「清除数据 / 清除存储空间」。
     *
     * 该能力的作用域由 Android 框架限定为**本应用的私有数据**：包括 /data/data/<pkg>
     * 下的全部内容（应用内所有设置、运行环境内的文件等）以及本应用专属的外部目录。
     * 它**不会删除共享存储（/storage/emulated/0 的下载、相册、音乐、电影等）中的用户文件**，
     * 因此不存在手写删除目录时误删用户数据的风险。
     *
     * @return true 表示清除已成功发起（此后本进程会被强杀）；false 表示不可用或调用失败。
     */
    private fun clearApplicationUserData(context: Context): Boolean {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return try {
            activityManager.clearApplicationUserData()
        } catch (t: Throwable) {
            // 部分 ROM 裁剪了该能力或策略禁用，会以 SecurityException 等形式抛出
            Log.e(TAG, "clearApplicationUserData 调用失败: ${t.message ?: t.javaClass.simpleName}", t)
            false
        }
    }

    /**
     * 打开本应用的「应用信息」设置页，供用户手动执行「清除数据」。
     *
     * @return 是否成功跳转。
     */
    private fun openAppDetailsSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "跳转应用设置页失败: ${t.message ?: t.javaClass.simpleName}", t)
            false
        }
    }
}
