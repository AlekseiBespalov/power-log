import Foundation
import PowerLogCore

var assertions = 0
func check(_ condition: Bool, _ message: @autoclosure () -> String) {
  assertions += 1
  if !condition { fatalError(message()) }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent(
  "powerlog-energy-per-distance-\(UUID().uuidString)/PowerLog")
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
let store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: store)
let origin = Date(timeIntervalSince1970: 1_780_000_000)
typealias EnergyInput = MonitorDataStore.EnergyInput

final class SyntheticRide {
  let id: String
  private let session = UUID().uuidString.lowercased()
  private var connection = UUID().uuidString.lowercased()
  private var clock = UUID().uuidString.lowercased()
  private var counter = 0
  private var consumed = 1_000.0
  init(distanceSource: String, battery: String) throws {
    id = try archive.create(
      startedAt: origin, indoor: false, watchEnabled: false, saveToHealth: false, recordGPS: true, sampleHz: 8,
      distanceSource: distanceSource, batteryConsumptionSource: battery
    ).id
    try append([lifecycle(0, "start")])
    try archive.update(id: id, phase: "running")
  }
  func reconnect() {
    connection = UUID().uuidString.lowercased()
    clock = UUID().uuidString.lowercased()
  }
  func telemetry(
    _ time: Double, power: Double = 150, motor: Double? = 400, speed: Double? = 6, battery: Bool = true
  ) throws -> WorkoutEvent {
    counter += 1
    var payload: [String: WorkoutJSON] = [
      "humanPowerW": .number(power), "cadenceRpm": .number(80), "controllerModel": .string("X6"),
      "controllerProtocol": .string("5.3"), "captureSessionID": .string(session),
      "connectionEpoch": .string(connection), "clockEpoch": .string(clock),
      "observationSequence": .string(String(counter)), "monotonicSeconds": .number(time),
    ]
    if battery {
      consumed += 0.02
      payload["batteryVoltageV"] = .number(50)
      payload["batteryCurrentA"] = .number(8 + Double(counter % 5))
      payload["consumedWh"] = .number(consumed)
    }
    if let motor { payload["motorInputPowerW"] = .number(motor) }
    if let speed { payload["controllerSpeedMps"] = .number(speed) }
    return try WorkoutEvent(
      workoutId: id, kind: "telemetry", source: "controller", timestamp: origin.addingTimeInterval(time),
      elapsedSeconds: time, payload: payload)
  }
  func location(_ time: Double, speed: Double = 6, barrier: Bool = false, source: String = "phone") throws
    -> WorkoutEvent
  {
    var payload: [String: WorkoutJSON] = [
      "latitude": .number(0), "longitude": .number(time * 0.00005), "horizontalAccuracyM": .number(3),
      "speedMps": .number(speed), "speedAccuracyMps": .number(0.5),
    ]
    if barrier { payload["distanceBarrier"] = .bool(true) }
    return try WorkoutEvent(
      workoutId: id, kind: "location", source: source, timestamp: origin.addingTimeInterval(time),
      elapsedSeconds: time, payload: payload)
  }
  func lifecycle(_ time: Double, _ action: String, interrupted: Bool = false) throws -> WorkoutEvent {
    var payload: [String: WorkoutJSON] = ["action": .string(action)]
    if interrupted {
      payload["interrupted"] = .bool(true)
      payload["clockEpoch"] = .string(clock)
      payload["timerSeconds"] = .number(time)
      payload["controllerSequence"] = .string(
        String(try archive.sourceProgress(id: id, producer: "controller").lastSequence))
    }
    return try WorkoutEvent(
      workoutId: id, kind: "lifecycle", source: "phone", timestamp: origin.addingTimeInterval(time),
      elapsedSeconds: time, payload: payload)
  }
  func second(_ second: Int, rate: Int = 8, telemetry: Bool = true, location: Bool = true) throws {
    let start = Double(second - 1)
    var events = try telemetry ? (0..<rate).map { try self.telemetry(start + Double($0) / Double(rate)) } : []
    if location { events.append(try self.location(start + 0.5)) }
    try append(events)
    if second % 5 == 0 {
      try archive.update(
        id: id, phase: "running",
        ownerTiming: WorkoutOwnerTiming(
          timestamp: WorkoutCoding.timestamp(origin.addingTimeInterval(Double(second))),
          elapsedSeconds: Double(second), timerSeconds: Double(second)),
        elapsedSeconds: Double(second), onlyIfChanged: true)
    }
  }
  func append(_ events: [WorkoutEvent]) throws { _ = try store.appendBatch(events) }
}

