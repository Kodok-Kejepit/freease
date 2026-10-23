package com.freease.music.data.youtube

import android.util.LruCache
import com.freease.music.data.model.ArtistItem
import com.freease.music.data.model.BrowseResult
import com.freease.music.data.model.PlaylistItem
import com.freease.music.data.model.Song
import com.freease.music.data.model.TitleCleaner
import com.freease.music.data.prefs.AudioQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

enum class SearchFilter(val label: String, val filters: List<String>) {
    SONGS("Lagu", listOf("music_songs", "music_videos", "videos")),
    VIDEOS("Video", listOf("videos")),
    ALBUMS("Album", listOf("music_albums", "playlists")),
    PLAYLISTS("Playlist", listOf("music_playlists", "playlists")),
    ARTISTS("Artis", listOf("channels")),
}

data class ResultPage(
    val items: List<BrowseResult>,
    val nextPage: Page?,
    /** Filter NewPipe yang benar-benar dipakai (setelah fallback). */
    val usedFilter: String? = null,
)

data class ArtistSection(val title: String, val playlists: List<PlaylistItem>)

data class ArtistPage(
    val url: String,
    val name: String,
    val avatar: String?,
    val banner: String?,
    val subscribers: Long,
    val description: String?,
    val songs: List<Song>,
    val songsTab: ListLinkHandler?,
    val songsNext: Page?,
    val sections: List<ArtistSection>,
)

data class PlaylistPage(
    val url: String,
    val name: String,
    val uploader: String?,
    val thumbnail: String?,
    val count: Long,
    val songs: List<Song>,
    val nextPage: Page?,
)

data class ResolvedStream(
    val url: String,
    val mimeType: String?,
    val bitrateKbps: Int,
    val label: String,
)

class YouTubeRepository {
    private val yt = ServiceList.YouTube
    private val linkFactory = YoutubeStreamLinkHandlerFactory.getInstance()

    /**
     * Hanya bagian StreamInfo yang dipakai (daftar stream + lagu terkait). Objek StreamInfo
     * utuh jauh lebih besar; menyimpannya puluhan sekaligus membuat memori terus membengkak.
     */
    class StreamData(
        val song: Song,
        val audioStreams: List<AudioStream>,
        val videoOnlyStreams: List<VideoStream>,
        val hlsUrl: String?,
        val related: List<Song>,
        val at: Long = System.currentTimeMillis(),
        val uploaderName: String? = null,
        /** Versi audio resmi buatan YouTube ("Provided to YouTube by …"): videonya hanya gambar sampul. */
        val isAudioTrack: Boolean = false,
    )

    private val infoCache = LruCache<String, StreamData>(24)
    private val relatedCache = LruCache<String, List<Song>>(48)
    private val locks = LruCache<String, Mutex>(32)

    /** Ekstraksi penuh memakai mesin JS (Rhino) yang berat — batasi 2 sekaligus. */
    private val extractionSlots = Semaphore(2)

    // ---------------------------------------------------------------- mapping

    fun videoId(url: String): String? = runCatching { linkFactory.getId(url) }.getOrNull()

    private fun List<Image>.best(): String? =
        maxByOrNull { if (it.height > 0) it.height else it.width }?.url

    fun toSong(item: StreamInfoItem): Song? {
        val id = videoId(item.url) ?: return null
        val (title, artist) = TitleCleaner.split(item.name ?: "", item.uploaderName ?: "")
        val live = item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM
        return Song(
            id = id,
            title = title,
            artist = artist.ifBlank { "YouTube" },
            artistUrl = item.uploaderUrl,
            durationSec = item.duration.coerceAtLeast(0),
            thumbnail = item.thumbnails.best(),
            isLive = live,
        )
    }

    fun toSong(info: StreamInfo): Song {
        val (title, artist) = TitleCleaner.split(info.name ?: "", info.uploaderName ?: "")
        return Song(
            id = info.id,
            title = title,
            artist = artist.ifBlank { "YouTube" },
            artistUrl = info.uploaderUrl,
            durationSec = info.duration.coerceAtLeast(0),
            thumbnail = info.thumbnails.best(),
            isLive = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM,
        )
    }

    private fun toPlaylist(item: PlaylistInfoItem) = PlaylistItem(
        url = item.url,
        title = item.name ?: "",
        uploader = item.uploaderName,
        thumbnail = item.thumbnails.best(),
        count = item.streamCount,
        isAlbum = item.url.contains("OLAK5uy_"),
    )

