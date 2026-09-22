import Foundation
import Darwin

// Reuse a disposable synthetic canonical fixture; never point this at personal recordings.
// This measures reads separately from the 1/4/8-hour production-writer matrix fixture creation.
guard CommandLine.arguments.count == 2 else { fatalError("Usage: projection-performance /absolute/path/to/disposable/PowerLog") }
let root = URL(fileURLWithPath: CommandLine.arguments[1])
let db = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let row = try db.read { try $0.rows("SELECT id,event_count FROM collections WHERE kind='workout' ORDER BY event_count DESC LIMIT 1", limit: 1).first! }
let id = row.string("id")!
// Use current-schema synthetic fixtures produced by benchmark:native; discard derived geometry only.
try db.transaction { try $0.execute("DELETE FROM derived_cache") }
let reader = MonitorDataStore(root: root)
var q = MonitorRequest(source: "workout", id: id, metrics: ["humanPowerW", "cadenceRpm", "heartRateBpm", "speedMps"], buckets: 256)
let end = ((try reader.describeSource(q))["domain"] as! [String: Double])["end"]!
func milliseconds(_ body: () throws -> Void) rethrows -> Double {
  let start = ProcessInfo.processInfo.systemUptime; try autoreleasepool(invoking: body); return (ProcessInfo.processInfo.systemUptime - start) * 1_000
}
var full: [String: Any] = [:]
let cold = try milliseconds { full = try reader.readPlot(q); precondition(full["status"] as? String == "ok") }
var encodedPlot = Data()
let conversion = try milliseconds { encodedPlot = try JSONSerialization.data(withJSONObject: full) }
let warm = try milliseconds { _ = try reader.readPlot(q) }
q.buckets = 257
let resized = try milliseconds { _ = try reader.readPlot(q) }
var pans: [Double] = []
let beforePans = reader.projectionDiagnostics()
for index in 0..<20 {
  q.startSeconds = end / 2 + Double(index); q.endSeconds = q.startSeconds! + 120
  pans.append(try milliseconds { _ = try reader.readPlot(q) })
}
let afterPans = reader.projectionDiagnostics()
q.startSeconds = 0; q.endSeconds = end
let fullStats = try milliseconds { _ = try reader.rangeStats(q) }
q.startSeconds = end / 2; q.endSeconds = q.startSeconds! + 120
let narrowStats = try milliseconds { _ = try reader.rangeStats(q) }
let afterStats = reader.projectionDiagnostics()
precondition(afterStats["scannedPoints"] == afterPans["scannedPoints"], "Statistics reread cached canonical projections")
precondition(beforePans["scannedPoints"] == afterPans["scannedPoints"], "Panning reread canonical observations")
precondition((afterPans["peakAllocatedBytes"] ?? 0) <= MonitorDataStore.maximumProjectionBytes)
var usage = rusage(); getrusage(RUSAGE_SELF, &usage)
let result: [String: Any] = ["provenance":"macOS optimized native, existing disposable synthetic SQL fixture; not device evidence", "collectionEvents":row.int("event_count")!,
  "coldFourMetricPlotMs":cold,"persistedExactPlotMs":warm,"resizedFullProjectionMs":resized,"twoMinutePanP95Ms":pans.sorted()[18],
  "fullDomainStatsMs":fullStats, "twoMinuteStatsMs":narrowStats, "geometryPoints":(full["series"] as! [String: [[String: Any]]]).values.reduce(0) { $0 + $1.count }, "projectionDiagnostics":afterStats,
  "plotJSONConversionMs":conversion,"plotJSONBytes":encodedPlot.count,"processPeakResidentBytes":usage.ru_maxrss]
print(String(data: try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys]), encoding: .utf8)!)
