import Foundation

import PowerLogCore

enum ControllerErrorPresentation {
  static func displayError(storage: String?, other: String?, recovery: String?, reconnecting: Bool) -> (
    message: String?, recoverable: Bool
  ) {
    if let storage { return (storage, false) }
    if let other { return (other, false) }
    return (recovery, reconnecting && recovery != nil)
  }
}

enum TelemetryDiagnosticEvent: String, CaseIterable {
  case engineStarted = "engine_started"
  case connectRequested = "connect_requested"
  case connectionAttempt = "connection_attempt"
  case linkConnected = "link_connected"
  case identityVerified = "identity_verified"
  case transportStage = "transport_stage"
  case transportError = "transport_error"
  case disconnectRequested = "disconnect_requested"
  case disconnected
  case connectionFailed = "connection_failed"
  case reconnectScheduled = "reconnect_scheduled"
  case sessionStopped = "session_stopped"
  case bluetoothState = "bluetooth_state"
  case telemetrySummary = "telemetry_summary"
  case telemetryGap = "telemetry_gap"
  case requestResent = "request_resent"
  case backgroundChanged = "background_changed"
  case recordingError = "recording_error"
  case restored
  case resumed
}

enum TelemetryDiagnosticReason: String, CaseIterable {
  case manualDisconnect = "manual_disconnect"
  case connectionDeadline = "connection_deadline"
  case responseTimeout = "response_timeout"
  case expiredReply = "expired_reply"
  case writeReadyTimeout = "write_ready_timeout"
  case writeFailed = "write_failed"
  case linkDisconnected = "link_disconnected"
  case connectionFailed = "connection_failed"
  case notificationSetup = "notification_setup"
  case notificationError = "notification_error"
  case serviceDiscovery = "service_discovery"
  case characteristicDiscovery = "characteristic_discovery"
  case servicesInvalidated = "services_invalidated"
  case identityRejected = "identity_rejected"
  case telemetryRejected = "telemetry_rejected"
  case writeCapacity = "write_capacity"
  case retryLimit = "retry_limit"
  case cancellationDeadline = "cancellation_deadline"
  case bluetoothUnavailable = "bluetooth_unavailable"
  case platformError = "platform_error"
  case unexpectedConnection = "unexpected_connection"
  case pendingReconnect = "pending_reconnect"
  case restorationCleanup = "restoration_cleanup"
  case storageFailure = "storage_failure"
  case appTerminating = "app_terminating"
}

struct ControllerDisconnectDiagnostic {
  let initiator: String
  let reason: TelemetryDiagnosticReason
  let errorDomain: String?
  let errorCode: Int?
  let connectionSeconds: Double?
  let sampleAgeSeconds: Double?

  var dictionary: [String: Any] {
    [
      "initiator": initiator, "reason": reason.rawValue,
      "errorDomain": errorDomain as Any? ?? NSNull(), "errorCode": errorCode as Any? ?? NSNull(),
      "connectionSeconds": connectionSeconds as Any? ?? NSNull(),
      "sampleAgeSeconds": sampleAgeSeconds as Any? ?? NSNull(),
    ]
  }
}

struct TelemetryDiagnosticMetrics {
  private(set) var sampleCount = 0
  private(set) var connectionAttempts = 0
  private(set) var reconnects = 0
  private(set) var requestTimeouts = 0
  private(set) var linkSamples = 0
  private(set) var linkStarted: Double?
  private(set) var lastSample: Double?
  private(set) var lastGapSeconds: Double?
  private(set) var lastDisconnect: ControllerDisconnectDiagnostic?
  private(set) var cancellationReason: TelemetryDiagnosticReason?
  private(set) var cancellationStarted: Double?
  private(set) var cancelledConnectionSeconds: Double?
  private(set) var cancelledSampleAgeSeconds: Double?
  private var windowFirstSample: Double?
  private var windowLastSample: Double?
  private var windowSamples = 0
  private var latencySumMs = 0.0
  private var latencyMaxMs = 0.0
  private var latencyCount = 0
  private(set) var nextSummary: Double = 0

