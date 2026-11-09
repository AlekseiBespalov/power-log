import Foundation
import PowerLogCore

func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() {
    FileHandle.standardError.write(Data("PowerLogCrashHook: \(message)\n".utf8))
    exit(1)
  }
}

func compose(_ root: URL) -> PowerLogRuntime {
  let ride = DispatchQueue(label: "app.powerlog.ride", qos: .utility, autoreleaseFrequency: .workItem)
  let clock = AppleClock()
  return AppleRuntime.shared.create(
    queues: AppleQueues(
      ride: ride, health: DispatchQueue(label: "app.powerlog.health"),
      maintenance: DispatchQueue(label: "app.powerlog.maintenance")),
    clock: clock,
    ports: Ports(
      permissions: ApplePermissionGate(queue: ride),
      picker: AppleFilePicker(documents: AttachedDocuments.shared, queue: ride), device: AppleDeviceInfo(),
      ble: nil, location: nil, health: nil, keepAlive: nil, tabLock: nil, persistence: nil, rideCard: nil,
      watch: nil, scheduler: nil),
    directories: AppleDirectories(
      archive: root.appendingPathComponent("archive").path, cache: root.appendingPathComponent("cache").path,
      diagnostics: root.appendingPathComponent("diagnostics").path),
    devTools: nil)
}

let arguments = CommandLine.arguments
if arguments.count == 3 && arguments[1] == "crash" {
  _ = compose(URL(fileURLWithPath: arguments[2]))
  DevToolsFactory.shared.crash()
  exit(0)
}

let root = FileManager.default.temporaryDirectory.appendingPathComponent("power-log-crash-hook-\(UUID().uuidString)")
let child = Process()
child.executableURL = URL(fileURLWithPath: arguments[0])
child.arguments = ["crash", root.path]
child.standardError = FileHandle.nullDevice
try child.run()
child.waitUntilExit()
expect(
  child.terminationReason == .uncaughtSignal, "the crashing process ended by a signal, not \(child.terminationStatus)")

let diagnostics = root.appendingPathComponent("diagnostics")
let pending = diagnostics.appendingPathComponent("crash-pending.jsonl")
let lines = ((try? String(contentsOf: pending, encoding: .utf8)) ?? "").split(separator: "\n")
expect(lines.count == 1, "the hook wrote one line before the process ended, not \(lines.count)")
let line = (try? JSONSerialization.jsonObject(with: Data(lines[0].utf8))) as? [String: Any] ?? [:]
expect(line["event"] as? String == "crash", "the line is a crash line: \(line)")
expect(line["exception"] as? String == "IllegalStateException", "the line names the exception: \(line)")
expect((line["image"] as? String)?.count == 36, "the line carries the image's UUID: \(line)")
expect((line["frames"] as? [String])?.isEmpty == false, "the line carries frames: \(line)")

let launched = DispatchSemaphore(value: 0)
compose(root).host.launch(context: ApplicationLaunchContext(reason: .userinterface)) { _ in launched.signal() }
expect(launched.wait(timeout: .now() + 20) == .success, "the next launch answered")
var logged = false
for _ in 0..<100 where !logged {
  let log = (try? String(contentsOf: diagnostics.appendingPathComponent("events.jsonl"), encoding: .utf8)) ?? ""
  logged = log.contains("\"event\":\"crash\"") && !FileManager.default.fileExists(atPath: pending.path)
  if !logged { Thread.sleep(forTimeInterval: 0.1) }
}
expect(logged, "the next launch took the pending crash line into the diagnostic log")
try? FileManager.default.removeItem(at: root)
print("PowerLogCrashHook: the hook kept the crash, and the next launch logged it")
