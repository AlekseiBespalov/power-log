import Foundation

var assertions = 0
func check(_ condition: Bool, _ message: String) {
  assertions += 1
  if !condition { fatalError(message) }
}
func fails(_ code: String, _ message: String, _ body: () throws -> Void) {
  do {
    try body()
    check(false, message)
  } catch let failure as ExportFailure {
    check(failure.code == code, "\(message): \(failure.code) \(failure.message)")
  } catch { check(false, "\(message): \(error)") }
}
func same(_ a: Double, _ b: Double) -> Bool { a == b || (a.isNaN && b.isNaN) }
func less(_ a: [Double], _ b: [Double]) -> Bool {
  for (x, y) in zip(a, b) where x != y { return x < y }
  return a.count < b.count
}
func doubles(_ value: Any?) -> [Double] {
  guard let data = value as? Data else { return [] }
  return data.withUnsafeBytes { raw in
    (0..<(raw.count / 8)).map {
      Double(bitPattern: UInt64(littleEndian: raw.loadUnaligned(fromByteOffset: $0 * 8, as: UInt64.self)))
    }
  }
}

// The column contract is read from the TypeScript catalog itself.
let catalogText = try String(contentsOfFile: "src/core/export/catalog.ts", encoding: .utf8)
var groups: [String: [String]] = [:]
for line in catalogText.split(separator: "\n") {
  let text = String(line)
  guard let match = text.range(of: "^const ([A-Z_]+) = \\[(.*)\\] as const;$", options: .regularExpression) else {
    continue
  }
  let body = String(text[match])
  let name = String(body.dropFirst(6).prefix { $0 != " " })
  let values = body[body.firstIndex(of: "[")!...].split(separator: "'").enumerated().filter { $0.offset % 2 == 1 }
  groups[name] = values.map { String($0.element) }
}
var catalog: [String: (kinds: [String], columns: [(name: String, type: String, consumers: [String])])] = [:]
let projectionText = catalogText.components(separatedBy: "export const PROJECTIONS = {")[1]
  .components(separatedBy: "} as const satisfies Record<ProjectionName, ProjectionDefinition>;")[0]
var current = ""
for line in projectionText.split(separator: "\n").map(String.init) {
  if let range = line.range(of: "^  ([A-Za-z]+): \\{$", options: .regularExpression) {
    current = String(line[range].dropFirst(2).dropLast(3))
    catalog[current] = ([], [])
  } else if let range = line.range(of: "kinds: [A-Z_]+,", options: .regularExpression) {
    catalog[current]!.kinds = groups[String(line[range].dropFirst(7).dropLast())]!
  } else if line.contains("{ name: '") {
    let fields = line.components(separatedBy: "'")
    let platforms = line.components(separatedBy: "platforms: ")[1].prefix { $0 != "," }
    let consumers = line.components(separatedBy: "consumers: ")[1].prefix { $0 != " " && $0 != "}" }
    guard groups[String(platforms)]!.contains("ios") else { continue }
    catalog[current]!.columns.append((fields[1], fields[3], groups[String(consumers)]!))
  }
}
func contract(_ projection: String, _ kind: String) -> [(name: String, type: String)] {
  guard let definition = catalog[projection], definition.kinds.contains(kind) else { return [] }
  return definition.columns.filter { column in
    column.consumers.contains(kind) || (kind == "fit" && column.consumers.contains("discovery"))
  }.map { ($0.name, $0.type) }
}
check(catalog.count == 7 && groups["IOS"] == ["ios"], "the catalog parses into seven projections")
for projection in catalog.keys.sorted() {
  for kind in ["zip", "fit"] {
    let expected = contract(projection, kind)
    let actual = ExportSource.columns(projection: projection, kind: kind)
    check(
      expected.map(\.name) == (expected.isEmpty ? [] : actual.map(\.name))
        && expected.map(\.type) == (expected.isEmpty ? [] : actual.map(\.type)),
      "\(projection) in a \(kind) session delivers exactly the catalog's iPhone columns: \(actual.map(\.name))")
  }
}

let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-export-source-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: store)
let distances = WorkoutDistanceStore(store: store)
let source = ExportSource { archive }
var jobs: [ExportSource.Job] = []
var replanned: [String] = []
ExportSource.jobObserverForTesting = { job in
  jobs.append(job)
  if job.reprepares > 0 { replanned.append(job.projection) }
}
let start = Date(timeIntervalSince1970: 1_780_000_000.25)
let context: [String: Any] = ["exportedAt": "2026-10-02T00:00:00.000Z", "platform": "ios"]

