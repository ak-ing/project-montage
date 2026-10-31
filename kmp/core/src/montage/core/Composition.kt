// SPDX-License-Identifier: Apache-2.0
package montage.core

import kotlinx.serialization.Serializable

/** A frozen, device-local render input. It contains IDs and edit decisions only. */
@Serializable
data class CompositionClip(
    val videoId: String,
    val inMillis: Long,
    val outMillis: Long,
) {
    init {
        require(videoId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid video identifier" }
        require(inMillis >= 0 && outMillis > inMillis) { "Composition clip must be non-empty" }
    }

    val durationMillis: Long get() = outMillis - inMillis
}

/**
 * The first device render slice supports an explicit no-transition sequence.
 * A non-none transition must be implemented by a later renderer instead of
 * silently turning a fade decision into a hard cut.
 */
@Serializable
data class CompositionRequest(
    val clips: List<CompositionClip>,
    val transition: String = "none",
) {
    init {
        require(clips.isNotEmpty()) { "Composition requires at least one clip" }
        require(transition.isNotBlank()) { "Transition must not be blank" }
    }

    val durationMillis: Long get() = clips.sumOf { it.durationMillis }
}

data class ExportProgress(
    val completedMillis: Long,
    val totalMillis: Long,
) {
    init {
        require(completedMillis >= 0 && totalMillis > 0) { "Invalid export progress" }
    }

    val fraction: Float get() = (completedMillis.toDouble() / totalMillis).coerceIn(0.0, 1.0).toFloat()
}

data class ExportResult(
    val output: MediaHandle,
    val durationMillis: Long,
    val byteSize: Long,
)

class ExportCancelledException : IllegalStateException("Video export cancelled")

/** Device adapters own the output directory, cancellation and partial-file cleanup. */
interface DeviceCompositionExporter {
    suspend fun export(request: CompositionRequest, onProgress: (ExportProgress) -> Unit = {}): ExportResult
    fun cancel()
}

fun WorkspaceSnapshot.toCompositionRequest(): CompositionRequest {
    require(draft.transition == "none") {
        "Transition '${draft.transition}' is not supported by the basic device renderer yet"
    }
    val clips = draft.scenes.map { scene ->
        val clip = requireNotNull(scene.clip) { "Every scene needs an applied video clip before export" }
        require(videos.any { it.id == clip.videoId }) { "Composition references a missing video" }
        CompositionClip(clip.videoId, clip.inMillis, clip.outMillis)
    }
    return CompositionRequest(clips, draft.transition)
}