func identical(_ a: Any, _ b: Any) -> Bool {
  switch (a, b) {
  case let (a as [String: Any], b as [String: Any]):
    return a.count == b.count && a.allSatisfy { key, value in b[key].map { identical(value, $0) } ?? false }
  case let (a as [Any], b as [Any]):
    return a.count == b.count && zip(a, b).allSatisfy { identical($0, $1) }
  case let (a as NSNumber, b as NSNumber):
    return a.isEqual(b) && a.doubleValue.bitPattern == b.doubleValue.bitPattern
  case let (a as String, b as String):
    return a == b
  case (is NSNull, is NSNull):
    return true
  default:
    return false
  }
}
func request(
  _ id: String, from: Double, to: Double, smoothing: Int = 0, totals: Bool = true, current: Bool = false,
  buckets: Int = 128
) -> MonitorRequest {
  var request = MonitorRequest(
    source: "workout", id: id, startSeconds: from, endSeconds: to, metrics: [], buckets: buckets)
  request.energySmoothingSeconds = smoothing
  request.energyTotals = totals
  request.energyCurrent = current
  return request
}
func observed(_ monitor: MonitorDataStore, _ request: MonitorRequest) throws -> (
  inputs: [EnergyInput], value: [String: Any]
) {
  var inputs: [EnergyInput] = []
  MonitorDataStore.energyInputObserverForTesting = { inputs.append($0) }
  defer { MonitorDataStore.energyInputObserverForTesting = nil }
  let value = try monitor.readEnergyPerDistance(request)
  check(value["status"] as? String == "ok", "a Wh/km read completes")
  return (inputs, value)
}
func difference(_ a: [EnergyInput], _ b: [EnergyInput]) -> String {
  let index = (0..<min(a.count, b.count)).first { a[$0] != b[$0] } ?? min(a.count, b.count)
  return "input \(index) of \(a.count) and \(b.count): \(index < a.count ? "\(a[index])" : "none") versus "
    + (index < b.count ? "\(b[index])" : "none")
}
func feed(_ input: EnergyInput, _ query: EnergyPerDistanceQuery) throws {
  switch input {
  case .distance(let startUs, let endUs, let meters, let speed0, let speed1):
    try query.appendDistance(
      startUs: startUs, endUs: endUs, meters: meters, speed0: speed0.map(KotlinDouble.init(value:)),
      speed1: speed1.map(KotlinDouble.init(value:)))
  case .battery(let startUs, let endUs, let wattHours, let rate0, let rate1, let indivisible, let timestamp):
    try query.appendBattery(
      startUs: startUs, endUs: endUs, wattHours: wattHours, rate0: rate0.map(KotlinDouble.init(value:)),
      rate1: rate1.map(KotlinDouble.init(value:)), indivisible: indivisible, timestamp: timestamp)
  case .speed(let timeUs, let mps, let accuracy, let activeInterval):
    try query.appendSpeed(
      timeUs: timeUs, mps: mps.map(KotlinDouble.init(value:)), accuracy: accuracy.map(KotlinDouble.init(value:)),
      activeInterval: activeInterval)
  case .telemetry(
    let timeUs, let watts, let batteryWatts, let speedMps, let activeInterval, let continuity, let timestamp):
    try query.appendTelemetry(
      timeUs: timeUs, watts: watts.map(KotlinDouble.init(value:)),
      batteryWatts: batteryWatts.map(KotlinDouble.init(value:)), speedMps: speedMps.map(KotlinDouble.init(value:)),
      activeInterval: activeInterval, continuity: continuity, timestamp: timestamp)
  }
}