    private fun toArtist(item: ChannelInfoItem) = ArtistItem(
        url = item.url,
        name = TitleCleaner.cleanArtist(item.name ?: ""),
        thumbnail = item.thumbnails.best(),
        subscribers = item.subscriberCount,
    )

    private fun mapItems(items: List<InfoItem>): List<BrowseResult> = items.mapNotNull { item ->
        when (item) {
            is StreamInfoItem -> if (item.isShortFormContent) null else toSong(item)?.let { BrowseResult.SongResult(it) }
            is PlaylistInfoItem -> BrowseResult.PlaylistResult(toPlaylist(item))
            is ChannelInfoItem -> BrowseResult.ArtistResult(toArtist(item))
            else -> null
        }
    }

    private fun songsOf(items: List<InfoItem>): List<Song> =
        items.filterIsInstance<StreamInfoItem>().filter { !it.isShortFormContent }.mapNotNull(::toSong)

    // ---------------------------------------------------------------- search

    suspend fun search(query: String, filter: SearchFilter): ResultPage = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (f in filter.filters) {
            try {
                val handler = yt.searchQHFactory.fromQuery(query, listOf(f), "")
                val info = SearchInfo.getInfo(yt, handler)
                val items = mapItems(info.relatedItems).filter { matches(it, filter) }
                if (items.isNotEmpty()) return@withContext ResultPage(items, info.nextPage, f)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        lastError?.let { throw it }
        ResultPage(emptyList(), null, null)
    }

    /** Video musik (bukan versi audio) — tab Video di YouTube Music, cadangan pencarian video biasa. */
    suspend fun searchMusicVideos(query: String, limit: Int = 10): List<Song> = withContext(Dispatchers.IO) {
        for (f in listOf("music_videos", "videos")) {
            val items = runCatching {
                SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery(query, listOf(f), "")).relatedItems
            }.getOrNull() ?: continue
            val songs = mapItems(items).filterIsInstance<BrowseResult.SongResult>().map { it.song }.filter { !it.isLive }
            if (songs.isNotEmpty()) return@withContext songs.take(limit)
        }
        emptyList()
    }

    suspend fun searchMore(query: String, usedFilter: String, filter: SearchFilter, page: Page): ResultPage =
        withContext(Dispatchers.IO) {
            val handler = yt.searchQHFactory.fromQuery(query, listOf(usedFilter), "")
            val more = SearchInfo.getMoreItems(yt, handler, page)
            ResultPage(mapItems(more.items).filter { matches(it, filter) }, more.nextPage, usedFilter)
        }

    private fun matches(r: BrowseResult, filter: SearchFilter) = when (filter) {
        SearchFilter.SONGS, SearchFilter.VIDEOS -> r is BrowseResult.SongResult
        SearchFilter.ALBUMS, SearchFilter.PLAYLISTS -> r is BrowseResult.PlaylistResult
        SearchFilter.ARTISTS -> r is BrowseResult.ArtistResult
    }

