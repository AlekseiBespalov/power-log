import Foundation
#if os(iOS)
  import ActivityKit
#endif

@available(iOS 16.2, *)
struct PowerLogRideAttributes {
  struct ContentState: Codable, Hashable {
    var phase: String
    var pendingAction: String?
    var timerSeconds: Double
    var observedAt: Date
    var observedUptime: Double
    var bikeSampleAgeSeconds: Double?
    var heartSampleAgeSeconds: Double?
    var riderPowerW: Double?
    var heartRateBpm: Double?
    var controlToken: String

    var isRunning: Bool { phase == "running" }
    var bikeAge: Double? {
      bikeSampleAgeSeconds.map { $0 + max(0, ProcessInfo.processInfo.systemUptime - observedUptime) }
    }
    var heartAge: Double? {
      heartSampleAgeSeconds.map { $0 + max(0, ProcessInfo.processInfo.systemUptime - observedUptime) }
    }
    static func isFresh(_ age: Double?, maximumAge: Double) -> Bool {
      age.map { $0.isFinite && $0 >= 0 && $0 < maximumAge } ?? false
    }
    var isBikeUnavailable: Bool { !Self.isFresh(bikeAge, maximumAge: 6) }
    var canControl: Bool { ["running", "paused"].contains(phase) && pendingAction == nil }
    var timerOrigin: Date { observedAt.addingTimeInterval(-timerSeconds) }
    var status: String {
      if pendingAction != nil { return "Updating…" }
      switch phase {
      case "running":
        if isBikeUnavailable { return "Bike unavailable" }
        return "Recording"
      case "paused": return "Paused"
      case "preparing": return "Starting…"
      case "recoverable": return "Open Power Log"
      case "finishing": return "Finishing…"
      default: return "Ride ended"
      }
    }
  }
  var rideID: String
}

#if os(iOS)
  @available(iOS 16.2, *)
  extension PowerLogRideAttributes: ActivityAttributes {}
#endif
