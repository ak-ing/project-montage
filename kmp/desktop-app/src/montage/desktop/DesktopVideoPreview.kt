// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import montage.core.VideoAsset
import montage.core.VideoMetadata
import montage.ui.*
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import org.bytedeco.ffmpeg.global.avutil.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Semaphore
import javax.sound.sampled.*
import kotlin.math.*

class DesktopVideoPreview(private val directory: File) : VideoPreviewProvider {
    override fun open(asset: VideoAsset): VideoPreviewController = DesktopPlayer(directory, asset)
    @Composable override fun Surface(controller: VideoPreviewController, modifier: Modifier) {
        val player = controller as DesktopPlayer
        val frame by player.frame.collectAsState()
        val silent by player.audioUnavailable.collectAsState()
        Box(modifier.background(Color.Black)) {
            frame?.let { Image(it, "Video playback", modifier = Modifier.matchParentSize()) }
            if (silent) Text("Audio output unavailable on this device", color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black))
        }
    }
}

/** One bounded worker owns FFmpeg. Native frame timestamps are paced by a monotonic clock, never by decode speed. */
private class DesktopPlayer(directory: File, asset: VideoAsset) : VideoPreviewController {
    companion object { private val decoderGate = Semaphore(1) }
    override val state = MutableStateFlow(VideoPreviewState())
    val frame = MutableStateFlow<ImageBitmap?>(null)
    val audioUnavailable = MutableStateFlow(false)
    @Volatile private var closed = false
    @Volatile private var playing = false
    @Volatile private var audioLine: SourceDataLine? = null
    private val seek = AtomicLong(-1)
    private val released = CountDownLatch(1)
    private val worker = Thread({ decode(File(directory, "${asset.id}.media"), asset) }, "montage-video-preview").apply {
        isDaemon = true
        start()
    }

