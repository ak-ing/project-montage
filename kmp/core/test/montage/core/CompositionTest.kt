// SPDX-License-Identifier: Apache-2.0
package montage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompositionTest {
    private val first = VideoAsset("first", "first.mp4", 10)
    private val second = VideoAsset("second", "second.mp4", 20)

    @Test
    fun freezesSceneOrderAndExclusiveRanges() {
        val draft = ProjectDraft(
            title = "render",
            transition = "none",
            scenes = listOf(
                Scene("a", "A", 4, SceneClip("second", 1_000, 2_500)),
                Scene("b", "B", 4, SceneClip("first", 250, 1_750)),
            ),
        )
        val request = WorkspaceSnapshot(draft, videos = listOf(first, second)).toCompositionRequest()
        assertEquals(listOf("second", "first"), request.clips.map { it.videoId })
        assertEquals(listOf(1_500L, 1_500L), request.clips.map { it.durationMillis })
        assertEquals(3_000L, request.durationMillis)
    }

    @Test
    fun rejectsFadeAndMissingSceneClipBeforeTheAdapterRuns() {
        val fade = WorkspaceSnapshot(ProjectDraft("fade", scenes = listOf(Scene("a", "A", 1))), videos = emptyList())
        assertFailsWith<IllegalArgumentException> { fade.toCompositionRequest() }
        val missingClip = WorkspaceSnapshot(ProjectDraft("clip", transition = "none", scenes = listOf(Scene("a", "A", 1))), videos = emptyList())
        assertFailsWith<IllegalArgumentException> { missingClip.toCompositionRequest() }
    }
}
