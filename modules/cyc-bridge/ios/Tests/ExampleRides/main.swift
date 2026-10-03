import Foundation

func check(_ value: Bool, _ message: String) { if !value { fatalError(message) } }
let root = FileManager.default.temporaryDirectory.appendingPathComponent(
  "power-log-example-rides-\(UUID().uuidString)/PowerLog")
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"))
let reader = MonitorDataStore(root: root)
let written = try WorkoutExampleRides.write(archive: archive, durations: [90, 45])
check(written.count == 2, "one ride per duration")
check(
  try WorkoutExampleRides.write(archive: archive, durations: [90, 45]).isEmpty,
  "existing example rides are not written twice")
let metadata = try archive.metadata(id: WorkoutExampleRides.id(0))
check(
  metadata.example == true && metadata.phase == "completed" && metadata.endedAt != nil,
  "example rides are completed and flagged")
check(metadata.dictionary["example"] as? Bool == true, "the bridge dictionary carries the example flag")
check(
  try archive.metadata(id: WorkoutExampleRides.id(1)).dictionary["example"] as? Bool == true,
  "every example ride is flagged")
let transfer = WorkoutTransferJournal(archive: archive)
check(try transfer.verify(id: metadata.id), "example seals verify")
check(
  try transfer.currentSeal(id: metadata.id)?.sources.allSatisfy { $0.provenance == WorkoutExampleRides.provenance }
    == true, "seals carry generated provenance")
let representations = try archive.store.read { db in
  try db.rows(
    "SELECT o.representation AS representation,count(*) AS rows,sum(h.heartRateBpm IS NOT NULL) AS heart,sum(h.activeEnergyKcal IS NOT NULL AND h.basalEnergyKcal IS NOT NULL AND h.distanceMeters IS NOT NULL) AS totals FROM collection_memberships m JOIN observations o ON o.id=m.observation_id JOIN health_samples h ON h.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='health' GROUP BY o.representation ORDER BY o.representation",
    [.text(metadata.id)], limit: 8)
}
check(
  representations.map { $0.string("representation") } == ["builderMostRecent", "cumulativeWorkoutTotal"],
  "example Health rows use only the declared builder representations")
let latest = representations[0]
let cumulative = representations[1]
check(
  latest.int("heart") == latest.int("rows") && latest.int("totals") == 0 && latest.int("rows") == 91,
  "heart rate is a most-recent builder value each second")
check(
  cumulative.int("totals") == cumulative.int("rows") && cumulative.int("heart") == 0 && cumulative.int("rows") == 91,
  "energy and distance are cumulative workout totals each second")
let summary = try WorkoutAnalysis.summarize(archive: archive, id: metadata.id)
check(summary.elapsedSeconds >= 89, "example rides summarize their whole duration")
let plot = try reader.readPlot(
  MonitorRequest(source: "workout", id: metadata.id, metrics: ["humanPowerW", "heartRateBpm"], buckets: 64))
check(plot["status"] as? String == "ok", "example rides plot")
print("Example rides: written once, flagged, sealed and readable")
