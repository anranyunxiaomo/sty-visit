package com.sty.visit.manager.util

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.sty.visit.manager.R

object UIUtils {
    fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun getColor(context: Context, resId: Int): Int {
        return androidx.core.content.ContextCompat.getColor(context, resId)
    }

    fun showConfirmDialog(context: Context, title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(context.getString(R.string.dialog_confirm)) { _, _ -> onConfirm() }
            .setNegativeButton(context.getString(R.string.dialog_cancel), null)
            .show()
    }

    fun showInputDialog(context: Context, title: String, initialValue: String = "", hint: String = "", onConfirm: (String) -> Unit) {
        val input = android.widget.EditText(context).apply {
            setText(initialValue)
            setHint(hint)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(context.getString(R.string.dialog_confirm)) { _, _ -> onConfirm(input.text.toString()) }
            .setNegativeButton(context.getString(R.string.dialog_cancel), null)
            .show()
    }

    /**
     * [ALIGNMENT] 实现轻量化代码高亮编辑器
     * 对齐功能清单 4.2 "提供轻量化代码高亮显示"
     */
    fun showCodeEditorDialog(context: Context, title: String, content: String, onConfirm: (String) -> Unit) {
        val input = android.widget.EditText(context).apply {
            setText(content)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 14f
            setBackgroundColor(android.graphics.Color.parseColor("#1C1C1E"))
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.TOP
            setPadding(32, 32, 32, 32)
            
            // 基础高亮逻辑：关键字着色
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (s == null) return
                    val text = s.toString()
                    val keywords = listOf("fun", "class", "import", "package", "val", "var", "if", "else", "return", "void", "public", "private", "protected")
                    
                    // 暂时禁用监听以防止无限递归
                    removeTextChangedListener(this)
                    
                    // 清除旧样式并应用新样式
                    val colorSpan = android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#FF453A"))
                    keywords.forEach { word ->
                        var index = text.indexOf(word)
                        while (index >= 0) {
                            s.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#5E5CE6")), index, index + word.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            index = text.indexOf(word, index + word.length)
                        }
                    }
                    
                    addTextChangedListener(this)
                }
            })
        }
        
        val container = android.widget.FrameLayout(context).apply {
            setPadding(40, 20, 40, 20)
            addView(input, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 800))
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("保存", { _, _ -> onConfirm(input.text.toString()) })
            .setNegativeButton("取消", null)
            .show()
    }

    fun showListDialog(context: Context, title: String, items: Array<String>, onClick: (Int) -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(items) { _, which -> onClick(which) }
            .show()
    }

    fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    fun getFileName(context: Context, uri: android.net.Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
}
