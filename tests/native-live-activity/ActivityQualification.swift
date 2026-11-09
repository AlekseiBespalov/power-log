import XCTest
import Vision

final class ActivityQualification: XCTestCase {
  @MainActor
  func testActivityExpiry() throws {
    continueAfterFailure = true
    let environment = ProcessInfo.processInfo.environment
    let caseName = environment["POWER_LOG_ACTIVITY_CASE"]!
    let acquired = Double(environment["POWER_LOG_ACTIVITY_ACQUIRED"]!)!
    let app = XCUIApplication(bundleIdentifier: environment["POWER_LOG_ACTIVITY_BUNDLE"]!)
    app.launchArguments = [
      "-PowerLogDevBike", "YES", "--activity-qualification", "--activity-qualification-acquiredAt", String(acquired),
    ]
    if caseName == "A" { app.launchArguments.append("--activity-qualification-running") }
    app.launch()
    if caseName == "F" {
      var states: [[String: Any]] = []
      for offset in [21.0, 30.0] {
        while Date().timeIntervalSince1970 < acquired + offset { Thread.sleep(forTimeInterval: 0.02) }
        states.append(["at": Date().timeIntervalSince1970, "foreground": app.state == .runningForeground])
      }
      while Date().timeIntervalSince1970 < acquired + 45 { Thread.sleep(forTimeInterval: 0.02) }
      let result: [String: Any] = [
        "case": caseName, "acquired": acquired, "frames": [],
        "lifecycle": ["foreground": app.state == .runningForeground, "deadlineStates": states],
        "observedUntil": Date().timeIntervalSince1970,
      ]
      let data = try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
      print("ACTIVITY_EVIDENCE " + String(decoding: data, as: UTF8.self))
      app.terminate()
      return
    }
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    var lifecycle: [String: Any] = ["method": "os-background"]
    var frames: [[String: Any]] = []
    func wait(until target: Double) {
      while Date().timeIntervalSince1970 < target { Thread.sleep(forTimeInterval: 0.02) }
    }
    wait(until: acquired + (caseName == "C" ? 21.2 : 0.2))
    XCUIDevice.shared.press(.home)
    lifecycle["backgroundedAt"] = Date().timeIntervalSince1970
    springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.04)).press(forDuration: 0.6)
    let panel = springboard.descendants(matching: .any).matching(identifier: "PowerLog.Activity").firstMatch
    let panelFrame = panel.exists ? panel.frame : .zero
    let screenFrame = springboard.frame
    let visible = panelFrame.width > 0 && panelFrame.height > 0 && panelFrame.intersects(screenFrame)
    func text(_ screenshot: XCUIScreenshot, frame: CGRect) throws -> [String] {
      guard frame.width > 0 && frame.height > 0, let image = screenshot.image.cgImage else { return [] }
      let scaleX = CGFloat(image.width) / screenFrame.width
      let scaleY = CGFloat(image.height) / screenFrame.height
      let pixels = CGRect(
        x: frame.minX * scaleX, y: frame.minY * scaleY,
        width: frame.width * scaleX, height: frame.height * scaleY)
      guard let crop = image.cropping(to: pixels) else { return [] }
      let request = VNRecognizeTextRequest()
      request.recognitionLevel = .accurate
      request.recognitionLanguages = ["en-US"]
      request.usesLanguageCorrection = false
      try VNImageRequestHandler(cgImage: crop).perform([request])
      return request.results?.compactMap { $0.topCandidates(1).first?.string } ?? []
    }
    if caseName != "A" {
      let cutoff = acquired + (caseName == "B" ? 21 : 30)
      while Date().timeIntervalSince1970 < cutoff {
        if app.state == .runningBackgroundSuspended {
          lifecycle["suspendedAt"] = Date().timeIntervalSince1970
          break
        }
        Thread.sleep(forTimeInterval: 0.05)
      }
    }
    for offset in caseName == "A" ? [21.0, 30.0] : [caseName == "B" ? 21.0 : 30.0] {
      let deadline = acquired + offset
      wait(until: deadline + 0.7)
      let screenshot = XCUIScreen.main.screenshot()
      let captured = Date().timeIntervalSince1970
      let panelText = try text(screenshot, frame: panelFrame)
      let attachment = XCTAttachment(screenshot: screenshot)
      attachment.name = "\(caseName)-expiry-\(offset)-\(captured)"
      attachment.lifetime = .keepAlways
      add(attachment)
      frames.append([
        "deadline": deadline, "capturedAt": captured, "surface": "dynamicIsland",
        "visible": visible, "panelText": panelText,
        "attachment": attachment.name!,
      ])
      if offset == 30 && caseName == "A" {
        lifecycle["unsuspendedAtHeartExpiry"] = app.state == .runningBackground || app.state == .runningForeground
      }
    }
    wait(until: acquired + 45)
    let result: [String: Any] = [
      "case": caseName, "acquired": acquired, "lifecycle": lifecycle, "frames": frames,
      "observedUntil": Date().timeIntervalSince1970,
    ]
    let data = try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
    print("ACTIVITY_EVIDENCE " + String(decoding: data, as: UTF8.self))
    app.terminate()
  }
}
