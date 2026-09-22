import Foundation

/// A launch opens the Watch before the separate, identified start command can arrive.
/// This is presentation only: it never owns a workout or changes command admission.
struct WatchStartHandoff {
  private(set) var beganAt: Date?
  static let timeout: TimeInterval = 45

  mutating func begin(at now: Date, phase: String, hasRide: Bool, canStart: Bool, hasIssue: Bool) {
    guard !isWaiting(at: now), phase == "ready", !hasRide, canStart, !hasIssue else { return }
    beganAt = now
  }

  mutating func clear() { beganAt = nil }

  mutating func expire(at now: Date) {
    if beganAt != nil && !isWaiting(at: now) { clear() }
  }

  func isWaiting(at now: Date) -> Bool {
    guard let beganAt else { return false }
    let age = now.timeIntervalSince(beganAt)
    return age >= 0 && age < Self.timeout
  }
}

enum WatchWorkoutPresentation: Equatable {
  case ready, preparing, saving, discarding, saved, discarded, startFailed, attention

  static func idle(phase: String, busy: Bool, recovering: Bool, finishing: Bool,
    stopPending: Bool, hasNativeSession: Bool, discarded: Bool, discardPending: Bool,
    hasIssue: Bool, awaitingStart: Bool) -> Self {
    if finishing { return discardPending ? .discarding : .saving }
    if phase == "finished" {
      if discarded { return .discarded }
      if discardPending { return hasIssue ? .attention : .discarding }
      if stopPending || hasNativeSession { return hasIssue ? .attention : .saving }
      return .saved
    }
    if busy || recovering { return .preparing }
    if phase == "failed" { return .startFailed }
    if phase == "recoverable" { return .attention }
    if phase == "ready", !hasIssue, awaitingStart { return .preparing }
    return .ready
  }

  var title: String {
    switch self {
    case .ready: return "Ready to ride"
    case .preparing: return "Preparing ride…"
    case .saving: return "Saving ride…"
    case .discarding: return "Discarding ride…"
    case .saved: return "Ride saved"
    case .discarded: return "Ride discarded"
    case .startFailed: return "Unable to start"
    case .attention: return "Ride needs attention"
    }
  }

  var showsProgress: Bool { self == .preparing || self == .saving || self == .discarding }

  static func savedDetail(saveToHealth: Bool, healthState: String) -> String {
    if !saveToHealth { return "Saved on Watch. Health saving is off." }
    return healthState == "saved" ? "Saved on Watch and in Health." : "Saved on Watch. Health save needs attention."
  }
}
