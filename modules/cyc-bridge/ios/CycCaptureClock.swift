import Foundation

/// A physical packet's identity is minted once, before live/recording/workout fan-out.
/// Reconnects keep this clock; an explicit connection or process restoration creates another.
struct CycCaptureClock {
  static let processEpoch = UUID().uuidString.lowercased()
  let sessionID: String
  let epochID: String
  let origin: Double
  let wallOrigin: Date
  private(set) var sequence: Int64 = 0
  private var previousWall: Date?
  private var previousMonotonic: Double?

  init(origin: Double, wallOrigin: Date = Date(), sessionID: String = UUID().uuidString.lowercased(),
       epochID: String = CycCaptureClock.processEpoch) {
    self.origin = origin; self.wallOrigin = wallOrigin
    self.sessionID = sessionID; self.epochID = epochID
  }

  mutating func observation(_ values: [String: Double], monotonic: Double, wall: Date = Date()) -> [String: Any] {
    precondition(sequence < Int64.max && monotonic.isFinite && monotonic >= origin)
    sequence += 1
    var sample: [String: Any] = values
    sample["timestamp"] = WorkoutCoding.timestamp(wall)
    sample["observationId"] = UUID().uuidString.lowercased()
    sample["captureSessionID"] = sessionID
    // Decimal strings survive the JavaScript bridge without losing integer precision.
    sample["observationSequence"] = String(sequence)
    sample["clockEpoch"] = epochID
    sample["acquisitionMonotonic"] = monotonic
    sample["sourceElapsedSeconds"] = monotonic - origin
    if let previousWall, let previousMonotonic {
      let discontinuity = wall.timeIntervalSince(previousWall) - (monotonic - previousMonotonic)
      if abs(discontinuity) > 0.25 { sample["clockDiscontinuitySeconds"] = discontinuity }
    }
    previousWall = wall; previousMonotonic = monotonic
    return sample
  }
}
