package com.sty.visit.manager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sty.visit.manager.R
import com.sty.visit.manager.api.Bookmark

class BookmarkManagerAdapter(
    private var bookmarks: List<Bookmark>,
    private val onEdit: (Bookmark) -> Unit,
    private val onDelete: (Bookmark) -> Unit
) : RecyclerView.Adapter<BookmarkManagerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvBookmarkName: TextView = view.findViewById(R.id.tvBookmarkName)
        val tvBookmarkHost: TextView = view.findViewById(R.id.tvBookmarkHost)
        val tvBookmarkUser: TextView = view.findViewById(R.id.tvBookmarkUser)
        val btnEditBookmark: ImageView = view.findViewById(R.id.btnEditBookmark)
        val btnDeleteBookmark: ImageView = view.findViewById(R.id.btnDeleteBookmark)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bookmark_manager, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = bookmarks[position]
        holder.tvBookmarkName.text = item.name
        holder.tvBookmarkHost.text = "${item.host}:${item.port}"
        holder.tvBookmarkUser.text = item.user

        holder.btnEditBookmark.setOnClickListener { onEdit(item) }
        holder.btnDeleteBookmark.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = bookmarks.size

    fun updateData(newBookmarks: List<Bookmark>) {
        bookmarks = newBookmarks
        notifyDataSetChanged()
    }
}
