// SPDX-License-Identifier: Apache-2.0
package montage.core

import kotlinx.serialization.Serializable

/** An edit decision referencing an app-owned asset. The out point is exclusive. */
@Serializable
data class SceneClip(val videoId: String, val inMillis: Long, val outMillis: Long) {
    init {
        require(videoId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid video identifier" }
        require(inMillis >= 0 && outMillis > inMillis) { "Clip must have a non-empty positive range" }
    }
    val durationMillis: Long get() = outMillis - inMillis
    fun validateAgainst(metadata: VideoMetadata) {
        require(outMillis <= metadata.durationMillis) { "Clip exceeds video duration" }
    }
}

fun WorkspaceSnapshot.withSceneClip(sceneId: String, clip: SceneClip, metadata: VideoMetadata): WorkspaceSnapshot {
    require(videos.any { it.id == clip.videoId }) { "Video is not in this workspace" }
    clip.validateAgainst(metadata)
    return updateScene(sceneId) { it.copy(clip = clip) }
}

fun WorkspaceSnapshot.clearSceneClip(sceneId: String): WorkspaceSnapshot = updateScene(sceneId) { it.copy(clip = null) }

fun WorkspaceSnapshot.moveScene(sceneId: String, destination: Int): WorkspaceSnapshot {
    val index = draft.scenes.indexOfFirst { it.id == sceneId }
    require(index >= 0 && destination in draft.scenes.indices) { "Invalid scene move" }
    val scenes = draft.scenes.toMutableList()
    scenes.add(destination, scenes.removeAt(index))
    return copy(draft = draft.copy(scenes = scenes))
}

/** Referenced media must be explicitly detached first, even if its file is missing. */
fun WorkspaceSnapshot.withoutVideo(videoId: String): WorkspaceSnapshot {
    require(draft.scenes.none { it.clip?.videoId == videoId }) { "Video is used by a scene" }
    return copy(videos = videos.filterNot { it.id == videoId })
}

private fun WorkspaceSnapshot.updateScene(sceneId: String, change: (Scene) -> Scene): WorkspaceSnapshot {
    require(draft.scenes.any { it.id == sceneId }) { "Scene does not exist" }
    return copy(draft = draft.copy(scenes = draft.scenes.map { if (it.id == sceneId) change(it) else it }))
}
