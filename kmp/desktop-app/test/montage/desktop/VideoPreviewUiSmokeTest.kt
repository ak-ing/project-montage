// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import javafx.application.Platform
import montage.core.*
import montage.ui.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class VideoPreviewUiSmokeTest {
    @get:Rule val compose = createComposeRule()
    companion object {
        @BeforeClass @JvmStatic fun decoderDiagnostics() { System.setProperty("jfxmedia.loglevel", "DEBUG") }
        @AfterClass @JvmStatic fun stopToolkit() { Platform.exit() }
    }
    private class ObservedProvider(directory: File) : VideoPreviewProvider {
        private val real = DesktopVideoPreview(directory)
        lateinit var current: VideoPreviewController
        var opened = 0
        var closed = 0
        private class Session(val native: VideoPreviewController, val dispose: () -> Unit) : VideoPreviewController by native {
            override fun close() { native.close(); dispose() }
        }
        override fun open(asset: VideoAsset): VideoPreviewController = Session(real.open(asset)) { closed++ }.also { current = it; opened++ }
        @Composable override fun Surface(controller: VideoPreviewController, modifier: Modifier) {
            real.Surface((controller as Session).native, modifier)
        }
    }
    private fun capture(stage: String) {
        val file = File("../build/ui-smoke/desktop/$stage.png")
        file.parentFile.mkdirs()
        check(ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", file))
    }
    private fun waitReady() = compose.waitUntil(30_000) {
        compose.onAllNodesWithText("No unsaved changes").fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitPreview(provider: ObservedProvider, expectedOpen: Int = 1) {
        compose.waitUntil(30_000) { provider.opened >= expectedOpen && provider.current.state.value.status != PlaybackStatus.Loading }
        assertEquals(PlaybackStatus.Paused, provider.current.state.value.status)
        compose.onNodeWithTag("video-thumbnail").assertExists()
        val metadata = provider.current.state.value.metadata!!
        assertEquals(8000, metadata.durationMillis)
        assertEquals(160, metadata.width)
        assertEquals(90, metadata.height)
        assertEquals(0, metadata.rotationDegrees)
        assertTrue(metadata.hasAudio)
        val pixels = provider.current.state.value.thumbnail!!.toPixelMap()
        File("../build/ui-smoke/desktop").mkdirs()
        ImageIO.write(provider.current.state.value.thumbnail!!.toAwtImage(), "png", File("../build/ui-smoke/desktop/preview-thumbnail.png"))
        assertTrue((0 until pixels.width step 8).any { x ->
            (0 until pixels.height step 8).any { y -> pixels[x, y].blue > 0.6f && pixels[x, y].red < 0.3f }
        }, "Thumbnail must contain a decoded blue video frame")
    }
    @Test fun nativeDecodePlaybackSeekAndRestoredPreviewKeepUnsavedEdits() {
        val dir = Files.createTempDirectory("montage-preview-ui-")
        try {
            val media = dir.resolve("media").toFile()
            val files = DesktopVideoLibrary(media)
            val library = object : VideoLibrary by files {
                override suspend fun pickAndImport() = files.importStream("Preview-smoke.mp4") { File("../test-data/preview-smoke.mp4").inputStream() }
            }
            val provider = ObservedProvider(media)
            var epoch by mutableStateOf(0)
            compose.setContent { key(epoch) {
                val storage = remember { FileWorkspaceStorage(dir.resolve("workspace.json")) }
                MontageApp("Native preview", storage, library, provider)
            } }
            waitReady()
            compose.onNodeWithTag("project-title").performTextReplacement("Unsaved preview draft")
            compose.onNodeWithTag("import-video").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Available on this device").fetchSemanticsNodes().isNotEmpty() }
            val preview = compose.onNode(hasTestTagStartingWith("preview-video-"))
            preview.performClick()
            waitPreview(provider)
            capture("preview-ready")
            compose.onNodeWithTag("play-video").performScrollTo().performClick()
            compose.waitUntil(10_000) { provider.current.state.value.positionMillis > 300 && provider.current.state.value.status == PlaybackStatus.Playing }
            compose.onNodeWithTag("pause-video").performClick()
            compose.waitUntil(5_000) { provider.current.state.value.status == PlaybackStatus.Paused }
            compose.onNodeWithTag("video-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(6000f) }
            compose.waitUntil(10_000) { provider.current.state.value.positionMillis in 5500..6500 }
            compose.waitUntil(10_000) {
                val pixels = compose.onNodeWithTag("video-surface").captureToImage().toPixelMap()
                val center = pixels[pixels.width / 2, pixels.height / 2]
                center.red > 0.6f && center.blue < 0.3f
            }
            capture("preview-seek")
            compose.onNodeWithTag("preview-back").performScrollTo().performClick()
            compose.onNodeWithTag("project-title").assertTextContains("Unsaved preview draft")
            compose.onNodeWithTag("storage-status").assertTextEquals("Unsaved changes")
            assertEquals(1, provider.closed)
            compose.onNodeWithTag("video-tab").assertTextContains("Videos 1")
            compose.onNodeWithTag("save-draft").performClick()
            waitReady()
            compose.runOnIdle { epoch++ }
            waitReady()
            compose.onNodeWithTag("video-tab").performClick()
            compose.onNode(hasTestTagStartingWith("preview-video-")).performClick()
            waitPreview(provider, 2)
            capture("preview-restored")
            compose.onNodeWithTag("preview-back").performScrollTo().performClick()
            compose.waitUntil(5_000) { provider.closed == 2 }
            assertEquals(2, provider.closed)
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun corruptVideoCanBeRepairedAndRetriedWithoutLosingWorkspace() {
        val dir = Files.createTempDirectory("montage-preview-bad-")
        try {
            val media = dir.resolve("media").toFile()
            val files = DesktopVideoLibrary(media)
            val asset = files.importStream("Repair.mp4") { File("../test-data/preview-smoke.mp4").inputStream() }
            val managed = File(media, "${asset.id}.media")
            managed.writeBytes(ByteArray(asset.byteSize.toInt()))
            val storage = FileWorkspaceStorage(dir.resolve("workspace.json"))
            WorkspaceRepository(storage).apply { save(load().copy(videos = listOf(asset))) }
            val provider = ObservedProvider(media)
            compose.setContent { MontageApp("Native preview", storage, files, provider) }
            waitReady()
            compose.onNodeWithTag("video-tab").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Available on this device").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasTestTagStartingWith("preview-video-")).performClick()
            compose.waitUntil(30_000) { provider.opened > 0 && provider.current.state.value.status == PlaybackStatus.Error }
            compose.onNodeWithTag("preview-error").assertExists()
            assertEquals(asset.byteSize, managed.length())
            File("../test-data/preview-smoke.mp4").copyTo(managed, overwrite = true)
            compose.onNodeWithTag("preview-retry").performClick()
            waitPreview(provider, 2)
            assertEquals(1, provider.closed)
            compose.onNodeWithTag("preview-back").performScrollTo().performClick()
            assertEquals(listOf(asset), WorkspaceRepository(storage).load().videos)
        } finally { dir.toFile().deleteRecursively() }
    }
    private fun hasTestTagStartingWith(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
        it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }
}
