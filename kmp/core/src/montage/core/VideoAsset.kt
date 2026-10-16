// SPDX-License-Identifier: Apache-2.0
package montage.core

import kotlinx.serialization.Serializable

/** An app-owned video copy. The identifier is opaque; it is never a user-selected filesystem path. */
@Serializable
data class VideoAsset(val id: String, val name: String, val byteSize: Long) {
    init {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid video identifier" }
        require(name.isNotBlank() && name.length <= 255) { "Invalid video name" }
        require(byteSize > 0) { "Video must not be empty" }
    }
}

interface VideoLibrary {
    /** Presents a platform picker, then copies the selected video into app storage. Null means cancelled. */
    suspend fun pickAndImport(): VideoAsset?
    fun isAvailable(asset: VideoAsset): Boolean
    /** Called only after a successful workspace save or explicit discard/reload. */
    fun removeUnreferenced(retained: List<VideoAsset>)
}
