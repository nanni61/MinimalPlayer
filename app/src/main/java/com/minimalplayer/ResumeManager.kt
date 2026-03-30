package com.minimalplayer

import android.content.Context

enum class WatchStatus { UNWATCHED, PARTIAL, WATCHED }

class ResumeManager(context: Context) {

    private val prefs = context.getSharedPreferences("resume_positions", Context.MODE_PRIVATE)

    private val minSaveThresholdMs = 10_000L       // 10 secondi
    private val completedThresholdPercent = 0.90f  // 90% = visto

    fun savePosition(url: String, positionMs: Long, durationMs: Long) {
        if (positionMs < minSaveThresholdMs) return
        if (durationMs > 0 && positionMs.toFloat() / durationMs >= completedThresholdPercent) {
            // Segnala come visto completamente
            prefs.edit().putLong(key(url), -1L).apply()
            return
        }
        prefs.edit().putLong(key(url), positionMs).apply()
    }

    fun getPosition(url: String): Long {
        val pos = prefs.getLong(key(url), 0L)
        return if (pos == -1L) 0L else pos // -1 = visto, restituisce 0
    }

    fun getWatchStatus(url: String): WatchStatus {
        return when (prefs.getLong(key(url), 0L)) {
            0L -> WatchStatus.UNWATCHED
            -1L -> WatchStatus.WATCHED
            else -> WatchStatus.PARTIAL
        }
    }

    fun hasPosition(url: String): Boolean {
        val pos = prefs.getLong(key(url), 0L)
        return pos > minSaveThresholdMs
    }

    fun remove(url: String) {
        prefs.edit().remove(key(url)).apply()
    }

    fun markWatched(url: String) {
        prefs.edit().putLong(key(url), -1L).apply()
    }

    fun formatPosition(positionMs: Long): String {
        val totalSec = positionMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%d:%02d".format(m, s)
    }

    private fun key(url: String) = url.hashCode().toString()
}
