package com.termux.app.rustdesk

/**
 * 内嵌 RustDesk 原生库的可用性判断。
 *
 * AAR 只带 RustDesk 自己编出的原生库。宿主仍会为 armeabi-v7a / x86 出包（CI 四个 ABI 都
 * 要能编过），这些架构上 APK 里没有 librustdesk.so，一旦拉起会话页就会在
 * `MainActivity.onCreate` 里抛 UnsatisfiedLinkError 直接崩进程。
 *
 * 用「能不能加载」而不是「ABI 名字对不对」来判断：加载成功本身就顺带完成了后面
 * Flutter 引擎要用的初始化，判断与使用不会出现两套结论。
 */
object RustDeskRuntime {

    @Volatile
    private var loaded: Boolean? = null

    fun isAvailable(): Boolean = loaded ?: synchronized(this) {
        loaded ?: runCatching { System.loadLibrary("rustdesk") }.isSuccess.also { loaded = it }
    }
}
