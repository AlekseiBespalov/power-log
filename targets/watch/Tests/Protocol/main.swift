import Foundation

func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() {
    FileHandle.standardError.write(Data("Watch protocol: \(message)\n".utf8))
    exit(1)
  }
}

let fixtures = URL(
  fileURLWithPath: ProcessInfo.processInfo.environment["POWER_LOG_CONTRACT_FIXTURES"] ?? "tests/fixtures/contract"
)
.appendingPathComponent("watch")

func fixture(_ name: String) -> Data {
  let data = try! Data(contentsOf: fixtures.appendingPathComponent("\(name).json"))
  return data.last == UInt8(ascii: "\n") ? data.dropLast() : data
}

func json(_ data: Data) -> NSObject { try! JSONSerialization.jsonObject(with: data) as! NSObject }

let messages = [
  "start-request", "cancel-start", "command", "health-samples", "health-outcome", "start", "ride-running",
  "ride-terminal", "ack", "ack-error",
]
for name in messages {
  guard case .valid(let message) = WatchMessages.decode(fixture(name)) else {
    expect(false, "\(name) decodes")
    exit(1)
  }
  guard let encoded = WatchMessages.encode(message) else {
    expect(false, "\(name) encodes")
    exit(1)
  }
  expect(json(encoded) == json(fixture(name)), "\(name) re-encodes to the same JSON value")
  expect(WatchMessages.decode(encoded) == .valid(message), "\(name) round-trips")
}

expect(WatchMessages.decode(fixture("other-version")) == .versionMismatch, "another v is a version mismatch")
expect(WatchMessages.decode(fixture("undecodable")) == .invalid, "a missing field is invalid")
expect(WatchMessages.decode(Data("not json".utf8)) == .invalid, "malformed JSON is invalid")
expect(WatchMessages.decode(Data(#"{"kind":"ack"}"#.utf8)) == .invalid, "a message without v is invalid")
expect(WatchMessages.decode(Data(#"{"v":1,"kind":"shout"}"#.utf8)) == .invalid, "an unknown kind is invalid")
expect(
  WatchMessages.decode(Data(#"{"v":1,"kind":"ack","extra":1}"#.utf8)) == .invalid, "an unknown field is invalid")
expect(
  WatchMessages.decode(Data(#"{"v":1,"kind":"ack","acknowledgedSequence":"12"}"#.utf8)) == .invalid,
  "a mistyped field is invalid")
expect(
  WatchMessages.decode(Data(#"{"v":1,"kind":"command","rideId":"r","action":"jump","expiresUtcUs":1}"#.utf8))
    == .invalid, "an unknown enum value is invalid")
expect(
  WatchMessages.decode(Data(#"{"v":1,"kind":"ack","error":null}"#.utf8)) == .valid(.ack(AckMessage())),
  "an explicit null is an absent optional field")
expect(
  WatchMessages.decode(Data(#"{"v":true,"kind":"ack"}"#.utf8)) == .versionMismatch, "a Boolean v is another version")
for other in [#""1""#, "1.0", "null", "{}", "2"] {
  expect(
    WatchMessages.decode(Data(#"{"v":\#(other),"kind":"ack"}"#.utf8)) == .versionMismatch,
    "v \(other) is another version, as Kotlin's codec answers")
}
func samples(_ range: Range<Int>) -> [HealthOriginal] {
  range.map {
    HealthOriginal(
      sampleID: String(repeating: "s", count: 900), member: $0, metric: "heartRateBpm", value: 1,
      start: Date(timeIntervalSince1970: 1), end: Date(timeIntervalSince1970: 1), dataOrigin: "app", syncID: nil)
  }
}
let firstHalf = WatchMessages.encode(
  .healthSamples(HealthSamplesMessage(rideID: "r", throughSequence: 64, samples: samples(0..<32), heartRate: nil)))!
let secondHalf = WatchMessages.encode(
  .healthSamples(HealthSamplesMessage(rideID: "r", throughSequence: 64, samples: samples(32..<64), heartRate: nil)))!
var merged = try! JSONSerialization.jsonObject(with: firstHalf) as! [String: Any]
merged["samples"] =
  (merged["samples"] as! [Any])
  + ((try! JSONSerialization.jsonObject(with: secondHalf) as! [String: Any])["samples"] as! [Any])
let oversizedInbound = try! JSONSerialization.data(withJSONObject: merged)
expect(oversizedInbound.count > WatchProtocol.maxMessageBytes, "the inbound message is over the send limit")
expect(
  WatchMessages.decode(oversizedInbound)
    == .valid(
      .healthSamples(HealthSamplesMessage(rideID: "r", throughSequence: 64, samples: samples(0..<64), heartRate: nil))),
  "an inbound message is checked for unknown keys without the size limit")

let oversized = HealthSamplesMessage(
  rideID: "r", throughSequence: 1,
  samples: (0..<600).map {
    HealthOriginal(
      sampleID: String(repeating: "x", count: 64), member: $0, metric: "heartRateBpm", value: 1, start: Date(),
      end: Date(), dataOrigin: "app", syncID: nil)
  }, heartRate: nil)
expect(WatchMessages.encode(.healthSamples(oversized)) == nil, "the encoder refuses a message over maxMessageBytes")
let ack = String(decoding: WatchMessages.encode(.ack(AckMessage(error: "watchBusy")))!, as: UTF8.self)
expect(ack == #"{"error":"watchBusy","kind":"ack","v":1}"#, "keys are sorted and v is 1: \(ack)")

let constants = try! JSONSerialization.jsonObject(with: fixture("constants")) as! [String: Any]
let protocolValues = constants["watchProtocol"] as! [String: NSNumber]
let swiftValues: [String: Int64] = [
  "version": Int64(WatchProtocol.version), "maxMessageBytes": Int64(WatchProtocol.maxMessageBytes),
  "maxQuantities": Int64(WatchProtocol.maxQuantities), "maxRoutePoints": Int64(WatchProtocol.maxRoutePoints),
  "maxLaps": Int64(WatchProtocol.maxLaps), "maxHealthSamples": Int64(WatchProtocol.maxHealthSamples),
  "rideIntervalUs": WatchProtocol.rideIntervalUs, "routineMinIntervalUs": WatchProtocol.routineMinIntervalUs,
  "commandExpiryUs": WatchProtocol.commandExpiryUs, "resendUs": WatchProtocol.resendUs,
  "replayUs": WatchProtocol.replayUs,
]
expect(Set(protocolValues.keys) == Set(swiftValues.keys), "the Swift constants cover WatchProtocol")
for (name, value) in swiftValues { expect(protocolValues[name]?.int64Value == value, "\(name) equals the fixture") }
expect((constants["startWindowUs"] as! NSNumber).int64Value == WatchProtocol.startWindowUs, "the Start window")
expect(
  (constants["heartRateDisplayHoldUs"] as! NSNumber).int64Value == WatchProtocol.heartRateDisplayHoldUs,
  "the heart-rate display hold")
expect(constants["ownSyncIdPrefix"] as! String == WatchProtocol.ownSyncIDPrefix, "the own sync id prefix")
print("Watch protocol: \(messages.count) fixtures round-trip, every rule and constant checked")
