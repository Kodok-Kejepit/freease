package com.freease.music

import com.freease.music.data.lyrics.LrcLib
import com.freease.music.data.lyrics.LyricsFinder
import com.freease.music.data.lyrics.YouTubeMusicLyrics
import com.freease.music.data.youtube.NewPipeHttp
import com.freease.music.data.youtube.YouTubeRepository
import okhttp3.OkHttpClient
import org.junit.Assume
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File

/**
 * Uji ujung-ke-ujung ke internet sungguhan: cari lagu di YouTube persis seperti aplikasi, lalu jalankan
 * pencari lirik asli. Hanya jalan kalau ada file `probe.on` (isi: satu kueri per baris) di folder modul app.
 */
class LyricsProbe {
    @Test
    fun probeLyrics() {
        val list = File("probe.on")
        Assume.assumeTrue(list.exists())
        val client = OkHttpClient()
        YouTubeRepository.initNewPipe(NewPipeHttp(client), "ID")
        val repo = YouTubeRepository()
        val finder = LyricsFinder(YouTubeMusicLyrics(client), LrcLib(client))
        val yt = ServiceList.YouTube
        val out = StringBuilder()
        for (q in list.readLines().map { it.trim() }.filter { it.isNotEmpty() }) {
            out.appendLine("##### $q")
            for (f in listOf("music_songs", "music_videos", "videos")) {
                val item = runCatching {
                    SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery(q, listOf(f), "")).relatedItems
                        .filterIsInstance<StreamInfoItem>().firstOrNull()
                }.getOrNull() ?: continue
                val song = repo.toSong(item) ?: continue
                val t0 = System.currentTimeMillis()
                val res = runCatching { finder.find(song) }
                val ms = System.currentTimeMillis() - t0
                val l = res.getOrNull()
                val desc = when {
                    l == null -> "GAGAL ${res.exceptionOrNull()}"
                    l.isEmpty -> "tidak ada lirik"
                    l.synced != null -> {
                        val first = l.synced!!.firstOrNull { it.text.isNotBlank() && it.text != "♪" }
                        val mode = when {
                            l.exactTiming -> "PAS"
                            l.canFollow && l.canAutoSync -> "JALAN(perkiraan)+SINKRON-SUARA"
                            l.canFollow -> "JALAN(perkiraan)"
                            l.canAutoSync -> "SINKRON-SUARA(lalu jalan)"
                            else -> "PERLU-KETUK"
                        }
                        "sinkron=$mode baris=${l.synced!!.size} " +
                            "pertama=${first?.timeMs}ms '${first?.text}' | ${l.source}"
                    }
                    else -> "teks saja (${l.text.lines().size} baris) | ${l.source}"
                }
                out.appendLine("  [%-12s] %-48s %4ds %5dms -> %s".format(f, "'${song.title}' / '${song.artist}'".take(48), song.durationSec, ms, desc))
            }
        }
        File("build/probe.txt").writeText(out.toString())
    }
}
