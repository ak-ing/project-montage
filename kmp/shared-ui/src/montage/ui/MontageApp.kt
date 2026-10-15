// SPDX-License-Identifier: Apache-2.0
package montage.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import montage.core.appendScene
import montage.core.sampleProject

@Composable
fun MontageApp(platformName: String) {
    var draft by remember { mutableStateOf(sampleProject()) }
    var description by remember { mutableStateOf("") }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Project Montage", style = MaterialTheme.typography.headlineMedium)
                Text("$platformName · Local draft preview")
                Text("Cloud generation is not connected. Changes are kept in memory for this session.")
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    label = { Text("Project title") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${draft.scenes.size} scenes · ${draft.visibleDurationSeconds}s visible · ${draft.transitionBufferSeconds}s transition buffer")
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("New scene description") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = description.isNotBlank(), onClick = {
                        draft = draft.appendScene(description)
                        description = ""
                    }) { Text("Add scene") }
                    TextButton(onClick = { draft = sampleProject(); description = "" }) { Text("Reset sample") }
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
