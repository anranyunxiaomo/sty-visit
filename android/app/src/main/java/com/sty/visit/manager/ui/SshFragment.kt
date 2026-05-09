package com.sty.visit.manager.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.sty.visit.manager.MainActivity
import com.sty.visit.manager.R
import com.sty.visit.manager.api.Bookmark

class SshFragment : Fragment() {

    private lateinit var sshHostInput: EditText
    private lateinit var sshPortInput: EditText
    private lateinit var sshUserInput: EditText
    private lateinit var sshPwdInput: EditText
    private lateinit var saveBookmarkRealCheck: CheckBox
    private lateinit var sshConnectBtn: Button
    private lateinit var bookmarkList: androidx.recyclerview.widget.RecyclerView
    private var pendingBookmarks: List<Bookmark>? = null
    private var onSelectCallback: ((Bookmark) -> Unit)? = null
    private var onDeleteCallback: ((Bookmark) -> Unit)? = null
    private var onEditCallback: ((Bookmark) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_ssh, container, false)
        
        sshHostInput = view.findViewById(R.id.sshHostInput)
        sshPortInput = view.findViewById(R.id.sshPortInput)
        sshUserInput = view.findViewById(R.id.sshUserInput)
        sshPwdInput = view.findViewById(R.id.sshPwdInput)
        saveBookmarkRealCheck = view.findViewById(R.id.saveBookmarkRealCheck)
        sshConnectBtn = view.findViewById(R.id.sshConnectBtn)
        bookmarkList = view.findViewById(R.id.bookmarkListSsh)
        val btnManageBookmarks = view.findViewById<android.widget.TextView>(R.id.btnManageBookmarks)

        bookmarkList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context, androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false)
        bookmarkList.adapter = BookmarkAdapter(emptyList(), {}, {}, {})

        btnManageBookmarks.setOnClickListener {
            (activity as? MainActivity)?.showBookmarkManager()
        }

        sshConnectBtn.setOnClickListener {
            val host = sshHostInput.text.toString()
            val port = sshPortInput.text.toString().ifEmpty { "22" }
            val user = sshUserInput.text.toString()
            val pwd = sshPwdInput.text.toString()

            if (host.isEmpty() || user.isEmpty() || pwd.isEmpty()) {
                (activity as? MainActivity)?.showToast("请完善连接信息")
                return@setOnClickListener
            }

            val sKey = com.sty.visit.manager.util.SessionManager.sessionKey ?: ""
            val encryptedPwd = com.sty.visit.manager.util.CryptoUtils.encrypt(pwd, sKey)

            val bookmark = Bookmark(
                name = "Manual",
                host = host,
                port = port,
                user = user,
                pwd = encryptedPwd
            )
            
            (activity as? MainActivity)?.initiateSshSession(bookmark, saveBookmarkRealCheck.isChecked)
        }

        pendingBookmarks?.let { list ->
            onSelectCallback?.let { select ->
                onDeleteCallback?.let { delete ->
                    onEditCallback?.let { edit ->
                        updateBookmarks(list, select, delete, edit)
                    }
                }
            }
        }

        return view
    }

    fun updateBookmarks(list: List<Bookmark>, onSelect: (Bookmark) -> Unit, onDelete: (Bookmark) -> Unit, onEdit: (Bookmark) -> Unit) {
        if (!::bookmarkList.isInitialized) {
            pendingBookmarks = list
            onSelectCallback = onSelect
            onDeleteCallback = onDelete
            onEditCallback = onEdit
            return
        }
        bookmarkList.adapter = BookmarkAdapter(list, onSelect, onDelete, onEdit)
        pendingBookmarks = null
    }

    fun fillFields(b: Bookmark) {
        if (!::sshHostInput.isInitialized) return
        sshHostInput.setText(b.host)
        sshPortInput.setText(b.port)
        sshUserInput.setText(b.user)
        
        val sKey = com.sty.visit.manager.util.SessionManager.sessionKey ?: ""
        val decryptedPwd = com.sty.visit.manager.util.CryptoUtils.decrypt(b.pwd ?: b.key ?: "", sKey) ?: ""
        sshPwdInput.setText(decryptedPwd)
    }
    
    fun setSaveCheck(checked: Boolean) {
        if (::saveBookmarkRealCheck.isInitialized) {
            saveBookmarkRealCheck.isChecked = checked
        }
    }
}
