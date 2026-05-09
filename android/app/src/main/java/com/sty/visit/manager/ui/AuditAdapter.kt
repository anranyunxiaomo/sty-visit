package com.sty.visit.manager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sty.visit.manager.R
import com.sty.visit.manager.api.AuditLog

class AuditAdapter(
    private var logs: List<AuditLog>
) : RecyclerView.Adapter<AuditAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val actionTxt: TextView = view.findViewById(R.id.logAction)
        val timeTxt: TextView = view.findViewById(R.id.logTime)
        val detailTxt: TextView = view.findViewById(R.id.logDetail)
        val ipTxt: TextView = view.findViewById(R.id.logIp)
        val statusIndicator: View = view.findViewById(R.id.statusIndicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_audit_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val log = logs[position]
        holder.actionTxt.text = log.action
        holder.timeTxt.text = log.timestamp
        holder.detailTxt.text = log.detail
        holder.ipTxt.text = log.ip

        // 根据风险级别或状态设置圆点颜色
        val bgRes = when (log.riskLevel.uppercase()) {
            "SECURITY", "HIGH", "CRITICAL" -> R.drawable.shape_circle_red
            "WARNING", "MEDIUM" -> R.drawable.shape_circle_orange
            else -> {
                if (log.status.uppercase() == "FAILURE") R.drawable.shape_circle_red
                else R.drawable.shape_circle_green
            }
        }
        holder.statusIndicator.setBackgroundResource(bgRes)
    }

    override fun getItemCount() = logs.size

    fun updateData(newLogs: List<AuditLog>) {
        logs = newLogs
        notifyDataSetChanged()
    }
}
