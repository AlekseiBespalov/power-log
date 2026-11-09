import CoreBluetooth
import Foundation
import PowerLogCore

func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() {
    FileHandle.standardError.write(Data("AppleBleCentral: \(message)\n".utf8))
    exit(1)
  }
}

final class StepClock: NSObject, ApplicationClock {
  var mono: Int64 = 0
  func monoUs() -> Int64 { mono }
  func utcUs() -> Int64 { 1_767_225_600_000_000 + mono }
  func read() -> DomainClockReading { DomainClockReading(monoUs: monoUs(), utcUs: utcUs()) }
}

final class FakeCharacteristic: CharacteristicSeam {
  let uuid: CBUUID
  let properties: CBCharacteristicProperties
  var value: Data?
  var isNotifying = false
  init(uuid: CBUUID, properties: CBCharacteristicProperties) {
    self.uuid = uuid
    self.properties = properties
  }
}

final class FakeService: ServiceSeam {
  let uuid: CBUUID
  let all: [FakeCharacteristic]
  var characteristics: [CharacteristicSeam]?
  init(uuid: CBUUID, all: [FakeCharacteristic]) {
    self.uuid = uuid
    self.all = all
  }
}

final class FakePeripheral: PeripheralSeam {
  enum Operation {
    case services([CBUUID]?)
    case characteristics([CBUUID]?, FakeService)
    case notify(FakeCharacteristic)
  }

  let identifier: UUID
  var name: String?
  var state = CBPeripheralState.disconnected
  var database: [FakeService] = []
  var services: [ServiceSeam]?
  var canSendWriteWithoutResponse = true
  var maximumWriteWithoutResponse = 182
  var notifyFails = false
  var operations: [Operation] = []
  var writes: [Data] = []

  init(identifier: UUID) { self.identifier = identifier }

  func discoverServices(_ services: [CBUUID]?) { operations.append(.services(services)) }

  func discoverCharacteristics(_ characteristics: [CBUUID]?, for service: ServiceSeam) {
    operations.append(.characteristics(characteristics, service as! FakeService))
  }

  func enableNotifications(_ characteristic: CharacteristicSeam) {
    operations.append(.notify(characteristic as! FakeCharacteristic))
  }

  func writeWithoutResponse(_ data: Data, for characteristic: CharacteristicSeam) { writes.append(data) }
}

final class FakeCentral: CentralSeam {
  var state = CBManagerState.unknown
  var scanned: [CBUUID]?
  var retrievable: Set<UUID> = []
  var peripherals: [UUID: FakePeripheral] = [:]
  var calls: [(name: String, peripheral: UUID)] = []
  var connects: [UUID] { calls.filter { $0.name == "connect" }.map(\.peripheral) }

  func scan(services: [CBUUID]) { scanned = services }
  func stopScan() { scanned = nil }
  func retrieve(_ identifier: UUID) -> PeripheralSeam? {
    retrievable.contains(identifier) ? peripheral(identifier) : nil
  }
  func connect(_ peripheral: PeripheralSeam) {
    calls.append(("connect", peripheral.identifier))
    (peripheral as! FakePeripheral).state = .connecting
  }
  func cancel(_ peripheral: PeripheralSeam) {
    calls.append(("cancel", peripheral.identifier))
    (peripheral as! FakePeripheral).state = .disconnected
  }
  func peripheral(_ identifier: UUID) -> FakePeripheral {
    if let known = peripherals[identifier] { return known }
    let created = FakePeripheral(identifier: identifier)
    peripherals[identifier] = created
    return created
  }
}

final class Recorder: NSObject, ApplicationBleEvents {
  var events: [(String, [String: Any])] = []
  var calling = false
  var reentered: String?

  private func record(_ name: String, _ fields: [String: Any]) {
    if calling, reentered == nil { reentered = "\(name) arrived inside the command that caused it" }
    events.append((name, fields))
  }

