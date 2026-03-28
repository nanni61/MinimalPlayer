package com.minimalplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FileAdapter(
    private val entries: List<FileEntry>,
    private val resumeManager: ResumeManager,
    private val onClick: (FileEntry) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon: TextView = view.findViewById(R.id.tvIcon)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvMeta: TextView = view.findViewById(R.id.tvMeta)
        val tvResume: TextView = view.findViewById(R.id.tvResume)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        holder.tvName.text = entry.name

        if (entry.isDirectory) {
            holder.tvIcon.text = "📁"
            holder.tvMeta.text = "Cartella"
            holder.tvResume.visibility = View.GONE
            holder.tvName.alpha = 1.0f
        } else {
            val ext = entry.name.substringAfterLast('.', "").uppercase()
            when (resumeManager.getWatchStatus(entry.url)) {
                WatchStatus.WATCHED -> {
                    // Visto: icona spuntata, nome sbiadito
                    holder.tvIcon.text = "✅"
                    holder.tvMeta.text = ext
                    holder.tvResume.visibility = View.GONE
                    holder.tvName.alpha = 0.45f
                }
                WatchStatus.PARTIAL -> {
                    // Parziale: icona con orologio, badge con posizione
                    holder.tvIcon.text = "🎬"
                    holder.tvMeta.text = ext
                    val pos = resumeManager.getPosition(entry.url)
                    holder.tvResume.text = "▶ ${resumeManager.formatPosition(pos)}"
                    holder.tvResume.visibility = View.VISIBLE
                    holder.tvName.alpha = 1.0f
                }
                WatchStatus.UNWATCHED -> {
                    holder.tvIcon.text = "🎬"
                    holder.tvMeta.text = ext
                    holder.tvResume.visibility = View.GONE
                    holder.tvName.alpha = 1.0f
                }
            }
        }

        holder.itemView.setOnClickListener { onClick(entry) }
    }

    override fun getItemCount() = entries.size
}
