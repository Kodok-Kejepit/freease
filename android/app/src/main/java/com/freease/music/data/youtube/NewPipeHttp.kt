package com.freease.music.data.youtube

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

/** Jembatan HTTP untuk NewPipeExtractor. */
class NewPipeHttp(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
        request.headers().forEach { (name, values) ->
            if (values.size > 1) {
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            } else if (values.size == 1) {
                builder.header(name, values[0])
            }
        }
        if (request.headers().keys.none { it.equals("User-Agent", true) }) {
            builder.header("User-Agent", DESKTOP_UA)
        }
        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 429) {
                throw ReCaptchaException("YouTube membatasi permintaan (429)", request.url())
            }
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                resp.body?.string(),
                resp.request.url.toString(),
            )
        }
    }
}

/**
 * URL googlevideo terikat ke "client" YouTube yang dipakai saat ekstraksi; User-Agent
 * permintaan harus cocok, kalau tidak server membalas 403.
 */
class StreamHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        val url = req.url.toString()
        if (!url.contains("googlevideo.com") && !url.contains("youtube.com/api/manifest")) {
            return chain.proceed(req)
        }
        val loc = Localization.DEFAULT
        val ua = when {
            YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(loc)
            YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(loc)
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(loc)
            else -> DESKTOP_UA
        }
        val b = req.newBuilder().header("User-Agent", ua)
        if (YoutubeParsingHelper.isWebStreamingUrl(url) || YoutubeParsingHelper.isWebEmbeddedPlayerStreamingUrl(url)) {
            b.header("Origin", "https://www.youtube.com").header("Referer", "https://www.youtube.com/")
        }
        return chain.proceed(b.build())
    }
}