struct Stored {
  let kind: String
  let producer: String
  let sequence: Int64
  let elapsed: Double
  let event: WorkoutEvent
  var selected = true
}
var stored: [String: [Stored]] = [:]
var sequences: [String: Int64] = [:]
func date(_ t: Double) -> Date { start.addingTimeInterval(t) }
func ride(watch: Bool = true, saves: Bool = true, gps: Bool = true, indoor: Bool = false) throws -> String {
  let id = try archive.create(
    startedAt: start, indoor: indoor, watchEnabled: watch, saveToHealth: saves, recordGPS: gps
  )
  .id
  stored[id] = []
  return id
}
func event(
  _ id: String, _ kind: String, _ source: String, _ t: Double?, _ payload: [String: WorkoutJSON], at: Date? = nil,
  eventID: String = UUID().uuidString
) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: id, kind: kind, source: source, timestamp: at ?? date(t ?? 0), elapsedSeconds: t, payload: payload,
    eventId: eventID)
}
func append(_ id: String, _ events: [WorkoutEvent], producer: String? = nil, firstSequence: Int64? = nil) throws {
  for offset in stride(from: 0, to: events.count, by: 512) {
    let batch = Array(events[offset..<min(events.count, offset + 512)])
    check(
      try archive.appendBatch(batch, producer: producer, firstSequence: firstSequence.map { $0 + Int64(offset) })
        == batch.count, "every synthetic observation is new")
  }
  let origin = try WorkoutCoding.date(archive.metadata(id: id).startedAt).timeIntervalSince1970
  for (offset, event) in events.enumerated() {
    let owner = producer ?? event.source
    let key = id + ":" + owner
    let sequence = firstSequence.map { $0 + Int64(offset) } ?? (sequences[key, default: 0] + 1)
    sequences[key] = max(sequences[key] ?? 0, sequence)
    let elapsed = try event.elapsedSeconds ?? (event.date.timeIntervalSince1970 - origin)
    stored[id]!.append(Stored(kind: event.kind, producer: owner, sequence: sequence, elapsed: elapsed, event: event))
  }
}
func deselect(_ id: String, _ eventIDs: [String]) {
  for index in stored[id]!.indices where eventIDs.contains(stored[id]![index].event.eventId) {
    stored[id]![index].selected = false
  }
}
func seal(_ id: String, stop: Double) throws {
  let metadata = try archive.metadata(id: id)
  let ended = date(stop)
  try archive.update(
    id: id, watchSyncState: metadata.watchEnabled ? "received" : nil, stopElapsedSeconds: stop,
    ownerTiming: try WorkoutOwnerTiming(
      timestamp: WorkoutCoding.timestamp(ended), elapsedSeconds: stop, timerSeconds: stop))
  try archive.finish(id: id, endedAt: ended)
  let transfer = WorkoutTransferJournal(archive: archive)
  let producers = try store.read { db in
    try db.rows(
      "SELECT producer FROM collection_sources WHERE collection_id=? ORDER BY producer", [.text(id)], limit: 8
    ).compactMap { $0.string("producer") }
  }
  var sources: [WorkoutSourceSeal] = []
  for producer in producers {
    try transfer.register(id: id, producer: producer)
    sources.append(try transfer.source(id: id, producer: producer))
  }
  var requirements = ["ownerEnded": "sealed"]
  if metadata.watchEnabled && !metadata.saveToHealth {
    for key in ["healthSave", "cycInsertion", "healthExtraction"] { requirements[key] = "notRequested" }
    requirements["localSensors"] = "sealed"
  }
  _ = try transfer.accept(
    seal: WorkoutSeal(
      workoutID: id, sealRevision: 1, collectionRevision: archive.revision(id: id), ownerRevision: 1,
      stopCutoff: WorkoutCoding.timestamp(ended), healthOutcome: metadata.saveToHealth ? "notSaved" : "notRequested",
      requirements: requirements, sources: sources, stopElapsedSeconds: stop, timerSeconds: stop,
      saveToHealth: metadata.saveToHealth, recordGPS: metadata.recordGPS))
  check(try transfer.verify(id: id), "the synthetic ride seal verifies")
}
func admitWithoutSeal(_ id: String, stop: Double) throws {
  try archive.update(id: id, stopElapsedSeconds: stop)
  try archive.finish(id: id, endedAt: date(stop))
  try archive.update(id: id, sealRevision: 1, verifiedSealRevision: 1, finalizationState: "complete")
}
func open(
  _ id: String, _ kind: String, on exporter: ExportSource = source, distance: String? = nil
) throws -> [String: Any] {
  var request: [String: Any] = ["rideId": id, "kind": kind, "context": context]
  if let distance { request["distanceSource"] = distance }
  return try exporter.open(request)
}

struct Table {
  var rows = 0
  var pages = 0
  var numbers: [String: [Double]] = [:]
  var strings: [String: [String?]] = [:]
  var connections: [[String: Any]] = []
}
func read(_ exporter: ExportSource, _ session: [String: Any], _ projection: String, kind: String) throws -> Table {
  let columns = contract(projection, kind)
  var table = Table()
  var after: Any = NSNull()
  var previous: [Double]?
  while true {
    let page = try exporter.page(["session": session["session"]!, "projection": projection, "after": after])
    table.pages += 1
    let rows = page["rows"] as! Int
    let done = page["done"] as! Bool
    let values = page["columns"] as! [String: Any]
    check(Set(values.keys) == Set(columns.map(\.name)), "\(projection) pages carry exactly the contract columns")
    check(rows <= exporter.pageRows, "\(projection) pages respect the row ceiling")
    var bytes = 0
    for (name, type) in columns {
      if type == "number" {
        check((values[name] as! Data).count == rows * 8, "\(projection).\(name) holds one binary64 per row")
        table.numbers[name, default: []] += doubles(values[name])
        bytes += rows * 8
      } else {
        check((values[name] as! [Any]).count == rows, "\(projection).\(name) holds one string per row")
        table.strings[name, default: []] += (values[name] as! [Any]).map { $0 as? String }
        bytes += (values[name] as! [Any]).reduce(0) { $0 + (($1 as? String)?.utf8.count ?? 0) }
      }
    }
    check(rows <= 1 || bytes <= exporter.pageBytes, "\(projection) pages respect the byte ceiling")
    table.connections += page["connections"] as? [[String: Any]] ?? []
    check((page["connections"] != nil) == (projection == "telemetry"), "only telemetry pages carry connections")
    if rows == 0 { check(done && page["last"] is NSNull, "an empty page ends its stream") }
    check(done || rows > 0, "a page that is not done is never empty")
    if rows > 0 {
      let last = page["last"] as! [Double]
      check(last.count == (projection == "distance" ? 2 : 4), "the cursor has the stream's tuple length")
      if let previous { check(less(previous, last), "cursors increase strictly across pages") }
      previous = last
      after = last
    }
    table.rows += rows
    if done { return table }
  }
}
func expected(_ id: String, _ kind: String) -> [Stored] {
  stored[id]!.filter { $0.kind == kind && $0.selected }.sorted {
    ($0.elapsed, $0.producer, $0.sequence) < ($1.elapsed, $1.producer, $1.sequence)
  }
}
func number(_ row: Stored, _ name: String) -> Double {
  let payload = row.event.payload
  switch name {
  case "elapsedSeconds": return row.elapsed
  case "producerSequence": return Double(row.sequence)
  case "distanceBarrier", "interrupted": return payload[name] == .bool(true) ? 1 : 0
  case "cycSequence": return payload[name]?.string.flatMap { Int64($0) }.map(Double.init) ?? .nan
  default: return payload[name]?.number ?? .nan
  }
}
func string(_ row: Stored, _ name: String) -> String? {
  let payload = row.event.payload
  switch name {
  case "timestamp": return row.event.timestamp
  case "producer": return row.producer
  case "connection": return payload["connectionEpoch"]?.string
  case "identifier": return payload["healthKitIdentifier"]?.string
  case "representation": return payload[name]?.string.flatMap { $0.isEmpty ? nil : $0 }
  case "sampleUUID": return (payload["healthKitUUID"] ?? payload["sampleUUID"])?.string?.lowercased()
  default: return payload[name]?.string
  }
}
let membershipKinds = [
  "telemetry": "telemetry", "gps": "location", "gpsDiscovery": "location", "healthZip": "health",
  "healthFit": "health", "lifecycle": "lifecycle",
]
func verify(_ table: Table, _ id: String, _ projection: String, kind: String) {
  let rows = expected(id, membershipKinds[projection]!)
  check(table.rows == rows.count, "\(projection) in \(kind) returns every selected row: \(table.rows) of \(rows.count)")
  for (name, type) in contract(projection, kind) {
    for (index, row) in rows.enumerated() {
      if type == "number" {
        check(
          same(table.numbers[name]![index], number(row, name)),
          "\(projection).\(name) row \(index): \(table.numbers[name]![index]) != \(number(row, name))")
      } else {
        check(
          table.strings[name]![index] == string(row, name),
          "\(projection).\(name) row \(index): \(String(describing: table.strings[name]![index]))")
      }
    }
  }
}

