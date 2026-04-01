
package com.minimalplayer

import java.io.File

class PlayerActivity {

    private var subtitleTracks = listOf<SubtitleTrack>()
    private var localSubtitleFiles = listOf<File?>()
    private var currentSubtitleIndex = -1  // -1 = off

    // Funzione per caricare i sottotitoli dalla stessa cartella del video
    fun loadSubtitlesFromSameDirectory(videoFile: File): File? {
        if (!videoFile.exists()) {
            return null
        }

        val videoDirectory = videoFile.parentFile
        val subtitleFileName = videoFile.nameWithoutExtension + ".srt"
        val subtitleFile = File(videoDirectory, subtitleFileName)

        if (subtitleFile.exists()) {
            return subtitleFile
        }

        return null
    }

    // Funzione per visualizzare i sottotitoli (adatta questa parte per il tuo player)
    fun displaySubtitles(subtitleFile: File?) {
        if (subtitleFile != null) {
            val subtitlesContent = subtitleFile.readText()
            println("Sottotitoli caricati: 
$subtitlesContent")
            // Aggiungi qui la logica per visualizzare i sottotitoli nel tuo player video
        } else {
            println("Nessun sottotitolo trovato per questo video.")
        }
    }

    // Funzione per caricare i sottotitoli durante la riproduzione
    fun loadSubtitlesAndInit(jellyfinItemId: String, jellyfin: JellyfinClient) {
        // Carica i sottotitoli dal server Jellyfin
        val tracks = jellyfin.getSubtitles(jellyfinItemId).getOrDefault(emptyList())
        subtitleTracks = tracks
        localSubtitleFiles = tracks.map { track ->
            // Scarica i sottotitoli in cache
            jellyfin.downloadSubtitle(track, subtitleCacheDir())
        }

        // Se ci sono sottotitoli, inizializza il primo
        if (localSubtitleFiles.isNotEmpty()) {
            currentSubtitleIndex = 0
            displaySubtitles(localSubtitleFiles[currentSubtitleIndex])
        }
    }

    private fun cleanSubtitleCache() {
        localSubtitleFiles.forEach { file ->
            file?.delete()
        }
    }

    // Funzione per gestire la cache
    private fun subtitleCacheDir(): File {
        // Crea una directory per la cache, qui puoi personalizzare la posizione
        val cacheDir = File("/path/to/cache/subtitles")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }
}
