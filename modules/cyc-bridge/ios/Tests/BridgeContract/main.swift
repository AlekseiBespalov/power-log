import Foundation
import CoreFoundation

var assertions = 0
func check(_ value: Bool, _ message: String) {
  assertions += 1
  if !value { fatalError(message) }
}
func compare(_ actual: Any, _ expected: Any, _ path: String) {
  if let expected = expected as? [String: Any] {
    guard let actual = actual as? [String: Any] else { fatalError("Object required at " + path) }
    check(Set(actual.keys) == Set(expected.keys), "Key set differs at " + path)
    for key in expected.keys.sorted() { compare(actual[key]!, expected[key]!, path + "." + key) }
  } else if let expected = expected as? [Any] {
    guard let actual = actual as? [Any] else { fatalError("Array required at " + path) }
    check(actual.count == expected.count, "Array length differs at " + path)
    for index in expected.indices { compare(actual[index], expected[index], path + "[\(index)]") }
  } else if let expected = expected as? NSNumber {
    guard let actual = actual as? NSNumber else { fatalError("Number or boolean required at " + path) }
    let boolean = CFGetTypeID(expected) == CFBooleanGetTypeID()
    check((CFGetTypeID(actual) == CFBooleanGetTypeID()) == boolean, "Boolean/number differs at " + path)
    check(actual.doubleValue == expected.doubleValue, "Numeric value differs at " + path)
  } else if let expected = expected as? String {
    check(actual as? String == expected, "String differs at " + path)
  } else {
    check(expected is NSNull && actual is NSNull, "Null differs at " + path)
  }
}
func roundTrip(_ map: [String: Any]) throws -> Any {
  try JSONSerialization.jsonObject(with: JSONSerialization.data(withJSONObject: map))
}
func fixture(_ name: String) throws -> [String: Any] {
  try JSONSerialization.jsonObject(
    with: Data(contentsOf: URL(fileURLWithPath: "tests/fixtures/contract/" + name + ".json"))) as! [String: Any]
}
let rideID = "3f6c1f0e-6f2d-4c43-9b7e-2a4d8f1b9c21"
let iphone26 = WorkoutCapabilities(iOSMajorVersion: 26, healthAvailable: true)
let iphone17 = WorkoutCapabilities(iOSMajorVersion: 17, healthAvailable: true)
let off = RideStreamsSnapshot(
  cyc: RideStreamSnapshot(status: "off"), heartRate: RideStreamSnapshot(status: "off"),
  gps: RideGPSSnapshot(status: "off", source: .phone, accuracyMeters: nil))
func snapshot(
  capabilities: WorkoutCapabilities = iphone26, id: String? = nil, phase: String = "idle",
  pendingAction: String? = nil, timerSeconds: Double = 0, historyRevision: String = "",
  lastDeletedWorkoutId: String? = nil, collectionRevision: Int64? = nil, sealRevision: Int64? = nil,
  verifiedSealRevision: Int64? = nil, finalizationState: String? = nil, useWatch: Bool = false,
  saveToHealth: Bool = false, recordGPS: Bool = false, recoveryState: String = "idle",
  recoveryMessage: String? = nil, healthKitState: String = "notRequested", installed: Bool = false,
  streams: RideStreamsSnapshot = off, error: String? = nil
) -> RideSnapshot {
  RideSnapshot(
    supported: true, capabilities: capabilities, id: id, phase: phase, pendingAction: pendingAction,
    timerSeconds: timerSeconds, historyRevision: historyRevision, lastDeletedWorkoutId: lastDeletedWorkoutId,
    collectionRevision: collectionRevision, sealRevision: sealRevision, verifiedSealRevision: verifiedSealRevision,
    finalizationState: finalizationState, indoor: false, useWatch: useWatch, saveToHealth: saveToHealth,
    recordGPS: recordGPS, recoveryState: recoveryState, recoveryMessage: recoveryMessage,
    healthKitState: healthKitState,
    watch: RideWatchSnapshot(installed: installed), streams: streams, error: error)
}
let snapshots: [String: RideSnapshot] = [
  "idle": snapshot(),
  "phone ride running with Health and GPS": snapshot(
    id: rideID, phase: "running", timerSeconds: 125.5, collectionRevision: 42, finalizationState: "pending",
    saveToHealth: true, recordGPS: true, healthKitState: "pending",
    streams: RideStreamsSnapshot(
      cyc: RideStreamSnapshot(status: "receiving"), heartRate: RideStreamSnapshot(status: "waiting"),
      gps: RideGPSSnapshot(status: "receiving", source: .phone, accuracyMeters: 4.25))),
  "Watch ride finishing after Health saved": snapshot(
    capabilities: iphone17, id: rideID, phase: "finishing", pendingAction: "stop", timerSeconds: 3600.75,
    collectionRevision: 918, sealRevision: 3, verifiedSealRevision: 2, finalizationState: "pending",
    useWatch: true, saveToHealth: true, recordGPS: true, healthKitState: "saved", installed: true,
    streams: RideStreamsSnapshot(
      cyc: RideStreamSnapshot(status: "stale"), heartRate: RideStreamSnapshot(status: "receiving"),
      gps: RideGPSSnapshot(status: "weak", source: .watch, accuracyMeters: 62.5))),
  "unresolved recovery with an error": snapshot(
    id: rideID, phase: "recoverable", timerSeconds: 61, collectionRevision: 7, finalizationState: "pending",
    saveToHealth: true, recoveryState: "unresolved",
    recoveryMessage: "Stop requested from the original owner. No recording completion has been confirmed.",
    healthKitState: "notSaved", error: "Bluetooth is turned off."),
  "deletion signal": snapshot(
    historyRevision: "5b1d7c9e-0a4f-4e0b-8a61-7d2c3e9f4a10", lastDeletedWorkoutId: rideID),
  "largest safe revision": snapshot(
    id: rideID, phase: "completed", timerSeconds: 0.5,
    collectionRevision: PowerLogStorageLimits.maximumRevision, sealRevision: PowerLogStorageLimits.maximumRevision,
    verifiedSealRevision: PowerLogStorageLimits.maximumRevision, finalizationState: "complete"),
]
let snapshotCases = try (fixture("ride-snapshots")["cases"] as! [[String: Any]]).filter {
  $0["platform"] as? String == "ios"
}
check(Set(snapshotCases.map { $0["name"] as! String }) == Set(snapshots.keys), "every iOS case has a typed counterpart")
check(snapshotCases.count == snapshots.count, "snapshot case names are unique")
for test in snapshotCases {
  let name = test["name"] as! String
  compare(try roundTrip(snapshots[name]!.wireMap), test["wire"]!, name)
}
let largest = try roundTrip(snapshots["largest safe revision"]!.wireMap) as! [String: Any]
for key in ["collectionRevision", "sealRevision", "verifiedSealRevision"] {
  check((largest[key] as! NSNumber).int64Value == 9_007_199_254_740_991, "largest revision remains exact: " + key)
}

