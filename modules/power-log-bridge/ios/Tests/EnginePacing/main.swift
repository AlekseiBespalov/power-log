import Foundation
import CoreBluetooth
import PowerLogCore

setbuf(stdout, nil)

final class MonitorDataStore {
  static let shared = MonitorDataStore()
  var samples: [[String: Any]] = []
  var failure: Error?
  func beginLive(startedAt: String, monotonic: Double, id: String) { samples = [] }
  func appendLive(_ sample: [String: Any], elapsedSeconds: Double) throws {
    if let failure { throw failure }
    samples.append(sample)
  }
}

final class MemoryDefaults: UserDefaults {
  private var values: [String: Any] = [:]
  override func data(forKey key: String) -> Data? { values[key] as? Data }
  override func string(forKey key: String) -> String? { values[key] as? String }
  override func double(forKey key: String) -> Double { values[key] as? Double ?? 0 }
  override func set(_ value: Any?, forKey key: String) { values[key] = value }
  override func removeObject(forKey key: String) { values.removeValue(forKey: key) }
}

var checks = 0
var failures = 0
func check(_ condition: Bool, _ label: String) {
  checks += 1
  if !condition {
    failures += 1
    print("FAIL: \(label)")
  }
}

func bytes(_ hex: String) -> Data {
  Data(
    stride(from: 0, to: hex.count, by: 2).map {
      let start = hex.index(hex.startIndex, offsetBy: $0)
      let end = hex.index(start, offsetBy: 2)
      return UInt8(hex[start..<end], radix: 16)!
    })
}

let telemetry = bytes(
  "02363203c0fb8f016301a7000004d200000237000009c4020b000030d400231860000d59f800000078000008ba00000000cd000009ab0103975203"
)
func framed(_ payload: [UInt8]) -> Data {
  let crc = CycProtocol.shared.crc(bytes: WorkoutCoreCodec.bytes(Data(payload)))
  return Data([2, UInt8(payload.count)] + payload + [UInt8(crc >> 8), UInt8(crc & 0xff), 3])
}
let identity = framed([111, 1, 2] + Array("X6 20260101".utf8) + [0])

final class EngineHarness {
  var now = 1000.0
  let timerDelay: Double
  let replyDelay: Double
  var timer: (due: Double, fire: () -> Void)?
  var polls: [(due: Double, work: DispatchWorkItem)] = []
  var replies: [(due: Double, data: Data)] = []
  var sent: [Double] = []
  var respond = true
  let peripheral = CBPeripheral()
  let writer = CBCharacteristic(
    uuid: CBUUID(string: "6e400002-b5a3-f393-e0a9-e50e24dcca9e"), properties: .writeWithoutResponse)
  let notifier = CBCharacteristic(
    uuid: CBUUID(string: "6e400003-b5a3-f393-e0a9-e50e24dcca9e"), properties: .notify)
  lazy var engine = ControllerEngine(
    defaults: MemoryDefaults(), monotonicNow: { [unowned self] in self.now },
    scheduleTimer: { [unowned self] delay, fire in self.timer = (self.now + delay + self.timerDelay, fire) },
    schedulePoll: { [unowned self] delay, work in self.polls.append((self.now + delay, work)) })

  init(timerDelay: Double = 0.3, replyDelay: Double = 0.02) {
    self.timerDelay = timerDelay
    self.replyDelay = replyDelay
    peripheral.onWrite = { [unowned self] packet in
      let isTelemetry = packet == TelemetrySession().frame(.telemetry)
      if isTelemetry { self.sent.append(self.now) }
      if self.respond { self.replies.append((self.now + self.replyDelay, isTelemetry ? telemetry : identity)) }
    }
  }

  func connect() throws {
    engine.setBackground(false)
    CBCentralManager.peripherals = [peripheral]
    try engine.startScan()
    try engine.connect(deviceID: peripheral.identifier.uuidString, rate: 8)
    peripheral.state = .connected
    engine.centralManager(CBCentralManager.latest, didConnect: peripheral)
    let service = CBService(
      uuid: CBUUID(string: "6e400001-b5a3-f393-e0a9-e50e24dcca9e"), characteristics: [writer, notifier])
    peripheral.services = [service]
    engine.peripheral(peripheral, didDiscoverServices: nil)
    engine.peripheral(peripheral, didDiscoverCharacteristicsFor: service, error: nil)
    engine.peripheral(peripheral, didUpdateNotificationStateFor: notifier, error: nil)
    advance(to: now + replyDelay)
    precondition(!respond || !sent.isEmpty, "Synthetic handshake did not complete: \(engine.state())")
  }

  func notify(_ data: Data, error: Error? = nil) {
    notifier.value = data
    engine.peripheral(peripheral, didUpdateValueFor: notifier, error: error)
  }

