package com.sty.visit.manager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sty.visit.manager.R
import com.sty.visit.manager.transfer.TransferManager.TransferTask

class TransferAdapter(private var tasks: List<TransferTask>) : RecyclerView.Adapter<TransferAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val transferName: TextView = view.findViewById(R.id.transferName)
        val transferStatus: TextView = view.findViewById(R.id.transferStatus)
        val transferPath: TextView = view.findViewById(R.id.transferPath)
        val transferProgress: ProgressBar = view.findViewById(R.id.transferProgress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_transfer, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val task = tasks[position]
        val isUpload = task.type == "UPLOAD"
        val prefix = if (isUpload) "【上传】 " else "【下载】 "
        holder.transferName.text = prefix + task.name
        holder.transferPath.text = if (isUpload) "上传至: ${task.targetPath}" else "保存至: ${task.targetPath}"
        
        var statusText = ""
        when (task.status) {
            "DOWNLOADING", "UPLOADING" -> {
                statusText = "进行中 (${task.progress}%)"
                holder.transferProgress.isIndeterminate = task.progress <= 0
                holder.transferStatus.setTextColor(android.graphics.Color.parseColor("#007AFF")) // iOS blue
            }
            "COMPLETED" -> {
                statusText = "已完成"
                holder.transferProgress.isIndeterminate = false
                holder.transferProgress.progress = 100
                holder.transferStatus.setTextColor(android.graphics.Color.parseColor("#34C759")) // iOS green
            }
            "ERROR" -> {
                val reason = task.errorMessage?.let { " - $it" } ?: ""
                statusText = "失败$reason"
                holder.transferProgress.isIndeterminate = false
                holder.transferStatus.setTextColor(android.graphics.Color.parseColor("#FF3B30")) // iOS red
            }
            else -> {
                statusText = "等待中"
                holder.transferProgress.isIndeterminate = true
                holder.transferStatus.setTextColor(android.graphics.Color.GRAY)
            }
        }
        
        holder.transferStatus.text = statusText
        if (task.status != "COMPLETED") {
            holder.transferProgress.progress = task.progress
        }
    }

    override fun getItemCount() = tasks.size

    fun updateTasks(newTasks: List<TransferTask>) {
        this.tasks = newTasks
        notifyDataSetChanged()
    }
}
