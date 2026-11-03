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

let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-controller-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }

final class EngineHarness {
  let log = try! TelemetryDiagnosticLog(directory: root.appendingPathComponent(UUID().uuidString))
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
    schedulePoll: { [unowned self] delay, work in self.polls.append((self.now + delay, work)) },
    diagnosticLog: { [unowned self] in self.log })

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

func recordingEvents(_ harness: EngineHarness) throws -> [[String: Any]] {
  try harness.log.read().split(separator: "\n").compactMap {
    let record = try JSONSerialization.jsonObject(with: Data($0.utf8)) as! [String: Any]
    return record["event"] as? String == "recording_error" ? record : nil
  }
}

let storage = EngineHarness()
try storage.engine.queue.sync {
  try storage.connect()
  MonitorDataStore.shared.failure = PowerLogStorageError.busy
  storage.advance(to: storage.now + 2)
  check(try recordingEvents(storage).count == 1, "full inbox logs one recording error across repeated frames")
  storage.engine.captureResult(PowerLogStorageError.busy)
  check(try recordingEvents(storage).count == 1, "owner callback does not repeat the same admission error")
  storage.engine.captureResult(nil)
  storage.engine.captureResult(nil)
  check(try recordingEvents(storage).count == 2, "recovery logs one recording error clearance")
  check(storage.engine.state()["error"] == nil, "recovery clears the recording banner")
  storage.advance(to: storage.now + 1)
  check(try recordingEvents(storage).count == 3, "a renewed full inbox logs a new transition")
  storage.engine.captureResult(PowerLogStorageError.sqlite(13, "synthetic disk full"))
  storage.engine.captureResult(PowerLogStorageError.sqlite(13, "synthetic disk full"))
  check(try recordingEvents(storage).count == 4, "a different store error logs once")
  MonitorDataStore.shared.failure = nil
  storage.engine.captureResult(nil)
  let events = try recordingEvents(storage)
  check(events.count == 5, "the second recovery logs once")
  check(
    events.compactMap { $0["recordingFailed"] as? Bool } == [true, false, true, true, false],
    "diagnostics distinguish errors and recovery without recording private text")
  try storage.finish()
}

let refusals: [(state: CBManagerState, code: String)] = [
  (.unauthorized, "bluetoothDenied"), (.poweredOff, "bluetoothOff"), (.unsupported, "bluetoothUnsupported"),
  (.resetting, "bluetoothNotReady"),
]
for (state, code) in refusals {
  for scan in [false, true] where !(scan && state == .resetting) {
    let harness = EngineHarness()
    try harness.engine.queue.sync {
      try harness.engine.startScan()
      harness.engine.stopScan()
      CBCentralManager.latest.state = state
      do {
        if scan {
          try harness.engine.startScan()
        } else {
          try harness.engine.connect(deviceID: harness.peripheral.identifier.uuidString, rate: 8)
        }
        check(false, "unavailable Bluetooth rejects Scan and Connect")
      } catch {
        let failure = WorkoutRideFailure.classify(error, stage: "bike")
        check(failure.code == code, "\(scan ? "Scan" : "Connect") preserves \(code) through bridge classification")
        check(error is WorkoutRideFailure, "Bluetooth refusal carries a typed failure")
      }
      check(CBCentralManager.latest.connections == 0, "Bluetooth refusal cannot start a native connection")
    }
  }
}

