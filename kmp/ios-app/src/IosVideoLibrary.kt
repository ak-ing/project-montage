// SPDX-License-Identifier: Apache-2.0
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import montage.core.VideoAsset
import montage.core.VideoLibrary
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.UTTypeMovie
import platform.darwin.NSObject
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
class IosVideoLibrary(private val present: (UIViewController) -> Unit) : VideoLibrary {
    private val manager = NSFileManager.defaultManager
    private val directory = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL)
        .path!! + "/ProjectMontage/media"
    private var pending: CancellableContinuation<NSURL?>? = null
    private val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            NSLog("Montage video import: selection callback")
            finishPicker(controller, didPickDocumentsAtURLs.firstOrNull() as? NSURL)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            NSLog("Montage video import: cancellation callback")
            finishPicker(controller, null)
        }
    }

    private fun finishPicker(controller: UIDocumentPickerViewController, url: NSURL?) {
        val continuation = pending ?: return
        pending = null
        // Keep the import busy until UIKit has completed the modal dismissal.
        controller.dismissViewControllerAnimated(true) {
            NSLog("Montage video import: picker dismissal completed")
            if (continuation.isActive) continuation.resume(url)
        }
    }

    override suspend fun pickAndImport(): VideoAsset? {
        val url = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<NSURL?> { continuation ->
                check(pending == null) { "Video picker is already open" }
                // Import mode gives us a sandbox copy, rather than opening the original in place.
                val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeMovie), asCopy = true)
                picker.delegate = delegate
                picker.allowsMultipleSelection = false
                val documents = NSHomeDirectory() + "/Documents"
                check(manager.createDirectoryAtPath(documents, true, null, null)) { "Cannot open import folder" }
                picker.directoryURL = NSURL.fileURLWithPath(documents)
                pending = continuation
                continuation.invokeOnCancellation { pending = null }
                try {
                    NSLog("Montage video import: presenting picker")
                    present(picker)
                } catch (failure: Exception) {
                    pending = null
                    throw failure
                }
            }
        } ?: return null
        NSLog("Montage video import: received imported copy")
        return withContext(Dispatchers.Default) { importUrl(url) }
    }

    private fun importUrl(url: NSURL): VideoAsset {
        val scoped = url.startAccessingSecurityScopedResource()
        val id = NSUUID().UUIDString
        val partial = "$directory/$id.part"
        val destination = "$directory/$id.media"
        try {
            check(manager.createDirectoryAtPath(directory, true, null, null)) { "Cannot create video directory" }
            // asCopy=true already copied the user's original. Move that app-owned copy to avoid a second large copy.
            check(manager.moveItemAtURL(url, NSURL.fileURLWithPath(partial), null)) { "Cannot stage imported video" }
            NSLog("Montage video import: staged imported copy")
            val asset = VideoAsset(id, url.lastPathComponent ?: "Imported video", byteSize(partial))
            check(manager.moveItemAtPath(partial, destination, null)) { "Cannot commit video" }
            NSLog("Montage video import: committed managed copy")
            return asset
        } finally {
            if (manager.fileExistsAtPath(partial)) manager.removeItemAtPath(partial, null)
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    private fun byteSize(path: String): Long {
        val attributes = manager.attributesOfItemAtPath(path, null) ?: return -1
        if (attributes[NSFileType] != NSFileTypeRegular) return -1
        return (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: -1
    }

    override fun isAvailable(asset: VideoAsset) = byteSize("$directory/${asset.id}.media") == asset.byteSize

    override fun removeUnreferenced(retained: List<VideoAsset>) {
        if (!manager.fileExistsAtPath(directory)) return
        val keep = retained.map { "${it.id}.media" }.toSet()
        val names = checkNotNull(manager.contentsOfDirectoryAtPath(directory, null)) { "Cannot read video directory" }
        names.filterIsInstance<String>().filter {
            it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(media|part)")) &&
                it !in keep && byteSize("$directory/$it") >= 0
        }
            .forEach { check(manager.removeItemAtPath("$directory/$it", null)) { "Cannot remove unused video" } }
    }
}
