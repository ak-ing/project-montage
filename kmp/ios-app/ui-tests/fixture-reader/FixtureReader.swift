// SPDX-License-Identifier: Apache-2.0
// Test-only UIKit control: verify actual Files URLs before testing the KMP app.
import UIKit
import UniformTypeIdentifiers
import CryptoKit

@main
final class FixtureReader: UIResponder, UIApplicationDelegate, UIDocumentPickerDelegate {
    var window: UIWindow?
    private let controller = UIViewController()
    private let status = UILabel()
    private var reads: [String: String] = [:]
    private var cancellations = 0
    private let expected = [
        "Montage-import-smoke.mp4": "af138040b22dd4b34cdeab13b2ae036605551e79351602dfc02fefd21b9dc086",
        "Montage-preview-smoke.mp4": "e894398d60b53b9370143cba19da5d35b61d72393e55b984bf3f88a98af26c1a",
        "Montage-preview-invalid.mp4": "2414a664d6f3f40086ab33737090f7ba661db4dbfda3a19f54550bb3059044e2",
    ]

    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        controller.view.backgroundColor = .systemBackground
        status.frame = CGRect(x: 20, y: 100, width: 350, height: 120)
        status.numberOfLines = 0
        status.accessibilityIdentifier = "native-picker-status"
        controller.view.addSubview(status)
        let pick = UIButton(type: .system)
        pick.frame = CGRect(x: 20, y: 250, width: 250, height: 60)
        pick.setTitle("Open Files picker", for: .normal)
        pick.accessibilityIdentifier = "native-pick-video"
        pick.addTarget(self, action: #selector(openPicker), for: .touchUpInside)
        controller.view.addSubview(pick)
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = controller
        window?.makeKeyAndVisible()
        report("Ready")
        return true
    }

    @objc private func openPicker() {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.movie], asCopy: true)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        controller.present(picker, animated: true)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        cancellations += 1
        controller.dismiss(animated: true)
        report("Cancelled")
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        controller.dismiss(animated: true)
        do {
            guard let url = urls.first, expected[url.lastPathComponent] != nil else { report("Failed", error: "Unexpected source"); return }
            // Never remove or modify the external original, even if import semantics change.
            guard url.standardizedFileURL.path.hasPrefix(NSHomeDirectory() + "/") else { report("Failed", error: "Not an app-owned picker copy"); return }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size > 0 && size <= 65536 else { report("Failed", error: "Invalid bounded fixture size"); return }
            let bytes = try Data(contentsOf: url)
            let hash = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
            guard hash == expected[url.lastPathComponent] else { report("Failed", error: "Fixture hash differs"); return }
            reads[url.lastPathComponent] = hash
            try FileManager.default.removeItem(at: url)
            report("Read " + url.lastPathComponent)
        } catch { report("Failed", error: String(describing: error)) }
    }

    private func report(_ message: String, error: String? = nil) {
        status.text = message
        let result: [String: Any] = ["status": message, "cancelled": cancellations, "reads": reads, "error": error ?? ""]
        do {
            // Keep required evidence in durable app storage, not a purgeable cache.
            let directory = URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support/MontageProbe", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let target = directory.appendingPathComponent("native-picker-control.json")
            try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys])
                .write(to: target, options: .atomic)
            NSLog("Montage native picker control: %@ report=%@", message, target.path)
        } catch {
            status.text = "Failed to save probe report"
            NSLog("Montage native picker control: report failed: %@", String(describing: error))
        }
    }
}
