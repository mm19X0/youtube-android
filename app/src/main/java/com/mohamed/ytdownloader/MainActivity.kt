package com.mohamed.ytdownloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

// ---------- Colors ----------
private val Bg = Color(0xFF121217)
private val CardBg = Color(0xFF1E1E24)
private val Field = Color(0xFF2A2A33)
private val Accent = Color(0xFFFF3B3B)
private val Muted = Color(0xFF9A9AA5)
private val Green = Color(0xFF4CD964)
private val Red = Color(0xFFFF6B6B)

class MainActivity : ComponentActivity() {

    // A link shared from the YouTube app ("Share" -> YT Downloader)
    private var sharedUrl by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        YtRepo.init()
        readSharedLink(intent)
        setContent {
            AppTheme { DownloaderScreen(sharedUrl) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readSharedLink(intent)
    }

    private fun readSharedLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            Regex("https?://\\S+").find(text)?.let { sharedUrl = it.value }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            background = Bg,
            onBackground = Color.White,
            surface = CardBg,
            onSurface = Color.White,
        ),
        content = content,
    )
}

private fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return "--:--"
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun shortError(e: Throwable): String =
    (e.message ?: e.javaClass.simpleName).lineSequence().first().take(140)

@Composable
private fun ToggleButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
        ) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(text, color = Muted) }
    }
}

@Composable
fun DownloaderScreen(sharedUrl: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var url by remember { mutableStateOf("") }
    var details by remember { mutableStateOf<VideoDetails?>(null) }
    var audioMode by remember { mutableStateOf(false) }
    var selectedVideo by remember { mutableStateOf<StreamOption?>(null) }
    var selectedAudio by remember { mutableStateOf<StreamOption?>(null) }
    var status by remember { mutableStateOf("Paste a YouTube link, or share one from the YouTube app.") }
    var statusColor by remember { mutableStateOf(Muted) }
    var progress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(sharedUrl) {
        if (sharedUrl.isNotBlank()) url = sharedUrl
    }

    // Whenever the link changes: wait a moment, then load the video info
    LaunchedEffect(url) {
        details = null
        val link = url.trim()
        if (!link.startsWith("http")) return@LaunchedEffect
        delay(600)
        status = "Loading video info..."
        statusColor = Muted
        try {
            val d = YtRepo.fetch(link)
            details = d
            selectedVideo = d.videos.firstOrNull()
            selectedAudio = d.audios.firstOrNull()
            audioMode = d.videos.isEmpty() && d.audios.isNotEmpty()
            status = "Ready to download"
            statusColor = Green
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "Could not load video: ${shortError(e)}"
            statusColor = Red
        }
    }

    val options = if (audioMode) details?.audios.orEmpty() else details?.videos.orEmpty()
    val selected = if (audioMode) selectedAudio else selectedVideo

    fun startDownload() {
        val d = details ?: return
        val option = selected ?: return
        busy = true
        progress = 0f
        status = "Downloading..."
        statusColor = Muted
        scope.launch {
            try {
                val name = YtRepo.download(context, d.title, option) { p -> progress = p }
                progress = 1f
                status = "Done ✔  Saved in Downloads/YT-Downloader\n$name"
                statusColor = Green
            } catch (e: Exception) {
                status = "Error: ${shortError(e)}"
                statusColor = Red
            }
            busy = false
        }
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            Column(
                modifier = Modifier
                    .background(Bg)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = Accent,
                    trackColor = Field,
                )
                Text(
                    text = status,
                    color = statusColor,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
                Button(
                    onClick = { startDownload() },
                    enabled = !busy && details != null && selected != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                ) {
                    Text(if (busy) "Downloading..." else "Download", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "▶  YT Downloader",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 12.dp),
            )
            Text(
                "Paste a link, pick a type and quality, download.",
                color = Muted,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 4.dp, bottom = 16.dp),
            )

            // --- Link ---
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg, contentColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        placeholder = { Text("Paste YouTube link here...", color = Muted) },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Field,
                            focusedContainerColor = Field,
                            unfocusedContainerColor = Field,
                            cursorColor = Accent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val text = clipboard.getText()?.text?.trim().orEmpty()
                            if (text.isNotEmpty()) url = text
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A46), contentColor = Color.White),
                    ) { Text("Paste") }
                }
            }

            // --- Preview ---
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg, contentColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Field),
                        contentAlignment = Alignment.Center,
                    ) {
                        val thumb = details?.thumbnail
                        if (thumb != null) {
                            AsyncImage(
                                model = thumb,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text("Thumbnail preview", color = Muted)
                        }
                    }
                    Text(
                        text = details?.title ?: "No video loaded",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    details?.let {
                        Text(
                            "${it.uploader}  •  ${formatDuration(it.durationSec)}",
                            color = Muted,
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            // --- Type + quality ---
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg, contentColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ToggleButton("🎬  Video", !audioMode, Modifier.weight(1f)) { audioMode = false }
                        ToggleButton("🎵  Audio", audioMode, Modifier.weight(1f)) { audioMode = true }
                    }
                    Text(
                        if (audioMode) "Format" else "Quality",
                        color = Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                    )
                    if (options.isEmpty()) {
                        Text(
                            if (details == null) "Load a video first" else "No direct streams of this type were found",
                            color = Muted,
                        )
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            options.forEach { opt ->
                                ToggleButton(opt.label, opt == selected) {
                                    if (audioMode) selectedAudio = opt else selectedVideo = opt
                                }
                            }
                        }
                    }
                    if (!audioMode && details != null) {
                        Text(
                            "Higher qualities combine separate video and audio streams during download.",
                            color = Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }
    }
}
