// SPDX-License-Identifier: Apache-2.0
package montage.core

import kotlin.test.*

class SceneClipTest {
    private val video = VideoAsset("video-1", "Local.mp4", 100)
    private val metadata = VideoMetadata(8000, 160, 90, 0, true)
    private fun workspace() = WorkspaceSnapshot(sampleProject(), videos = listOf(video),
        jobs = listOf(JobCheckpoint("job", MontageTool.GenerateVideos, JobStatus.Interrupted, "polling", "op-1")))
    private class Storage(var document: String? = null) : WorkspaceStorage {
        var failWrite = false
        var writes = 0
        override fun read() = document
        override fun writeAtomically(document: String) {
            check(!failWrite)
            this.document = document; writes++
        }
    }

    @Test fun rangesAreNonEmptyAndBoundedByActualDuration() {
        for ((start, end) in listOf(-1L to 1L, 0L to 0L, 10L to 9L)) {
            assertFailsWith<IllegalArgumentException> { SceneClip(video.id, start, end) }
        }
        assertFailsWith<IllegalArgumentException> { SceneClip("../source", 0, 1) }
        val full = SceneClip(video.id, 0, 8000)
        full.validateAgainst(metadata)
        assertEquals(8000, full.durationMillis)
        SceneClip(video.id, 7999, 8000).validateAgainst(metadata)
        assertFailsWith<IllegalArgumentException> { full.validateAgainst(metadata.copy(durationMillis = 7999)) }
        assertFailsWith<IllegalArgumentException> { SceneClip(video.id, 0, Long.MAX_VALUE).validateAgainst(metadata) }
    }

    @Test fun applyingClipRequiresExistingSceneAndManagedVideo() {
        val source = workspace()
        val clip = SceneClip(video.id, 1250, 6500)
        val edited = source.withSceneClip("opening", clip, metadata)
        assertEquals(clip, edited.draft.scenes.first().clip)
        assertNull(source.draft.scenes.first().clip)
        assertEquals(9250, edited.draft.editedDurationMillis)
        assertEquals(10, edited.draft.visibleDurationSeconds) // Generation targets are not local edit lengths.
        assertFailsWith<IllegalArgumentException> { source.withSceneClip("missing", clip, metadata) }
        assertFailsWith<IllegalArgumentException> { source.withSceneClip("opening", clip.copy(videoId = "missing"), metadata) }
        assertFailsWith<IllegalArgumentException> { source.withSceneClip("opening", clip.copy(outMillis = 8001), metadata) }
        assertFailsWith<IllegalArgumentException> { source.clearSceneClip("missing") }
    }

    @Test fun reorderingKeepsIdentityClipTargetsAndCheckpoints() {
        val source = workspace().withSceneClip("opening", SceneClip(video.id, 0, 4000), metadata)
        val moved = source.moveScene("opening", 1)
        assertEquals(listOf("ending", "opening"), moved.draft.scenes.map { it.id })
        assertEquals(source.draft.scenes.first(), moved.draft.scenes.last())
        assertEquals(source, moved.moveScene("opening", 0))
        assertEquals(moved, moved.moveScene("opening", 1))
        assertEquals(source.jobs, moved.jobs)
        assertFailsWith<IllegalArgumentException> { source.moveScene("missing", 0) }
        assertFailsWith<IllegalArgumentException> { source.moveScene("opening", -1) }
        assertFailsWith<IllegalArgumentException> { source.moveScene("opening", 2) }
    }

    @Test fun referencedVideoCannotBeRemovedUntilAllScenesDetach() {
        val clip = SceneClip(video.id, 0, 1000)
        val source = workspace().withSceneClip("opening", clip, metadata).withSceneClip("ending", clip, metadata)
        assertFailsWith<IllegalArgumentException> { source.withoutVideo(video.id) }
        assertFailsWith<IllegalArgumentException> { source.clearSceneClip("opening").withoutVideo(video.id) }
        assertFailsWith<IllegalArgumentException> { source.copy(videos = emptyList()) }
        val detached = source.clearSceneClip("opening").clearSceneClip("ending").withoutVideo(video.id)
        assertTrue(detached.videos.isEmpty())
        assertTrue(detached.draft.scenes.all { it.clip == null })
        assertEquals(source.jobs, detached.jobs)
    }

    @Test fun clipPrecisionAndSceneOrderSurviveNewRepositoryAndFailedSave() {
        val storage = Storage()
        val repository = WorkspaceRepository(storage)
        repository.load()
        val edited = workspace().withSceneClip("opening", SceneClip(video.id, 1234, 6789), metadata).moveScene("opening", 1)
        repository.save(edited)
        assertEquals(edited, WorkspaceRepository(storage).load())
        val previous = storage.document
        storage.failWrite = true
        assertFails { repository.save(edited.clearSceneClip("opening")) }
        assertEquals(previous, storage.document)
        storage.failWrite = false
        assertEquals(edited, WorkspaceRepository(storage).load())
        repository.save(edited.clearSceneClip("opening"))
        assertNull(WorkspaceRepository(storage).load().draft.scenes.last().clip)
    }

    @Test fun versionTwoMigrationPreservesMediaDraftAndRunningOperationAtomically() {
        val old = """{"schemaVersion":2,"draft":{"title":"旧项目","scenes":[{"id":"old","description":"中文","durationSeconds":7}]},"videos":[{"id":"video-1","name":"Local.mp4","byteSize":100}],"jobs":[{"id":"job","tool":"GenerateVideos","status":"Running","stage":"polling","providerOperationId":"op-1"}]}"""
        val storage = Storage(old).apply { failWrite = true }
        val repository = WorkspaceRepository(storage)
        assertFails { repository.load() }
        assertEquals(old, storage.document)
        assertFailsWith<IllegalStateException> { repository.save(workspace()) }
        storage.failWrite = false
        val restored = repository.load()
        assertEquals(3, restored.schemaVersion)
        assertEquals("旧项目", restored.draft.title)
        assertEquals(Scene("old", "中文", 7), restored.draft.scenes.single())
        assertEquals(listOf(video), restored.videos)
        assertEquals(JobStatus.Interrupted, restored.jobs.single().status)
        assertEquals("op-1", restored.jobs.single().providerOperationId)
        assertEquals(1, storage.writes)
        assertEquals(restored, WorkspaceRepository(storage).load())
        assertEquals(1, storage.writes)
    }

    @Test fun invalidSavedClipOrDanglingReferenceNeverOverwritesDocument() {
        for (clip in listOf("""{"videoId":"missing","inMillis":0,"outMillis":1}""",
            """{"videoId":"video-1","inMillis":10,"outMillis":10}""")) {
            val document = """{"schemaVersion":3,"draft":{"title":"Keep","scenes":[{"id":"a","description":"A","durationSeconds":4,"clip":$clip}]},"videos":[{"id":"video-1","name":"Local.mp4","byteSize":100}]}"""
            val storage = Storage(document)
            val repository = WorkspaceRepository(storage)
            assertFails { repository.load() }
            assertFailsWith<IllegalStateException> { repository.save(workspace()) }
            assertEquals(document, storage.document)
            assertEquals(0, storage.writes)
        }
    }
}
