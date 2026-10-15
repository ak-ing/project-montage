// SPDX-License-Identifier: Apache-2.0
package montage.core

import kotlinx.serialization.Serializable

/** Client-owned draft. Provider-specific generation constraints belong to the backend adapter. */
@Serializable
data class Scene(val id: String, val description: String, val durationSeconds: Int) {
    init {
        require(id.isNotBlank()) { "Scene id must not be blank" }
        require(durationSeconds > 0) { "Scene duration must be positive" }
    }
}

@Serializable
data class ProjectDraft(
    val title: String,
    val scenes: List<Scene> = emptyList(),
    val transition: String = "fade",
) {
    init { require(scenes.map { it.id }.distinct().size == scenes.size) { "Scene ids must be unique" } }
    val visibleDurationSeconds: Int get() = scenes.sumOf { it.durationSeconds }
    // Upstream adds one second to each non-final clip, then consumes that buffer in xfade.
    val transitionBufferSeconds: Int get() = if (transition == "none") 0 else (scenes.size - 1).coerceAtLeast(0)
    val generatedDurationSeconds: Int get() = visibleDurationSeconds + transitionBufferSeconds
}

/** A local-only preview, never a successful remote generation result. */
fun sampleProject() = ProjectDraft(
    title = "My first Montage",
    scenes = listOf(Scene("opening", "Opening scene", 6), Scene("ending", "Closing scene", 4)),
)

fun ProjectDraft.appendScene(description: String): ProjectDraft {
    require(description.isNotBlank()) { "Scene description must not be blank" }
    var number = scenes.size + 1
    while (scenes.any { it.id == "scene-$number" }) number++
    return copy(scenes = scenes + Scene("scene-$number", description.trim(), 4))
}
