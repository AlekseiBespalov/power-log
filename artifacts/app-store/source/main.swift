import Foundation
import PowerLogCore

func verifiedHealthOrigins(archive: WorkoutArchive, id: String) throws -> Int64 {
  var cursor: Int64 = 0
  var count: Int64 = 0
  while true {
    let page = try archive.pageEvents(id: id, afterSequence: cursor, producer: "watch")
    guard !page.isEmpty else { return count }
    for row in page where row.event.kind == "health" {
      let event = row.event
      guard let encoded = event.payload["origin"],
        event.payload["provider"] == .string("appleHealth"),
        event.payload["dataOrigin"] == .string(WorkoutExampleRides.healthDataOrigin)
      else { throw WorkoutDataError.invalid("Example Health event is missing its Watch origin: \(id)") }
      let origin = try HealthCodec.shared.decodeOrigin(
        text: String(decoding: WorkoutCoding.encoder().encode(encoded), as: UTF8.self))
      guard origin.provider == .applehealth, origin.dataOrigin == WorkoutExampleRides.healthDataOrigin,
        origin.providerObservationId == nil, origin.originRevision == nil,
        origin.producer == .watch, origin.sequence == row.sequence, origin.originEventId == event.eventId
      else { throw WorkoutDataError.invalid("Example Health origin does not match its Watch observation: \(id)") }
      count += 1
    }
    cursor = page.last!.sequence
  }
}

func verifiedReports(archive: WorkoutArchive, reports: [[String: Any]]) throws -> [[String: Any]] {
  try reports.map { report in
    guard let id = report["id"] as? String else { throw WorkoutDataError.invalid("Missing example ride ID") }
    let metadata = try archive.metadata(id: id)
    let completion = try archive.completion(id: id)
    let summary = try WorkoutAnalysis.summarize(archive: archive, id: id)
    let originCount = try verifiedHealthOrigins(archive: archive, id: id)
    guard originCount == summary.healthCount else {
      throw WorkoutDataError.invalid("Unverified example Health origins")
    }
    guard summary.averageHeartRateBpm != nil, summary.heartRateCoveredSeconds > 0,
      summary.activeEnergyKcal != nil, summary.basalEnergyKcal != nil
    else { throw WorkoutDataError.invalid("Example Health observations must contribute heart rate and energy: \(id)") }
    guard metadata.example == true, metadata.watchEnabled, !metadata.saveToHealth,
      completion.phase == "completed", completion.endedAt != nil,
      completion.healthOutcome == .notrequested, metadata.healthKitUUID == nil, metadata.healthReason == nil,
      RideCompletion.shared.exportReady(snapshot: completion, selectedRideId: id)
    else { throw WorkoutDataError.invalid("Example ride must be completed with Health not requested: \(id)") }
    let reader = MonitorDataStore(root: archive.rootURL.deletingLastPathComponent())
    let plot = try reader.readPlot(
      MonitorRequest(source: "workout", id: id, metrics: ["humanPowerW", "heartRateBpm"], buckets: 64))
    guard plot["status"] as? String == "ok" else { throw WorkoutDataError.invalid("Example ride must plot: \(id)") }
    let exporter = ExportSource { archive }
    defer { exporter.closeAll() }
    let export = try exporter.open(["rideId": id, "kind": "zip"])
    guard export["session"] is String else {
      throw WorkoutDataError.invalid("Example ride must open for export: \(id)")
    }
    for projection in ["telemetry", "healthZip", "gps"] {
      let page = try exporter.page(["session": export["session"]!, "projection": projection, "after": NSNull()])
      guard (page["rows"] as? Int ?? 0) > 0 else {
        throw WorkoutDataError.invalid("Example export must contain \(projection): \(id)")
      }
    }
    let projected = metadata.dictionary.merging(try archive.healthProjection(id: id)) { _, new in new }
    return report.merging(["metadata": projected, "exportReady": true, "verifiedHealthOriginCount": originCount]) {
      _, new in new
    }
  }
}

do {
  let arguments = Array(CommandLine.arguments.dropFirst())
  guard arguments.count == 1 || (arguments.count == 2 && arguments[1] == "--smoke") else {
    throw WorkoutDataError.invalid("Usage: examples /new/output/PowerLog [--smoke]")
  }
  let root = URL(fileURLWithPath: arguments[0])
  guard !FileManager.default.fileExists(atPath: root.path) else { throw WorkoutDataError.invalid("Output must be new") }
  let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"))
  let reports = try verifiedReports(
    archive: archive,
    reports: WorkoutExampleRides.write(
      archive: archive, durations: arguments.count == 2 ? [2, 3, 4] : WorkoutExampleRides.durations))
  try JSONSerialization.data(withJSONObject: reports, options: [.prettyPrinted, .sortedKeys]).write(
    to: root.deletingLastPathComponent().appendingPathComponent("example-values.json"))
  print(
    "Verified \(reports.count) completed fictional phone rides with Watch observations, Health saving off and export ready."
  )
} catch {
  FileHandle.standardError.write(Data("Example generation failed: \(error)\n".utf8))
  exit(1)
}
