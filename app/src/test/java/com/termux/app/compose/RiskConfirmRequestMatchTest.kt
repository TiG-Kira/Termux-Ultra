package com.termux.app.compose

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * RiskConfirmManager 确认请求「按 requestId 精确匹配」的通路测试。
 *
 * 覆盖的缺陷：
 * 1. confirm/cancel 原先用 `pendingRequests.keys.lastOrNull()` 取请求。pendingRequests 是
 *    HashMap，迭代顺序与插入顺序无关，多个请求同时在途时会把用户的「确认」结算给
 *    错误的调用方（A 敲的命令、B 点了确认 → B 被放行）。
 * 2. requestId 原先用 System.currentTimeMillis()，同一毫秒内发起的两个请求会撞 id，
 *    后注册的回调覆盖先注册的，先发起的请求永远等不到结果。
 *
 * 现在弹窗状态携带 requestId，confirm/cancel 按弹窗绑定的 id 结算。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class RiskConfirmRequestMatchTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 清掉所有可能残留的待处理状态（KEY_PENDING_SESSION_HANDLE / KEY_AGENT_PENDING_ACTION），
        // 否则 confirm() 会先走跳转分支，根本到不了 pendingRequests
        context.getSharedPreferences(RiskConfirmManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        // 保护级别固定为 WARN_VERIFY（默认），无限制模式关闭
        RiskConfirmManager.preloadCache(context)
        RiskConfirmManager.setSkipRiskCheck(false)
        RiskConfirmManager.stopCountdown()
        RiskConfirmManager._dialogState.value = null
    }

    @After
    fun tearDown() {
        RiskConfirmManager.stopCountdown()
        RiskConfirmManager._dialogState.value = null
    }

    /** 等待条件成立（请求在 Default 线程上发起，真实时间轮询）。 */
    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待条件超时（${timeoutMs}ms）")
    }

    /**
     * 带超时的 await：一旦结算没送到（正是本测试要抓的缺陷），
     * 让用例失败而不是把整个构建挂死。
     */
    private suspend fun <T> awaitOrFail(
        deferred: kotlinx.coroutines.Deferred<T>,
        what: String
    ): T = kotlinx.coroutines.withTimeoutOrNull(5000) { deferred.await() }
        ?: throw AssertionError("$what 未在 5s 内结算 —— 确认结果没有送到正确的请求")

    @Test
    fun singleRequest_confirm_returnsTrueAndClearsDialog() {
        runBlocking {
            val deferred = async(Dispatchers.Default) {
                RiskConfirmManager.requestConfirmation(context, "rm -rf /")
            }
            waitUntil { RiskConfirmManager.dialogState.value != null }

            val requestId = RiskConfirmManager.dialogState.value!!.requestId
            assertTrue("弹窗必须绑定 requestId，否则无法精确结算", requestId.isNotEmpty())

            RiskConfirmManager.confirm(context)

            assertTrue("用户确认后请求应放行", awaitOrFail(deferred, "确认后的请求"))
            assertNull("确认后弹窗必须收起", RiskConfirmManager.dialogState.value)
        }
    }

    @Test
    fun singleRequest_cancel_returnsFalseAndClearsDialog() {
        runBlocking {
            val deferred = async(Dispatchers.Default) {
                RiskConfirmManager.requestConfirmation(context, "rm -rf /")
            }
            waitUntil { RiskConfirmManager.dialogState.value != null }

            RiskConfirmManager.cancel(context)

            assertFalse("用户取消后请求应被拒绝", awaitOrFail(deferred, "取消后的请求"))
            assertNull("取消后弹窗必须收起", RiskConfirmManager.dialogState.value)
        }
    }

    /**
     * 核心用例：两个请求同时在途时，「确认」必须结算当前弹窗绑定的那一个。
     * 同时验证后发起的请求会把先发起的按拒绝结算（而不是让它挂到超时）。
     */
    @Test
    fun twoConcurrentRequests_confirmResolvesOnlyTheDisplayedOne() {
        runBlocking {
            val first = async(Dispatchers.Default) {
                RiskConfirmManager.requestConfirmation(context, "rm -rf /")
            }
            waitUntil { RiskConfirmManager.dialogState.value != null }
            val firstId = RiskConfirmManager.dialogState.value!!.requestId

            val second = async(Dispatchers.Default) {
                RiskConfirmManager.requestConfirmation(context, "rm -rf /etc")
            }
            waitUntil {
                val id = RiskConfirmManager.dialogState.value?.requestId
                id != null && id != firstId
            }
            val secondId = RiskConfirmManager.dialogState.value!!.requestId

            assertNotEquals("两个请求必须有不同 requestId（原实现用时间戳会撞）", firstId, secondId)
            assertTrue("被顶掉的旧请求必须立即结算，不能挂到超时", first.isCompleted)
            assertFalse("被顶掉的旧请求按拒绝结算", awaitOrFail(first, "被顶掉的旧请求"))

            RiskConfirmManager.confirm(context)

            assertTrue("确认必须结算当前弹窗绑定的请求（第二个）", awaitOrFail(second, "当前弹窗绑定的请求"))
            assertNull("结算后弹窗必须收起", RiskConfirmManager.dialogState.value)
        }
    }

    /** 同一请求被重复结算时，第二次必须是空操作（不能重复 resume 协程）。 */
    @Test
    fun doubleConfirm_isIdempotent() {
        runBlocking {
            val deferred = async(Dispatchers.Default) {
                RiskConfirmManager.requestConfirmation(context, "rm -rf /")
            }
            waitUntil { RiskConfirmManager.dialogState.value != null }

            RiskConfirmManager.confirm(context)
            RiskConfirmManager.confirm(context)

            assertTrue(awaitOrFail(deferred, "重复确认后的请求"))
            assertNull(RiskConfirmManager.dialogState.value)
        }
    }

    /** 非高危命令不应弹窗，直接放行。 */
    @Test
    fun harmlessCommand_returnsTrueWithoutDialog() {
        runBlocking {
            val result = RiskConfirmManager.requestConfirmation(context, "ls -la")
            assertTrue("非高危命令应直接放行", result)
            assertNull("非高危命令不应弹窗", RiskConfirmManager.dialogState.value)
        }
    }

    /** 保护级别为 OFF 时直接放行，不弹窗。 */
    @Test
    fun protectionOff_returnsTrueWithoutDialog() {
        context.getSharedPreferences(RiskConfirmManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(
                RiskConfirmManager.KEY_PROTECTION_LEVEL,
                RiskConfirmManager.ProtectionLevel.OFF.ordinal
            )
            .commit()
        RiskConfirmManager.preloadCache(context)

        runBlocking {
            val result = RiskConfirmManager.requestConfirmation(context, "rm -rf /")
            assertTrue("OFF 级别应直接放行", result)
            assertNull("OFF 级别不应弹窗", RiskConfirmManager.dialogState.value)
        }

        // 复位，避免影响其他用例
        context.getSharedPreferences(RiskConfirmManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        RiskConfirmManager.preloadCache(context)
    }
}
