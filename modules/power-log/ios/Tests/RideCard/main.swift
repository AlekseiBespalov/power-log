import Foundation
import PowerLogCore

var assertions = 0
func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  assertions += 1
  if !condition() {
    FileHandle.standardError.write(Data("AppleRideCard: \(message)\n".utf8))
    exit(1)
  }
}

final class FakeActivities: RideActivityKit {
  var enabled = true
  var current: [RideActivity] = []
  var refuse = false
  var requests: [PowerLogRideAttributes.ContentState] = []
  var updates: [PowerLogRideAttributes.ContentState] = []
  var pending: [() -> Void] = []
  var ended: [RideActivity] = []
  var observers: [String: () -> Void] = [:]
  var next = 1

  func request(_ content: PowerLogRideAttributes.ContentState) throws -> RideActivity {
    if refuse { throw NSError(domain: "ActivityKit.ActivityAuthorizationError", code: 3) }
    requests.append(content)
    let activity = RideActivity(id: "activity-\(next)", rideID: content.rideID)
    next += 1
    current.append(activity)
    return activity
  }

  func update(_ activity: RideActivity, content: PowerLogRideAttributes.ContentState, done: @escaping () -> Void) {
    updates.append(content)
    pending.append(done)
  }

  func end(_ activity: RideActivity) {
    ended.append(activity)
    current.removeAll { $0 == activity }
  }

  func observe(_ activity: RideActivity, ended: @escaping () -> Void) { observers[activity.id] = ended }

  func finishUpdates() {
    while !pending.isEmpty { pending.removeFirst()() }
  }
}

final class FakeTimer: RideCardTimer {
  var delay: Double?
  var handler: (() -> Void)?
  func replace(after seconds: Double, handler: @escaping () -> Void) {
    delay = seconds
    self.handler = handler
  }
  func cancel() {
    delay = nil
    handler = nil
  }
}

final class Failures: NSObject, ApplicationRideCardEvents {
  var errors: [ApplicationPortError] = []
  func failed(error: ApplicationPortError) { errors.append(error) }
}

let ride = "11111111-1111-4111-8111-111111111111"
let other = "22222222-2222-4222-8222-222222222222"
let token = "66666666-6666-4666-8666-666666666666"
let active = Notification.Name("test.active")
let inactive = Notification.Name("test.inactive")

final class Rig {
  let kit = FakeActivities()
  let timer = FakeTimer()
  let failures = Failures()
  let center = NotificationCenter()
  let defaults: UserDefaults
  let queue = DispatchQueue(label: "app.powerlog.ride.test")
  var mainQueue: [() -> Void] = []
  var nowUs: Int64 = 1_700_000_000_000_000
  lazy var card: AppleRideCard = {
    let card = AppleRideCard(
      kit: kit, timer: timer, defaults: defaults, center: center, active: active, inactive: inactive, queue: queue,
      main: { self.mainQueue.append($0) }, now: { self.nowUs })
    card.attach(rideCardEvents: failures)
    return card
  }()

  init() {
    let suite = "app.powerlog.ridecard.test.\(UUID().uuidString)"
    defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
  }

  func onMain() {
    while !mainQueue.isEmpty { mainQueue.removeFirst()() }
  }

  func front() {
    _ = card
    center.post(name: active, object: nil)
    onMain()
  }

  func back() {
    center.post(name: inactive, object: nil)
    onMain()
  }

  func facts(
    _ state: DomainRideCardState = .running, rideId: String = ride, token: String = token, power: Double? = 200,
    controls: [DomainRideAction] = [.pause, .finish]
  ) -> DomainRideCardFacts {
    DomainRideCardFacts(
      rideId: rideId, token: token, state: state,
      timer: DomainRideTimer(state: .running, baseUs: 10_000_000, originUtcUs: KotlinLong(value: nowUs - 10_000_000)),
      powerW: power.map { KotlinDouble(value: $0) }, heartRateBpm: KotlinDouble(value: 150),
      bikeFreshUntilUtcUs: KotlinLong(value: nowUs + 21_000_000),
      heartRateFreshUntilUtcUs: KotlinLong(value: nowUs + 30_000_000), controls: controls)
  }

  func publish(_ facts: DomainRideCardFacts) {
    card.publish(facts: facts)
    onMain()
  }
}

