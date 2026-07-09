import XCTest

/// Walks the app and writes full-resolution screenshots for App Store submission.
/// Run on an iPhone 16 Pro Max simulator for the required 6.9" (1320x2868) set.
/// Output: /tmp/canopy_shots/ on the host Mac.
final class ScreenshotTests: XCTestCase {

    private let outputDirectory = URL(fileURLWithPath: "/tmp/canopy_shots", isDirectory: true)

    private func snap(_ name: String) {
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)
        try? png.write(to: outputDirectory.appendingPathComponent("\(name).png"))
    }

    func testCaptureAppStoreScreenshots() {
        let app = XCUIApplication()
        app.launch()

        // 1. Welcome
        let enterButton = app.buttons["Enter Your Grove"]
        XCTAssertTrue(enterButton.waitForExistence(timeout: 10))
        sleep(2) // let the entrance animation settle
        snap("01_welcome")
        enterButton.tap()

        // Paywall appears when not subscribed; unlock via the debug path so the
        // screenshot set shows the real app content.
        let debugUnlock = app.buttons["Continue in Debug"]
        if debugUnlock.waitForExistence(timeout: 4) {
            snap("02_paywall")
            debugUnlock.tap()
        }

        // 2. The Grove (conversation list)
        let launchPlan = app.staticTexts["Two-Week Launch Plan"]
        XCTAssertTrue(launchPlan.waitForExistence(timeout: 10))
        sleep(1)
        snap("03_grove")

        // 3. Chat: launch plan (markdown lists)
        launchPlan.tap()
        sleep(2)
        snap("04_chat_launch_plan")
        app.navigationBars.buttons.firstMatch.tap() // back

        // 4. Chat: coffee with sources
        let coffee = app.staticTexts["Coffee Before the Market"]
        XCTAssertTrue(coffee.waitForExistence(timeout: 10))
        coffee.tap()
        sleep(2)
        snap("05_chat_sources")
        app.navigationBars.buttons.firstMatch.tap()

        // 5. Chat: taglines (creative persona)
        let taglines = app.staticTexts["Eco Brand Taglines"]
        XCTAssertTrue(taglines.waitForExistence(timeout: 10))
        taglines.tap()
        sleep(2)
        snap("06_chat_taglines")
        app.navigationBars.buttons.firstMatch.tap()

        // 6. Dark mode grove: toggle in settings, screenshot list, toggle back
        let gear = app.buttons["Settings"]
        XCTAssertTrue(gear.waitForExistence(timeout: 5))
        gear.tap()
        let darkSwitch = app.switches.firstMatch
        if darkSwitch.waitForExistence(timeout: 5) {
            darkSwitch.tap()
            sleep(1)
            snap("07_settings_dark")
            app.buttons["Done"].tap()
            sleep(1)
            snap("08_grove_dark")

            // restore light mode for subsequent runs
            gear.tap()
            if darkSwitch.waitForExistence(timeout: 5) {
                darkSwitch.tap()
                app.buttons["Done"].tap()
            }
        }
    }

    override func setUp() {
        continueAfterFailure = false
    }
}
