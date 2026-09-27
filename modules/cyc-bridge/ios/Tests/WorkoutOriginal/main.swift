import Foundation
var assertions = 0
func check(_ value: @autoclosure () -> Bool, _ message: String) {
  assertions += 1
  if !value() { fatalError(message) }
}
func rejects(_ body: () throws -> Void, _ message: String) {
  do {
    try body()
    check(false, message)
  } catch { check(true, message) }
}
let fm = FileManager.default
let root = fm.temporaryDirectory.appendingPathComponent("powerlog-original-tests-" + UUID().uuidString)
try fm.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? fm.removeItem(at: root) }
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("archive"))
let id = UUID().uuidString.lowercased()
let start = Date(timeIntervalSince1970: 1_780_000_000)
_ = try archive.create(id: id, startedAt: start, indoor: false, watchEnabled: true, saveToHealth: true, recordGPS: true)
let zip = root.appendingPathComponent("original.zip")
rejects(
  { try WorkoutOriginalExport.write(archive: archive, id: id, to: zip) }, "running ride cannot export original data")
var cyc: [String: Any] = [:]
for key in CycProtocol.columns.dropFirst() { cyc[key] = 0.0 }
cyc["timestamp"] = WorkoutCoding.timestamp(start.addingTimeInterval(2))
cyc["humanPowerW"] = 151
cyc["cadenceRpm"] = 74.5
cyc["motorInputPowerW"] = 484.7
cyc["batteryVoltageV"] = 52.4
cyc["batteryCurrentA"] = 9.25
cyc["elapsedSeconds"] = 120.0
cyc["sequence"] = 240
cyc["speedRaw"] = 36.0
cyc["controllerSpeedMps"] = 10.0
cyc["controllerModel"] = "X12"
cyc["firmwareLabel"] = "20250604"
cyc["controllerProtocol"] = "5.3"
cyc["connectionEpoch"] = "11111111-1111-4111-8111-111111111111"
var reconnected = cyc
reconnected["timestamp"] = WorkoutCoding.timestamp(start.addingTimeInterval(5))
reconnected["elapsedSeconds"] = 0.0
reconnected["sequence"] = 0
reconnected["connectionEpoch"] = "22222222-2222-4222-8222-222222222222"
let originals = try [
  WorkoutEvent(dictionary: [
    "schemaVersion": 1, "workoutId": id, "eventId": UUID().uuidString, "kind": "telemetry", "source": "cyc",
    "timestamp": cyc["timestamp"]!, "elapsedSeconds": 2.0, "payload": cyc,
  ]),
  WorkoutEvent(dictionary: [
    "schemaVersion": 1, "workoutId": id, "eventId": UUID().uuidString, "kind": "health", "source": "watch",
    "timestamp": WorkoutCoding.timestamp(start),
    "payload": [
      "heartRateBpm": 117.125,
      "rawQuantities": [
        [
          "identifier": "synthetic.quantity", "value": 12.375, "unit": "count",
          "startDate": WorkoutCoding.timestamp(start),
        ]
      ],
    ],
  ]),
  WorkoutEvent(dictionary: [
    "schemaVersion": 1, "workoutId": id, "eventId": UUID().uuidString, "kind": "location", "source": "watch",
    "timestamp": WorkoutCoding.timestamp(start), "elapsedSeconds": 0.0,
    "payload": ["latitude": 0.01234567, "longitude": 0.07654321, "horizontalAccuracyM": 3.25],
  ]),
  WorkoutEvent(dictionary: [
    "schemaVersion": 1, "workoutId": id, "eventId": UUID().uuidString, "kind": "telemetry", "source": "cyc",
    "timestamp": reconnected["timestamp"]!, "elapsedSeconds": 5.0, "payload": reconnected,
  ]),
]
for event in originals { try archive.append(event) }
_ = try archive.update(id: id, healthKitState: "saved")
_ = try archive.finish(id: id, endedAt: start.addingTimeInterval(6), finalPhase: "completed")
rejects(
  { try WorkoutOriginalExport.write(archive: archive, id: id, to: zip) },
  "saved HealthKit status does not substitute for final Watch archive")
_ = try archive.update(id: id, watchSyncState: "received")
rejects(
  { try WorkoutOriginalExport.write(archive: archive, id: id, to: zip) },
  "transport status without a verified seal cannot export")
