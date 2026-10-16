// SPDX-License-Identifier: Apache-2.0
package montage.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import montage.core.WorkspaceRepository
import montage.core.WorkspaceSnapshot
import montage.core.WorkspaceStorage
import montage.core.VideoAsset
import montage.core.VideoLibrary
import montage.core.appendScene
import montage.core.sampleProject

@Composable
fun MontageApp(platformName: String, storage: WorkspaceStorage, videos: VideoLibrary? = null) {
    val repository = remember(storage) { WorkspaceRepository(storage) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var workspace by remember(repository) { mutableStateOf(WorkspaceSnapshot(sampleProject())) }
    var saved by remember(repository) { mutableStateOf<WorkspaceSnapshot?>(null) }
    var busy by remember(repository) { mutableStateOf(true) }
    var loaded by remember(repository) { mutableStateOf(false) }
    var failure by remember(repository) { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf("") }
    var showVideos by remember { mutableStateOf(false) }
    var importNotice by remember { mutableStateOf<String?>(null) }
    var availability by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    suspend fun cleanVideos(retained: List<VideoAsset>): Boolean = try {
        withContext(Dispatchers.Default) { videos?.removeUnreferenced(retained) }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    suspend fun reload(discardImports: Boolean = false) {
        busy = true
        failure = null
        try {
            workspace = withContext(Dispatchers.Default) { repository.load() }
            saved = workspace
            loaded = true
            description = ""
            importNotice = null
            if (discardImports && !cleanVideos(workspace.videos)) failure = "Draft loaded; some unused video copies could not be removed."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loaded = false
            failure = "Cannot open saved draft. Your saved file has been kept. Retry loading."
        } finally {
            busy = false
        }
    }

    LaunchedEffect(repository) { reload() }
    LaunchedEffect(videos, workspace.videos, busy) {
        if (!busy) availability = withContext(Dispatchers.Default) {
            workspace.videos.associate { it.id to runCatching { videos?.isAvailable(it) == true }.getOrDefault(false) }
        }
    }
    val draft = workspace.draft
    val editable = loaded && !busy
    val dirty = workspace != saved
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Project Montage", style = MaterialTheme.typography.headlineMedium)
                Text("$platformName · Device workspace")
                Text("Save changes before closing. Generation and rendering are not connected.")
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { workspace = workspace.copy(draft = draft.copy(title = it)) },
                    enabled = editable,
                    label = { Text("Project title") },
                    modifier = Modifier.fillMaxWidth().testTag("project-title"),
                )
                Text("${draft.scenes.size} scenes · ${draft.visibleDurationSeconds}s visible · ${draft.transitionBufferSeconds}s transition buffer", modifier = Modifier.testTag("draft-summary"))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    enabled = editable,
                    label = { Text("New scene description") },
                    modifier = Modifier.fillMaxWidth().testTag("scene-description"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.testTag("add-scene"), enabled = editable && description.isNotBlank(), onClick = {
                        workspace = workspace.copy(draft = draft.appendScene(description))
                        description = ""
                        focus.clearFocus()
                    }) { Text("Add scene") }
                    TextButton(modifier = Modifier.testTag("reset-sample"), enabled = editable, onClick = {
                        workspace = workspace.copy(draft = sampleProject())
                        description = ""
                        focus.clearFocus()
                    }) { Text("Reset sample") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.testTag("save-draft"), enabled = editable && dirty, onClick = {
                        busy = true
                        focus.clearFocus()
                        failure = null
                        val snapshot = workspace
                        scope.launch {
                            try {
                                withContext(Dispatchers.Default) { repository.save(snapshot) }
                                saved = snapshot
                                importNotice = null
                                if (!cleanVideos(snapshot.videos)) failure = "Draft saved; some unused video copies could not be removed."
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                failure = "Could not save. Changes remain here; retry Save draft."
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Save draft") }
                    TextButton(modifier = Modifier.testTag("reload-draft"), enabled = !busy, onClick = {
                        busy = true
                        scope.launch { reload(discardImports = true) }
                    }) { Text(if (!loaded) "Retry loading" else if (dirty) "Discard edits & reload" else "Reload saved") }
                }
                Text(failure ?: when {
                    busy -> "Working…"
                    dirty -> "Unsaved changes"
                    else -> "No unsaved changes"
                }, modifier = Modifier.testTag("storage-status"))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(modifier = Modifier.testTag("import-video"), enabled = editable && videos != null, onClick = {
                        busy = true
                        failure = null
                        importNotice = null
                        focus.clearFocus()
                        scope.launch {
                            try {
                                val asset = videos?.pickAndImport()
                                if (asset == null) importNotice = "Import cancelled" else {
                                    workspace = workspace.copy(videos = workspace.videos + asset)
                                    showVideos = true
                                    importNotice = "Video copied · save draft to keep it"
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                failure = "Could not import video. Check file access and available storage, then retry."
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Import video") }
                    TextButton(modifier = Modifier.testTag("scene-tab"), onClick = { showVideos = false }) { Text("Scenes ${draft.scenes.size}") }
                    TextButton(modifier = Modifier.testTag("video-tab"), onClick = { showVideos = true }) { Text("Videos ${workspace.videos.size}") }
                }
                importNotice?.let { Text(it, modifier = Modifier.testTag("import-status")) }
                if (workspace.jobs.isNotEmpty()) {
                    Text("${workspace.jobs.size} saved task checkpoints · no tasks run automatically")
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showVideos) {
                        if (workspace.videos.isEmpty()) item { Text("No videos imported") }
                        items(workspace.videos, key = { "video-${it.id}" }) { asset ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(asset.name, maxLines = 2)
                                        Text("${asset.byteSize} bytes · not yet analyzed", style = MaterialTheme.typography.bodySmall)
                                        Text(when (availability[asset.id]) {
                                            true -> "Available on this device"
                                            false -> "Video file unavailable · remove and import again"
                                            null -> "Checking video file…"
                                        })
                                    }
                                    TextButton(modifier = Modifier.testTag("remove-video-${asset.id}"), enabled = editable, onClick = {
                                        workspace = workspace.copy(videos = workspace.videos.filterNot { it.id == asset.id })
                                        importNotice = "Save draft to remove the video copy"
                                    }) { Text("Remove") }
                                }
                            }
                        }
                    } else items(draft.scenes, key = { "scene-${it.id}" }) { scene ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(scene.description)
                                Text("${scene.durationSeconds} seconds", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
