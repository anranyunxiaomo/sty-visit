package com.sty.visit.manager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sty.visit.manager.R
import com.sty.visit.manager.api.Snippet

class SnippetAdapter(
    private val snippets: List<Snippet>,
    private val onClick: (Snippet) -> Unit,
    private val onLongClick: (Snippet) -> Unit = {}
) : RecyclerView.Adapter<SnippetAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameTxt: TextView = view.findViewById(R.id.snippetName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_snippet, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val s = snippets[position]
        holder.nameTxt.text = s.name
        holder.itemView.setOnClickListener { onClick(s) }
        holder.itemView.setOnLongClickListener {
            onLongClick(s)
            true
        }
    }

    override fun getItemCount() = snippets.size
}
