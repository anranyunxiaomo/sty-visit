package com.sty.visit.manager.ui

import android.view.LayoutInflater
import android.graphics.Color
import androidx.core.content.ContextCompat
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sty.visit.manager.R
import com.sty.visit.manager.api.FileInfo

class FileAdapter(
    private var files: List<FileInfo>,
    private val onClick: (FileInfo) -> Unit,
    private val onLongClick: (FileInfo) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    var isSelectMode = false
        set(value) {
            field = value
            if (!value) selectedPaths.clear()
            notifyDataSetChanged()
        }

    val selectedPaths = mutableSetOf<String>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fileName: TextView = view.findViewById(R.id.fileName)
        val fileMeta: TextView = view.findViewById(R.id.fileMeta)
        val fileIcon: ImageView = view.findViewById(R.id.fileIcon)
        val btnMenu: ImageView = view.findViewById(R.id.btnMenu)
        val fileCheck: CheckBox = view.findViewById(R.id.fileCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.fileName.text = file.name
        holder.fileMeta.text = "${file.updateTime} · ${if (file.isDir) "文件夹" else formatSize(file.size)}"
        
        holder.fileIcon.setImageResource(if (file.isDir) R.drawable.ic_folder else R.drawable.ic_file)
        // 使用 iOS 标准调色盘染色：文件夹使用经典蓝色，文件使用中性灰色
        holder.fileIcon.setColorFilter(if (file.isDir) 0xFF007AFF.toInt() else 0xFF8E8E93.toInt())
        
        // 多选模式适配
        holder.fileCheck.visibility = if (isSelectMode) View.VISIBLE else View.GONE
        holder.fileCheck.isChecked = selectedPaths.contains(file.path)
        holder.btnMenu.visibility = if (isSelectMode) View.GONE else View.VISIBLE

        holder.itemView.setOnClickListener {
            if (isSelectMode) {
                toggleSelection(file.path)
            } else {
                onClick(file)
            }
        }

        holder.itemView.setOnLongClickListener { 
            if (!isSelectMode) {
                isSelectMode = true
                toggleSelection(file.path)
            }
            true
        }

        holder.btnMenu.setOnClickListener { onLongClick(file) }
    }

    private fun toggleSelection(path: String) {
        if (selectedPaths.contains(path)) selectedPaths.remove(path)
        else selectedPaths.add(path)
        notifyDataSetChanged()
        onSelectionChanged(selectedPaths.size)
    }

    override fun getItemCount() = files.size

    fun updateData(newFiles: List<FileInfo>) {
        files = newFiles
        notifyDataSetChanged()
    }

    private fun formatSize(size: Long): String {
        if (size < 1024) return "$size B"
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0)
        return String.format("%.1f MB", size / (1024.0 * 1024.0))
    }
}
