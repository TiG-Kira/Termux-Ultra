package com.termux.app.rustdesk

import android.os.Bundle
import android.widget.Toast
import com.carriez.flutter_hbb.MainActivity
import com.carriez.flutter_hbb.RustDeskHostBridge
import com.termux.app.R

/**
 * 内嵌 RustDesk 会话页。
 *
 * 直接继承 vendor 进来的上游 [MainActivity]：Flutter 引擎创建、MethodChannel 建立、剪贴板
 * 管理器注入、插件注册全部由它完成，宿主不再复制一份。会话参数仍走官方 `rustdesk://`
 * 深链，由上游的 `uni_links` 从启动 Intent 读取，与官方客户端走的是同一条路径——因此
 * [RustDeskClient.buildUri] 拼出来的链接可以原样复用，只把 Intent 的目标换成这个组件。
 *
 * 上游把这个 Activity 声明成 `exported=false` 并摘掉了 LAUNCHER 与深链过滤器：进入会话只能
 * 由宿主显式拉起，本应用不会变成 `rustdesk://` 的全局处理者。
 */
class RustDeskSessionActivity : MainActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 该 ABI 没有原生库时立刻退出，不要进 MainActivity 去踩 UnsatisfiedLinkError
        if (!RustDeskRuntime.isAvailable()) {
            Toast.makeText(this, R.string.rustdesk_unavailable_abi, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // native 初始化必须在 Flutter 引擎起来之前完成。上游放在 MainApplication.onCreate，
        // 宿主无法继承它；放这里而不是宿主 Application，是因为 librustdesk.so 有 20MB 级，
        // 加载会拖慢每一次冷启动，而只有真正打开会话页时才需要。
        RustDeskHostBridge.attach(applicationContext)
        super.onCreate(savedInstanceState)
    }
}