  func advance(to end: Double) {
    var events = 0
    while true {
      polls.removeAll { $0.work.isCancelled }
      polls.sort { $0.due < $1.due }
      replies.sort { $0.due < $1.due }
      let pollDue = polls.first?.due ?? .infinity
      let replyDue = replies.first?.due ?? .infinity
      let timerDue = timer?.due ?? .infinity
      let due = min(pollDue, replyDue, timerDue)
      guard due <= end else { break }
      events += 1
      if events > 10_000 {
        check(false, "capture must not spin at one timestamp")
        break
      }
      now = due
      if replyDue == due {
        notify(replies.removeFirst().data)
      } else if pollDue == due {
        polls.removeFirst().work.perform()
      } else {
        let fire = timer!.fire
        timer = nil
        fire()
      }
    }
    now = end
  }

  func finish() throws { try engine.disconnect() }
}

let deadlinesURL = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
  .appendingPathComponent("tests/fixtures/contract/telemetry/deadlines.json")
let deadlines = try JSONSerialization.jsonObject(with: Data(contentsOf: deadlinesURL)) as! [String: Any]

let traced = EngineHarness()
try traced.engine.queue.sync {
  traced.respond = false
  traced.engine.startBluetoothTrace()
  try traced.connect()
  let rejected = framed([111, 5, 3] + Array("X1 20260618".utf8) + [0])
  traced.notify(rejected.prefix(4))
  traced.notify(rejected.dropFirst(4))
  traced.engine.stopBluetoothTrace()
  let report = try JSONSerialization.jsonObject(with: Data(traced.engine.readBluetoothTrace().utf8)) as! [String: Any]
  let events = report["events"] as! [[String: Any]]
  let hex = events.filter { $0["event"] as? String == "notification" }.map { $0["hex"] as! String }.joined()
  check(hex == rejected.map { String(format: "%02x", $0) }.joined(), "trace preserves rejected raw fragments")
  check(
    events.contains { ($0["detail"] as? String)?.contains("rejection=identity") == true },
    "trace records the identity rejection before disconnect")
  check(traced.engine.state()["status"] as? String == "error", "diagnostics never bypass identity validation")
  check(traced.sent.isEmpty, "a rejected identity cannot trigger telemetry reads")
  traced.engine.clearBluetoothTrace()
  check(traced.engine.bluetoothTraceState()["eventCount"] as? Int32 == 0, "clear deletes the raw capture")
  try traced.finish()
}

let lateDiscovery = EngineHarness()
try lateDiscovery.engine.queue.sync {
  CBCentralManager.peripherals = [lateDiscovery.peripheral]
  lateDiscovery.engine.setBackground(false)
  try lateDiscovery.engine.startScan()
  lateDiscovery.now += deadlines["attemptDeadline"] as! Double
  var discoveries = 0
  lateDiscovery.engine.addSink(id: UUID()) { event, _ in if event == "onDevice" { discoveries += 1 } }
  lateDiscovery.engine.centralManager(
    CBCentralManager.latest, didDiscover: lateDiscovery.peripheral,
    advertisementData: [:], rssi: -50)
  check(discoveries == 0, "discovery at the twenty-second boundary cannot admit a device")
  lateDiscovery.engine.stopScan()
}

