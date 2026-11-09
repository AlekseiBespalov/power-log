import Foundation
import PowerLogCore

func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() {
    FileHandle.standardError.write(Data("PowerLogHostSmoke: \(message)\n".utf8))
    exit(1)
  }
}

func answer(_ send: (@escaping (String) -> Void) -> Void) -> [String: Any] {
  let answered = DispatchSemaphore(value: 0)
  var text = ""
  send { reply in
    text = reply
    answered.signal()
  }
  expect(answered.wait(timeout: .now() + 20) == .success, "an answer came within 20 s")
  return (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] ?? [:]
}

func connection(_ session: BridgeSession, until wanted: ([String: Any]) -> Bool) -> [String: Any] {
  var after = "null"
  for _ in 0..<50 {
    let envelope = answer {
      session.next(
        stream: "connection.state", request: "{\"params\":{},\"after\":\(after),\"key\":\"smoke\"}", reply: $0)
    }
    expect(envelope["ok"] as? Bool == true, "connection.state answered \(envelope)")
    let value = envelope["value"] as? [String: Any] ?? [:]
    if wanted(value) { return value }
    after = "\(envelope["revision"] ?? "null")"
  }
  expect(false, "connection.state never reached the wanted state")
  return [:]
}

let root = FileManager.default.temporaryDirectory.appendingPathComponent("power-log-host-smoke-\(UUID().uuidString)")
let ride = DispatchQueue(label: "app.powerlog.ride", qos: .utility, autoreleaseFrequency: .workItem)
let queues = AppleQueues(
  ride: ride,
  health: DispatchQueue(label: "app.powerlog.health", qos: .utility, autoreleaseFrequency: .workItem),
  maintenance: DispatchQueue(label: "app.powerlog.maintenance", qos: .background, autoreleaseFrequency: .workItem))
let clock = AppleClock()
let ports = Ports(
  permissions: ApplePermissionGate(queue: ride),
  picker: AppleFilePicker(documents: AttachedDocuments.shared, queue: ride), device: AppleDeviceInfo(),
  ble: nil, location: nil, health: nil, keepAlive: nil, tabLock: nil, persistence: nil, rideCard: nil, watch: nil,
  scheduler: nil)
let switches = DevSwitches(bike: true, longRide: false, slice: false, crashAfterUs: nil, killAfterUs: nil)
let runtime = AppleRuntime.shared.create(
  queues: queues, clock: clock, ports: ports,
  directories: AppleDirectories(
    archive: root.appendingPathComponent("archive").path, cache: root.appendingPathComponent("cache").path,
    diagnostics: root.appendingPathComponent("diagnostics").path),
  devTools: DevToolsFactory.shared.create(switches: { switches }, clock: clock))

let launched = DispatchSemaphore(value: 0)
var refusal: DomainRefusalDetail?
runtime.host.launch(context: ApplicationLaunchContext(reason: .userinterface)) {
  refusal = $0
  launched.signal()
}
expect(launched.wait(timeout: .now() + 20) == .success, "launch answered within 20 s")
expect(refusal == nil, "launch opened the stores, not \(String(describing: refusal))")

let session = runtime.openBridgeSession()
let version = answer { session.call(op: "apiVersion", request: "{}", reply: $0) }
expect((version["value"] as? [String: Any])?["hash"] is String, "apiVersion answered \(version)")
let search = answer { session.call(op: "connection.startSearch", request: "{}", reply: $0) }
expect(search["ok"] as? Bool == true, "startSearch answered \(search)")
_ = connection(session) { state in
  (state["found"] as? [[String: Any]] ?? []).contains { $0["bikeId"] as? String == "power-log-demo-bike" }
}
let connect = answer {
  session.call(op: "connection.connect", request: "{\"bikeId\":\"power-log-demo-bike\"}", reply: $0)
}
expect(connect["ok"] as? Bool == true, "connect answered \(connect)")
let connected = connection(session) { state in
  state["state"] as? String == "connected" && (state["bike"] as? [String: Any])?["identity"] is [String: Any]
}
let identity = (connected["bike"] as? [String: Any])?["identity"] as? [String: Any]
expect(
  identity?["model"] as? String == "X6",
  "the demo bike answered its recorded identity, not \(String(describing: identity))")
session.close()

final class Module { let session = SessionBox() }
var module: Module? = Module()
let box = module!.session
let onDestroy = { box.close() }
box.open(runtime.openBridgeSession())
let opened = box.current
module = nil
onDestroy()
expect(box.current == nil, "OnDestroy's box forgets the session")
let late = DispatchSemaphore(value: 0)
opened?.call(op: "apiVersion", request: "{}") { _ in late.signal() }
expect(late.wait(timeout: .now() + 1) == .timedOut, "the session OnDestroy closed answers nothing")
try? FileManager.default.removeItem(at: root)
print("PowerLogHostSmoke: the composed runtime launched over its stores and connected the demo bike")
