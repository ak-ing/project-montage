// SPDX-License-Identifier: Apache-2.0
package montage.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import montage.core.appendScene
import montage.core.sampleProject

@Composable
fun MontageApp(platformName: String, storage: WorkspaceStorage) {
    val repository = remember(storage) { WorkspaceRepository(storage) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var workspace by remember(repository) { mutableStateOf(WorkspaceSnapshot(sampleProject())) }
    var saved by remember(repository) { mutableStateOf<WorkspaceSnapshot?>(null) }
    var busy by remember(repository) { mutableStateOf(true) }
    var loaded by remember(repository) { mutableStateOf(false) }
    var failure by remember(repository) { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf("") }

    suspend fun reload() {
        busy = true
        failure = null
        try {
            workspace = withContext(Dispatchers.Default) { repository.load() }
            saved = workspace
            loaded = true
            description = ""
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
    val draft = workspace.draft
    val editable = loaded && !busy
    val dirty = workspace != saved
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Project Montage", style = MaterialTheme.typography.headlineMedium)
                Text("$platformName · Device workspace")
                Text("Model APIs and media processing are not connected. Use Save draft to keep changes on this device.")
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
                        scope.launch { reload() }
                    }) { Text(if (!loaded) "Retry loading" else if (dirty) "Discard edits & reload" else "Reload saved") }
                }
                Text(failure ?: when {
                    busy -> "Opening or saving draft…"
                    dirty -> "Unsaved changes"
                    else -> "No unsaved changes"
                }, modifier = Modifier.testTag("storage-status"))
                if (workspace.jobs.isNotEmpty()) {
                    Text("${workspace.jobs.size} saved task checkpoints · no tasks run automatically")
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(draft.scenes, key = { it.id }) { scene ->
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
