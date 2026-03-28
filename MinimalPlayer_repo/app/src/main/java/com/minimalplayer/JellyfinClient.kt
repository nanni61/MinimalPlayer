package com.minimalplayer

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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

class JellyfinClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    var baseUrl = ""
    var accessToken = ""
    var userId = ""

    private val deviceId = "MinimalPlayer-Android-001"

    private fun authHeader(): String {
        return if (accessToken.isEmpty()) {
            """MediaBrowser Client="MinimalPlayer", Device="Android", DeviceId="$deviceId", Version="1.0""""
        } else {
            """MediaBrowser Client="MinimalPlayer", Device="Android", DeviceId="$deviceId", Version="1.0", Token="$accessToken""""
        }
    }

    // ── Autenticazione ─────────────────────────────────────────────────────────

    fun authenticate(username: String, password: String): Result<Unit> {
        return try {
            val body = JSONObject().apply {
                put("Username", username)
                put("Pw", password)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/Users/AuthenticateByName")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("X-Emby-Authorization", authHeader())
                .header("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result.failure(Exception("Autenticazione fallita: HTTP ${response.code}"))
            }

            val json = JSONObject(response.body?.string() ?: "{}")
            accessToken = json.getString("AccessToken")
            userId = json.getJSONObject("User").getString("Id")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Librerie root ──────────────────────────────────────────────────────────

    fun getViews(): Result<List<FileEntry>> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/Users/$userId/Views")
                .header("X-Emby-Authorization", authHeader())
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return Result.failure(Exception("HTTP ${response.code}"))

            val json = JSONObject(response.body?.string() ?: "{}")
            val items = json.getJSONArray("Items")
            val entries = mutableListOf<FileEntry>()

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                entries.add(FileEntry(
                    name = item.getString("Name"),
                    url = "",
                    isDirectory = true,
                    jellyfinId = item.getString("Id")
                ))
            }
            Result.success(entries)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Contenuto cartella ────────────────────────────────────────────────────

    fun getItems(parentId: String): Result<List<FileEntry>> {
        return try {
            val url = "$baseUrl/Users/$userId/Items" +
                "?ParentId=$parentId" +
                "&SortBy=SortName" +
                "&SortOrder=Ascending" +
                "&Fields=MediaSources,Path" +
                "&Recursive=false"

            val request = Request.Builder()
                .url(url)
                .header("X-Emby-Authorization", authHeader())
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return Result.failure(Exception("HTTP ${response.code}"))

            val json = JSONObject(response.body?.string() ?: "{}")
            val items = json.getJSONArray("Items")
            val entries = mutableListOf<FileEntry>()

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val id = item.getString("Id")
                val name = item.getString("Name")
                val type = item.optString("Type", "")

                val isDir = type in listOf("Folder", "CollectionFolder", "Series", "Season", "BoxSet")
                val isVideo = !isDir && type.isNotEmpty()

                if (!isDir && !isVideo) continue

                val streamUrl = if (isVideo) getStreamUrl(id) else ""

                entries.add(FileEntry(
                    name = name,
                    url = streamUrl,
                    isDirectory = isDir,
                    jellyfinId = id
                ))
            }

            Result.success(entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── URL stream diretto ────────────────────────────────────────────────────

    fun getStreamUrl(itemId: String): String {
        return "$baseUrl/Videos/$itemId/stream?static=true&api_key=$accessToken"
    }

    // ── Sottotitoli ───────────────────────────────────────────────────────────

    fun getSubtitles(itemId: String): Result<List<SubtitleTrack>> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/Videos/$itemId/PlaybackInfo?UserId=$userId")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .header("X-Emby-Authorization", authHeader())
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return Result.success(emptyList())

            val json = JSONObject(response.body?.string() ?: "{}")
            val mediaSources = json.optJSONArray("MediaSources") ?: return Result.success(emptyList())
            if (mediaSources.length() == 0) return Result.success(emptyList())

            val mediaSource = mediaSources.getJSONObject(0)
            val mediaStreams = mediaSource.optJSONArray("MediaStreams") ?: return Result.success(emptyList())

            val subtitles = mutableListOf<SubtitleTrack>()

            for (i in 0 until mediaStreams.length()) {
                val stream = mediaStreams.getJSONObject(i)
                if (stream.optString("Type") != "Subtitle") continue

                val index = stream.optInt("Index", -1)
                if (index < 0) continue

                val language = stream.optString("Language", "und")
                val title = stream.optString("DisplayTitle",
                    stream.optString("Title", language))
                val isExternal = stream.optBoolean("IsExternal", false)

                // URL sottotitolo SRT via API Jellyfin
                val subUrl = if (isExternal) {
                    // File esterno (.srt) — scaricabile direttamente
                    "$baseUrl/Videos/$itemId/$itemId/Subtitles/$index/0/Stream.srt?api_key=$accessToken"
                } else {
                    // Traccia embedded — Jellyfin la converte in SRT al volo
                    "$baseUrl/Videos/$itemId/$itemId/Subtitles/$index/0/Stream.srt?api_key=$accessToken"
                }

                subtitles.add(SubtitleTrack(
                    index = index,
                    language = language,
                    title = title,
                    url = subUrl
                ))
            }

            Result.success(subtitles)
        } catch (e: Exception) {
            Result.success(emptyList()) // Se fallisce, niente sottotitoli — non blocca il video
        }
    }
}
