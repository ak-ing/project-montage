// SPDX-License-Identifier: Apache-2.0
package montage.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import montage.core.VideoAsset
import montage.core.VideoMetadata

enum class PlaybackStatus { Loading, Paused, Playing, Ended, Error }

data class VideoPreviewState(
    val metadata: VideoMetadata? = null,
    val thumbnail: ImageBitmap? = null,
    val status: PlaybackStatus = PlaybackStatus.Loading,
    val positionMillis: Long = 0,
)

interface VideoPreviewController {
    val state: StateFlow<VideoPreviewState>
    fun play()
    fun pause()
    fun seekTo(positionMillis: Long)
    fun close()
}

interface VideoPreviewProvider {
    fun open(asset: VideoAsset): VideoPreviewController
    @Composable fun Surface(controller: VideoPreviewController, modifier: Modifier)
}

@Composable
fun VideoPreview(asset: VideoAsset, provider: VideoPreviewProvider, onBack: () -> Unit) {
    var attempt by remember(asset.id) { mutableStateOf(0) }
    val player = remember(provider, asset.id, attempt) { provider.open(asset) }
    DisposableEffect(player) { onDispose { player.close() } }
    val state by player.state.collectAsState()
    val metadata = state.metadata
    var seeking by remember(player) { mutableStateOf<Float?>(null) }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(modifier = Modifier.testTag("preview-back"), onClick = onBack) { Text("Back to videos") }
                Text(asset.name, style = MaterialTheme.typography.titleLarge)
                when (state.status) {
                    PlaybackStatus.Error -> {
                        Text("Cannot preview this video. The file may be missing, damaged, or unsupported on this device.",
                            modifier = Modifier.testTag("preview-error"))
                        Button(onClick = { attempt++ }, modifier = Modifier.testTag("preview-retry")) { Text("Retry preview") }
                    }
                    PlaybackStatus.Loading -> Text("Reading video…", modifier = Modifier.testTag("preview-loading"))
                    else -> Unit
                }
                metadata?.let {
                    Text("${it.durationMillis} ms · ${it.width} × ${it.height} · ${it.rotationDegrees?.let { r -> "$r°" } ?: "orientation not reported"} · ${if (it.hasAudio) "Audio" else "No audio"}",
                        modifier = Modifier.testTag("video-metadata"))
                }
                state.thumbnail?.let {
                    Image(it, contentDescription = "Video thumbnail", modifier = Modifier.size(128.dp, 72.dp).testTag("video-thumbnail"))
                }
                provider.Surface(player, Modifier.fillMaxWidth().height(220.dp).testTag("video-surface"))
                if (metadata != null && state.status != PlaybackStatus.Error) {
                    val enabled = state.status != PlaybackStatus.Loading
                    Text("${state.positionMillis} / ${metadata.durationMillis} ms", modifier = Modifier.testTag("video-position"))
                    Slider(value = seeking ?: state.positionMillis.toFloat(),
                        onValueChange = { seeking = it },
                        onValueChangeFinished = { seeking?.let { player.seekTo(it.toLong()) }; seeking = null },
                        valueRange = 0f..metadata.durationMillis.toFloat(), enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("video-seek"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = enabled, modifier = Modifier.testTag("play-video"), onClick = {
                            if (state.status == PlaybackStatus.Ended) player.seekTo(0)
                            player.play()
                        }) { Text(if (state.status == PlaybackStatus.Ended) "Replay" else "Play") }
                        OutlinedButton(enabled = enabled, modifier = Modifier.testTag("pause-video"), onClick = { player.pause() }) { Text("Pause") }
                    }
                    Text(when (state.status) {
                        PlaybackStatus.Playing -> "Playing"
                        PlaybackStatus.Ended -> "Playback finished"
                        else -> "Paused"
                    }, modifier = Modifier.testTag("playback-status"))
                }
            }
        }
    }
}