// A Watch-owned ride whose streams interleave, tie at equal elapsed times and carry superseded and deleted Health.
let main = try ride()
try append(
  main,
  [
    event(main, "lifecycle", "watch", 0, ["action": .string("start")]),
    event(
      main, "lifecycle", "watch", 6,
      [
        "action": .string("pause"), "interrupted": .bool(true), "clockEpoch": .string("epoch-2"),
        "timerSeconds": .number(6), "cycSequence": .string("12"),
      ]),
    event(main, "lifecycle", "watch", 6, ["action": .string("resume"), "clockEpoch": .string("epoch-2")]),
    event(main, "lifecycle", "watch", 15, ["action": .string("lap")]),
    event(main, "lifecycle", "watch", 20, ["action": .string("stop"), "timerSeconds": .number(20)]),
  ])
var frames: [WorkoutEvent] = []
for index in 0..<40 {
  let t = Double(index) / 2
  for copy in index == 10 ? [0, 1] : [0] {
    var payload: [String: WorkoutJSON] = [:]
    for (offset, column) in PowerLogStore.telemetryColumns.enumerated() {
      payload[column] = .number(Double(offset) + t / 8 + Double(copy) / 3)
    }
    payload["faultCode"] = .integer(Int64(index % 3))
    payload["assistLevel"] = .integer(Int64(index % 5))
    if index % 7 == 3 { payload["batteryVoltageV"] = nil }
    payload["controllerSpeedMps"] = .number(4 + t / 10)
    payload["clockEpoch"] = .string(index < 12 ? "epoch-1" : "epoch-2")
    payload["connectionEpoch"] = .string(index < 20 ? "connection-a" : "connection-b")
    payload["controllerModel"] = .string(index < 20 ? "X6" : "X12")
    payload["firmwareLabel"] = .string(index < 20 ? "20240601" : "20250101")
    payload["controllerProtocol"] = .string("5.3")
    payload["captureSessionID"] = .string(main)
    payload["observationSequence"] = .string(String(frames.count + 1))
    frames.append(try event(main, "telemetry", "cyc", t, payload))
  }
}
try append(main, frames)
func fix(_ id: String, _ source: String, _ t: Double, _ extra: [String: WorkoutJSON] = [:]) throws -> WorkoutEvent {
  var payload: [String: WorkoutJSON] = [
    "latitude": .number(45 + t * 0.00004 + (source == "phone" ? 0.000001 : 0)), "longitude": .number(7 + t * 0.00003),
    "altitudeMeters": .number(400 + t), "verticalAccuracyM": .number(3), "horizontalAccuracyM": .number(4),
    "speedMps": .number(5), "speedAccuracyMps": .number(0.5), "courseDegrees": .number(90),
    "courseAccuracyDegrees": .number(10), "clockEpoch": .string("gps-epoch"),
  ]
  payload.merge(extra) { _, value in value }
  return try event(id, "location", source, t, payload)
}
var fixes: [WorkoutEvent] = []
for second in 0..<20 {
  let t = Double(second)
  fixes.append(try fix(main, "watch", t, second == 12 ? ["distanceBarrier": .bool(true)] : [:]))
  fixes.append(
    try fix(
      main, "phone", t + 0.5,
      second == 7 ? ["verticalAccuracyM": .number(-1), "altitudeMeters": .null, "distanceBarrier": .bool(false)] : [:]))
}
fixes.append(try fix(main, "phone", 5))
try append(main, fixes)
func heart(_ id: String, _ t: Double, _ value: Double, _ extra: [String: WorkoutJSON] = [:]) throws -> WorkoutEvent {
  let sample = UUID().uuidString.lowercased()
  var payload: [String: WorkoutJSON] = [
    "healthKitIdentifier": .string("HKQuantityTypeIdentifierHeartRate"), "value": .number(value),
    "unit": .string("bpm"), "representation": .string("rawQuantity"), "sampleUUID": .string(sample),
    "sampleCount": .number(1), "sampleStart": .string(WorkoutCoding.timestamp(date(t))),
    "sampleEnd": .string(WorkoutCoding.timestamp(date(t))), "sourceBundleIdentifier": .string("com.example.watch"),
    "heartRateBpm": .number(value),
  ]
  payload.merge(extra) { _, value in value }
  return try event(id, "health", "watch", t, payload, eventID: sample)
}
var health: [WorkoutEvent] = []
for second in 1..<20 {
  health.append(
    try heart(main, Double(second), 100 + Double(second), second == 4 ? ["connectionEpoch": .string("link")] : [:]))
}
health.append(try event(main, "health", "phone", 5, ["heartRateBpm": .number(101)]))
for second in [5.0, 10, 15] {
  health.append(
    try event(
      main, "health", "watch", second,
      [
        "activeEnergyKcal": .number(second * 2), "basalEnergyKcal": .number(second / 2),
        "distanceMeters": .number(second * 5), "representation": .string("cumulativeWorkoutTotal"),
      ]))
}
health.append(
  try event(
    main, "health", "watch", 20,
    [
      "activeEnergyKcal": .number(44), "healthKitIdentifier": .string("HKQuantityTypeIdentifierActiveEnergyBurned"),
      "value": .number(44), "unit": .string("kcal"), "representation": .string("finalWorkoutTotal"),
    ]))
health.append(try event(main, "health", "phone", nil, ["heartRateBpm": .number(99)], at: date(-5)))
let replaced = try heart(main, 7, 90)
let deleted = try heart(main, 8, 91)
health += [replaced, deleted]
try append(main, health)
let replacement = try heart(main, 7, 92, ["supersedesEventId": .string(replaced.eventId)])
let tombstone = try event(
  main, "health", "watch", 8.5,
  [
    "representation": .string("healthTombstone"), "sampleUUID": deleted.payload["sampleUUID"]!,
    "supersedesEventId": .string(deleted.eventId), "deleted": .bool(true),
  ])
let association = try event(
  main, "health", "phone", 20,
  [
    "representation": .string("workoutAssociation"), "sampleUUID": .string(UUID().uuidString.lowercased()),
    "associatedWorkoutUUID": .string(UUID().uuidString.lowercased()),
  ])