    suspend fun searchSongs(query: String, limit: Int = 20): List<Song> =
        search(query, SearchFilter.SONGS).items.filterIsInstance<BrowseResult.SongResult>().map { it.song }.take(limit)

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { yt.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- streams

    private fun lockFor(id: String): Mutex = synchronized(locks) {
        locks.get(id) ?: Mutex().also { locks.put(id, it) }
    }

    /** Data stream di-cache ±4 jam (URL googlevideo berlaku ±6 jam). */
    suspend fun streamInfo(id: String): StreamData = withContext(Dispatchers.IO) {
        lockFor(id).withLock {
            val cached = infoCache.get(id)
            if (cached != null && System.currentTimeMillis() - cached.at < 4 * 3600_000L) {
                return@withLock cached
            }
            val info = extractionSlots.withPermit { StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$id") }
            val related = songsOf(info.relatedItems)
            val data = StreamData(
                song = toSong(info),
                audioStreams = info.audioStreams,
                videoOnlyStreams = info.videoOnlyStreams,
                hlsUrl = info.hlsUrl,
                related = related,
                uploaderName = info.uploaderName,
                isAudioTrack = isAutoGenerated(info.description?.content, info.uploaderName),
            )
            infoCache.put(id, data)
            relatedCache.put(id, related)
            data
        }
    }

    fun invalidate(id: String) {
        infoCache.remove(id)
    }

    suspend fun song(id: String): Song = streamInfo(id).song

    fun pickAudio(info: StreamData, quality: AudioQuality): ResolvedStream? {
        val candidates = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP } }
        if (candidates.isEmpty()) return null
        fun kbps(a: AudioStream) = if (a.averageBitrate > 0) a.averageBitrate else a.bitrate / 1000
        // Opus diutamakan saat bitrate setara: kualitas per-bit lebih baik dari AAC.
        fun score(a: AudioStream) = kbps(a) * 10 + if (a.format == MediaFormat.WEBMA_OPUS) 5 else 0
        val chosen = when (quality) {
            AudioQuality.MAX -> candidates.maxByOrNull(::score)
            AudioQuality.NORMAL -> candidates.filter { kbps(it) in 96..160 }.maxByOrNull(::score)
                ?: candidates.minByOrNull { kotlin.math.abs(kbps(it) - 128) }
            AudioQuality.SAVER -> candidates.minByOrNull(::score)
        } ?: return null
        return ResolvedStream(
            url = chosen.content,
            mimeType = chosen.format?.mimeType,
            bitrateKbps = kbps(chosen),
            label = "${chosen.format?.getName() ?: "audio"} · ${kbps(chosen)} kbps",
        )
    }

