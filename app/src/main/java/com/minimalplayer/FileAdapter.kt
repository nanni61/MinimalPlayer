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

            // Preferisci i dati Jellyfin (UserData), fallback a ResumeManager locale
            val watchStatus: WatchStatus
            val resumePositionMs: Long

            if (entry.jellyfinPlayed != null) {
                // Dati dal server Jellyfin — fonte di verità
                watchStatus = when {
                    entry.jellyfinPlayed -> WatchStatus.WATCHED
                    (entry.jellyfinPositionMs ?: 0L) > 10_000L -> WatchStatus.PARTIAL
                    else -> WatchStatus.UNWATCHED
                }
                resumePositionMs = entry.jellyfinPositionMs ?: 0L
            } else {
                // Fallback locale (file non Jellyfin o UserData assente)
                watchStatus = resumeManager.getWatchStatus(entry.url)
                resumePositionMs = resumeManager.getPosition(entry.url)
            }

            when (watchStatus) {
                WatchStatus.WATCHED -> {
                    holder.tvIcon.text = "✅"
                    holder.tvMeta?.text = ext
                    holder.tvResume?.visibility = View.GONE
                    holder.tvName.alpha = 0.45f
                }
                WatchStatus.PARTIAL -> {
                    holder.tvIcon.text = "🎬"
                    holder.tvMeta?.text = ext
                    holder.tvResume?.text = "▶ ${resumeManager.formatPosition(resumePositionMs)}"
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

        if (entry.isDirectory) {
            holder.tvIcon.setOnClickListener { onClick(entry) }
            holder.tvName.setOnClickListener { onClick(entry) }
            holder.tvMeta?.setOnClickListener { onClick(entry) }
            holder.tvIcon.setOnLongClickListener(null)
            holder.tvName.setOnLongClickListener(null)
            holder.tvMeta?.setOnLongClickListener(null)
        } else {
            holder.tvIcon.setOnClickListener(null)
            holder.tvName.setOnClickListener(null)
            holder.tvMeta?.setOnClickListener(null)
            holder.tvIcon.setOnLongClickListener { onClick(entry); true }
            holder.tvName.setOnLongClickListener { onClick(entry); true }
            holder.tvMeta?.setOnLongClickListener { onClick(entry); true }
        }

        holder.itemView.setOnClickListener(null)
        holder.itemView.isClickable = false
    }

    override fun getItemCount() = entries.size
}