try append(main, [replacement, tombstone, association])
func series(_ id: String, _ t: Double, _ values: [Double]) throws -> (sample: String, rows: [WorkoutEvent]) {
  let sample = UUID().uuidString.lowercased()
  let common: [String: WorkoutJSON] = [
    "healthKitIdentifier": .string("HKQuantityTypeIdentifierHeartRate"), "unit": .string("bpm"),
    "sampleUUID": .string(sample), "sampleStart": .string(WorkoutCoding.timestamp(date(t))),
    "sampleEnd": .string(WorkoutCoding.timestamp(date(t + Double(values.count)))),
  ]
  var rows = [
    try event(
      id, "health", "watch", t + Double(values.count),
      common.merging([
        "representation": .string("rawQuantity"), "sampleCount": .number(Double(values.count)),
        "value": .number(values.reduce(0, +) / Double(values.count)),
      ]) { _, value in value }, eventID: sample)
  ]
  for (offset, value) in values.enumerated() {
    rows.append(
      try event(
        id, "health", "watch", t + Double(offset + 1),
        common.merging([
          "representation": .string("rawSeries"), "value": .number(value), "heartRateBpm": .number(value),
        ]) { _, value in value }, eventID: WorkoutStableIdentity.uuid("series:\(sample):\(offset)")))
  }
  return (sample, rows)
}
func deletion(_ id: String, _ t: Double, _ sample: String) throws -> WorkoutEvent {
  try event(
    id, "health", "watch", t,
    [
      "representation": .string("healthTombstone"), "sampleUUID": .string(sample),
      "supersedesEventId": .string(sample), "deleted": .bool(true),
    ])
}
let deletedSeries = try series(main, 11.1, [150, 151])
let laterSeries = try series(main, 13.1, [160, 161])
let seriesDeletion = try deletion(main, 16.1, deletedSeries.sample)
try append(main, deletedSeries.rows + laterSeries.rows + [seriesDeletion])
deselect(
  main,
  [replaced.eventId, deleted.eventId, tombstone.eventId, seriesDeletion.eventId] + deletedSeries.rows.map(\.eventId))
try seal(main, stop: 20)
let revision = try archive.revision(id: main)

let paged = [
  ExportSource(pageRows: 1, jobRows: 1) { archive }, ExportSource(pageRows: 3, jobRows: 2) { archive },
  ExportSource(pageRows: 17, jobRows: 5) { archive }, ExportSource(pageBytes: 700) { archive },
]
fails("gate", "only the stored lowercase identifier is accepted") { _ = try open(main.uppercased(), "zip") }
let zipSession = try open(main, "zip")
let fitSession = try open(main, "fit")
let small = try paged.map { (zip: try open(main, "zip", on: $0), fit: try open(main, "fit", on: $0)) }
let explicit = try open(main, "fit", distance: "gps:phone")
let controller = try open(main, "fit", distance: "controller")
let unavailable = try open(main, "fit", distance: "health:phone")
check((zipSession["metadata"] as! [String: Any])["startedAt"] as? String == WorkoutCoding.timestamp(start), "startedAt")
let summary = zipSession["metadata"] as! [String: Any]
check(summary["endedAt"] as? String == WorkoutCoding.timestamp(date(20)), "endedAt is the retained cutoff")
check(
  (summary["ownerTiming"] as? [String: Any])?["timerSeconds"] as? Double == 20
    && (summary["ownerTiming"] as? [String: Any])?["timestamp"] as? String == WorkoutCoding.timestamp(date(20)),
  "owner timing is the retained tuple")
check(
  summary["indoor"] as? Bool == false && summary["interrupted"] as? Bool == false
    && summary["watchEnabled"] as? Bool == true
    && summary["saveToHealth"] as? Bool == true && summary["recordGPS"] as? Bool == true, "frozen recording choices")
check(summary["warnings"] == nil, "export metadata carries no ride notices")
let healthMetadata = summary["health"] as! [String: Any]
check(
  healthMetadata["provider"] as? String == "appleHealth" && healthMetadata["state"] as? String == "notSaved"
    && healthMetadata["workoutUUID"] is NSNull && healthMetadata["export"] is NSNull, "Health metadata mapping")
check(
  summary["watchSyncState"] as? String == "received" && summary["finalizationState"] as? String == "complete"
    && summary["example"] as? Bool == false && summary["sampleHz"] is NSNull, "ride state mapping")
check(zipSession["elapsedEnd"] as? Double == 20, "E is the measured stop elapsed")
check(
  (zipSession["producers"] as! [String: Any])["gps"] as? [String] == ["phone", "watch"]
    && (zipSession["producers"] as! [String: Any])["health"] as? [String] == ["phone", "watch"],
  "producers present at R")
check(zipSession["distanceProfile"] is NSNull, "a ZIP session resolves no distance profile")
let profile = fitSession["distanceProfile"] as! [String: Any]
check(
  profile["source"] as? String == "gps:watch" && profile["kind"] as? String == "gps"
    && profile["producer"] as? String == "watch", "auto resolves the owner's GPS profile with its producer")

// A correction and a late fix after R stay outside the snapshot.
let corrected = expected(main, "health").first { $0.event.payload["heartRateBpm"]?.number == 103 }!.event
try append(
  main,
  [
    try heart(main, 3, 140, ["supersedesEventId": .string(corrected.eventId)]), try fix(main, "watch", 3.5),
    try deletion(main, 17.1, laterSeries.sample),
  ])
stored[main]!.removeLast(3)
check(try archive.revision(id: main) == revision + 3, "the later writes advance the collection revision")

var reference: [String: Table] = [:]
for (session, kind) in [(zipSession, "zip"), (fitSession, "fit")] {
  for projection in ["telemetry", "gps", "gpsDiscovery", "healthZip", "healthFit", "lifecycle"]
  where !contract(projection, kind).isEmpty {
    let table = try read(source, session, projection, kind: kind)
    verify(table, main, projection, kind: kind)
    reference[kind + projection] = table
    for (index, exporter) in paged.enumerated() {
      let other = try read(exporter, kind == "zip" ? small[index].zip : small[index].fit, projection, kind: kind)
      verify(other, main, projection, kind: kind)
      check(
        other.pages >= table.pages && other.connections.count == table.connections.count,
        "\(projection) pages identically at page size \(exporter.pageRows)")
    }
  }
}
check(
  reference["zipgps"]!.strings["producer"]!.prefix(4) == ["watch", "phone", "watch", "phone"], "producers interleave")
check(
  reference["zipgps"]!.numbers["elapsedSeconds"]![10...11] == [5, 5]
    && reference["zipgps"]!.strings["producer"]![10...11] == ["phone", "watch"], "equal-elapsed fixes tie by producer")