final class StepCount: @unchecked Sendable {
  private let lock = NSLock()
  private var steps = 0
  func add(_ value: Int) {
    lock.lock()
    defer { lock.unlock() }
    steps += value
  }
  func take() -> Int {
    lock.lock()
    defer { lock.unlock() }
    defer { steps = 0 }
    return steps
  }
}
func describe(_ id: String) throws -> (revision: Int64, end: Double, metadata: [String: Any]) {
  try store.read { db in
    let row = try db.rows("SELECT * FROM collections WHERE id=? AND kind='workout'", [.text(id)], limit: 1).first!
    let revision = row.int("revision")!
    var metadata =
      (row.data("metadata").flatMap { try? JSONSerialization.jsonObject(with: $0) }) as? [String: Any] ?? [:]
    if let elapsed = metadata["elapsedSeconds"] as? Double {
      metadata["elapsedSeconds"] =
        try (metadata["stopElapsedSeconds"] as? Double) ?? max(elapsed, store.appElapsed(id: id, revision: revision))
    }
    return (revision, max(10, row.double("max_elapsed") ?? 0, metadata["elapsedSeconds"] as? Double ?? 0), metadata)
  }
}
func lifecycleTimeline(_ id: String, revision: Int64, end sourceEnd: Double, metadata: [String: Any]) throws
  -> ActiveTimeline
{
  var events: [AnalysisItem] = []
  var after: Int64 = 0
  while true {
    let rows = try store.read { db in
      try db.rows(
        "SELECT m.id,m.elapsed_seconds,m.elapsed_us,m.producer,m.sequence,l.action,o.physical_id,o.original_timestamp,o.clock_epoch,json_extract(o.extra,'$.interrupted') AS interrupted,json_extract(o.extra,'$.controllerSequence') AS controller_sequence FROM collection_memberships m INDEXED BY membership_stream_time JOIN observations o ON o.id=m.observation_id JOIN lifecycle_records l ON l.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='lifecycle' AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL) AND m.id>? ORDER BY m.id LIMIT 256",
        [.text(id), .integer(revision), .integer(revision), .integer(revision), .integer(after)], limit: 256)
    }
    guard let last = rows.last else { break }
    for row in rows {
      events.append(
        AnalysisItem(
          collectionUs: row.int("elapsed_us")!, collectionElapsedSeconds: row.double("elapsed_seconds")!,
          eventId: row.string("physical_id")!, observationId: row.string("physical_id")!,
          producer: row.string("producer")!, sequence: row.int("sequence")!, kind: "lifecycle", source: "phone",
          timestamp: row.string("original_timestamp")!, values: [:], originalElapsedSeconds: nil,
          representation: nil,
          continuity: Continuity(clockEpoch: row.string("clock_epoch"), connectionEpoch: nil, interruptionIndex: 0),
          action: row.string("action"), distanceBarrier: false, interrupted: row.int("interrupted") == 1,
          controllerSequence: row.string("controller_sequence").flatMap(Int64.init).map(KotlinLong.init(value:)),
          hasElapsedMapping: true, origin: nil, declaredMetrics: nil))
    }
    after = last.int("id")!
    if rows.count < 256 { break }
  }
  var end = sourceEnd
  if let stop = metadata["stopElapsedSeconds"] as? Double { end = min(end, stop) }
  if let elapsed = metadata["elapsedSeconds"] as? Double, metadata["endedAt"] != nil { end = min(end, elapsed) }
  for event in events where event.action == "stop" { end = min(end, event.collectionElapsedSeconds) }
  return ActiveTimeline(endUs: WorkoutDistancePolicy.timeUs(end), lifecycle: events)
}
func unionRead(_ request: MonitorRequest) throws -> (inputs: [EnergyInput], payload: [String: Any]) {
  let id = request.id!
  let source = try describe(id)
  let selection = source.metadata["distanceSource"] as? String ?? request.distanceSource
  let whole =
    !request.energyTotals && !request.energyCurrent && request.startSeconds == 0
    && (request.endSeconds ?? source.end) >= source.end
  let end = whole ? source.end : request.endSeconds ?? (source.metadata["elapsedSeconds"] as? Double ?? source.end)
  let start = request.energyCurrent ? end : (request.startSeconds ?? end)
  let timeline = try lifecycleTimeline(id, revision: source.revision, end: source.end, metadata: source.metadata)
  let timer = timeline.intervals.reduce(Int64(0)) {
    $0
      + max(
        0, min(WorkoutDistancePolicy.timeUs(end), $1.endUs) - max(WorkoutDistancePolicy.timeUs(start), $1.startUs))
  }
  let slot =
    whole
    ? try InspectionPlotSlots.shared.alignedSlotUs(
      range: AnalysisRange(startUs: 0, endUs: WorkoutDistancePolicy.timeUs(end)), buckets: Int32(request.buckets))
    : nil
  let query = try EnergyPerDistanceQuery(
    startUs: WorkoutDistancePolicy.timeUs(start), endUs: WorkoutDistancePolicy.timeUs(end),
    buckets: Int32(request.buckets), alignedSlotUs: slot, timerUs: timer, controllerSpeed: selection == "controller",
    smoothingSeconds: Int32(request.energySmoothingSeconds), includeTotals: request.energyTotals)
  let held =
    request.energyTotals
    ? try WorkoutDistanceStore(store: store).leasedSnapshot(id: id, revision: source.revision, selection: selection)
    : nil
  defer { held?.lease.release() }
  let distance = held?.snapshot.source ?? selection
  let battery = (source.metadata["batteryConsumptionSource"] as? String ?? "controller") + "EnergyWh"
  let from = max(
    0, start - Double(EnergyPerDistancePolicy.shared.contextSeconds(seconds: Int32(request.energySmoothingSeconds))))
  let until = end + 10
  var inputs: [EnergyInput] = []
  var cursor = (time: from.nextDown, channel: Int64(-1), id: Int64(-1))
  var chunkStart = from
  var chunkEnd = min(until, from + 16)
  while true {
    let rows = try store.read { db in
      try db.rows(
        """
        SELECT * FROM (
          SELECT CASE source WHEN ? THEN 0 ELSE 1 END AS channel,point_id AS row_id,elapsed_seconds AS time,
            start_seconds AS a,end_seconds AS b,increment AS amount,start_speed AS u,end_speed AS v,
            indivisible,timestamp AS stamp,'' AS producer,0 AS seq,NULL AS clock,NULL AS connection,NULL AS watts,NULL AS power,NULL AS speed,NULL AS accuracy
          FROM distance_points INDEXED BY distance_point_time
          WHERE generation_id=? AND source IN (?,?) AND point_id<=? AND elapsed_seconds BETWEEN ? AND ?
          UNION ALL
          SELECT 3,m.observation_id,m.elapsed_seconds,m.elapsed_seconds,m.elapsed_seconds,0,NULL,NULL,0,
            o.original_timestamp,m.producer,m.sequence,o.clock_epoch,json_extract(o.extra,'$.connectionEpoch'),t.humanPowerW,t.motorInputPowerW,t.controllerSpeedMps,NULL
          FROM collection_memberships m JOIN observations o ON o.id=m.observation_id
            JOIN telemetry_frames t ON t.observation_id=m.observation_id
          WHERE m.collection_id=? AND m.kind='telemetry' AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL)
            AND m.elapsed_seconds BETWEEN ? AND ?
          UNION ALL
          SELECT 2,m.observation_id,m.elapsed_seconds,m.elapsed_seconds,m.elapsed_seconds,0,NULL,NULL,0,
            o.original_timestamp,m.producer,m.sequence,o.clock_epoch,NULL,NULL,NULL,
            CASE WHEN json_extract(o.extra,'$.distanceBarrier')=1 THEN NULL ELSE t.speedMps END,t.speedAccuracyMps
          FROM collection_memberships m JOIN observations o ON o.id=m.observation_id
            JOIN locations t ON t.observation_id=m.observation_id
          WHERE m.collection_id=? AND m.kind='location' AND m.source=? AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL)
            AND m.elapsed_seconds BETWEEN ? AND ?
        ) WHERE (time,channel,row_id)>(?,?,?) ORDER BY time,channel,row_id LIMIT 128
        """,
        [
          .text(distance), .integer(held?.snapshot.storageID ?? -1), .text(distance), .text(battery),
          .integer(held?.snapshot.maximumPointID ?? -1), .real(chunkStart), .real(chunkEnd),
          .text(id), .integer(source.revision), .integer(source.revision), .integer(source.revision),
          .real(chunkStart), .real(chunkEnd),
          .text(id), .text(distance.hasPrefix("gps:") ? String(distance.dropFirst(4)) : "none"),
          .integer(source.revision), .integer(source.revision),
          .real(chunkStart), .real(chunkEnd), .real(cursor.time), .integer(cursor.channel), .integer(cursor.id),
        ], limit: 128)
    }
    for row in rows {
      let time = WorkoutDistancePolicy.timeUs(row.double("time")!)
      switch row.int("channel")! {
      case 0:
        let a = WorkoutDistancePolicy.timeUs(row.double("a")!)
        let b = WorkoutDistancePolicy.timeUs(row.double("b")!)
        if b > a {
          inputs.append(
            .distance(
              startUs: a, endUs: b, meters: row.double("amount")!, speed0: row.double("u"), speed1: row.double("v")))
        }
      case 1:
        inputs.append(
          .battery(
            startUs: WorkoutDistancePolicy.timeUs(row.double("a")!),
            endUs: WorkoutDistancePolicy.timeUs(row.double("b")!),
            wattHours: row.double("amount")!, rate0: row.double("u"), rate1: row.double("v"),
            indivisible: row.int("indivisible") == 1, timestamp: row.string("stamp")!))
      default:
        let clock = row.string("clock")
        let active = timeline.membership(
          t: time, producer: row.string("producer")!, sequence: row.int("seq")!, clockEpoch: clock)
        if row.int("channel") == 2 {
          inputs.append(
            .speed(timeUs: time, mps: row.double("speed"), accuracy: row.double("accuracy"), activeInterval: active))
        } else {
          inputs.append(
            .telemetry(
              timeUs: time, watts: row.double("watts"), batteryWatts: row.double("power"),
              speedMps: row.double("speed"), activeInterval: active,
              continuity: "\(clock ?? "")|\(row.string("connection") ?? "")", timestamp: row.string("stamp")!))
        }
      }
      cursor = (row.double("time")!, row.int("channel")!, row.int("row_id")!)
    }
    if rows.count < 128 {
      if chunkEnd == until { break }
      chunkStart = chunkEnd
      chunkEnd = min(until, chunkEnd + 16)
      cursor = (chunkStart, Int64.max, Int64.max)
    }
  }
  for input in inputs { try feed(input, query) }
  let payload = try JSONSerialization.jsonObject(with: Data(try query.resultJson().utf8)) as! [String: Any]
  return (inputs, payload)
}

