package com.mohamed.ytdownloader

import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * NewPipe Extractor does not do any networking by itself.
 * This class tells it how to make web requests (using OkHttp).
 */
class AppDownloader : Downloader() {

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val url = request.url()
        val data = request.dataToSend()

        var body: okhttp3.RequestBody? = data?.toRequestBody()
        if (body == null && (method == "POST" || method == "PUT")) {
            body = ByteArray(0).toRequestBody()
        }

        val builder = okhttp3.Request.Builder()
            .method(method, body)
            .url(url)
            .addHeader("User-Agent", USER_AGENT)

        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (value in values) builder.addHeader(name, value)
        }

        val response = client.newCall(builder.build()).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCAPTCHA challenge requested", url)
        }

        val text = response.body?.string()
        val finalUrl = response.request.url.toString()
        return Response(response.code, response.message, response.headers.toMultimap(), text, finalUrl)
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
