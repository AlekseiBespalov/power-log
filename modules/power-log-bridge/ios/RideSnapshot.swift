import Foundation
import PowerLogCore

struct RideWatchSnapshot {
  let installed: Bool
}

struct RideStreamSnapshot {
  let status: String
}

struct RideGPSSnapshot {
  enum Source: String {
    case phone, watch
  }
  let status: String
  let source: Source
  let accuracyMeters: Double?
}

struct RideStreamsSnapshot {
  let controller: RideStreamSnapshot
  let heartRate: RideStreamSnapshot
  let gps: RideGPSSnapshot
}

struct RideSnapshot {
  let supported: Bool
  let capabilities: RideCapabilities
  let id: String?
  let phase: String
  let pendingAction: String?
  let timerSeconds: Double
  let historyRevision: String
  let lastDeletedWorkoutId: String?
  let collectionRevision: Int64?
  let indoor: Bool
  let useWatch: Bool
  let saveToHealth: Bool
  let recordGPS: Bool
  let recoveryState: String
  let recoveryMessage: String?
  let healthKitState: String
  let watch: RideWatchSnapshot
  let streams: RideStreamsSnapshot
  let error: String?
  var healthReason: String? = nil
  var failure: WorkoutRideFailure? = nil
  var sessionBusy = false
  var endingOwner = false

  var wireMap: [String: Any] {
    var result: [String: Any] = [
      "supported": supported,
      "capabilities": [
        "phoneWorkout": capabilities.phoneWorkout, "watchWorkout": capabilities.watchWorkout,
        "phoneHealth": capabilities.phoneHealth, "watchHealth": capabilities.watchHealth,
        "healthProvider": capabilities.healthProvider as Any? ?? NSNull(),
        "gps": capabilities.gps, "foregroundOnly": capabilities.foregroundOnly,
      ],
      "id": id as Any? ?? NSNull(), "phase": phase,
      "pendingAction": pendingAction as Any? ?? NSNull(), "timerSeconds": timerSeconds,
      "historyRevision": historyRevision.isEmpty ? "0" : historyRevision,
      "lastDeletedWorkoutId": lastDeletedWorkoutId as Any? ?? NSNull(),
      "collectionRevision": collectionRevision.map(String.init) as Any? ?? NSNull(),
      "indoor": indoor, "useWatch": useWatch, "saveToHealth": saveToHealth, "recordGPS": recordGPS,
      "recoveryState": recoveryState, "recoveryMessage": recoveryMessage as Any? ?? NSNull(),
      "healthKitState": healthKitState,
      "watch": ["installed": watch.installed],
      "streams": [
        "controller": ["status": streams.controller.status], "heartRate": ["status": streams.heartRate.status],
        "gps": [
          "status": streams.gps.status, "source": streams.gps.source.rawValue,
          "accuracyMeters": streams.gps.accuracyMeters as Any? ?? NSNull(),
        ],
      ],
      "error": error as Any? ?? NSNull(),
      "failure": failure?.wire as Any? ?? NSNull(), "sessionBusy": sessionBusy, "endingOwner": endingOwner,
    ]
    if let healthReason { result["healthReason"] = healthReason }
    return result
  }
}
