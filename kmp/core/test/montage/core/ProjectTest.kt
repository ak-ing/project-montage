// SPDX-License-Identifier: Apache-2.0
package montage.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectTest {
    @Test fun transitionConsumesExtraFootageInsteadOfVisibleDuration() {
        val project = sampleProject()
        assertEquals(10, project.visibleDurationSeconds)
        assertEquals(11, project.generatedDurationSeconds)
        assertEquals(10, project.copy(transition = "none").generatedDurationSeconds)
    }
    @Test fun noBoundaryNeedsNoBuffer() {
        assertEquals(0, ProjectDraft("Empty").generatedDurationSeconds)
        assertEquals(6, ProjectDraft("One", listOf(Scene("a", "A", 6))).generatedDurationSeconds)
    }
    @Test fun rejectsInvalidSceneDurationsAndDuplicateIdentity() {
        assertFailsWith<IllegalArgumentException> { Scene("a", "A", 0) }
        assertFailsWith<IllegalArgumentException> { Scene("a", "A", -1) }
        assertFailsWith<IllegalArgumentException> { Scene("", "A", 4) }
        assertFailsWith<IllegalArgumentException> {
            ProjectDraft("Duplicate", listOf(Scene("a", "A", 4), Scene("a", "B", 6)))
        }
    }
    @Test fun appendPreservesSourceAndAvoidsIdentityCollisions() {
        val original = ProjectDraft("Draft", listOf(Scene("scene-2", "Original", 8)))
        val updated = original.appendScene("  New  ")
        assertEquals(1, original.scenes.size)
        assertEquals("scene-3", updated.scenes.last().id)
        assertEquals("New", updated.scenes.last().description)
        assertFailsWith<IllegalArgumentException> { original.appendScene("  ") }
    }
    @Test fun draftSurvivesJsonRoundTrip() {
        val draft = sampleProject().appendScene("中文 / العربية")
        assertEquals(draft, Json.decodeFromString<ProjectDraft>(Json.encodeToString(draft)))
    }
}
