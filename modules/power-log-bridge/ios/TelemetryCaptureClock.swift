import Foundation
import PowerLogCore

struct TelemetryCaptureClock {
  static let processEpoch = UUID().uuidString.lowercased()
  let sessionID: String
  let epochID: String
  let origin: Double
  let wallOrigin: Date
  private(set) var sequence: Int64 = 0
  private var previousWall: Date?
  private var previousMonotonic: Double?

  init(
    origin: Double, wallOrigin: Date = Date(), sessionID: String = UUID().uuidString.lowercased(),
    epochID: String = TelemetryCaptureClock.processEpoch
  ) {
    self.origin = origin
    self.wallOrigin = wallOrigin
    self.sessionID = sessionID
    self.epochID = epochID
  }

  mutating func observation(
    _ reading: TelemetryReading, connectionEpoch: String, monotonic: Double, wall: Date = Date()
  ) throws -> [String: Any] {
    guard sequence < Int64.max else { throw ControllerIOError.invalid("Telemetry sequence is exhausted.") }
    let discontinuity: KotlinDouble?
    if let previousWall, let previousMonotonic {
      discontinuity = try TelemetryCaptureAdmission.shared.discontinuity(
        previousWallClockSeconds: previousWall.timeIntervalSinceReferenceDate,
        wallClockSeconds: wall.timeIntervalSinceReferenceDate,
        previousAcquisitionMonotonic: previousMonotonic, acquisitionMonotonic: monotonic)
    } else {
      discontinuity = nil
    }
    let evidence = try TelemetryCaptureEvidence(
      rideId: sessionID, observationId: UUID().uuidString.lowercased(), captureSessionId: sessionID,
      clockEpoch: epochID, connectionEpoch: connectionEpoch, sequence: sequence + 1,
      timestamp: Timestamps.shared.parse(original: WorkoutCoding.timestamp(wall)),
      acquisitionMonotonic: monotonic, sourceElapsedSeconds: monotonic - origin, telemetry: reading,
      clockDiscontinuitySeconds: discontinuity)
    sequence = evidence.sequence
    previousWall = wall
    previousMonotonic = monotonic
    return TelemetryRecords.sample(evidence)
  }
}
