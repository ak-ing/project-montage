// SPDX-License-Identifier: Apache-2.0
import XCTest

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
        let file = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Montage-import-smoke")).firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 30), app.debugDescription)
        attach(app, "video-picker")
        file.tap()
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
}
