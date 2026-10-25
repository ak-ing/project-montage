// SPDX-License-Identifier: Apache-2.0
import XCTest
import CoreGraphics

final class DraftUiSmokeTests: XCTestCase {
    private func openApp(_ app: XCUIApplication) {
        if app.state == .notRunning {
            app.launch()
        } else {
            app.activate()
        }
    }

    func testEditAppendAndResetDraft() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "dev.montage.app")
        openApp(app)
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30), app.debugDescription)
        let initial = app.staticTexts["2 scenes · 10s visible · 1s transition buffer"]
        XCTAssertTrue(initial.waitForExistence(timeout: 30), app.debugDescription)
        let add = app.buttons["add-scene"]
        XCTAssertTrue(add.exists, app.debugDescription)
        XCTAssertFalse(add.isEnabled)
        attach(app, "initial")

        let title = app.textViews["project-title"]
        XCTAssertTrue(title.exists, app.debugDescription)
        title.tap()
        dismissKeyboardIntroduction(app)
        typeSlowly(title, " UI smoke title")
        XCTAssertTrue((title.value as? String)?.contains("UI smoke title") == true, app.debugDescription)
        let description = app.textViews["scene-description"]
        XCTAssertTrue(description.exists, app.debugDescription)
        description.tap()
        typeSlowly(description, "Smoke scene")
        XCTAssertTrue(add.isEnabled)
        add.tap()
        XCTAssertTrue(app.staticTexts["3 scenes · 14s visible · 2s transition buffer"].waitForExistence(timeout: 10))
        XCTAssertFalse(add.isEnabled)
        attach(app, "added")

        app.buttons["save-draft"].tap()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        app.terminate()
        openApp(app)
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["3 scenes · 14s visible · 2s transition buffer"].exists)
        XCTAssertTrue((app.textViews["project-title"].value as? String)?.contains("UI smoke title") == true)
        XCTAssertTrue(app.staticTexts["Smoke scene"].exists)
        attach(app, "restored")

        app.buttons["reset-sample"].tap()
        XCTAssertTrue(initial.waitForExistence(timeout: 10))
        XCTAssertEqual(title.value as? String, "My first Montage")
        XCTAssertFalse(app.staticTexts["Smoke scene"].exists)
        attach(app, "reset")
        app.buttons["save-draft"].tap()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func dismissKeyboardIntroduction(_ app: XCUIApplication) {
        // A fresh simulator may present Apple's first-use QuickPath introduction.
        let intro = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Speed up your typing")).element(boundBy: 0)
        if intro.waitForExistence(timeout: 3) {
            app.buttons["Continue"].tap()
        }
    }

    private func typeSlowly(_ field: XCUIElement, _ value: String) {
        // Hosted simulators can drop a burst of synthetic keystrokes while composing.
        for character in value {
            field.typeText(String(character))
            Thread.sleep(forTimeInterval: 0.2)
        }
    }

    func testImportVideoCancelSaveRestoreAndRemove() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "dev.montage.app")
        openApp(app)
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        app.buttons["import-video"].tap()
        let cancel = app.buttons.matching(identifier: "Cancel").element(boundBy: 0)
        XCTAssertTrue(cancel.waitForExistence(timeout: 30), app.debugDescription)
        cancel.tap()
        let cancelledPicker = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.collectionViews["File View"])
        wait(for: [cancelledPicker], timeout: 15)
        XCTAssertTrue(app.staticTexts["Import cancelled"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["save-draft"].isEnabled)

        app.buttons["import-video"].tap()
        selectDocument(app, "Montage-import-smoke", capture: true)
        XCTAssertTrue(app.staticTexts["Available on this device"].waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(app.staticTexts["Montage-import-smoke.mp4"].exists)
        app.buttons["save-draft"].tap()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        app.terminate()
        openApp(app)
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        app.buttons["video-tab"].tap()
        XCTAssertTrue(app.staticTexts["Available on this device"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["Montage-import-smoke.mp4"].exists)
        attach(app, "videos-restored")
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "remove-video-")).element(boundBy: 0).tap()
        app.buttons["save-draft"].tap()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["No videos imported"].exists)
        attach(app, "videos-removed")
    }

    private func importVideo(_ app: XCUIApplication, _ prefix: String) {
        app.buttons["import-video"].tap()
        selectDocument(app, prefix)
        XCTAssertTrue(app.staticTexts[prefix + ".mp4"].waitForExistence(timeout: 30), app.debugDescription)
    }

    private func selectDocument(_ app: XCUIApplication, _ prefix: String, capture: Bool = false) {
        // Source documents are published atomically inside a separate fixture app.
        // Select them through the real provider; the app under test only imports.
        navigateToFixtureFolder(app)
        attach(app, "picker-folder-resolved-" + prefix)
        let file = app.cells.matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).element(boundBy: 0)
        XCTAssertTrue(file.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(file.isEnabled)
        if capture { attach(app, "video-picker") }
        let filename = file.staticTexts[prefix]
        XCTAssertTrue(filename.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(filename.frame.isEmpty)
        file.tap()
        let open = app.buttons.matching(identifier: "Open").element(boundBy: 0)
        if open.waitForExistence(timeout: 3) { open.tap() }
        attach(app, "picker-after-selection-" + prefix)
        let dismissed = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: file)
        wait(for: [dismissed], timeout: 15)
    }

    private func navigateToFixtureFolder(_ app: XCUIApplication) {
        let folderTitle = app.buttons["Montage smoke fixtures, Actions Menu"]
        let folder = app.cells.matching(NSPredicate(format: "label BEGINSWITH %@", "Montage smoke fixtures")).element(boundBy: 0)
        let location = app.cells["DOC.sidebar.item.On My iPhone"]
        let parent = app.buttons.matching(identifier: "BackButton").element(boundBy: 0)
        let browse = app.buttons.matching(NSPredicate(
            format: "label == %@ AND identifier != %@", "Browse", "BackButton"
        )).element(boundBy: 0)
        // A verified restored destination is already usable. At every step,
        // wait for current native navigation state rather than a vanished row.
        for _ in 0..<6 {
            let ready = app.descendants(matching: .any).matching(NSPredicate(
                format: "identifier IN %@ OR label BEGINSWITH %@ OR label == %@ OR label == %@",
                ["Montage smoke fixtures, Actions Menu", "DOC.sidebar.item.On My iPhone", "BackButton"],
                "Montage smoke fixtures", "Browse", "On My iPhone"
            )).element(boundBy: 0)
            XCTAssertTrue(ready.waitForExistence(timeout: 60), app.debugDescription)
            if folderTitle.exists { return }
            let phoneRoot = app.navigationBars.staticTexts.matching(identifier: "On My iPhone").element(boundBy: 0)
            if phoneRoot.exists {
                XCTAssertTrue(folder.waitForExistence(timeout: 60), app.debugDescription)
                folder.tap()
                continue
            }
            if folder.exists { folder.tap(); continue }
            if location.exists { location.tap(); continue }
            if parent.exists { parent.tap(); continue }
            browse.tap()
        }
        XCTAssertTrue(folderTitle.waitForExistence(timeout: 30), app.debugDescription)
    }

    private func assertPreviewReady(_ app: XCUIApplication) {
        let metadata = app.staticTexts["video-metadata"]
        XCTAssertTrue(metadata.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(metadata.label.contains("8000 ms"), metadata.label)
        XCTAssertTrue(metadata.label.contains("160 × 90"), metadata.label)
        XCTAssertTrue(metadata.label.contains("0°"), metadata.label)
        XCTAssertTrue(metadata.label.contains("Audio"), metadata.label)
        XCTAssertTrue(app.descendants(matching: .any)["video-thumbnail"].exists)
        XCTAssertTrue(app.staticTexts["Paused"].waitForExistence(timeout: 10))
    }

    private func previewPosition(_ app: XCUIApplication) -> Int {
        Int(app.staticTexts["video-position"].label.components(separatedBy: "/")[0].trimmingCharacters(in: .whitespaces)) ?? -1
    }

    func testFileProviderNativeControl() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "dev.montage.fixture-reader")
        app.launch()
        XCTAssertTrue(app.staticTexts["native-picker-status"].waitForExistence(timeout: 15))
        app.buttons["native-pick-video"].tap()
        let cancel = app.buttons.matching(identifier: "Cancel").element(boundBy: 0)
        XCTAssertTrue(cancel.waitForExistence(timeout: 30))
        cancel.tap()
        XCTAssertTrue(app.staticTexts["Cancelled"].waitForExistence(timeout: 15))
        let closed = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.collectionViews["File View"])
        wait(for: [closed], timeout: 15)
        for name in ["Montage-import-smoke", "Montage-preview-smoke", "Montage-preview-invalid"] {
            app.buttons["native-pick-video"].tap()
            selectDocument(app, name)
            XCTAssertTrue(app.staticTexts["Read " + name + ".mp4"].waitForExistence(timeout: 15), app.debugDescription)
        }
        attach(app, "native-picker-control-ready")
        app.terminate()
    }

    private func editorTap(_ app: XCUIApplication, _ identifier: String, scrollDown: Bool = false) {
        let button = app.buttons[identifier]
        let editor = app.descendants(matching: .any)["scene-editor-scroll"]
        let list = app.descendants(matching: .any)["workspace-list"]
        for _ in 0..<5 {
            if button.exists && button.isHittable { button.tap(); return }
            let scroll = editor.exists ? editor : list
            XCTAssertTrue(scroll.exists, app.debugDescription)
            if scrollDown { scroll.swipeDown() } else { scroll.swipeUp() }
        }
        XCTFail("Editor button not reachable: " + identifier + "\n" + app.debugDescription)
    }

    private func replaceNumber(_ app: XCUIApplication, _ identifier: String, _ value: String) {
        // Compose exposes these editable fields as TextView on iOS, including single-line fields.
        let field = app.textViews[identifier]
        XCTAssertTrue(field.waitForExistence(timeout: 10), app.debugDescription)
        field.tap()
        let existing = field.value as? String ?? ""
        typeSlowly(field, String(repeating: XCUIKeyboardKey.delete.rawValue, count: existing.count) + value)
    }

    func testSceneClipEditingAndRestart() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "dev.montage.app")
        openApp(app)
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        importVideo(app, "Montage-preview-smoke")
        app.buttons["scene-tab"].tap()
        app.buttons["edit-scene-opening"].tap()
        XCTAssertTrue(app.buttons["choose-scene-video"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["move-scene-up"].isEnabled)
        app.buttons["choose-scene-video"].tap()
        let video = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "clip-video-")).element(boundBy: 0)
        XCTAssertTrue(video.waitForExistence(timeout: 10), app.debugDescription)
        video.tap()
        XCTAssertTrue(app.staticTexts["clip-source-duration"].waitForExistence(timeout: 30))
        XCTAssertEqual(app.staticTexts["clip-source-duration"].label, "8000 ms available")
        let defaultRange = expectation(for: NSPredicate(format: "value == %@", "8000"), evaluatedWith: app.textViews["clip-out"])
        wait(for: [defaultRange], timeout: 10)
        replaceNumber(app, "clip-out", "9000")
        XCTAssertFalse(app.buttons["apply-scene-clip"].isEnabled)
        XCTAssertTrue(app.staticTexts["clip-range-error"].exists)
        replaceNumber(app, "clip-in", "1250")
        replaceNumber(app, "clip-out", "6500")
        editorTap(app, "apply-scene-clip")
        editorTap(app, "move-scene-down", scrollDown: true)
        XCTAssertEqual(app.staticTexts["scene-editor-position"].label, "Scene 2")
        editorTap(app, "scene-editor-back", scrollDown: true)
        XCTAssertTrue(app.staticTexts["Unsaved changes"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["edited-duration"].label, "9250 ms edited sequence · transitions not rendered")
        app.buttons["video-tab"].tap()
        let remove = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "remove-video-")).element(boundBy: 0)
        XCTAssertTrue(remove.exists)
        XCTAssertFalse(remove.isEnabled)
        app.buttons["save-draft"].tap()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        app.terminate()
        app.launch()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        // Reordered scene retains its stable identity and exact millisecond edit.
        editorTap(app, "edit-scene-opening")
        XCTAssertTrue(app.staticTexts["clip-source-duration"].waitForExistence(timeout: 30))
        XCTAssertEqual(app.staticTexts["scene-editor-position"].label, "Scene 2")
        XCTAssertEqual(app.textViews["clip-in"].value as? String, "1250")
        XCTAssertEqual(app.textViews["clip-out"].value as? String, "6500")
        XCTAssertEqual(app.staticTexts["scene-editor-video"].label, "Montage-preview-smoke.mp4")
        attach(app, "scene-edits-restored")
        editorTap(app, "detach-scene-video")
        editorTap(app, "scene-editor-back", scrollDown: true)
        app.buttons["save-draft"].tap()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        app.buttons["video-tab"].tap()
        XCTAssertTrue(remove.isEnabled)
        remove.tap()
        app.buttons["save-draft"].tap()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["No videos imported"].exists)
        app.buttons["scene-tab"].tap()
        app.buttons["reset-sample"].tap()
        app.buttons["save-draft"].tap()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
    }

    func testVideoPreviewPlaybackSeekAndRestart() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "dev.montage.app")
        openApp(app)
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        importVideo(app, "Montage-preview-smoke")
        let preview = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "preview-video-")).element(boundBy: 0)
        XCTAssertTrue(preview.waitForExistence(timeout: 10))
        preview.tap()
        assertPreviewReady(app)
        attach(app, "preview-ready")
        app.buttons["play-video"].tap()
        XCTAssertTrue(app.staticTexts["Playing"].waitForExistence(timeout: 5))
        Thread.sleep(forTimeInterval: 1)
        app.buttons["pause-video"].tap()
        XCTAssertTrue(app.staticTexts["Paused"].waitForExistence(timeout: 5))
        XCTAssertGreaterThan(previewPosition(app), 300)
        let slider = app.descendants(matching: .any)["video-seek"]
        XCTAssertTrue(slider.exists, app.debugDescription)
        // Compose exposes an adjustable Other element rather than UIKit's UISlider.
        let currentFraction = max(0.02, min(0.98, Double(previewPosition(app)) / 8000.0))
        slider.coordinate(withNormalizedOffset: CGVector(dx: currentFraction, dy: 0.5))
            .press(forDuration: 0.2, thenDragTo: slider.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.5)))
        let sought = expectation(for: NSPredicate { _, _ in self.previewPosition(app) >= 5000 }, evaluatedWith: app.staticTexts["video-position"])
        wait(for: [sought], timeout: 10)
        XCTAssertLessThanOrEqual(previewPosition(app), 7500)
        attach(app, "preview-seek")
        app.buttons["preview-back"].tap()
        XCTAssertTrue(app.staticTexts["Unsaved changes"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Montage-preview-smoke.mp4"].exists)
        app.buttons["save-draft"].tap()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        app.terminate()
        app.launch()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        app.buttons["video-tab"].tap()
        preview.tap()
        assertPreviewReady(app)
        attach(app, "preview-restored")
        // Termination while a preview is open must not retain its temporary alias.
        app.terminate()
        app.launch()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        app.buttons["video-tab"].tap()
        preview.tap()
        assertPreviewReady(app)
        attach(app, "preview-terminated-restored")
        app.buttons["preview-back"].tap()
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "remove-video-")).element(boundBy: 0).tap()
        app.buttons["save-draft"].tap()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))

        importVideo(app, "Montage-preview-invalid")
        preview.tap()
        XCTAssertTrue(app.staticTexts["preview-error"].waitForExistence(timeout: 30), app.debugDescription)
        attach(app, "preview-error")
        app.buttons["preview-retry"].tap()
        XCTAssertTrue(app.staticTexts["preview-error"].waitForExistence(timeout: 30))
        app.buttons["preview-back"].tap()
        XCTAssertTrue(app.staticTexts["Montage-preview-invalid.mp4"].exists)
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "remove-video-")).element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Unsaved changes"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["save-draft"].isEnabled)
        app.buttons["save-draft"].tap()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["No videos imported"].exists)
    }
}
