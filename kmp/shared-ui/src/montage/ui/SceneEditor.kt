// SPDX-License-Identifier: Apache-2.0
package montage.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import montage.core.*

/** Edit decisions only; source media is never trimmed or overwritten here. */
@Composable
fun SceneEditor(workspace: WorkspaceSnapshot, sceneId: String, previews: VideoPreviewProvider?,
                onChange: (WorkspaceSnapshot) -> Unit, onBack: () -> Unit) {
    val scene = workspace.draft.scenes.first { it.id == sceneId }
    val index = workspace.draft.scenes.indexOf(scene)
    val focus = LocalFocusManager.current
    var selectedId by remember(sceneId) { mutableStateOf(scene.clip?.videoId) }
    var inText by remember(sceneId) { mutableStateOf((scene.clip?.inMillis ?: 0).toString()) }
    var outText by remember(sceneId) { mutableStateOf(scene.clip?.outMillis?.toString() ?: "") }
    var rangeEdited by remember(sceneId) { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    val asset = workspace.videos.firstOrNull { it.id == selectedId }
    val player = remember(previews, asset, attempt) { asset?.let { previews?.open(it) } }
    DisposableEffect(player) { onDispose { player?.close() } }
    val state = player?.state?.collectAsState()?.value
    val metadata = state?.metadata
    LaunchedEffect(player, metadata) {
        if (metadata != null && !rangeEdited && outText.isEmpty()) outText = metadata.durationMillis.toString()
    }
    val pending = selectedId != scene.clip?.videoId || (selectedId != null &&
        (inText.toLongOrNull() != scene.clip?.inMillis || outText.toLongOrNull() != scene.clip?.outMillis))
    val candidate = asset?.let { runCatching { SceneClip(it.id, inText.toLong(), outText.toLong()) }.getOrNull() }
    val valid = metadata != null && candidate != null && candidate.outMillis <= metadata.durationMillis &&
        state.status != PlaybackStatus.Error && state.status != PlaybackStatus.Loading
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()).testTag("scene-editor-scroll"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(modifier = Modifier.testTag("scene-editor-back"), onClick = { focus.clearFocus(); onBack() }) {
                    Text(if (pending) "Discard clip edits & back" else "Back to scenes")
                }
                Text("Scene ${index + 1}", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("scene-editor-position"))
                Text(scene.description)
                Text("Apply clip to update the draft, then Save draft on the scenes page.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.testTag("move-scene-up"), enabled = index > 0,
                        onClick = { focus.clearFocus(); onChange(workspace.moveScene(sceneId, index - 1)) }) { Text("Move up") }
                    OutlinedButton(modifier = Modifier.testTag("move-scene-down"), enabled = index < workspace.draft.scenes.lastIndex,
                        onClick = { focus.clearFocus(); onChange(workspace.moveScene(sceneId, index + 1)) }) { Text("Move down") }
                }
                Box {
                    Button(modifier = Modifier.testTag("choose-scene-video"), enabled = workspace.videos.isNotEmpty() && previews != null,
                        onClick = { focus.clearFocus(); choosing = true }) { Text("Choose video") }
                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        workspace.videos.forEach { video ->
                            DropdownMenuItem(text = { Text(video.name) }, modifier = Modifier.testTag("clip-video-${video.id}"), onClick = {
                                if (selectedId != video.id) {
                                    selectedId = video.id; inText = "0"; outText = ""; rangeEdited = false
                                }
                                choosing = false
                            })
                        }
                    }
                }
                Text(asset?.name ?: "No video linked", modifier = Modifier.testTag("scene-editor-video"))
                if (workspace.videos.isEmpty()) Text("Import a video from the Videos page first.")
                if (asset != null) {
                    when (state?.status) {
                        null, PlaybackStatus.Loading -> Text("Reading video…")
                        PlaybackStatus.Error -> {
                            Text("Cannot read this video. Check the file and retry.", modifier = Modifier.testTag("clip-video-error"))
                            TextButton(modifier = Modifier.testTag("clip-video-retry"), onClick = { attempt++ }) { Text("Retry video") }
                        }
                        else -> Unit
                    }
                    metadata?.let { Text("${it.durationMillis} ms available", modifier = Modifier.testTag("clip-source-duration")) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = inText, onValueChange = { inText = it; rangeEdited = true },
                            label = { Text("In (ms)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("clip-in"))
                        OutlinedTextField(value = outText, onValueChange = { outText = it; rangeEdited = true },
                            label = { Text("Out (ms)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("clip-out"))
                    }
                    if (metadata != null && !valid && state.status != PlaybackStatus.Error) {
                        Text("Use a non-empty range from 0 to ${metadata.durationMillis} ms.", modifier = Modifier.testTag("clip-range-error"))
                    }
                    Button(modifier = Modifier.testTag("apply-scene-clip"), enabled = valid && pending, onClick = {
                        focus.clearFocus()
                        onChange(workspace.withSceneClip(sceneId, candidate!!, metadata!!))
                    }) { Text("Apply clip") }
                    // Android's native player needs an attached surface before becoming ready.
                    if (player != null && previews != null) key(player) {
                        previews.Surface(player, Modifier.fillMaxWidth().height(120.dp).testTag("clip-video-surface"))
                    }
                    state?.thumbnail?.let { Image(it, "Clip video thumbnail", Modifier.size(128.dp, 72.dp)) }
                }
                if (scene.clip != null) TextButton(modifier = Modifier.testTag("detach-scene-video"), onClick = {
                    focus.clearFocus()
                    onChange(workspace.clearSceneClip(sceneId))
                    selectedId = null; inText = "0"; outText = ""; rangeEdited = false
                }) { Text("Detach video") }
            }
        }
    }
}
