// SPDX-License-Identifier: Apache-2.0
package montage.android

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import montage.core.VideoAsset
import montage.core.VideoMetadata
import montage.ui.*
import java.io.File

class AndroidVideoPreview(private val context: Context) : VideoPreviewProvider {
    private var current: AndroidPlayer? = null
    override fun open(asset: VideoAsset): VideoPreviewController = AndroidPlayer(context, asset).also { current = it }
    fun pause() { current?.pause() }
    @Composable override fun Surface(controller: VideoPreviewController, modifier: Modifier) {
        AndroidView(factory = { (controller as AndroidPlayer).view }, modifier = modifier)
    }
}

private class AndroidPlayer(context: Context, asset: VideoAsset) : VideoPreviewController {
    override val state = MutableStateFlow(VideoPreviewState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val view = VideoView(context)
    private var native: MediaPlayer? = null
    private var closed = false
    private var prepared = false
    private fun fail() { if (!closed) state.update { it.copy(status = PlaybackStatus.Error) } }
    private fun ready() {
        if (prepared && state.value.metadata != null && state.value.status != PlaybackStatus.Error)
            state.update { it.copy(status = PlaybackStatus.Paused) }
    }
    init {
        view.setOnPreparedListener { player ->
            native = player
            prepared = true
            player.seekTo(1) // Display the first decoded frame while remaining paused.
            player.setOnSeekCompleteListener { if (!closed) state.update { it.copy(positionMillis = player.currentPosition.toLong()) } }
            ready()
        }
        view.setOnErrorListener { _, _, _ -> fail(); true }
        view.setOnCompletionListener { if (!closed) state.update { it.copy(status = PlaybackStatus.Ended, positionMillis = it.metadata?.durationMillis ?: it.positionMillis) } }
        scope.launch {
            try {
                val file = File(context.filesDir, "media/${asset.id}.media")
                val result = withContext(Dispatchers.IO) {
                    check(file.isFile && file.length() == asset.byteSize)
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        fun key(id: Int) = retriever.extractMetadata(id)
                        val metadata = VideoMetadata(
                            key(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong(),
                            key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt(),
                            key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt(),
                            key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt(),
                            key(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                        )
                        val bitmap = if (Build.VERSION.SDK_INT >= 27)
                            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                        else {
                            check(metadata.width.toLong() * metadata.height <= 3840L * 2160) { "Video too large for legacy thumbnail decoding" }
                            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                        metadata to checkNotNull(bitmap).asImageBitmap()
                    } finally { retriever.release() }
                }
                state.update { it.copy(metadata = result.first, thumbnail = result.second) }
                view.setVideoPath(file.absolutePath)
                withTimeout(15_000) { while (!prepared && state.value.status != PlaybackStatus.Error) delay(100) }
                ready()
            } catch (_: TimeoutCancellationException) { fail() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail() }
        }
        scope.launch {
            while (isActive) {
                if (prepared && !closed && state.value.status != PlaybackStatus.Error) {
                    runCatching { view.currentPosition.toLong() }.onSuccess { position ->
                        state.update { it.copy(positionMillis = it.metadata?.constrainPosition(position) ?: 0) }
                    }.onFailure { fail() }
                }
                delay(100)
            }
        }
    }
    override fun play() {
        if (!closed && prepared && state.value.status != PlaybackStatus.Error) runCatching {
            view.start(); state.update { it.copy(status = PlaybackStatus.Playing) }
        }.onFailure { fail() }
    }
    override fun pause() {
        if (!closed && prepared && state.value.status != PlaybackStatus.Error) runCatching {
            view.pause(); state.update { it.copy(status = PlaybackStatus.Paused) }
        }.onFailure { fail() }
    }
    override fun seekTo(positionMillis: Long) {
        val position = state.value.metadata?.constrainPosition(positionMillis) ?: return
        if (!closed && prepared) runCatching {
            if (Build.VERSION.SDK_INT >= 26) native?.seekTo(position, MediaPlayer.SEEK_CLOSEST)
            else view.seekTo(position.toInt())
            state.update { it.copy(status = if (it.status == PlaybackStatus.Playing) PlaybackStatus.Playing else PlaybackStatus.Paused) }
        }.onFailure { fail() }
    }
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        view.stopPlayback()
        native = null
    }
}
