// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import montage.core.VideoAsset
import montage.core.VideoLibrary
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DesktopVideoLibrary(private val directory: File) : VideoLibrary {
    override suspend fun pickAndImport(): VideoAsset? {
        val selected = suspendCancellableCoroutine<File?> { continuation ->
            EventQueue.invokeLater {
                if (!continuation.isActive) return@invokeLater
                var picker: FileDialog? = null
                try {
                    picker = FileDialog(null as Frame?, "Import video", FileDialog.LOAD)
                    picker.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in VIDEO_EXTENSIONS }
                    continuation.invokeOnCancellation { EventQueue.invokeLater { picker.dispose() } }
                    picker.isVisible = true
                    val result = picker.file?.let { File(picker.directory, it) }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } finally {
                    picker?.dispose()
                }
            }
        } ?: return null
        require(selected.extension.lowercase() in VIDEO_EXTENSIONS) { "Select a video file" }
        return withContext(Dispatchers.IO) { importStream(selected.name) { selected.inputStream() } }
    }

    /** Streamed copies are also exercised by tests, independently of the native picker. */
    fun importStream(name: String, open: () -> InputStream): VideoAsset {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create video directory" }
        val id = UUID.randomUUID().toString()
        val partial = File(directory, "$id.part")
        val destination = File(directory, "$id.media")
        try {
            open().use { input ->
                FileOutputStream(partial).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
            val asset = VideoAsset(id, name, partial.length())
            check(!destination.exists() && partial.renameTo(destination)) { "Cannot commit video" }
            return asset
        } finally {
            partial.delete()
        }
    }

    override fun isAvailable(asset: VideoAsset): Boolean = File(directory, "${asset.id}.media").let {
        it.isFile && it.canRead() && it.length() == asset.byteSize
    }

    override fun removeUnreferenced(retained: List<VideoAsset>) {
        if (!directory.exists()) return
        val keep = retained.map { "${it.id}.media" }.toSet()
        checkNotNull(directory.listFiles()) { "Cannot read video directory" }
            .filter { it.isFile && it.name.matches(MANAGED_FILE) && it.name !in keep }
            .forEach { check(it.delete()) { "Cannot remove unused video" } }
    }

    companion object {
        private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm", "mkv", "avi", "mpeg", "mpg", "3gp")
        private val MANAGED_FILE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(media|part)")
    }
}
