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

fun getSubtitles(itemId: String, baseUrl: String, accessToken: String): Result<List<SubtitleTrack>> {
    val subtitles = mutableListOf<SubtitleTrack>()
    try {
        val url = "$baseUrl/Videos/$itemId/Subtitles?api_key=$accessToken"
        val request = Request.Builder().url(url).build()
        val client = OkHttpClient()
        val response = client.newCall(request).execute()

        if (response.isSuccessful) {
            val jsonResponse = JSONObject(response.body?.string() ?: "{}")
            val tracks = jsonResponse.getJSONArray("subtitles")
            for (i in 0 until tracks.length()) {
                val track = tracks.getJSONObject(i)
                val subUrl = "$baseUrl/Videos/$itemId/${track.getString("id")}/Stream.srt?api_key=$accessToken"
                subtitles.add(SubtitleTrack(
                    index = i,
                    language = track.getString("language"),
                    title = track.getString("title"),
                    url = subUrl
                ))
            }
            return Result.success(subtitles)
        } else {
            return Result.failure(Exception("Failed to fetch subtitles"))
        }
    } catch (e: Exception) {
        return Result.failure(e)
    }
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