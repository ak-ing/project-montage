// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.PauseTransition
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.embed.swing.SwingFXUtils
import javafx.event.EventHandler
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.media.AudioTrack
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.util.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import montage.core.VideoAsset
import montage.core.VideoMetadata
import montage.ui.*
import java.awt.EventQueue
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

class DesktopVideoPreview(private val directory: File) : VideoPreviewProvider {
    override fun open(asset: VideoAsset): VideoPreviewController = DesktopPlayer(directory, asset)
    @Composable override fun Surface(controller: VideoPreviewController, modifier: Modifier) {
        val frame by (controller as DesktopPlayer).frame.collectAsState()
        Box(modifier.background(Color.Black)) {
            frame?.let { Image(it, contentDescription = "Video playback", modifier = Modifier.matchParentSize()) }
        }
    }
}

/** Native decoding/audio clock; bounded CPU preview frames keep rendering inside Compose. */
private class DesktopPlayer(directory: File, asset: VideoAsset) : VideoPreviewController {
    override val state = MutableStateFlow(VideoPreviewState())
    val frame = MutableStateFlow<ImageBitmap?>(null)
    @Volatile private var closed = false
    private var player: MediaPlayer? = null
    @Volatile private var fxPanel: JFXPanel? = null
    private var priming = true
    private var deadline: PauseTransition? = null
    private var frameClock: Timeline? = null
    private var captureUntil = 0L

    private fun fail(cause: Throwable? = null) {
        cause?.let { System.err.println("Video preview decoder: $it") }
        if (!closed) {
            state.update { it.copy(status = PlaybackStatus.Error) }
            frameClock?.stop()
            player?.stop()
        }
    }
    init {
        EventQueue.invokeLater {
            if (!closed) try {
                System.setProperty("prism.order", "sw")
                val surface = JFXPanel() // Initializes the platform toolkit once; no Swing component is embedded in Compose.
                fxPanel = surface
                Platform.setImplicitExit(false)
                Platform.runLater {
                    if (!closed) try {
                        val file = File(directory, "${asset.id}.media")
                        check(file.isFile && file.canRead() && file.length() == asset.byteSize)
                        val geometry = Mp4VideoGeometry.read(file)
                        val media = Media(file.toURI().toString())
                        val native = MediaPlayer(media)
                        native.isMute = true // Priming a thumbnail must not play sound before the user presses Play.
                        player = native
                        val view = MediaView(native)
                        surface.scene = Scene(Group(view))
                        media.setOnError { fail(media.error) }
                        native.setOnError { fail(native.error) }
                        native.setOnReady {
                            if (!closed && state.value.status != PlaybackStatus.Error) try {
                                val metadata = VideoMetadata(native.totalDuration.toMillis().toLong(),
                                    geometry?.width ?: media.width, geometry?.height ?: media.height,
                                    geometry?.rotation, media.tracks.any { it is AudioTrack })
                                val scale = min(1.0, min(640.0 / metadata.displayWidth, 360.0 / metadata.displayHeight))
                                view.fitWidth = metadata.width * scale
                                view.fitHeight = metadata.height * scale
                                view.rotate = (metadata.rotationDegrees ?: 0).toDouble()
                                val pixels = WritableImage((metadata.displayWidth * scale).roundToInt().coerceAtLeast(1),
                                    (metadata.displayHeight * scale).roundToInt().coerceAtLeast(1))
                                state.update { it.copy(metadata = metadata) }
                                fun capture() {
                                    if (!closed && state.value.status != PlaybackStatus.Error) {
                                        val snapshot = view.snapshot(SnapshotParameters(), pixels)
                                        frame.value = SwingFXUtils.fromFXImage(snapshot, null).toComposeImageBitmap()
                                    }
                                }
                                capture() // Attach the rendering peer before starting native frame delivery.
                                frameClock = Timeline(KeyFrame(Duration.millis(1000.0 / 15), EventHandler {
                                    if (state.value.status == PlaybackStatus.Playing || System.nanoTime() < captureUntil) {
                                        try { capture() } catch (failure: Exception) { fail(failure) }
                                    }
                                })).apply { cycleCount = Animation.INDEFINITE; play() }
                                native.setOnPlaying {
                                    if (priming && !closed && state.value.status != PlaybackStatus.Error) {
                                        PauseTransition(Duration.millis(200.0)).apply {
                                            setOnFinished {
                                                if (!closed && state.value.status != PlaybackStatus.Error) try {
                                                    native.pause()
                                                    native.isMute = false
                                                    capture()
                                                    System.err.println("Video preview primed at ${native.currentTime.toMillis().toLong()} ms")
                                                    val thumbnail = checkNotNull(frame.value)
                                                    native.seek(Duration.ZERO)
                                                    captureUntil = System.nanoTime() + 1_000_000_000
                                                    priming = false
                                                    deadline?.stop()
                                                    state.update { it.copy(thumbnail = thumbnail, positionMillis = 0, status = PlaybackStatus.Paused) }
                                                } catch (failure: Exception) { fail(failure) }
                                            }
                                            play()
                                        }
                                    }
                                }
                                native.play() // Decode a frame before reporting a ready preview.
                            } catch (failure: Exception) { fail(failure) }
                        }
                        native.currentTimeProperty().addListener { _, _, position ->
                            if (!closed && !priming) state.update {
                                it.copy(positionMillis = it.metadata?.constrainPosition(position.toMillis().toLong()) ?: 0)
                            }
                        }
                        native.setOnPaused { if (!closed && !priming && state.value.status != PlaybackStatus.Error) state.update { it.copy(status = PlaybackStatus.Paused) } }
                        native.setOnEndOfMedia { if (!closed && !priming && state.value.status != PlaybackStatus.Error) state.update { it.copy(status = PlaybackStatus.Ended, positionMillis = it.metadata?.durationMillis ?: 0) } }
                        deadline = PauseTransition(Duration.seconds(15.0)).apply {
                            setOnFinished { if (state.value.status == PlaybackStatus.Loading) fail() }
                            play()
                        }
                    } catch (failure: Exception) { fail(failure) }
                }
            } catch (failure: Throwable) {
                if (failure !is Exception && failure !is LinkageError) throw failure
                System.err.println("Video preview runtime: ${failure.javaClass.simpleName}")
                if (!closed) state.update { it.copy(status = PlaybackStatus.Error) }
            }
        }
    }
    private fun onFx(action: (MediaPlayer) -> Unit) {
        if (!closed && fxPanel != null) Platform.runLater {
            if (!closed && !priming && state.value.status != PlaybackStatus.Error) try { player?.let(action) }
            catch (failure: Exception) { fail(failure) }
        }
    }
    override fun play() = onFx { it.play(); state.update { s -> s.copy(status = PlaybackStatus.Playing) } }
    override fun pause() = onFx { it.pause(); state.update { s -> s.copy(status = PlaybackStatus.Paused) } }
    override fun seekTo(positionMillis: Long) = onFx { native ->
        state.value.metadata?.let { native.seek(Duration.millis(it.constrainPosition(positionMillis).toDouble())) }
        captureUntil = System.nanoTime() + 1_000_000_000
        state.update { it.copy(status = if (it.status == PlaybackStatus.Playing) PlaybackStatus.Playing else PlaybackStatus.Paused) }
    }
    override fun close() {
        if (closed) return
        closed = true
        if (fxPanel != null) Platform.runLater {
            deadline?.stop()
            frameClock?.stop()
            player?.dispose()
            player = null
            fxPanel?.scene = null
            frame.value = null
        }
    }
}
