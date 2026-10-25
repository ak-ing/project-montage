// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import montage.core.*
import montage.ui.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class SceneEditorUiSmokeTest {
    @get:Rule val compose = createComposeRule()
    private fun waitReady() = compose.waitUntil(30_000) {
        compose.onAllNodesWithText("No unsaved changes").fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitDuration() = compose.waitUntil(30_000) {
        compose.onAllNodesWithTag("clip-source-duration").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithText("Reading video…").fetchSemanticsNodes().isEmpty()
    }
    private class ObservedProvider(media: File) : VideoPreviewProvider {
        val real = DesktopVideoPreview(media)
        var opened = 0
        var closed = 0
        private class Session(val native: VideoPreviewController, val dispose: () -> Unit) : VideoPreviewController by native {
            override fun close() { native.close(); dispose() }
        }
        override fun open(asset: VideoAsset): VideoPreviewController = Session(real.open(asset)) { closed++ }.also { opened++ }
        @Composable override fun Surface(controller: VideoPreviewController, modifier: Modifier) {
            real.Surface((controller as Session).native, modifier)
        }
    }

    @Test fun editValidateReorderRecoverDetachAndRemoveUsingRealMedia() {
        val directory = Files.createTempDirectory("montage-scene-editor-")
        try {
            val media = directory.resolve("media").toFile()
            val library = DesktopVideoLibrary(media)
            val fixture = File("../test-data/preview-smoke.mp4")
            val original = fixture.readBytes()
            val asset = library.importStream("Scene-source.mp4") { fixture.inputStream() }
            val shortAsset = library.importStream("Short-source.mp4") { File("../test-data/import-smoke.mp4").inputStream() }
            val file = File(media, "${asset.id}.media")
            val disk = FileWorkspaceStorage(directory.resolve("workspace.json"))
            var failSave = false
            val storage = object : WorkspaceStorage by disk {
                override fun writeAtomically(document: String) {
                    check(!failSave) { "Injected disk failure" }
                    disk.writeAtomically(document)
                }
            }
            val repository = WorkspaceRepository(storage)
            val initial = repository.load().copy(videos = listOf(asset, shortAsset), jobs = listOf(
                JobCheckpoint("job", MontageTool.GenerateVideos, JobStatus.Interrupted, "polling", "operation-keep")))
            // Start from an actual schema-2 file, not just a new schema-3 draft.
            val legacy = """{"schemaVersion":2,"draft":{"title":"My first Montage","scenes":[{"id":"opening","description":"Opening scene","durationSeconds":6},{"id":"ending","description":"Closing scene","durationSeconds":4}],"transition":"fade"},"jobs":[{"id":"job","tool":"GenerateVideos","status":"Interrupted","stage":"polling","providerOperationId":"operation-keep"}],"videos":[{"id":"${asset.id}","name":"${asset.name}","byteSize":${asset.byteSize}},{"id":"${shortAsset.id}","name":"${shortAsset.name}","byteSize":${shortAsset.byteSize}}]}"""
            disk.writeAtomically(legacy)
            val provider = ObservedProvider(media)
            var epoch by mutableStateOf(0)
            compose.setContent { key(epoch) { MontageApp("Scene edit smoke", storage, library, provider) } }
            waitReady()
            assertEquals(initial, WorkspaceRepository(storage).load())
            val before = disk.read()
            assertNotEquals(legacy, before)
            compose.onNodeWithTag("edit-scene-opening").performScrollTo().performClick()
            compose.onNodeWithTag("move-scene-up").assertIsNotEnabled()
            compose.onNodeWithTag("choose-scene-video").performClick()
            compose.onNodeWithTag("clip-video-${asset.id}").performClick()
            waitDuration()
            compose.onNodeWithTag("clip-source-duration").assertTextEquals("8000 ms available")
            compose.onNodeWithTag("choose-scene-video").performClick()
            compose.onNodeWithTag("clip-video-${shortAsset.id}").performClick()
            waitDuration()
            compose.onNodeWithTag("clip-source-duration").assertTextEquals("1000 ms available")
            compose.onNodeWithTag("clip-out").performTextReplacement("6500")
            compose.onNodeWithTag("apply-scene-clip").assertIsNotEnabled()
            compose.onNodeWithTag("choose-scene-video").performClick()
            compose.onNodeWithTag("clip-video-${asset.id}").performClick()
            waitDuration()
            compose.onNodeWithTag("clip-source-duration").assertTextEquals("8000 ms available")
            compose.onNodeWithTag("clip-in").performTextReplacement("-1")
            compose.onNodeWithTag("apply-scene-clip").assertIsNotEnabled()
            compose.onNodeWithTag("clip-in").performTextReplacement("1250")
            compose.onNodeWithTag("clip-out").performTextReplacement("9000")
            compose.onNodeWithTag("clip-range-error").assertExists()
            compose.onNodeWithTag("apply-scene-clip").assertIsNotEnabled()
            compose.onNodeWithTag("clip-out").performTextReplacement("1250")
            compose.onNodeWithTag("apply-scene-clip").assertIsNotEnabled()
            compose.onNodeWithTag("clip-out").performTextReplacement("6500")
            compose.onNodeWithTag("apply-scene-clip").assertIsEnabled().performClick()
            compose.onNodeWithTag("apply-scene-clip").assertIsNotEnabled()
            compose.onNodeWithTag("move-scene-down").performClick()
            compose.onNodeWithTag("scene-editor-position").assertTextEquals("Scene 2")
            compose.onNodeWithTag("move-scene-down").assertIsNotEnabled()
            compose.onNodeWithTag("scene-editor-back").performClick()
            compose.onNodeWithTag("storage-status").assertTextEquals("Unsaved changes")
            compose.onNodeWithTag("edited-duration").assertTextEquals("9250 ms edited sequence · transitions not rendered")
            compose.onNodeWithTag("video-tab").performClick()
            compose.onNodeWithTag("video-in-use-${asset.id}").assertExists()
            compose.onNodeWithTag("remove-video-${asset.id}").assertIsNotEnabled()
            compose.runOnIdle { failSave = true }
            compose.onNodeWithTag("save-draft").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Could not save. Changes remain here; retry Save draft.").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before, disk.read())
            compose.runOnIdle { failSave = false }
            compose.onNodeWithTag("save-draft").performClick()
            waitReady()
            val saved = WorkspaceRepository(storage).load()
            assertEquals(listOf("ending", "opening"), saved.draft.scenes.map { it.id })
            assertEquals(SceneClip(asset.id, 1250, 6500), saved.draft.scenes.last().clip)
            assertEquals(initial.jobs, saved.jobs)
            compose.runOnIdle { epoch++ }
            waitReady()
            compose.onNodeWithTag("edit-scene-opening").performScrollTo().performClick()
            waitDuration()
            compose.onNodeWithTag("clip-in").assertTextContains("1250")
            compose.onNodeWithTag("clip-out").assertTextContains("6500")
            val screenshot = File("../build/ui-smoke/desktop/scene-edits-restored.png")
            screenshot.parentFile.mkdirs()
            check(ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", screenshot))
            compose.onNodeWithTag("clip-out").performTextReplacement("7000")
            compose.onNodeWithTag("scene-editor-back").assertTextContains("Discard clip edits & back").performClick()
            compose.onNodeWithTag("edit-scene-opening").performScrollTo().performClick()
            waitDuration()
            compose.onNodeWithTag("clip-out").assertTextContains("6500")
            compose.onNodeWithTag("scene-editor-back").performClick()
            // Missing referenced media remains linked and is retryable after external repair.
            val backup = File(media, "backup")
            compose.runOnIdle {
                // Input dispatch precedes recomposition/disposal. Windows forbids
                // renaming an open native decoder file; wait for the actual exit.
                assertEquals(provider.opened, provider.closed)
                check(file.renameTo(backup))
            }
            compose.onNodeWithTag("edit-scene-opening").performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("clip-video-error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("apply-scene-clip").assertIsNotEnabled()
            compose.onNodeWithTag("detach-scene-video").assertIsEnabled()
            check(backup.renameTo(file))
            compose.onNodeWithTag("clip-video-retry").performClick()
            waitDuration()
            compose.onNodeWithTag("clip-out").assertTextContains("6500")
            compose.onNodeWithTag("detach-scene-video").performScrollTo().performClick()
            compose.onNodeWithTag("scene-editor-back").performClick()
            compose.onNodeWithTag("reload-draft").performClick()
            waitReady()
            assertTrue(file.isFile)
            compose.onNodeWithTag("edit-scene-opening").performScrollTo().performClick()
            waitDuration()
            compose.onNodeWithTag("clip-in").assertTextContains("1250")
            compose.onNodeWithTag("detach-scene-video").performScrollTo().performClick()
            compose.onNodeWithTag("scene-editor-back").performClick()
            compose.onNodeWithTag("save-draft").performClick()
            waitReady()
            compose.onNodeWithTag("video-tab").performClick()
            compose.onNodeWithTag("remove-video-${asset.id}").assertIsEnabled().performClick()
            assertTrue(file.isFile)
            compose.onNodeWithTag("save-draft").performClick()
            waitReady()
            compose.onNodeWithTag("remove-video-${shortAsset.id}").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("save-draft").performClick()
            waitReady()
            val final = WorkspaceRepository(storage).load()
            assertTrue(final.videos.isEmpty())
            assertTrue(final.draft.scenes.all { it.clip == null })
            assertEquals(initial.jobs, final.jobs)
            assertTrue(media.listFiles()!!.isEmpty())
            assertContentEquals(original, fixture.readBytes())
            assertEquals(provider.opened, provider.closed)
        } finally { directory.toFile().deleteRecursively() }
    }
}