  mutating func beginSession(at now: Double) {
    self = TelemetryDiagnosticMetrics()
    nextSummary = now + 30
  }

  mutating func beginAttempt(at now: Double) {
    connectionAttempts += 1
    linkStarted = nil
    linkSamples = 0
    cancellationReason = nil
    cancellationStarted = nil
    cancelledConnectionSeconds = nil
    cancelledSampleAgeSeconds = nil
    resetWindow(at: now)
  }

  mutating func connected(at now: Double) { linkStarted = now }
  mutating func scheduledReconnect() { reconnects += 1 }
  mutating func timedOut() { requestTimeouts += 1 }

  func connectionSeconds(at now: Double) -> Double? { linkStarted.map { max(0, now - $0) } }
  func sampleAge(at now: Double) -> Double? { lastSample.map { max(0, now - $0) } }

  // Keep the first app cancellation reason until its CB callback, even after transport fields are cleared.
  mutating func requestedCancellation(reason: TelemetryDiagnosticReason, at now: Double) {
    guard cancellationReason == nil else { return }
    cancellationReason = reason
    cancellationStarted = now
    cancelledConnectionSeconds = connectionSeconds(at: now)
    cancelledSampleAgeSeconds = sampleAge(at: now)
  }

  mutating func disconnected(
    at now: Double, error: Error?, defaultReason: TelemetryDiagnosticReason = .linkDisconnected
  )
    -> [String: Any]
  {
    let nsError = error as NSError?
    let detail = ControllerDisconnectDiagnostic(
      initiator: cancellationReason == nil ? "peer_or_system" : "app",
      reason: cancellationReason ?? defaultReason,
      errorDomain: nsError.map { TelemetryDiagnosticLog.safeErrorDomain($0.domain) }, errorCode: nsError?.code,
      connectionSeconds: connectionSeconds(at: now), sampleAgeSeconds: sampleAge(at: now))
    var fields = detail.dictionary
    fields["linkSamples"] = linkSamples
    if let cancellationStarted { fields["cancellationCallbackSeconds"] = max(0, now - cancellationStarted) }
    if let cancelledConnectionSeconds { fields["connectionSecondsAtCancel"] = cancelledConnectionSeconds }
    if let cancelledSampleAgeSeconds { fields["sampleAgeSecondsAtCancel"] = cancelledSampleAgeSeconds }
    lastDisconnect = detail
    linkStarted = nil
    cancellationReason = nil
    cancellationStarted = nil
    resetWindow(at: now)
    return fields
  }

  mutating func receivedSample(at now: Double, responseSeconds: Double) -> Double? {
    let gap = lastSample.flatMap { now - $0 > TelemetryTiming.shared.telemetryTimeoutSeconds ? now - $0 : nil }
    if let gap {
      lastGapSeconds = gap
      resetWindow(at: now)
    }
    sampleCount += 1
    linkSamples += 1
    lastSample = now
    if windowFirstSample == nil { windowFirstSample = now }
    windowLastSample = now
    windowSamples += 1
    if responseSeconds.isFinite, responseSeconds >= 0 {
      let milliseconds = responseSeconds * 1000
      latencySumMs += milliseconds
      latencyMaxMs = max(latencyMaxMs, milliseconds)
      latencyCount += 1
    }
    return gap
  }

  func recentSampleHz(at now: Double) -> Double? {
    guard let first = windowFirstSample, let last = windowLastSample,
      windowSamples > 1, last > first, now - last <= TelemetryTiming.shared.telemetryTimeoutSeconds
    else { return nil }
    return Double(windowSamples - 1) / (last - first)
  }

  var responseLatencyMs: [String: Double]? {
    guard latencyCount > 0 else { return nil }
    return ["mean": latencySumMs / Double(latencyCount), "max": latencyMaxMs]
  }

  mutating func resetWindow(at now: Double) {
    windowFirstSample = nil
    windowLastSample = nil
    windowSamples = 0
    latencySumMs = 0
    latencyMaxMs = 0
    latencyCount = 0
    nextSummary = now + 30
  }
}