    private fun decode(file: File, asset: VideoAsset) {
        var grabber: FFmpegFrameGrabber? = null
        var failure: Throwable? = null
        var acquired = false
        try {
            decoderGate.acquire()
            acquired = true
            if (closed) return
            check(file.isFile && file.canRead() && file.length() == asset.byteSize)
            av_log_set_level(AV_LOG_ERROR)
            val source = FFmpegFrameGrabber(file).also { grabber = it }
            source.imageWidth = 640; source.imageHeight = 360 // Bound RGB allocation even before stream metadata is available.
            source.sampleMode = FrameGrabber.SampleMode.SHORT
            source.sampleFormat = AV_SAMPLE_FMT_S16
            source.timeout = 10_000
            source.setOption("protocol_whitelist", "file,pipe")
            source.setOption("format_whitelist", "mov,matroska,webm,avi,mpeg,mpegts,asf,flv,ogg")
            source.setVideoOption("threads", "2")
            source.setAudioOption("threads", "1")
            source.start()
            val context = source.formatContext
            val video = (0 until context.nb_streams()).map { context.streams(it).codecpar() }
                .firstOrNull { it.codec_type() == AVMEDIA_TYPE_VIDEO } ?: error("No video track")
            check(video.width() > 0 && video.height() > 0 && video.width().toLong() * video.height() <= 3840L * 2160)
            val geometry = Mp4VideoGeometry.read(file)
            val rotation = if (geometry != null) geometry.rotation else {
                val angle = source.displayRotation
                if (angle.isFinite()) ((-angle.roundToInt() % 360 + 360) % 360).takeIf { it in setOf(0, 90, 180, 270) } else null
            }
            val metadata = VideoMetadata(source.lengthInTime / 1000, video.width(), video.height(), rotation, source.audioChannels > 0)
            val scale = min(1.0, min(640.0 / metadata.displayWidth, 360.0 / metadata.displayHeight))
            source.imageWidth = (metadata.width * scale).roundToInt().coerceAtLeast(1)
            source.imageHeight = (metadata.height * scale).roundToInt().coerceAtLeast(1)
            if (metadata.hasAudio) {
                source.audioChannels = min(source.audioChannels, 2)
                source.sampleRate = 48_000
                try {
                    val format = AudioFormat(48_000f, 16, source.audioChannels, true, false)
                    audioLine = (AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine).apply {
                        open(format, 48_000 * source.audioChannels * 2 / 10)
                    }
                } catch (_: Exception) { audioUnavailable.value = true }
            }
            val converter = Java2DFrameConverter()
            fun render(input: Frame) {
                val image = checkNotNull(converter.convert(input))
                frame.value = rotate(image, metadata.rotationDegrees).toComposeImageBitmap()
            }
            render(checkNotNull(source.grabImage()))
            source.setVideoTimestamp(0)
            if (closed) return
            state.value = VideoPreviewState(metadata, frame.value, PlaybackStatus.Paused)
            var lastPositionUs = 0L
            var lastRenderedUs = Long.MIN_VALUE / 2
            var lastReportedUs = 0L
            var anchorUs = 0L
            var anchorNanos = 0L
            var active = false
            var pending: Frame? = null
            while (!closed) {
                val requested = seek.getAndSet(-1)
                if (requested >= 0) {
                    audioLine?.stop(); audioLine?.flush()
                    val lastFrameUs = (metadata.durationMillis * 1000 - (1_000_000 / source.frameRate.coerceAtLeast(1.0)).toLong()).coerceAtLeast(0)
                    source.setVideoTimestamp(min(requested * 1000, lastFrameUs))
                    val selected = checkNotNull(source.grabImage())
                    render(selected)
                    pending = null
                    lastPositionUs = selected.timestamp.coerceAtLeast(0)
                    lastRenderedUs = lastPositionUs
                    anchorUs = lastPositionUs; anchorNanos = System.nanoTime()
                    if (playing) audioLine?.start()
                    state.update { it.copy(positionMillis = metadata.constrainPosition(lastPositionUs / 1000),
                        status = if (playing) PlaybackStatus.Playing else PlaybackStatus.Paused) }
                }
                if (!playing) {
                    if (active) { audioLine?.stop(); active = false }
                    if (state.value.status == PlaybackStatus.Playing) state.update { it.copy(status = PlaybackStatus.Paused) }
                    Thread.sleep(10)
                    continue
                }
                if (!active) {
                    active = true
                    anchorUs = lastPositionUs; anchorNanos = System.nanoTime()
                    audioLine?.start()
                    state.update { it.copy(status = PlaybackStatus.Playing) }
                }
                val next = pending ?: source.grab()
                pending = null
                if (next == null) {
                    audioLine?.drain(); audioLine?.stop()
                    playing = false; active = false
                    state.update { it.copy(status = PlaybackStatus.Ended, positionMillis = metadata.durationMillis) }
                    continue
                }
                val timestamp = next.timestamp.coerceAtLeast(0)
                while (!closed && playing && seek.get() < 0 && (System.nanoTime() - anchorNanos) / 1000 < timestamp - anchorUs)
                    Thread.sleep(5)
                if (closed) break
                if (seek.get() >= 0) continue
                if (!playing) { pending = next; continue }
                if (next.image != null && timestamp - lastRenderedUs >= 1_000_000 / 15) {
                    render(next); lastRenderedUs = timestamp
                }
                val samples = next.samples?.firstOrNull() as? ShortBuffer
                if (samples != null) audioLine?.let { line ->
                    val input = samples.duplicate()
                    check(input.remaining() <= 48_000 * source.audioChannels)
                    val pcm = ByteBuffer.allocate(input.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
                    while (input.hasRemaining()) pcm.putShort(input.get())
                    line.write(pcm.array(), 0, pcm.position())
                }
                lastPositionUs = timestamp
                if (timestamp - lastReportedUs >= 100_000) {
                    state.update { it.copy(positionMillis = metadata.constrainPosition(timestamp / 1000)) }
                    lastReportedUs = timestamp
                }
            }
        } catch (problem: Throwable) {
            if (problem !is Exception && problem !is LinkageError) throw problem
            if (!closed) failure = problem
        } finally {
            runCatching { audioLine?.close() }; audioLine = null
            runCatching { grabber?.close() }
            if (acquired) decoderGate.release()
            if (failure != null && !closed) {
                System.err.println("Video preview decoder: ${failure.javaClass.simpleName}")
                state.update { it.copy(status = PlaybackStatus.Error) }
            }
            released.countDown()
        }
    }
    override fun play() { if (!closed && state.value.metadata != null && state.value.status != PlaybackStatus.Error) playing = true }
    override fun pause() { playing = false }
    override fun seekTo(positionMillis: Long) { state.value.metadata?.let { seek.set(it.constrainPosition(positionMillis)) } }
    override fun close() {
        if (closed) return
        closed = true; playing = false
        runCatching { audioLine?.close() }
        worker.interrupt()
        released.await(1, TimeUnit.SECONDS)
        frame.value = null
    }
    private fun rotate(image: BufferedImage, angle: Int?): BufferedImage {
        if (angle == null || angle == 0) return image
        val result = BufferedImage(if (angle == 180) image.width else image.height,
            if (angle == 180) image.height else image.width, BufferedImage.TYPE_INT_RGB)
        val graphics = result.createGraphics()
        try {
            when (angle) {
                90 -> { graphics.translate(result.width.toDouble(), 0.0); graphics.rotate(PI / 2) }
                180 -> { graphics.translate(result.width.toDouble(), result.height.toDouble()); graphics.rotate(PI) }
                270 -> { graphics.translate(0.0, result.height.toDouble()); graphics.rotate(-PI / 2) }
            }
            graphics.drawImage(image, 0, 0, null)
        } finally { graphics.dispose() }
        return result
    }
}