for (selection, battery) in [("gps:phone", "calculated"), ("controller", "controller")] {
  let ride = try SyntheticRide(distanceSource: selection, battery: battery)
  for second in 0..<170 {
    let time = Double(second)
    let rate = (48..<61).contains(second) ? 40 : 8
    var events: [WorkoutEvent] = []
    if !(100..<112).contains(second) {
      for k in 0..<rate {
        let n = second * 40 + k
        events.append(
          try ride.telemetry(
            time + Double(k) / Double(rate), power: Double(100 + n % 97),
            motor: n % 7 == 0 ? nil : Double(300 + n % 50),
            speed: n % 11 == 0 ? nil : (n % 13 == 0 ? 0 : 4 + Double(n % 9) / 4)))
      }
    }
    if second % 10 == 3 { events.append(try ride.telemetry(time + 0.5, power: 222, motor: 333, speed: 5)) }
    events.append(try ride.location(time + 0.5, barrier: second == 30))
    if second % 20 == 7 { events.append(try ride.location(time + 0.25, source: "watch")) }
    try ride.append(events)
    switch second {
    case 70: try ride.append([ride.lifecycle(70.25, "pause")])
    case 80: try ride.append([ride.lifecycle(80.5, "resume")])
    case 120:
      try ride.append([ride.lifecycle(120.125, "pause", interrupted: true)])
      ride.reconnect()
      try ride.append([ride.telemetry(120.125, power: 140)])
    case 125: try ride.append([ride.lifecycle(125, "resume")])
    default: break
    }
  }
  _ = try WorkoutDistanceStore(store: store).snapshot(
    id: ride.id, revision: try store.collection(id: ride.id).int("revision")!)
  let last = 169.875
  let cases = [
    ("whole-ride totals", request(ride.id, from: 0, to: last)),
    ("smoothed whole-ride totals", request(ride.id, from: 0, to: last, smoothing: 30)),
    ("range totals", request(ride.id, from: 37.3, to: 131.7, smoothing: 5)),
    ("whole-ride graph", request(ride.id, from: 0, to: last, totals: false, buckets: 512)),
    ("smoothed graph", request(ride.id, from: 0, to: last, smoothing: 60, totals: false, buckets: 512)),
    ("aligned whole-ride graph", request(ride.id, from: 0, to: 400, totals: false, buckets: 64)),
    ("aligned smoothed graph", request(ride.id, from: 0, to: last, smoothing: 30, totals: false, buckets: 100)),
    ("zoomed graph", request(ride.id, from: 37.3, to: 131.7, smoothing: 5, totals: false, buckets: 64)),
    ("current reading", request(ride.id, from: 121, to: 121, totals: false, current: true)),
    ("inspection", request(ride.id, from: 70.25, to: 70.25, totals: false)),
  ]
  for (name, request) in cases {
    let steps = StepCount()
    store.jobObserverForTesting = { _, work, _ in steps.add(work) }
    let actual = try observed(MonitorDataStore(root: root), request)
    let reader = steps.take()
    let expected = try unionRead(request)
    let union = steps.take()
    store.jobObserverForTesting = nil
    check(
      request.endSeconds! - request.startSeconds! < 60 || reader * 2 < union,
      "\(selection) \(name) runs \(reader) SQLite steps, under half of a paged union's \(union)")
    check(
      actual.inputs == expected.inputs,
      "\(selection) \(name) appends the inputs of a paged three-branch union in its order: "
        + difference(actual.inputs, expected.inputs))
    for key in ["points", "averages", "endpoints", "statistics"] {
      check(
        identical(actual.value[key]!, expected.payload[key]!),
        "\(selection) \(name) \(key) are bit-identical to a series query fed the same inputs")
    }
    if request.energyTotals {
      check((actual.value["series"] as? [String: Any])?.isEmpty == true, "\(selection) \(name) builds no series")
    } else {
      check(
        identical(actual.value["series"]!, expected.payload["series"]!),
        "\(selection) \(name) series are bit-identical")
    }
  }
  check(
    try store.read {
      try $0.scalarInt(
        "SELECT count(*) FROM collection_memberships WHERE collection_id=? AND kind='telemetry' AND elapsed_seconds>48 AND elapsed_seconds<=64",
        [.text(ride.id)])
    }! > 512, "the differential ride pages one chunk's telemetry")
}
print("Wh/km reader differential passed")

