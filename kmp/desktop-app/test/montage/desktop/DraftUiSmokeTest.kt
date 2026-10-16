// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.graphics.toAwtImage
import montage.ui.MontageApp
import montage.core.WorkspaceRepository
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import java.nio.file.Files

/** Runs a real ComposeWindow on the host, including its native Skiko renderer. */
class DraftUiSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editAppendAndResetDraft() {
        val directory = Files.createTempDirectory("montage-ui-")
        val storage = FileWorkspaceStorage(directory.resolve("workspace.json"))
        compose.setContent { MontageApp(System.getProperty("os.name"), storage) }
        waitUntilReady()
        compose.onNodeWithTag("draft-summary").assertTextEquals("2 scenes · 10s visible · 1s transition buffer")
        compose.onNodeWithTag("add-scene").assertIsNotEnabled()
        capture("initial")
        compose.onNodeWithTag("project-title").performTextReplacement("UI smoke title")
        compose.onNodeWithTag("project-title").assertTextContains("UI smoke title")
        compose.onNodeWithTag("scene-description").performTextInput("Smoke scene")
        compose.onNodeWithTag("add-scene").assertIsEnabled().performClick()
        compose.onNodeWithTag("draft-summary").assertTextEquals("3 scenes · 14s visible · 2s transition buffer")
        compose.onNodeWithText("Smoke scene").assertExists()
        compose.onNodeWithTag("add-scene").assertIsNotEnabled()
        capture("added")
        compose.onNodeWithTag("save-draft").performClick()
        waitUntilReady()
        compose.onNodeWithTag("save-draft").assertIsNotEnabled()
        compose.runOnIdle {
            val restored = WorkspaceRepository(FileWorkspaceStorage(directory.resolve("workspace.json"))).load()
            check(restored.draft.title == "UI smoke title")
            check(restored.draft.scenes.size == 3)
        }
        compose.onNodeWithTag("reset-sample").performClick()
        compose.onNodeWithTag("draft-summary").assertTextEquals("2 scenes · 10s visible · 1s transition buffer")
        compose.onNodeWithTag("reload-draft").performClick()
        waitUntilReady()
        compose.onNodeWithTag("draft-summary").assertTextEquals("3 scenes · 14s visible · 2s transition buffer")
        compose.onNodeWithTag("project-title").assertTextContains("UI smoke title")
        capture("restored")
        compose.onNodeWithTag("reset-sample").performClick()
        compose.onNodeWithTag("project-title").assertTextContains("My first Montage")
        compose.onNodeWithText("Smoke scene").assertDoesNotExist()
        capture("reset")
        directory.toFile().deleteRecursively()
    }

    private fun waitUntilReady() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("No unsaved changes").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun unreadableWorkspaceDisablesEditsAndCanBeRetried() {
        val directory = Files.createTempDirectory("montage-ui-damaged-")
        try {
            val file = directory.resolve("workspace.json")
            Files.writeString(file, "damaged")
            compose.setContent { MontageApp("Test", FileWorkspaceStorage(file)) }
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText("Cannot open saved draft. Your saved file has been kept. Retry loading.")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("project-title").assertIsNotEnabled()
            compose.onNodeWithTag("save-draft").assertIsNotEnabled()
            compose.onNodeWithTag("reset-sample").assertIsNotEnabled()
            check(Files.readString(file) == "damaged")
            compose.runOnIdle {
                val storage = FileWorkspaceStorage(file)
                // Simulate restoring a valid copy outside the app; retry must not need a restart.
                Files.delete(file)
                val repository = WorkspaceRepository(storage)
                val snapshot = repository.load()
                repository.save(snapshot.copy(draft = snapshot.draft.copy(title = "Recovered")))
            }
            compose.onNodeWithTag("reload-draft").performClick()
            waitUntilReady()
            compose.onNodeWithTag("project-title").assertIsEnabled().assertTextContains("Recovered")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun capture(stage: String) {
        val output = File("../build/ui-smoke/desktop/$stage.png")
        output.parentFile.mkdirs()
        check(ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", output))
    }
}
