import Foundation
import PowerLogCore

func check(_ condition: Bool, _ message: String) { precondition(condition, message) }
let directory = FileManager.default.temporaryDirectory.appendingPathComponent("power-log-preferences-\(UUID())")
defer { try? FileManager.default.removeItem(at: directory) }
let defaults = WorkoutCapturePreferences(sampleHz: 2, distanceSource: "auto")
check(try WorkoutPreferences.read(directory: directory) == defaults, "missing settings use the shared defaults")
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
let codec = Preferences.shared
let original = try JSONSerialization.jsonObject(with: Data(codec.encode(preferences: codec.defaults()).utf8))
func save(_ revision: UInt64, sampleHz: Int, distanceSource: String, batterySource: String = "controller") throws {
  var contents = original as! [String: Any]
  contents["sampleHz"] = sampleHz
  contents["distanceSource"] = distanceSource
  contents["batteryConsumptionSource"] = batterySource
  let file = directory.appendingPathComponent(String(format: "monitor-%016llu.json", revision))
  try JSONSerialization.data(withJSONObject: contents).write(to: file)
}
for (index, rate) in [2, 4, 8].enumerated() {
  for (offset, distance) in ["controller", "gps:phone"].enumerated() {
    let battery = offset == 0 ? "controller" : "calculated"
    try save(UInt64(index * 2 + offset + 1), sampleHz: rate, distanceSource: distance, batterySource: battery)
    let expected = WorkoutCapturePreferences(
      sampleHz: Double(rate), distanceSource: distance, batteryConsumptionSource: battery)
    check(try WorkoutPreferences.read(directory: directory) == expected, "native Start reads the latest rate/distance")
  }
}
let frozen = try WorkoutPreferences.read(directory: directory)
try save(7, sampleHz: 2, distanceSource: "controller")
check(
  frozen == WorkoutCapturePreferences(sampleHz: 8, distanceSource: "gps:phone", batteryConsumptionSource: "calculated"),
  "admitted settings remain frozen")
check(try WorkoutPreferences.read(directory: directory).sampleHz == 2, "the next native Start sees committed edits")
try Data("{broken".utf8).write(to: directory.appendingPathComponent("monitor-0000000000000008.json"))
try save(9, sampleHz: 16, distanceSource: "gps:phone")
var malformed = original as! [String: Any]
malformed["distanceSource"] = 7
try JSONSerialization.data(withJSONObject: malformed).write(
  to: directory.appendingPathComponent("monitor-0000000000000010.json"))
check(
  try WorkoutPreferences.read(directory: directory)
    == WorkoutCapturePreferences(sampleHz: 2, distanceSource: "controller"),
  "corrupt revisions recover the same previous valid revision as the UI")
try Data(repeating: 32, count: 64 * 1024 + 1).write(
  to: directory.appendingPathComponent("monitor-0000000000000011.json"))
try Data("{broken".utf8).write(to: directory.appendingPathComponent("monitor-0000000000000012.tmp"))
try save(9_007_199_254_740_992, sampleHz: 8, distanceSource: "gps:phone")
check(
  try WorkoutPreferences.read(directory: directory).sampleHz == 2, "uncommitted/oversized/unsafe revisions are skipped")
for revision in 1...7 {
  try FileManager.default.removeItem(
    at: directory.appendingPathComponent(String(format: "monitor-%016d.json", revision)))
}
do {
  _ = try WorkoutPreferences.read(directory: directory)
  fatalError("unreadable committed settings must block Start")
} catch {
  check(
    error.localizedDescription.contains("Open Settings on the phone"), "an unreadable store gives an actionable error")
}
check(try FileManager.default.contentsOfDirectory(atPath: directory.path).count == 6, "reading never rewrites settings")
try FileManager.default.removeItem(at: directory)
try Data("not a directory".utf8).write(to: directory)
do {
  _ = try WorkoutPreferences.read(directory: directory)
  fatalError("invalid settings directory must block Start")
} catch {}
print("Native committed recording preferences passed")
