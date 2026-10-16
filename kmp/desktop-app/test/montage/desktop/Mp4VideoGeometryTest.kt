// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import org.junit.Test
import kotlin.test.*
import java.io.File
import java.nio.file.Files

class Mp4VideoGeometryTest {
    @Test fun readsVideoGeometryFromManagedCopyWithOpaqueExtension() {
        val copy = Files.createTempFile("geometry-", ".media").toFile()
        try {
            File("../test-data/preview-smoke.mp4").copyTo(copy, overwrite = true)
            assertEquals(Mp4VideoGeometry(160, 90, 0), Mp4VideoGeometry.read(copy))
        } finally { copy.delete() }
    }
    @Test fun malformedAtomSizesFailWithoutAllocatingFromUntrustedLengths() {
        val copy = Files.createTempFile("geometry-invalid-", ".media").toFile()
        try {
            for (data in listOf(byteArrayOf(0, 0, 0), byteArrayOf(-1, -1, -1, -1, 109, 111, 111, 118),
                byteArrayOf(0, 0, 0, 1, 109, 111, 111, 118, -1, -1, -1, -1, -1, -1, -1, -1))) {
                copy.writeBytes(data)
                assertNull(Mp4VideoGeometry.read(copy))
            }
        } finally { copy.delete() }
    }
}
