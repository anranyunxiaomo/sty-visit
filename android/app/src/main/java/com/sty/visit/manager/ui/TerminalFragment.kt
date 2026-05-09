package com.sty.visit.manager.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sty.visit.manager.MainActivity
import com.sty.visit.manager.R
import com.sty.visit.manager.api.Snippet
import com.sty.visit.manager.util.UIUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.nio.charset.Charset

class TerminalFragment : Fragment() {

    private lateinit var terminalOutput: RecyclerView
    private lateinit var commandInput: EditText
    private lateinit var snippetBar: RecyclerView
    private lateinit var shortcutBar: LinearLayout
    private lateinit var btnSend: View
    private lateinit var btnReset: View

    private val terminalAdapter = TerminalLineAdapter()
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    
    private var allSnippets: MutableList<Snippet> = mutableListOf()
    private var currentEncoding = "UTF-8"
    private var decoder = java.nio.charset.Charset.forName("UTF-8").newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
    private val byteBuffer = java.nio.ByteBuffer.allocate(1024 * 64)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_terminal, container, false)
        terminalOutput = view.findViewById(R.id.terminalOutput)
        commandInput = view.findViewById(R.id.commandInput)
        snippetBar = view.findViewById(R.id.snippetBar)
        shortcutBar = view.findViewById(R.id.shortcutBar)
        btnSend = view.findViewById(R.id.btnSend)
        btnReset = view.findViewById(R.id.btnReset)

        terminalOutput.layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
        terminalOutput.adapter = terminalAdapter

        snippetBar.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)

        setupShortcutBar()
        
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // [ALIGNMENT] 从 Cache 恢复历史输出
        com.sty.visit.manager.util.SessionManager.terminalOutputCache.forEach { terminalAdapter.appendOutput(it) }
        terminalOutput.scrollToPosition(terminalAdapter.itemCount - 1)
        
        // 绑定回调钩子
        com.sty.visit.manager.util.SessionManager.terminalCallback = { text ->
            lifecycleScope.launch(Dispatchers.Main) {
                terminalAdapter.appendOutput(text)
                terminalOutput.scrollToPosition(terminalAdapter.itemCount - 1)
            }
        }

        // 检查是否需要重新初始化连接
        if (com.sty.visit.manager.util.SessionManager.activeWebSocket == null || lastConnectedBookmarkId != com.sty.visit.manager.util.SessionManager.currentBookmark?.id) {
            val client = (activity?.application as? com.sty.visit.manager.di.StyVisitApplication)?.container?.getOkHttpClient()
            if (client != null) initWebSocket(client)
        }

        btnSend.setOnClickListener { sendCommandFromInput() }
        btnReset.setOnClickListener { 
            UIUtils.showConfirmDialog(requireContext(), "重置终端", "确定要断开并重新连接终端吗？") {
                val client = (requireActivity() as MainActivity).container.getOkHttpClient()
                initWebSocket(client)
            }
        }
        commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendCommandFromInput()
                true
            } else false
        }
        
        fetchSnippets()
    }

    private fun setupShortcutBar() {
        val shortcuts = listOf("Encoding", "ESC", "Alt", "TAB", "Ctrl+C", "Ctrl+Z", "↑", "↓", "←", "→")
        shortcutBar.removeAllViews()
        shortcuts.forEach { label ->
            val displayLabel = if (label == "Encoding") currentEncoding else label
            val tv = TextView(requireContext()).apply {
                text = displayLabel
                setTextColor(if (label == "Encoding") UIUtils.getColor(requireContext(), R.color.ios_accent) else Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
                setBackgroundResource(R.drawable.bg_shortcut_btn)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dpToPx(4), 0, dpToPx(4), 0)
                }
                setOnClickListener { handleShortcut(label) }
            }
            shortcutBar.addView(tv)
        }
    }

    private fun handleShortcut(label: String) {
        val cmd = when (label) {
            "ESC" -> "\u001b"
            "Alt" -> "\u001b"
            "TAB" -> "\t"
            "Ctrl+C" -> "\u0003"
            "Ctrl+Z" -> "\u001a"
            "↑" -> "\u001b[A"
            "↓" -> "\u001b[B"
            "←" -> "\u001b[D"
            "→" -> "\u001b[C"
            "Encoding" -> {
                currentEncoding = when (currentEncoding) {
                    "UTF-8" -> "GBK"
                    "GBK" -> "GB18030"
                    "GB18030" -> "ISO-8859-1"
                    else -> "UTF-8"
                }
                setupShortcutBar()
                UIUtils.showToast(requireContext(), "已切换至 $currentEncoding")
                ""
            }
            else -> ""
        }
        if (cmd.isNotEmpty()) sendCommand(cmd)
    }

    private var lastConnectedBookmarkId: String? = null

    fun initWebSocket(client: okhttp3.OkHttpClient) {
        val b = com.sty.visit.manager.util.SessionManager.currentBookmark
        if (b == null) {
            terminalAdapter.appendOutput("\n[错误] 请先在监控页选择书签\n")
            return
        }

        // [ALIGNMENT] 物理销毁旧连接并清理 Cache
        com.sty.visit.manager.util.SessionManager.activeWebSocket?.close(1000, "Switching Bookmark")
        com.sty.visit.manager.util.SessionManager.terminalOutputCache.clear()
        terminalAdapter.clear()
        
        val baseUrl = com.sty.visit.manager.util.SessionManager.serverUrl
        if (baseUrl.isEmpty()) {
            terminalAdapter.appendOutput("\n[错误] 全局服务器地址未初始化，请尝试重新登录\n")
            return
        }
        
        val url = if (baseUrl.startsWith("ws")) baseUrl else baseUrl.replace("http", "ws") + "/ws/terminal"
        val request = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", "visit-auth.${com.sty.visit.manager.util.SessionManager.authToken}")
            .build()
        
        com.sty.visit.manager.util.SessionManager.activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastConnectedBookmarkId = b.id
                lifecycleScope.launch {
                    val sKey = com.sty.visit.manager.util.SessionManager.sessionKey ?: ""
                    val pwdRaw = com.sty.visit.manager.util.CryptoUtils.decrypt(b.pwd ?: b.key ?: "", sKey) ?: ""
                    // [UX FIX] 将初始终端列数缩小至 45，确保任何未走 alias 的命令输出也能适应手机竖屏而不换行错位
                    val authMsg = """{"type":"auth","sshHost":"${b.host}","sshPort":"${b.port}","sshUser":"${b.user}","sshPwd":"${android.util.Base64.encodeToString(pwdRaw.toByteArray(), android.util.Base64.NO_WRAP)}","cols":45,"rows":24}"""
                    webSocket.send(authMsg)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // [CORE FIX] 移除 Base64 冗余解码。直接处理 [INFO] [SYSTEM] 等文本指令。
                com.sty.visit.manager.util.SessionManager.terminalOutputCache.add(text)
                com.sty.visit.manager.util.SessionManager.terminalCallback?.invoke(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                try {
                    val raw = bytes.toByteArray()
                    val input = java.nio.ByteBuffer.wrap(raw)
                    val output = java.nio.CharBuffer.allocate(raw.size * 2)
                    
                    // [CORE FIX] 使用 stateful decoder 处理跨包断字
                    decoder.decode(input, output, false)
                    output.flip()
                    val decoded = output.toString()
                    
                    if (decoded.isNotEmpty()) {
                        com.sty.visit.manager.util.SessionManager.terminalOutputCache.add(decoded)
                        com.sty.visit.manager.util.SessionManager.terminalCallback?.invoke(decoded)
                    }
                } catch (e: Exception) {
                    val fallback = String(bytes.toByteArray(), java.nio.charset.Charset.forName(currentEncoding))
                    com.sty.visit.manager.util.SessionManager.terminalOutputCache.add(fallback)
                    com.sty.visit.manager.util.SessionManager.terminalCallback?.invoke(fallback)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                com.sty.visit.manager.util.SessionManager.terminalCallback?.invoke("\n[连接异常: ${t.message}]\n")
                com.sty.visit.manager.util.SessionManager.activeWebSocket = null
            }
        })
        fetchSnippets()
    }

    fun refreshSnippets() {
        fetchSnippets()
    }

    private fun fetchSnippets() {
        val activity = activity as? MainActivity ?: return
        lifecycleScope.launch {
            try {
                val resp = (activity.application as com.sty.visit.manager.di.StyVisitApplication).container.getApiService().getSnippets()
                if (resp.isSuccessful) {
                    allSnippets = (resp.body()?.data ?: emptyList()).toMutableList()
                    updateSnippetAdapter()
                }
            } catch (e: Exception) {}
        }
    }

    private fun updateSnippetAdapter() {
        val displayList = allSnippets.toMutableList()
        displayList.add(0, Snippet("管理", "", "ADMIN")) 
        
        snippetBar.adapter = SnippetAdapter(displayList, { s ->
            if (s.name == "管理") {
                (activity as? MainActivity)?.showSnippetManager()
            } else {
                sendCommand(s.command + "\n")
            }
        }, { s ->
            // 长按无操作，管理功能已移至独立页面
        })
    }

    private fun sendCommandFromInput() {
        val cmd = commandInput.text.toString()
        if (cmd.isNotEmpty()) {
            sendCommand(cmd + "\n")
            commandInput.setText("")
        }
    }

    fun sendCommand(cmd: String) {
        // [ALIGNMENT] 物理对齐：使用全局活跃句柄发送指令
        val bytes = cmd.toByteArray(java.nio.charset.Charset.forName(currentEncoding))
        com.sty.visit.manager.util.SessionManager.activeWebSocket?.send(okio.ByteString.of(*bytes))
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        // [ALIGNMENT] Session 保持：视图销毁时不关闭 WebSocket，仅移除 UI 回调
        com.sty.visit.manager.util.SessionManager.terminalCallback = null
    }
}
