package com.freease.music.data.lyrics.align

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/** Mendekode berkas audio (webm/opus, m4a/aac) langsung menjadi [AudioFeatures] dengan decoder bawaan HP. */
object AudioDecoder {
    private const val TIMEOUT_US = 10_000L

    fun features(file: File, isActive: () -> Boolean = { true }): AudioFeatures {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("Tidak ada trek audio")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return decode(ex, codec, format, isActive)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            ex.release()
        }
    }

    private fun decode(ex: MediaExtractor, codec: MediaCodec, input: MediaFormat, isActive: () -> Boolean): AudioFeatures {
        var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        var extractor: AudioFeatureExtractor? = null
        var shorts = ShortArray(0)
        var floats = FloatArray(0)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (true) {
            if (!isActive()) throw IOException("Dibatalkan")
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(TIMEOUT_US)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                oi >= 0 -> {
                    if (info.size > 0) {
                        val ext = extractor ?: AudioFeatureExtractor(rate, channels).also { extractor = it }
                        val out = codec.getOutputBuffer(oi)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val ordered = out.slice().order(ByteOrder.nativeOrder())
                        if (floatPcm) {
                            val fb = ordered.asFloatBuffer()
                            val n = fb.remaining()
                            if (floats.size < n) floats = FloatArray(n)
                            fb.get(floats, 0, n)
                            ext.feed(floats, n)
                        } else {
                            val sb = ordered.asShortBuffer()
                            val n = sb.remaining()
                            if (shorts.size < n) shorts = ShortArray(n)
                            sb.get(shorts, 0, n)
                            ext.feed(shorts, n)
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        return extractor?.finish() ?: throw IOException("Audio kosong")
    }
}
