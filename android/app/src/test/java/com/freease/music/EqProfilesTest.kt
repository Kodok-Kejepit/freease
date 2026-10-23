package com.freease.music

import android.media.AudioDeviceInfo
import com.freease.music.playback.EqProfile
import com.freease.music.playback.OutputDevice
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EqProfilesTest {
    private val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to ""

    @Test
    fun picksBluetoothOverWired() {
        val d = EqProfile.pick(listOf(speaker, AudioDeviceInfo.TYPE_WIRED_HEADPHONES to "", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "Galaxy Buds2 "))
        assertEquals(OutputDevice("bt:Galaxy Buds2", "Galaxy Buds2", OutputDevice.Kind.BLUETOOTH), d)
    }

    @Test
    fun wiredAndSpeaker() {
        assertEquals("wired", EqProfile.pick(listOf(speaker, AudioDeviceInfo.TYPE_WIRED_HEADSET to "")).key)
        assertEquals("speaker", EqProfile.pick(listOf(speaker)).key)
        assertEquals(OutputDevice.Kind.USB, EqProfile.pick(listOf(speaker, AudioDeviceInfo.TYPE_USB_HEADSET to "DAC")).kind)
    }

    @Test
    fun profileJsonRoundTrip() {
        val p = EqProfile(true, -1, listOf(300, -150, 0, 200, 450), 500, 0, 800)
        assertEquals(p, EqProfile.fromJson(p.toJson()))
    }
}
