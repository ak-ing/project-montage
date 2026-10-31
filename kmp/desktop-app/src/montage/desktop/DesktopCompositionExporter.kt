// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import montage.core.CompositionRequest
import montage.core.DeviceCompositionExporter
import montage.core.ExportCancelledException
import montage.core.ExportProgress
import montage.core.ExportResult
import montage.core.MediaHandle
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop first-slice renderer. It streams decoded frames through JavaCV, so
 * the source clips and output are never loaded into a single byte array.
 * Transitions are deliberately rejected by the shared request contract.
 */
class DesktopCompositionExporter(
    private val mediaDirectory: File,
    private val exportDirectory: File,
) : DeviceCompositionExporter {
    private val cancelled = AtomicBoolean(false)
    private val active = AtomicBoolean(false)

    override suspend fun export(request: CompositionRequest, onProgress: (ExportProgress) -> Unit): ExportResult =
        withContext(Dispatchers.IO) {
            check(request.transition == "none") { "Desktop basic renderer only supports hard cuts" }
            check(active.compareAndSet(false, true)) { "An export is already running" }
            cancelled.set(false)
            val id = UUID.randomUUID().toString()
            val partial = File(exportDirectory, "$id.part.mp4")
            val output = File(exportDirectory, "$id.mp4")
            var recorder: FFmpegFrameRecorder? = null
            var totalWritten = 0L
            try {
                check(exportDirectory.isDirectory || exportDirectory.mkdirs()) { "Cannot create export directory" }
                val first = readMetadata(request)
                recorder = FFmpegFrameRecorder(partial, first.width, first.height, first.audioChannels)
                recorder.format = "mp4"
                recorder.videoCodec = AV_CODEC_ID_H264
                if (first.audioChannels > 0) recorder.audioCodec = AV_CODEC_ID_AAC
                recorder.frameRate = first.frameRate.coerceAtLeast(1.0)
                recorder.videoBitrate = 2_000_000
                recorder.start()
                onProgress(ExportProgress(0, request.durationMillis))

                var timelineOffset = 0L
                for (clip in request.clips) {
                    check(!cancelled.get()) { throw ExportCancelledException() }
                    val source = FFmpegFrameGrabber(File(mediaDirectory, "${clip.videoId}.media"))
                    try {
                        source.start()
                        check(source.imageWidth == first.width && source.imageHeight == first.height) {
                            "All clips must have the same encoded dimensions in the basic renderer"
                        }
                        check((source.audioChannels > 0) == (first.audioChannels > 0)) {
                            "All clips must either contain audio or be silent in the basic renderer"
                        }
                        source.setTimestamp(clip.inMillis * 1000)
                        while (true) {
                            check(!cancelled.get()) { throw ExportCancelledException() }
                            val frame: Frame = source.grab() ?: break
                            val sourceTimestamp = frame.timestamp
                            if (sourceTimestamp < clip.inMillis * 1000) continue
                            if (sourceTimestamp >= clip.outMillis * 1000) break
                            recorder.timestamp = timelineOffset * 1000 + (sourceTimestamp - clip.inMillis * 1000)
                            recorder.record(frame)
                            val completed = timelineOffset + ((sourceTimestamp / 1000) - clip.inMillis)
                            totalWritten = completed.coerceIn(0, request.durationMillis)
                            onProgress(ExportProgress(totalWritten, request.durationMillis))
                        }
                    } finally {
                        runCatching { source.stop() }
                        runCatching { source.release() }
                    }
                    timelineOffset += clip.durationMillis
                }
                recorder.stop()
                recorder.release()
                recorder = null
                check(!cancelled.get()) { throw ExportCancelledException() }
                check(partial.isFile && partial.length() > 0) { "Renderer produced no output" }
                check(partial.renameTo(output)) { "Cannot commit rendered output" }
                onProgress(ExportProgress(request.durationMillis, request.durationMillis))
                ExportResult(MediaHandle(output.toURI().toString(), "video/mp4"), request.durationMillis, output.length())
            } catch (cancelledException: ExportCancelledException) {
                throw cancelledException
            } catch (cancelledException: CancellationException) {
                throw cancelledException
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                partial.delete()
                active.set(false)
            }
        }

    private data class SourceMetadata(val width: Int, val height: Int, val audioChannels: Int, val frameRate: Double)

    private fun readMetadata(request: CompositionRequest): SourceMetadata {
        val firstClip = request.clips.first()
        val grabber = FFmpegFrameGrabber(File(mediaDirectory, "${firstClip.videoId}.media"))
        return try {
            grabber.start()
            SourceMetadata(grabber.imageWidth, grabber.imageHeight, grabber.audioChannels, grabber.frameRate)
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    override fun cancel() {
        if (active.get()) cancelled.set(true)
    }
}
