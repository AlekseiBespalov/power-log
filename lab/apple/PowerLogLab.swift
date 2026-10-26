internal import Expo
import Foundation
import PowerLogCore
import UIKit
@testable internal import PowerLogBridge

final class PowerLogLabSceneDelegate: ExpoAppSceneDelegate {
  override func scene(
    _ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions
  ) {
    guard PowerLogLab.isRequested else {
      if PowerLogLiveDemo.enabled { PowerLogLiveDemo.prepare() }
      super.scene(scene, willConnectTo: session, options: connectionOptions)
      if PowerLogLiveDemo.enabled { PowerLogLiveDemo.start() }
      return
    }
    guard let scene = scene as? UIWindowScene else { return }
    let window = UIWindow(windowScene: scene)
    let controller = UIViewController()
    controller.view.backgroundColor = .systemBackground
    let label = UILabel(frame: CGRect(x: 24, y: 120, width: 350, height: 120))
    label.numberOfLines = 0
    label.text = "Power Log Lab\n" + (UserDefaults.standard.string(forKey: "PowerLogLabCase") ?? "")
    controller.view.addSubview(label)
    window.rootViewController = controller
    self.window = window
    window.makeKeyAndVisible()
    Task { await PowerLogLab.run() }
  }
}

enum PowerLogLab {
  static var isRequested: Bool { UserDefaults.standard.string(forKey: "PowerLogLabCase") != nil }
  static var archiveRoot: URL {
    FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent(
      "PowerLog/workouts", isDirectory: true)
  }
  static func onEngine<T>(_ work: @escaping (WorkoutEngine) throws -> T) async throws -> T {
    let engine = WorkoutEngine.shared
    return try await withCheckedThrowingContinuation { continuation in
      engine.queue.async { continuation.resume(with: Result { try work(engine) }) }
    }
  }
  static func startLocal() async throws -> [String: Any] {
    let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("power-log-preferences", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
    let revision =
      files.compactMap { file -> UInt64? in
        let name = file.lastPathComponent
        guard name.hasPrefix("monitor-"), name.hasSuffix(".json") else { return nil }
        return UInt64(name.dropFirst(8).dropLast(5))
      }.max() ?? 0
    var preferences =
      try JSONSerialization.jsonObject(
        with: Data(Preferences.shared.encode(preferences: Preferences.shared.defaults()).utf8)) as! [String: Any]
    preferences["sampleHz"] = 8
    let path = directory.appendingPathComponent(String(format: "monitor-%016llu.json", revision + 1))
    try JSONSerialization.data(withJSONObject: preferences).write(to: path, options: .atomic)
    ControllerEngine.shared.queue.sync { ControllerEngine.shared.labBeginCapture() }
    return try await withCheckedThrowingContinuation { continuation in
      let engine = WorkoutEngine.shared
      engine.queue.async {
        engine.start(indoor: true, useWatch: false, saveToHealth: false, recordGPS: false) {
          continuation.resume(with: $0)
        }
      }
    }
  }
  static func run() async {
    let defaults = UserDefaults.standard
    let name = defaults.string(forKey: "PowerLogLabCase") ?? ""
    let run = defaults.string(forKey: "PowerLogLabRun") ?? UUID().uuidString.lowercased()
    guard run.range(of: "^[a-zA-Z0-9_-]{1,120}$", options: .regularExpression) != nil else { return }
    do {
      let digest = try stamp()
      var result: [String: Any] = [
        "case": name, "run": run, "sourceDigest": digest,
        "pid": ProcessInfo.processInfo.processIdentifier, "status": "passed",
      ]
      if name == "interrupt-watch-prepare" {
        let archive = try WorkoutArchive(rootURL: archiveRoot)
        let start = Date().addingTimeInterval(-2)
        let record = try archive.create(
          startedAt: start, indoor: true, watchEnabled: true, saveToHealth: false, recordGPS: false, sampleHz: 8)
        try archive.append(
          WorkoutEvent(
            workoutId: record.id, kind: "lifecycle", source: "phone", timestamp: start, elapsedSeconds: 0,
            payload: ["action": .string("start")]))
        try archive.append(
          WorkoutEvent(
            workoutId: record.id, kind: "telemetry", source: "controller", timestamp: start.addingTimeInterval(1),
            elapsedSeconds: 1,
            payload: [
              "humanPowerW": .number(137.25), "cadenceRpm": .number(81), "batteryCurrentA": .number(3.125),
            ]))
        try archive.update(id: record.id, phase: "running", elapsedSeconds: 1)
        result["ride"] = record.id
        result["status"] = "killed"
        try write(result, run: run)
        _exit(77)
      }
      let state: [String: Any]
      if name == "local-record" {
        // A failed or interrupted earlier Lab run retains its ride just like the production app.
        try await onEngine { engine in
          let previous = engine.state()
          if previous["phase"] as? String == "interrupted", let id = previous["id"] as? String {
            _ = try engine.stop(expectedID: id)
          }
        }
        state = try await startLocal()
        let id = state["id"] as! String
        let live = UUID().uuidString.lowercased()
        for index in 0..<16 {
          let now = Date()
          let uptime = ProcessInfo.processInfo.systemUptime
          let sample: [String: Any] = [
            "timestamp": WorkoutCoding.preciseTimestamp(now), "observationId": UUID().uuidString.lowercased(),
            "clockEpoch": TelemetryCaptureClock.processEpoch, "captureSessionID": live, "connectionEpoch": live,
            "observationSequence": String(index + 1), "sourceElapsedSeconds": Double(index) / 8,
            "acquisitionMonotonic": uptime,
            "humanPowerW": 100 + Double(index), "batteryCurrentA": Double(index) / 8, "cadenceRpm": 80.0,
          ]
          try await onEngine { engine in
            try engine.admitController(
              PowerLogCaptureFrame(
                sample: sample, liveID: live,
                liveStartedAt: WorkoutCoding.timestamp(now), liveOrigin: uptime, liveElapsed: Double(index) / 8))
          }
          try await Task.sleep(nanoseconds: 125_000_000)
        }
        _ = try await onEngine { try $0.pause(expectedID: id) }
        _ = try await onEngine { try $0.lap(expectedID: id) }
        _ = try await onEngine { try $0.resume(expectedID: id) }
        _ = try await onEngine { try $0.stop(expectedID: id) }
      } else if name == "interrupt-watch-finish" {
        state = try await onEngine { $0.state() }
        guard state["phase"] as? String == "interrupted", let id = state["id"] as? String else {
          throw WorkoutDataError.invalid("No interrupted ride retained after process restart.")
        }
        _ = try await onEngine { try $0.stop(expectedID: id) }
      } else {
        throw WorkoutDataError.invalid("Unknown Lab case.")
      }
      // Let post-Finish Health readback execute even when this simulator has not granted read access.
      try await Task.sleep(nanoseconds: 2_500_000_000)
      let id = state["id"] as! String
      let archive = try WorkoutArchive(rootURL: archiveRoot)
      let record = try archive.metadata(id: id)
      guard record.phase == "completed", record.endedAt != nil, record.sampleHz == 8 else {
        throw WorkoutDataError.invalid("Local Finish did not retain its committed 8 Hz recording settings.")
      }
      let exporter = ExportSource { archive }
      for kind in ["fit", "zip"] {
        let session = try exporter.open(["rideId": id, "kind": kind])
        var after: Any = NSNull()
        var rows = 0
        repeat {
          let page = try exporter.page(["session": session["session"]!, "projection": "telemetry", "after": after])
          rows += page["rows"] as! Int
          after = page["last"]!
          if page["done"] as! Bool { break }
        } while true
        guard rows > 0 else { throw WorkoutDataError.invalid("Export lost controller originals.") }
        result[kind + "Rows"] = rows
        exporter.close(session["session"] as! String)
      }
      let plot = try MonitorDataStore.shared.readPlot(
        MonitorRequest(source: "workout", id: id, metrics: ["humanPowerW", "batteryCurrentA"], buckets: 64))
      guard plot["status"] as? String == "ok" else {
        throw WorkoutDataError.invalid("Recorded graphs are unavailable.")
      }
      result["ride"] = id
      result["events"] = record.eventCount
      if name == "local-record" {
        let discardedID = try await startLocal()["id"] as! String
        let discarded = try await onEngine { try $0.stop(expectedID: discardedID, discard: true) }
        guard discarded["phase"] as? String == "idle", try archive.store.isWorkoutDeleted(id: discardedID) else {
          throw WorkoutDataError.invalid("Discard did not remove the new local ride.")
        }
        result["discarded"] = true
      }
      try write(result, run: run)
    } catch {
      try? write(
        [
          "case": name, "run": run, "sourceDigest": (try? stamp()) ?? "",
          "pid": ProcessInfo.processInfo.processIdentifier,
          "status": "error", "error": error.localizedDescription,
        ], run: run)
    }
  }
  static func stamp() throws -> String {
    guard let url = Bundle.main.url(forResource: "build", withExtension: "json"),
      let value = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: String],
      let digest = value["sourceDigest"]
    else { throw WorkoutDataError.invalid("Missing Lab source stamp.") }
    return digest
  }
  static func write(_ result: [String: Any], run: String) throws {
    let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(
      "lab-results")
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys]).write(
      to: root.appendingPathComponent(run + ".json"), options: .atomic)
  }
}
