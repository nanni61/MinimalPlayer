package com.minimalplayer

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class FileEntry(
    val name: String,
    val url: String,
    val isDirectory: Boolean,
    val size: Long = -1,
    val jellyfinId: String = "",
    // Stato di visione letto da Jellyfin (UserData) — null = non disponibile
    val jellyfinPlayed: Boolean? = null,
    val jellyfinPositionMs: Long? = null   // posizione in ms, 0 = mai visto
)

data class SubtitleTrack(
    val index: Int,
    val language: String,
    val title: String,
    val url: String
)

class JellyfinClient {

    private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .connectionPool(
        okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS)
    )
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

            val url = "$baseUrl/Users/AuthenticateByName"

            val request = Request.Builder()
                .url(url)
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
            val url = "$baseUrl/Users/$userId/Views"

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
    //
    // "UserData" aggiunto ai Fields: la risposta include per ogni item
    // UserData.Played (bool) e UserData.PlaybackPositionTicks (long).
    // I ticks Jellyfin sono unità da 100ns → dividiamo per 10_000 per avere ms.

    fun getItems(parentId: String): Result<List<FileEntry>> {
        return try {
            val url = "$baseUrl/Users/$userId/Items" +
                "?ParentId=$parentId" +
                "&SortBy=SortName" +
                "&SortOrder=Ascending" +
                "&Fields=MediaSources,Path,UserData" +
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

                // Leggi UserData solo per i video
                val userData = if (isVideo) item.optJSONObject("UserData") else null
                val played = userData?.optBoolean("Played", false)
                val positionTicks = userData?.optLong("PlaybackPositionTicks", 0L) ?: 0L
                val positionMs = positionTicks / 10_000L

                entries.add(FileEntry(
                    name = name,
                    url = streamUrl,
                    isDirectory = isDir,
                    jellyfinId = id,
                    jellyfinPlayed = played,
                    jellyfinPositionMs = if (isVideo) positionMs else null
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

    // ── Sottotitoli: lista tracce ─────────────────────────────────────────────

    fun getSubtitles(itemId: String): Result<List<SubtitleTrack>> {
        return try {
            val url = "$baseUrl/Videos/$itemId/PlaybackInfo?UserId=$userId"

            val request = Request.Builder()
                .url(url)
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

                val subUrl = "$baseUrl/Videos/$itemId/Subtitles/$index/0/Stream.srt?api_key=$accessToken"

                subtitles.add(SubtitleTrack(
                    index = index,
                    language = language,
                    title = title,
                    url = subUrl
                ))
            }

            Result.success(subtitles)
        } catch (e: Exception) {
            Result.success(emptyList())
        }
    }

    // ── Sottotitoli: download su file locale ──────────────────────────────────

    fun downloadSubtitle(track: SubtitleTrack, cacheDir: File): File? {
        return try {

            val request = Request.Builder()
                .url(track.url)
                .header("X-Emby-Authorization", authHeader())
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val bytes = response.body?.bytes() ?: return null
            if (bytes.isEmpty()) return null

            val destFile = File(cacheDir, "sub_${track.index}_${track.language}.srt")
            destFile.writeBytes(bytes)
            destFile
        } catch (e: Exception) {
            null
        }
    }

    // ── Reporting playback a Jellyfin ─────────────────────────────────────────

    fun reportPlaybackStarted(itemId: String, positionMs: Long, playSessionId: String) {
        try {
            val body = JSONObject().apply {
                put("ItemId", itemId)
                put("MediaSourceId", itemId)
                put("PositionTicks", positionMs * 10_000L)
                put("IsPaused", false)
                put("PlayMethod", "DirectPlay")
                put("PlaySessionId", playSessionId)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/Sessions/Playing")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("X-Emby-Authorization", authHeader())
                .build()

            client.newCall(request).execute().close()
        } catch (_: Exception) { }
    }

    fun reportPlaybackProgress(itemId: String, positionMs: Long, isPaused: Boolean, playSessionId: String) {
        try {
            val body = JSONObject().apply {
                put("ItemId", itemId)
                put("MediaSourceId", itemId)
                put("PositionTicks", positionMs * 10_000L)
                put("IsPaused", isPaused)
                put("PlayMethod", "DirectPlay")
                put("PlaySessionId", playSessionId)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/Sessions/Playing/Progress")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("X-Emby-Authorization", authHeader())
                .build()

            client.newCall(request).execute().close()
        } catch (_: Exception) { }
    }

    fun reportPlaybackStopped(itemId: String, positionMs: Long, playSessionId: String) {
        try {
            val body = JSONObject().apply {
                put("ItemId", itemId)
                put("MediaSourceId", itemId)
                put("PositionTicks", positionMs * 10_000L)
                put("PlayMethod", "DirectPlay")
                put("PlaySessionId", playSessionId)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/Sessions/Playing/Stopped")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("X-Emby-Authorization", authHeader())
                .build()

            client.newCall(request).execute().close()
        } catch (_: Exception) { }
    }
}
