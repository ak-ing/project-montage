// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import montage.core.WorkspaceStorage
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class FileWorkspaceStorage(private val file: Path) : WorkspaceStorage {
    override fun read(): String? = if (Files.notExists(file)) null else Files.readString(file)

    override fun writeAtomically(document: String) {
        val directory = file.toAbsolutePath().parent
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".workspace-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use {
                it.write(document.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            // Fail safely on filesystems without atomic replacement; never truncate the old document.
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

fun defaultWorkspaceFile(): Path {
    System.getProperty("montage.dataDir")?.let { return Path.of(it, "workspace.json") }
    val userDirectory = System.getProperty("user.home")
    val directory = when {
        System.getProperty("os.name").startsWith("Windows") ->
            Path.of(System.getenv("LOCALAPPDATA") ?: "$userDirectory/AppData/Local", "ProjectMontage")
        System.getProperty("os.name").startsWith("Mac") ->
            Path.of(userDirectory, "Library", "Application Support", "ProjectMontage")
        else -> Path.of(System.getenv("XDG_DATA_HOME") ?: "$userDirectory/.local/share", "project-montage")
    }
    return directory.resolve("workspace.json")
}
