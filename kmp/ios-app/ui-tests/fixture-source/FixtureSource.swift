// SPDX-License-Identifier: Apache-2.0
// Test-only source app. Let the system export picker create provider-owned
// documents; importing through the product's picker remains a separate flow.
import UIKit

@main
final class FixtureSource: UIResponder, UIApplicationDelegate, UIDocumentPickerDelegate {
    var window: UIWindow?
    private let status = UILabel()
    private let export = UIButton(type: .system)
    private let names = ["Montage-import-smoke.mp4", "Montage-preview-smoke.mp4", "Montage-preview-invalid.mp4"]

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let controller = UIViewController()
        controller.view.backgroundColor = .systemBackground
        status.numberOfLines = 0
        status.accessibilityIdentifier = "fixture-status"
        status.frame = CGRect(x: 20, y: 100, width: 340, height: 150)
        controller.view.addSubview(status)
        export.setTitle("Export three fixtures", for: .normal)
        export.accessibilityIdentifier = "export-fixtures"
        export.frame = CGRect(x: 20, y: 260, width: 340, height: 50)
        export.addTarget(self, action: #selector(exportDocuments), for: .touchUpInside)
        controller.view.addSubview(export)
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = controller
        window?.makeKeyAndVisible()
        do {
            let documents = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            // Make the fixture app's Documents folder visible as a destination.
            try Data("CI source documents only.\n".utf8).write(to: documents.appendingPathComponent("Read me.txt"), options: .atomic)
            report(state: "awaiting_export", error: nil)
        } catch { report(state: "failed", error: String(describing: error)) }
        return true
    }

    @objc private func exportDocuments() {
        let sources = names.map { Bundle.main.bundleURL.appendingPathComponent("Fixtures").appendingPathComponent($0) }
        let picker = UIDocumentPickerViewController(forExporting: sources, asCopy: true)
        picker.delegate = self
        picker.directoryURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        window?.rootViewController?.present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        do {
            let documents = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: false)
            for name in names {
                let original = Bundle.main.bundleURL.appendingPathComponent("Fixtures").appendingPathComponent(name)
                guard try Data(contentsOf: documents.appendingPathComponent(name)) == Data(contentsOf: original) else {
                    report(state: "failed", error: "Exported bytes differ: " + name); return
                }
            }
            report(state: "ready", error: nil)
            export.isEnabled = false
        } catch { report(state: "failed", error: String(describing: error)) }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        report(state: "failed", error: "Fixture export cancelled")
    }

    private func report(state: String, error: String?) {
        let result: [String: Any] = [
            "status": state,
            "creation": "UIDocumentPicker.forExporting(asCopy:true)",
            "saved_documents": state == "ready" ? names : [],
            "error": error ?? "",
        ]
        status.text = state == "ready" ? "Source documents exported" : (error ?? "Export fixtures through Files")
        do {
            let cache = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys])
                .write(to: cache.appendingPathComponent("montage-fixtures.json"), options: .atomic)
        } catch { NSLog("Montage fixture source report failed: %@", String(describing: error)) }
    }
}
