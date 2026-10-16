// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import montage.core.*
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files

class FileWorkspaceStorageTest {
    @Test fun restoresSavedDraftFromNewRepositoryAndReplacesExistingFile() {
        val directory = Files.createTempDirectory("montage-storage-")
        try {
            val file = directory.resolve("nested/workspace.json")
            val repository = WorkspaceRepository(FileWorkspaceStorage(file))
            val original = repository.load()
            repository.save(original)
            val edited = original.copy(draft = original.draft.copy(title = "中文 / العربية"))
            repository.save(edited)
            assertEquals(edited, WorkspaceRepository(FileWorkspaceStorage(file)).load())
            Files.list(file.parent).use { assertEquals(1L, it.count()) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun damagedFileIsKeptForRecovery() {
        val directory = Files.createTempDirectory("montage-damaged-")
        try {
            val file = directory.resolve("workspace.json")
            Files.writeString(file, "damaged")
            val repository = WorkspaceRepository(FileWorkspaceStorage(file))
            assertThrows(Exception::class.java) { repository.load() }
            assertThrows(IllegalStateException::class.java) { repository.save(WorkspaceSnapshot(sampleProject())) }
            assertEquals("damaged", Files.readString(file))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
