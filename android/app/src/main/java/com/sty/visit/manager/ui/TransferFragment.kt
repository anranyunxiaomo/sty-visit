package com.sty.visit.manager.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sty.visit.manager.MainActivity
import com.sty.visit.manager.R

class TransferFragment : Fragment() {

    private lateinit var transferRecyclerView: RecyclerView
    private lateinit var btnBack: ImageView
    private lateinit var adapter: TransferAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_transfer, container, false)
        transferRecyclerView = view.findViewById(R.id.transferRecyclerView)
        btnBack = view.findViewById(R.id.btnBack)
        val btnClear: android.widget.TextView = view.findViewById(R.id.btnClear)

        transferRecyclerView.layoutManager = LinearLayoutManager(context)
        adapter = TransferAdapter(emptyList())
        transferRecyclerView.adapter = adapter

        btnBack.setOnClickListener {
            // 返回上一个 Fragment (文件列表)
            (activity as? MainActivity)?.switchToFileList()
        }

        btnClear.setOnClickListener {
            (activity as? MainActivity)?.transferManager?.clearFinishedTasks()
        }

        // 监听底层状态流
        lifecycleScope.launchWhenStarted {
            val activity = activity as? MainActivity
            activity?.transferManager?.tasks?.collect { tasks ->
                adapter.updateTasks(tasks.reversed())
            }
        }

        return view
    }
}