check(
  reference["ziptelemetry"]!.numbers["elapsedSeconds"]![10...11] == [5, 5]
    && reference["ziptelemetry"]!.numbers["humanPowerW"]![10] < reference["ziptelemetry"]!.numbers["humanPowerW"]![11],
  "equal-elapsed frames keep their sequence order")
check(
  reference["ziplifecycle"]!.strings["action"]! == ["start", "pause", "resume", "lap", "stop"]
    && reference["ziplifecycle"]!.numbers["interrupted"]! == [0, 1, 0, 0, 0]
    && reference["ziplifecycle"]!.numbers["cycSequence"]![1] == 12, "lifecycle keeps its stored group order")
check(reference["ziphealthZip"]!.numbers["elapsedSeconds"]!.first == -5, "Health before the start is exported")
check(
  !reference["ziphealthZip"]!.numbers["heartRateBpm"]!.contains(150)
    && !reference["ziphealthZip"]!.strings["sampleUUID"]!.contains(deletedSeries.sample)
    && !reference["fithealthFit"]!.numbers["heartRateBpm"]!.contains(151),
  "a Health deletion removes the parent and its independently identified series values")
check(
  reference["ziphealthZip"]!.strings["sampleUUID"]!.filter { $0 == laterSeries.sample }.count == 3
    && reference["fithealthFit"]!.numbers["heartRateBpm"]!.filter { $0 == 160 || $0 == 161 }.count == 2,
  "a Health deletion after R is not visible")
check(
  !reference["ziphealthZip"]!.strings["sampleUUID"]!.contains(deleted.payload["sampleUUID"]!.string)
    && !reference["ziphealthZip"]!.numbers["heartRateBpm"]!.contains(90)
    && reference["ziphealthZip"]!.numbers["heartRateBpm"]!.contains(92), "superseded and deleted Health is absent")
check(
  reference["ziphealthZip"]!.numbers["heartRateBpm"]!.contains(103)
    && !reference["ziphealthZip"]!.numbers["heartRateBpm"]!.contains(140)
    && reference["zipgps"]!.numbers["elapsedSeconds"]!.filter { $0 == 3.5 }.count == 1,
  "a correction and a fix after R are not visible")
check(
  reference["ziphealthZip"]!.strings["representation"]!.contains("workoutAssociation"),
  "every selected Health row is delivered")
let connections = reference["ziptelemetry"]!.connections
check(
  connections.count == 2 && connections[0]["token"] as? String == "connection-a"
    && connections[0]["model"] as? String == "X6" && connections[0]["firmware"] as? String == "20240601"
    && connections[0]["protocol"] as? String == "5.3" && connections[0]["vendor"] as? String == "cyc"
    && connections[1]["token"] as? String == "connection-b" && connections[1]["model"] as? String == "X12",
  "each connection's identity comes once per pass, in order of first appearance")
let again = try source.page(["session": zipSession["session"]!, "projection": "telemetry", "after": NSNull()])
check((again["connections"] as! [[String: Any]]).count == 2, "a new pass lists the identities again")
check(ExportSource.failure(PowerLogStorageError.busy).code == "changed", "a busy store asks for a retry")

// Distance: the stored intervals of the leased profile, from which the stored points derive again.
let snapshot = try distances.snapshot(id: main, revision: revision)
let points = try distances.page(snapshot: snapshot)
let intervals = points.filter { $0.startSeconds < $0.endSeconds }
var distanceTables: [Table] = []
for (exporter, session) in [(source, fitSession)] + zip(paged, small.map(\.fit)).map({ ($0.0, $0.1) }) {
  let table = try read(exporter, session, "distance", kind: "fit")
  distanceTables.append(table)
  check(table.rows == intervals.count && table.rows > 10, "every stored interval is delivered once")
  for (index, interval) in intervals.enumerated() {
    check(
      table.numbers["start"]![index] == interval.startSeconds && table.numbers["end"]![index] == interval.endSeconds
        && table.numbers["meters"]![index] == interval.incrementMeters
        && table.numbers["segment"]![index] == Double(interval.segment)
        && same(table.numbers["startSpeed"]![index], interval.startSpeed ?? .nan)
        && same(table.numbers["endSpeed"]![index], interval.endSpeed ?? .nan), "interval \(index) maps its end point")
  }
}
var derived: [(Double, Double)] = []
var cumulative = 0.0
var previousSegment: Double?
var previousEnd: Double?
let table = distanceTables[0]
for index in 0..<table.rows {
  let (begin, end) = (table.numbers["start"]![index], table.numbers["end"]![index])
  if previousSegment != table.numbers["segment"]![index] || previousEnd != begin { derived.append((begin, cumulative)) }
  cumulative += table.numbers["meters"]![index]
  derived.append((end, cumulative))
  previousSegment = table.numbers["segment"]![index]
  previousEnd = end
}
check(
  derived.count == points.count
    && zip(derived, points).allSatisfy { $0.0 == $1.elapsedSeconds && $0.1 == $1.distanceMeters },
  "the iPhone distance points derive exactly from the delivered intervals")
check(
  (explicit["distanceProfile"] as? [String: Any])?["producer"] as? String == "phone",
  "an explicit GPS profile carries its producer")
check(
  (controller["distanceProfile"] as? [String: Any])?["kind"] as? String == "controller"
    && (controller["distanceProfile"] as? [String: Any])?["producer"] == nil, "a controller profile has no producer")
let controllerTable = try read(source, controller, "distance", kind: "fit")
check(
  controllerTable.rows > 0 && controllerTable.numbers["startSpeed"]!.allSatisfy(\.isFinite)
    && controllerTable.numbers["endSpeed"]!.allSatisfy(\.isFinite), "controller intervals carry their endpoint speeds")
check(unavailable["distanceProfile"] is NSNull, "an unavailable explicit profile resolves to none")
let none = try source.page(["session": unavailable["session"]!, "projection": "distance", "after": NSNull()])
check(none["rows"] as? Int == 0 && none["done"] as? Bool == true, "without a profile the distance stream is empty")
for session in [explicit, controller, unavailable] { source.close(session["session"] as! String) }

