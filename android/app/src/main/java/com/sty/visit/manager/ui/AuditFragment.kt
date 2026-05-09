package com.sty.visit.manager.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.sty.visit.manager.MainActivity
import com.sty.visit.manager.R
import com.sty.visit.manager.api.AuditLog
import com.sty.visit.manager.util.UIUtils
import kotlinx.coroutines.launch

class AuditFragment : Fragment() {

    lateinit var swipeRefresh: SwipeRefreshLayout
    lateinit var auditRecyclerView: RecyclerView
    private val adapter = AuditAdapter(emptyList())
    private var pendingLogs: List<AuditLog>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_audit, container, false)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        auditRecyclerView = view.findViewById(R.id.auditRecyclerView)
        
        auditRecyclerView.layoutManager = LinearLayoutManager(context)
        auditRecyclerView.adapter = adapter

        swipeRefresh.setOnRefreshListener {
            (activity as? MainActivity)?.fetchAuditLogs()
        }

        view.findViewById<View>(R.id.btnAuditSettings).setOnClickListener {
            showRetentionDialog()
        }

        pendingLogs?.let { updateLogs(it) }

        return view
    }

    private fun showRetentionDialog() {
        val activity = activity as? MainActivity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = activity.getApiService().getAuditRetention()
                if (resp.isSuccessful) {
                    val currentDays = (resp.body()?.data?.get("days") as? Double)?.toInt() ?: 31
                    UIUtils.showInputDialog(requireContext(), "日志保留天数", initialValue = currentDays.toString()) { newVal ->
                        val days = newVal.toIntOrNull()
                        if (days != null && days > 0) {
                            updateRetention(days)
                        } else {
                            UIUtils.showToast(requireContext(), "请输入有效数字")
                        }
                    }
                }
            } catch (e: Exception) { UIUtils.showToast(requireContext(), "加载配置失败") }
        }
    }

    private fun updateRetention(days: Int) {
        val activity = activity as? MainActivity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (activity.getApiService().updateAuditRetention(mapOf("days" to days)).isSuccessful) {
                    UIUtils.showToast(requireContext(), "设置已更新为 $days 天")
                }
            } catch (e: Exception) { UIUtils.showToast(requireContext(), "保存失败") }
        }
    }

    fun updateLogs(logs: List<AuditLog>) {
        if (!::swipeRefresh.isInitialized) {
            pendingLogs = logs
            return
        }
        adapter.updateData(logs)
        swipeRefresh.isRefreshing = false
        pendingLogs = null
    }

    fun setRefreshing(refreshing: Boolean) {
        if (::swipeRefresh.isInitialized) {
            swipeRefresh.isRefreshing = refreshing
        }
    }
}
