// SPDX-License-Identifier: Apache-2.0
package montage.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class JobStatus { Queued, Running, Interrupted, Succeeded, Failed, Cancelled }

/** Metadata only. Never store credentials or signed media URLs in a checkpoint. */
@Serializable
data class JobCheckpoint(
    val id: String,
    val tool: MontageTool,
    val status: JobStatus = JobStatus.Queued,
    val stage: String = "pending",
    val providerOperationId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Job id must not be blank" }
        require(stage.isNotBlank()) { "Job stage must not be blank" }
        require(providerOperationId == null || providerOperationId.isNotBlank()) { "Operation id must not be blank" }
    }

    // Recovery records interruption; it never resubmits a possibly billable request.
    fun recover(): JobCheckpoint = if (status == JobStatus.Running) copy(status = JobStatus.Interrupted) else this
}

/** One local workspace for this milestone; media bytes live outside this document. */
@Serializable
data class WorkspaceSnapshot(
    val draft: ProjectDraft,
    val jobs: List<JobCheckpoint> = emptyList(),
    val schemaVersion: Int = 1,
) {
    init {
        require(schemaVersion == 1) { "Unsupported workspace version" }
        require(jobs.map { it.id }.distinct().size == jobs.size) { "Job ids must be unique" }
    }

    fun recover(): WorkspaceSnapshot = copy(jobs = jobs.map { it.recover() })
}

/** Platform implementations must replace the whole document or retain the previous one on failure. */
interface WorkspaceStorage {
    fun read(): String?
    fun writeAtomically(document: String)
}

/** Blocking storage operations: hosts call this off the UI thread and serialize access. */
class WorkspaceRepository(private val storage: WorkspaceStorage) {
    private val json = Json { encodeDefaults = true }
    private var loaded = false

    fun load(): WorkspaceSnapshot {
        loaded = false
        val document = storage.read()
        val saved = document?.let { json.decodeFromString<WorkspaceSnapshot>(it) }
            ?: WorkspaceSnapshot(sampleProject())
        val recovered = saved.recover()
        if (saved != recovered) storage.writeAtomically(json.encodeToString(recovered))
        loaded = true
        return recovered
    }

    fun save(snapshot: WorkspaceSnapshot) {
        check(loaded) { "Load the workspace successfully before saving" }
        storage.writeAtomically(json.encodeToString(snapshot))
    }
}
