package com.freease.music

import com.freease.music.data.importer.SpotifyReader
import com.freease.music.data.importer.TrackMatcher
import com.freease.music.data.model.BrowseResult
import com.freease.music.data.youtube.NewPipeHttp
import com.freease.music.data.youtube.SearchFilter
import com.freease.music.data.youtube.YouTubeRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume
import org.junit.Test
import java.io.File

/** Impor ujung-ke-ujung dengan jaringan sungguhan. Hanya jalan kalau ada `spotify.on` berisi link. */
class SpotifyProbe {
    @Test
    fun probe() {
        val f = File("spotify.on")
        Assume.assumeTrue(f.exists())
        val client = OkHttpClient()
        YouTubeRepository.initNewPipe(NewPipeHttp(client), "ID")
        val yt = YouTubeRepository()
        val out = StringBuilder()
        for (link in f.readLines().filter { it.isNotBlank() }) {
            val ref = SpotifyReader.parseLink(link)!!
            val list = SpotifyReader(client).read(ref)
            out.appendLine("##### ${list.name} (${list.tracks.size} lagu, terpotong=${list.truncated})")
            var found = 0
            for (t in list.tracks.take(15)) {
                val q = "${t.artists.split(",").first().trim()} ${t.title}"
                val songs = runBlocking { yt.search(q, SearchFilter.SONGS) }.items.filterIsInstance<BrowseResult.SongResult>().map { it.song }
                var s = TrackMatcher.best(t, songs)
                if (s == null) {
                    val v = runBlocking { yt.search(q, SearchFilter.VIDEOS) }.items.filterIsInstance<BrowseResult.SongResult>().map { it.song }
                    s = TrackMatcher.best(t, v)
                }
                if (s != null) found++
                out.appendLine("  %-40s %4ds -> %s".format("${t.title} / ${t.artists}".take(40), t.durationMs / 1000,
                    s?.let { "${it.title} / ${it.artist} (${it.durationSec}s) ${it.id}" } ?: "TIDAK DITEMUKAN"))
            }
            out.appendLine("  ditemukan $found/15")
        }
        File("build/spotify.txt").writeText(out.toString())
    }
}
