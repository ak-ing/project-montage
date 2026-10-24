// SPDX-License-Identifier: Apache-2.0
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import montage.core.VideoAsset
import montage.core.VideoLibrary
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.UTTypeMovie
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
        // UIKit can invoke completion before the remote picker view leaves its window.
        controller.dismissViewControllerAnimated(true) {
            CoroutineScope(continuation.context).launch {
                try {
                    withTimeout(5_000) {
                        while (controller.view.window != null) delay(16)
                    }
                    NSLog("Montage video import: picker view detached")
                    if (continuation.isActive) continuation.resume(url)
                } catch (_: TimeoutCancellationException) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Video picker did not close"))
                } catch (cancelled: CancellationException) { throw cancelled }
            }
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
                // Keep Files' normal location/provider selection. A raw URL into
                // this app's own container can retain invalid provider IDs after
                // an installation/container migration.
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
        val managedName = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(media|part)")
        fun entries(): List<NSURL> = checkNotNull(manager.contentsOfDirectoryAtURL(
            NSURL.fileURLWithPath(directory), null, 0u, null,
        )) { "Cannot read video directory" }.map { it as NSURL }
        var removed = 0
        for (url in entries()) {
            val name = url.lastPathComponent ?: continue
            if (!managedName.matches(name) || name in keep) continue
            val attributes = checkNotNull(manager.attributesOfItemAtPath(checkNotNull(url.path), null)) {
                "Cannot inspect unused video"
            }
            val type = attributes[NSFileType]
            // Remove a managed link itself, never its destination; preserve unexpected directories.
            check(type == NSFileTypeRegular || type == NSFileTypeSymbolicLink) { "Unexpected managed video entry" }
            check(manager.removeItemAtURL(url, null)) { "Cannot remove unused video" }
            removed++
        }
        val remaining = entries().count { url ->
            val name = url.lastPathComponent ?: ""
            managedName.matches(name) && name !in keep
        }
        NSLog("Montage video cleanup: removed=$removed retained=${keep.size} unreferenced=$remaining")
        check(remaining == 0) { "Unused video copies remain" }
    }
}
