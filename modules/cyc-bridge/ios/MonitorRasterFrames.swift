import Foundation

/// Constant-size counters, with no frame history, chart data or device identifiers.
struct MonitorRasterFrameCounters {
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
    frames += 1; totalMilliseconds += milliseconds
    maximumMilliseconds = max(maximumMilliseconds, milliseconds)
    if milliseconds <= 16.67 { within16Milliseconds += 1 }
    if milliseconds <= 33.3 { within33Milliseconds += 1 }
    if milliseconds > 100 { over100Milliseconds += 1 }
  }

  func snapshot(active: Bool) -> [String: Double] {
    ["active": active ? 1 : 0, "frames": Double(frames), "presentationUpdates": Double(presentationUpdates),
     "elapsedMilliseconds": totalMilliseconds, "meanMilliseconds": frames > 0 ? totalMilliseconds / Double(frames) : 0,
     "maxMilliseconds": maximumMilliseconds, "within16_67Milliseconds": Double(within16Milliseconds),
     "within33_3Milliseconds": Double(within33Milliseconds), "over100Milliseconds": Double(over100Milliseconds),
     "percentWithin16_67": frames > 0 ? Double(within16Milliseconds) / Double(frames) * 100 : 0,
     "percentWithin33_3": frames > 0 ? Double(within33Milliseconds) / Double(frames) * 100 : 0]
  }
}

/// One capture per process, armed only by an explicit development launch argument.
struct MonitorRasterLaunchProfile {
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
    // 1 = duration reached, 2 = foreground lost, 3 = explicit sampler stop/restart.
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

/// Explicit, opt-in physical validation of real gestures. No driver and no capture/storage integration.
/// Display callback cadence is a main-thread frame proxy, not touch-to-photon latency.
final class MonitorRasterFrameDiagnostics: NSObject {
  static let shared = MonitorRasterFrameDiagnostics()
  private var link: CADisplayLink?
  private var beganAt = 0.0
  private var durationLimit = 60.0
  private var counters = MonitorRasterFrameCounters()
  private var launchProfile = MonitorRasterLaunchProfile(enabled: ProcessInfo.processInfo.arguments.contains(MonitorRasterLaunchProfile.argument))
  private var profiling = false
  private var profileWriteStatus = 0.0
  private var inactiveObserver: NSObjectProtocol?
  private let outputQueue = DispatchQueue(label: "powerlog.monitor.profile-output", qos: .utility)

  private override init() {
    super.init()
    if launchProfile.enabled, let directory = Self.outputDirectory {
      // Remove an earlier run's report before this launch can be mistaken for a completed capture.
      outputQueue.async { try? FileManager.default.removeItem(at: directory.appendingPathComponent(MonitorRasterLaunchProfile.filename)) }
    }
  }

  private static var outputDirectory: URL? { FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first }

  func start() -> [String: Double] {
    begin(duration: 60, profile: false)
    return snapshot()
  }

  /// Called by the real mounted view after its accepted bitmap and current presentation are available.
  /// This observes a real cursor only; it never creates gestures, changes a prop, or reads recording data.
  func observePresentation(hasAcceptedBitmap: Bool, hasCursor: Bool) {
    precondition(Thread.isMainThread)
    guard launchProfile.shouldStart(hasAcceptedBitmap: hasAcceptedBitmap, hasCursor: hasCursor) else { return }
    _ = MonitorRasterWorker.shared.diagnostics(reset: true)
    begin(duration: 30, profile: true)
  }

  private func begin(duration: Double, profile: Bool) {
    precondition(Thread.isMainThread)
    if link != nil { _ = finish(reason: 3) }
    durationLimit = duration; profiling = profile
    counters = MonitorRasterFrameCounters(); beganAt = CACurrentMediaTime()
    if profile { counters.presentationUpdates = 1 } // Include the real presentation that armed this capture.
    let next = CADisplayLink(target: self, selector: #selector(tick(_:)))
    next.add(to: .main, forMode: .common); link = next
    inactiveObserver = NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification,
      object: nil, queue: .main) { [weak self] _ in _ = self?.finish(reason: 2) }
  }

  func stop() -> [String: Double] { finish(reason: 3) }
  func snapshot() -> [String: Double] {
    var result = counters.snapshot(active: link != nil)
    result["launchProfileEnabled"] = launchProfile.enabled ? 1 : 0
    result["launchProfileStarted"] = launchProfile.started ? 1 : 0
    // 0 = no write, 1 = queued, 2 = written, -1 = failed. These are counters, never user data.
    result["profileWriteStatus"] = profileWriteStatus
    return result
  }
  func presentationChanged() { if link != nil { counters.presentationUpdates += 1 } }

  private func finish(reason: Int) -> [String: Double] {
    precondition(Thread.isMainThread)
    link?.invalidate(); link = nil
    if let inactiveObserver { NotificationCenter.default.removeObserver(inactiveObserver); self.inactiveObserver = nil }
    if profiling {
      profiling = false
      let report = MonitorRasterLaunchProfile.report(frames: counters.snapshot(active: false),
        raster: MonitorRasterWorker.shared.diagnostics(), stopReason: reason)
      saveProfile(report)
    }
    return snapshot()
  }

  private func saveProfile(_ report: [String: Double]) {
    guard let directory = Self.outputDirectory else { profileWriteStatus = -1; return }
    profileWriteStatus = 1
    // One small numeric report, written after sampling on its own queue; never a capture/storage queue.
    outputQueue.async {
      let status: Double
      do { try MonitorRasterLaunchProfile.write(report, to: directory); status = 2 } catch { status = -1 }
      DispatchQueue.main.async { self.profileWriteStatus = status }
    }
  }

  @objc private func tick(_ link: CADisplayLink) {
    guard UIApplication.shared.applicationState == .active else { _ = finish(reason: 2); return }
    guard CACurrentMediaTime() - beganAt < durationLimit else { _ = finish(reason: 1); return }
    counters.record(timestamp: link.timestamp)
  }
}
#endif
