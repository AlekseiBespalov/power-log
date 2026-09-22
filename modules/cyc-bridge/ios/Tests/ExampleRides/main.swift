import Foundation

func check(_ value: Bool, _ message: String) { if !value { fatalError(message) } }
let root = FileManager.default.temporaryDirectory.appendingPathComponent("power-log-example-rides-\(UUID().uuidString)/PowerLog")
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"))
let reader = MonitorDataStore(root: root)
let written = try WorkoutExampleRides.write(archive: archive, durations: [90, 45])
check(written.count == 2, "one ride per duration")
check(try WorkoutExampleRides.write(archive: archive, durations: [90, 45]).isEmpty, "existing example rides are not written twice")
let metadata = try archive.metadata(id: WorkoutExampleRides.id(0))
check(metadata.example == true && metadata.phase == "completed" && metadata.endedAt != nil, "example rides are completed and flagged")
check(metadata.dictionary["example"] as? Bool == true, "the bridge dictionary carries the example flag")
check(try archive.metadata(id: WorkoutExampleRides.id(1)).dictionary["example"] as? Bool == true, "every example ride is flagged")
let transfer = WorkoutTransferJournal(archive: archive)
check(try transfer.verify(id: metadata.id), "example seals verify")
check(try transfer.currentSeal(id: metadata.id)?.sources.allSatisfy { $0.provenance == WorkoutExampleRides.provenance } == true, "seals carry generated provenance")
let summary = try WorkoutFIT.summarize(archive: archive, id: metadata.id)
check(summary.elapsedSeconds >= 89, "example rides summarize their whole duration")
let plot = try reader.readPlot(MonitorRequest(source: "workout", id: metadata.id, metrics: ["humanPowerW", "heartRateBpm"], buckets: 64))
check(plot["status"] as? String == "ok", "example rides plot")
print("Example rides: written once, flagged, sealed and readable")
