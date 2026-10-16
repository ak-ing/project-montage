// SPDX-License-Identifier: Apache-2.0
package montage.android

import android.content.Context
import android.util.AtomicFile
import java.io.File
import montage.core.WorkspaceStorage

class AndroidWorkspaceStorage(context: Context) : WorkspaceStorage {
    private val file = File(context.filesDir, "workspace.json")
    private val atomicFile = AtomicFile(file)

    override fun read(): String? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override fun writeAtomically(document: String) {
        val stream = atomicFile.startWrite()
        try {
            stream.write(document.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (failure: Exception) {
            atomicFile.failWrite(stream)
            throw failure
        }
    }
}
