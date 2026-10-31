// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import kotlinx.coroutines.runBlocking
import montage.core.CompositionClip
import montage.core.CompositionRequest
import montage.core.ExportCancelledException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File
import java.net.URI
import org.bytedeco.javacv.FFmpegFrameGrabber

class DesktopCompositionExporterTest {
    @Test
    fun trimsAndConcatenatesClipsIntoAnMp4AndReportsFinalDuration() = runBlocking {
        val root = createTempDirectory("montage-render").toFile()
        val media = root.resolve("media")
        val exports = root.resolve("exports")
        val library = DesktopVideoLibrary(media)
        val asset = library.importStream("preview.mp4") { File("../test-data/preview-smoke.mp4").inputStream() }
        val request = CompositionRequest(
            listOf(CompositionClip(asset.id, 0, 1_000), CompositionClip(asset.id, 4_000, 5_000)),
            transition = "none",
        )
        val progress = mutableListOf<Float>()
        val result = DesktopCompositionExporter(media, exports).export(request) { progress += it.fraction }
        val output = File(URI(result.output.value))
        assertTrue(output.isFile && output.length() > 0)
        assertEquals(2_000L, result.durationMillis)
        assertEquals(1f, progress.last())
        val grabber = FFmpegFrameGrabber(output)
        try {
            grabber.start()
            assertTrue(grabber.lengthInTime in 1_850_000L..2_150_000L, "${grabber.lengthInTime}us")
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    @Test
    fun cancellationRemovesOnlyThePartialExport() = runBlocking {
        val root = createTempDirectory("montage-render-cancel").toFile()
        val media = root.resolve("media")
        val exports = root.resolve("exports")
        val library = DesktopVideoLibrary(media)
        val asset = library.importStream("preview.mp4") { File("../test-data/preview-smoke.mp4").inputStream() }
        val exporter = DesktopCompositionExporter(media, exports)
        val request = CompositionRequest(listOf(CompositionClip(asset.id, 0, 8_000)), "none")
        var requested = false
        assertFailsWith<ExportCancelledException> {
            exporter.export(request) { progress ->
                if (!requested && progress.completedMillis > 0) {
                    requested = true
                    exporter.cancel()
                }
            }
        }
        assertTrue(requested)
        assertFalse(exports.exists() && exports.listFiles()!!.any { it.name.endsWith(".part.mp4") || it.extension == "mp4" })
    }
}
