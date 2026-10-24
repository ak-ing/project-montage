// SPDX-License-Identifier: Apache-2.0
// Test-only source app. Create documents through iOS coordination rather than
// inserting files behind the simulator FileProvider's document-ID database.
import UIKit

private final class FixtureDocument: UIDocument {
    let bytes: Data

    init(url: URL, bytes: Data) {
        self.bytes = bytes
        super.init(fileURL: url)
    }

    override func contents(forType typeName: String) throws -> Any { bytes }
}

@main
final class FixtureSource: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private let status = UILabel()
    private let names = ["Montage-import-smoke.mp4", "Montage-preview-smoke.mp4", "Montage-preview-invalid.mp4"]
    private var saved: [String] = []

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let controller = UIViewController()
        controller.view.backgroundColor = .systemBackground
        status.text = "Creating coordinated test documents…"
        status.numberOfLines = 0
        status.frame = CGRect(x: 20, y: 100, width: 340, height: 150)
        controller.view.addSubview(status)
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = controller
        window?.makeKeyAndVisible()
        createNext(0)
        return true
    }

    private func createNext(_ index: Int) {
        guard index < names.count else { finish(error: nil); return }
        do {
            let name = names[index]
            let manager = FileManager.default
            let documents = try manager.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            let source = Bundle.main.bundleURL.appendingPathComponent("Fixtures").appendingPathComponent(name)
            let bytes = try Data(contentsOf: source)
            let destination = documents.appendingPathComponent(name)
            // Reopening this fixture source must preserve the original bytes.
            if manager.fileExists(atPath: destination.path) {
                guard try Data(contentsOf: destination) == bytes else {
                    finish(error: "Existing fixture differs: " + name); return
                }
                saved.append(name)
                createNext(index + 1)
                return
            }
            let document = FixtureDocument(url: destination, bytes: bytes)
            document.save(to: destination, for: .forCreating) { [self, document] success in
                guard success else { finish(error: "UIDocument save failed: " + name); return }
                document.close { [self] closed in
                    guard closed else { finish(error: "UIDocument close failed: " + name); return }
                    saved.append(name)
                    createNext(index + 1)
                }
            }
        } catch { finish(error: String(describing: error)) }
    }

    private func finish(error: String?) {
        let report: [String: Any] = [
            "status": error == nil ? "ready" : "failed",
            "creation": "UIDocument.save(.forCreating)",
            "saved_documents": saved,
            "error": error ?? "",
        ]
        status.text = error ?? "Three coordinated source documents ready"
        do {
            let cache = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys])
                .write(to: cache.appendingPathComponent("montage-fixtures.json"), options: .atomic)
        } catch { NSLog("Montage fixture source report failed: %@", String(describing: error)) }
    }
}
