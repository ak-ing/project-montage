// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.graphics.toAwtImage
import montage.ui.MontageApp
import montage.core.WorkspaceRepository
import montage.core.VideoAsset
import montage.core.VideoLibrary
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import java.nio.file.Files

/** Exercises a Compose test scene with the host's Skiko renderer; snapshots are rendered offscreen. */
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

    @Test fun importsVideoWithCancelFailureAndMissingFileRecovery() {
        val directory = Files.createTempDirectory("montage-import-ui-")
        try {
            val files = DesktopVideoLibrary(directory.resolve("media").toFile())
            var calls = 0
            val library = object : VideoLibrary {
                override suspend fun pickAndImport(): VideoAsset? = when (++calls) {
                    1 -> null
                    2 -> error("Unreadable selection")
                    else -> files.importStream("Montage-import-smoke.mp4") {
                        File("../test-data/import-smoke.mp4").inputStream()
                    }
                }
                override fun isAvailable(asset: VideoAsset) = files.isAvailable(asset)
                override fun removeUnreferenced(retained: List<VideoAsset>) = files.removeUnreferenced(retained)
            }
            val storage = FileWorkspaceStorage(directory.resolve("workspace.json"))
            compose.setContent { MontageApp(System.getProperty("os.name"), storage, library) }
            waitUntilReady()
            compose.onNodeWithTag("import-video").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Import cancelled").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("save-draft").assertIsNotEnabled()
            compose.onNodeWithTag("import-video").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Could not import video. Check file access and available storage, then retry.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("video-tab").assertTextContains("Videos 0")
            compose.onNodeWithTag("project-title").performTextReplacement("Draft after import error")
            compose.onNodeWithTag("storage-status").assertTextEquals("Unsaved changes")
            compose.onNodeWithTag("project-title").performTextReplacement("My first Montage")
            compose.onNodeWithTag("import-video").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Available on this device").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Montage-import-smoke.mp4").assertExists()
            compose.onNodeWithTag("save-draft").performClick()
            waitUntilReady()
            val asset = WorkspaceRepository(storage).load().videos.single()
            capture("videos")
            directory.resolve("media/${asset.id}.media").toFile().delete()
            compose.onNodeWithTag("reload-draft").performClick()
            waitUntilReady()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Video file unavailable · remove and import again").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("remove-video-${asset.id}").performClick()
            compose.onNodeWithTag("save-draft").performClick()
            waitUntilReady()
            check(WorkspaceRepository(storage).load().videos.isEmpty())
        } finally { directory.toFile().deleteRecursively() }
    }
}