    /**
     * Audio paling hemat untuk penyelarasan lirik. Opus diutamakan: decoder-nya membuang jeda awal
     * codec dengan tepat, jadi waktunya sama persis dengan yang didengar di player.
     */
    fun pickAlignmentAudio(info: StreamData): ResolvedStream? {
        val pool = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP } }
        val chosen = pool.filter { it.format == MediaFormat.WEBMA_OPUS }.minByOrNull { it.averageBitrate }
            ?: pool.minByOrNull { it.averageBitrate }
            ?: return null
        return ResolvedStream(chosen.content, chosen.format?.mimeType, chosen.averageBitrate, "align")
    }

    fun pickVideo(info: StreamData, maxHeight: Int): ResolvedStream? {
        fun codecRank(v: VideoStream): Int {
            val c = v.codec?.lowercase() ?: ""
            return when {
                c.startsWith("avc1") -> 3
                c.startsWith("vp9") || c.startsWith("vp09") -> 2
                v.format == MediaFormat.MPEG_4 && c.isEmpty() -> 2
                else -> 1
            }
        }
        val pool = info.videoOnlyStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height > 0 }
        val fit = pool.filter { it.height <= maxHeight }.ifEmpty { pool }
        val chosen = fit.sortedWith(
            compareByDescending<VideoStream> { if (it.height <= maxHeight) it.height else -it.height }
                .thenByDescending { codecRank(it) }
                .thenByDescending { it.fps },
        ).firstOrNull() ?: return null
        return ResolvedStream(chosen.content, chosen.format?.mimeType, chosen.bitrate / 1000, "${chosen.height}p")
    }

    // ---------------------------------------------------------------- browse

    /**
     * Lagu terkait. Kalau data stream belum ada di cache, cukup ambil halaman videonya saja
     * tanpa mengurai semua URL stream (jauh lebih ringan untuk CPU & baterai).
     */
    suspend fun related(id: String, limit: Int = 25): List<Song> = withContext(Dispatchers.IO) {
        val all = relatedCache.get(id) ?: infoCache.get(id)?.related ?: run {
            val ex = yt.getStreamExtractor("https://www.youtube.com/watch?v=$id")
            ex.fetchPage()
            songsOf(ex.relatedItems?.items ?: emptyList()).also { relatedCache.put(id, it) }
        }
        all.filter { !it.isLive && it.durationSec in 60..900 }.take(limit)
    }

    suspend fun trendingMusic(): List<Song> = withContext(Dispatchers.IO) {
        val ex = yt.kioskList.getExtractorById("trending_music", null)
        ex.fetchPage()
        songsOf(ex.initialPage.items)
    }

    suspend fun artist(url: String): ArtistPage = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(yt, url)
        val tabs = info.tabs
        val songsTab = tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.TRACKS) }
            ?: tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }
        coroutineScope {
            val songsJob = async {
                songsTab?.let { runCatching { ChannelTabInfo.getInfo(yt, it) }.getOrNull() }
            }
            val sectionJobs = listOf(ChannelTabs.ALBUMS to "Album", ChannelTabs.PLAYLISTS to "Playlist")
                .mapNotNull { (key, label) ->
                    tabs.firstOrNull { it.contentFilters.contains(key) }?.let { tab ->
                        async {
                            runCatching {
                                val t = ChannelTabInfo.getInfo(yt, tab)
                                ArtistSection(label, t.relatedItems.filterIsInstance<PlaylistInfoItem>().map(::toPlaylist))
                            }.getOrNull()
                        }
                    }
                }
            val songsInfo = songsJob.await()
            ArtistPage(
                url = url,
                name = TitleCleaner.cleanArtist(info.name ?: ""),
                avatar = info.avatars.best(),
                banner = info.banners.best(),
                subscribers = info.subscriberCount,
                description = info.description,
                songs = songsInfo?.let { songsOf(it.relatedItems) } ?: emptyList(),
                songsTab = songsTab,
                songsNext = songsInfo?.nextPage,
                sections = sectionJobs.awaitAll().filterNotNull().filter { it.playlists.isNotEmpty() },
            )
        }
    }

    suspend fun artistMore(tab: ListLinkHandler, page: Page): Pair<List<Song>, Page?> = withContext(Dispatchers.IO) {
        val more = ChannelTabInfo.getMoreItems(yt, tab, page)
        songsOf(more.items) to more.nextPage
    }

    suspend fun playlist(url: String): PlaylistPage = withContext(Dispatchers.IO) {
        val info = PlaylistInfo.getInfo(yt, url)
        PlaylistPage(
            url = url,
            name = info.name ?: "Playlist",
            uploader = info.uploaderName,
            thumbnail = info.thumbnails.best(),
            count = info.streamCount,
            songs = songsOf(info.relatedItems),
            nextPage = info.nextPage,
        )
    }

    suspend fun playlistMore(url: String, page: Page): Pair<List<Song>, Page?> = withContext(Dispatchers.IO) {
        val more = PlaylistInfo.getMoreItems(yt, url, page)
        songsOf(more.items) to more.nextPage
    }

    /** Ambil seluruh isi playlist (maks. [max] lagu) — dipakai saat menyimpan ke koleksi. */
    suspend fun playlistAll(url: String, max: Int = 500): PlaylistPage {
        val first = playlist(url)
        val all = first.songs.toMutableList()
        var next = first.nextPage
        while (next != null && all.size < max) {
            val (songs, n) = playlistMore(url, next)
            all += songs
            next = n
        }
        return first.copy(songs = all.distinctBy { it.id }, nextPage = null)
    }

    suspend fun searchPlaylists(query: String): List<PlaylistItem> =
        runCatching {
            search(query, SearchFilter.PLAYLISTS).items.filterIsInstance<BrowseResult.PlaylistResult>().map { it.playlist }
        }.getOrDefault(emptyList())

    fun linkType(url: String): LinkType {
        val u = url.trim()
        return when {
            u.contains("list=") && !u.contains("watch") -> LinkType.PLAYLIST
            u.contains("/channel/") || u.contains("/@") || u.contains("/c/") || u.contains("/user/") -> LinkType.CHANNEL
            videoId(u) != null -> LinkType.VIDEO
            else -> LinkType.UNKNOWN
        }
    }

    enum class LinkType { VIDEO, PLAYLIST, CHANNEL, UNKNOWN }

    companion object {
        /** Versi audio resmi buatan YouTube (deskripsi "Provided to YouTube by …" / kanal "- Topic"). */
        fun isAutoGenerated(description: String?, uploader: String?): Boolean =
            description?.contains("Auto-generated by YouTube", ignoreCase = true) == true ||
                description?.trimStart()?.startsWith("Provided to YouTube by", ignoreCase = true) == true ||
                uploader?.trim()?.endsWith("- Topic") == true

        fun isYouTubeUrl(text: String): Boolean =
            Regex("https?://(www\\.|m\\.|music\\.)?(youtube\\.com|youtu\\.be)/\\S+", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)

        fun extractUrl(text: String): String? =
            Regex("https?://\\S+").find(text)?.value

        fun initNewPipe(http: NewPipeHttp, region: String) {
            NewPipe.init(
                http,
                org.schabi.newpipe.extractor.localization.Localization("id", region),
                org.schabi.newpipe.extractor.localization.ContentCountry(region),
            )
        }
    }
}
