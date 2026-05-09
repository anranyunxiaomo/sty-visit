package com.sty.visit.manager

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.sty.visit.manager.api.Bookmark
import com.sty.visit.manager.api.FileInfo
import com.sty.visit.manager.di.AppContainer
import com.sty.visit.manager.ui.*
import com.sty.visit.manager.util.*
import com.sty.visit.manager.transfer.TransferManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val TAG = "VISIT_LOG"

    private lateinit var setupOverlay: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var topTitle: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var btnLogout: ImageView
    private lateinit var progressBar: ProgressBar

    // 授权页组件
    private lateinit var serverIpInput: EditText
    private lateinit var serverPortInput: EditText
    private lateinit var adminPwdInput: EditText
    private lateinit var loginBtn: Button
    private lateinit var serverConfigGroup: View
    private lateinit var toggleServerConfig: TextView

    lateinit var transferManager: TransferManager
    var currentPath: String = "."

    private val monitorFragment = MonitorFragment()
    private val sshFragment = SshFragment()
    val terminalFragment = TerminalFragment()
    private val filesFragment = FilesFragment()
    private val auditFragment = AuditFragment()
    private val transferFragment = TransferFragment()
    private val snippetFragment = SnippetFragment()
    private val bookmarkFragment = BookmarkFragment()

    enum class AppStage { INITIAL, AUTHENTICATED, TUNNEL_ESTABLISHED }
    private var currentStage = AppStage.INITIAL
    private var editingBookmarkId: String? = null
    
    // 业务循环任务 (电量感知：进入后台自动停止)
    private var statsJob: Job? = null
    private var titleClickCount = 0

    val container by lazy { (application as com.sty.visit.manager.di.StyVisitApplication).container }

    fun getApiService() = container.getApiService()

    val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { performFileUpload(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        loadPrefs()
        
        transferManager = TransferManager(this) { container.getApiService() }
        updateUIStage(AppStage.INITIAL)
        Log.i(TAG, "[SYSTEM] Application Started")
    }

    private fun initViews() {
        setupOverlay = findViewById(R.id.setupOverlay)
        bottomNav = findViewById(R.id.bottomNav)
        topTitle = findViewById(R.id.topTitle)
        connectionStatus = findViewById(R.id.connectionStatus)
        btnLogout = findViewById(R.id.btnLogout)
        progressBar = findViewById(R.id.progressBar)

        serverIpInput = findViewById(R.id.serverIpInput)
        serverPortInput = findViewById(R.id.serverPortInput)
        adminPwdInput = findViewById(R.id.adminPwdInput)
        loginBtn = findViewById(R.id.loginBtn)
        serverConfigGroup = findViewById(R.id.serverConfigGroup)
        toggleServerConfig = findViewById(R.id.toggleServerConfig)
    }

    private fun setupListeners() {
        loginBtn.setOnClickListener {
            val pwd = adminPwdInput.text.toString()
            if (pwd.isEmpty()) { showToast("请输入访问密钥"); return@setOnClickListener }
            val ip = serverIpInput.text.toString()
            val port = serverPortInput.text.toString().ifEmpty { "8080" }
            if (ip.isEmpty()) { serverConfigGroup.isVisible = true; showToast("首次登录需配置服务器 IP"); return@setOnClickListener }
            val url = "http://$ip:$port"
            container.initRetrofit(url)
            performWebLogin(pwd)
        }

        toggleServerConfig.setOnClickListener {
            serverConfigGroup.isVisible = !serverConfigGroup.isVisible
            toggleServerConfig.text = if (serverConfigGroup.isVisible) "隐藏服务器配置" else "更改服务器配置"
        }

        topTitle.setOnClickListener {
            titleClickCount++
            if (titleClickCount >= 7) {
                titleClickCount = 0
                showEmergencyExit()
            }
        }
        
        btnLogout.setOnClickListener {
            UIUtils.showConfirmDialog(this, "退出登录", "确定要退出并清除当前授权状态吗？") {
                val pwd = if (currentStage == AppStage.INITIAL) adminPwdInput.text.toString() else ""
                performLogout(pwd)
            }
        }

        // [ALIGNMENT] 物理对齐：实时传输进度显示
        lifecycleScope.launchWhenStarted {
            transferManager.tasks.collect { tasks ->
                val activeTask = tasks.find { it.status == "DOWNLOADING" || it.status == "UPLOADING" }
                if (activeTask != null) {
                    progressBar.isVisible = true
                    progressBar.isIndeterminate = activeTask.progress <= 0
                    progressBar.progress = activeTask.progress
                } else {
                    progressBar.isVisible = false
                }
            }
        }

        bottomNav.setOnNavigationItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_monitor -> monitorFragment
                R.id.nav_ssh_config -> sshFragment
                R.id.nav_terminal -> terminalFragment
                R.id.nav_files -> filesFragment
                R.id.nav_audit -> auditFragment
                else -> monitorFragment
            }
            switchFragment(fragment)
            if (item.itemId == R.id.nav_files && currentStage != AppStage.INITIAL) fetchFiles(".")
            true
        }
    }

    private fun performWebLogin(key: String) {
        lifecycleScope.launch {
            try {
                Log.i(TAG, "[AUTH] Login Attempt...")
                val response = container.getApiService().login(mapOf("password" to key))
                if (response.isSuccessful && response.body()?.code == 200) {
                    val data = response.body()?.data
                    SessionManager.authToken = data?.token
                    // [ALIGNMENT] 物理对齐：使用服务端分发的动态会话密钥，确保端云加解密同步
                    SessionManager.sessionKey = data?.sessionKey
                    
                    savePrefs(key) // savePrefs 内部已不再保存 key，仅更新 IP/Port
                    updateUIStage(AppStage.AUTHENTICATED)
                    bottomNav.selectedItemId = R.id.nav_monitor
                    fetchBookmarks()
                    fetchAuditLogs()
                    startStatsLoop()
                } else { showToast("授权失败: 密钥错误或服务器异常") }
            } catch (e: Exception) {
                showToast("无法连接至服务器，请检查 IP 端口配置")
                serverConfigGroup.isVisible = true
            }
        }
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            while (true) {
                if (currentStage != AppStage.INITIAL) {
                    val start = System.currentTimeMillis()
                    try {
                        val resp = container.getApiService().getStats()
                        if (resp.isSuccessful) {
                            resp.body()?.data?.let { monitorFragment.updateStats(it, System.currentTimeMillis() - start) }
                        }
                    } catch (e: Exception) {}
                    
                    try {
                        val h5Resp = container.getApiService().getH5Status()
                        if (h5Resp.isSuccessful) {
                            val active = h5Resp.body()?.data?.get("enabled") as? Boolean ?: false
                            monitorFragment.setH5SwitchSilently(active)
                        }
                    } catch (e: Exception) {}
                }
                delay(5000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentStage != AppStage.INITIAL) startStatsLoop()
    }

    override fun onPause() {
        super.onPause()
        statsJob?.cancel()
        Log.i(TAG, "[UX] Stats Loop Paused (Battery Awareness)")
    }

    private fun switchFragment(fragment: Fragment) {
        val transaction = supportFragmentManager.beginTransaction()
        supportFragmentManager.fragments.forEach { transaction.hide(it) }
        if (!fragment.isAdded) transaction.add(R.id.nav_host_fragment, fragment)
        else transaction.show(fragment)
        transaction.commit()
    }

    fun toggleH5Access(active: Boolean) {
        lifecycleScope.launch {
            try {
                if (container.getApiService().toggleH5(mapOf("enabled" to active)).isSuccessful) {
                    showToast(if (active) "H5 面板已开启" else "H5 面板已关闭")
                }
            } catch (e: Exception) { showToast("设置失败") }
        }
    }

    fun showTransferRecords() {
        switchFragment(transferFragment)
    }

    fun showSnippetManager() {
        switchFragment(snippetFragment)
    }

    fun showBookmarkManager() {
        switchFragment(bookmarkFragment)
    }

    fun refreshSshBookmarks() {
        lifecycleScope.launch {
            try {
                val response = container.getApiService().getBookmarks()
                if (response.isSuccessful) {
                    val bookmarks = response.body()?.data ?: emptyList()
                    sshFragment.updateBookmarks(
                        bookmarks,
                        onSelect = { initiateSshSession(it, false) },
                        onDelete = { showToast("请前往书签管理页面删除") },
                        onEdit = {
                            editingBookmarkId = it.id
                            sshFragment.fillFields(it)
                            sshFragment.setSaveCheck(false)
                            showToast("已填充至输入框，可修改参数")
                        }
                    )
                }
            } catch (e: Exception) {
                showToast("同步书签失败")
            }
        }
    }

    fun switchToTerminal() {
        switchFragment(terminalFragment)
    }

    fun switchToFileList() {
        switchFragment(filesFragment)
    }

    fun initiateSshSession(b: Bookmark, save: Boolean) {
        if (save) {
            UIUtils.showInputDialog(this, "保存书签", initialValue = b.name) { name ->
                val newB = b.copy(name = name, id = editingBookmarkId)
                executeSaveBookmark(newB)
                startSshSession(newB)
                editingBookmarkId = null
            }
        } else { startSshSession(b) }
    }

    private fun startSshSession(b: Bookmark) {
        SessionManager.currentBookmark = b
        // [ALIGNMENT] 物理对齐：触发终端片段执行会话初始化
        terminalFragment.initWebSocket(container.getOkHttpClient())
        updateUIStage(AppStage.TUNNEL_ESTABLISHED)
        bottomNav.selectedItemId = R.id.nav_terminal
    }

    private fun executeSaveBookmark(b: Bookmark) {
        lifecycleScope.launch {
            try {
                container.getApiService().saveBookmark(b)
                fetchBookmarks()
            } catch (e: Exception) { Log.e(TAG, "Save Failed", e) }
        }
    }

    private fun updateUIStage(stage: AppStage) {
        currentStage = stage
        setupOverlay.isVisible = (stage == AppStage.INITIAL)
        bottomNav.isVisible = (stage != AppStage.INITIAL)
        btnLogout.isVisible = (stage != AppStage.INITIAL)
        connectionStatus.text = if (stage == AppStage.INITIAL) "未授权" else "已连接"
    }

    fun fetchBookmarks() {
        lifecycleScope.launch {
            try {
                val resp = container.getApiService().getBookmarks()
                if (resp.isSuccessful) {
                    val list = resp.body()?.data ?: emptyList()
                    sshFragment.updateBookmarks(list, { b -> loadBookmarkToSsh(b) }, { b -> showDeleteConfirm(b) }, { b -> handleEditBookmark(b) })
                }
            } catch (e: Exception) { Log.e(TAG, "Fetch Bookmarks Failed", e) }
        }
    }

    fun fetchAuditLogs() {
        lifecycleScope.launch {
            auditFragment.setRefreshing(true)
            try {
                val response = container.getApiService().getLogs()
                if (response.isSuccessful) auditFragment.updateLogs(response.body()?.data ?: emptyList())
            } catch (e: Exception) { Log.e(TAG, "Fetch Logs Failed", e) }
            finally { auditFragment.setRefreshing(false) }
        }
    }

    fun fetchFiles(path: String) {
        currentPath = path
        lifecycleScope.launch {
            filesFragment.setRefreshing(true)
            try {
                val response = container.getApiService().listFiles(path)
                if (response.isSuccessful) {
                    filesFragment.updateFiles(response.body()?.data ?: emptyList(), path)
                } else {
                    showToast("获取失败: 请确保终端已建立 SSH 连接")
                }
            } catch (e: Exception) { 
                Log.e(TAG, "Fetch Files Failed", e)
                showToast("获取失败: 服务器响应异常")
            }
            finally { filesFragment.setRefreshing(false) }
        }
    }

    fun syncPathToTerminal(path: String) {
        terminalFragment.sendCommand("cd \"$path\"\n")
        showToast("同步路径至终端: $path")
    }

    fun showFileMenu(file: FileInfo) {
        val items = arrayOf("查看/编辑", "下载", "重命名", "删除", "同步至终端")
        UIUtils.showListDialog(this, file.name, items) { which ->
            when (which) {
                0 -> fetchAndShowFileContent(file)
                1 -> startDownload(file)
                2 -> showRenameDialog(file)
                3 -> confirmDelete(file)
                4 -> syncPathToTerminal(file.path)
            }
        }
    }

    private fun fetchAndShowFileContent(file: FileInfo) {
        val ext = file.name.substringAfterLast(".", "").lowercase()
        val isImage = ext in listOf("png", "jpg", "jpeg", "gif", "webp")
        
        lifecycleScope.launch {
            progressBar.isVisible = true
            try {
                if (isImage) {
                    // [ALIGNMENT] 实现图片在线直刷预览
                    val tokenResp = container.getApiService().getDownloadToken(file.path)
                    val token = tokenResp.body()?.data?.get("token")
                    if (token != null) {
                        val imgResp = container.getApiService().downloadFile(token)
                        val bytes = imgResp.body()?.bytes()
                        if (bytes != null) {
                            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            showImagePreview(file.name, bitmap)
                        }
                    }
                } else {
                    val resp = container.getApiService().getFileContent(file.path)
                    if (resp.isSuccessful) {
                        val content = resp.body()?.data ?: ""
                        UIUtils.showCodeEditorDialog(this@MainActivity, "查看/编辑: ${file.name}", content) { newContent ->
                            saveFileContent(file.path, newContent)
                        }
                    }
                }
            } catch (e: Exception) { showToast("加载失败: ${e.message}") }
            finally { progressBar.isVisible = false }
        }
    }

    private fun showImagePreview(name: String, bitmap: android.graphics.Bitmap) {
        val iv = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        val scroller = android.widget.ScrollView(this).apply { addView(iv) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("预览: $name")
            .setView(scroller)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun saveFileContent(path: String, content: String) {
        lifecycleScope.launch {
            try {
                if (container.getApiService().saveFile(mapOf("path" to path, "content" to content)).isSuccessful) {
                    showToast("已保存")
                }
            } catch (e: Exception) { showToast("保存失败") }
        }
    }

    private fun startDownload(file: FileInfo) {
        lifecycleScope.launch {
            try {
                val resp = container.getApiService().getDownloadToken(file.path)
                if (resp.isSuccessful) {
                    val token = resp.body()?.data?.get("token") ?: return@launch
                    transferManager.enqueueDownload(file.name, token)
                    showToast("已加入下载队列")
                }
            } catch (e: Exception) { Log.e(TAG, "Download Failed", e) }
        }
    }
    
    private fun showRenameDialog(file: FileInfo) {
        UIUtils.showInputDialog(this, "重命名", initialValue = file.name) { newName ->
            lifecycleScope.launch {
                try { if (container.getApiService().renameFile(file.path, newName).isSuccessful) fetchFiles(currentPath) }
                catch (e: Exception) { Log.e(TAG, "Rename Failed", e) }
            }
        }
    }
    
    private fun confirmDelete(file: FileInfo) {
        UIUtils.showConfirmDialog(this, "确认删除", "确定删除 ${file.name}？") {
            lifecycleScope.launch {
                try { if (container.getApiService().deleteFile(file.path).isSuccessful) fetchFiles(currentPath) }
                catch (e: Exception) { Log.e(TAG, "Delete Failed", e) }
            }
        }
    }

    private fun performFileUpload(uri: android.net.Uri) {
        lifecycleScope.launch {
            progressBar.isVisible = true
            val (success, msg) = transferManager.uploadFile(uri, currentPath)
            showToast(msg)
            if (success) {
                fetchFiles(currentPath)
            }
            progressBar.isVisible = false
        }
    }

    private fun loadBookmarkToSsh(b: Bookmark) {
        sshFragment.fillFields(b)
        sshFragment.setSaveCheck(false)
        bottomNav.selectedItemId = R.id.nav_ssh_config
    }

    private fun handleEditBookmark(b: Bookmark) {
        editingBookmarkId = b.id
        sshFragment.fillFields(b)
        sshFragment.setSaveCheck(false)
        bottomNav.selectedItemId = R.id.nav_ssh_config
    }

    private fun showDeleteConfirm(b: Bookmark) {
        val bid = b.id ?: return
        UIUtils.showConfirmDialog(this, "删除书签", "确认删除 ${b.name}？") {
            lifecycleScope.launch {
                try { if (container.getApiService().deleteBookmark(bid).isSuccessful) fetchBookmarks() }
                catch (e: Exception) { Log.e(TAG, "Delete Failed", e) }
            }
        }
    }

    private fun showEmergencyExit() {
        UIUtils.showConfirmDialog(this, "紧急出口", "确定要执行全量物理擦除并退出吗？\n(服务器端数据销毁需匹配当前输入的密钥)") {
            val pwd = if (currentStage == AppStage.INITIAL) adminPwdInput.text.toString() else ""
            performLogout(pwd)
        }
    }

    private fun performLogout(password: String = "") {
        lifecycleScope.launch {
            try { 
                if (password.isNotEmpty()) {
                    container.getApiService().wipeData(mapOf("password" to password))
                }
            } catch (e: Exception) { Log.e(TAG, "Server Wipe Failed", e) }
            
            getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE).edit().clear().apply()
            SessionManager.reset()
            updateUIStage(AppStage.INITIAL)
            showToast("本地隐私已擦除，服务器记录已同步物理清零")
        }
    }

    fun showToast(msg: String) { UIUtils.showToast(this, msg) }

    private fun savePrefs(key: String) {
        val ip = serverIpInput.text.toString()
        val port = serverPortInput.text.toString().ifEmpty { "8080" }
        getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE).edit()
            .putString(AppConstants.KEY_SERVER_URL, "http://$ip:$port")
            .putString(AppConstants.KEY_LAST_IP, ip)
            .putString(AppConstants.KEY_LAST_PORT, port)
            .apply()
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
        val lastIp = prefs.getString(AppConstants.KEY_LAST_IP, "")
        val lastPort = prefs.getString(AppConstants.KEY_LAST_PORT, "8080")
        serverIpInput.setText(lastIp)
        serverPortInput.setText(lastPort)
        
        // [ALIGNMENT] 安全对齐：不自动填充密钥，确保端侧“零存证”
        adminPwdInput.setText("")
        
        serverConfigGroup.isVisible = lastIp.isNullOrEmpty()
        if (!lastIp.isNullOrEmpty()) {
            toggleServerConfig.text = "更改服务器配置"
            SessionManager.serverUrl = "http://$lastIp:$lastPort"
        }
    }
}
