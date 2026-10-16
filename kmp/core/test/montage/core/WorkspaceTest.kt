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
        for (document in listOf("broken JSON", "", """{"schemaVersion":2,"draft":{"title":"Future"}}""")) {
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
}
