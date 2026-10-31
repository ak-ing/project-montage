// SPDX-License-Identifier: Apache-2.0
package montage.android

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import montage.core.CompositionRequest
import montage.core.DeviceCompositionExporter
import montage.core.ExportCancelledException
import montage.core.ExportProgress
import montage.core.ExportResult as MontageExportResult
import montage.core.MediaHandle
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Android Media3 first slice: trim each item and concatenate hard cuts on-device. */
@OptIn(UnstableApi::class)
class AndroidCompositionExporter(
    private val activity: ComponentActivity,
    private val videos: AndroidVideoLibrary,
) : DeviceCompositionExporter {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active: Transformer? = null
    @Volatile private var activePartial: File? = null
    @Volatile private var cancelRequested = false

    override suspend fun export(request: CompositionRequest, onProgress: (ExportProgress) -> Unit): MontageExportResult =
        withContext(Dispatchers.Main.immediate) {
            check(request.transition == "none") { "Android basic renderer only supports hard cuts" }
            val exportDirectory = File(activity.filesDir, "exports")
            check(exportDirectory.isDirectory || exportDirectory.mkdirs()) { "Cannot create export directory" }
            val id = UUID.randomUUID().toString()
            val partial = File(exportDirectory, "$id.part.mp4")
            val output = File(exportDirectory, "$id.mp4")
            cancelRequested = false
            val items = request.clips.map { clip ->
                val media = MediaItem.Builder()
                    .setUri(videos.managedUri(clip.videoId))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.inMillis)
                            .setEndPositionMs(clip.outMillis)
                            .build(),
                    )
                    .build()
                EditedMediaItem.Builder(media).build()
            }
            val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(items)
            val composition = Composition.Builder(sequence).build()
            onProgress(ExportProgress(0, request.durationMillis))
            try {
                val result = suspendCancellableCoroutine<MontageExportResult> { continuation ->
                    val transformer = Transformer.Builder(activity)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (partial.renameTo(output)) {
                                    onProgress(ExportProgress(request.durationMillis, request.durationMillis))
                                    if (continuation.isActive) continuation.resume(
                                        MontageExportResult(MediaHandle(output.toURI().toString(), "video/mp4"), request.durationMillis, output.length())
                                    )
                                } else if (continuation.isActive) {
                                    continuation.resumeWithException(IllegalStateException("Cannot commit Android export"))
                                }
                                active = null
                                activePartial = null
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                partial.delete()
                                active = null
                                activePartial = null
                                if (continuation.isActive) {
                                    if (cancelRequested) {
                                        continuation.resumeWithException(ExportCancelledException())
                                    } else continuation.resumeWithException(exportException)
                                }
                            }
                        })
                        .build()
                    active = transformer
                    activePartial = partial
                    continuation.invokeOnCancellation {
                        main.post {
                            transformer.cancel()
                            partial.delete()
                            active = null
                            activePartial = null
                        }
                    }
                    transformer.start(composition, partial.absolutePath)
                }
                result
            } catch (cancelled: CancellationException) {
                active?.cancel()
                partial.delete()
                throw cancelled
            } catch (error: Exception) {
                partial.delete()
                throw error
            }
        }

    override fun cancel() {
        main.post {
            cancelRequested = true
            active?.cancel()
            activePartial?.delete()
        }
    }
}
