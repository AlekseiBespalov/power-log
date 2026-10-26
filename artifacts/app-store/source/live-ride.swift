import XCTest

final class CaptureTests: XCTestCase {
  let app = XCUIApplication(bundleIdentifier: "app.powerlog.lab")
  func shot(_ name: String) throws {
    sleep(2)
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("captures")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    try XCUIScreen.main.screenshot().pngRepresentation.write(to: folder.appendingPathComponent(name + ".png"))
    print("DEMO_MARK \(name) \(Date().timeIntervalSince1970)")
    sleep(2)
  }
  func scrollTo(_ element: XCUIElement) {
    for _ in 0..<6 {
      if element.isHittable && element.frame.minY > 100 && element.frame.maxY < app.frame.height - 140 { return }
      let delta = element.frame.minY - 150
      let height = app.frame.height
      let y: CGFloat = delta > 0 ? 0.72 : 0.25
      app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: y)).press(
        forDuration: 0.05,
        thenDragTo: app.coordinate(
          withNormalizedOffset: CGVector(dx: 0.5, dy: max(0.15, min(0.75, y - delta / height)))),
        withVelocity: .slow, thenHoldForDuration: 0.1)
    }
  }
  func testLiveRide() throws {
    continueAfterFailure = false
    app.launchArguments = ["-PowerLogLiveDemo", "YES"]
    app.launch()
    XCTAssertTrue(app.buttons["start-ride"].waitForExistence(timeout: 30))
    try shot("01-ready")
    app.buttons["start-ride"].tap()
    if app.buttons["setup-start-ride"].waitForExistence(timeout: 3) {
      app.buttons["setup-start-ride"].tap()
    }
    let sheet = app.otherElements["ride-setup-sheet"]
    if sheet.exists && sheet.buttons["Done"].exists { sheet.buttons["Done"].tap() }
    XCTAssertTrue(app.buttons["Pause"].waitForExistence(timeout: 20))
    sleep(16)
    try shot("02-live-recording")
    app.buttons["Lap"].tap()
    sleep(3)
    app.buttons["Pause"].tap()
    XCTAssertTrue(app.buttons["Resume"].waitForExistence(timeout: 5))
    try shot("03-paused")
    app.buttons["Resume"].tap()
    sleep(8)
    scrollTo(app.buttons["monitor-expand"])
    app.buttons["monitor-expand"].tap()
    XCTAssertTrue(app.buttons["monitor-close"].waitForExistence(timeout: 5))
    sleep(10)
    try shot("04-live-graphs")
    // Inspect horizontally well below most power values. Both power series share the moving time cursor.
    let chart = app.otherElements["monitor-chart-power-fullscreen"]
    XCTAssertTrue(chart.waitForExistence(timeout: 5))
    let left = chart.coordinate(withNormalizedOffset: CGVector(dx: 0.23, dy: 0.87))
    let right = chart.coordinate(withNormalizedOffset: CGVector(dx: 0.82, dy: 0.87))
    left.press(forDuration: 0.2, thenDragTo: right, withVelocity: .slow, thenHoldForDuration: 1)
    try shot("05-inspect-right")
    let first = chart.value as? String
    right.press(forDuration: 0.2, thenDragTo: left, withVelocity: .slow, thenHoldForDuration: 1)
    try shot("06-inspect-left")
    XCTAssertNotEqual(first, chart.value as? String)
    app.buttons["monitor-zoom-in-fullscreen"].tap()
    sleep(2)
    app.buttons["monitor-zoom-in-fullscreen"].tap()
    try shot("07-zoom")
    left.press(forDuration: 0.2, thenDragTo: right, withVelocity: .slow, thenHoldForDuration: 1)
    try shot("08-zoom-inspect")
    app.buttons["monitor-reset-fullscreen"].tap()
    sleep(2)
    app.buttons["monitor-close"].tap()
    scrollTo(app.buttons["Finish"])
    app.buttons["Finish"].tap()
    XCTAssertTrue(app.buttons["Save ride"].waitForExistence(timeout: 5))
    try shot("09-finish")
    app.buttons["Save ride"].tap()
    sleep(5)
    app.otherElements["tab-sessions"].tap()
    let ride = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Open ride,")).firstMatch
    XCTAssertTrue(ride.waitForExistence(timeout: 15))
    ride.tap()
    XCTAssertTrue(app.buttons["Export FIT"].waitForExistence(timeout: 15))
    try shot("10-saved-ride")
    app.buttons["Export FIT"].tap()
    let save = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Save to Files")).firstMatch
    XCTAssertTrue(save.waitForExistence(timeout: 60))
    try shot("11-fit-export")
  }
}
