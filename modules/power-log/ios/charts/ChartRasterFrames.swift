import Foundation

struct ChartRasterFrameCounters {
  private var previous: Double?
  private(set) var frames = 0
  private var totalMilliseconds = 0.0
  private var maximumMilliseconds = 0.0
  private var within16Milliseconds = 0
  private var within33Milliseconds = 0
  private var over100Milliseconds = 0
  var presentationUpdates = 0

  mutating func record(timestamp: Double) {
    guard timestamp.isFinite else { return }
    defer { previous = timestamp }
    guard let previous, timestamp > previous else { return }
    let milliseconds = (timestamp - previous) * 1_000
    frames += 1
    totalMilliseconds += milliseconds
    maximumMilliseconds = max(maximumMilliseconds, milliseconds)
    if milliseconds <= 16.67 { within16Milliseconds += 1 }
    if milliseconds <= 33.3 { within33Milliseconds += 1 }
    if milliseconds > 100 { over100Milliseconds += 1 }
  }

  func snapshot(active: Bool) -> [String: Double] {
    [
      "active": active ? 1 : 0, "frames": Double(frames), "presentationUpdates": Double(presentationUpdates),
      "elapsedMilliseconds": totalMilliseconds, "meanMilliseconds": frames > 0 ? totalMilliseconds / Double(frames) : 0,
      "maxMilliseconds": maximumMilliseconds, "within16_67Milliseconds": Double(within16Milliseconds),
      "within33_3Milliseconds": Double(within33Milliseconds), "over100Milliseconds": Double(over100Milliseconds),
      "percentWithin16_67": frames > 0 ? Double(within16Milliseconds) / Double(frames) * 100 : 0,
      "percentWithin33_3": frames > 0 ? Double(within33Milliseconds) / Double(frames) * 100 : 0,
    ]
  }
}

struct ChartRasterLaunchProfile {
  static let argument = "--power-log-chart-profile"
  static let filename = "monitor-raster-profile.json"
  let enabled: Bool
  private(set) var started = false

  mutating func shouldStart(hasAcceptedBitmap: Bool, hasCursor: Bool) -> Bool {
    guard enabled, !started, hasAcceptedBitmap, hasCursor else { return false }
    started = true
    return true
  }

  static func report(frames: [String: Double], raster: [String: Int], stopReason: Int) -> [String: Double] {
    var result = frames
    result["profileVersion"] = 1
    result["requestedMilliseconds"] = 30_000
    // 1 = duration reached, 2 = foreground lost.
    result["stopReason"] = Double(stopReason)
    for (name, count) in raster { result["raster_\(name)"] = Double(count) }
    return result
  }

  static func write(_ report: [String: Double], to directory: URL) throws {
    let data = try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    try data.write(to: directory.appendingPathComponent(filename), options: [.atomic])
  }
}

#if canImport(UIKit)
  import UIKit

  final class ChartRasterFrameDiagnostics: NSObject {
    static let shared = ChartRasterFrameDiagnostics()
    private static let durationSeconds = 30.0
    private var link: CADisplayLink?
    private var beganAt = 0.0
    private var counters = ChartRasterFrameCounters()
    private var launchProfile = ChartRasterLaunchProfile(
      enabled: ProcessInfo.processInfo.arguments.contains(ChartRasterLaunchProfile.argument))
    private var inactiveObserver: NSObjectProtocol?
    private let outputQueue = DispatchQueue(label: "powerlog.monitor.profile-output", qos: .utility)

    private override init() {
      super.init()
      if launchProfile.enabled, let directory = Self.outputDirectory {
        // Remove an earlier run's report before this launch can be mistaken for a completed capture.
        outputQueue.async {
          try? FileManager.default.removeItem(at: directory.appendingPathComponent(ChartRasterLaunchProfile.filename))
        }
      }
    }

    private static var outputDirectory: URL? {
      FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
    }

    func observePresentation(hasAcceptedBitmap: Bool, hasCursor: Bool) {
      precondition(Thread.isMainThread)
      guard launchProfile.shouldStart(hasAcceptedBitmap: hasAcceptedBitmap, hasCursor: hasCursor) else { return }
      _ = ChartRasterWorker.shared.diagnostics(reset: true)
      counters = ChartRasterFrameCounters()
      counters.presentationUpdates = 1
      beganAt = CACurrentMediaTime()
      let next = CADisplayLink(target: self, selector: #selector(tick(_:)))
      next.add(to: .main, forMode: .common)
      link = next
      inactiveObserver = NotificationCenter.default.addObserver(
        forName: UIApplication.willResignActiveNotification,
        object: nil, queue: .main
      ) { [weak self] _ in self?.finish(reason: 2) }
    }

    func presentationChanged() { if link != nil { counters.presentationUpdates += 1 } }

    private func finish(reason: Int) {
      precondition(Thread.isMainThread)
      guard let link else { return }
      link.invalidate()
      self.link = nil
      if let inactiveObserver {
        NotificationCenter.default.removeObserver(inactiveObserver)
        self.inactiveObserver = nil
      }
      let report = ChartRasterLaunchProfile.report(
        frames: counters.snapshot(active: false),
        raster: ChartRasterWorker.shared.diagnostics(), stopReason: reason)
      guard let directory = Self.outputDirectory else { return }
      outputQueue.async { try? ChartRasterLaunchProfile.write(report, to: directory) }
    }

    @objc private func tick(_ link: CADisplayLink) {
      guard UIApplication.shared.applicationState == .active else {
        finish(reason: 2)
        return
      }
      guard CACurrentMediaTime() - beganAt < Self.durationSeconds else {
        finish(reason: 1)
        return
      }
      counters.record(timestamp: link.timestamp)
    }
  }
#endif
