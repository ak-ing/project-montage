// SPDX-License-Identifier: Apache-2.0
package montage.core

import kotlin.test.*

class WorkspaceTest {
    private class Storage(var document: String? = null) : WorkspaceStorage {
        var failWrite = false
        var writes = 0
        override fun read() = document
        override fun writeAtomically(document: String) {
            if (failWrite) error("Disk unavailable")
            this.document = document
            writes++
        }
    }

    @Test fun newWorkspaceDoesNotWriteUntilSaved() {
        val storage = Storage()
        assertEquals(sampleProject(), WorkspaceRepository(storage).load().draft)
        assertNull(storage.document)
    }

    @Test fun newRepositoryRestoresDraftAndCheckpoint() {
        val storage = Storage()
        val repository = WorkspaceRepository(storage)
        val edited = repository.load().copy(
            draft = sampleProject().copy(title = "中文 / العربية").appendScene("Saved scene"),
            jobs = listOf(JobCheckpoint("video-1", MontageTool.GenerateVideos, JobStatus.Interrupted,
                "polling", "provider-operation-123")),
        )
        repository.save(edited)
        assertEquals(edited, WorkspaceRepository(storage).load())
    }

    @Test fun runningJobsBecomeInterruptedWithoutLosingProviderOperation() {
        val storage = Storage()
        val repository = WorkspaceRepository(storage)
        val snapshot = repository.load().copy(jobs = JobStatus.entries.map {
            JobCheckpoint(it.name, MontageTool.GenerateVideos, it, "polling", "operation-${it.name}")
        })
        repository.save(snapshot)
        val restored = WorkspaceRepository(storage).load()
        assertEquals(snapshot.jobs.map { if (it.status == JobStatus.Running) it.copy(status = JobStatus.Interrupted) else it }, restored.jobs)
        assertEquals(2, storage.writes) // Explicit save, then durable interruption checkpoint.
        assertEquals(restored, WorkspaceRepository(storage).load())
        assertEquals(2, storage.writes)
    }

    @Test fun unreadableOrFutureDocumentsAreNeverReplacedWithSample() {
        for (document in listOf("broken JSON", "", """{"schemaVersion":4,"draft":{"title":"Future"}}""")) {
            val storage = Storage(document)
            val repository = WorkspaceRepository(storage)
            assertFails { repository.load() }
            assertFailsWith<IllegalStateException> { repository.save(WorkspaceSnapshot(sampleProject())) }
            assertEquals(document, storage.document)
            assertEquals(0, storage.writes)
        }
    }

    @Test fun failedSaveRetainsPreviousDocumentAndCanBeRetried() {
        val storage = Storage()
        val repository = WorkspaceRepository(storage)
        val original = repository.load()
        repository.save(original)
        val edited = original.copy(draft = original.draft.copy(title = "Retry me"))
        storage.failWrite = true
        assertFails { repository.save(edited) }
        assertEquals(original, WorkspaceRepository(storage).load())
        storage.failWrite = false
        repository.save(edited)
        assertEquals(edited, WorkspaceRepository(storage).load())
    }

    @Test fun failedRecoveryDoesNotAllowSavingOverUnreadState() {
        val storage = Storage()
        val repository = WorkspaceRepository(storage)
        val snapshot = repository.load().copy(jobs = listOf(JobCheckpoint("job", MontageTool.GenerateImages, JobStatus.Running)))
        repository.save(snapshot)
        val document = storage.document
        storage.failWrite = true
        assertFails { repository.load() }
        assertFailsWith<IllegalStateException> { repository.save(WorkspaceSnapshot(sampleProject())) }
        assertEquals(document, storage.document)
    }

    @Test fun rejectsAmbiguousJobIdentityAndInvalidCheckpointFields() {
        val job = JobCheckpoint("job", MontageTool.GenerateImages)
        assertFailsWith<IllegalArgumentException> { WorkspaceSnapshot(sampleProject(), listOf(job, job)) }
        assertFailsWith<IllegalArgumentException> { job.copy(id = " ") }
        assertFailsWith<IllegalArgumentException> { job.copy(stage = "") }
        assertFailsWith<IllegalArgumentException> { job.copy(providerOperationId = " ") }
    }

    @Test fun migratesVersionOneWithoutLosingDraftOrProviderCheckpoint() {
        val storage = Storage("""{"schemaVersion":1,"draft":{"title":"旧项目"},"jobs":[{"id":"video-1","tool":"GenerateVideos","status":"Interrupted","stage":"polling","providerOperationId":"op-123"}]}""")
        val restored = WorkspaceRepository(storage).load()
        assertEquals(3, restored.schemaVersion)
        assertEquals("旧项目", restored.draft.title)
        assertEquals("op-123", restored.jobs.single().providerOperationId)
        assertTrue(restored.videos.isEmpty())
        assertEquals(1, storage.writes)
        assertEquals(restored, WorkspaceRepository(storage).load())
        assertEquals(1, storage.writes)
    }

    @Test fun migrationWriteFailureKeepsOldDocumentAndDisablesSave() {
        val old = """{"schemaVersion":1,"draft":{"title":"Old"}}"""
        val storage = Storage(old).apply { failWrite = true }
        val repository = WorkspaceRepository(storage)
        assertFails { repository.load() }
        assertEquals(old, storage.document)
        assertFailsWith<IllegalStateException> { repository.save(WorkspaceSnapshot(sampleProject())) }
        storage.failWrite = false
        assertEquals("Old", repository.load().draft.title)
    }

    @Test fun savesOpaqueVideoReferencesAndRejectsDuplicateIdentity() {
        val storage = Storage()
        val repository = WorkspaceRepository(storage)
        val asset = VideoAsset("video-123", "中文 movie.mp4", 1234)
        val snapshot = repository.load().copy(videos = listOf(asset))
        repository.save(snapshot)
        assertEquals(snapshot, WorkspaceRepository(storage).load())
        assertFailsWith<IllegalArgumentException> { snapshot.copy(videos = listOf(asset, asset)) }
    }

    @Test fun rejectsUnsafeHandlesAndEmptyVideoMetadata() {
        for (id in listOf("", "../escape", "/file", "a\\b", "x".repeat(81))) {
            assertFailsWith<IllegalArgumentException> { VideoAsset(id, "Video.mp4", 1) }
        }
        assertFailsWith<IllegalArgumentException> { VideoAsset("video", " ", 1) }
        assertFailsWith<IllegalArgumentException> { VideoAsset("video", "Movie.mp4", 0) }
    }
}