  func radioChanged(state: ApplicationRadioState) { record("radioChanged", ["state": state.name]) }
  func advertised(advertisement: ApplicationAdvertisement) {
    record(
      "advertised",
      [
        "advertisement": [
          "bike": advertisement.bike, "name": advertisement.name ?? NSNull(),
          "rssi": advertisement.rssi.map { NSNumber(value: $0.int32Value) } ?? NSNull(),
          "services": advertisement.services, "manufacturerData": bytes(advertisement.manufacturerData),
          "at": tree(advertisement.at),
        ] as [String: Any]
      ])
  }
  func searchEnded(error: ApplicationPortError?) { record("searchEnded", ["error": tree(error)]) }
  func linkUp(link: Int64, bike: String) { record("linkUp", ["link": link, "bike": bike]) }
  func linkFailed(link: Int64, error: ApplicationPortError) {
    record("linkFailed", ["link": link, "error": tree(error)])
  }
  func linkDown(link: Int64, error: ApplicationPortError?, systemReconnecting: Bool) {
    record("linkDown", ["link": link, "error": tree(error), "systemReconnecting": systemReconnecting])
  }
  func linkReady(link: Int64, result: ApplicationGattReady) {
    let inventory: Any =
      result.inventory.map { services in
        services.map { service -> [String: Any] in
          [
            "uuid": service.uuid,
            "characteristics": service.characteristics.map { ["uuid": $0.uuid, "properties": $0.properties] },
          ]
        }
      } ?? NSNull()
    record(
      "linkReady",
      [
        "link": link,
        "result": [
          "outcome": result.outcome.name,
          "maxWriteBytes": result.maxWriteBytes.map { NSNumber(value: $0.int32Value) } ?? NSNull(),
          "inventory": inventory, "error": tree(result.error),
        ] as [String: Any],
      ])
  }
  func servicesInvalidated(link: Int64) { record("servicesInvalidated", ["link": link]) }
  func canSend(link: Int64) { record("canSend", ["link": link]) }
  func notified(link: Int64, bytes: KotlinByteArray, at: DomainClockReading) {
    record("notified", ["link": link, "bytes": self.bytes(bytes), "at": tree(at)])
  }
  func notificationFailed(link: Int64, error: ApplicationPortError) {
    record("notificationFailed", ["link": link, "error": tree(error)])
  }
  func writeFailed(link: Int64, error: ApplicationPortError) {
    record("writeFailed", ["link": link, "error": tree(error)])
  }

  private func bytes(_ value: KotlinByteArray?) -> Any {
    value.map { AppleBytes.shared.data(bytes: $0).base64EncodedString() } ?? NSNull()
  }
  private func tree(_ at: DomainClockReading) -> [String: Any] { ["monoUs": at.monoUs, "utcUs": at.utcUs] }
  private func tree(_ error: ApplicationPortError?) -> Any {
    guard let error else { return NSNull() }
    let platform: Any = error.platform.map { ["domain": $0.domain, "code": $0.code] } ?? NSNull()
    return ["kind": error.kind.name, "platform": platform]
  }
}

// Scenario bikes ("D1") become peripheral identifiers and back again in the events.
final class Harness {
  let clock = StepClock()
  let os = FakeCentral()
  var queue: [() -> Void] = []
  lazy var adapter = AppleBleCentral(clock: clock, post: { self.queue.append($0) }, makeCentral: { _ in self.os })
  var current: UUID?

  static func identifier(_ bike: String) -> UUID { UUID(uuidString: "00000000-0000-0000-0000-0000000000\(bike)")! }
  static func bike(_ identifier: String) -> String { String(identifier.suffix(2)) }

  func peripheral(_ bike: String) -> FakePeripheral { os.peripheral(Self.identifier(bike)) }

