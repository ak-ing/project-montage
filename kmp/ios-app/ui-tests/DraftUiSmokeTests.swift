// SPDX-License-Identifier: Apache-2.0
import XCTest

final class DraftUiSmokeTests: XCTestCase {
    func testEditAppendAndResetDraft() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "dev.montage.app")
        app.launch()
        let initial = app.staticTexts["2 scenes · 10s visible · 1s transition buffer"]
        XCTAssertTrue(initial.waitForExistence(timeout: 30), app.debugDescription)
        let add = app.buttons["add-scene"]
        XCTAssertTrue(add.exists, app.debugDescription)
        XCTAssertFalse(add.isEnabled)
        attach(app, "initial")

        let title = app.textFields["project-title"]
        XCTAssertTrue(title.exists, app.debugDescription)
        title.tap()
        title.typeText(" UI smoke title")
        XCTAssertTrue((title.value as? String)?.contains("UI smoke title") == true)
        let description = app.textFields["scene-description"]
        XCTAssertTrue(description.exists, app.debugDescription)
        description.tap()
        description.typeText("Smoke scene")
        XCTAssertTrue(add.isEnabled)
        add.tap()
        XCTAssertTrue(app.staticTexts["3 scenes · 14s visible · 2s transition buffer"].waitForExistence(timeout: 10))
        XCTAssertFalse(add.isEnabled)
        attach(app, "added")

        app.buttons["reset-sample"].tap()
        XCTAssertTrue(initial.waitForExistence(timeout: 10))
        XCTAssertEqual(title.value as? String, "My first Montage")
        XCTAssertFalse(app.staticTexts["Smoke scene"].exists)
        attach(app, "reset")
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
