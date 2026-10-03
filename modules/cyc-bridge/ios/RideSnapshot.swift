import Foundation

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
  let cyc: RideStreamSnapshot
  let heartRate: RideStreamSnapshot
  let gps: RideGPSSnapshot
}

struct RideSnapshot {
  let supported: Bool
  let capabilities: WorkoutCapabilities
  let id: String?
  let phase: String
  let pendingAction: String?
  let timerSeconds: Double
  let historyRevision: String
  let lastDeletedWorkoutId: String?
  let collectionRevision: Int64?
  let sealRevision: Int64?
  let verifiedSealRevision: Int64?
  let finalizationState: String?
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

  var wireMap: [String: Any] {
    [
      "supported": supported,
      "capabilities": [
        "phoneWorkout": capabilities.phoneWorkout, "watchWorkout": capabilities.watchWorkout,
        "phoneHealth": capabilities.phoneHealth, "watchHealth": capabilities.watchHealth,
        "healthProvider": capabilities.healthProvider as Any? ?? NSNull(),
        "gps": capabilities.gps, "foregroundOnly": capabilities.foregroundOnly,
      ],
      "id": id as Any? ?? NSNull(), "phase": phase,
      "pendingAction": pendingAction as Any? ?? NSNull(), "timerSeconds": timerSeconds,
      "historyRevision": historyRevision, "lastDeletedWorkoutId": lastDeletedWorkoutId as Any? ?? NSNull(),
      "collectionRevision": collectionRevision as Any? ?? NSNull(),
      "sealRevision": sealRevision as Any? ?? NSNull(),
      "verifiedSealRevision": verifiedSealRevision as Any? ?? NSNull(),
      "finalizationState": finalizationState as Any? ?? NSNull(),
      "indoor": indoor, "useWatch": useWatch, "saveToHealth": saveToHealth, "recordGPS": recordGPS,
      "recoveryState": recoveryState, "recoveryMessage": recoveryMessage as Any? ?? NSNull(),
      "healthKitState": healthKitState, "watch": ["installed": watch.installed],
      "streams": [
        "cyc": ["status": streams.cyc.status], "heartRate": ["status": streams.heartRate.status],
        "gps": [
          "status": streams.gps.status, "source": streams.gps.source.rawValue,
          "accuracyMeters": streams.gps.accuracyMeters as Any? ?? NSNull(),
        ],
      ],
      "error": error as Any? ?? NSNull(),
    ]
  }
}
