import Foundation
import PowerLogCore

/// The phone reduces only the small Health publication window. Archive and chart originals stay untouched.
final class WorkoutHealthSeconds {
  private var readings: [String: [SecondReading]] = [:]
  private var controller = ControllerDistanceKernel()
  private var gps = WorkoutGPSDistanceAccumulator()
  private struct Delta {
    var meters: Double
    var start: Double
    var end: Double
  }
  private var gpsDeltas: [Int64: Delta] = [:]
  private(set) var gpsMeters = 0.0
  private(set) var controllerMeters = 0.0
  private var interval: Int32 = 0
  private var lastEmitted = Int64.min
  func boundary() {
    interval += 1
    readings.removeAll()
    controller = ControllerDistanceKernel()
    gps.reset()
  }
  func append(_ event: WorkoutEvent) throws {
    guard let elapsed = event.elapsedSeconds else { return }
    let utc = try WorkoutCoding.timestampMicroseconds(event.timestamp)
    let collection = Int64((elapsed * 1_000_000).rounded())
    let source: Source = event.kind == "telemetry" ? .controller : .phone
    let continuity = Continuity(
      clockEpoch: event.payload["clockEpoch"]?.string,
      connectionEpoch: event.payload["connectionEpoch"]?.string, interruptionIndex: interval)
    let sequence = Int64(event.payload["observationSequence"]?.string ?? "") ?? max(1, collection + 1)
    let origin = Origin(
      provider: nil, dataOrigin: nil, providerObservationId: nil, originEventId: event.eventId,
      producer: source, sequence: sequence, originRevision: nil)
    let mapping: [(String, String)] =
      event.kind == "telemetry"
      ? [("riderPowerW", "humanPowerW"), ("cadenceRpm", "cadenceRpm")] : [("gpsSpeedMps", "speedMps")]
    for (key, field) in mapping {
      let reading = SecondReading(
        origin: origin, originalUTC: event.timestamp, utcUs: utc, collectionUs: collection,
        source: MeasurementSource(
          source: source, device: source == .controller ? .controller : .iphone, provider: nil, dataOrigin: nil),
        activeInterval: KotlinInt(value: interval), continuity: continuity,
        originalScalar: event.number(field).map { Scalar.Real64(value: $0) }, admittedQ: nil,
        barrier: event.payload["distanceBarrier"] == .bool(true),
        horizontalAccuracyM: event.number("horizontalAccuracyM").map(KotlinDouble.init(value:)),
        speedAccuracyMps: event.number("speedAccuracyMps").map(KotlinDouble.init(value:)))
      readings[key, default: []].append(reading)
      if readings[key]!.count > 128 { readings[key]!.removeFirst(readings[key]!.count - 128) }
    }
    if event.kind == "location" {
      let fix = WorkoutGPSFix(
        time: elapsed, latitude: event.number("latitude") ?? .nan,
        longitude: event.number("longitude") ?? .nan, horizontalAccuracy: event.number("horizontalAccuracyM") ?? -1,
        speed: event.number("speedMps"), speedAccuracy: event.number("speedAccuracyMps"), epoch: continuity.clockEpoch,
        activeInterval: Int(interval), identity: event.eventId, timestamp: event.timestamp,
        barrier: event.payload["distanceBarrier"] == .bool(true), originalElapsedSeconds: elapsed,
        collectionUs: collection)
      if let edge = gps.append(fix) {
        gpsMeters += edge.meters
        try addDelta(
          &gpsDeltas, second: utc / 1_000_000, meters: edge.meters, start: edge.startTimestamp, end: edge.endTimestamp)
      }
      return
    }
    guard event.kind == "telemetry" else { return }
    let speed = event.number("controllerSpeedMps")
    let evidence = ControllerDistanceEvidence(
      timeUs: collection, originalTime: elapsed,
      speedMps: speed.map(KotlinDouble.init(value:)), model: event.payload["controllerModel"]?.string,
      protocol: event.payload["controllerProtocol"]?.string, deviceIdentity: event.payload["captureSessionID"]?.string,
      continuityIdentity: [continuity.clockEpoch, continuity.connectionEpoch].compactMap { $0 }.joined(separator: ":"),
      activeInterval: KotlinInt(value: interval),
      monotonicSeconds: event.number("acquisitionMonotonic").map(KotlinDouble.init(value:)),
      counter: Scalar.Integer64(value: sequence), barrier: false,
      anchor: Original(
        observationId: event.eventId, eventId: event.eventId, timestamp: event.timestamp,
        elapsedSeconds: elapsed, value: Scalar.Real64(value: speed ?? 0),
        originalElapsedSeconds: KotlinDouble(value: elapsed)))
    if let edge = controller.append(current: evidence) {
      let meters = Double(edge.distanceQ) / 1_000_000
      controllerMeters += meters
    }
  }
  private func addDelta(_ target: inout [Int64: Delta], second: Int64, meters: Double, start: String, end: String)
    throws
  {
    let start = try WorkoutCoding.date(start).timeIntervalSince1970
    let end = try WorkoutCoding.date(end).timeIntervalSince1970
    let previous = target[second]
    target[second] = Delta(
      meters: (previous?.meters ?? 0) + meters, start: min(previous?.start ?? start, start),
      end: max(previous?.end ?? end, end))
  }
  func drain(now: Date, force: Bool = false) throws -> (
    second: Int64, values: [String: Double]
  )? {
    let cutoff = Int64(floor(now.timeIntervalSince1970)) - (force ? 0 : 3)
    let eligible = readings.values.flatMap { $0 }.map { $0.utcUs / 1_000_000 }.filter { $0 <= cutoff }
    guard let second = eligible.filter({ $0 > lastEmitted }).min() else { return nil }
    lastEmitted = second
    var output: [String: Double] = [:]
    for (key, kind) in [
      ("riderPowerW", HealthKind.riderpower), ("cadenceRpm", .cyclingcadence), ("gpsSpeedMps", .cyclingspeed),
    ] {
      let values = readings[key] ?? []
      let result = try SecondKernel.shared.aggregate(
        bounds: SecondBounds(n: second, startUs: second * 1_000_000, endUs: (second + 1) * 1_000_000),
        kind: kind, readings: WorkoutCoreCodec.array(values))
      if let sample = result as? SecondOutcome.Sample { output[key] = sample.sample.value }
      readings[key] = values.filter { $0.utcUs >= (second - 10) * 1_000_000 }
    }
    if let delta = gpsDeltas[second] {
      output["distanceDeltaMeters"] = delta.meters
      output["distanceStartTime"] = delta.start
      output["distanceEndTime"] = delta.end
    }
    gpsDeltas = gpsDeltas.filter { $0.key > second }
    return (second, output)
  }
}
