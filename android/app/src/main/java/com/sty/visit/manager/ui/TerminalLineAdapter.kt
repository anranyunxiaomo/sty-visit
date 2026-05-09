package com.sty.visit.manager.ui

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sty.visit.manager.util.AnsiParser

/**
 * 终极流式终端适配器 (Stream-oriented Adapter)
 * 已升级：支持 \r (回车) 物理重置逻辑，完美解决回显重复感。
 */
class TerminalLineAdapter : RecyclerView.Adapter<TerminalLineAdapter.ViewHolder>() {
    private val lines = mutableListOf<CharSequence>()
    private var currentLineBuffer = StringBuilder()

    fun clear() {
        lines.clear()
        currentLineBuffer.setLength(0)
        notifyDataSetChanged()
    }

    /**
     * 流式处理 ANSI 流数据
     */
    fun appendOutput(text: String) {
        if (text.isEmpty()) return
        
        // [UX FIX] 处理终端清屏指令 \e[2J 或 \e[H\e[2J
        if (text.contains("\u001B[2J") || text.contains("\u001B[H\u001B[2J")) {
            lines.clear()
            currentLineBuffer.setLength(0)
            notifyDataSetChanged()
        }

        // 预处理：处理回车符 \r (通常用于光标复位)
        var processedText = text
        if (text.contains("\r")) {
            // 如果包含 \r，简易策略：只保留最后一次 \r 之后的内容（模拟行内覆盖）
            // 复杂策略：这里目前处理为“如果以 \r 开头，尝试清空当前行缓冲区”
            if (text.startsWith("\r")) {
                currentLineBuffer.setLength(0)
                processedText = text.substring(1)
            }
            // 替换所有 \r\n 为 \n 统一处理
            processedText = processedText.replace("\r\n", "\n")
            // 移除孤立的 \r (防止显示乱码)
            processedText = processedText.replace("\r", "")
        }
        
        val parts = processedText.split("\n")
        for (i in parts.indices) {
            currentLineBuffer.append(parts[i])
            
            if (i < parts.size - 1) {
                // 遇到换行，提交当前行
                commitCurrentLine()
            } else {
                // 最后一部分（可能没有换行），更新最后一行预览
                // 注意：如果最后一部分为空字符串且前面已经 commit 了，则不需要 update
                if (parts[i].isNotEmpty() || i == 0) {
                    updateLastLine()
                }
            }
        }
    }

    private fun commitCurrentLine() {
        val parsed = AnsiParser.parse(currentLineBuffer.toString())
        lines.add(parsed)
        currentLineBuffer.setLength(0)
        notifyItemInserted(lines.size - 1)
        
        // 物理内存防御
        if (lines.size > 2000) {
            lines.removeAt(0)
            notifyItemRemoved(0)
        }
    }

    private fun updateLastLine() {
        val parsed = AnsiParser.parse(currentLineBuffer.toString())
        if (lines.isEmpty()) {
            lines.add(parsed)
            notifyItemInserted(0)
        } else {
            lines[lines.size - 1] = parsed
            notifyItemChanged(lines.size - 1)
        }
    }

    fun addLine(text: String) = appendOutput(text + "\n")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val tv = TextView(parent.context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(16, 2, 16, 2)
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        (holder.itemView as TextView).text = lines[position]
    }

    override fun getItemCount() = lines.size
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
