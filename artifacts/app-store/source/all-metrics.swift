import XCTest

final class CaptureTests: XCTestCase {
  let app = XCUIApplication(bundleIdentifier: "app.powerlog.lab")
  var seen = Set<String>()
  let chartIDs = [
    "power", "cadenceRpm", "heartRateBpm", "pedalTorqueNm", "energy", "batteryVoltageV", "current",
    "consumedAh", "consumedWh", "temperature", "motorRpm", "throttleVoltageV", "speed", "distanceMeters",
    "distance-comparison", "battery-energy", "battery-charge", "altitudeMeters", "accuracy", "courseDegrees",
    "assistLevel", "raceMode", "faultCode",
  ]
  func mark(_ name: String) { print("DEMO_MARK \(name) \(Date().timeIntervalSince1970)") }
  func shot(_ name: String) throws {
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("captures")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    try XCUIScreen.main.screenshot().pngRepresentation.write(to: folder.appendingPathComponent(name + ".png"))
    mark(name)
  }
  func scroll(by distance: CGFloat) {
    // Stay above the fixed recording controls. Vertical drags scroll through the charts.
    let height = app.frame.height
    let start: CGFloat = distance > 0 ? 0.67 : 0.27
    let delta = min(330, abs(distance)) / height * (distance > 0 ? 1 : -1)
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: start)).press(
      forDuration: 0.03,
      thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: start - delta)),
      withVelocity: .slow, thenHoldForDuration: 0.4)
  }
  func recordVisibleCharts() throws -> [String: CGRect] {
    let snapshot = try app.snapshot()
    var frames: [String: CGRect] = [:]
    func visit(_ element: XCUIElementSnapshot) {
      if element.identifier.hasPrefix("monitor-chart-") {
        let id = String(element.identifier.dropFirst("monitor-chart-".count))
        frames[id] = element.frame
        if element.frame.minY > 150 && element.frame.maxY < snapshot.frame.height - 210 {
          seen.insert(id)
          XCTAssertFalse((element.value as? String ?? "").contains("No samples"), "\(id) has no samples")
        }
      }
      for child in element.children { visit(child) }
    }
    visit(snapshot)
    return frames
  }
  func showChart(_ id: String) throws {
    for _ in 0..<40 {
      let frames = try recordVisibleCharts()
      guard let frame = frames[id] else {
        XCTFail("Missing chart \(id)")
        return
      }
      if frame.minY > 245 && frame.maxY < app.frame.height - 210 { return }
      scroll(by: frame.minY - 330)
    }
    XCTFail("Could not bring \(id) into view")
  }
  func testAllMetrics() throws {
    continueAfterFailure = false
    app.launchArguments = ["-PowerLogLiveDemo", "YES", "-PowerLogDemoAllMetrics", "YES"]
    app.launch()
    if !app.buttons["start-ride"].waitForExistence(timeout: 3) && app.buttons["Finish"].exists {
      app.buttons["Finish"].tap()
      if app.buttons["Save ride"].waitForExistence(timeout: 3) { app.buttons["Save ride"].tap() }
    }
    XCTAssertTrue(app.buttons["start-ride"].waitForExistence(timeout: 30))
    try shot("01-ready")
    app.buttons["start-ride"].tap()
    if app.buttons["setup-start-ride"].waitForExistence(timeout: 2) { app.buttons["setup-start-ride"].tap() }
    XCTAssertTrue(app.buttons["Pause"].waitForExistence(timeout: 15))
    sleep(16)
    try shot("02-all-numbers-top")
    for index in 1...3 {
      scroll(by: 300)
      sleep(1)
      try shot(String(format: "03-numbers-%02d", index))
    }
    for (index, id) in chartIDs.enumerated() {
      try showChart(id)
      sleep(1)
      try shot(String(format: "04-chart-%02d-%@", index + 1, id))
    }
    _ = try recordVisibleCharts()
    XCTAssertEqual(seen, Set(chartIDs), "Every enabled chart must be fully visible during the sweep")
    try shot("04-all-metrics-bottom")
    try showChart("power")
    app.buttons["monitor-expand"].tap()
    XCTAssertTrue(app.buttons["monitor-close"].waitForExistence(timeout: 5))
    let power = app.otherElements["monitor-chart-power-fullscreen"]
    XCTAssertTrue(power.waitForExistence(timeout: 5))
    let left = power.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.85))
    let right = power.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.85))
    mark("inspection-start")
    left.press(forDuration: 0.15, thenDragTo: right, withVelocity: .slow, thenHoldForDuration: 0.4)
    try shot("05-inspect-right")
    let first = power.value as? String
    right.press(forDuration: 0.15, thenDragTo: left, withVelocity: .slow, thenHoldForDuration: 0.4)
    XCTAssertNotEqual(first, power.value as? String)
    try shot("06-inspect-left")
    app.buttons["monitor-zoom-in-fullscreen"].tap()
    app.buttons["monitor-zoom-in-fullscreen"].tap()
    left.press(forDuration: 0.15, thenDragTo: right, withVelocity: .slow, thenHoldForDuration: 0.4)
    try shot("07-zoom-inspect")
    app.buttons["monitor-reset-fullscreen"].tap()
    app.buttons["monitor-close"].tap()
    app.buttons["Lap"].tap()
    app.buttons["Pause"].tap()
    sleep(1)
    try shot("08-paused")
    app.buttons["Resume"].tap()
    sleep(1)
    app.buttons["Finish"].tap()
    XCTAssertTrue(app.buttons["Save ride"].waitForExistence(timeout: 5))
    try shot("09-finish")
    app.buttons["Save ride"].tap()
    sleep(2)
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
    sleep(2)
  }
}
