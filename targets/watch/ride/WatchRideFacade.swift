import Foundation

@MainActor protocol WatchRideFacade: AnyObject {
  var facts: WatchRideFacts { get }
  var options: WatchOptions { get }
  func setOptions(_ options: WatchOptions)
  func start()
  func cancelStart()
  func pause()
  func resume()
  func lap()
  func finish(rideID: String, discard: Bool)
}

struct WatchRideFacts: Equatable {
  enum Phase { case idle, preparing, running, paused, interrupted, completed }

  var phase: Phase = .idle
  var rideID: String?
  var timer: RideTimer?
  var display: WatchDisplay?
  var heartRate: LiveHeartRate?
  var units: UnitSystem = .metric
  var recordGps = false
  var distanceSource: DistanceSource?
  var lapCount = 0
  var healthOutcome: HealthOutcome?
  var issue: WatchIssue?
  var available: [WatchControl: WatchAvailability] = [:]
}

enum WatchControl: CaseIterable { case start, options, cancelStart, pause, resume, lap, finish, discard }

struct WatchAvailability: Equatable {
  let enabled: Bool
  let reason: WatchReason?

  static let enabled = WatchAvailability(enabled: true, reason: nil)
  static func disabled(_ reason: WatchReason) -> WatchAvailability { WatchAvailability(enabled: false, reason: reason) }
}

enum WatchReason { case phoneUnreachable, commandPending, sessionBusy, noActiveRide }

enum WatchIssue: Equatable {
  case failure(String)
  case phoneUnreachableAtStart
  case commandUnconfirmed
  case endedLocallyUnreachable
  case endedLocallyUnconfirmed
  case orphanRecovered
}
