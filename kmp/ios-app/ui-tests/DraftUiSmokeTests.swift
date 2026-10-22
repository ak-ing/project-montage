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
        let intro = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Speed up your typing")).firstMatch
        if intro.waitForExistence(timeout: 3) {
            app.buttons["Continue"].tap()
        }
    }

    private func typeSlowly(_ field: XCUIElement, _ value: String) {
        // Hosted simulators can drop a burst of synthetic keystrokes while composing.
        for character in value {
            field.typeText(String(character))
            Thread.sleep(forTimeInterval: 0.1)
        }
    }

    func testImportVideoCancelSaveRestoreAndRemove() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "dev.montage.app")
        openApp(app)
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        app.buttons["import-video"].tap()
        let cancel = app.buttons["Cancel"].firstMatch
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
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "remove-video-")).firstMatch.tap()
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
        let file = app.cells.matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(file.isEnabled)
        if capture { attach(app, "video-picker") }
        let filename = file.staticTexts[prefix]
        XCTAssertTrue(filename.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(filename.frame.isEmpty)
        // The simulator's Files grid can route a tap to either the caption or the
        // cell gesture depending on whether its remote provider has just loaded.
        // Use bounded alternatives, then require the picker to dismiss below.
        let activation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: file)
        filename.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        if XCTWaiter.wait(for: [activation], timeout: 4) != .completed {
            let open = app.buttons["Open"].firstMatch
            if open.waitForExistence(timeout: 1) { open.tap() }
        }
        if file.exists {
            attach(app, "picker-activation-retry-" + prefix)
            file.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            let retry = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: file)
            _ = XCTWaiter.wait(for: [retry], timeout: 4)
        }
        if file.exists {
            let thumbnail = file.images.firstMatch
            XCTAssertTrue(thumbnail.exists, app.debugDescription)
            thumbnail.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).doubleTap()
            let open = app.buttons["Open"].firstMatch
            if open.waitForExistence(timeout: 3) { open.tap() }
        }
        attach(app, "picker-after-selection-" + prefix)
        let dismissed = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: file)
        wait(for: [dismissed], timeout: 15)
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

    func testVideoPreviewPlaybackSeekAndRestart() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "dev.montage.app")
        openApp(app)
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        importVideo(app, "Montage-preview-smoke")
        let preview = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "preview-video-")).firstMatch
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
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "remove-video-")).firstMatch.tap()
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
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "remove-video-")).firstMatch.tap()
        app.buttons["save-draft"].tap()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["No videos imported"].exists)
    }
}
