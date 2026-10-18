import Foundation
import PowerLogCore

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
let representations = try archive.store.read { db in
  try db.rows(
    "SELECT o.representation AS representation,count(*) AS rows,sum(h.heartRateBpm IS NOT NULL) AS heart,sum((h.activeEnergyKcal IS NOT NULL)+(h.basalEnergyKcal IS NOT NULL)) AS totals FROM collection_memberships m JOIN observations o ON o.id=m.observation_id JOIN health_samples h ON h.observation_id=m.observation_id WHERE m.collection_id=? AND m.kind='health' GROUP BY o.representation ORDER BY o.representation",
    [.text(metadata.id)], limit: 8)
}
check(
  representations.map { $0.string("representation") } == ["builderMostRecent", "cumulativeWorkoutTotal"],
  "example Health rows use only the declared builder representations")
check(
  try archive.store.read { db in
    try db.scalarInt("SELECT count(*) FROM health_samples WHERE distanceMeters IS NOT NULL") == 0
  }, "example rides contain no own cycling distance totals")
let latest = representations[0]
let cumulative = representations[1]
check(
  latest.int("heart") == latest.int("rows") && latest.int("totals") == 0 && latest.int("rows") == 91,
  "heart rate is a most-recent builder value each second")
check(
  cumulative.int("totals") == cumulative.int("rows") && cumulative.int("heart") == 0 && cumulative.int("rows") == 182,
  "active and basal energy are cumulative workout totals each second")
for row in try archive.pageEvents(id: metadata.id, limit: 64, producer: "watch") where row.event.kind == "health" {
  let event = row.event
  let payload = event.payload
  let input = try WorkoutHealthObservation.input(
    start: WorkoutCoding.date(payload["sampleStart"]!.string!),
    end: WorkoutCoding.date(payload["sampleEnd"]!.string!),
    metric: payload["healthKitIdentifier"]!.string!, unit: payload["unit"]!.string!, value: payload["value"]!.number!)
  check(
    try event.eventId
      == WorkoutHealthObservation.builderID(
        id: metadata.id, input: input, representation: payload["representation"]!.string!),
    "example Health events retain Watch builder identities")
  check(payload["exampleData"] == .bool(true), "example Health events retain generated data flags")
  check(payload["provider"] == .string("appleHealth"), "example Health events retain the Watch provider")
  check(
    payload["dataOrigin"] == .string("app.powerlog.mobile.watchkitapp"),
    "example Health events retain the Watch app origin")
  let origin = try HealthCodec.shared.decodeOrigin(
    text: String(decoding: WorkoutCoding.encoder().encode(payload["origin"]!), as: UTF8.self))
  check(
    origin.provider == .applehealth && origin.dataOrigin == "app.powerlog.mobile.watchkitapp"
      && origin.providerObservationId == nil, "example Health origins match Watch builder observations")
  check(
    origin.originEventId == event.eventId && origin.producer == .watch && origin.sequence == row.sequence,
    "example Health origins retain event identity and producer sequence")

}
let summary = try WorkoutAnalysis.summarize(archive: archive, id: metadata.id)
check(summary.elapsedSeconds >= 89, "example rides summarize their whole duration")
check(summary.averageHeartRateBpm != nil && summary.heartRateCoveredSeconds == 90, "example heart rate covers the ride")
check(summary.activeEnergyKcal != nil && summary.basalEnergyKcal != nil, "example Watch energy contributes to summary")
let plot = try reader.readPlot(
  MonitorRequest(source: "workout", id: metadata.id, metrics: ["humanPowerW", "heartRateBpm"], buckets: 64))
check(plot["status"] as? String == "ok", "example rides plot")
print("Example rides: written once, flagged and readable")