  func occur(_ name: String, _ args: [String: Any]) {
    func error(_ domain: String) -> NSError? {
      guard let platform = args["platform"] as? [String: Any] else { return nil }
      return NSError(domain: platform["domain"] as? String ?? domain, code: Int(platform["code"] as? String ?? "0")!)
    }
    switch name {
    case "radio":
      let state: CBManagerState =
        switch args["state"] as! String {
        case "poweredOn": .poweredOn
        case "poweredOff": .poweredOff
        case "unsupported": .unsupported
        case "denied": .unauthorized
        case "resetting": .resetting
        default: .unknown
        }
      os.state = state
      adapter.centralStateChanged(state)
    case "remembered":
      os.retrievable.insert(Self.identifier(args["bike"] as! String))
    case "restored":
      let restored = peripheral(args["bike"] as! String)
      restored.state =
        switch args["state"] as! String {
        case "connected": .connected
        case "connecting": .connecting
        default: .disconnected
        }
      adapter.centralRestored([restored])
    case "discovered":
      clock.mono = (args["atMonoUs"] as! NSNumber).int64Value
      var advertisement: [String: Any] = [
        CBAdvertisementDataServiceUUIDsKey: (args["services"] as! [String]).map { CBUUID(string: $0) }
      ]
      if let name = args["name"] as? String { advertisement[CBAdvertisementDataLocalNameKey] = name }
      if let data = args["manufacturerData"] as? String {
        advertisement[CBAdvertisementDataManufacturerDataKey] = Data(base64Encoded: data)!
      }
      adapter.centralDiscovered(
        peripheral(args["bike"] as! String), advertisement: advertisement,
        rssi: (args["rssi"] as? NSNumber)?.intValue ?? 127)
    case "connected":
      let connected = peripheral(args["bike"] as! String)
      connected.state = .connected
      adapter.centralConnected(connected)
    case "connectFailed":
      let failed = peripheral(args["bike"] as! String)
      failed.state = .disconnected
      adapter.centralFailedToConnect(failed, error: error(CBErrorDomain) ?? NSError(domain: CBErrorDomain, code: 1))
    case "disconnected":
      let ended = peripheral(args["bike"] as! String)
      let reconnecting = args["systemReconnecting"] as? Bool ?? false
      ended.state = reconnecting ? .connecting : .disconnected
      adapter.centralDisconnected(ended, reconnecting: reconnecting, error: error(CBErrorDomain))
    case "database":
      let target = peripheral(args["bike"] as! String)
      target.database = (args["services"] as! [[String: Any]]).map { service in
        FakeService(
          uuid: CBUUID(string: service["uuid"] as! String),
          all: (service["characteristics"] as! [[String: Any]]).map {
            FakeCharacteristic(
              uuid: CBUUID(string: $0["uuid"] as! String),
              properties: CBCharacteristicProperties(rawValue: ($0["properties"] as! NSNumber).uintValue))
          })
      }
      if let max = args["maxWriteBytes"] as? NSNumber { target.maximumWriteWithoutResponse = max.intValue }
      target.notifyFails = args["notifyFails"] as? Bool ?? false
    case "servicesModified":
      let target = peripheral(args["bike"] as! String)
      adapter.peripheralModifiedServices(target, invalidated: target.database.map(\.uuid))
    case "notify":
      clock.mono = (args["atMonoUs"] as! NSNumber).int64Value
      let (target, notifier) = notifier()
      notifier.value = Data(base64Encoded: args["bytes"] as! String)!
      adapter.peripheralUpdatedValue(target, characteristic: notifier, error: nil)
    case "notifyError":
      let (target, notifier) = notifier()
      adapter.peripheralUpdatedValue(
        target, characteristic: notifier, error: error(CBATTErrorDomain) ?? NSError(domain: CBATTErrorDomain, code: 14))
    case "writeBusy":
      os.peripheral(current!).canSendWriteWithoutResponse = false
    case "writeReady":
      let target = os.peripheral(current!)
      target.canSendWriteWithoutResponse = true
      adapter.peripheralReadyToSend(target)
    case "writeError":
      let target = os.peripheral(current!)
      let writer = target.database.flatMap(\.all).first { $0.properties.contains(.writeWithoutResponse) }!
      adapter.peripheralWrote(
        target, characteristic: writer, error: error(CBATTErrorDomain) ?? NSError(domain: CBATTErrorDomain, code: 3))
    default:
      expect(false, "iOS has no OS occurrence \(name)")
    }
  }

  private func notifier() -> (FakePeripheral, FakeCharacteristic) {
    let target = os.peripheral(current!)
    return (target, target.database.flatMap(\.all).first { $0.properties.contains(.notify) }!)
  }

  func drain() {
    var progressed = true
    while progressed {
      progressed = false
      while !queue.isEmpty {
        queue.removeFirst()()
        progressed = true
      }
      for target in os.peripherals.values where !target.operations.isEmpty {
        let operation = target.operations.removeFirst()
        progressed = true
        switch operation {
        case .services(let wanted):
          target.services = target.database.filter { wanted?.contains($0.uuid) ?? true }
          adapter.peripheralDiscoveredServices(target, error: nil)
        case .characteristics(let wanted, let service):
          service.characteristics = service.all.filter { wanted?.contains($0.uuid) ?? true }
          adapter.peripheralDiscoveredCharacteristics(target, service: service, error: nil)
        case .notify(let characteristic):
          characteristic.isNotifying = !target.notifyFails
          adapter.peripheralUpdatedNotificationState(
            target, characteristic: characteristic,
            error: target.notifyFails ? NSError(domain: CBATTErrorDomain, code: 3) : nil)
        }
      }
    }
  }
}