// Requests outside the session's contract.
fails("unsupported", "a ZIP session reads no FIT projection") {
  _ = try source.page(["session": zipSession["session"]!, "projection": "healthFit", "after": NSNull()])
}
fails("unsupported", "a FIT session reads no ZIP Health") {
  _ = try source.page(["session": fitSession["session"]!, "projection": "healthZip", "after": NSNull()])
}
fails("unsupported", "a ZIP session reads no distance") {
  _ = try source.page(["session": zipSession["session"]!, "projection": "distance", "after": NSNull()])
}
fails("unsupported", "unknown projections are refused") {
  _ = try source.page(["session": zipSession["session"]!, "projection": "events", "after": NSNull()])
}
let malformed: [Any] = [
  [1.0, 1, 1], [1.0, 3, 1, 1], [1.0, 0, 1.5, 1], [Double.nan, 0, 1, 1], ["x", 0, 1, 1] as [Any],
]
for after in malformed {
  fails("cursor", "malformed cursors are refused: \(after)") {
    _ = try source.page(["session": zipSession["session"]!, "projection": "telemetry", "after": after])
  }
}
fails("cursor", "a distance cursor has two keys") {
  _ = try source.page(["session": fitSession["session"]!, "projection": "distance", "after": [1.0, 2, 3]])
}
source.closeAll()
fails("unsupported", "unknown export kinds are refused") { _ = try source.open(["rideId": main, "kind": "csv"]) }
fails("unsupported", "unknown distance sources are refused") { _ = try open(main, "fit", distance: "gps:bike") }
fails("deleted", "an unknown ride cannot be exported") { _ = try open(UUID().uuidString.lowercased(), "zip") }

// Only a ride with its retained end and a verified seal opens.
let running = try ride()
try append(running, [event(running, "lifecycle", "watch", 0, ["action": .string("start")])])
fails("gate", "a recording ride is refused") { _ = try open(running, "zip") }
try archive.update(id: running, phase: "completed")
fails("gate", "a ride without its retained end is refused") { _ = try open(running, "zip") }
let unsealed = try ride()
try append(unsealed, [event(unsealed, "lifecycle", "watch", 0, ["action": .string("start")])])
try archive.update(id: unsealed, stopElapsedSeconds: 1)
try archive.finish(id: unsealed, endedAt: date(1))
fails("gate", "a ride without a seal is refused") { _ = try open(unsealed, "zip") }
try archive.update(id: unsealed, finalizationState: "complete")
fails("gate", "a ride without a seal is refused although finalized") { _ = try open(unsealed, "zip") }
try archive.update(id: unsealed, sealRevision: 1, verifiedSealRevision: 1)
check((try open(unsealed, "zip"))["session"] is String, "a verified seal admits the ride")
try archive.update(id: unsealed, sealRevision: 2)
fails("gate", "an unverified seal is refused") { _ = try open(unsealed, "fit") }
let unfinalized = try ride()
try append(unfinalized, [event(unfinalized, "lifecycle", "watch", 0, ["action": .string("start")])])
try seal(unfinalized, stop: 1)
check((try open(unfinalized, "zip"))["session"] is String, "a verified ride opens")
try append(unfinalized, [try fix(unfinalized, "watch", 0.5)])
check(try archive.metadata(id: unfinalized).finalizationState == "pending", "a late original reopens finalization")
fails("gate", "a ride whose finalization is pending is refused") { _ = try open(unfinalized, "zip") }

// Deletion during a session fails its next page.
let doomed = try ride()
try append(doomed, (0..<6).map { try fix(doomed, "watch", Double($0)) })
try append(doomed, [event(doomed, "lifecycle", "watch", 0, ["action": .string("start")])])
try seal(doomed, stop: 6)
let narrow = ExportSource(pageRows: 2, jobRows: 2) { archive }
let doomedZip = try open(doomed, "zip", on: narrow)
let doomedFit = try open(doomed, "fit", on: narrow)
let doomedWithoutDistance = try open(doomed, "fit", on: narrow, distance: "health:phone")
check(doomedWithoutDistance["distanceProfile"] is NSNull, "the session has no distance profile")
let first = try narrow.page(["session": doomedZip["session"]!, "projection": "gps", "after": NSNull()])
let firstDistance = try narrow.page(["session": doomedFit["session"]!, "projection": "distance", "after": NSNull()])
_ = try store.markWorkoutDeleted(id: doomed)
fails("deleted", "an empty distance stream still fails once the ride is deleted") {
  _ = try narrow.page(["session": doomedWithoutDistance["session"]!, "projection": "distance", "after": NSNull()])
}
fails("deleted", "a ride deleted mid-session fails the next page") {
  _ = try narrow.page(["session": doomedZip["session"]!, "projection": "gps", "after": first["last"]!])
}
fails("deleted", "a ride deleted mid-session fails the next distance page") {
  _ = try narrow.page(["session": doomedFit["session"]!, "projection": "distance", "after": firstDistance["last"]!])
}
fails("deleted", "a deleted ride cannot open") { _ = try open(doomed, "zip") }

// The distance lease survives rebuilds that would otherwise prune the session's generation.
let leased = try ride()
try append(leased, [event(leased, "lifecycle", "watch", 0, ["action": .string("start")])])
try append(leased, (0..<40).map { try fix(leased, "watch", Double($0) * 2) })
try seal(leased, stop: 80)
let leasedRevision = try archive.revision(id: leased)
let expectedPoints = try distances.page(snapshot: try distances.snapshot(id: leased, revision: leasedRevision))
  .filter { $0.startSeconds < $0.endSeconds }
let leasedSession = try open(leased, "fit", on: narrow)
let leasedGeneration = try distances.snapshot(id: leased, revision: leasedRevision).generation
var leasedRows = try narrow.page(["session": leasedSession["session"]!, "projection": "distance", "after": NSNull()])
var leasedEnds = doubles((leasedRows["columns"] as! [String: Any])["end"])
var generations = Set([leasedGeneration])
try append(leased, [try fix(leased, "watch", 79)])
check(try distances.snapshot(id: leased).generation == leasedGeneration, "a later fix appends to the leased generation")
for late in 0..<5 {
  try append(leased, [try fix(leased, "watch", Double(late) * 2 + 1)])
  generations.insert(try distances.snapshot(id: leased).generation)
}
check(generations.count == 6, "every late original rebuilds a new generation")
let retained = try store.read { db in
  try db.rows(
    "SELECT generation FROM distance_generations WHERE collection_id=?", [.text(leased)], limit: 16
  ).compactMap { $0.string("generation") }
}
check(retained.contains(leasedGeneration) && retained.count == 4, "pruning keeps three generations and the lease")
while leasedRows["done"] as? Bool == false {
  leasedRows = try narrow.page(
    ["session": leasedSession["session"]!, "projection": "distance", "after": leasedRows["last"]!])
  leasedEnds += doubles((leasedRows["columns"] as! [String: Any])["end"])
}
check(leasedEnds == expectedPoints.map(\.endSeconds), "the leased profile pages unchanged across rebuilds")
narrow.close(leasedSession["session"] as! String)
narrow.close(leasedSession["session"] as! String)
try append(leased, [try fix(leased, "watch", 81)])
_ = try distances.snapshot(id: leased)
let released = try store.read { db in
  try db.rows(
    "SELECT generation FROM distance_generations WHERE collection_id=?", [.text(leased)], limit: 16
  ).compactMap { $0.string("generation") }
}
check(!released.contains(leasedGeneration) && released.count == 3, "close releases the lease to pruning")
fails("cancelled", "a closed session reads nothing") {
  _ = try narrow.page(["session": leasedSession["session"]!, "projection": "distance", "after": NSNull()])
}
narrow.closeAll()
fails("cancelled", "closing all sessions ends them") {
  _ = try narrow.page(["session": doomedFit["session"]!, "projection": "lifecycle", "after": NSNull()])
}
let aged = try ride()
try append(aged, [event(aged, "lifecycle", "watch", 0, ["action": .string("start")])])
try append(aged, (0..<20).map { try fix(aged, "watch", Double($0) * 2) })
let agedRevision = try archive.revision(id: aged)
for late in 0..<3 {
  try append(aged, [try fix(aged, "watch", Double(late) * 2 + 1)])
  _ = try distances.snapshot(id: aged)
}
let newer = try store.read { db in
  try db.rows(
    "SELECT revision FROM distance_generations WHERE collection_id=?", [.text(aged)], limit: 16
  ).compactMap { $0.int("revision") }
}
check(newer.count == 3 && newer.allSatisfy { $0 > agedRevision }, "three newer generations exist")
let agedLease = try distances.leasedSnapshot(id: aged, revision: agedRevision)
check(
  try distances.page(snapshot: agedLease.snapshot).count == 20 && agedLease.snapshot.revision == agedRevision,
  "a lease at an older revision survives the prune of its own rebuild")
