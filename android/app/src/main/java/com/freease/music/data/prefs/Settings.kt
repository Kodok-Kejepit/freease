package com.freease.music.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

enum class AudioQuality(val label: String, val desc: String) {
    MAX("Tertinggi", "Opus/AAC bitrate tertinggi (≈160–256 kbps)"),
    NORMAL("Normal", "≈128 kbps, seimbang"),
    SAVER("Hemat data", "≈50–70 kbps"),
}

data class AppSettings(
    val audioQuality: AudioQuality = AudioQuality.MAX,
    val saverOnCellular: Boolean = false,
    val videoQuality: Int = 720,
    val crossfadeSec: Int = 0,
    val fadePlayPause: Boolean = true,
    val skipSilence: Boolean = false,
    val autoplay: Boolean = true,
    val cacheSizeMb: Int = 1024,
    val accent: Int = 0,
    val dynamicColor: Boolean = true,
    val amoled: Boolean = false,
    val eqEnabled: Boolean = false,
    val eqPreset: Int = -1,
    val eqBands: List<Int> = emptyList(),
    val bassBoost: Int = 0,
    val virtualizer: Int = 0,
    val loudnessGain: Int = 0,
    val region: String = "ID",
    val downloadWifiOnly: Boolean = false,
    val showLyricsCard: Boolean = true,
    val resumeOnStart: Boolean = true,
    val smartMode: Boolean = true,
    /** Suasana Freease Pintar: "" (biasa), "focus", "chill", "workout", "sleep". */
    val smartMood: String = "",
    /** Kualitas yang dipakai saat di jaringan seluler (kalau [saverOnCellular] aktif). */
    val cellularQuality: AudioQuality = AudioQuality.SAVER,
    val autoDownload: Boolean = false,
    /** Timer tidur otomatis kalau tidak ada interaksi selama sekian menit (0 = mati). */
    val idleSleepMin: Int = 0,
)

private val Context.dataStore by preferencesDataStore("freease_settings")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val audioQuality = stringPreferencesKey("audio_quality")
        val saverOnCellular = booleanPreferencesKey("saver_cellular")
        val videoQuality = intPreferencesKey("video_quality")
        val crossfade = intPreferencesKey("crossfade")
        val fade = booleanPreferencesKey("fade_play_pause")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val autoplay = booleanPreferencesKey("autoplay")
        val cacheSize = intPreferencesKey("cache_size")
        val accent = intPreferencesKey("accent")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val amoled = booleanPreferencesKey("amoled")
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqPreset = intPreferencesKey("eq_preset")
        val eqBands = stringPreferencesKey("eq_bands")
        val bass = intPreferencesKey("bass_boost")
        val virt = intPreferencesKey("virtualizer")
        val loud = intPreferencesKey("loudness")
        val region = stringPreferencesKey("region")
        val wifiOnly = booleanPreferencesKey("dl_wifi_only")
        val lyricsCard = booleanPreferencesKey("lyrics_card")
        val resume = booleanPreferencesKey("resume_on_start")
        val smart = booleanPreferencesKey("smart_mode")
        val mood = stringPreferencesKey("smart_mood")
        val cellularQuality = stringPreferencesKey("cellular_quality")
        val autoDl = booleanPreferencesKey("auto_download")
        val idleSleep = intPreferencesKey("idle_sleep_min")
    }

    private fun read(p: Preferences) = AppSettings(
        audioQuality = p[K.audioQuality]?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() } ?: AudioQuality.MAX,
        saverOnCellular = p[K.saverOnCellular] ?: false,
        videoQuality = p[K.videoQuality] ?: 720,
        crossfadeSec = p[K.crossfade] ?: 0,
        fadePlayPause = p[K.fade] ?: true,
        skipSilence = p[K.skipSilence] ?: false,
        autoplay = p[K.autoplay] ?: true,
        cacheSizeMb = p[K.cacheSize] ?: 1024,
        accent = p[K.accent] ?: 0,
        dynamicColor = p[K.dynamicColor] ?: true,
        amoled = p[K.amoled] ?: false,
        eqEnabled = p[K.eqEnabled] ?: false,
        eqPreset = p[K.eqPreset] ?: -1,
        eqBands = p[K.eqBands]?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList(),
        bassBoost = p[K.bass] ?: 0,
        virtualizer = p[K.virt] ?: 0,
        loudnessGain = p[K.loud] ?: 0,
        region = p[K.region] ?: "ID",
        downloadWifiOnly = p[K.wifiOnly] ?: false,
        showLyricsCard = p[K.lyricsCard] ?: true,
        resumeOnStart = p[K.resume] ?: true,
        smartMode = p[K.smart] ?: true,
        smartMood = p[K.mood] ?: "",
        cellularQuality = p[K.cellularQuality]?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() } ?: AudioQuality.SAVER,
        autoDownload = p[K.autoDl] ?: false,
        idleSleepMin = p[K.idleSleep] ?: 0,
    )

    /** Dibaca sekali secara sinkron saat start supaya service langsung punya nilai yang benar. */
    private val initial: AppSettings = runBlocking { read(context.dataStore.data.first()) }

    val flow: StateFlow<AppSettings> =
        context.dataStore.data.map(::read).stateIn(scope, SharingStarted.Eagerly, initial)

    val current: AppSettings get() = flow.value

    private val writeScope = scope

    private fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        writeScope.launch { context.dataStore.edit { block(it) } }
    }

    fun setAudioQuality(v: AudioQuality) = edit { it[K.audioQuality] = v.name }
    fun setSaverOnCellular(v: Boolean) = edit { it[K.saverOnCellular] = v }
    fun setVideoQuality(v: Int) = edit { it[K.videoQuality] = v }
    fun setCrossfade(v: Int) = edit { it[K.crossfade] = v }
    fun setFadePlayPause(v: Boolean) = edit { it[K.fade] = v }
    fun setSkipSilence(v: Boolean) = edit { it[K.skipSilence] = v }
    fun setAutoplay(v: Boolean) = edit { it[K.autoplay] = v }
    fun setCacheSize(v: Int) = edit { it[K.cacheSize] = v }
    fun setAccent(v: Int) = edit { it[K.accent] = v }
    fun setDynamicColor(v: Boolean) = edit { it[K.dynamicColor] = v }
    fun setAmoled(v: Boolean) = edit { it[K.amoled] = v }
    fun setEqEnabled(v: Boolean) = edit { it[K.eqEnabled] = v }
    fun setEq(preset: Int, bands: List<Int>) = edit {
        it[K.eqPreset] = preset
        it[K.eqBands] = bands.joinToString(",")
    }
    fun setBassBoost(v: Int) = edit { it[K.bass] = v }
    fun setVirtualizer(v: Int) = edit { it[K.virt] = v }
    fun setLoudness(v: Int) = edit { it[K.loud] = v }
    fun setRegion(v: String) = edit { it[K.region] = v }
    fun setDownloadWifiOnly(v: Boolean) = edit { it[K.wifiOnly] = v }
    fun setShowLyricsCard(v: Boolean) = edit { it[K.lyricsCard] = v }
    fun setResumeOnStart(v: Boolean) = edit { it[K.resume] = v }
    fun setSmartMode(v: Boolean) = edit { it[K.smart] = v }
    fun setSmartMood(v: String) = edit { it[K.mood] = v }
    fun setCellularQuality(v: AudioQuality) = edit { it[K.cellularQuality] = v.name }
    fun setAutoDownload(v: Boolean) = edit { it[K.autoDl] = v }
    fun setIdleSleepMin(v: Int) = edit { it[K.idleSleep] = v }
}
