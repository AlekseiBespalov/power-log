import Foundation
import PowerLogCore

func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() {
    FileHandle.standardError.write(Data("AppleWatchLink: \(message)\n".utf8))
    exit(1)
  }
}

final class StepClock: NSObject, ApplicationClock {
  var mono: Int64 = 0
  func monoUs() -> Int64 { mono }
  func utcUs() -> Int64 { 1_767_225_600_000_000 + mono }
  func read() -> DomainClockReading { DomainClockReading(monoUs: monoUs(), utcUs: utcUs()) }
}

final class FakeSession: WatchSessionSeam {
  var activated = false
  var paired = true
  var appInstalled = true
  var reachable = true
  var activations = 0
  var sent: [(Data, (Data) -> Void, (Error) -> Void)] = []
  func activate() { activations += 1 }
  func send(_ data: Data, reply: @escaping (Data) -> Void, failure: @escaping (Error) -> Void) {
    sent.append((data, reply, failure))
  }
}

final class FakeLauncher: WatchAppLauncher {
  var launches: [(Bool, (Error?) -> Void)] = []
  func launch(indoor: Bool, completion: @escaping (Error?) -> Void) { launches.append((indoor, completion)) }
}

final class Recorded: NSObject, ApplicationWatchLinkEvents {
  var availability: [ApplicationWatchLinkAvailability] = []
  var received: [(Data, ApplicationResponder, Int64)] = []
  func availabilityChanged(availability: ApplicationWatchLinkAvailability) { self.availability.append(availability) }
  func received(message: KotlinByteArray, respond: ApplicationResponder, at: DomainClockReading) {
    received.append((AppleBytes.shared.data(bytes: message), respond, at.monoUs))
  }
}

final class Rig {
  let clock = StepClock()
  let session = FakeSession()
  let launcher = FakeLauncher()
  let events = Recorded()
  var queue: [() -> Void] = []
  lazy var link = AppleWatchLink(
    clock: clock, post: { self.queue.append($0) }, makeSession: { _ in self.session }, launcher: launcher)
  init() { link.attach(watchLinkEvents: events) }
  func drain() {
    while !queue.isEmpty { queue.removeFirst()() }
  }
  func bytes(_ text: String) -> KotlinByteArray { AppleBytes.shared.bytes(data: Data(text.utf8)) }
}

let before = Rig()
expect(!before.link.availability().installed && !before.link.availability().reachable, "nothing before activation")
var failures: [ApplicationPortError] = []
before.link.send(
  message: before.bytes("{}"), reply: ApplicationReply(onOk: { _ in }, onFailed: { failures.append($0) }))
expect(failures.isEmpty, "a refusal arrives after the call returns")
before.drain()
expect(failures.map(\.kind) == [.notreachable], "a send before activation is notReachable at once")
before.link.activate()
before.link.activate()
expect(before.session.activations == 2, "activate asks the session each launch it is called")
expect(!before.link.availability().reachable, "the values stay false until activation completes")

let live = Rig()
live.link.activate()
live.session.activated = true
live.link.sessionChanged()
live.drain()
expect(
  live.events.availability.map { [$0.installed, $0.reachable] } == [[true, true]],
  "activation raises availabilityChanged")
live.session.appInstalled = false
expect(!live.link.availability().installed, "installed needs the Watch app")
live.session.appInstalled = true
live.session.reachable = false
var unreachable: [ApplicationPortError] = []
live.link.send(message: live.bytes("a"), reply: ApplicationReply(onOk: { _ in }, onFailed: { unreachable.append($0) }))
live.drain()
expect(unreachable.map(\.kind) == [.notreachable] && live.session.sent.isEmpty, "unreachable is refused without a send")
live.session.reachable = true
var replies: [String] = []
var errors: [ApplicationPortError] = []
var sink: ApplicationReply<KotlinByteArray> {
  ApplicationReply(
    onOk: { replies.append(String(decoding: AppleBytes.shared.data(bytes: $0!), as: UTF8.self)) },
    onFailed: { errors.append($0) })
}
live.link.send(message: live.bytes("hello"), reply: sink)
expect(String(decoding: live.session.sent[0].0, as: UTF8.self) == "hello", "the bytes go to sendMessageData")
live.session.sent[0].1(Data("answer".utf8))
expect(replies == ["answer"], "the reply's bytes complete the request")
live.link.send(message: live.bytes("lost"), reply: sink)
live.session.sent[1].2(NSError(domain: "WCErrorDomain", code: 7007))
live.link.send(message: live.bytes("late"), reply: sink)
live.session.sent[2].2(NSError(domain: "WCErrorDomain", code: 7012))
expect(errors.map(\.kind) == [.notreachable, .failed], "7007 is notReachable, another code failed")
expect(errors.map { $0.platform?.code } == ["7007", "7012"], "the WatchConnectivity code is kept")
expect(errors.allSatisfy { $0.platform?.domain == "WCErrorDomain" }, "the domain is WCErrorDomain")

var launched: [String] = []
live.link.launchWatchApp(
  indoor: true,
  reply: ApplicationReply(onOk: { _ in launched.append("ok") }, onFailed: { launched.append("\($0.kind)") }))
expect(live.launcher.launches.first?.0 == true, "an indoor ride launches an indoor workout configuration")
live.launcher.launches[0].1(nil)
live.link.launchWatchApp(
  indoor: false,
  reply: ApplicationReply(onOk: { _ in launched.append("ok") }, onFailed: { launched.append($0.kind.name) }))
live.launcher.launches[1].1(NSError(domain: "com.apple.healthkit", code: 1))
expect(launched == ["ok", "launchFailed"], "a launch error is launchFailed: \(launched)")

live.clock.mono = 4_000
var answered: [String] = []
live.link.sessionReceived(Data("ping".utf8)) { answered.append(String(decoding: $0, as: UTF8.self)) }
live.clock.mono = 9_000
expect(live.events.received.isEmpty, "a message is delivered on the ride queue, not inside the callback")
live.drain()
expect(live.events.received.count == 1 && live.events.received[0].2 == 4_000, "a message is stamped on receipt")
let responder = live.events.received[0].1
responder.reply(message: live.bytes("pong"))
responder.reply(message: live.bytes("again"))
expect(answered == ["pong"], "the responder replies exactly once")

live.session.activated = false
live.link.sessionDeactivated()
live.drain()
expect(live.session.activations == 2, "a deactivated session activates again")
expect(live.events.availability.last.map { !$0.installed && !$0.reachable } == true, "deactivation is reported")
let unattached = AppleWatchLink(
  clock: StepClock(), post: { $0() }, makeSession: { _ in FakeSession() }, launcher: FakeLauncher())
var fallback: [Data] = []
unattached.sessionReceived(Data("ping".utf8)) { fallback.append($0) }
expect(fallback == [Data()], "a message with no events attached still gets its one reply")
print("AppleWatchLink: 24 assertions passed")