for verified in [false, true] {
  for stage in ["services", "characteristics"] {
    for callbackError in [false, true] {
      let harness = EngineHarness(timerDelay: 0)
      try harness.engine.queue.sync {
        if verified {
          try harness.connect()
          harness.advance(to: harness.now + 0.02)
          harness.engine.setWorkoutSamplingOwner("synthetic-ride", rate: 8)
          harness.replies = []
          harness.peripheral.state = .disconnected
          harness.engine.centralManager(
            CBCentralManager.latest, didDisconnectPeripheral: harness.peripheral, error: nil)
          harness.advance(to: harness.now + 1)
        } else {
          CBCentralManager.peripherals = [harness.peripheral]
          try harness.engine.startScan()
          try harness.engine.connect(deviceID: harness.peripheral.identifier.uuidString, rate: 8)
        }
        harness.peripheral.state = .connected
        harness.engine.centralManager(CBCentralManager.latest, didConnect: harness.peripheral)
        let error = callbackError ? NSError(domain: "CBErrorDomain", code: 1) : nil
        if stage == "services" {
          harness.peripheral.services = []
          harness.engine.peripheral(harness.peripheral, didDiscoverServices: error)
        } else {
          let service = CBService(
            uuid: CBUUID(string: "6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
            characteristics: [
              CBCharacteristic(uuid: harness.writer.uuid, properties: []), harness.notifier,
            ])
          harness.peripheral.services = [service]
          harness.engine.peripheral(harness.peripheral, didDiscoverServices: nil)
          harness.engine.peripheral(harness.peripheral, didDiscoverCharacteristicsFor: service, error: error)
        }
        let retry = callbackError || verified
        let label = "\(stage), verified=\(verified), callback error=\(callbackError)"
        check(
          harness.engine.state()["status"] as? String == (retry ? "reconnecting" : "error"),
          "discovery preserves retry versus unsupported hardware: " + label)
        check(
          harness.engine.state()["error"] as? String
            == WorkoutRideFailure(retry ? "bikeReconnecting" : "controllerUnsupported").message,
          "discovery shows the shared reconnecting or unsupported text: " + label)
        let connections = CBCentralManager.latest.connections
        harness.peripheral.state = .disconnected
        harness.engine.centralManager(CBCentralManager.latest, didDisconnectPeripheral: harness.peripheral, error: nil)
        harness.advance(to: harness.now + 3)
        check(
          CBCentralManager.latest.connections == connections + (retry ? 1 : 0),
          "discovery retries exactly once after cancellation: " + label)
        if retry {
          harness.peripheral.state = .connected
          harness.engine.centralManager(CBCentralManager.latest, didConnect: harness.peripheral)
          let service = CBService(
            uuid: CBUUID(string: "6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
            characteristics: [harness.writer, harness.notifier])
          harness.peripheral.services = [service]
          harness.engine.peripheral(harness.peripheral, didDiscoverServices: nil)
          harness.engine.peripheral(harness.peripheral, didDiscoverCharacteristicsFor: service, error: nil)
          harness.engine.peripheral(harness.peripheral, didUpdateNotificationStateFor: harness.notifier, error: nil)
          harness.advance(to: harness.now + 0.05)
          check(
            harness.engine.state()["status"] as? String == "connected", "rediscovery resumes real capture: " + label)
        }
        harness.engine.setWorkoutSamplingOwner(nil, rate: 8)
        try harness.finish()
      }
    }
  }
}

for blocked in [false, true] {
  let harness = EngineHarness(timerDelay: 0)
  try harness.engine.queue.sync {
    try harness.connect()
    harness.advance(to: harness.now + 0.02)
    if blocked {
      harness.peripheral.canSendWriteWithoutResponse = false
      harness.advance(to: harness.now + 3)
    } else {
      harness.respond = false
      harness.replies = []
      harness.advance(to: harness.sent[0] + 0.125)
      harness.now += 2.500001
      harness.notify(telemetry)
    }
    check(
      harness.engine.state()["error"] as? String == WorkoutRideFailure("bikeReconnecting").message,
      "transport errors show the shared reconnecting text")
    let log = try harness.log.read()
    check(log.contains(blocked ? "write_ready_timeout" : "expired_reply"), "diagnostics retain the transport reason")
    try harness.finish()
  }
}

func handshake(_ harness: EngineHarness) {
  harness.peripheral.state = .connected
  harness.engine.centralManager(CBCentralManager.latest, didConnect: harness.peripheral)
  let service = CBService(
    uuid: CBUUID(string: "6e400001-b5a3-f393-e0a9-e50e24dcca9e"), characteristics: [harness.writer, harness.notifier])
  harness.peripheral.services = [service]
  harness.engine.peripheral(harness.peripheral, didDiscoverServices: nil)
  harness.engine.peripheral(harness.peripheral, didDiscoverCharacteristicsFor: service, error: nil)
  harness.engine.peripheral(harness.peripheral, didUpdateNotificationStateFor: harness.notifier, error: nil)
}

func completeCancellation(_ harness: EngineHarness) {
  harness.peripheral.state = .disconnected
  harness.engine.centralManager(CBCentralManager.latest, didDisconnectPeripheral: harness.peripheral, error: nil)
}

func shown(_ harness: EngineHarness) -> (status: String?, error: String?, code: String?) {
  let state = harness.engine.state()
  return (state["status"] as? String, state["error"] as? String, state["errorCode"] as? String)
}

let search = EngineHarness(timerDelay: 0)
try search.engine.queue.sync {
  CBCentralManager.peripherals = [search.peripheral]
  try search.engine.startScan()
  search.advance(to: search.now + 20.5)
  let notFound = shown(search)
  check(
    notFound.status == "error" && notFound.error == WorkoutRideFailure("bikeNotFound").message
      && notFound.code == "bikeNotFound",
    "a search that finds nothing says so at its deadline: \(notFound)")
  try search.engine.startScan()
  check(shown(search).error == nil, "a new search clears the not-found text")
  search.advance(to: search.now + 1)
  search.engine.stopScan()
  search.advance(to: search.now + 20.5)
  check(shown(search).status == "idle" && shown(search).error == nil, "a manual stop ends the search quietly")
  try search.engine.startScan()
  search.engine.centralManager(
    CBCentralManager.latest, didDiscover: search.peripheral, advertisementData: [:], rssi: -50)
  search.advance(to: search.now + 20.5)
  check(shown(search).status == "idle" && shown(search).error == nil, "a search that found a bike ends quietly")
}

let denied = EngineHarness(timerDelay: 0)
try denied.engine.queue.sync {
  try denied.engine.startScan()
  CBCentralManager.latest.state = .unauthorized
  denied.engine.centralManagerDidUpdateState(CBCentralManager.latest)
  let refusal = shown(denied)
  check(
    refusal.status == "error"
      && refusal.error == "Power Log can't use Bluetooth. Allow Bluetooth for Power Log in Settings."
      && refusal.code == "bluetoothDenied",
    "a denied Bluetooth permission shows the shared text with its code: \(refusal)")
}

let switchedOff = EngineHarness(timerDelay: 0)
try switchedOff.engine.queue.sync {
  try switchedOff.engine.startScan()
  CBCentralManager.latest.state = .poweredOff
  switchedOff.engine.centralManagerDidUpdateState(CBCentralManager.latest)
  let off = shown(switchedOff)
  check(
    off.status == "error" && off.error == "Bluetooth is off. Turn on Bluetooth, then search again."
      && off.code == "bluetoothOff",
    "Bluetooth switched off shows the shared text with its code: \(off)")
}

let dropped = EngineHarness(timerDelay: 0)
try dropped.engine.queue.sync {
  try dropped.connect()
  dropped.advance(to: dropped.now + 0.05)
  dropped.respond = false
  dropped.replies = []
  dropped.peripheral.state = .disconnected
  dropped.engine.centralManager(
    CBCentralManager.latest, didDisconnectPeripheral: dropped.peripheral,
    error: NSError(domain: "CBErrorDomain", code: 7))
  let lost = shown(dropped)
  check(
    lost.status == "reconnecting" && lost.error == "Connection to your bike was lost. Reconnecting…" && lost.code == nil
      && dropped.engine.state()["recoverableConnectionError"] as? Bool == true,
    "a lost link shows the shared reconnecting text: \(lost)")
  for delay in [1.0, 2, 4, 8, 16] {
    dropped.advance(to: dropped.now + delay + 0.001)
    dropped.peripheral.state = .disconnected
    dropped.engine.centralManager(CBCentralManager.latest, didFailToConnect: dropped.peripheral, error: nil)
  }
  let stopped = shown(dropped)
  check(
    stopped.status == "error"
      && stopped.error
        == "Power Log couldn't connect to your bike. "
        + "Close other apps connected to the bike, turn its display off and on, then connect again."
      && stopped.code == "bikeUnavailable",
    "reconnects that run out outside a ride show the shared couldn't-connect text: \(stopped)")
  let log = try dropped.log.read()
  check(
    log.contains("link_disconnected") && log.contains("connection_failed") && log.contains("retry_limit"),
    "the log keeps each detailed cause")
}

let unsupported = EngineHarness(timerDelay: 0)
try unsupported.engine.queue.sync {
  unsupported.respond = false
  try unsupported.connect()
  unsupported.notify(framed([111, 5, 3] + Array("X1 20260618".utf8) + [0]))
  let rejected = shown(unsupported)
  check(
    rejected.status == "error"
      && rejected.error == "This isn't a supported controller. Power Log works with CYC X6 and X12."
      && rejected.code == "controllerUnsupported",
    "an unsupported identity shows the shared text: \(rejected)")
  try unsupported.finish()
}

let narrowWrites = EngineHarness(timerDelay: 0)
try narrowWrites.engine.queue.sync {
  narrowWrites.peripheral.maximumWriteLength = 4
  narrowWrites.respond = false
  try narrowWrites.connect()
  let rejected = shown(narrowWrites)
  check(
    rejected.status == "error" && rejected.error == WorkoutRideFailure("controllerUnsupported").message
      && rejected.code == "controllerUnsupported",
    "a link too narrow for the identity request shows the unsupported-controller text: \(rejected)")
  check(try narrowWrites.log.read().contains("write_capacity"), "the log keeps the write-capacity reason")
}

let firmware = EngineHarness(timerDelay: 0)
try firmware.engine.queue.sync {
  try firmware.connect()
  firmware.respond = false
  firmware.replies = []
  var payload = Array(telemetry[2..<56])
  payload[4] ^= 1
  firmware.notify(framed(payload))
  let rejected = shown(firmware)
  check(
    rejected.status == "error" && rejected.error == WorkoutRideFailure("firmwareUnsupported").message
      && rejected.code == "firmwareUnsupported",
    "an unsupported telemetry layout shows the shared firmware text: \(rejected)")
  check(try firmware.log.read().contains("\"rejection\":\"telemetryMask\""), "the log keeps the detailed rejection")
  try firmware.finish()
}

for verified in [false, true] {
  let silent = EngineHarness(timerDelay: 0)
  try silent.engine.queue.sync {
    if verified {
      try silent.connect()
      silent.advance(to: silent.now + 0.05)
      try silent.engine.disconnect()
      completeCancellation(silent)
    } else {
      CBCentralManager.peripherals = [silent.peripheral]
      try silent.engine.startScan()
    }
    silent.respond = false
    silent.replies = []
    try silent.engine.connect(deviceID: silent.peripheral.identifier.uuidString, rate: 8)
    handshake(silent)
    silent.advance(to: silent.now + 2.6)
    check(shown(silent).status == "reconnecting", "a first unanswered identity retries, verified=\(verified)")
    completeCancellation(silent)
    silent.advance(to: silent.now + TelemetryTiming.shared.linkReleaseSeconds + 0.1)
    handshake(silent)
    silent.advance(to: silent.now + 2.6)
    let outcome = shown(silent)
    if verified {
      check(outcome.status == "reconnecting", "a verified controller keeps reconnecting: \(outcome)")
    } else {
      check(
        outcome.status == "error" && outcome.error == WorkoutRideFailure("controllerNoReply").message
          && outcome.code == "controllerNoReply",
        "a device that never answers identity is rejected on its second attempt: \(outcome)")
      check(try silent.log.read().contains("response_timeout"), "the log keeps the identity timeout reason")
    }
    try silent.finish()
  }
}

print("Controller capture: \(checks - failures) passed, \(failures) failed")
if failures > 0 { exit(1) }
