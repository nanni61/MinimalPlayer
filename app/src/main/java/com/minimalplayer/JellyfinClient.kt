
package com.minimalplayer

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class FileEntry(
    val name: String,
    val url: String,
    val isDirectory: Boolean,
    val size: Long = -1,
    val jellyfinId: String = ""
)

data class SubtitleTrack(
    val index: Int,
    val language: String,
    val title: String,
    val url: String
)

class JellyfinClient(private val baseUrl: String, private val accessToken: String) {

    private val client = OkHttpClient()

    fun getSubtitles(itemId: String): List<SubtitleTrack> {
        val url = "$baseUrl/Videos/$itemId/Subtitles?api_key=$accessToken"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (response.isSuccessful) {
            // Process and return subtitle tracks (example)
            val jsonResponse = JSONObject(response.body?.string() ?: "{}")
            val tracks = jsonResponse.getJSONArray("subtitles")
            val subtitleTracks = mutableListOf<SubtitleTrack>()

            for (i in 0 until tracks.length()) {
                val track = tracks.getJSONObject(i)
                subtitleTracks.add(SubtitleTrack(
                    index = i,
                    language = track.getString("language"),
                    title = track.getString("title"),
                    url = track.getString("url")
                ))
            }
            return subtitleTracks
        }
        return emptyList()
    }

    fun downloadSubtitle(track: SubtitleTrack, cacheDir: File): File? {
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(track.url).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val subtitleFile = File(cacheDir, "${track.index}_${track.language}.srt")
                subtitleFile.writeText(response.body?.string() ?: "")
                return subtitleFile
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
