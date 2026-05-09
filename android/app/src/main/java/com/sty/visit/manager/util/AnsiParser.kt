package com.sty.visit.manager.util

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

/**
 * 终极终端解析器 (Stateless & Robust)
 * 已升级：支持物理级屏蔽 OSC (终端标题) 与 CSI (非颜色控制) 垃圾字符。
 */
object AnsiParser {
    // 匹配 SGR 颜色序列: \e[...m
    private val SGR_PATTERN = Pattern.compile("\\x1B\\[([0-9;]*)m")
    
    // 匹配 OSC 序列 (如终端标题设置): \e]0;...\x07 或 \e]0;...\x1B\\
    private val OSC_PATTERN = Pattern.compile("\\x1B\\][0-9;]*;[^\\x07\\x1B]*[\\x07]|\\x1B\\][0-9;]*;[^\\x1B]*\\x1B\\\\")
    
    // 匹配其他 CSI 序列 (如清除屏幕、移动光标等，目前暂不支持物理渲染，先物理屏蔽防止乱码)
    private val CSI_OTHER_PATTERN = Pattern.compile("\\x1B\\[[0-9;?]*[A-DGHJKSTfhnsu]")

    fun parse(text: String): CharSequence {
        if (!text.contains("\u001B")) return text

        // 1. 物理清洗：移除所有 OSC (标题设置) 和 不支持的 CSI 指令
        var cleaned = OSC_PATTERN.matcher(text).replaceAll("")
        cleaned = CSI_OTHER_PATTERN.matcher(cleaned).replaceAll("")

        // 2. 颜色解析
        var currentCode = 0
        val segments = mutableListOf<AnsiSegment>()
        
        val m = SGR_PATTERN.matcher(cleaned)
        var lastPos = 0
        while (m.find()) {
            val content = cleaned.substring(lastPos, m.start())
            if (content.isNotEmpty()) {
                segments.add(AnsiSegment(content, currentCode))
            }
            
            val codeStr = m.group(1) ?: ""
            if (codeStr.isEmpty() || codeStr == "0") {
                currentCode = 0
            } else {
                val parts = codeStr.split(";")
                for (p in parts) {
                    val pCode = p.toIntOrNull() ?: continue
                    if (pCode in 30..37 || pCode in 90..97) {
                        currentCode = pCode
                    } else if (pCode == 0) {
                        currentCode = 0
                    }
                }
            }
            lastPos = m.end()
        }
        
        val lastContent = cleaned.substring(lastPos)
        if (lastContent.isNotEmpty()) {
            segments.add(AnsiSegment(lastContent, currentCode))
        }

        val result = SpannableStringBuilder()
        for (seg in segments) {
            val start = result.length
            result.append(seg.text)
            val color = getColor(seg.code)
            if (color != null) {
                result.setSpan(ForegroundColorSpan(color), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return result
    }

    private fun getColor(code: Int): Int? {
        return when (code) {
            31, 91 -> Color.parseColor("#FF453A") // Apple Red
            32, 92 -> Color.parseColor("#32D74B") // Apple Green
            33, 93 -> Color.parseColor("#FFD60A") // Apple Yellow
            34, 94 -> Color.parseColor("#0A84FF") // Apple Blue
            35, 95 -> Color.parseColor("#BF5AF2") // Apple Purple
            36, 96 -> Color.parseColor("#64D2FF") // Apple Cyan
            else -> null
        }
    }

    private data class AnsiSegment(val text: String, val code: Int)
}
