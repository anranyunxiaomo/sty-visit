package com.sty.visit.manager.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sty.visit.manager.MainActivity
import com.sty.visit.manager.R
import com.sty.visit.manager.api.Bookmark
import com.sty.visit.manager.util.CryptoUtils
import com.sty.visit.manager.util.SessionManager
import kotlinx.coroutines.launch

class BookmarkFragment : Fragment() {

    private lateinit var rvBookmarkManager: RecyclerView
    private lateinit var btnAddBookmark: android.widget.TextView
    private lateinit var adapter: BookmarkManagerAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_bookmark, container, false)
        rvBookmarkManager = view.findViewById(R.id.rvBookmarkManager)
        btnAddBookmark = view.findViewById(R.id.btnAddBookmark)

        rvBookmarkManager.layoutManager = LinearLayoutManager(context)
        adapter = BookmarkManagerAdapter(emptyList(), ::showEditDialog, ::deleteBookmark)
        rvBookmarkManager.adapter = adapter

        btnAddBookmark.setOnClickListener { showEditDialog(null) }

        loadBookmarks()

        return view
    }

    private fun loadBookmarks() {
        lifecycleScope.launch {
            try {
                val response = (activity as MainActivity).getApiService().getBookmarks()
                if (response.isSuccessful) {
                    val bookmarks = response.body()?.data ?: emptyList()
                    adapter.updateData(bookmarks)
                } else {
                    (activity as MainActivity).showToast("加载书签失败")
                }
            } catch (e: Exception) {
                (activity as MainActivity).showToast("网络错误")
            }
        }
    }

    private fun showEditDialog(bookmark: Bookmark?) {
        val builder = AlertDialog.Builder(context)
        val view = layoutInflater.inflate(R.layout.dialog_bookmark_edit, null)
        
        val etName = view.findViewById<EditText>(R.id.etBookmarkName)
        val etHost = view.findViewById<EditText>(R.id.etBookmarkHost)
        val etPort = view.findViewById<EditText>(R.id.etBookmarkPort)
        val etUser = view.findViewById<EditText>(R.id.etBookmarkUser)
        val etPwd = view.findViewById<EditText>(R.id.etBookmarkPwd)

        if (bookmark != null) {
            etName.setText(bookmark.name)
            etHost.setText(bookmark.host)
            etPort.setText(bookmark.port)
            etUser.setText(bookmark.user)
            // 解密展示正常密码
            val sKey = SessionManager.sessionKey ?: ""
            val decryptedPwd = CryptoUtils.decrypt(bookmark.pwd ?: bookmark.key ?: "", sKey) ?: ""
            etPwd.setText(decryptedPwd)
        } else {
            etPort.setText("22")
        }

        builder.setView(view)
            .setTitle(if (bookmark == null) "新增书签" else "编辑书签")
            .setPositiveButton("保存") { _, _ ->
                val newName = etName.text.toString().trim()
                val newHost = etHost.text.toString().trim()
                val newPort = etPort.text.toString().trim()
                val newUser = etUser.text.toString().trim()
                val newPwd = etPwd.text.toString().trim()
                
                if (newName.isEmpty() || newHost.isEmpty() || newUser.isEmpty()) {
                    (activity as MainActivity).showToast("请填写完整信息")
                    return@setPositiveButton
                }

                // 加密输出
                val sKey = SessionManager.sessionKey ?: ""
                val encryptedPwd = CryptoUtils.encrypt(newPwd, sKey)

                val newBookmark = Bookmark(
                    id = bookmark?.id,
                    name = newName,
                    host = newHost,
                    port = newPort.ifEmpty { "22" },
                    user = newUser,
                    pwd = encryptedPwd,
                    key = null,
                    isKeyAuth = false
                )
                saveBookmark(newBookmark)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveBookmark(bookmark: Bookmark) {
        lifecycleScope.launch {
            try {
                val response = (activity as MainActivity).getApiService().saveBookmark(bookmark)
                if (response.isSuccessful && response.body()?.code == 200) {
                    (activity as MainActivity).showToast("保存成功")
                    loadBookmarks()
                    // 同步刷新终端界面的书签
                    (activity as MainActivity).refreshSshBookmarks()
                } else {
                    (activity as MainActivity).showToast("保存失败")
                }
            } catch (e: Exception) {
                (activity as MainActivity).showToast("保存异常: ${e.message}")
            }
        }
    }

    private fun deleteBookmark(bookmark: Bookmark) {
        AlertDialog.Builder(context)
            .setTitle("确认删除")
            .setMessage("确定要删除书签 ${bookmark.name} 吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val response = (activity as MainActivity).getApiService().deleteBookmark(bookmark.id!!)
                        if (response.isSuccessful && response.body()?.code == 200) {
                            (activity as MainActivity).showToast("删除成功")
                            loadBookmarks()
                            // 同步刷新终端界面的书签
                            (activity as MainActivity).refreshSshBookmarks()
                        } else {
                            (activity as MainActivity).showToast("删除失败")
                        }
                    } catch (e: Exception) {
                        (activity as MainActivity).showToast("删除异常: ${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