let lateSetup = EngineHarness()
try lateSetup.engine.queue.sync {
  CBCentralManager.peripherals = [lateSetup.peripheral]
  try lateSetup.engine.startScan()
  try lateSetup.engine.connect(deviceID: lateSetup.peripheral.identifier.uuidString, rate: 8)
  lateSetup.peripheral.state = .connected
  lateSetup.now += deadlines["lateCallbackAt"] as! Double
  lateSetup.engine.centralManager(CBCentralManager.latest, didConnect: lateSetup.peripheral)
  let service = CBService(
    uuid: CBUUID(string: "6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
    characteristics: [lateSetup.writer, lateSetup.notifier])
  lateSetup.peripheral.services = [service]
  lateSetup.engine.peripheral(lateSetup.peripheral, didDiscoverServices: nil)
  lateSetup.engine.peripheral(lateSetup.peripheral, didDiscoverCharacteristicsFor: service, error: nil)
  lateSetup.engine.peripheral(lateSetup.peripheral, didUpdateNotificationStateFor: lateSetup.notifier, error: nil)
  check(lateSetup.replies.isEmpty, "a late setup callback cannot send identity after the attempt budget")
  try lateSetup.finish()
}

let delayed = EngineHarness()
try delayed.engine.queue.sync {
  try delayed.connect()
  let started = delayed.now
  delayed.advance(to: started + 60)
  let samples = MonitorDataStore.shared.samples.count
  let rate = Double(samples) / 60
  print(String(format: "8 Hz, timer +300 ms, replies +20 ms: %d readings/minute, %.3f Hz", samples, rate))
  check(samples >= 456, "delayed timer capture must retain at least 95% of 8 Hz over one minute (got \(samples)/480)")
  check(
    zip(delayed.sent, delayed.sent.dropFirst()).allSatisfy { $1 - $0 >= 0.125 - 1e-9 },
    "delayed timers never exceed 8 Hz")
  try delayed.finish()
}

let instant = EngineHarness(replyDelay: 0)
try instant.engine.queue.sync {
  try instant.connect()
  let started = instant.now
  instant.advance(to: started + 60 - 1e-9)
  let samples = MonitorDataStore.shared.samples.count
  print("8 Hz, instant replies: \(samples) readings/minute")
  check(samples <= 480, "instant replies never capture more than 480 readings in a minute")
  check(
    zip(instant.sent, instant.sent.dropFirst()).allSatisfy { $1 - $0 >= 0.125 - 1e-9 },
    "instant replies never send before the 125 ms due time")
  try instant.finish()
}

let overdue = EngineHarness(replyDelay: 0.2)
try overdue.engine.queue.sync {
  try overdue.connect()
  let firstSend = overdue.sent[0]
  overdue.advance(to: firstSend + 0.2)
  check(overdue.sent.count == 2, "an overdue next poll sends in the reply callback")
  check(overdue.sent.last == firstSend + 0.2, "an overdue reply does not add a timer delay")
  try overdue.finish()
}

let trailing = EngineHarness()
try trailing.engine.queue.sync {
  trailing.respond = false
  try trailing.connect()
  trailing.notify(identity + telemetry)
  check(trailing.sent.count == 1, "identity verification starts one selective request after draining the callback")
  check(MonitorDataStore.shared.samples.isEmpty, "trailing old telemetry cannot satisfy a newly issued poll")
  trailing.notify(telemetry + telemetry)
  check(MonitorDataStore.shared.samples.count == 1, "duplicate telemetry in one callback captures one original")
  trailing.advance(to: trailing.sent[0] + 0.125)
  check(trailing.sent.count == 2, "one completed request schedules exactly one next poll")
  try trailing.finish()
}

let missing = EngineHarness()
try missing.engine.queue.sync {
  try missing.connect()
  missing.respond = false
  missing.replies = []
  let first = missing.sent[0]
  missing.advance(to: first + TelemetryTiming.shared.silenceSeconds - 0.001)
  check(missing.sent.count == 3, "an unanswered request is sent again on the same link")
  check(
    zip(missing.sent, missing.sent.dropFirst()).allSatisfy { $1 - $0 >= TelemetryTiming.shared.replyWindowSeconds },
    "a request is sent again only after its reply window, never overlapping")
  check(missing.engine.state()["status"] as? String != "reconnecting", "unanswered requests keep the link")
  missing.advance(to: first + TelemetryTiming.shared.silenceSeconds + 0.7)
  check(missing.sent.count == 3, "silence ends the re-sending")
  check(
    missing.engine.state()["status"] as? String == "reconnecting",
    "the delayed timer closes a link that stays silent")
  check(CBCentralManager.latest.cancellations == 1, "silence cancels the ambiguous old connection")
  try missing.finish()
}

let resent = EngineHarness(timerDelay: 0)
try resent.engine.queue.sync {
  try resent.connect()
  resent.replies = []
  resent.advance(to: resent.sent[0] + TelemetryTiming.shared.replyWindowSeconds + 0.1)
  check(resent.sent.count == 2, "the reply window sends the request again")
  check(MonitorDataStore.shared.samples.count == 1, "the reply to a re-sent request is captured")
  check(resent.engine.state()["status"] as? String == "connected", "a re-sent request keeps the link connected")
  check(CBCentralManager.latest.cancellations == 0, "a re-sent request never cancels the connection")
  try resent.finish()
}

let late = EngineHarness(timerDelay: 0)
try late.engine.queue.sync {
  try late.connect()
  late.respond = false
  late.replies = []
  late.advance(to: late.sent[0] + TelemetryTiming.shared.replyWindowSeconds + 0.01)
  check(late.sent.count == 2, "the reply window sends the late request again")
  late.notify(telemetry)
  late.notify(telemetry)
  check(
    MonitorDataStore.shared.samples.count == 1, "a late reply and the reply to its re-sent request record one reading")
  try late.finish()
}

let expired = EngineHarness()
try expired.engine.queue.sync {
  try expired.connect()
  expired.replies = []
  expired.now += TelemetryTiming.shared.telemetryTimeoutSeconds + 0.001
  expired.notify(telemetry)
  check(MonitorDataStore.shared.samples.isEmpty, "an expired reply never enters capture")
  check(
    expired.sent.count == 1 && expired.engine.state()["status"] as? String == "reconnecting",
    "an expired reply reconnects without pacing another request")
  try expired.finish()
}

let blocked = EngineHarness()
try blocked.engine.queue.sync {
  try blocked.connect()
  blocked.advance(to: blocked.now + 0.02)
  blocked.peripheral.canSendWriteWithoutResponse = false
  blocked.advance(to: blocked.sent[0] + 0.125)
  check(blocked.sent.count == 1, "reply pacing respects UART backpressure")
  blocked.now += 0.01
  blocked.peripheral.canSendWriteWithoutResponse = true
  blocked.engine.peripheralIsReady(toSendWriteWithoutResponse: blocked.peripheral)
  check(blocked.sent.count == 2, "write-ready callback sends the waiting due request")
  check(
    blocked.sent.count == 2 && blocked.sent.last! - blocked.sent[0] >= 0.125,
    "write-ready recovery does not exceed the requested rate")
  try blocked.finish()
}

let writeTimeout = EngineHarness()
try writeTimeout.engine.queue.sync {
  try writeTimeout.connect()
  writeTimeout.advance(to: writeTimeout.now + 0.02)
  writeTimeout.peripheral.canSendWriteWithoutResponse = false
  writeTimeout.advance(to: writeTimeout.sent[0] + 0.125 + TelemetryTiming.shared.telemetryTimeoutSeconds + 0.7)
  check(writeTimeout.sent.count == 1, "blocked writes cannot emit or overlap requests")
  check(
    writeTimeout.engine.state()["status"] as? String == "reconnecting", "the timer preserves the blocked-write deadline"
  )
  try writeTimeout.finish()
}

let disconnected = EngineHarness()
try disconnected.engine.queue.sync {
  try disconnected.connect()
  disconnected.advance(to: disconnected.now + 0.02)
  let queuedPolls = disconnected.polls.map(\.work)
  try disconnected.finish()
  disconnected.advance(to: disconnected.now + 1)
  for work in queuedPolls { work.perform() }
  check(disconnected.sent.count == 1, "disconnect invalidates queued reply pacing")
}

let earlyWake = EngineHarness()
try earlyWake.engine.queue.sync {
  try earlyWake.connect()
  earlyWake.advance(to: earlyWake.now + 0.02)
  let due = earlyWake.sent[0] + 0.125
  earlyWake.polls.first?.work.perform()
  check(earlyWake.sent.count == 1, "an early queue wake reschedules without exceeding the requested rate")
  earlyWake.advance(to: due)
  check(earlyWake.sent.count == 2, "an early queue wake retains the original next-poll deadline")
  try earlyWake.finish()
}

let timerRace = EngineHarness()
try timerRace.engine.queue.sync {
  try timerRace.connect()
  timerRace.advance(to: timerRace.now + 0.02)
  let poll = timerRace.polls.first?.work
  let timer = timerRace.timer?.fire
  timerRace.now = timerRace.sent[0] + 0.125
  timer?()
  poll?.perform()
  check(timerRace.sent.count == 2, "the timer and reply work item cannot issue overlapping due requests")
  timerRace.advance(to: timerRace.now + 0.125)
  check(timerRace.sent.count == 3, "a timer-won race preserves pacing for the next completed reply")
  try timerRace.finish()
}

for owner in [false, true] {
  let changedRate = EngineHarness()
  try changedRate.engine.queue.sync {
    try changedRate.connect()
    changedRate.advance(to: changedRate.now + 0.02)
    if owner {
      changedRate.engine.setWorkoutSamplingOwner("synthetic-ride", rate: 4)
    } else {
      try changedRate.engine.setSampleRate(4)
    }
    let latestReply = changedRate.now
    changedRate.advance(to: changedRate.sent[0] + 0.125)
    check(changedRate.sent.count == 1, "a lower \(owner ? "owner" : "phone") rate invalidates the earlier queued poll")
    if owner {
      changedRate.engine.setWorkoutSamplingOwner("synthetic-ride", rate: 8)
      check(changedRate.engine.currentSampleRate == 4, "an active owner's admitted rate remains frozen")
      changedRate.engine.setWorkoutSamplingOwner("synthetic-next-ride", rate: 8)
    } else {
      try changedRate.engine.setSampleRate(8)
    }
    changedRate.advance(to: latestReply + 0.125)
    check(changedRate.sent.count == 2, "a higher \(owner ? "owner" : "phone") rate replaces the slower queued poll")
    try changedRate.finish()
  }
}

let fragments = EngineHarness()
try fragments.engine.queue.sync {
  try fragments.connect()
  fragments.respond = false
  fragments.replies = []
  fragments.notify(telemetry.prefix(10))
  check(
    MonitorDataStore.shared.samples.isEmpty && fragments.polls.isEmpty,
    "partial replies neither capture nor pace a new request")
  fragments.now += 0.2
  let acquired = fragments.now
  fragments.notify(telemetry.dropFirst(10))
  check(fragments.sent.count == 2, "a completed split reply paces its overdue next request")
  check(
    MonitorDataStore.shared.samples.first?["acquisitionMonotonic"] as? Double == acquired,
    "reply pacing retains the completed original's acquisition time")
  try fragments.finish()
}

let cancelledBackoff = EngineHarness()
try cancelledBackoff.engine.queue.sync {
  try cancelledBackoff.connect()
  cancelledBackoff.engine.setBackground(true)
  cancelledBackoff.respond = false
  cancelledBackoff.replies = []
  cancelledBackoff.now += 2.500001
  cancelledBackoff.notify(telemetry)
  let failedAt = cancelledBackoff.now
  let oldConnections = CBCentralManager.latest.connections
  cancelledBackoff.advance(to: failedAt + TelemetryTiming.shared.linkReleaseSeconds + 0.5)
  check(CBCentralManager.latest.connections == oldConnections, "retry waits for native cancellation completion")
  cancelledBackoff.peripheral.state = .disconnected
  cancelledBackoff.engine.centralManager(
    CBCentralManager.latest,
    didDisconnectPeripheral: cancelledBackoff.peripheral, error: nil)
  cancelledBackoff.engine.setBackground(false)
  cancelledBackoff.advance(to: failedAt + TelemetryTiming.shared.linkReleaseSeconds + 0.8)
  check(
    CBCentralManager.latest.connections == oldConnections + 1,
    "completed cancellation permits exactly one bounded retry")
  cancelledBackoff.notify(telemetry)
  check(cancelledBackoff.sent.count == 1, "old notification cannot skip the new identity handshake")
  try cancelledBackoff.finish()
}
let cancellationBudget = EngineHarness(timerDelay: 0)
try cancellationBudget.engine.queue.sync {
  try cancellationBudget.connect()
  cancellationBudget.respond = false
  cancellationBudget.replies = []
  cancellationBudget.now += 2.500001
  cancellationBudget.notify(telemetry)
  let failedAt = cancellationBudget.now
  cancellationBudget.now = failedAt + 0.5
  cancellationBudget.peripheral.state = .connected
  cancellationBudget.engine.centralManager(CBCentralManager.latest, didConnect: cancellationBudget.peripheral)
  cancellationBudget.advance(
    to: failedAt + TelemetryTiming.shared.linkReleaseSeconds + TelemetryTiming.shared.cancellationGraceSeconds)
  check(
    cancellationBudget.engine.state()["status"] as? String == "error",
    "unexpected connection during backoff cannot extend the original cancellation deadline")
  check(MonitorDataStore.shared.samples.isEmpty, "cancellation-fenced callbacks cannot capture originals")
  try cancellationBudget.finish()
}

let activeCancellation = EngineHarness(timerDelay: 0)
try activeCancellation.engine.queue.sync {
  try activeCancellation.connect()
  activeCancellation.engine.setWorkoutSamplingOwner("synthetic-ride", rate: 8)
  activeCancellation.respond = false
  activeCancellation.replies = []
  activeCancellation.now += 2.500001
  activeCancellation.notify(telemetry)
  let expiry =
    activeCancellation.now + TelemetryTiming.shared.linkReleaseSeconds + TelemetryTiming.shared.cancellationGraceSeconds
  let oldConnections = CBCentralManager.latest.connections
  activeCancellation.advance(to: expiry)
  check(
    activeCancellation.engine.state()["status"] as? String == "reconnecting",
    "an active ride retains intent after cancellation expiry")
  activeCancellation.now = expiry + 1
  activeCancellation.peripheral.state = .disconnected
  activeCancellation.engine.centralManager(
    CBCentralManager.latest,
    didDisconnectPeripheral: activeCancellation.peripheral, error: nil)
  activeCancellation.advance(to: expiry + 29)
  check(
    CBCentralManager.latest.connections == oldConnections,
    "late cancellation completion cannot bypass the active ride's thirty-second backoff")
  activeCancellation.advance(to: expiry + 30)
  check(
    CBCentralManager.latest.connections == oldConnections + 1,
    "an active ride resumes bounded recovery after late native completion")
  try activeCancellation.finish()
}

let restoredRadio = EngineHarness()
try restoredRadio.engine.queue.sync {
  try restoredRadio.connect()
  restoredRadio.engine.setWorkoutSamplingOwner("synthetic-ride", rate: 8)
  let oldConnections = CBCentralManager.latest.connections
  restoredRadio.peripheral.state = .disconnected
  CBCentralManager.latest.state = .poweredOff
  restoredRadio.engine.centralManagerDidUpdateState(CBCentralManager.latest)
  check(restoredRadio.engine.state()["status"] as? String == "reconnecting", "Bluetooth-off retains active ride intent")
  check(
    restoredRadio.engine.state()["error"] as? String == WorkoutRideFailure("bluetoothOff").message,
    "Bluetooth-off during a ride names its cause")
  CBCentralManager.latest.state = .poweredOn
  restoredRadio.engine.centralManagerDidUpdateState(CBCentralManager.latest)
  check(
    CBCentralManager.latest.connections == oldConnections + 1,
    "radio restoration starts recovery without inventing a thirty-second delay")
  check(
    restoredRadio.engine.state()["error"] as? String == WorkoutRideFailure("bikeReconnecting").message,
    "radio restoration replaces the Bluetooth-off text with the reconnecting text")
  try restoredRadio.finish()
}

let radioDuringCancellation = EngineHarness(timerDelay: 100)
try radioDuringCancellation.engine.queue.sync {
  radioDuringCancellation.now = 0
  radioDuringCancellation.respond = false
  try radioDuringCancellation.connect()
  radioDuringCancellation.engine.setWorkoutSamplingOwner("synthetic-ride", rate: 8)
  radioDuringCancellation.now = 20
  radioDuringCancellation.timer!.fire()
  let oldConnections = CBCentralManager.latest.connections
  radioDuringCancellation.now = 27
  radioDuringCancellation.peripheral.state = .disconnected
  CBCentralManager.latest.state = .poweredOff
  radioDuringCancellation.engine.centralManagerDidUpdateState(CBCentralManager.latest)
  check(radioDuringCancellation.engine.state()["status"] as? String == "reconnecting", "radio loss retains ride intent")
  CBCentralManager.latest.state = .poweredOn
  radioDuringCancellation.engine.centralManagerDidUpdateState(CBCentralManager.latest)
  check(
    CBCentralManager.latest.connections == oldConnections + 1,
    "radio loss at 27 clears the expired 21/26 retry and cancellation fence")
  radioDuringCancellation.now = 28
  radioDuringCancellation.timer?.fire()
  check(
    CBCentralManager.latest.connections == oldConnections + 1, "deferred timer cannot restore a radio teardown fence")
  try radioDuringCancellation.finish()
}

let systemAttempt = EngineHarness(timerDelay: 100)
try systemAttempt.engine.queue.sync {
  systemAttempt.now = 0
  try systemAttempt.connect()
  systemAttempt.advance(to: 30.1)
  systemAttempt.respond = false
  systemAttempt.replies = []
  systemAttempt.peripheral.state = .disconnected
  let oldConnections = CBCentralManager.latest.connections
  systemAttempt.engine.centralManager(
    CBCentralManager.latest, didDisconnectPeripheral: systemAttempt.peripheral,
    timestamp: 0, isReconnecting: true, error: nil)
  check(CBCentralManager.latest.connections == oldConnections, "a stable peer disconnect adopts the system attempt")
  systemAttempt.now = 50.1
  systemAttempt.timer!.fire()
  check(CBCentralManager.latest.cancellations == 1, "expired system ownership cancels even a disconnected peripheral")
  systemAttempt.now = 52.1
  systemAttempt.timer!.fire()
  check(
    CBCentralManager.latest.connections == oldConnections, "expired system attempt waits for cancellation completion")
  systemAttempt.now = 53
  systemAttempt.peripheral.state = .disconnected
  systemAttempt.engine.centralManager(
    CBCentralManager.latest, didDisconnectPeripheral: systemAttempt.peripheral, error: nil)
  systemAttempt.timer!.fire()
  check(
    CBCentralManager.latest.connections == oldConnections + 1, "system cancellation completion permits one manual retry"
  )
  try systemAttempt.finish()
}

for activeRide in [false, true] {
  for callbackFirst in [false, true] {
    let owner = EngineHarness(timerDelay: 100)
    try owner.engine.queue.sync {
      owner.now = 0
      owner.respond = false
      try owner.connect()
      if activeRide { owner.engine.setWorkoutSamplingOwner("synthetic-ride", rate: 8) }
      owner.now = 20
      owner.timer!.fire()
      let overdue = owner.timer!.fire
      let oldConnections = CBCentralManager.latest.connections
      owner.now = 27
      owner.peripheral.state = .disconnected
      let completion = {
        owner.engine.centralManager(CBCentralManager.latest, didDisconnectPeripheral: owner.peripheral, error: nil)
      }
      if callbackFirst {
        completion()
        overdue()
      } else {
        overdue()
        completion()
      }
      let order = callbackFirst ? "callback first" : "timer first"
      check(
        owner.engine.state()["status"] as? String == (activeRide ? "reconnecting" : "error"),
        "late cancellation keeps the \(activeRide ? "ride" : "preview") failure path, \(order)")
      check(
        owner.engine.state()["error"] as? String
          == WorkoutRideFailure(activeRide ? "bikeReconnecting" : "bikeUnavailable").message,
        "late cancellation shows the shared \(activeRide ? "reconnecting" : "couldn't-connect") text, \(order)")
      owner.now = 29
      owner.timer?.fire()
      check(
        CBCentralManager.latest.connections == oldConnections, "late cancellation cannot use ordinary retry, \(order)")
      if activeRide {
        owner.now = 56.999
        owner.timer?.fire()
        check(
          CBCentralManager.latest.connections == oldConnections,
          "ride cancellation recovery waits thirty seconds, \(order)")
        owner.now = 57
        owner.timer?.fire()
        check(
          CBCentralManager.latest.connections == oldConnections + 1,
          "ride cancellation recovery resumes at its deadline, \(order)")
      }
      try owner.finish()
    }
  }
}

for activeRide in [false, true] {
  for callbackFirst in [false, true] {
    let owner = EngineHarness(timerDelay: 100)
    try owner.engine.queue.sync {
      owner.now = 0
      owner.respond = false
      try owner.connect()
      if activeRide { owner.engine.setWorkoutSamplingOwner("synthetic-ride", rate: 8) }
      owner.now = 20
      owner.timer!.fire()
      let overdue = owner.timer!.fire
      let oldConnections = CBCentralManager.latest.connections
      let oldCancellations = CBCentralManager.latest.cancellations
      owner.now = 27
      owner.peripheral.state = .disconnected
      let completion = {
        owner.engine.centralManager(
          CBCentralManager.latest, didDisconnectPeripheral: owner.peripheral,
          timestamp: 0, isReconnecting: true, error: nil)
      }
      if callbackFirst {
        completion()
        overdue()
      } else {
        overdue()
        completion()
      }
      let order = callbackFirst ? "callback first" : "timer first"
      check(
        owner.engine.state()["status"] as? String == (activeRide ? "reconnecting" : "error"),
        "late modern cancellation keeps the failure path, \(order)")
      check(
        owner.engine.state()["error"] as? String
          == WorkoutRideFailure(activeRide ? "bikeReconnecting" : "bikeUnavailable").message,
        "late modern cancellation shows the shared \(activeRide ? "reconnecting" : "couldn't-connect") text, \(order)")
      check(
        CBCentralManager.latest.cancellations > oldCancellations,
        "late modern callback cancels the flagged system attempt, \(order)")
      owner.now = 57
      owner.peripheral.state = .disconnected
      owner.timer?.fire()
      check(
        CBCentralManager.latest.connections == oldConnections,
        "late modern cancellation awaits system completion before any app attempt, \(order)")
      if !activeRide {
        do {
          try owner.engine.connect(deviceID: owner.peripheral.identifier.uuidString, rate: 8)
          check(false, "preview awaits flagged system cancellation before a new connection, \(order)")
        } catch {
          check(
            WorkoutRideFailure.classify(error, stage: "bike").code == "bluetoothNotReady",
            "preview awaits flagged system cancellation before a new connection, \(order)")
        }
      }
      owner.engine.centralManager(
        CBCentralManager.latest, didDisconnectPeripheral: owner.peripheral,
        timestamp: 0, isReconnecting: false, error: nil)
      owner.timer?.fire()
      check(
        CBCentralManager.latest.connections == oldConnections + (activeRide ? 1 : 0),
        "system cancellation completion releases only the ride recovery, \(order)")
      try owner.finish()
    }
  }
}

for completes in [false, true] {
  let nativeFence = EngineHarness(timerDelay: 100)
  try nativeFence.engine.queue.sync {
    nativeFence.now = 0
    try nativeFence.connect()
    nativeFence.advance(to: 0.1)
    nativeFence.respond = false
    nativeFence.replies = []
    nativeFence.now = 20
    nativeFence.peripheral.state = .disconnecting
    nativeFence.engine.centralManager(
      CBCentralManager.latest, didDisconnectPeripheral: nativeFence.peripheral, error: nil)
    let oldConnections = CBCentralManager.latest.connections
    nativeFence.now = 21
    nativeFence.timer!.fire()
    check(CBCentralManager.latest.connections == oldConnections, "native disconnecting fences the retry due at 21")
    if completes {
      nativeFence.now = 22
      nativeFence.peripheral.state = .disconnected
      nativeFence.timer!.fire()
      check(CBCentralManager.latest.connections == oldConnections + 1, "native disconnection releases the retry fence")
    } else {
      nativeFence.now = 26
      nativeFence.timer!.fire()
      check(
        nativeFence.engine.state()["status"] as? String == "error",
        "native disconnecting obeys cancellation deadline 26")
      check(
        nativeFence.engine.state()["error"] as? String == WorkoutRideFailure("bikeUnavailable").message
          && nativeFence.engine.state()["errorCode"] as? String == "bikeUnavailable",
        "native disconnecting expiry shows the shared couldn't-connect text")
    }
    try nativeFence.finish()
  }
}

// The bike banner shows plain text from the shared vocabulary, never a native description.
let storage = EngineHarness()
try storage.engine.queue.sync {
  storage.engine.captureResult(PowerLogStorageError.sqlite(5, "database is locked"))
  let recording = storage.engine.state()["error"] as? String
  check(
    recording == WorkoutRideFailure("storageFailed").message,
    "a recording storage failure shows plain text: \(recording ?? "none")")
  storage.engine.captureResult(nil)
  check(storage.engine.state()["error"] == nil, "a later successful capture clears the banner")
  try storage.connect()
  MonitorDataStore.shared.failure = PowerLogStorageError.sqlite(5, "database is locked")
  storage.advance(to: storage.now + 2)
  MonitorDataStore.shared.failure = nil
  let live = storage.engine.state()["error"] as? String
  check(
    live == WorkoutRideFailure("storageFailed").message,
    "a live capture storage failure shows plain text: \(live ?? "none")")
  try storage.finish()
}
let refused = EngineHarness()
try refused.engine.queue.sync {
  CBCentralManager.peripherals = [refused.peripheral]
  try refused.engine.startScan()
  try refused.engine.connect(deviceID: refused.peripheral.identifier.uuidString, rate: 8)
  refused.engine.centralManager(
    CBCentralManager.latest, didFailToConnect: refused.peripheral,
    error: NSError(domain: "CBErrorDomain", code: 14, userInfo: [NSLocalizedDescriptionKey: "Peer removed pairing"]))
  let shown = refused.engine.state()["error"] as? String
  check(
    shown == WorkoutRideFailure("bikeReconnecting").message,
    "a failed connection attempt shows the shared reconnecting text: \(shown ?? "none")")
  try refused.finish()
}

let backgroundPreview = EngineHarness(timerDelay: 0)
try backgroundPreview.engine.queue.sync {
  try backgroundPreview.connect()
  func requests(for seconds: Double) -> Int {
    let started = backgroundPreview.now
    backgroundPreview.advance(to: started + seconds)
    return backgroundPreview.sent.filter { $0 > started }.count
  }
  backgroundPreview.engine.setBackground(true)
  let preview = requests(for: 10)
  check(preview <= 11, "a background preview polls at 1 Hz (\(preview) requests in 10 s)")
  backgroundPreview.engine.setWorkoutSamplingOwner("synthetic-ride", rate: 8)
  let ride = requests(for: 10)
  check(ride >= 75, "a background ride polls at its rate (\(ride) requests in 10 s)")
  backgroundPreview.engine.setWorkoutSamplingOwner(nil, rate: 8)
  backgroundPreview.engine.setBackground(false)
  let foreground = requests(for: 10)
  check(foreground >= 75, "a foreground preview polls at the selected rate (\(foreground) requests in 10 s)")
  try backgroundPreview.finish()
}

let steadyState = EngineHarness(timerDelay: 0)
try steadyState.engine.queue.sync {
  try steadyState.connect()
  steadyState.advance(to: steadyState.now + 1)
  var states = 0
  steadyState.engine.addSink(id: UUID()) { event, _ in if event == "onState" { states += 1 } }
  steadyState.advance(to: steadyState.now + 10)
  check(states == 0, "steady telemetry does not repeat an unchanged controller state (\(states) events)")
  try steadyState.finish()
}

let silentLoss = EngineHarness(timerDelay: 0)
try silentLoss.engine.queue.sync {
  try silentLoss.connect()
  silentLoss.advance(to: silentLoss.now + 1)
  if let reply = silentLoss.replies.map(\.due).min() { silentLoss.advance(to: reply) }
  silentLoss.peripheral.state = .disconnected
  silentLoss.advance(to: silentLoss.now + 0.5)
  check(
    silentLoss.engine.state()["status"] as? String == "reconnecting",
    "a link lost without a disconnect callback reconnects instead of polling a dead peripheral: \(silentLoss.engine.state())"
  )
  try silentLoss.finish()
}

print("Engine pacing: \(checks - failures) passed, \(failures) failed")
if failures > 0 { exit(1) }
