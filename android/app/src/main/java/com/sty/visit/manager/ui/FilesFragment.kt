package com.sty.visit.manager.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.lifecycle.lifecycleScope
import com.sty.visit.manager.MainActivity
import com.sty.visit.manager.R
import com.sty.visit.manager.api.FileInfo
import com.sty.visit.manager.util.UIUtils

class FilesFragment : Fragment() {

    lateinit var fileRecyclerView: RecyclerView
    lateinit var swipeRefresh: SwipeRefreshLayout
    lateinit var currentPathText: TextView
    private lateinit var fileSearchInput: EditText
    
    private var fileAdapter: FileAdapter? = null
    private var originalFileList: List<FileInfo> = emptyList()

    private var pendingData: List<FileInfo>? = null
    private var pendingPath: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_files, container, false)
        fileRecyclerView = view.findViewById(R.id.fileRecyclerView)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        currentPathText = view.findViewById(R.id.currentPathText)
        fileSearchInput = view.findViewById(R.id.fileSearchInput)

        fileRecyclerView.layoutManager = LinearLayoutManager(context)
        
        swipeRefresh.setOnRefreshListener {
            val activity = activity as? MainActivity
            activity?.fetchFiles(activity.currentPath)
        }
        
        view.findViewById<View>(R.id.btnUpload)?.setOnClickListener {
            (activity as? MainActivity)?.pickFileLauncher?.launch("*/*")
        }

        view.findViewById<View>(R.id.btnHome)?.setOnClickListener {
            (activity as? MainActivity)?.fetchFiles("/")
        }

        view.findViewById<View>(R.id.btnBackParent)?.setOnClickListener {
            val activity = activity as? MainActivity ?: return@setOnClickListener
            val current = activity.currentPath
            if (current == "/" || current == ".") return@setOnClickListener
            val parent = if (current.endsWith("/")) {
                current.substring(0, current.length - 1).substringBeforeLast("/", "/")
            } else {
                current.substringBeforeLast("/", "/")
            }
            activity.fetchFiles(if (parent.isEmpty()) "/" else parent)
        }

        view.findViewById<View>(R.id.pathInputContainer)?.setOnClickListener {
            val activity = activity as? MainActivity ?: return@setOnClickListener
            UIUtils.showInputDialog(requireContext(), "跳转到路径", initialValue = activity.currentPath) { newPath ->
                activity.fetchFiles(newPath)
            }
        }

        view.findViewById<View>(R.id.btnSyncTerminal)?.setOnClickListener {
            val activity = activity as? MainActivity ?: return@setOnClickListener
            activity.syncPathToTerminal(activity.currentPath)
            UIUtils.showToast(requireContext(), "已尝试同步路径到终端")
        }

        view.findViewById<View>(R.id.btnTransferRecords)?.setOnClickListener {
            val activity = activity as? MainActivity ?: return@setOnClickListener
            activity.showTransferRecords()
        }

        // [ALIGNMENT] 物理实装：模糊搜索逻辑
        fileSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterFiles(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 恢复挂起的数据
        pendingData?.let { updateFiles(it, pendingPath ?: ".") }

        return view
    }

    fun updateFiles(data: List<FileInfo>, path: String) {
        if (!::fileRecyclerView.isInitialized) {
            pendingData = data
            pendingPath = path
            return
        }
        val activity = activity as? MainActivity ?: return
        originalFileList = data
        
        refreshAdapter(data)
        
        swipeRefresh.isRefreshing = false
        currentPathText.text = path
        
        // 保持之前的搜索过滤
        val query = fileSearchInput.text.toString()
        if (query.isNotEmpty()) filterFiles(query)
        
        pendingData = null
    }

    private fun filterFiles(query: String) {
        val filtered = if (query.isEmpty()) {
            originalFileList
        } else {
            originalFileList.filter { it.name.contains(query, ignoreCase = true) }
        }
        refreshAdapter(filtered)
    }

    private fun refreshAdapter(list: List<FileInfo>) {
        val activity = activity as? MainActivity ?: return
        fileRecyclerView.adapter = FileAdapter(list,
            { f -> if (f.isDir) activity.fetchFiles(f.path) else activity.showFileMenu(f) },
            { f -> activity.showFileMenu(f) },
            { _ -> }
        )
    }
    
    fun setRefreshing(refreshing: Boolean) {
        if (::swipeRefresh.isInitialized) {
            swipeRefresh.isRefreshing = refreshing
        }
    }
}
