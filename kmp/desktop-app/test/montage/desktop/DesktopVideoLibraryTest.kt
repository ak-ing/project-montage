// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.awt.FileDialog
import java.awt.EventQueue
import java.awt.Window
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class DesktopVideoLibraryTest {
    @Test fun copiesBytesAndKeepsAnIndependentReadableFile() {
        val directory = Files.createTempDirectory("montage-video-").toFile()
        try {
            val source = directory.resolve("original.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val library = DesktopVideoLibrary(directory.resolve("media"))
            val asset = library.importStream(source.name) { source.inputStream() }
            source.delete()
            assertTrue(DesktopVideoLibrary(directory.resolve("media")).isAvailable(asset))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), directory.resolve("media/${asset.id}.media").readBytes())
            assertEquals(4L, asset.byteSize)
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedReadDoesNotCommitOrRetainPartialFiles() {
        val directory = Files.createTempDirectory("montage-video-fail-").toFile()
        try {
            val library = DesktopVideoLibrary(directory)
            val source = object : InputStream() {
                var count = 0
                override fun read(): Int = if (++count < 100) 1 else throw IOException("Read failure")
            }
            assertThrows(IOException::class.java) { library.importStream("broken.mp4") { source } }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun emptyVideoIsNotCommitted() {
        val directory = Files.createTempDirectory("montage-video-empty-").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                DesktopVideoLibrary(directory).importStream("empty.mp4") { ByteArrayInputStream(byteArrayOf()) }
            }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun cleanupKeepsReferencedVideosAndUnrelatedFiles() {
        val directory = Files.createTempDirectory("montage-video-clean-").toFile()
        try {
            val library = DesktopVideoLibrary(directory)
            val kept = library.importStream("keep.mp4") { ByteArrayInputStream(byteArrayOf(1)) }
            val removed = library.importStream("remove.mp4") { ByteArrayInputStream(byteArrayOf(2)) }
            directory.resolve("user-notes.txt").writeText("keep")
            directory.resolve("user-video.media").writeText("unrelated")
            library.removeUnreferenced(listOf(kept))
            assertTrue(library.isAvailable(kept))
            assertFalse(library.isAvailable(removed))
            assertEquals("keep", directory.resolve("user-notes.txt").readText())
            assertEquals("unrelated", directory.resolve("user-video.media").readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun nativeFilePickerCanBeCancelledWithoutImporting() = runBlocking {
        val directory = Files.createTempDirectory("montage-picker-").toFile()
        try {
            val result = async(Dispatchers.Default) { DesktopVideoLibrary(directory).pickAndImport() }
            withTimeout(30_000) {
                while (Window.getWindows().filterIsInstance<FileDialog>().none { it.isVisible }) delay(100)
                val picker = Window.getWindows().filterIsInstance<FileDialog>().first { it.isVisible }
                delay(500) // Native peers finish opening after AWT marks the dialog visible.
                // Exercise the same native close path used when the importing screen is disposed.
                EventQueue.invokeLater { picker.dispose() }
                assertNull(result.await())
            }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