func mismatch(_ expected: Any, _ actual: Any) -> String? {
  if expected is NSNull { return actual is NSNull ? nil : "expected null, got \(actual)" }
  if actual is NSNull { return "expected \(expected), got null" }
  if let fields = expected as? [String: Any] {
    guard let actual = actual as? [String: Any] else { return "expected an object, got \(actual)" }
    for (key, value) in fields.sorted(by: { $0.key < $1.key }) {
      guard let field = actual[key] else { return "no field \(key)" }
      if let failure = mismatch(value, field) { return "\(key): \(failure)" }
    }
    return nil
  }
  if let items = expected as? [Any] {
    guard let actual = actual as? [Any] else { return "expected an array, got \(actual)" }
    guard items.count == actual.count else { return "expected \(items.count) items, got \(actual.count)" }
    for (index, item) in items.enumerated() {
      if let failure = mismatch(item, actual[index]) { return "[\(index)] \(failure)" }
    }
    return nil
  }
  if let text = expected as? String { return (actual as? String) == text ? nil : "expected \(text), got \(actual)" }
  if let number = expected as? NSNumber {
    if let flag = actual as? Bool { return number.boolValue == flag ? nil : "expected \(number), got \(flag)" }
    if let value = actual as? Int64 { return number.int64Value == value ? nil : "expected \(number), got \(value)" }
    if let value = actual as? NSNumber { return number == value ? nil : "expected \(number), got \(value)" }
    if let value = actual as? Int32 { return number.int32Value == value ? nil : "expected \(number), got \(value)" }
    return "can't compare \(number) with \(actual)"
  }
  return "can't compare \(expected) with \(actual)"
}

let fixtures = ProcessInfo.processInfo.environment["POWER_LOG_CONTRACT_FIXTURES"] ?? "tests/fixtures/contract"
let document =
  try! JSONSerialization.jsonObject(
    with: Data(contentsOf: URL(fileURLWithPath: fixtures).appendingPathComponent("ports/ble-central.json")))
  as! [String: Any]
let vocabulary = (document["os"] as! [String: [String]]).mapValues(Set.init)
let callVocabulary = Set(document["osCalls"] as? [String] ?? [])
let profiles = (document["profiles"] as! [String: [String: Any]]).mapValues {
  DomainTransportProfile(
    service: $0["service"] as! String, notify: $0["notify"] as! String, write: $0["write"] as! String,
    namePrefixes: $0["namePrefixes"] as! [String])
}
let scenarios = (document["scenarios"] as! [[String: Any]]).filter { ($0["platforms"] as! [String]).contains("ios") }
expect(!scenarios.isEmpty, "the file has iPhone scenarios")

