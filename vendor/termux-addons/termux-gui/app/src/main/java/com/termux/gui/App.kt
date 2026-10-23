package com.termux.gui

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.util.Log

/**
 * Makes the Application object globally available enable the handler Threads to register Activity Lifecycle listeners.
 * Also cleans up any leftover Tasks stacks from e.g. a crash and sets the default Exception handler to just print
 * (so exceptions in handler threads don't bring down the whole app).
 *
 * 内置化改动：本类不再由系统实例化（宿主 Application 是 com.termux.app.TermuxApplication），
 * 因此 [APP] 改由宿主通过 [attach] 注入。
 *
 * 不注入的后果：协议层用 `App.APP?.registerActivityLifecycleCallbacks(...)` 注册 Activity
 * 生命周期回调，而 V0Shared.newActivity() 靠该回调把 Activity 实例回填进 ActivityState.a，
 * 回填前会自旋等待（`while (activities[aid]?.a == null) sleep(1)`）。APP 为 null 时回调
 * 永不注册、自旋永不结束——表现为客户端程序连上插件后卡死、GUI 页面不出现，只能 Ctrl+C 终止。
 */
class App : Application() {
    companion object {
        var APP: Application? = null

        /**
         * 由宿主注入 Application 并完成原 onCreate 的初始化。
         * 幂等：重复传入同一实例不会重复清理任务栈。
         *
         * 不设置 Thread.setDefaultUncaughtExceptionHandler——宿主已有 CrashHandler，覆盖它会
         * 丢掉宿主的崩溃上报；连接线程自身的异常由 ConnectionHandler 捕获。
         */
        @JvmStatic
        fun attach(app: Application) {
            if (APP === app) return
            APP = app
            Log.i("TermuxGUI", "Application attached: " + app.javaClass.name)
            runCatching { Settings.instance.load(app) }
                .onFailure { Log.w("TermuxGUI", "load settings failed", it) }
            cleanUpTasks(app)
        }

        /** 清理崩溃残留的 GUI 任务栈，避免旧栈占着 Task 影响新连接。 */
        private fun cleanUpTasks(app: Application) {
            try {
                app.getSystemService(ActivityManager::class.java).let {
                    for (t in it.appTasks) {
                        if (t.taskInfo?.baseIntent?.component == ComponentName(app, GUIActivity::class.java) ||
                                t.taskInfo?.baseIntent?.component == ComponentName(app, GUIActivityDialog::class.java) ||
                                t.taskInfo?.baseIntent?.component == ComponentName(app, GUIActivityLockscreen::class.java))
                            t.finishAndRemoveTask()
                    }
                }
            } catch (e: Exception) {
                Log.w("TermuxGUI", "clean up GUI tasks failed", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        attach(this)
        Thread.setDefaultUncaughtExceptionHandler { _, e -> e.printStackTrace() }
    }


}
