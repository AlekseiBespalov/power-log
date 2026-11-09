import Foundation
import PowerLogCore
import UIKit

final class RecordingHost: NSObject, HostFacade {
  var calls: [String] = []
  var background: (() -> Void)?

  func launch(context: ApplicationLaunchContext, done: @escaping (DomainRefusalDetail?) -> Void) {
    calls.append("launch \(context.reason.name)")
  }
  func foregroundChanged(foreground: Bool) { calls.append("foreground \(foreground)") }
  func didEnterBackground(done: @escaping () -> Void) {
    calls.append("background")
    background = done
  }
  func willTerminate(timeoutUs: Int64) -> Bool {
    calls.append("terminate \(timeoutUs)")
    return true
  }
  var intents: [ApplicationRideCardIntent] = []
  var answer: ApplicationApiFailure?
  func rideCardIntent(intent: ApplicationRideCardIntent, done: @escaping (ApplicationApiFailure?) -> Void) {
    intents.append(intent)
    done(answer)
  }
  func runHealthWork(rideId: String, attempt: Int32, done: @escaping (ApplicationHealthWorkResult) -> Void) {}
}

final class RecordingTasks: BackgroundTasks {
  var begun: [Int] = []
  var ended: [Int] = []
  var expire: (() -> Void)?
  var refuse = false

  func begin(name: String, expired: @escaping () -> Void) -> Int? {
    if refuse { return nil }
    begun.append(begun.count + 7)
    expire = expired
    return begun.last!
  }

  func end(_ task: Int) { ended.append(task) }
}

func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() {
    FileHandle.standardError.write(Data("PowerLogHost: \(message)\n".utf8))
    exit(1)
  }
}

let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
let directories = PowerLogHost.directories()
expect(
  URL(fileURLWithPath: directories.diagnostics, isDirectory: true).standardizedFileURL.path
    == support.appendingPathComponent("PowerLog/diagnostics", isDirectory: true).standardizedFileURL.path,
  "the diagnostic log sits in Application Support/PowerLog/diagnostics")
expect(
  URL(fileURLWithPath: directories.archive).standardizedFileURL.path
    == support.appendingPathComponent("PowerLog").standardizedFileURL.path,
  "the archive sits in Application Support/PowerLog")

let center = NotificationCenter()
let host = RecordingHost()
let tasks = RecordingTasks()
PowerLogHost.forwardLifecycle(to: host, from: center, tasks: tasks)
center.post(name: Notification.Name("UIApplicationDidBecomeActiveNotification"), object: nil)
center.post(name: Notification.Name("UIApplicationWillResignActiveNotification"), object: nil)
expect(host.calls == ["foreground true", "foreground false"], "becoming and resigning active reach foregroundChanged")
center.post(name: Notification.Name("UIApplicationDidEnterBackgroundNotification"), object: nil)
expect(
  host.calls.last == "background" && tasks.begun == [7] && tasks.ended.isEmpty, "the background save runs in a task")
host.background?()
tasks.expire?()
expect(tasks.ended == [7], "the task ends once, when the save is done")
center.post(name: Notification.Name("UIApplicationDidEnterBackgroundNotification"), object: nil)
tasks.expire?()
host.background?()
expect(tasks.ended == [7, 8], "an expired task ends once too")
tasks.refuse = true
center.post(name: Notification.Name("UIApplicationDidEnterBackgroundNotification"), object: nil)
host.background?()
expect(host.calls.last == "background" && tasks.ended == [7, 8], "a refused task still saves in the background")
center.post(name: Notification.Name("UIApplicationWillTerminateNotification"), object: nil)
expect(host.calls.last == "terminate 2000000", "terminating commits within 2 s")

for (reason, expected) in [
  (ApplicationLaunchReason.bluetoothrelaunch, ["prepare", "launch bluetoothRelaunch"]),
  (ApplicationLaunchReason.background, ["launch background"]),
] {
  let launched = RecordingHost()
  PowerLogHost.launch(launched, reason: reason, prepare: { launched.calls.append("prepare") }) { _ in }
  expect(launched.calls == expected, "\(reason.name) launches with \(expected)")
}
let reloaded = NSObject()
let current = NSObject()
let currentView = UIViewController()
AppleUiContext.shared.attach(reloaded) { UIViewController() }
AppleUiContext.shared.attach(current) { currentView }
AppleUiContext.shared.detach(reloaded)
RunLoop.main.run(until: Date().addingTimeInterval(0.05))
expect(AppleUiContext.shared.presenter() === currentView, "a reloaded module's late detach keeps the new provider")
AppleUiContext.shared.detach(current)
RunLoop.main.run(until: Date().addingTimeInterval(0.05))
expect(AppleUiContext.shared.presenter() == nil, "the current module's detach removes its provider")
var outcomes: [RideCardIntentOutcome] = []
let ride = "11111111-1111-4111-8111-111111111111"
PowerLogHost.rideCardIntent(host: host, rideId: ride, token: "t", phase: "running", action: "pause") {
  outcomes.append($0)
}
expect(
  host.intents == [ApplicationRideCardIntent(rideId: ride, token: "t", phase: .running, action: .pause)],
  "a ride-card intent reaches rideCardIntent with its ride, token, phase and action")
host.answer = ApplicationApiFailure(code: .ridechanged, retryable: false, occurrence: nil, detail: nil)
PowerLogHost.rideCardIntent(host: host, rideId: ride, token: "old", phase: "paused", action: "resume") {
  outcomes.append($0)
}
host.answer = ApplicationApiFailure(code: .storagebusy, retryable: true, occurrence: nil, detail: nil)
PowerLogHost.rideCardIntent(host: host, rideId: ride, token: "t", phase: "running", action: "finish") {
  outcomes.append($0)
}
expect(outcomes == [.done, .rideChanged, .failed], "no failure is done, rideChanged is silent, others fail")
PowerLogHost.rideCardIntent(host: host, rideId: ride, token: "t", phase: "finishing", action: "pause") {
  outcomes.append($0)
}
PowerLogHost.rideCardIntent(host: nil, rideId: ride, token: "t", phase: "running", action: "pause") {
  outcomes.append($0)
}
expect(host.intents.count == 3 && outcomes.suffix(2) == [.failed, .failed], "an unknown phase or no runtime fails")
host.answer = nil
var foregrounded: [String] = []
for action in ["pause", "finish"] {
  try await PowerLogHost.performRideCardIntent(
    host: host, rideId: ride, token: "t", phase: "running", action: action,
    continueInForeground: { foregrounded.append(action) })
}
expect(foregrounded == ["finish"], "a Finish without failure continues in the foreground, Pause does not")
host.answer = ApplicationApiFailure(code: .ridechanged, retryable: false, occurrence: nil, detail: nil)
try await PowerLogHost.performRideCardIntent(
  host: host, rideId: ride, token: "old", phase: "running", action: "finish",
  continueInForeground: { foregrounded.append("stale") })
expect(foregrounded == ["finish"], "a stale Finish completes silently without opening the app")
host.answer = ApplicationApiFailure(code: .storagebusy, retryable: true, occurrence: nil, detail: nil)
var threw = false
do {
  try await PowerLogHost.performRideCardIntent(
    host: host, rideId: ride, token: "t", phase: "running", action: "finish",
    continueInForeground: { foregrounded.append("failed") })
} catch is RideCardIntentFailed { threw = true }
expect(threw && foregrounded == ["finish"], "another failure fails the intent")
print("PowerLogHost: 18 assertions passed")