/// Bounded, private JSONL. The serial log queue avoids making diagnostics part of BLE latency.
final class TelemetryDiagnosticLog {
  static let maximumFileBytes = 4 * 1024 * 1024
  private let queue = DispatchQueue(label: "app.powerlog.controller.diagnostics", qos: .utility)
  private let directory: URL
  private let currentURL: URL
  private let previousURL: URL
  private let byteLimit: Int
  private var handle: FileHandle?
  private var currentBytes = 0
  private var writeError: Error?

  init(directory: URL? = nil, byteLimit: Int = TelemetryDiagnosticLog.maximumFileBytes) throws {
    self.directory =
      try directory
      ?? FileManager.default.url(
        for: .applicationSupportDirectory,
        in: .userDomainMask, appropriateFor: nil, create: true
      ).appendingPathComponent("PowerLog/diagnostics", isDirectory: true)
    self.currentURL = self.directory.appendingPathComponent("events.jsonl")
    self.previousURL = self.directory.appendingPathComponent("events.previous.jsonl")
    self.byteLimit = max(512, min(byteLimit, Self.maximumFileBytes))
    try FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
    #if os(iOS)
      try FileManager.default.setAttributes(
        [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: self.directory.path)
    #endif
    var resourceURL = self.directory
    var values = URLResourceValues()
    values.isExcludedFromBackup = true
    try resourceURL.setResourceValues(values)
    try recover(previousURL)
    if FileManager.default.fileExists(atPath: currentURL.path) {
      try recover(currentURL)
    } else {
      try Data().write(to: currentURL, options: .atomic)
    }
    handle = try FileHandle(forWritingTo: currentURL)
    currentBytes = Int(try handle?.seekToEnd() ?? 0)
  }

  static let numberFields: Set<String> = [
    "requestedHz", "sampleCount", "connectionAttempts", "reconnects", "requestTimeouts",
    "decoderDiscardedBytes", "lastSampleAgeSeconds", "gapSeconds", "linkSamples", "connectionSeconds",
    "sampleAgeSeconds",
    "cancellationCallbackSeconds", "connectionSecondsAtCancel", "sampleAgeSecondsAtCancel", "requestAgeSeconds",
    "writeBlockedSeconds", "retryAttempt", "retryDelaySeconds", "errorCode", "recentSampleHz", "latencyMeanMs",
    "latencyMaxMs",
    "notificationErrors", "pendingBytes", "queueDelaySeconds", "storageWriteMs",
    "connectionGeneration", "cpuSeconds", "thermalState", "thermalStatus",
  ]
  static let booleanFields: Set<String> = [
    "background", "systemReconnect", "recordingFailed", "interactive", "powerSave", "deviceIdle",
  ]
  static let stringFields: [String: Set<String>] = [
    "initiator": ["app", "peer_or_system"],
    "request": ["identity", "telemetry"],
    "manager": ["central"],
    "status": ["idle", "scanning", "connecting", "connected", "reconnecting", "error"],
    "bluetoothState": ["unknown", "resetting", "unsupported", "unauthorized", "poweredOff", "poweredOn"],
  ]
  static let errorDomains: Set<String> = [
    "CBErrorDomain", "CBATTErrorDomain", "NSCocoaErrorDomain", "NSPOSIXErrorDomain", "NSOSStatusErrorDomain",
    "AndroidGattStatus", "AndroidBluetoothStatus",
  ]

  static func safeErrorDomain(_ domain: String) -> String {
    errorDomains.contains(domain) ? domain : "OtherErrorDomain"
  }

  static func errorFields(_ error: Error?) -> [String: Any] {
    guard let error = error as NSError? else { return [:] }
    return ["errorDomain": safeErrorDomain(error.domain), "errorCode": error.code]
  }

  func append(_ event: TelemetryDiagnosticEvent, elapsedSeconds: Double, fields: [String: Any] = [:]) {
    guard elapsedSeconds.isFinite, elapsedSeconds >= 0 else { return }
    var entry = Self.sanitized(fields)
    entry["schemaVersion"] = 1
    entry["timestamp"] = WorkoutCoding.timestamp(Date())
    entry["elapsedSeconds"] = elapsedSeconds
    entry["event"] = event.rawValue
    guard var bytes = try? JSONSerialization.data(withJSONObject: entry, options: [.sortedKeys]),
      bytes.count + 1 <= byteLimit
    else { return }
    bytes.append(10)
    let payload = bytes
    queue.async {
      do {
        if self.currentBytes + payload.count > self.byteLimit { try self.rotate() }
        try self.handle?.write(contentsOf: payload)
        try self.handle?.synchronize()
        self.currentBytes += payload.count
      } catch { self.writeError = error }
    }
  }

  func read() throws -> String {
    try queue.sync {
      try handle?.synchronize()
      if writeError != nil { throw ControllerIOError.invalid("Private diagnostic storage is unavailable.") }
      let bytes = try readBounded(previousURL) + readBounded(currentURL)
      guard let text = String(data: bytes, encoding: .utf8) else {
        throw ControllerIOError.invalid("Private diagnostic log is not UTF-8.")
      }
      return text
    }
  }

  private func rotate() throws {
    try handle?.synchronize()
    try handle?.close()
    handle = nil
    if FileManager.default.fileExists(atPath: previousURL.path) { try FileManager.default.removeItem(at: previousURL) }
    try FileManager.default.moveItem(at: currentURL, to: previousURL)
    try Data().write(to: currentURL, options: .atomic)
    handle = try FileHandle(forWritingTo: currentURL)
    currentBytes = 0
  }

  // Recover only bounded complete lines after a process interruption.
  private func recover(_ url: URL) throws {
    guard FileManager.default.fileExists(atPath: url.path) else { return }
    let reader = try FileHandle(forReadingFrom: url)
    let size = try reader.seekToEnd()
    var complete = size == 0
    if size > 0 {
      try reader.seek(toOffset: size - 1)
      complete = try reader.read(upToCount: 1) == Data([10])
    }
    try reader.close()
    if complete, size <= UInt64(byteLimit) { return }
    try readBounded(url).write(to: url, options: .atomic)
  }

  private func readBounded(_ url: URL) throws -> Data {
    guard FileManager.default.fileExists(atPath: url.path) else { return Data() }
    let reader = try FileHandle(forReadingFrom: url)
    defer { try? reader.close() }
    let size = try reader.seekToEnd()
    let offset = size > UInt64(byteLimit) ? size - UInt64(byteLimit) : 0
    try reader.seek(toOffset: offset)
    var data = try reader.read(upToCount: byteLimit) ?? Data()
    // A retained tail starts in an unknown line; omit that line rather than emit malformed JSON.
    if offset > 0, let newline = data.firstIndex(of: 10) {
      data.removeSubrange(...newline)
    } else if offset > 0 {
      return Data()
    }
    guard let lastNewline = data.lastIndex(of: 10) else { return Data() }
    data.removeSubrange(data.index(after: lastNewline)..<data.endIndex)
    return data
  }

  private static func sanitized(_ fields: [String: Any]) -> [String: Any] {
    var safe: [String: Any] = [:]
    for (key, value) in fields {
      if numberFields.contains(key), let number = value as? NSNumber, number.doubleValue.isFinite {
        safe[key] = number
      } else if booleanFields.contains(key), let value = value as? Bool {
        safe[key] = value
      } else if key == "transportStage", let value = value as? String,
        WorkoutCoreCodec.values(TelemetryStage.values()).contains(where: { $0.name == value })
      {
        safe[key] = value
      } else if key == "reason", let value = value as? String, TelemetryDiagnosticReason(rawValue: value) != nil {
        safe[key] = value
      } else if key == "errorDomain", let value = value as? String {
        safe[key] = safeErrorDomain(value)
      } else if key == "rejection", let value = value as? String,
        WorkoutCoreCodec.values(CycRejection.values()).contains(where: { $0.name == value })
      {
        safe[key] = value
      } else if let value = value as? String, stringFields[key]?.contains(value) == true {
        safe[key] = value
      }
    }
    return safe
  }
}
