import XCTest

final class CaptureTests: XCTestCase {
  func testScreens() throws {
    continueAfterFailure = false
    let app = XCUIApplication(bundleIdentifier: "app.powerlog.mobile.watchkitapp")
    app.launch()
    sleep(12)
    XCUIDevice.shared.press(.home)
    app.activate()
    XCTAssertTrue(app.buttons["Start ride"].waitForExistence(timeout: 10))
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("captures")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    func shot(_ name: String) throws {
      sleep(2)
      try XCUIScreen.main.screenshot().pngRepresentation.write(to: folder.appendingPathComponent(name + ".png"))
      print("DEMO_MARK \(name) \(Date().timeIntervalSince1970)")
    }
    try shot("01-ready")
    app.buttons["Ride options, outdoor"].tap()
    XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5))
    try shot("02-ride-options")
    app.buttons["Done"].tap()
  }
}
