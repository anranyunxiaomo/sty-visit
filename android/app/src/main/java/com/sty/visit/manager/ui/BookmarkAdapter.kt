package com.sty.visit.manager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sty.visit.manager.R
import com.sty.visit.manager.api.Bookmark

class BookmarkAdapter(
    private var bookmarks: List<Bookmark>,
    private val onSelect: (Bookmark) -> Unit,
    private val onDelete: (Bookmark) -> Unit,
    private val onEdit: (Bookmark) -> Unit
) : RecyclerView.Adapter<BookmarkAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.bookmarkName)
        val host: TextView = view.findViewById(R.id.bookmarkHost)
        val btnDelete: ImageView = view.findViewById(R.id.btnDelete)
        val btnEdit: ImageView = view.findViewById(R.id.btnEdit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val b = bookmarks[position]
        holder.name.text = b.name
        holder.host.text = "${b.user}@${b.host}"
        
        holder.itemView.setOnClickListener { onSelect(b) }
        holder.btnDelete.setOnClickListener { onDelete(b) }
        holder.btnEdit.setOnClickListener { onEdit(b) }
    }

    override fun getItemCount() = bookmarks.size

    fun updateData(newList: List<Bookmark>) {
        bookmarks = newList
        notifyDataSetChanged()
    }
}
