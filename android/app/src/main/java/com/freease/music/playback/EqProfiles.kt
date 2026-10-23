package com.freease.music.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.freease.music.data.prefs.AppSettings
import com.freease.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Perangkat keluaran audio yang sedang aktif. [key] stabil per perangkat. */
data class OutputDevice(val key: String, val label: String, val kind: Kind) {
    enum class Kind { SPEAKER, WIRED, USB, BLUETOOTH }
}

/** Setelan efek suara yang disimpan per perangkat. */
data class EqProfile(
    val enabled: Boolean,
    val preset: Int,
    val bands: List<Int>,
    val bass: Int,
    val virtualizer: Int,
    val loudness: Int,
) {
    fun toJson(): JSONObject = JSONObject().put("on", enabled).put("preset", preset).put("bands", JSONArray(bands))
        .put("bass", bass).put("virt", virtualizer).put("loud", loudness)

    companion object {
        fun of(s: AppSettings) = EqProfile(s.eqEnabled, s.eqPreset, s.eqBands, s.bassBoost, s.virtualizer, s.loudnessGain)

        fun fromJson(o: JSONObject) = EqProfile(
            o.optBoolean("on"), o.optInt("preset", -1),
            o.optJSONArray("bands")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty(),
            o.optInt("bass"), o.optInt("virt"), o.optInt("loud"),
        )

        /**
         * Perangkat yang benar-benar dipakai Android untuk musik: Bluetooth > USB > kabel > speaker
         * (urutan yang sama dengan routing bawaan saat beberapa terhubung sekaligus).
         */
        fun pick(devices: List<Pair<Int, String>>): OutputDevice {
            fun name(n: String, fallback: String) = n.trim().takeIf { it.isNotEmpty() && !it.equals(android.os.Build.MODEL, true) } ?: fallback
            val bt = devices.firstOrNull { it.first in BT_TYPES }
            if (bt != null) return OutputDevice("bt:${bt.second.trim()}", name(bt.second, "Bluetooth"), OutputDevice.Kind.BLUETOOTH)
            val usb = devices.firstOrNull { it.first in USB_TYPES }
            if (usb != null) return OutputDevice("usb:${usb.second.trim()}", name(usb.second, "Headset USB"), OutputDevice.Kind.USB)
            if (devices.any { it.first in WIRED_TYPES }) return OutputDevice("wired", "Headset kabel", OutputDevice.Kind.WIRED)
            return OutputDevice("speaker", "Speaker HP", OutputDevice.Kind.SPEAKER)
        }

        private val BT_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* BLE_HEADSET */, 27 /* BLE_SPEAKER */, 30 /* BLE_BROADCAST */)
        private val USB_TYPES = setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        private val WIRED_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG)
    }
}

/**
 * Menyimpan & memulihkan setelan equalizer per perangkat keluaran: ganti dari speaker ke
 * earbud Bluetooth → profil earbud langsung dipakai, cabut lagi → kembali ke profil speaker.
 * Perangkat yang baru pertama kali tersambung mewarisi setelan yang sedang dipakai.
 */
class EqProfiles(
    context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val onSwitched: (OutputDevice) -> Unit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val prefs = context.getSharedPreferences("eq_profiles", Context.MODE_PRIVATE)
    private val profiles = HashMap<String, EqProfile>()
    private val _device = MutableStateFlow(OutputDevice("speaker", "Speaker HP", OutputDevice.Kind.SPEAKER))
    val device: StateFlow<OutputDevice> = _device.asStateFlow()

    /** Profil yang sedang ditulis ke Settings — emisi lama diabaikan sampai profil ini terlihat. */
    private var applying: EqProfile? = null

    fun start() {
        runCatching {
            val o = JSONObject(prefs.getString("all", "{}") ?: "{}")
            o.keys().forEach { k -> profiles[k] = EqProfile.fromJson(o.getJSONObject(k)) }
        }
        _device.value = current()
        audio?.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onDevicesChanged()
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onDevicesChanged()
            },
            Handler(Looper.getMainLooper()),
        )
        scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            settings.flow.collect { s ->
                val p = EqProfile.of(s)
                val pending = applying
                if (pending != null) {
                    if (p == pending) applying = null
                    return@collect
                }
                if (s.eqPerDevice && profiles[_device.value.key] != p) {
                    profiles[_device.value.key] = p
                    save()
                }
            }
        }
    }

    private fun current(): OutputDevice {
        val list = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            .map { it.type to (it.productName?.toString() ?: "") }
        return EqProfile.pick(list)
    }

    private fun onDevicesChanged() {
        val now = current()
        val prev = _device.value
        if (now.key == prev.key) return
        _device.value = now
        val s = settings.current
        if (!s.eqPerDevice) return
        val active = EqProfile.of(s)
        // Pastikan profil perangkat lama tersimpan dengan nilai terakhirnya.
        if (applying == null) profiles[prev.key] = active
        val target = profiles[now.key]
        if (target == null) {
            profiles[now.key] = active
            save()
            return
        }
        save()
        if (target != active) {
            applying = target
            settings.setEqProfile(target.enabled, target.preset, target.bands, target.bass, target.virtualizer, target.loudness)
            onSwitched(now)
        }
    }

    private fun save() {
        val o = JSONObject()
        profiles.forEach { (k, p) -> o.put(k, p.toJson()) }
        prefs.edit().putString("all", o.toString()).apply()
    }
}