let transfer = WorkoutTransferJournal(archive: archive)
try transfer.register(id: id, producer: "cyc")
try transfer.register(id: id, producer: "watch")
let sources = try ["cyc", "watch"].map { try transfer.source(id: id, producer: $0) }
let seal = WorkoutSeal(
  workoutID: id, sealRevision: 1, collectionRevision: try archive.metadata(id: id).collectionRevision ?? 0,
  ownerRevision: 1, stopCutoff: WorkoutCoding.timestamp(start.addingTimeInterval(6)), healthOutcome: "saved",
  requirements: ["ownerEnded": "sealed", "healthExtraction": "sealed"], sources: sources, stopElapsedSeconds: 6,
  timerSeconds: 6,
  saveToHealth: true, recordGPS: true)
_ = try transfer.accept(seal: seal)
_ = try transfer.verify(id: id)
let before = try archive.metadata(id: id)
let csvOnly = CommandLine.arguments.contains("--csv-only")
let extracted = root.appendingPathComponent("extracted")
if csvOnly {
  try fm.createDirectory(at: extracted, withIntermediateDirectories: true)
  try WorkoutOriginalExport.writeContents(archive: archive, metadata: before, to: extracted)
} else {
  try WorkoutOriginalExport.write(archive: archive, id: id, to: zip)
  check(fm.fileExists(atPath: zip.path), "ZIP persists beyond coordinator accessor lifetime")
  let verify = Process()
  verify.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
  verify.arguments = ["-tq", zip.path]
  verify.standardOutput = Pipe()
  verify.standardError = Pipe()
  try verify.run()
  verify.waitUntilExit()
  check(verify.terminationStatus == 0, "system unzip validates ZIP directory and CRCs")
  let unzip = Process()
  unzip.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
  unzip.arguments = ["-x", "-k", zip.path, extracted.path]
  try unzip.run()
  unzip.waitUntilExit()
  check(unzip.terminationStatus == 0, "system archive extractor accepts package")
}
let files = fm.enumerator(at: extracted, includingPropertiesForKeys: nil)!.allObjects.compactMap { $0 as? URL }
func file(_ name: String) -> URL { files.first { $0.lastPathComponent == name }! }
for name in ["metadata.json", "events.jsonl", "CYCtelemetry.csv", "manifest.json"] {
  check(files.contains { $0.lastPathComponent == name }, "portable package contains \(name)")
}
let events = try String(contentsOf: file("events.jsonl"), encoding: .utf8).split(separator: "\n").map {
  try JSONDecoder().decode(WorkoutEvent.self, from: Data($0.utf8))
}
check(events.count == originals.count, "all canonical events exported")
for (actual, expected) in zipEvents(events, originals) {
  let encodedActual = try WorkoutCoding.encoder().encode(actual)
  let encodedExpected = try WorkoutCoding.encoder().encode(expected)
  check(encodedActual == encodedExpected, "original nested payload and event identity retained exactly")
}
let csv = try String(contentsOf: file("CYCtelemetry.csv"), encoding: .utf8)
check(csv.contains("484.7,52.4"), "motor and battery data retained in convenience CSV")
let lines = csv.split(separator: "\n")
check(lines.count == 3, "CSV includes both CYC sessions without synthesized health rows")
let columns = lines[0].split(separator: ",").map(String.init)
check(
  columns == [
    "timestamp", "elapsedSeconds", "sequence", "humanPowerW", "cadenceRpm", "motorInputPowerW",
    "batteryVoltageV", "batteryCurrentA", "motorCurrentA", "motorRpm", "pedalTorqueNm", "controllerTempC",
    "motorTempC", "consumedAh", "consumedWh", "throttleVoltageV", "faultCode", "assistLevel", "raceMode", "speedRaw",
    "controllerSpeedMps", "controllerModel", "firmwareLabel", "controllerProtocol", "connectionEpoch",
    "interruptionIndex",
  ], "CSV uses the canonical 26-column header")
let sampleTypes = try String(contentsOfFile: "src/core/types.ts", encoding: .utf8)
func stringColumns(_ declaration: String) -> [String] {
  let start = sampleTypes.range(of: "export const " + declaration + " = [")!.upperBound
  let end = sampleTypes.range(of: "] as const", range: start..<sampleTypes.endIndex)!.lowerBound
  return sampleTypes[start..<end].components(separatedBy: "'").enumerated().filter { $0.offset % 2 == 1 }.map {
    String($0.element)
  }
}
let required = stringColumns("REQUIRED_SAMPLE_COLUMNS")
let identity = stringColumns("SAMPLE_IDENTITY_COLUMNS")
let additional = stringColumns("SAMPLE_COLUMNS")
check(
  columns == required + [additional[0]] + identity + Array(additional.dropFirst()),
  "native CSV header agrees with TypeScript SAMPLE_COLUMNS")
