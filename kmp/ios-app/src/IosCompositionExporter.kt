// SPDX-License-Identifier: Apache-2.0
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import montage.core.CompositionRequest
import montage.core.DeviceCompositionExporter
import montage.core.ExportCancelledException
import montage.core.ExportProgress
import montage.core.ExportResult
import montage.core.MediaHandle
import platform.AVFoundation.*
import platform.CoreMedia.*
import platform.Foundation.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** iOS 15-compatible AVFoundation hard-cut composition/export adapter. */
@OptIn(ExperimentalForeignApi::class)
class IosCompositionExporter : DeviceCompositionExporter {
    private val manager = NSFileManager.defaultManager
    private val root = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL)
        .path!! + "/ProjectMontage"
    private val mediaDirectory get() = "$root/media"
    private val exportDirectory get() = "$root/exports"
    private var active: AVAssetExportSession? = null
    private var activePartial: String? = null

    override suspend fun export(request: CompositionRequest, onProgress: (ExportProgress) -> Unit): ExportResult =
        withContext(Dispatchers.Main) {
            check(request.transition == "none") { "iOS basic renderer only supports hard cuts" }
            check(manager.createDirectoryAtPath(exportDirectory, true, null, null)) { "Cannot create export directory" }
            val id = NSUUID().UUIDString
            val partial = "$exportDirectory/$id.part.mp4"
            val output = "$exportDirectory/$id.mp4"
            val composition = AVMutableComposition()
            val videoTrack = checkNotNull(composition.addMutableTrackWithMediaType(AVMediaTypeVideo, 1))
            var audioTrack: AVMutableCompositionTrack? = null
            var cursor = CMTimeMake(0, 1000)
            var hasAudio: Boolean? = null
            onProgress(ExportProgress(0, request.durationMillis))
            for (clip in request.clips) {
                val asset = AVURLAsset(NSURL.fileURLWithPath("$mediaDirectory/${clip.videoId}.media"), null)
                val sourceVideo = checkNotNull(asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack)
                val audio = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack
                if (hasAudio == null) hasAudio = audio != null
                check(hasAudio == (audio != null)) { "All clips must either contain audio or be silent in the basic renderer" }
                val range = CMTimeRangeMake(CMTimeMake(clip.inMillis, 1000), CMTimeMake(clip.durationMillis, 1000))
                check(videoTrack.insertTimeRange(range, sourceVideo, cursor, null)) { "Cannot insert iOS video clip" }
                videoTrack.preferredTransform = sourceVideo.preferredTransform
                if (audio != null) {
                    if (audioTrack == null) audioTrack = checkNotNull(composition.addMutableTrackWithMediaType(AVMediaTypeAudio, 2))
                    check(audioTrack!!.insertTimeRange(range, audio, cursor, null)) { "Cannot insert iOS audio clip" }
                }
                cursor = CMTimeAdd(cursor, CMTimeMake(clip.durationMillis, 1000))
            }
            val session = checkNotNull(AVAssetExportSession(composition, AVAssetExportPresetHighestQuality))
            session.outputURL = NSURL.fileURLWithPath(partial)
            session.outputFileType = AVFileTypeMPEG4
            active = session
            activePartial = partial
            try {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation {
                        session.cancelExport()
                        manager.removeItemAtPath(partial, null)
                    }
                    session.exportAsynchronouslyWithCompletionHandler {
                        active = null
                        activePartial = null
                        when (session.status) {
                            AVAssetExportSessionStatusCompleted -> {
                                if (manager.moveItemAtPath(partial, output, null) && continuation.isActive) {
                                    val bytes = (manager.attributesOfItemAtPath(output, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
                                    onProgress(ExportProgress(request.durationMillis, request.durationMillis))
                                    continuation.resume(ExportResult(MediaHandle(NSURL.fileURLWithPath(output).absoluteString!!, "video/mp4"), request.durationMillis, bytes))
                                } else if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Cannot commit iOS export"))
                            }
                            AVAssetExportSessionStatusCancelled -> if (continuation.isActive) continuation.resumeWithException(ExportCancelledException())
                            else -> if (continuation.isActive) continuation.resumeWithException(session.error ?: IllegalStateException("iOS export failed"))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                session.cancelExport()
                manager.removeItemAtPath(partial, null)
                throw cancelled
            } finally {
                manager.removeItemAtPath(partial, null)
            }
        }

    override fun cancel() {
        active?.cancelExport()
        activePartial?.let { manager.removeItemAtPath(it, null) }
    }
}