func delta(_ after: [String: Int], _ before: [String: Int], _ key: String) -> Int {
  (after[key] ?? 0) - (before[key] ?? 0)
}
func cut(_ end: Double) -> Double { Double((Int64(end * 1_000_000) - 2_500_001) / 16_000_000 * 16) }
func rowCount(_ id: String, after lower: Double, through upper: Double, distance: String, battery: String) throws
  -> Int
{
  let revision = try store.collection(id: id).int("revision")!
  let snapshot = try WorkoutDistanceStore(store: store).snapshot(id: id, revision: revision)
  return try store.read { db in
    let window: [PowerLogSQLValue] = [.real(lower), .real(upper)]
    var count = try db.scalarInt(
      "SELECT count(*) FROM collection_memberships WHERE collection_id=? AND kind='telemetry' AND elapsed_seconds>? AND elapsed_seconds<=?",
      [.text(id)] + window)!
    if distance.hasPrefix("gps:") {
      count += try db.scalarInt(
        "SELECT count(*) FROM collection_memberships WHERE collection_id=? AND kind='location' AND source=? AND elapsed_seconds>? AND elapsed_seconds<=?",
        [.text(id), .text(String(distance.dropFirst(4)))] + window)!
    }
    count += try db.scalarInt(
      "SELECT count(*) FROM distance_points WHERE generation_id=? AND source IN (?,?) AND point_id<=? AND elapsed_seconds>? AND elapsed_seconds<=?",
      [.integer(snapshot.storageID), .text(distance), .text(battery), .integer(snapshot.maximumPointID)] + window)!
    return Int(count)
  }
}
func dropDistance(_ id: String) throws {
  try store.transaction { db in
    try db.execute(
      "DELETE FROM distance_points WHERE generation_id IN (SELECT storage_id FROM distance_generations WHERE collection_id=?)",
      [.text(id)])
    try db.execute("DELETE FROM distance_snapshots WHERE collection_id=?", [.text(id)])
    try db.execute("DELETE FROM distance_generations WHERE collection_id=?", [.text(id)])
  }
}
func switchBattery(_ id: String, to battery: String) throws {
  var metadata = try archive.metadata(id: id)
  metadata.batteryConsumptionSource = battery
  try store.transaction { db in
    let revision = try store.collection(id: id).int("revision")! + 1
    metadata.collectionRevision = revision
    let data = try WorkoutCoding.encoder().encode(metadata)
    try db.execute(
      "UPDATE collections SET metadata=?,revision=? WHERE id=?", [.blob(data), .integer(revision), .text(id)])
    try db.execute(
      "INSERT INTO collection_versions(collection_id,revision,metadata) VALUES(?,?,?)",
      [.text(id), .integer(revision), .blob(data)])
    try store.recordChange(db, id: id, revision: revision, minUS: nil, maxUS: nil, kind: "metadata", metrics: [])
  }
}
final class Totals {
  let monitor = MonitorDataStore(root: root)
  let id: String
  var valid: [Int: Bool] = [:]
  init(_ id: String) { self.id = id }
  func invalidate() { valid.removeAll() }
  @discardableResult
  func read(_ end: Double, smoothing: Int = 0, _ scenario: String) throws -> (inputs: [EnergyInput], rows: Int) {
    let request = request(id, from: 0, to: end, smoothing: smoothing)
    let before = monitor.energyDiagnostics()
    let incremental = try observed(monitor, request)
    let after = monitor.energyDiagnostics()
    let full = try MonitorDataStore(root: root).readEnergyPerDistance(request)
    check(
      identical(incremental.value, full),
      "\(scenario): incremental totals at \(end) s with \(smoothing) s smoothing equal a full read")
    let resumed = delta(after, before, "energyResumedReads") == 1
    check(
      resumed == (valid[smoothing] ?? false),
      "\(scenario): the totals read at \(end) s with \(smoothing) s smoothing "
        + (resumed ? "resumed a stale checkpoint" : "rebuilt although its checkpoint was valid"))
    check(
      delta(after, before, "energyFullReads") + delta(after, before, "energyResumedReads") == 1,
      "\(scenario): one totals read is either full or resumed")
    valid[smoothing] = cut(end) >= 16
    return (incremental.inputs, delta(after, before, "energyRows"))
  }
}