var previousElapsed = -1.0
var previousSequence = 0.0
for (index, line) in lines.dropFirst().enumerated() {
  let cells = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
  check(cells.count == columns.count, "every CSV row has the canonical column count")
  let row = Dictionary(uniqueKeysWithValues: Swift.zip(columns, cells))
  var numbers: [String: Double] = [:]
  for key in columns
  where !["timestamp", "controllerModel", "firmwareLabel", "controllerProtocol", "connectionEpoch"].contains(key) {
    guard let number = Double(row[key]!) else { fatalError("Missing numeric CSV field \(key)") }
    check(number.isFinite && abs(number) <= 1e12, "\(key) is finite and within importer magnitude bounds")
    numbers[key] = number
  }
  check(
    row["connectionEpoch"] == [cyc, reconnected][index]["connectionEpoch"] as? String,
    "CSV preserves each original connection epoch")
  check(numbers["interruptionIndex"] == 0, "BLE reconnect leaves the hard interruption index unchanged")
  let elapsed = numbers["elapsedSeconds"]!
  let sequence = numbers["sequence"]!
  check(
    elapsed >= 0 && elapsed <= 7 * 24 * 60 * 60 && elapsed > previousElapsed,
    "CSV elapsed time strictly increases within the duration limit")
  check(elapsed == [2.0, 5.0][index], "CSV elapsed time uses the ride timeline across a BLE session reset")
  check(
    sequence >= 0 && sequence <= 9_007_199_254_740_991 && sequence.rounded() == sequence,
    "CSV sequence is a nonnegative safe integer")
  check(sequence == previousSequence + 1, "CSV sequence increases once per telemetry row across a BLE session reset")
  for key in ["faultCode", "assistLevel", "raceMode"] {
    let value = numbers[key]!
    check(value.rounded() == value && (0...255).contains(value), "\(key) is an unsigned byte")
  }
  for key in ["humanPowerW", "motorRpm"] {
    let value = numbers[key]!
    check(value.rounded() == value && (-2147483648...2147483647).contains(value), "\(key) is a signed wire integer")
  }
  let watts = numbers["batteryVoltageV"]! * numbers["batteryCurrentA"]!
  check(
    abs(numbers["motorInputPowerW"]! - watts) <= max(0.001, abs(watts) * 1e-7),
    "battery input watts agree with voltage times battery current")
  let speed = numbers["controllerSpeedMps"]!
  check(
    abs(speed - numbers["speedRaw"]! / 3.6) <= max(1e-9, abs(speed) * 1e-12),
    "normalized controller speed agrees with the raw value")
  check(
    row["controllerModel"] == "X12" && row["firmwareLabel"] == "20250604" && row["controllerProtocol"] == "5.3",
    "controller provenance is valid for normalized speed")
  check(
    row["timestamp"]!.hasSuffix("Z") && (try? WorkoutCoding.date(row["timestamp"]!)) != nil,
    "CSV timestamps remain valid original UTC")
  previousElapsed = elapsed
  previousSequence = sequence
}
for times in [[0.1, 0.4, 0.5, 0.9, 1.1], [0.1, 0.4, 0.4, 0.9, 1.1]] {
  let interruptedID = try archive.create(
    startedAt: start, indoor: false, watchEnabled: true, saveToHealth: false, recordGPS: false
  ).id
  for (index, time) in times.enumerated() {
    var sample = cyc
    sample["clockEpoch"] = index < 2 ? "first" : index < 4 ? "second" : "third"
    sample["interruptionIndex"] = 999
    try archive.append(
      WorkoutEvent(dictionary: [
        "schemaVersion": 1, "workoutId": interruptedID,
        "eventId": String(interruptedID.prefix(24)) + String(format: "%012d", index),
        "kind": "telemetry", "source": "cyc", "timestamp": WorkoutCoding.timestamp(start.addingTimeInterval(time)),
        "elapsedSeconds": time, "payload": sample,
      ]))
  }
  try archive.append(
    WorkoutEvent(
      workoutId: interruptedID, kind: "lifecycle", source: "watch", timestamp: start.addingTimeInterval(-30),
      elapsedSeconds: 0.4,
      payload: ["action": .string("pause"), "interrupted": .bool(true), "cycSequence": .string("2")]))
  let interruptedContents = root.appendingPathComponent("interrupted-contents-" + interruptedID)
  try fm.createDirectory(at: interruptedContents, withIntermediateDirectories: true)
  try WorkoutOriginalExport.writeContents(
    archive: archive, metadata: archive.metadata(id: interruptedID), to: interruptedContents)
  let interruptedCSV = try String(
    contentsOf: interruptedContents.appendingPathComponent("CYCtelemetry.csv"), encoding: .utf8)
  let interruptionCells = interruptedCSV.split(separator: "\n").dropFirst().map {
    $0.split(separator: ",", omittingEmptySubsequences: false).last.map(String.init)
  }
  check(
    interruptionCells == ["0", "0", "1", "1", "2"],
    "CSV retains one interruption at and after a process transition, including equal elapsed cutoff samples")
}
for sameEpoch in [false, true] {
  let tiedID = try archive.create(
    startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false
  ).id
  for index in (0..<520).reversed() {
    let epoch = index < 260 || sameEpoch ? "old-process" : "new-process"
    let event = try WorkoutEvent(
      workoutId: tiedID, kind: "telemetry", source: "cyc", timestamp: start.addingTimeInterval(Double(index)),
      elapsedSeconds: 1,
      payload: ["humanPowerW": .number(Double(index)), "cadenceRpm": .number(80), "clockEpoch": .string(epoch)],
      eventId: String(tiedID.prefix(24)) + String(format: "%012d", 520 - index))
    _ = try archive.appendBatch([event], producer: "cyc", firstSequence: Int64(index + 1))
  }
  try archive.append(
    WorkoutEvent(
      workoutId: tiedID, kind: "lifecycle", source: "phone", timestamp: start,
      elapsedSeconds: 1,
      payload: [
        "action": .string("pause"), "interrupted": .bool(true), "clockEpoch": .string("old-process"),
        "cycSequence": .string("260"),
      ]))
  let tiedContents = root.appendingPathComponent("tied-contents-" + tiedID)
  try fm.createDirectory(at: tiedContents, withIntermediateDirectories: true)
  try WorkoutOriginalExport.writeContents(archive: archive, metadata: archive.metadata(id: tiedID), to: tiedContents)
  let tiedRows = try String(contentsOf: tiedContents.appendingPathComponent("CYCtelemetry.csv"), encoding: .utf8)
    .split(separator: "\n").dropFirst().map {
      $0.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
    }
  check(tiedRows.count == 520, "equal-time telemetry keyset pagination preserves every producer observation")
  check(
    tiedRows.enumerated().allSatisfy { Int($0.element[3]) == $0.offset },
    "CSV orders equal-time observations by producer sequence despite reversed UUID and transfer arrival")
  check(
    tiedRows.enumerated().allSatisfy { $0.element.last == ($0.offset < 260 ? "0" : "1") },
    "equal-time CSV rows count one interruption across page boundaries")
}
for kind in ["telemetry", "location", "lifecycle"] {
  let corruptID = try archive.create(
    startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: true
  ).id
  let payload: [String: WorkoutJSON]
  switch kind {
  case "telemetry": payload = ["humanPowerW": .number(100), "cadenceRpm": .number(80)]
  case "location": payload = ["latitude": .number(0), "longitude": .number(0), "horizontalAccuracyM": .number(3)]
  default: payload = ["action": .string("pause"), "interrupted": .bool(true), "cycSequence": .string("0")]
  }
  try archive.append(
    WorkoutEvent(
      workoutId: corruptID, kind: kind, source: kind == "telemetry" ? "cyc" : "phone", timestamp: start,
      elapsedSeconds: 0, payload: payload))
  _ = try archive.store.transaction { db in
    try db.execute(
      "UPDATE collection_memberships SET original_elapsed_seconds=NULL WHERE collection_id=?", [.text(corruptID)])
  }
  let directory = root.appendingPathComponent("corrupt-" + corruptID)
  try fm.createDirectory(at: directory, withIntermediateDirectories: true)
  rejects(
    {
      try WorkoutOriginalExport.writeContents(
        archive: archive, metadata: archive.metadata(id: corruptID), to: directory)
    }, "CSV/JSONL rejects app originals without elapsed: " + kind)
}
let after = try archive.metadata(id: id)
check(
  before.eventCount == after.eventCount && before.phase == after.phase, "export does not mutate the original workout")
if !csvOnly {
  try WorkoutOriginalExport.write(archive: archive, id: id, to: zip)
  check(fm.fileExists(atPath: zip.path), "repeat export replaces completed ZIP safely")
}
print(
  "Workout original export: \(assertions) assertions passed; \(csvOnly ? "CSV/JSONL contents only; ZIP coordinator not exercised" : "real system ZIP round-trip")"
)
func zipEvents(_ a: [WorkoutEvent], _ b: [WorkoutEvent]) -> [(WorkoutEvent, WorkoutEvent)] { Array(Swift.zip(a, b)) }
