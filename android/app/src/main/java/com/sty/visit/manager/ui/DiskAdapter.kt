package com.sty.visit.manager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sty.visit.manager.R
import com.sty.visit.manager.api.DiskStat

class DiskAdapter(private var items: List<DiskStat>) : RecyclerView.Adapter<DiskAdapter.ViewHolder>() {

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val mount: TextView = v.findViewById(R.id.diskMount)
        val usageText: TextView = v.findViewById(R.id.diskUsageText)
        val progress: ProgressBar = v.findViewById(R.id.diskProgress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_disk, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.mount.text = item.mount
        holder.usageText.text = "${item.usage.toInt()}%"
        holder.progress.progress = item.usage.toInt()
    }

    override fun getItemCount() = items.size

    fun updateData(newList: List<DiskStat>) {
        items = newList
        notifyDataSetChanged()
    }
}