do {
  let ride = try SyntheticRide(distanceSource: "gps:phone", battery: "calculated")
  let totals = Totals(ride.id)
  for second in 1...230 {
    try ride.second(second, telemetry: !(60...75).contains(second), location: !(100...123).contains(second))
    var scenario = (100...123).contains(second) ? "GPS gap" : "steady GPS ride"
    switch second {
    case 40, 50:
      try ride.append([ride.lifecycle(Double(second) - 0.05, second == 40 ? "pause" : "resume")])
      scenario = second == 40 ? "pause" : "resume"
      totals.invalidate()
    case 60...75: scenario = "telemetry gap"
    case 90:
      try ride.append([ride.lifecycle(89.875, "pause", interrupted: true)])
      ride.reconnect()
      scenario = "interruption"
      totals.invalidate()
    case 93:
      try ride.append([ride.lifecycle(92.5, "resume")])
      scenario = "resume after an interruption"
      totals.invalidate()
    case 110:
      try ride.append([ride.location(cut(109) - 3)])
      scenario = "late GPS row"
      totals.invalidate()
    case 120:
      try ride.append([ride.location(cut(119) - 1)])
      scenario = "late GPS row inside a GPS gap"
      totals.invalidate()
    case 130:
      try dropDistance(ride.id)
      scenario = "distance generation rebuild"
      totals.invalidate()
    case 140..<215: continue
    case 215: scenario = "more than 512 append-only revisions without a read"
    case 225:
      try switchBattery(ride.id, to: "controller")
      scenario = "battery source change"
      totals.invalidate()
    default: break
    }
    if second == 215 {
      let revisions = try store.read {
        try $0.scalarInt(
          "SELECT count(DISTINCT revision) FROM collection_memberships WHERE collection_id=? AND elapsed_seconds>139",
          [.text(ride.id)])
      }!
      check(revisions > 512, "more than 512 revisions accumulate without a read")
    }
    let end = Double(second)
    let read = try totals.read(end, scenario)
    if second % 4 == 0 { try totals.read(end, smoothing: 30, scenario) }
    if [30, 100, 215].contains(second) {
      let previous = second == 215 ? 139.0 : end - 1
      check(
        read.rows
          == (try rowCount(
            ride.id, after: cut(previous), through: end + 10, distance: "gps:phone", battery: "calculatedEnergyWh")),
        "a resumed read at \(end) s feeds only the rows after its cut at \(cut(previous)) s")
      let whole = try unionRead(request(ride.id, from: 0, to: end)).inputs
      check(
        read.inputs.count < whole.count && read.inputs == Array(whole.suffix(read.inputs.count)),
        "a resumed read at \(end) s appends exactly the tail of a full read's inputs")
    }
  }
}
print("Wh/km incremental totals on a GPS ride passed")

do {
  let ride = try SyntheticRide(distanceSource: "controller", battery: "controller")
  let totals = Totals(ride.id)
  for second in 1...110 {
    try ride.second(second, telemetry: !(60..<80).contains(second))
    var scenario = (60..<80).contains(second) ? "telemetry gap" : "steady controller ride"
    if second == 78 {
      try ride.append([ride.telemetry(cut(77), battery: false)])
      scenario = "late telemetry row at the cut inside a gap"
      totals.invalidate()
    }
    let end = Double(second)
    try totals.read(end, scenario)
    if second % 3 == 0 { try totals.read(end, smoothing: 5, scenario) }
  }
  let full = MonitorDataStore(root: root)
  let before = full.energyDiagnostics()
  _ = try full.readEnergyPerDistance(request(ride.id, from: 0, to: 110))
  check(
    delta(full.energyDiagnostics(), before, "energyRows")
      == (try rowCount(ride.id, after: -1, through: 120, distance: "controller", battery: "controllerEnergyWh")),
    "a full read feeds every row of the ride once")
}
print("Wh/km incremental totals on a controller ride passed")

func submitted(_ monitor: MonitorDataStore, _ request: MonitorRequest) throws -> [String: Any] {
  let done = DispatchSemaphore(value: 0)
  var outcome: Result<[String: Any], Error>?
  check(
    monitor.submit(.energyPerDistance, request: request) {
      outcome = $0
      done.signal()
    }, "a Wh/km read is admitted")
  check(done.wait(timeout: .now() + 30) == .success, "a submitted Wh/km read completes")
  return try outcome!.get()
}
do {
  let ride = try SyntheticRide(distanceSource: "gps:phone", battery: "calculated")
  let monitor = MonitorDataStore(root: root)
  for second in 1...40 { try ride.second(second) }
  let before = monitor.energyDiagnostics()
  _ = try submitted(monitor, request(ride.id, from: 0, to: 40))
  for second in 41...45 { try ride.second(second) }
  _ = try submitted(monitor, request(ride.id, from: 45, to: 45, totals: false, current: true))
  check(
    delta(monitor.energyDiagnostics(), before, "energyFullReads") == 1
      && delta(monitor.energyDiagnostics(), before, "energyResumedReads") == 0,
    "a current Wh/km reading leaves the whole-ride totals state alone")
  let resumed = try submitted(monitor, request(ride.id, from: 0, to: 45))
  check(
    delta(monitor.energyDiagnostics(), before, "energyResumedReads") == 1,
    "the energy lane resumes whole-ride totals from its previous read")
  check(
    identical(resumed, try MonitorDataStore(root: root).readEnergyPerDistance(request(ride.id, from: 0, to: 45))),
    "whole-ride totals resumed on the energy lane equal a full read")
}
print("Wh/km totals on the energy lane passed")

