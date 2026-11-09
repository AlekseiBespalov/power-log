import Foundation
import PowerLogCore

func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() {
    FileHandle.standardError.write(Data("AppleRecordingKeepAlive: \(message)\n".utf8))
    exit(1)
  }
}

final class FakeTasks: BackgroundTasks {
  var refuse = false
  var next = 1
  var begun: [String] = []
  var ended: [Int] = []
  var expirations: [Int: () -> Void] = [:]

  func begin(name: String, expired: @escaping () -> Void) -> Int? {
    guard !refuse else { return nil }
    begun.append(name)
    let task = next
    next += 1
    expirations[task] = expired
    return task
  }

  func end(_ task: Int) { ended.append(task) }
}

final class Expiring: NSObject, ApplicationKeepAliveEvents {
  var kinds: [ApplicationHoldKind] = []
  func expiring(kind: ApplicationHoldKind) { kinds.append(kind) }
}

final class Rig {
  let tasks = FakeTasks()
  let events = Expiring()
  let queue = DispatchQueue(label: "app.powerlog.ride.test")
  var mainQueue: [() -> Void] = []
  var answers: [String] = []
  lazy var keepAlive: AppleRecordingKeepAlive = {
    let keepAlive = AppleRecordingKeepAlive(tasks: tasks, queue: queue, main: { self.mainQueue.append($0) })
    keepAlive.attach(keepAliveEvents: events)
    return keepAlive
  }()

  var reply: ApplicationReply<KotlinUnit> {
    ApplicationReply(onOk: { _ in self.answers.append("ok") }, onFailed: { self.answers.append("\($0.kind.name)") })
  }

  func onMain() {
    while !mainQueue.isEmpty { mainQueue.removeFirst()() }
  }

  func onRide() { queue.sync {} }
}

let ride = Rig()
ride.keepAlive.hold(kind: .ride, location: true, reply: ride.reply)
expect(ride.answers.isEmpty, "a hold answers after the call returns, on the main thread")
ride.onMain()
expect(ride.answers == ["ok"], "a ride hold answers ok at once")
expect(ride.tasks.begun.isEmpty, "a ride hold begins no background task")
ride.keepAlive.release(kind: .ride)
ride.onMain()
expect(ride.tasks.ended.isEmpty, "releasing a ride hold ends nothing")

let reconnect = Rig()
reconnect.keepAlive.hold(kind: .reconnect, location: false, reply: reconnect.reply)
reconnect.onMain()
expect(reconnect.answers == ["ok"], "a reconnect hold answers ok once its task began")
expect(reconnect.tasks.begun == ["Power Log bike reconnect"], "the reconnect hold is a named finite task")
reconnect.keepAlive.hold(kind: .reconnect, location: false, reply: reconnect.reply)
reconnect.onMain()
expect(reconnect.tasks.begun.count == 1 && reconnect.answers == ["ok", "ok"], "a held reconnect begins no second task")
reconnect.keepAlive.release(kind: .reconnect)
reconnect.onMain()
expect(reconnect.tasks.ended == [1], "release ends the task")
reconnect.tasks.expirations[1]?()
reconnect.onRide()
expect(reconnect.events.kinds.isEmpty, "an ended task's late expiry sends nothing")

let expiry = Rig()
expiry.keepAlive.hold(kind: .reconnect, location: false, reply: expiry.reply)
expiry.onMain()
expiry.tasks.expirations[1]?()
expiry.onRide()
expect(expiry.events.kinds == [.reconnect], "expiry sends expiring(reconnect)")
expect(expiry.tasks.ended == [1], "expiry ends the task")
expiry.keepAlive.release(kind: .reconnect)
expiry.onMain()
expect(expiry.tasks.ended == [1], "a release after expiry ends nothing more")
expiry.keepAlive.hold(kind: .reconnect, location: false, reply: expiry.reply)
expiry.onMain()
expect(expiry.tasks.begun.count == 2, "a later reconnect hold begins a new task")

let refused = Rig()
refused.tasks.refuse = true
refused.keepAlive.hold(kind: .reconnect, location: false, reply: refused.reply)
refused.onMain()
expect(refused.answers == ["notAllowedInBackground"], "an invalid task answers notAllowedInBackground")
refused.keepAlive.release(kind: .reconnect)
refused.onMain()
expect(refused.tasks.ended.isEmpty, "a refused hold has no task to end")

print("AppleRecordingKeepAlive: 18 assertions passed")
