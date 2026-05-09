package com.sty.visit.manager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sty.visit.manager.R
import com.sty.visit.manager.api.Snippet

class SnippetManagerAdapter(
    private var snippets: List<Snippet>,
    private val onEditClick: (Snippet) -> Unit,
    private val onDeleteClick: (Snippet) -> Unit
) : RecyclerView.Adapter<SnippetManagerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val snippetName: TextView = view.findViewById(R.id.snippetName)
        val snippetCommand: TextView = view.findViewById(R.id.snippetCommand)
        val btnEdit: ImageView = view.findViewById(R.id.btnEdit)
        val btnDelete: ImageView = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_snippet_manager, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val snippet = snippets[position]
        holder.snippetName.text = snippet.name
        holder.snippetCommand.text = snippet.command
        
        holder.btnEdit.setOnClickListener { onEditClick(snippet) }
        holder.btnDelete.setOnClickListener { onDeleteClick(snippet) }
    }

    override fun getItemCount() = snippets.size

    fun updateSnippets(newSnippets: List<Snippet>) {
        this.snippets = newSnippets
        notifyDataSetChanged()
    }
}
