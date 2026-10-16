// SPDX-License-Identifier: Apache-2.0
package montage.android

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import montage.core.VideoAsset
import montage.core.VideoLibrary
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume

class AndroidVideoLibrary(activity: ComponentActivity) : VideoLibrary {
    private val resolver = activity.applicationContext.contentResolver
    private val directory = File(activity.filesDir, "media")
    private var pending: CancellableContinuation<Uri?>? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pending?.let { if (it.isActive) it.resume(uri) }
        pending = null
    }

    override suspend fun pickAndImport(): VideoAsset? {
        val uri = withContext(Dispatchers.Main) { suspendCancellableCoroutine<Uri?> { continuation ->
            check(pending == null) { "Video picker is already open" }
            pending = continuation
            continuation.invokeOnCancellation { pending = null }
            try {
                picker.launch(arrayOf("video/*"))
            } catch (failure: Exception) {
                pending = null
                throw failure
            }
        } } ?: return null
        return withContext(Dispatchers.IO) { importUri(uri) }
    }

    private fun importUri(uri: Uri): VideoAsset {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Imported video"
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create video directory" }
        val id = UUID.randomUUID().toString()
        val partial = File(directory, "$id.part")
        val destination = File(directory, "$id.media")
        try {
            checkNotNull(resolver.openInputStream(uri)) { "Cannot open selected video" }.use { input ->
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

    override fun isAvailable(asset: VideoAsset) = File(directory, "${asset.id}.media").let {
        it.isFile && it.canRead() && it.length() == asset.byteSize
    }

    override fun removeUnreferenced(retained: List<VideoAsset>) {
        if (!directory.exists()) return
        val keep = retained.map { "${it.id}.media" }.toSet()
        checkNotNull(directory.listFiles()) { "Cannot read video directory" }
            .filter { it.isFile && it.name.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(media|part)")) && it.name !in keep }
            .forEach { check(it.delete()) { "Cannot remove unused video" } }
    }
}
