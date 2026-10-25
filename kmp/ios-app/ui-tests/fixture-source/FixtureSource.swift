// SPDX-License-Identifier: Apache-2.0
// Test-only source app. Create each final filename once from inside iOS;
// do not replace its inode while Files may already be indexing it.
import UIKit

@main
final class FixtureSource: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private let status = UILabel()
    private let names = ["Montage-import-smoke.mp4", "Montage-preview-smoke.mp4", "Montage-preview-invalid.mp4"]
    private var saved: [String] = []

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let controller = UIViewController()
        controller.view.backgroundColor = .systemBackground
        status.text = "Creating test source documents…"
        status.numberOfLines = 0
        status.frame = CGRect(x: 20, y: 100, width: 340, height: 150)
        controller.view.addSubview(status)
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = controller
        window?.makeKeyAndVisible()
        do {
            let manager = FileManager.default
            let documents = try manager.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            for name in names {
                let original = Bundle.main.bundleURL.appendingPathComponent("Fixtures").appendingPathComponent(name)
                let bytes = try Data(contentsOf: original)
                guard bytes.count <= 65536 else { report(error: "Fixture exceeds test-only byte limit"); return true }
                let destination = documents.appendingPathComponent(name)
                if manager.fileExists(atPath: destination.path) {
                    guard try Data(contentsOf: destination) == bytes else { report(error: "Existing fixture differs: " + name); return true }
                } else {
                    try bytes.write(to: destination, options: .withoutOverwriting)
                }
                saved.append(name)
            }
            report(error: nil)
        } catch { report(error: String(describing: error)) }
        return true
    }

    private func report(error: String?) {
        status.text = error ?? "Three source documents ready"
        let result: [String: Any] = [
            "status": error == nil ? "ready" : "failed",
            "creation": "Data.write(.withoutOverwriting)",
            "saved_documents": saved,
            "error": error ?? "",
        ]
        do {
            let cache = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys])
                .write(to: cache.appendingPathComponent("montage-fixtures.json"), options: .atomic)
        } catch { NSLog("Montage fixture source report failed: %@", String(describing: error)) }
    }
}
