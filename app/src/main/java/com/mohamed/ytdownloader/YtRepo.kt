package com.mohamed.ytdownloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/** One thing the user can pick: a video quality or an audio format. */
data class StreamOption(
    val label: String,
    val url: String,
    val ext: String,
    val mime: String,
    val audioUrl: String? = null,
)

data class VideoDetails(
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbnail: String?,
    val videos: List<StreamOption>,
    val audios: List<StreamOption>,
)

object YtRepo {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var started = false

    fun init() {
        if (!started) {
            NewPipe.init(AppDownloader())
            started = true
        }
    }

    private fun heightOf(resolution: String?): Int =
        Regex("\\d+").find(resolution ?: "")?.value?.toIntOrNull() ?: 0

    /** Reads the video page and returns title, thumbnail and the available streams. */
    suspend fun fetch(link: String): VideoDetails = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(link)

        // Videos that already contain sound (direct download, no merging needed)
        val videosByHeight = info.videoStreams
            .filter {
                it.isUrl && !it.isVideoOnly &&
                    it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
            }
            .groupBy { heightOf(it.resolution) }
            .map { (h, list) ->
                val best = list.firstOrNull { it.format?.suffix == "mp4" } ?: list.first()
                h to StreamOption(
                    label = if (h > 0) "${h}p" else (best.resolution ?: "Video"),
                    url = best.content,
                    ext = best.format?.suffix ?: "mp4",
                    mime = best.format?.mimeType ?: "video/mp4",
                )
            }
            .toMap()

        val audioForContainer = info.audioStreams
            .filter { it.isUrl }
            .groupBy { it.format?.suffix?.lowercase() }
        val adaptiveVideos = info.videoOnlyStreams
            .filter { it.isUrl }
            .mapNotNull { video ->
                val suffix = video.format?.suffix?.lowercase() ?: return@mapNotNull null
                val audioSuffix = when (suffix) {
                    "mp4" -> "m4a"
                    "webm" -> "webm"
                    else -> return@mapNotNull null
                }
                val audio = audioForContainer[audioSuffix]
                    ?.maxByOrNull { it.averageBitrate }
                    ?: return@mapNotNull null
                val height = heightOf(video.resolution)
                if (height == 0 || height in videosByHeight) return@mapNotNull null

                val ext = if (suffix == "webm") "webm" else "mp4"
                height to StreamOption(
                    label = "${height}p",
                    url = video.content,
                    ext = ext,
                    mime = if (ext == "webm") "video/webm" else "video/mp4",
                    audioUrl = audio.content,
                )
            }
            .distinctBy { it.first }
            .toMap()

        val videos = (videosByHeight + adaptiveVideos)
            .toSortedMap(compareByDescending { it })
            .values

        // Audio only (M4A first because it plays everywhere)
        val audios = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedWith(
                compareByDescending<AudioStream> { it.format?.suffix == "m4a" }
                    .thenByDescending { it.averageBitrate }
            )
            .take(4)
            .map { s ->
                val ext = s.format?.suffix ?: "m4a"
                val kbps = if (s.averageBitrate > 0) " • ${s.averageBitrate} kbps" else ""
                StreamOption(
                    label = "${ext.uppercase()}$kbps",
                    url = s.content,
                    ext = ext,
                    mime = s.format?.mimeType ?: "audio/mp4",
                )
            }

