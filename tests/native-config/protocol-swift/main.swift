import Foundation
import PowerLogCore

var checks = 0
func check(_ value: Bool, file: StaticString = #file, line: UInt = #line) {
  precondition(value, file: file, line: line)
  checks += 1
}
func rejects(_ work: () throws -> Void) {
  do {
    try work()
    fatalError("Invalid original was admitted")
  } catch {
    check((error as NSError).domain == "KotlinException")
  }
}
func bytes(_ value: String) -> KotlinByteArray {
  let utf8 = Array(value.utf8)
  let result = KotlinByteArray(size: Int32(utf8.count))
  for (index, value) in utf8.enumerated() { result.set(index: Int32(index), value: Int8(bitPattern: value)) }
  return result
}
func text(_ value: KotlinByteArray) -> String {
  String(decoding: (0..<value.size).map { UInt8(bitPattern: value.get(index: $0)) }, as: UTF8.self)
}

let timestamp = try Timestamps.shared.parse(original: "2026-09-09T00:00:00.123456789Z")
check(timestamp.original == "2026-09-09T00:00:00.123456789Z")
check(timestamp.comparisonUs == 1_788_912_000_123_456)
rejects { _ = try Timestamps.shared.parse(original: "2026-09-09T00:00:00.1234567890Z") }
rejects { _ = try CanonicalNumber.shared.encode(value: .infinity) }
check(try CanonicalNumber.shared.encode(value: -0.0) == "0")
check(try text(WatchProtocol.shared.canonicalizeJson(bytes: bytes("{\"b\":2,\"a\":1}"))) == "{\"a\":1,\"b\":2}")

let ride = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
let source = """
  {"schemaVersion":1,"eventId":"00000000-0000-4000-8000-000000000003",\
  "workoutId":"\(ride)","kind":"telemetry","source":"controller",\
  "timestamp":"\(timestamp.original)","elapsedSeconds":0.125,\
  "payload":{"humanPowerW":150.25,"cadenceRpm":80,"observationSequence":"18446744073709551615"}}
  """
let event = try WatchProtocol.shared.decodeEvent(bytes: bytes(source), rideId: ride)
check(event.timestamp.original == timestamp.original && event.elapsedSeconds?.doubleValue == 0.125)
check(event.payloadJson.contains("18446744073709551615"))
let roundTrip = try WatchProtocol.shared.decodeEvent(bytes: event.canonicalBytes, rideId: ride)
check(roundTrip.canonicalUtf8 == event.canonicalUtf8)
rejects {
  _ = try WatchProtocol.shared.decodeEvent(
    bytes: bytes(source), rideId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
}
rejects { _ = try WatchProtocol.shared.canonicalizeJson(bytes: bytes("{bad JSON}")) }
let events = KotlinArray<Event>(size: 1) { _ in event }
let digest = try ContentDigest.shared.events(events: events)
let accumulator = Sha256Accumulator()
try accumulator.update(data: bytes(event.canonicalUtf8 + "\n"))
check(try accumulator.digest() == digest)

let power = ControllerMetric(contentVersion: 1, sourceRevision: 2, content: ControllerPresent(value: 150))
let cadence = ControllerMetric(contentVersion: 1, sourceRevision: 2, content: ControllerPresent(value: 90))
let second = ControllerSecond(n: 0, endOffsetUs: 1_000_000, power: power, cadence: cadence)
let records = KotlinArray<ControllerSecond>(size: 1) { _ in second }
check(
  try ContentDigest.shared.seconds(records: records)
    == "d34822414f5405a8a091e71c9bb163b3efba6448415b306391d268d087e008e2")
print("Swift original-record boundary: \(checks) checks passed")
