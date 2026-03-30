package com.minimalplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FileAdapter(
    private val entries: List<FileEntry>,
    private val resumeManager: ResumeManager,
    private val onClick: (FileEntry) -> Unit,
    private val isRootView: Boolean = false
) : RecyclerView.Adapter<FileAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon: TextView = view.findViewById(R.id.tvIcon)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvMeta: TextView? = view.findViewById(R.id.tvMeta)
        val tvResume: TextView? = view.findViewById(R.id.tvResume)
        val layoutText: View? = view.findViewById(R.id.layoutText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layoutId = if (isRootView) R.layout.item_library else R.layout.item_file
        val v = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        holder.tvName.text = entry.name

        if (isRootView) {
            holder.tvIcon.text = "📁"
            holder.itemView.setOnClickListener { onClick(entry) }
            return
        }

        if (entry.isDirectory) {
            holder.tvIcon.text = "📁"
            holder.tvMeta?.text = "Cartella"
            holder.tvResume?.visibility = View.GONE
            holder.tvName.alpha = 1.0f
        } else {
            val ext = entry.name.substringAfterLast('.', "").uppercase()
            when (resumeManager.getWatchStatus(entry.url)) {
                WatchStatus.WATCHED -> {
                    holder.tvIcon.text = "✅"
                    holder.tvMeta?.text = ext
                    holder.tvResume?.visibility = View.GONE
                    holder.tvName.alpha = 0.45f
                }
                WatchStatus.PARTIAL -> {
                    holder.tvIcon.text = "🎬"
                    holder.tvMeta?.text = ext
                    val pos = resumeManager.getPosition(entry.url)
                    holder.tvResume?.text = "▶ ${resumeManager.formatPosition(pos)}"
                    holder.tvResume?.visibility = View.VISIBLE
                    holder.tvName.alpha = 1.0f
                }
                WatchStatus.UNWATCHED -> {
                    holder.tvIcon.text = "🎬"
                    holder.tvMeta?.text = ext
                    holder.tvResume?.visibility = View.GONE
                    holder.tvName.alpha = 1.0f
                }
            }
        }

        // Icona e testo cliccabili, badge resume no
        holder.tvIcon.setOnClickListener { onClick(entry) }
        holder.layoutText?.setOnClickListener { onClick(entry) }
        holder.itemView.setOnClickListener(null)
        holder.itemView.isClickable = false
    }

    override fun getItemCount() = entries.size
}
