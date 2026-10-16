// SPDX-License-Identifier: Apache-2.0
package montage.core

/** Derived from the local media, never trusted from a saved project or used as a file path. */
data class VideoMetadata(
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int?,
    val hasAudio: Boolean,
) {
    init {
        require(durationMillis > 0 && width > 0 && height > 0) { "Invalid video metadata" }
        require(rotationDegrees == null || rotationDegrees in setOf(0, 90, 180, 270)) { "Invalid video rotation" }
    }
    val displayWidth get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
    val displayHeight get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
    fun constrainPosition(positionMillis: Long) = positionMillis.coerceIn(0, durationMillis)
}