func play(_ steps: [[String: Any]]) -> String? {
  let harness = Harness()
  var checkedCalls = 0
  let recorder = Recorder()
  harness.adapter.attach(bleEvents: recorder)
  for (index, step) in steps.enumerated() {
    let args = step["args"] as? [String: Any] ?? [:]
    let failure: String?
    if let call = step["call"] as? String {
      recorder.calling = true
      let returned: Any? =
        switch call {
        case "prepare": { harness.adapter.prepare() }()
        case "radio": harness.adapter.radio().name
        case "startSearch":
          {
            harness.adapter.startSearch(
              profiles: (args["profiles"] as! [String]).map { profiles[$0]! },
              inventory: args["inventory"] as? Bool ?? false)
          }()
        case "stopSearch": { harness.adapter.stopSearch() }()
        case "connect":
          { () -> Int64 in
            let bike = args["bike"] as! String
            harness.current = Harness.identifier(bike)
            return harness.adapter.connect(
              bike: Harness.identifier(bike).uuidString, profile: profiles[args["profile"] as! String]!,
              adopt: args["adopt"] as? Bool ?? false)
          }()
        case "ready":
          {
            harness.adapter.ready(
              link: (args["link"] as! NSNumber).int64Value, inventory: args["inventory"] as? Bool ?? false)
          }()
        case "send":
          harness.adapter.send(
            link: (args["link"] as! NSNumber).int64Value,
            frame: SuiteTables.shared.decoders.all.first!.request(
              kind: args["request"] as! String == "identity"
                ? DomainControllerRequest.identity : DomainControllerRequest.telemetry)
          ).name
        case "cancel": { harness.adapter.cancel(link: (args["link"] as! NSNumber).int64Value) }()
        case "release": harness.adapter.release(link: (args["link"] as! NSNumber).int64Value)
        default: "unknown command \(call)"
        }
      recorder.calling = false
      harness.drain()
      failure = step["returns"].flatMap { expected in mismatch(expected, returned ?? NSNull()) }
    } else if let name = step["os"] as? String {
      guard let allowed = vocabulary[name] else { return "step \(index + 1): unknown OS occurrence \(name)" }
      let unknown = Set(args.keys).subtracting(allowed)
      if !unknown.isEmpty { return "step \(index + 1): arguments \(unknown) aren't in the vocabulary of \(name)" }
      harness.occur(name, args)
      harness.drain()
      failure = nil
    } else if let name = step["expect"] as? String {
      harness.drain()
      if recorder.events.isEmpty {
        failure = "nothing arrived"
      } else {
        var (event, fields) = recorder.events.removeFirst()
        if event == "advertised" || event == "linkUp" {
          fields = renamed(fields)
        }
        failure = event != name ? "got \(event) \(fields)" : mismatch(args, fields).map { "\($0) in \(fields)" }
      }
    } else if let call = (step["expectOs"] ?? step["expectNoOs"]) as? String {
      harness.drain()
      let bike = args["bike"] as? String
      let calls = harness.os.calls.map { (name: $0.name, bike: Harness.bike($0.peripheral.uuidString)) }
      let found = (checkedCalls..<calls.count).first {
        calls[$0].name == call && (bike == nil || calls[$0].bike == bike)
      }
      if !callVocabulary.contains(call) {
        failure = "unknown OS call \(call)"
      } else if step["expectOs"] != nil {
        if let found {
          checkedCalls = found + 1
          failure = nil
        } else {
          failure = "no OS call \(call) \(args) in \(calls[checkedCalls...])"
        }
      } else if let found {
        failure = "unexpected OS call \(calls[found])"
      } else {
        checkedCalls = calls.count
        failure = nil
      }
    } else if step["expectNone"] as? Bool == true {
      harness.drain()
      failure = recorder.events.isEmpty ? nil : "unexpected \(recorder.events)"
    } else {
      failure = "unknown step"
    }
    if let failure { return "step \(index + 1) \(step): \(failure)" }
    if let reentered = recorder.reentered { return "step \(index + 1) \(step): \(reentered)" }
  }
  return nil
}

func renamed(_ fields: [String: Any]) -> [String: Any] {
  var copy = fields
  if let bike = copy["bike"] as? String { copy["bike"] = Harness.bike(bike) }
  if var advertisement = copy["advertisement"] as? [String: Any], let bike = advertisement["bike"] as? String {
    advertisement["bike"] = Harness.bike(bike)
    copy["advertisement"] = advertisement
  }
  return copy
}

var failures: [String] = []
for scenario in scenarios {
  if let failure = play(scenario["steps"] as! [[String: Any]]) { failures.append("\(scenario["name"]!): \(failure)") }
}
expect(failures.isEmpty, failures.joined(separator: "\n"))

expect(AppleBleCentral.uuid(CBUUID(string: "180A")) == "0000180a-0000-1000-8000-00805f9b34fb", "16-bit UUIDs widen")
expect(
  AppleBleCentral.uuid(CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"))
    == "6e400001-b5a3-f393-e0a9-e50e24dcca9e", "128-bit UUIDs are lowercase")

let search = Harness()
search.adapter.attach(bleEvents: Recorder())
search.adapter.prepare()
search.occur("radio", ["state": "poweredOn"])
search.adapter.startSearch(profiles: [profiles["uart"]!, profiles["uart"]!], inventory: false)
expect(
  search.os.scanned == [CBUUID(string: "6e400001-b5a3-f393-e0a9-e50e24dcca9e")], "a search names each service once")

let connecting = Harness()
connecting.adapter.attach(bleEvents: Recorder())
connecting.adapter.prepare()
connecting.occur("radio", ["state": "poweredOn"])
connecting.occur("remembered", ["bike": "D1"])
_ = connecting.adapter.connect(bike: Harness.identifier("D1").uuidString, profile: profiles["uart"]!, adopt: false)
expect(connecting.os.connects == [Harness.identifier("D1")], "a fresh connect asks the OS once")
print("AppleBleCentral: \(scenarios.count) scenarios and 4 assertions passed")
