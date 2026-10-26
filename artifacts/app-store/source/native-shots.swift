import XCTest

final class CaptureTests: XCTestCase {
  let app = XCUIApplication(bundleIdentifier: "app.powerlog.mobile")
  var folder: URL {
    FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("captures")
  }
  func mark(_ name: String) { print("DEMO_MARK \(name) \(Date().timeIntervalSince1970)") }
  func shot(_ name: String) throws {
    sleep(2)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    try XCUIScreen.main.screenshot().pngRepresentation.write(to: folder.appendingPathComponent(name + ".png"))
    mark(name)
    sleep(2)
  }
  func scrollTo(_ element: XCUIElement, y: CGFloat = 105) {
    for _ in 0..<8 {
      let delta = element.frame.minY - y
      if abs(delta) < 22 { return }
      let height = app.frame.height
      let startY: CGFloat = delta > 0 ? height * 0.72 : height * 0.25
      let endY = max(height * 0.15, min(height * 0.74, startY - delta))
      app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: app.frame.width * 0.5, dy: startY))
        .press(
          forDuration: 0.03,
          thenDragTo: app.coordinate(withNormalizedOffset: .zero).withOffset(
            CGVector(dx: app.frame.width * 0.5, dy: endY)), withVelocity: .slow, thenHoldForDuration: 0.1)
    }
  }
  func choose(_ name: String) {
    scrollTo(app.buttons["monitor-view-picker"])
    app.buttons["monitor-view-picker"].tap()
    app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch.tap()
  }
  func editGraphs() {
    scrollTo(app.buttons["monitor-view-picker"])
    app.buttons["monitor-edit"].tap()
    app.otherElements["Graphs"].tap()
  }
  func addMetric(_ name: String) {
    let search = app.textFields["Search metrics"]
    search.tap()
    search.typeText(name + "\n")
    let row = app.otherElements.matching(NSPredicate(format: "label == %@ AND value CONTAINS %@", name, "checkbox"))
      .firstMatch
    XCTAssertTrue(row.waitForExistence(timeout: 10))
    row.tap()
    XCTAssertEqual(row.value as? String, "checkbox, checked")
    search.tap()
    // Replace the current search via its native clear affordance.
    if search.buttons["Clear text"].exists {
      search.buttons["Clear text"].tap()
    } else {
      search.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: name.count))
    }
  }
  func closeEditor() {
    let editor = app.otherElements["monitor-editor-dialog"]
    editor.buttons["Done"].tap()
    if editor.exists { editor.buttons["Done"].tap() }
    XCTAssertFalse(editor.waitForExistence(timeout: 1))
  }
  var phone: Bool { app.frame.width < 600 }
  var chartSuffix: String { phone ? "" : "-fullscreen" }
  func removeNumbers() {
    let buttons = app.buttons.matching(
      NSPredicate(format: "label BEGINSWITH %@ AND label ENDSWITH %@", "Remove ", " from numbers"))
    while buttons.count > 0 { buttons.firstMatch.tap() }
  }
  func closeCharts() { if !phone { app.buttons["monitor-close"].tap() } }
  func expand() {
    if phone {
      scrollTo(app.buttons["monitor-view-picker"], y: 70)
      return
    }
    scrollTo(app.buttons["monitor-expand"])
    app.buttons["monitor-expand"].tap()
    XCTAssertTrue(app.buttons["monitor-close"].waitForExistence(timeout: 5))
  }
  func testScreens() throws {
    continueAfterFailure = false
    app.launch()
    XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30))
    if !phone {
      let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
      let controls = system.buttons["window-controls:app.powerlog.mobile"]
      if controls.exists { controls.tap() }
      let zoom = system.buttons["Zoom-button"]
      if zoom.exists { zoom.tap() }
    }
    app.otherElements["tab-sessions"].tap()
    let rides = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Open ride,"))
    XCTAssertTrue(rides.firstMatch.waitForExistence(timeout: 20))
    mark("begin")
    try shot("01-history")
    rides.firstMatch.tap()
    XCTAssertTrue(app.buttons["Export FIT"].waitForExistence(timeout: 20))
    try shot("02-ride-summary")
    scrollTo(app.buttons["monitor-view-picker"])
    app.buttons["monitor-edit"].tap()
    try shot("09-custom-dashboard")
    if phone { removeNumbers() }
    closeEditor()
    expand()
    try shot("03-power-cadence")
    let chart = app.otherElements["monitor-chart-power" + chartSuffix]
    mark("inspection-start")
    chart.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.8)).press(
      forDuration: 0.5,
      thenDragTo: chart.coordinate(withNormalizedOffset: CGVector(dx: 0.78, dy: 0.8)), withVelocity: .slow,
      thenHoldForDuration: 1)
    app.buttons["monitor-zoom-in" + chartSuffix].tap()
    app.buttons["monitor-zoom-in" + chartSuffix].tap()
    try shot("04-chart-detail")
    app.buttons["monitor-reset" + chartSuffix].tap()
    closeCharts()
    scrollTo(app.buttons["monitor-view-picker"])
    app.buttons["monitor-edit"].tap()
    app.otherElements["Graphs"].tap()
    app.buttons["Remove Cadence from graphs"].tap()
    app.otherElements.matching(NSPredicate(format: "label == %@ AND value CONTAINS %@", "Heart rate", "unchecked"))
      .firstMatch.tap()
    closeEditor()
    expand()
    chart.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8)).tap()
    try shot("05-power-heart-rate")
    closeCharts()
    choose("Battery")
    editGraphs()
    app.buttons["Remove Battery current from graphs"].tap()
    app.buttons["Remove Battery input power from graphs"].tap()
    closeEditor()
    scrollTo(app.buttons["monitor-view-picker"])
    try shot("06-battery-voltage")
    editGraphs()
    app.buttons["Remove Battery voltage from graphs"].tap()
    if phone {
      app.otherElements["Numbers"].tap()
      removeNumbers()
      app.otherElements["Graphs"].tap()
    }
    for metric in ["Energy used (controller)", "Energy used (calc)", "Charge used (controller)", "Charge used (calc)"] {
      addMetric(metric)
    }
    closeEditor()
    expand()
    try shot("07-battery-consumption")
    closeCharts()
    choose("Temperature")
    scrollTo(app.buttons["monitor-view-picker"])
    try shot("08-temperature")
    scrollTo(app.buttons["Export FIT"])
    app.buttons["Export FIT"].tap()
    let saveFile = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Save to Files"))
      .firstMatch
    XCTAssertTrue(saveFile.waitForExistence(timeout: 120))
    try shot("10-fit-export")
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.15)).tap()
    if saveFile.exists {
      app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.58)).press(
        forDuration: 0.1,
        thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.97)))
    }
    app.otherElements["tab-settings"].tap()
    XCTAssertTrue(app.otherElements["settings-screen"].waitForExistence(timeout: 20))
    try shot("11-settings")
    mark("end")
  }
}
