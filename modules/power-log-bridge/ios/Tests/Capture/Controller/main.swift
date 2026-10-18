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

for state in [CBManagerState.unauthorized, .poweredOff] {
  for scan in [false, true] {
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
        let code = state == .unauthorized ? "bluetoothDenied" : "bikeUnavailable"
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
          harness.engine.setWorkoutSamplingOwner("synthetic-ride", rate: 8)
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
        let message = harness.engine.state()["error"] as? String ?? ""
        check(!message.contains("UART") && !message.contains("properties"), "discovery uses rider wording: " + label)
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
    let message = harness.engine.state()["error"] as? String ?? ""
    check(
      message
        == (blocked
          ? "The bike did not accept a request. Reconnecting…" : "The bike response arrived too late. Reconnecting…"),
      "transport errors use rider wording")
    let log = try harness.log.read()
    check(log.contains(blocked ? "write_ready_timeout" : "expired_reply"), "diagnostics retain the transport reason")
    try harness.finish()
  }
}

print("Controller capture: \(checks - failures) passed, \(failures) failed")
if failures > 0 { exit(1) }
