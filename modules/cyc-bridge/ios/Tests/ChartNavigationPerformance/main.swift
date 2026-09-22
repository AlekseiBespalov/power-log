import Foundation
import Darwin
// Use only a disposable synthetic benchmark store. Never use a device recording store.
guard CommandLine.arguments.count == 2 else { fatalError("Pass a disposable synthetic PowerLog root") }
let root = URL(fileURLWithPath: CommandLine.arguments[1])
let db = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let row = try db.read { try $0.rows("SELECT id,event_count FROM collections WHERE kind='workout' ORDER BY event_count DESC LIMIT 1", limit: 1).first! }
_ = try db.transaction { try $0.execute("DELETE FROM derived_cache") }
let reader = MonitorDataStore(root: root)
var q = MonitorRequest(source: "workout", id: row.string("id")!, metrics: ["humanPowerW", "motorInputPowerW", "cadenceRpm", "heartRateBpm"], buckets: 360)
let domain = try reader.describeSource(q)["domain"] as! [String: Double]
let end = domain["end"]!
var points = 0
func measure(_ body: () throws -> Void) rethrows -> Double {
  let start = ProcessInfo.processInfo.systemUptime
  try autoreleasepool(invoking: body)
  return (ProcessInfo.processInfo.systemUptime - start) * 1000
}
func plot() throws {
  let result = try reader.readPlot(q)
  precondition(result["status"] as? String == "ok")
  points = (result["series"] as! [String: [[String: Any]]]).values.reduce(0) { $0 + $1.count }
  precondition(points <= MonitorDataStore.maximumPlotPoints)
}
let cold = try measure { try plot() }
let initial = reader.projectionDiagnostics()
var pans: [Double] = []
for i in 0..<20 {
  q.startSeconds = 0.071 + Double(i) * 0.83
  q.endSeconds = end - 4.013 + Double(i) * 0.031
  pans.append(try measure { try plot() })
}
var zooms: [Double] = []
for i in 0..<12 {
  let span = end * (0.90 - Double(i) * 0.03)
  q.startSeconds = (end - span) / 2; q.endSeconds = q.startSeconds! + span
  zooms.append(try measure { try plot() })
}
let after = reader.projectionDiagnostics()
q.startSeconds = 0; q.endSeconds = end
let stats = try measure { _ = try reader.rangeStats(q) }
var inspect = q; inspect.seconds = end / 2 + 0.01
let cursor = try measure { _ = try reader.inspectAt(inspect) }
func distribution(_ values: [Double]) -> [String: Double] {
  let sorted = values.sorted()
  return ["medianMs": sorted[sorted.count / 2], "p95Ms": sorted[min(sorted.count - 1, Int(Double(sorted.count) * 0.95))], "maxMs": sorted.last!]
}
var usage = rusage(); getrusage(RUSAGE_SELF, &usage)
let result: [String: Any] = ["provenance": "Optimized macOS native; synthetic eight-hour data; not phone frame-rate evidence", "collectionEvents": row.int("event_count")!, "metrics": q.metrics,
  "coldPlotMs": cold, "changedWideWindow": distribution(pans), "changedWideZoom": distribution(zooms), "exactFullStatsMs": stats, "exactCursorMs": cursor,
  "lastPlotPoints": points, "beforeNavigation": initial, "afterNavigation": after, "processPeakResidentBytes": usage.ru_maxrss]
print(String(data: try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys]), encoding: .utf8)!)
