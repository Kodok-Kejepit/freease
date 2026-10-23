package com.freease.music

import com.freease.music.data.youtube.NewPipeHttp
import com.freease.music.data.youtube.YouTubeRepository
import com.freease.music.playback.CanvasResolver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume
import org.junit.Test
import java.io.File

/** Pencarian video Canvas dengan jaringan sungguhan. Hanya jalan kalau ada berkas `canvas.on`. */
class CanvasProbe {
    @Test
    fun probe() = runBlocking {
        val f = File("canvas.on")
        Assume.assumeTrue(f.exists())
        YouTubeRepository.initNewPipe(NewPipeHttp(OkHttpClient()), "ID")
        val yt = YouTubeRepository()
        f.readLines().filter { it.isNotBlank() }.forEach { q ->
            val song = yt.searchSongs(q, 3).firstOrNull() ?: return@forEach println("[$q] tidak ada hasil")
            val info = runCatching { yt.streamInfo(song.id) }.getOrNull()
            val vids = runCatching { yt.searchMusicVideos("${song.artist} ${song.title}", 10) }.getOrDefault(emptyList())
            val pick = CanvasResolver.pickVideo(song, vids)
            println("[$q] song=${song.id} '${song.title}' / ${song.artist} ${song.durationSec}s audioTrack=${info?.isAudioTrack} uploader=${info?.uploaderName} videoOnly=${info?.videoOnlyStreams?.size}")
            info?.videoOnlyStreams?.filter { it.height in 300..400 }?.forEach { println("     song-v ${it.height}p ${it.codec} br=${it.bitrate} fps=${it.fps}") }
            CanvasResolver.pickVideo(song, vids)?.let { p -> runCatching { yt.streamInfo(p.id) }.getOrNull()?.videoOnlyStreams?.filter { it.height in 300..400 }?.forEach { println("     pick-v ${it.height}p ${it.codec} br=${it.bitrate} fps=${it.fps}") } }
            vids.take(4).forEach { println("     cand ${it.id} '${it.title}' / ${it.artist} ${it.durationSec}s") }
            println("     PICK -> ${pick?.id} '${pick?.title}' start=${pick?.let { CanvasResolver.startFor(it.durationSec) }}")
        }
    }
}