agedLease.lease.release()

// Bounded database work: sparse and exhausted streams, and the query plans behind them.
func work(_ id: String, _ projection: String, kind: String) throws -> [ExportSource.Job] {
  let session = try open(id, kind)
  jobs.removeAll()
  _ = try read(source, session, projection, kind: kind)
  source.close(session["session"] as! String)
  return jobs.filter { $0.projection == projection }
}
var sparse: [String] = []
for frames in [200, 2000] {
  let id = try ride(watch: false, saves: false)
  try append(id, [event(id, "lifecycle", "phone", 0, ["action": .string("start")])])
  try append(
    id,
    (0..<frames).map { index in
      try event(id, "telemetry", "cyc", Double(index) / 8, ["humanPowerW": .number(150), "cadenceRpm": .number(80)])
    })
  try append(id, (0..<30).map { try fix(id, "phone", Double($0)) })
  try seal(id, stop: Double(frames) / 8)
  sparse.append(id)
}
let quiet = try work(sparse[0], "gps", kind: "zip")
let busy = try work(sparse[1], "gps", kind: "zip")
check(
  quiet.map(\.examined) == [30] && busy.map(\.examined) == [30],
  "a sparse stream examines only its own candidates")
check(
  abs(quiet[0].steps - busy[0].steps) <= 10 * 30,
  "sparse paging work does not grow with 1,800 more telemetry rows: \(quiet.map(\.steps)) \(busy.map(\.steps))")
let shortTelemetry = try work(sparse[0], "telemetry", kind: "fit")
let session = try open(sparse[0], "zip")
check(try read(source, session, "gps", kind: "zip").rows == 30, "the sparse stream is complete")
jobs.removeAll()
let tail = try source.page(
  [
    "session": session["session"]!, "projection": "telemetry",
    "after": [Double(199) / 8, 0, Double(sequences[sparse[0] + ":cyc"]!), 1e9],
  ])
check(
  tail["rows"] as? Int == 0 && tail["done"] as? Bool == true && jobs.map(\.examined) == [0] && jobs[0].steps < 100,
  "an exhausted stream examines no candidate although other streams continue: \(jobs.map(\.steps))")
check(shortTelemetry.allSatisfy { $0.examined <= 512 }, "each job examines a bounded batch")
source.closeAll()

for projection in ["telemetry", "gps", "gpsDiscovery", "healthZip", "healthFit", "lifecycle"] {
  for kind in ["zip", "fit"] where !contract(projection, kind).isEmpty {
    let plan = try store.read { db in
      try db.rows(
        "EXPLAIN QUERY PLAN " + ExportSource.candidateSQL(projection: projection, kind: kind, limit: 512)!,
        [.integer(1), .integer(1), .integer(1), .text(main), .real(0), .text(""), .integer(0), .integer(0)],
        limit: 32)
    }.compactMap { $0.string("detail") }
    check(
      plan.contains {
        $0.hasPrefix(
          "SEARCH m USING INDEX membership_kind_time (collection_id=? AND kind=? AND (elapsed_seconds,producer,sequence,rowid)>(?,?,?,?))"
        )
      }
        && !plan.contains {
          $0.contains("TEMP B-TREE") || $0.contains("membership_export_time") || $0.hasPrefix("SCAN")
        },
      "\(projection) seeks its stream index in tie order: \(plan)")
  }
}
for kind in ["location", "health"] {
  let plan = try store.read { db in
    try db.rows(
      "EXPLAIN QUERY PLAN " + ExportSource.presenceSQL(kind: kind, limit: 512),
      [.integer(1), .integer(1), .integer(1), .text(main), .text("phone"), .real(0), .integer(0)], limit: 32)
  }.compactMap { $0.string("detail") }
  check(
    plan.contains { $0.hasPrefix("SEARCH m USING INDEX membership_stream_time") }
      && !plan.contains { $0.contains("TEMP B-TREE") || $0.hasPrefix("SCAN") },
    "producer presence seeks the source index: \(plan)")
}
let distancePlan = try store.read { db in
  try db.rows(
    "EXPLAIN QUERY PLAN " + WorkoutDistanceStore.exportIntervalQuery(limit: 512),
    [.integer(1), .text("gps:watch"), .real(0), .integer(0)], limit: 32)
}.compactMap { $0.string("detail") }
check(
  distancePlan.contains {
    $0.hasPrefix(
      "SEARCH distance_points USING INDEX distance_point_time (generation_id=? AND source=? AND (elapsed_seconds,point_id)>(?,?))"
    )
  } && !distancePlan.contains { $0.contains("TEMP B-TREE") }, "distance pages seek the point index: \(distancePlan)")

// Heavily superseded and later Health: every rejected candidate still advances the job cursor.
let superseded = try ride()
try append(superseded, [event(superseded, "lifecycle", "watch", 0, ["action": .string("start")])])
let originals = try (0..<1200).map { try heart(superseded, Double($0) / 100, 120) }
try append(superseded, originals)
try append(
  superseded,
  originals.map { original in
    try event(
      superseded, "health", "watch", original.elapsedSeconds! + 0.001,
      [
        "representation": .string("healthTombstone"), "sampleUUID": original.payload["sampleUUID"]!,
        "supersedesEventId": .string(original.eventId), "deleted": .bool(true),
      ])
  })
