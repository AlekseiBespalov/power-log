import Foundation

let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-lod-\(UUID().uuidString)/PowerLog")
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"))
let db = archive.store
let origin = Date(timeIntervalSince1970: 1_780_000_000)
let ride = try archive.create(startedAt: origin, indoor: true, watchEnabled: false)
var assertions = 0
func check(_ condition: @autoclosure () -> Bool, _ message: String) { precondition(condition(), message); assertions += 1 }
func event(_ time: Double, value: Double, id: String = UUID().uuidString.lowercased()) throws -> WorkoutEvent {
  try WorkoutEvent(workoutId: ride.id, kind: "telemetry", source: "cyc", timestamp: origin.addingTimeInterval(time), elapsedSeconds: time,
    payload: ["humanPowerW": .number(value), "cadenceRpm": .number(75)], eventId: id)
}
for offset in stride(from: 0, to: 32_000, by: 256) {
  let events = try (offset..<min(32_000, offset + 256)).compactMap { index -> WorkoutEvent? in
    let t = Double(index) / 8
    if t >= 1000 && t < 1008 { return nil }
    return try event(t, value: t == 1008 ? 10_000 : Double(index % 199))
  }
  _ = try db.appendBatch(events)
}
let reader = MonitorDataStore(root: root)
func q(_ start: Double = 0, _ end: Double = 3999.875, _ buckets: Int = 64) -> MonitorRequest {
  MonitorRequest(source: "workout", id: ride.id, startSeconds: start, endSeconds: end, metrics: ["humanPowerW"], buckets: buckets)
}
func points(_ request: MonitorRequest) throws -> [[String: Any]] {
  let result = try reader.readPlot(request)
  check(result["status"] as? String == "ok", "successful bounded read")
  return (result["series"] as! [String: [[String: Any]]])["humanPowerW"]!
}
let countBefore = try archive.revision(id: ride.id)
let first = try points(q())
check(first.count <= 4 * 66 + 2, "wide geometry is bounded by display width")
check(first.contains { $0["elapsedSeconds"] as? Double == 1008 && $0["startsSegment"] as? Bool == true }, "coarse geometry preserves the original gap and exact spike")
let initial = reader.projectionDiagnostics()
check(initial["pyramidBuilds"] == 1, "long history builds one hierarchy")
check(initial["pyramidOriginalPoints"] == 31_936, "originals scanned once to build the hierarchy")
for i in 0..<20 {
  let start = 20.03 + Double(i) * 0.37, end = 3800.91 + Double(i) * 0.31
  let result = try points(q(start, end, 63 + i % 3))
  let inside = result.filter { ($0["elapsedSeconds"] as! Double) >= start && ($0["elapsedSeconds"] as! Double) <= end }
  check(inside.map { $0["value"] as! Double }.max() == 10_000, "every moved window preserves the exact peak")
}
let after = reader.projectionDiagnostics()
check(after["pyramidOriginalPoints"] == initial["pyramidOriginalPoints"], "changing wide viewports never rescans the full-resolution history")
check((after["scannedPoints"] ?? 0) - (initial["scannedPoints"] ?? 0) <= 20 * 128, "only clipped eight-second edge tiles may read originals")
check((after["allocatedBytes"] ?? 0) <= (after["byteLimit"] ?? 0), "hierarchy shares the hard cache allocation budget")
let gap = try points(q(999.99, 1009.99, 1))
check(gap.contains { $0["elapsedSeconds"] as? Double == 1008 && $0["startsSegment"] as? Bool == true }, "partial edge tiles retain gap semantics")
let revisionAfter = try archive.revision(id: ride.id)
check(revisionAfter == countBefore, "chart reads do not alter recordings")
let constrained = MonitorDataStore(root: root, projectionCacheBytes: 512 * 1024)
_ = try constrained.readPlot(q())
let constrainedBefore = constrained.projectionDiagnostics()
_ = try constrained.rangeStats(q())
_ = try constrained.readPlot(q(10.1, 3900.2))
let constrainedAfter = constrained.projectionDiagnostics()
check(constrainedAfter["pyramidOriginalPoints"] == constrainedBefore["pyramidOriginalPoints"], "exact statistics never evict navigation summaries under memory pressure")
check(constrainedAfter["pyramidEntries"] == 1 && constrainedAfter["allocatedBytes"]! <= 512 * 1024, "exact reads stream within the shared budget")
let beforeAppend = reader.projectionDiagnostics()["pyramidOriginalPoints"]!
_ = try db.appendBatch((0..<8).map { try event(4000 + Double($0) / 8, value: 77) })
_ = try points(q(0, 4000.875, 62))
check(reader.projectionDiagnostics()["pyramidOriginalPoints"]! - beforeAppend == 8, "live append extends only the new tail")
try archive.append(event(2222.0125, value: -1000))
let corrected = try points(q(0, 4000.875, 61))
check(corrected.contains { $0["value"] as? Double == -1000 && $0["elapsedSeconds"] as? Double == 2222.0125 }, "late submillisecond extrema rebuild affected hierarchy")
check(reader.projectionDiagnostics()["pyramidInvalidations"] == 1, "late data invalidates the hierarchy")
var inspect = q(); inspect.seconds = 2222.0125
let exact = try reader.inspectAt(inspect)
check(((exact["points"] as? [String: [String: Any]])?["humanPowerW"])?["value"] as? Double == -1000, "cursor still reads original values")
_ = try archive.finish(id: ride.id, endedAt: origin.addingTimeInterval(4001))
_ = try points(q(0, 4000.875, 60))
let reopened = MonitorDataStore(root: root)
let restored = try reopened.readPlot(q(0.02, 3999.8, 63))
check(restored["status"] as? String == "ok" && reopened.projectionDiagnostics()["pyramidDiskHits"] == 1, "reopened completed rides load persisted summaries")
check((reopened.projectionDiagnostics()["pyramidOriginalPoints"] ?? 0) == 0, "reopening the hierarchy does not rescan original history")
try db.transaction { sql in
  let row = try sql.rows("SELECT key,value FROM derived_cache WHERE key LIKE 'lod1|%'", limit: 1).first!
  var bytes = row.data("value")!; bytes[100] ^= 1
  try sql.execute("UPDATE derived_cache SET value=? WHERE key=?", [.blob(bytes), .text(row.string("key")!)])
}
let repairing = MonitorDataStore(root: root)
let recovered = try repairing.readPlot(q(0.04, 3999.9, 63))
check(recovered["status"] as? String == "ok" && repairing.projectionDiagnostics()["pyramidBuilds"] == 1, "damaged derived summaries rebuild from originals")
check((repairing.projectionDiagnostics()["pyramidDiskHits"] ?? 0) == 0, "checksum rejects corrupted cached measurements")
print("Native level-of-detail: \(assertions) assertions passed; exact extrema, gaps, late arrivals, append-only updates and bounded wide-window work")
