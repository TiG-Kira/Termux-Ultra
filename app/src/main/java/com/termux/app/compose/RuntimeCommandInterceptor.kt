package com.termux.app.compose

/**
 * 运行时命令拦截器接口。
 * 用于在命令执行前进行实时解析和拦截。
 */
interface RuntimeCommandInterceptor {
    /**
     * 拦截即将执行的命令。
     * @param command 即将执行的命令
     * @return true 表示允许执行，false 表示拦截
     */
    fun intercept(command: String): Boolean
    
    /**
     * 获取拦截器名称（用于日志）
     */
    fun getName(): String
}