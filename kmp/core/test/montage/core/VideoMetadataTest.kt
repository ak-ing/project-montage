// SPDX-License-Identifier: Apache-2.0
package montage.core

import kotlin.test.*

class VideoMetadataTest {
    @Test fun preservesEncodedDimensionsAndComputesRotatedDisplaySize() {
        val video = VideoMetadata(6000, 320, 180, 90, true)
        assertEquals(320, video.width)
        assertEquals(180, video.displayWidth)
        assertEquals(320, video.displayHeight)
        assertEquals(320, video.copy(rotationDegrees = 180).displayWidth)
        assertEquals(320, video.copy(rotationDegrees = null).displayWidth)
    }
    @Test fun seekIsBoundedByTheActualMediaDuration() {
        val video = VideoMetadata(6000, 320, 180, 0, false)
        assertEquals(0, video.constrainPosition(-1))
        assertEquals(3000, video.constrainPosition(3000))
        assertEquals(6000, video.constrainPosition(Long.MAX_VALUE))
    }
    @Test fun rejectsInvalidMetadataWithoutHidingUnknownOrientation() {
        val valid = VideoMetadata(1, 1, 1, null, false)
        assertNull(valid.rotationDegrees)
        assertFailsWith<IllegalArgumentException> { valid.copy(durationMillis = 0) }
        assertFailsWith<IllegalArgumentException> { valid.copy(width = 0) }
        assertFailsWith<IllegalArgumentException> { valid.copy(height = -1) }
        assertFailsWith<IllegalArgumentException> { valid.copy(rotationDegrees = 45) }
    }
}
