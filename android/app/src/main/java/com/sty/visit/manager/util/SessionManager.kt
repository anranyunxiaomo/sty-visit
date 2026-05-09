package com.sty.visit.manager.util

import com.sty.visit.manager.api.Bookmark

object SessionManager {
    @Volatile var authToken: String? = null
    @Volatile var sessionKey: String? = null
    @Volatile var currentBookmark: Bookmark? = null
    @Volatile var serverUrl: String = ""
    
    // [ALIGNMENT] 实现终端多屏协同与 Session 保持
    @Volatile var activeWebSocket: okhttp3.WebSocket? = null
    val terminalOutputCache = java.util.concurrent.CopyOnWriteArrayList<String>()
    var terminalCallback: ((String) -> Unit)? = null
    
    val isAuthValid: Boolean
        get() = !authToken.isNullOrEmpty()

    fun reset() {
        activeWebSocket?.close(1000, "Logout")
        activeWebSocket = null
        terminalOutputCache.clear()
        authToken = null
        sessionKey = null
        currentBookmark = null
    }
}