func alignedSeries(_ request: MonitorRequest, inputs: [EnergyInput], exactWidth: Bool = false) throws -> [String: Any] {
  let source = try describe(request.id!)
  let endUs = WorkoutDistancePolicy.timeUs(source.end)
  let timeline = try lifecycleTimeline(
    request.id!, revision: source.revision, end: source.end, metadata: source.metadata)
  let timer = timeline.intervals.reduce(Int64(0)) { $0 + max(0, min(endUs, $1.endUs) - max(0, $1.startUs)) }
  let query = try EnergyPerDistanceQuery(
    startUs: 0, endUs: endUs, buckets: Int32(request.buckets),
    alignedSlotUs: exactWidth
      ? nil
      : try InspectionPlotSlots.shared.alignedSlotUs(
        range: AnalysisRange(startUs: 0, endUs: endUs), buckets: Int32(request.buckets)),
    timerUs: timer,
    controllerSpeed: (source.metadata["distanceSource"] as? String ?? request.distanceSource) == "controller",
    smoothingSeconds: Int32(request.energySmoothingSeconds), includeTotals: false)
  for input in inputs { try feed(input, query) }
  return try JSONSerialization.jsonObject(with: Data(try query.resultJson().utf8)) as! [String: Any]
}
func tailRows(_ id: String, gps: String?, fromUs: Int64, through upper: Double) throws -> Int {
  Int(
    try store.read {
      try $0.scalarInt(
        "SELECT count(*) FROM collection_memberships WHERE collection_id=? AND (kind='telemetry' OR (kind='location' AND source=?)) AND elapsed_seconds>=? AND elapsed_seconds<=?",
        [.text(id), .text(gps ?? ""), .real(WorkoutDistancePolicy.seconds(fromUs)), .real(upper)])
    }!)
}
func wholeSeries(_ id: String, end: Double, smoothing: Int = 0, buckets: Int) throws -> MonitorRequest {
  request(id, from: 0, to: max(end, try describe(id).end), smoothing: smoothing, totals: false, buckets: buckets)
}
final class SeriesReads {
  let monitor = MonitorDataStore(root: root)
  let id: String
  let buckets: Int
  var valid: Set<Int> = []
  var ends: [Int: Double] = [:]
  init(_ id: String, buckets: Int) {
    self.id = id
    self.buckets = buckets
  }
  func invalidate() { valid.removeAll() }
  @discardableResult
  func read(_ end: Double, smoothing: Int = 0, _ scenario: String, compare: Bool = true) throws -> (
    inputs: [EnergyInput], rows: Int, value: [String: Any]
  ) {
    let request = try wholeSeries(id, end: end, smoothing: smoothing, buckets: buckets)
    let before = monitor.energyDiagnostics()
    let incremental = try observed(monitor, request)
    let after = monitor.energyDiagnostics()
    if compare {
      let fresh = try observed(MonitorDataStore(root: root), request)
      let reference = try alignedSeries(request, inputs: fresh.inputs)
      for key in ["series", "points", "averages", "endpoints", "statistics"] {
        check(
          identical(incremental.value[key]!, reference[key]!),
          "\(scenario): the incremental Wh/km series at \(end) s with \(smoothing) s smoothing equals a fresh "
            + "aligned query (\(key))")
      }
      check(
        identical(incremental.value, fresh.value),
        "\(scenario): the incremental Wh/km series at \(end) s with \(smoothing) s smoothing equals a fresh read")
    }
    let resumed = delta(after, before, "energySeriesResumedReads") == 1
    check(
      resumed == valid.contains(smoothing),
      "\(scenario): the Wh/km series read at \(end) s with \(smoothing) s smoothing "
        + (resumed ? "resumed a stale series" : "rebuilt although its series was valid"))
    check(
      delta(after, before, "energySeriesBuilds") + delta(after, before, "energySeriesResumedReads")
        + delta(after, before, "energySeriesFallbacks") == 1,
      "\(scenario): one series read is built, resumed or served fresh")
    valid.insert(smoothing)
    ends[smoothing] = try describe(id).end
    return (incremental.inputs, delta(after, before, "energyRows"), incremental.value)
  }
}
let margin = EnergyPerDistancePolicy.shared.checkpointMarginUs

