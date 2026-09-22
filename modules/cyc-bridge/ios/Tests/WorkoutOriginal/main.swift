import Foundation
var assertions = 0
func check(_ value: @autoclosure () -> Bool, _ message: String) {
  assertions += 1; if !value() { fatalError(message) }
}
func rejects(_ body: () throws -> Void, _ message: String) {
  do { try body(); check(false, message) } catch { check(true, message) }
}
let fm = FileManager.default
let root = fm.temporaryDirectory.appendingPathComponent("powerlog-original-tests-" + UUID().uuidString)
try fm.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? fm.removeItem(at: root) }
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("archive"))
let id = UUID().uuidString.lowercased(), start = Date(timeIntervalSince1970: 1_780_000_000)
_ = try archive.create(id: id, startedAt: start, indoor: false, watchEnabled: true)
let zip = root.appendingPathComponent("original.zip")
rejects({ try WorkoutOriginalExport.write(archive: archive, id: id, to: zip) }, "running ride cannot export original data")
var cyc: [String: Any] = [:]
for key in CycProtocol.columns.dropFirst() { cyc[key] = 0.0 }
cyc["timestamp"] = WorkoutCoding.timestamp(start); cyc["humanPowerW"] = 151.25; cyc["cadenceRpm"] = 74.5
cyc["motorInputPowerW"] = 487.75; cyc["batteryVoltageV"] = 52.375
cyc["controllerModel"] = "X12"; cyc["firmwareLabel"] = "20250604"; cyc["controllerProtocol"] = "5.3"
let originals = try [
  WorkoutEvent(dictionary: ["schemaVersion": 1, "workoutId": id, "eventId": UUID().uuidString, "kind": "telemetry", "source": "cyc", "timestamp": WorkoutCoding.timestamp(start), "payload": cyc]),
  WorkoutEvent(dictionary: ["schemaVersion": 1, "workoutId": id, "eventId": UUID().uuidString, "kind": "health", "source": "watch", "timestamp": WorkoutCoding.timestamp(start), "payload": ["heartRateBpm": 117.125, "rawQuantities": [["identifier": "synthetic.quantity", "value": 12.375, "unit": "count", "startDate": WorkoutCoding.timestamp(start)]]]]),
  WorkoutEvent(dictionary: ["schemaVersion": 1, "workoutId": id, "eventId": UUID().uuidString, "kind": "location", "source": "watch", "timestamp": WorkoutCoding.timestamp(start), "payload": ["latitude": 0.01234567, "longitude": 0.07654321, "horizontalAccuracyM": 3.25]])
]
for event in originals { try archive.append(event) }
_ = try archive.update(id: id, healthKitState: "saved")
_ = try archive.finish(id: id, endedAt: start.addingTimeInterval(1), finalPhase: "completed")
rejects({ try WorkoutOriginalExport.write(archive: archive, id: id, to: zip) }, "saved HealthKit status does not substitute for final Watch archive")
_ = try archive.update(id: id, watchSyncState: "received")
rejects({ try WorkoutOriginalExport.write(archive: archive, id: id, to: zip) }, "transport status without a verified seal cannot export")
let transfer = WorkoutTransferJournal(archive: archive)
try transfer.register(id: id, producer: "cyc")
try transfer.register(id: id, producer: "watch")
let sources = try ["cyc", "watch"].map { try transfer.source(id: id, producer: $0) }
let seal = WorkoutSeal(workoutID: id, sealRevision: 1, collectionRevision: try archive.metadata(id: id).collectionRevision ?? 0,
  ownerRevision: 1, stopCutoff: WorkoutCoding.timestamp(start.addingTimeInterval(1)), healthOutcome: "saved",
  requirements: ["healthExtraction": "sealed"], sources: sources)
_ = try transfer.accept(seal: seal)
_ = try transfer.verify(id: id)
let before = try archive.metadata(id: id)
try WorkoutOriginalExport.write(archive: archive, id: id, to: zip)
check(fm.fileExists(atPath: zip.path), "ZIP persists beyond coordinator accessor lifetime")
let verify = Process(); verify.executableURL = URL(fileURLWithPath: "/usr/bin/unzip"); verify.arguments = ["-tq", zip.path]
verify.standardOutput = Pipe(); verify.standardError = Pipe(); try verify.run(); verify.waitUntilExit()
check(verify.terminationStatus == 0, "system unzip validates ZIP directory and CRCs")
let extracted = root.appendingPathComponent("extracted")
let unzip = Process(); unzip.executableURL = URL(fileURLWithPath: "/usr/bin/ditto"); unzip.arguments = ["-x", "-k", zip.path, extracted.path]
try unzip.run(); unzip.waitUntilExit(); check(unzip.terminationStatus == 0, "system archive extractor accepts package")
let files = fm.enumerator(at: extracted, includingPropertiesForKeys: nil)!.allObjects.compactMap { $0 as? URL }
func file(_ name: String) -> URL { files.first { $0.lastPathComponent == name }! }
for name in ["metadata.json", "events.jsonl", "CYCtelemetry.csv", "manifest.json"] { check(files.contains { $0.lastPathComponent == name }, "portable package contains \(name)") }
let events = try String(contentsOf: file("events.jsonl"), encoding: .utf8).split(separator: "\n").map { try JSONDecoder().decode(WorkoutEvent.self, from: Data($0.utf8)) }
check(events.count == originals.count, "all canonical events exported")
for (actual, expected) in zipEvents(events, originals) {
  let encodedActual = try WorkoutCoding.encoder().encode(actual), encodedExpected = try WorkoutCoding.encoder().encode(expected)
  check(encodedActual == encodedExpected, "original nested payload and event identity retained exactly")
}
let csv = try String(contentsOf: file("CYCtelemetry.csv"), encoding: .utf8)
check(csv.contains("487.75,52.375"), "motor and battery data retained in convenience CSV")
check(csv.split(separator: "\n")[1].hasSuffix(",X12,20250604,5.3"), "controller identity columns carry their string values")
check(csv.split(separator: "\n").count == 2, "CSV includes only CYC measurements without synthesized health rows")
let after = try archive.metadata(id: id)
check(before.eventCount == after.eventCount && before.phase == after.phase, "export does not mutate the original workout")
try WorkoutOriginalExport.write(archive: archive, id: id, to: zip)
check(fm.fileExists(atPath: zip.path), "repeat export replaces completed ZIP safely")
print("Workout original export: \(assertions) assertions passed; real system ZIP round-trip")
func zipEvents(_ a: [WorkoutEvent], _ b: [WorkoutEvent]) -> [(WorkoutEvent, WorkoutEvent)] { Array(Swift.zip(a, b)) }
