package com.termux.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.termux.app.utils.BootstrapDownloader
import com.termux.app.terminal.shell.ComposeSessionManager
import com.termux.shared.errors.Error
import com.termux.shared.file.FileUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_FILES_DIR_PATH
import com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH
import com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH
import com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH

/**
 * 「重置运行环境」核心类 —— 清掉 prefix + home 里所有运行环境数据，
 * 重新下载 bootstrap 并解压，恢复到全新安装状态。
 *
 * 设计原则：全程脱离 TermuxService 终端层执行。除了通过 Broadcast 杀掉
 * 所有会话进程外，不调用任何依赖 TermuxService 活跃状态的 API；解压/删除
 * 等 IO 全部在独立后台线程完成，主线程只负责回调。
 */
object RuntimeEnvironmentResetter {

    private const val TAG = "RuntimeEnvResetter"

    /** 重置管线步骤，按执行顺序排列。 */
    enum class Step {
        DOWNLOADING,
        VERIFYING,
        KILLING_SESSIONS,
        PURGING_PREFIX,
        PURGING_HOME,
        EXTRACTING,
        SETTING_UP_SYMLINKS,
        DONE
    }

    /**
     * 重置结果回调，所有方法都在主线程执行。
     * 调用顺序：onStepUpdate(×N) → (onSuccess | onFailBeforePurge | onFailAfterPurge)
     */
    interface Listener {
        fun onStepUpdate(step: Step, message: String)
        fun onSuccess()
        /** 清环境之前失败（下载/校验）—— 可以重试。 */
        fun onFailBeforePurge(message: String)
        /** 清环境之后失败（解压/rename 等）—— prefix 已空，建议清除应用数据。 */
        fun onFailAfterPurge(message: String)
    }

    /**
     * 启动重置管道。必须确保 bootstrap zip 完全下载+校验通过后才开始删环境，
     * 否则中途下载失败会导致 prefix 已空但无 zip 可用。
     *
     * @param context 用于 cacheDir、getMainExecutor 等
     * @param listener 主线程回调
     */
    fun reset(context: Context, listener: Listener) {
        Thread {
            val mainExecutor = context.mainExecutor
            fun post(block: () -> Unit) { mainExecutor.execute(block) }

            try {
                // ---------- 1. 下载 bootstrap（含缓存 + SHA-256 校验） ----------
                post { listener.onStepUpdate(Step.DOWNLOADING, "正在下载 bootstrap...") }
                val arch = BootstrapDownloader.getArchForAbi()
                val zipBytes = try {
                    BootstrapDownloader.downloadBootstrap(context, arch, useCache = true)
                } catch (e: Throwable) {
                    val msg = "下载失败: ${e.message ?: e.javaClass.simpleName}"
                    Log.e(TAG, msg, e)
                    post { listener.onFailBeforePurge(msg) }
                    return@Thread
                }

                // ---------- 2. 完整性校验（downloadBootstrap 内部已校验，再兜一次） ----------
                post { listener.onStepUpdate(Step.VERIFYING, "正在校验完整性...") }
                val expectedSha = BootstrapDownloader.getExpectedSha256(arch)
                if (expectedSha == null || !BootstrapDownloader.isValidBootstrap(zipBytes, expectedSha)) {
                    val msg = "bootstrap 完整性校验失败（SHA-256 不匹配或架构未知）"
                    Log.e(TAG, msg)
                    post { listener.onFailBeforePurge(msg) }
                    return@Thread
                }

                // ---------- 3. 杀所有会话（两套会话体系都要清干净） ----------
                post { listener.onStepUpdate(Step.KILLING_SESSIONS, "正在关闭所有会话...") }
                try {
                    // Java 体系：通过广播通知 TermuxService
                    context.sendBroadcast(Intent(TermuxConstants.TERMUX_SERVICE.ACTION_KILL_SESSIONS))
                } catch (_: Throwable) { /* 无 TermuxService 时静默忽略 */ }
                try {
                    // Compose Kotlin 体系
                    ComposeSessionManager.killAllSessions()
                } catch (_: Throwable) { /* Compose 体系不存在时忽略 */ }
                // 给 OS 足够时间释放文件句柄（否则 prefix 删除可能因文件占用失败）
                Thread.sleep(600)

                // ---------- 4. 清 prefix（如果存在 staging 也清） ----------
                post { listener.onStepUpdate(Step.PURGING_PREFIX, "正在清除环境文件...") }
                FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true)
                val errPrefix = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true)
                if (errPrefix != null) {
                    val msg = "清除 prefix 失败: ${Error.getErrorMarkdownString(errPrefix)}"
                    Log.e(TAG, msg)
                    post { listener.onFailAfterPurge(msg) }
                    return@Thread
                }

                // ---------- 5. 清 home（dotfiles、项目、storage symlinks 等） ----------
                post { listener.onStepUpdate(Step.PURGING_HOME, "正在清除个人文件...") }
                val errHome = FileUtils.deleteFile("termux home directory", TERMUX_HOME_DIR_PATH, true)
                if (errHome != null) {
                    // home 清除失败也属于 after-purge 范畴，因为 prefix 已经没了
                    val msg = "清除 home 失败: ${Error.getErrorMarkdownString(errHome)}"
                    Log.e(TAG, msg)
                    post { listener.onFailAfterPurge(msg) }
                    return@Thread
                }

                // ---------- 6. 解压 bootstrap 到 staging → atomic rename → prefix ----------
                post { listener.onStepUpdate(Step.EXTRACTING, "正在解压 bootstrap...") }
                try {
                    TermuxInstaller.extractBootstrapZip(zipBytes)
                } catch (e: Throwable) {
                    val msg = "解压 bootstrap 失败: ${e.message ?: e.javaClass.simpleName}"
                    Log.e(TAG, msg, e)
                    post { listener.onFailAfterPurge(msg) }
                    return@Thread
                }

                // ---------- 7. 重建 home/storage symlinks ----------
                post { listener.onStepUpdate(Step.SETTING_UP_SYMLINKS, "正在重建存储链接...") }
                try {
                    TermuxInstaller.setupStorageSymlinks(context)
                } catch (e: Throwable) {
                    val msg = "重建 storage symlinks 失败: ${e.message ?: e.javaClass.simpleName}"
                    Log.e(TAG, msg, e)
                    // storage symlinks 失败不影响 prefix 本体，仍算成功但记录日志
                }

                // ---------- 8. 完成 ----------
                post {
                    listener.onStepUpdate(Step.DONE, "重置完成")
                    listener.onSuccess()
                }

            } catch (t: Throwable) {
                val msg = "重置管道异常: ${t.message ?: t.javaClass.simpleName}"
                Log.e(TAG, msg, t)
                // 无法判断 purge 状态（可能中途），保守按 after-purge 处理
                post { listener.onFailAfterPurge(msg) }
            }
        }.start()
    }
}