        VideoDetails(
            title = info.name ?: "Untitled",
            uploader = info.uploaderName ?: "",
            durationSec = info.duration,
            thumbnail = info.thumbnails.maxByOrNull { it.height }?.url,
            videos = videos,
            audios = audios,
        )
    }

    /** Downloads the chosen stream into the phone's Downloads/YT-Downloader folder. */
    suspend fun download(
        context: Context,
        title: String,
        option: StreamOption,
        onProgress: (Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val safeName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifEmpty { "video" }
        val fileName = "$safeName.${option.ext}"

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, option.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YT-Downloader")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the file")

        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open the file")
            out.use {
                if (option.audioUrl == null) {
                    copyInChunks(option.url, it, onProgress)
                } else {
                    val videoFile = File.createTempFile("yt-video-", ".stream", context.cacheDir)
                    val audioFile = File.createTempFile("yt-audio-", ".stream", context.cacheDir)
                    val mergedFile = File.createTempFile("yt-merged-", ".${option.ext}", context.cacheDir)
                    try {
                        FileOutputStream(videoFile).use { videoOut ->
                            copyInChunks(option.url, videoOut) { onProgress(it * 0.45f) }
                        }
                        FileOutputStream(audioFile).use { audioOut ->
                            copyInChunks(option.audioUrl, audioOut) { onProgress(0.45f + it * 0.45f) }
                        }
                        muxStreams(videoFile, audioFile, mergedFile, option.ext)
                        onProgress(0.9f)
                        FileInputStream(mergedFile).use { input -> input.copyTo(it) }
                        onProgress(1f)
                    } finally {
                        videoFile.delete()
                        audioFile.delete()
                        mergedFile.delete()
                    }
                }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        fileName
    }

    private data class MuxSample(val size: Int, val timeUs: Long, val flags: Int)

    private fun muxStreams(videoFile: File, audioFile: File, outputFile: File, ext: String) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxingCompleted = false
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val videoTrack = findTrack(videoExtractor, "video/")
            val audioTrack = findTrack(audioExtractor, "audio/")
            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)

            val outputFormat = if (ext == "webm") {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }
            val activeMuxer = MediaMuxer(outputFile.absolutePath, outputFormat)
            muxer = activeMuxer
            val outputVideoTrack = activeMuxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val outputAudioTrack = activeMuxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            val videoBuffer = sampleBuffer(videoExtractor.getTrackFormat(videoTrack))
            val audioBuffer = sampleBuffer(audioExtractor.getTrackFormat(audioTrack))
            var videoSample = readSample(videoExtractor, videoBuffer)
            var audioSample = readSample(audioExtractor, audioBuffer)
            if (videoSample == null || audioSample == null) {
                throw IOException("The video or audio stream is empty")
            }

            activeMuxer.start()
            muxerStarted = true
            while (videoSample != null || audioSample != null) {
                val writeVideo = audioSample == null ||
                    (videoSample != null && videoSample.timeUs <= audioSample.timeUs)
                val extractor = if (writeVideo) videoExtractor else audioExtractor
                val buffer = if (writeVideo) videoBuffer else audioBuffer
                val sample = if (writeVideo) videoSample!! else audioSample!!
                val track = if (writeVideo) outputVideoTrack else outputAudioTrack
                val info = MediaCodec.BufferInfo().apply {
                    set(0, sample.size, sample.timeUs, sample.flags)
                }
                activeMuxer.writeSampleData(track, buffer, info)
                extractor.advance()
                if (writeVideo) {
                    videoSample = readSample(videoExtractor, videoBuffer)
                } else {
                    audioSample = readSample(audioExtractor, audioBuffer)
                }
            }
            muxingCompleted = true
        } finally {
            try {
                if (muxerStarted && muxingCompleted) muxer?.stop()
            } finally {
                try {
                    muxer?.release()
                } finally {
                    videoExtractor.release()
                    audioExtractor.release()
                }
            }
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith(mimePrefix) == true) return index
        }
        throw IOException("Could not find a $mimePrefix track to merge")
    }

    private fun sampleBuffer(format: MediaFormat): ByteBuffer {
        val inputSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            0
        }
        return ByteBuffer.allocateDirect(maxOf(inputSize, 4 * 1024 * 1024))
    }

    private fun readSample(extractor: MediaExtractor, buffer: ByteBuffer): MuxSample? {
        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) return null
        val timeUs = extractor.sampleTime
        if (timeUs < 0) throw IOException("Invalid stream timestamp")
        return MuxSample(size, timeUs, extractor.sampleFlags)
    }

    /**
     * YouTube works best when a file is requested in pieces (4 MB each),
     * so we ask for one piece at a time using the HTTP "Range" header.
     */
    private fun copyInChunks(url: String, out: OutputStream, onProgress: (Float) -> Unit) {
        val chunk = 4L * 1024 * 1024
        val buffer = ByteArray(32 * 1024)
        var pos = 0L
        var total = -1L

        while (total < 0 || pos < total) {
            val before = pos
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", AppDownloader.USER_AGENT)
                .header("Range", "bytes=$pos-${pos + chunk - 1}")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response")

                if (response.code == 200) {
                    // The server ignored "Range": it sends the whole file at once
                    val length = body.contentLength()
                    var read = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            read += n
                            if (length > 0) onProgress(read.toFloat() / length)
                        }
                    }
                    pos = read
                    total = read
                } else {
                    // Content-Range looks like:  bytes 0-4194303/12345678
                    total = response.header("Content-Range")
                        ?.substringAfterLast('/')?.toLongOrNull()
                        ?: throw IOException("Unknown file size")
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            pos += n
                            onProgress(pos.toFloat() / total)
                        }
                    }
                }
            }
            if (pos == before) throw IOException("No data received")
        }
    }
}
