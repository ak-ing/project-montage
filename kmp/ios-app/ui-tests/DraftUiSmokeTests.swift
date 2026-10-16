// SPDX-License-Identifier: Apache-2.0
import XCTest
import CoreGraphics

final class DraftUiSmokeTests: XCTestCase {
    func testEditAppendAndResetDraft() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "dev.montage.app")
        app.launch()
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
        title.typeText(" UI smoke title")
        XCTAssertTrue((title.value as? String)?.contains("UI smoke title") == true)
        let description = app.textViews["scene-description"]
        XCTAssertTrue(description.exists, app.debugDescription)
        description.tap()
        description.typeText("Smoke scene")
        XCTAssertTrue(add.isEnabled)
        add.tap()
        XCTAssertTrue(app.staticTexts["3 scenes · 14s visible · 2s transition buffer"].waitForExistence(timeout: 10))
        XCTAssertFalse(add.isEnabled)
        attach(app, "added")

        app.buttons["save-draft"].tap()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        app.terminate()
        app.launch()
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

    func testImportVideoCancelSaveRestoreAndRemove() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "dev.montage.app")
        app.launch()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        app.buttons["import-video"].tap()
        let cancel = app.buttons["Cancel"].firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 30), app.debugDescription)
        cancel.tap()
        XCTAssertTrue(app.staticTexts["Import cancelled"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["save-draft"].isEnabled)

        app.buttons["import-video"].tap()
        selectDocument(app, "Montage-import-smoke", capture: true)
        XCTAssertTrue(app.staticTexts["Available on this device"].waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(app.staticTexts["Montage-import-smoke.mp4"].exists)
        app.buttons["save-draft"].tap()
        XCTAssertTrue(app.staticTexts["No unsaved changes"].waitForExistence(timeout: 30))
        app.terminate()
        app.launch()
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
        let more = app.buttons["More"].firstMatch
        XCTAssertTrue(more.waitForExistence(timeout: 10), app.debugDescription)
        more.tap()
        let list = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "List")).firstMatch
        XCTAssertTrue(list.waitForExistence(timeout: 10), app.debugDescription)
        list.tap()
        let file = app.cells.matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(file.isEnabled)
        if capture { attach(app, "video-picker") }
        XCTAssertTrue(file.isHittable, app.debugDescription)
        file.tap()
        let open = app.buttons["Open"].firstMatch
        if open.waitForExistence(timeout: 3) { open.tap() }
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
        app.launch()
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
        let slider = app.sliders["video-seek"]
        XCTAssertTrue(slider.exists, app.debugDescription)
        slider.adjust(toNormalizedSliderPosition: 0.75)
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
