package com.sty.visit.manager.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sty.visit.manager.MainActivity
import com.sty.visit.manager.R
import com.sty.visit.manager.api.Bookmark
import com.sty.visit.manager.api.DiskStat
import com.sty.visit.manager.api.SystemStats

class MonitorFragment : Fragment() {

    private lateinit var uptimeText: TextView
    private lateinit var cpuText: TextView
    private lateinit var memText: TextView
    private lateinit var h5AccessSwitch: SwitchCompat
    private lateinit var diskRecyclerView: RecyclerView
    
    private var diskAdapter = DiskAdapter(emptyList())
    
    private var pendingStats: SystemStats? = null
    private var pendingLatency: Long? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_monitor, container, false)
        uptimeText = view.findViewById(R.id.uptimeText)
        cpuText = view.findViewById(R.id.cpuText)
        memText = view.findViewById(R.id.memText)
        h5AccessSwitch = view.findViewById(R.id.h5AccessSwitch)
        diskRecyclerView = view.findViewById(R.id.diskRecyclerView)
        
        diskRecyclerView.layoutManager = LinearLayoutManager(context)
        diskRecyclerView.adapter = diskAdapter
        
        h5AccessSwitch.setOnCheckedChangeListener { _, isChecked ->
            (activity as? MainActivity)?.toggleH5Access(isChecked)
        }
        
        // 恢复延迟数据
        pendingStats?.let { updateStats(it, pendingLatency ?: 0L) }
        
        return view
    }

    fun updateStats(stats: SystemStats, latency: Long) {
        if (!::uptimeText.isInitialized) {
            pendingStats = stats
            pendingLatency = latency
            return
        }
        uptimeText.text = "${stats.uptime ?: "N/A"} (RTT: ${latency}ms)"
        cpuText.text = "${String.format("%.1f", stats.cpu)}%"
        memText.text = "${String.format("%.1f", stats.mem)}%"
        
        stats.disks?.let { diskAdapter.updateData(it) }
        pendingStats = null
    }
    
    fun setH5SwitchSilently(checked: Boolean) {
        if (::h5AccessSwitch.isInitialized) {
            h5AccessSwitch.setOnCheckedChangeListener(null)
            h5AccessSwitch.isChecked = checked
            h5AccessSwitch.setOnCheckedChangeListener { _, isChecked ->
                (activity as? MainActivity)?.toggleH5Access(isChecked)
            }
        }
    }
}
