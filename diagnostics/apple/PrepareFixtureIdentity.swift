// SPDX-License-Identifier: Apache-2.0
// Diagnostic source-publication experiment, never linked into the product.
import Foundation
import Darwin

func prepareFixtureIdentity(_ url: URL) throws -> NSNumber {
    // Only the fixture source calls this on its own newly published, bounded
    // files. Preserve the inode and contents; establish and flush document-ID
    // tracking before another process enumerates the source directory.
    let descriptor = Darwin.open(url.path, O_RDWR | O_NOFOLLOW)
    guard descriptor >= 0 else {
        throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno), userInfo: nil)
    }
    defer { Darwin.close(descriptor) }
    var attributes = Darwin.stat()
    guard Darwin.fstat(descriptor, &attributes) == 0,
          Darwin.fchflags(descriptor, attributes.st_flags | UInt32(UF_TRACKED)) == 0,
          Darwin.fsync(descriptor) == 0 else {
        throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno), userInfo: nil)
    }
    let fresh = URL(fileURLWithPath: url.path)
    let values = try fresh.resourceValues(forKeys: [.documentIdentifierKey])
    guard let identifier = values.documentIdentifier as? NSNumber, identifier.uint64Value > 0 else {
        throw NSError(domain: "MontageFixtureIdentity", code: 1,
            userInfo: [NSLocalizedDescriptionKey: "No document identifier after tracking " + url.lastPathComponent])
    }
    return identifier
}