do {
  let ride = try SyntheticRide(distanceSource: "gps:phone", battery: "calculated")
  let series = SeriesReads(ride.id, buckets: 32)
  for second in 1...530 {
    let quiet = (140..<220).contains(second)
    let compare = second <= 330 || second % 10 == 0 || second >= 505
    try ride.second(second, rate: quiet || (25...35).contains(second) ? 8 : 1)
    var scenario = "steady GPS series"
    switch second {
    case 40, 50:
      try ride.append([ride.lifecycle(Double(second) - 0.25, second == 40 ? "pause" : "resume")])
      scenario = second == 40 ? "pause" : "resume"
      series.invalidate()
    case 90:
      try ride.append([ride.location(80.75, speed: 9)])
      scenario = "late GPS row"
      series.invalidate()
    case 120:
      try dropDistance(ride.id)
      scenario = "distance generation rebuild"
    case 140..<220: continue
    case 220: scenario = "more than 512 append-only revisions without a read"
    case 300:
      try ride.append([ride.lifecycle(299.5, "pause", interrupted: true)])
      ride.reconnect()
      scenario = "interruption"
      series.invalidate()
    case 305:
      try ride.append([ride.lifecycle(304.75, "resume")])
      scenario = "resume after an interruption"
      series.invalidate()
    case 325:
      try ride.append([ride.lifecycle(315.25, "pause"), ride.lifecycle(316.25, "resume")])
      scenario = "late pause and resume before the series cut"
      series.invalidate()
    default: break
    }
    if second == 220 {
      let revisions = try store.read {
        try $0.scalarInt(
          "SELECT count(DISTINCT revision) FROM collection_memberships WHERE collection_id=? AND elapsed_seconds>139",
          [.text(ride.id)])
      }!
      check(revisions > 512, "more than 512 revisions accumulate without a series read")
    }
    let end = Double(second)
    let previous = series.ends[0]
    let read = try series.read(end, scenario, compare: compare)
    if second % 3 == 0 {
      try series.read(end, smoothing: second < 200 ? 5 : second < 400 ? 30 : 60, scenario, compare: compare)
    }
    if [25, 75, 220, 250, 450, 520].contains(second), let previous {
      let source = try describe(ride.id)
      check(
        read.rows
          == (try tailRows(
            ride.id, gps: "phone", fromUs: WorkoutDistancePolicy.timeUs(previous) - margin, through: source.end + 10)),
        "a resumed Wh/km series read at \(end) s feeds only the rows after its committed cut")
      let whole = try observed(MonitorDataStore(root: root), try wholeSeries(ride.id, end: end, buckets: 32))
      check(
        read.inputs.count < whole.inputs.count && read.inputs == Array(whole.inputs.suffix(read.inputs.count)),
        "a resumed Wh/km series read at \(end) s appends exactly the tail of a full read's inputs")
    }
    if second == 260 {
      let wider = try wholeSeries(ride.id, end: end, buckets: 100)
      let before = series.monitor.energyDiagnostics()
      let resized = try observed(series.monitor, wider)
      let resumed = delta(series.monitor.energyDiagnostics(), before, "energySeriesResumedReads") == 1
      let fresh = try alignedSeries(wider, inputs: try observed(MonitorDataStore(root: root), wider).inputs)
      check(
        resumed && identical(resized.value["series"]!, fresh["series"]!),
        "a Wh/km series resumes for another bucket budget and equals its fresh aligned query")
    }
    if second == 32 || second == 33 {
      let source = try describe(ride.id)
      let slot = try InspectionPlotSlots.shared.alignedSlotUs(
        range: AnalysisRange(startUs: 0, endUs: WorkoutDistancePolicy.timeUs(source.end)), buckets: 32)
      check(
        second == 32 ? slot == nil : slot?.int64Value == 2_000_000,
        "a 32-bucket series switches from exact-width to 2 s slots once the ride spans 32 s")
      let exact = try alignedSeries(
        try wholeSeries(ride.id, end: end, buckets: 32),
        inputs: try observed(MonitorDataStore(root: root), try wholeSeries(ride.id, end: end, buckets: 32)).inputs,
        exactWidth: true)
      check(
        identical(read.value["series"]!, exact["series"]!) == (second == 32),
        "the series grid is exact-width only below a span of 32 s")
    }
  }
}
print("Wh/km incremental series on a GPS ride passed")

do {
  let ride = try SyntheticRide(distanceSource: "controller", battery: "controller")
  let series = SeriesReads(ride.id, buckets: 64)
  for second in 1...130 {
    try ride.second(second, rate: 4, telemetry: !(90..<100).contains(second))
    var scenario = (90..<100).contains(second) ? "telemetry gap" : "steady controller series"
    switch second {
    case 60:
      try ride.append([ride.lifecycle(59.5, "pause")])
      scenario = "pause"
      series.invalidate()
    case 62:
      try ride.append([ride.lifecycle(61.5, "resume")])
      scenario = "resume"
      series.invalidate()
    case 97:
      try ride.append([ride.telemetry(92.5, battery: false)])
      scenario = "delayed telemetry row inside a gap after the series cut"
    case 99:
      try ride.append([ride.telemetry(85.125, battery: false)])
      scenario = "late telemetry row before the series cut"
      series.invalidate()
    default: break
    }
    let end = Double(second)
    try series.read(end, scenario)
    if second % 4 == 0 { try series.read(end, smoothing: 30, scenario) }
  }
}
print("Wh/km incremental series on a controller ride passed")

do {
  MonitorDataStore.energySeriesPointBudgetForTesting = 8
  defer { MonitorDataStore.energySeriesPointBudgetForTesting = nil }
  let ride = try SyntheticRide(distanceSource: "gps:phone", battery: "calculated")
  let monitor = MonitorDataStore(root: root)
  var overflowed = false
  for second in 1...40 {
    try ride.second(second, rate: 4)
    let request = try wholeSeries(ride.id, end: Double(second), buckets: 16)
    let before = monitor.energyDiagnostics()
    let value = try observed(monitor, request).value
    let after = monitor.energyDiagnostics()
    check(
      identical(value, try MonitorDataStore(root: root).readEnergyPerDistance(request)),
      "a Wh/km series past its summary budget at \(second) s equals a fresh read")
    let fallback = delta(after, before, "energySeriesFallbacks") == 1
    check(
      !overflowed || fallback,
      "a Wh/km series that overflowed its summaries is served by a fresh aligned query, not rebuilt, at \(second) s")
    overflowed = overflowed || fallback
  }
  check(overflowed, "an 8-point summary budget overflows")
}
print("Wh/km series past its summary budget passed")

do {
  let ride = try SyntheticRide(distanceSource: "gps:phone", battery: "calculated")
  let monitor = MonitorDataStore(root: root)
  for second in 1...40 { try ride.second(second) }
  let before = monitor.energyDiagnostics()
  _ = try submitted(monitor, try wholeSeries(ride.id, end: 40, buckets: 32))
  for second in 41...45 { try ride.second(second) }
  let resumed = try submitted(monitor, try wholeSeries(ride.id, end: 45, buckets: 32))
  check(
    delta(monitor.energyDiagnostics(), before, "energySeriesBuilds") == 1
      && delta(monitor.energyDiagnostics(), before, "energySeriesResumedReads") == 1,
    "the energy lane resumes the whole-ride Wh/km series from its previous read")
  check(
    identical(
      resumed,
      try MonitorDataStore(root: root).readEnergyPerDistance(try wholeSeries(ride.id, end: 45, buckets: 32))),
    "a Wh/km series resumed on the energy lane equals a fresh read")
}
print("Wh/km series on the energy lane passed")
print("Wh/km checks passed: \(assertions) assertions")
