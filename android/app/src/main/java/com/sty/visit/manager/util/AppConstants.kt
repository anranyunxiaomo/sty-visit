package com.sty.visit.manager.util

object AppConstants {
    const val PREFS_NAME = "visit_prefs"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_ADMIN_KEY = "admin_key"
    const val KEY_SSH_HOST = "ssh_host"
    const val KEY_SSH_PORT = "ssh_port"
    const val KEY_SSH_USER = "ssh_user"
    const val KEY_LAST_IP = "last_ip"
    const val KEY_LAST_PORT = "last_port"
    
    // 网络与路径
    const val WS_TERMINAL_PATH = "/ws/terminal"
    const val HEADER_AUTH = "Authorization"
    const val AUTH_PREFIX = "Bearer "
    
    // 业务常量
    const val SSH_AUTH_TYPE_PWD = "pwd"
    const val SSH_AUTH_TYPE_KEY = "key"

    fun getWsUrl(baseUrl: String): String {
        val cleanUrl = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        return cleanUrl.replace("http", "ws") + WS_TERMINAL_PATH
    }
}
