package com.sty.visit.manager.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sty.visit.manager.MainActivity
import com.sty.visit.manager.R
import com.sty.visit.manager.api.Snippet
import com.sty.visit.manager.util.UIUtils
import kotlinx.coroutines.launch

class SnippetFragment : Fragment() {

    private lateinit var snippetRecyclerView: RecyclerView
    private lateinit var btnBack: ImageView
    private lateinit var btnAddSnippet: TextView
    private lateinit var adapter: SnippetManagerAdapter

    private var allSnippets: MutableList<Snippet> = mutableListOf()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_snippet, container, false)
        snippetRecyclerView = view.findViewById(R.id.snippetRecyclerView)
        btnBack = view.findViewById(R.id.btnBack)
        btnAddSnippet = view.findViewById(R.id.btnAddSnippet)

        snippetRecyclerView.layoutManager = LinearLayoutManager(context)
        adapter = SnippetManagerAdapter(emptyList(), this::showEditSnippetDialog, this::confirmDeleteSnippet)
        snippetRecyclerView.adapter = adapter

        btnBack.setOnClickListener {
            (activity as? MainActivity)?.switchToTerminal()
        }

        btnAddSnippet.setOnClickListener {
            showAddSnippetDialog()
        }

        fetchSnippets()

        return view
    }

    private fun fetchSnippets() {
        val activity = activity as? MainActivity ?: return
        lifecycleScope.launch {
            try {
                val resp = (activity.application as com.sty.visit.manager.di.StyVisitApplication).container.getApiService().getSnippets()
                if (resp.isSuccessful) {
                    allSnippets = (resp.body()?.data ?: emptyList()).toMutableList()
                    adapter.updateSnippets(allSnippets)
                }
            } catch (e: Exception) {}
        }
    }

    private fun showAddSnippetDialog() {
        UIUtils.showInputDialog(requireContext(), "新增常用指令", hint = "指令名称") { name ->
            UIUtils.showCodeEditorDialog(requireContext(), "指令内容", "ls -la") { cmd ->
                allSnippets.add(Snippet(name, cmd, "User"))
                saveSnippetsToServer()
            }
        }
    }

    private fun showEditSnippetDialog(s: Snippet) {
        UIUtils.showInputDialog(requireContext(), "编辑指令名称", initialValue = s.name) { name ->
            UIUtils.showCodeEditorDialog(requireContext(), "编辑指令内容", s.command) { cmd ->
                val index = allSnippets.indexOf(s)
                if (index != -1) {
                    allSnippets[index] = Snippet(name, cmd, s.category)
                    saveSnippetsToServer()
                }
            }
        }
    }

    private fun confirmDeleteSnippet(s: Snippet) {
        UIUtils.showConfirmDialog(requireContext(), "删除指令", "确定删除指令 ${s.name} 吗？") {
            allSnippets.remove(s)
            saveSnippetsToServer()
        }
    }

    private fun saveSnippetsToServer() {
        val activity = activity as? MainActivity ?: return
        lifecycleScope.launch {
            try {
                (activity.application as com.sty.visit.manager.di.StyVisitApplication).container.getApiService().saveSnippets(allSnippets)
                adapter.updateSnippets(allSnippets)
                UIUtils.showToast(requireContext(), "指令库已同步")
                activity.terminalFragment.refreshSnippets() // Tell TerminalFragment to refresh
            } catch (e: Exception) { UIUtils.showToast(requireContext(), "同步失败") }
        }
    }
}
