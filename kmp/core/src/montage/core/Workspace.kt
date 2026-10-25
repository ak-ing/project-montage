// SPDX-License-Identifier: Apache-2.0
package montage.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

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
    val schemaVersion: Int = 3,
    val videos: List<VideoAsset> = emptyList(),
) {
    init {
        require(schemaVersion == 3) { "Unsupported workspace version" }
        require(jobs.map { it.id }.distinct().size == jobs.size) { "Job ids must be unique" }
        require(videos.map { it.id }.distinct().size == videos.size) { "Video ids must be unique" }
        require(draft.scenes.all { scene -> scene.clip == null || videos.any { it.id == scene.clip.videoId } }) {
            "Scene references a video outside the workspace"
        }
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
        var migrated = false
        val saved = if (document == null) WorkspaceSnapshot(sampleProject()) else {
            val fields = json.parseToJsonElement(document).jsonObject
            val version = fields["schemaVersion"]?.jsonPrimitive?.int ?: 1
            require(version in 1..3) { "Unsupported workspace version" }
            migrated = version < 3
            json.decodeFromJsonElement<WorkspaceSnapshot>(JsonObject(fields + ("schemaVersion" to JsonPrimitive(3))))
        }
        val recovered = saved.recover()
        if (migrated || saved != recovered) storage.writeAtomically(json.encodeToString(recovered))
        loaded = true
        return recovered
    }

    fun save(snapshot: WorkspaceSnapshot) {
        check(loaded) { "Load the workspace successfully before saving" }
        storage.writeAtomically(json.encodeToString(snapshot))
    }
}
