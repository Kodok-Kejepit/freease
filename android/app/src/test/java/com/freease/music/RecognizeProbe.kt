package com.freease.music

import com.freease.music.data.recognize.SignatureGenerator
import com.freease.music.data.recognize.SongRecognizer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Uji nyata ke server (butuh internet): -Precognize.pcm=/path/16k-mono.pcm */
class RecognizeProbe {
    @Test
    fun probe() {
        val path = System.getProperty("recognize.pcm")
        assumeTrue(path != null)
        val bytes = File(path!!).readBytes()
        val pcm = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        val gen = SignatureGenerator()
        gen.feed(pcm)
        println("peaks=${gen.peakCount} samples=${gen.numberSamples} sig=${gen.encode().size}B")
        File(path + ".sig").writeBytes(gen.encode())
        val result = runBlocking { SongRecognizer(OkHttpClient(), { Base64.getEncoder().encodeToString(it) }).recognize(gen) }
        println("RESULT: $result")
    }
}