struct OptionInput: Decodable {
  let indoor: Bool
  let useWatch: Bool?
  let saveToHealth: Bool?
  let recordGPS: Bool?
  let sampleHz: Double?
}
let optionsFixture = try fixture("ride-options")
let capabilityFixtures = optionsFixture["capabilities"] as! [String: Any]
let capabilities = [
  "iphone26": iphone26, "iphone17": iphone17,
  "iphoneWithoutHealth": WorkoutCapabilities(iOSMajorVersion: 26, healthAvailable: false),
]
check(
  Set(capabilityFixtures.keys.filter { $0.hasPrefix("iphone") }) == Set(capabilities.keys),
  "every iPhone capability set has a native counterpart")
for (name, value) in capabilities {
  let wire = try roundTrip(snapshot(capabilities: value).wireMap) as! [String: Any]
  compare(wire["capabilities"]!, capabilityFixtures[name]!, name)
}
let optionCases = (optionsFixture["cases"] as! [[String: Any]]).filter {
  ($0["capabilities"] as! String).hasPrefix("iphone")
}
for test in optionCases {
  let input = try JSONDecoder().decode(
    OptionInput.self, from: JSONSerialization.data(withJSONObject: test["input"]!))
  let options = WorkoutRecordingPolicy.effectiveOptions(
    capabilities: capabilities[test["capabilities"] as! String]!, indoor: input.indoor,
    useWatch: input.useWatch ?? false, saveToHealth: input.saveToHealth ?? true, recordGPS: input.recordGPS)
  let wire: [String: Any] = [
    "sampleHz": try WorkoutRecordingPolicy.sampleHz(input.sampleHz ?? 2), "indoor": input.indoor,
    "useWatch": options.useWatch, "saveToHealth": options.saveToHealth, "recordGPS": options.recordGPS,
  ]
  compare(try roundTrip(wire), test["expected"]!, test["name"] as! String)
}
for rate in [2.0, 4.0, 8.0] {
  check(try WorkoutRecordingPolicy.sampleHz(rate) == rate, "supported sample rate remains exact")
}
let rejectedRates =
  (optionsFixture["rejectedSampleRates"] as! [NSNumber]).map(\.doubleValue)
  + [Double.nan, .infinity, -.infinity]
for rate in rejectedRates {
  do {
    _ = try WorkoutRecordingPolicy.sampleHz(rate)
    fatalError("Invalid sample rate admitted: \(rate)")
  } catch {
    check(error is WorkoutDataError, "invalid sample rates use the input error path")
  }
}
print(
  "Bridge contract passed: \(snapshotCases.count) snapshots, \(optionCases.count) option cases, \(rejectedRates.count) rejected rates, \(assertions) assertions"
)
