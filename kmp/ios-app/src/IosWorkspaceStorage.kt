// SPDX-License-Identifier: Apache-2.0
import kotlinx.cinterop.ExperimentalForeignApi
import montage.core.WorkspaceStorage
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class)
class IosWorkspaceStorage : WorkspaceStorage {
    private val manager = NSFileManager.defaultManager
    private val directory: String = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
        .first() as NSURL).path!! + "/ProjectMontage"
    private val path = "$directory/workspace.json"

    override fun read(): String? {
        if (!manager.fileExistsAtPath(path)) return null
        return checkNotNull(NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)) {
            "Cannot read workspace"
        }
    }

    override fun writeAtomically(document: String) {
        check(manager.createDirectoryAtPath(directory, true, null, null)) { "Cannot create workspace directory" }
        check(NSString.create(string = document).writeToFile(path, true, NSUTF8StringEncoding, null)) {
            "Cannot save workspace"
        }
    }
}