deselect(superseded, stored[superseded]!.filter { $0.kind == "health" }.map(\.event.eventId))
let survivors = try (0..<3).map { try heart(superseded, 20 + Double($0), 130) }
try append(superseded, survivors)
try seal(superseded, stop: 30)
let heavy = try open(superseded, "zip")
jobs.removeAll()
let heavyPage = try source.page(["session": heavy["session"]!, "projection": "healthZip", "after": NSNull()])
let heavyJobs = jobs.filter { $0.projection == "healthZip" }
check(
  heavyPage["rows"] as? Int == 3 && heavyPage["done"] as? Bool == true
    && heavyJobs.map(\.examined) == [512, 512, 512, 512, 355], "one request runs bounded jobs until rows appear")
let perCandidate = heavyJobs.map { Double($0.steps) / Double(max(1, $0.examined)) }
check(perCandidate.allSatisfy { $0 < 120 }, "rejected candidates cost bounded work each: \(perCandidate)")
try append(superseded, try (0..<700).map { try heart(superseded, 5 + Double($0) / 100, 125) })
stored[superseded]!.removeLast(700)
jobs.removeAll()
let later = try read(source, heavy, "healthZip", kind: "zip")
verify(later, superseded, "healthZip", kind: "zip")
check(
  jobs.filter { $0.projection == "healthZip" }.map(\.examined) == [512, 512, 512, 512, 512, 512, 31]
    && jobs.allSatisfy { $0.examined <= 512 }, "rows above R are examined in bounded jobs and never delivered")

// Exact keys only: sequences above 2^53 − 1 and unknown producers fail the export.
let huge = try ride()
try append(huge, [event(huge, "lifecycle", "watch", 0, ["action": .string("start")])])
try append(huge, [try fix(huge, "watch", 1)], producer: "watch", firstSequence: 9_007_199_254_740_992)
try admitWithoutSeal(huge, stop: 2)
let hugeSession = try open(huge, "zip")
fails("limit", "a sequence above 2^53 − 1 fails") {
  _ = try source.page(["session": hugeSession["session"]!, "projection": "gps", "after": NSNull()])
}
let relayed = try ride()
try append(relayed, [try fix(relayed, "phone", 1)], producer: "relay")
try admitWithoutSeal(relayed, stop: 2)
let relayedSession = try open(relayed, "zip")
fails("unsupported", "an unknown producer fails") {
  _ = try source.page(["session": relayedSession["session"]!, "projection": "gps", "after": NSNull()])
}

let hidden = try ride()
let visible = try fix(hidden, "watch", 1)
let oversized = try fix(hidden, "watch", 2)
let relay = try fix(hidden, "phone", 3)
try append(hidden, [visible])
try append(hidden, [oversized], producer: "watch", firstSequence: 9_007_199_254_740_992)
try append(hidden, [relay], producer: "relay")
try append(
  hidden,
  [
    try fix(hidden, "phone", 2, ["supersedesEventId": .string(oversized.eventId)]),
    try fix(hidden, "phone", 3, ["supersedesEventId": .string(relay.eventId)]),
  ])
deselect(hidden, [oversized.eventId, relay.eventId])
try admitWithoutSeal(hidden, stop: 4)
verify(try read(source, try open(hidden, "zip"), "gps", kind: "zip"), hidden, "gps", kind: "zip")
let broken = try ride()
try append(
  broken,
  [
    event(broken, "lifecycle", "watch", 0, ["action": .string("start")]),
    event(broken, "lifecycle", "watch", 2, ["action": .string("pause"), "interrupted": .bool(true)]),
  ])
try append(broken, (0..<4).map { try fix(broken, "watch", Double($0)) })
try seal(broken, stop: 4)
check(try open(broken, "fit")["distanceProfile"] is NSNull, "a broken distance build leaves FIT without distance")

// Producers count selected measurements only.
let partial = try ride(watch: false)
try append(partial, [try fix(partial, "phone", 1)])
let gone = try heart(partial, 2, 100)
try append(
  partial,
  [
    try event(
      partial, "health", "phone", 3,
      [
        "representation": .string("workoutAssociation"), "sampleUUID": .string(UUID().uuidString.lowercased()),
        "associatedWorkoutUUID": .string(UUID().uuidString.lowercased()),
      ]), gone,
    try event(
      partial, "health", "watch", 2.5,
      [
        "representation": .string("healthTombstone"), "sampleUUID": gone.payload["sampleUUID"]!,
        "supersedesEventId": .string(gone.eventId), "deleted": .bool(true),
      ]),
  ])
try admitWithoutSeal(partial, stop: 4)
let partialProducers = try open(partial, "zip")["producers"] as! [String: [String]]
check(partialProducers == ["gps": ["phone"], "health": []], "associations and deleted Health are not producers")

// Stored data that cannot be read fails the gate, at open and while paging.
let garbled = try ride()
try append(garbled, [event(garbled, "lifecycle", "watch", 0, ["action": .string("start")])])
try seal(garbled, stop: 1)
let garbledSession = try open(garbled, "zip")
try store.transaction { db in
  try db.execute(
    "UPDATE observations SET extra=? WHERE id=(SELECT observation_id FROM collection_memberships WHERE collection_id=? AND kind='lifecycle')",
    [.blob(Data("{".utf8)), .text(garbled)])
  try db.execute("UPDATE collections SET metadata=? WHERE id=?", [.blob(Data("{".utf8)), .text(garbled)])
}
fails("gate", "an unreadable stored observation fails its page") {
  _ = try source.page(["session": garbledSession["session"]!, "projection": "lifecycle", "after": NSNull()])
}
fails("gate", "unreadable stored metadata fails the open") { _ = try open(garbled, "zip") }

source.closeAll()

// Example rides export through the same contract.
_ = try WorkoutExampleRides.write(archive: archive, durations: [30])
let example = try open(WorkoutExampleRides.id(0), "zip")
check((example["metadata"] as! [String: Any])["example"] as? Bool == true, "example rides are flagged")
let exampleHealth = try read(source, example, "healthZip", kind: "zip")
check(
  Set(exampleHealth.strings["representation"]!.compactMap { $0 }) == ["builderMostRecent", "cumulativeWorkoutTotal"],
  "example rides export the declared Health representations")

check(replanned.isEmpty, "no export statement is re-planned between jobs: \(replanned)")
ExportSource.jobObserverForTesting = nil
print("Export source: \(assertions) assertions passed")