do {
  let rig = Rig()
  rig.publish(rig.facts())
  expect(rig.kit.requests.isEmpty, "no card starts while the app is not in front")
  rig.front()
  expect(rig.kit.requests.count == 1, "coming to the front starts the card")
  let first = rig.kit.requests[0]
  expect(first.publicationSequence == 1, "a ride's contents are numbered from 1")
  expect(first.rideID == ride && first.controlToken == token, "the content carries the ride and its token")
  expect(first.expectedPhase == "running", "the expected phase is the facts' state")
  expect(first.controls.map(\.action) == ["pause", "finish"], "controls carry their actions")
  expect(first.controls.allSatisfy { $0.expectedPhase == "running" && $0.rideID == ride }, "controls carry identity")
  expect(first.timer.mode == "running", "a running timer counts up")
  expect(first.timer.origin == Date(timeIntervalSince1970: Double(rig.nowUs - 10_000_000) / 1_000_000), "origin")
  expect(
    first.render(at: Date(timeIntervalSince1970: Double(rig.nowUs) / 1_000_000), isStale: false).bike.text == "200",
    "the bike reading shows before its fresh-until instant")
  expect(first.systemStale.status.text == "Open Power Log" && first.systemStale.controls.isEmpty, "stale render")
  expect(
    first.staleDate == Date(timeIntervalSince1970: Double(rig.nowUs + 21_000_000) / 1_000_000),
    "the stale date is the earliest fresh-until instant")
  expect(rig.defaults.string(forKey: AppleRideCard.startedRideKey) == ride, "the started ride is marked")
  expect(rig.timer.delay == 21, "the card re-renders at the earliest fresh-until instant")

  rig.publish(rig.facts(power: 210))
  rig.publish(rig.facts(power: 220))
  rig.publish(rig.facts(power: 230))
  expect(rig.kit.updates.count == 1, "one update is in flight")
  rig.kit.finishUpdates()
  rig.onMain()
  expect(rig.kit.updates.count == 2, "the waiting facts follow once the update settles")
  expect(rig.kit.updates.map(\.publicationSequence) == [2, 3], "the newest replaces the waiting ones")
  expect(
    rig.kit.updates[1].render(at: Date(timeIntervalSince1970: Double(rig.nowUs) / 1_000_000), isStale: false).bike.text
      == "230", "the update in flight is followed by the newest facts")
  rig.kit.finishUpdates()
  rig.onMain()

  rig.nowUs += 21_000_000
  rig.timer.handler?()
  rig.onMain()
  let rerendered = rig.kit.updates.last!
  expect(rig.kit.updates.count == 3, "the fresh-until instant re-renders the card")
  expect(
    rerendered.render(at: Date(timeIntervalSince1970: Double(rig.nowUs) / 1_000_000), isStale: false).bike.text == "—",
    "the re-rendered card shows a dash for the expired reading")
  expect(
    rerendered.staleDate == Date(timeIntervalSince1970: Double(rig.nowUs + 9_000_000) / 1_000_000),
    "the re-rendered card's stale date is the next fresh-until instant")
  rig.kit.finishUpdates()
  rig.onMain()

  rig.kit.observers["activity-1"]?()
  rig.onMain()
  rig.publish(rig.facts(power: 240))
  expect(rig.kit.updates.count == 3 && rig.kit.requests.count == 1, "a dismissed card is neither updated nor recreated")

  rig.publish(rig.facts(rideId: other))
  expect(rig.kit.requests.last?.rideID == other, "a new ride in front gets its card")
  expect(rig.kit.requests.last?.publicationSequence == 1, "a new ride numbers from 1 again")

  rig.kit.current.append(RideActivity(id: "older", rideID: ride))
  rig.card.end()
  rig.onMain()
  expect(rig.kit.current.isEmpty, "end ends every Power Log card, an earlier one's too")
  expect(rig.defaults.string(forKey: AppleRideCard.startedRideKey) == nil, "end clears the marker")
  expect(rig.timer.delay == nil, "end stops the re-render")
}

do {
  let rig = Rig()
  rig.front()
  rig.publish(rig.facts(.starting, controls: []))
  rig.publish(rig.facts(.updating, controls: []))
  expect(rig.kit.requests.isEmpty, "starting and updating start no card")
  rig.kit.enabled = false
  rig.publish(rig.facts())
  expect(rig.kit.requests.isEmpty, "disabled Live Activities start no card")
  rig.kit.enabled = true
  rig.back()
  rig.publish(rig.facts())
  expect(rig.kit.requests.isEmpty, "a card never starts in the background")
}

do {
  let rig = Rig()
  rig.defaults.set(ride, forKey: AppleRideCard.startedRideKey)
  rig.front()
  rig.publish(rig.facts())
  expect(rig.kit.requests.isEmpty, "a ride whose card was started before and is gone is not recreated")
}

do {
  let rig = Rig()
  rig.kit.current = [RideActivity(id: "earlier", rideID: ride), RideActivity(id: "stray", rideID: other)]
  rig.defaults.set(ride, forKey: AppleRideCard.startedRideKey)
  rig.publish(rig.facts(.interrupted, controls: []))
  expect(rig.kit.requests.isEmpty, "an interrupted ride makes no new card")
  expect(rig.kit.updates.count == 1 && rig.kit.updates[0].expectedPhase == "interrupted", "the earlier card updates")
  expect(rig.kit.updates[0].controls.isEmpty, "an interrupted card has no controls")
  expect(rig.kit.ended.map(\.id) == ["stray"], "other rides' cards end")
  rig.kit.observers["earlier"]?()
  rig.onMain()
  rig.publish(rig.facts(.interrupted, power: 210, controls: []))
  expect(rig.kit.updates.count == 1, "an adopted earlier card is observed, and its dismissal stops updates")
}

do {
  let rig = Rig()
  rig.kit.refuse = true
  rig.front()
  rig.publish(rig.facts())
  rig.queue.sync {}
  expect(rig.failures.errors.count == 1, "a refused request reports failed")
  expect(rig.failures.errors[0].kind == .failed, "a refused request is failed")
  expect(rig.failures.errors[0].platform?.domain == "ActivityKit.ActivityAuthorizationError", "with its domain")
  expect(rig.failures.errors[0].platform?.code == "3", "and its code")
  rig.kit.refuse = false
  rig.publish(rig.facts(power: 210))
  expect(rig.kit.requests.isEmpty, "no request follows a failed one until the app comes to the front again")
  rig.back()
  rig.front()
  expect(rig.kit.requests.count == 1, "coming to the front again allows the request")
}

print("AppleRideCard: \(assertions) assertions passed")
