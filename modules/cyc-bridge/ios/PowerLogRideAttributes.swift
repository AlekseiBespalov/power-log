#if os(iOS)
import ActivityKit
import Foundation

@available(iOS 16.2, *)
struct PowerLogRideAttributes: ActivityAttributes {
  struct ContentState: Codable, Hashable {
    var phase: String
    var pendingAction: String?
    var timerSeconds: Double
    var observedAt: Date
    var lastBikeSampleAt: Date?
    var lastHeartSampleAt: Date?
    var riderPowerW: Double?
    var heartRateBpm: Double?
    var controlToken: String

    var isRunning: Bool { phase == "running" }
    var isBikeUnavailable: Bool {
      guard let lastBikeSampleAt else { return false }
      let age = observedAt.timeIntervalSince(lastBikeSampleAt)
      return age < 0 || age >= 6
    }
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
#endif
