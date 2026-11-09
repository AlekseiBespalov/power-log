import Foundation

func expect(_ condition: @autoclosure () -> Bool, _ message: String, line: Int = #line) {
  if !condition() {
    FileHandle.standardError.write(Data("Watch app (line \(line)): \(message)\n".utf8))
    exit(1)
  }
}

final class ManualTimer: WatchTimer {
  var cancelled = false
  func cancel() { cancelled = true }
}

final class ManualClock: WatchClock {
  var now = Date(timeIntervalSince1970: 1_793_523_600)
  private var timers: [(due: Date, timer: ManualTimer, action: @MainActor () -> Void)] = []

  func after(_ seconds: TimeInterval, _ action: @escaping @MainActor () -> Void) -> WatchTimer {
    let timer = ManualTimer()
    timers.append((now.addingTimeInterval(seconds), timer, action))
    return timer
  }

  @MainActor func advance(_ seconds: TimeInterval) {
    let end = now.addingTimeInterval(seconds)
    while let next = timers.enumerated().filter({ !$0.element.timer.cancelled && $0.element.due <= end })
      .min(by: { $0.element.due < $1.element.due })
    {
      timers.remove(at: next.offset)
      now = max(now, next.element.due)
      next.element.action()
    }
    now = end
    timers.removeAll { $0.timer.cancelled }
  }
}

final class FakePhone: PhoneChannel {
  var reachable = true
  var changed: (() -> Void)?
  var received: ((Data, @escaping (Data) -> Void) -> Void)?
  var sent: [(message: WatchMessage, reply: (Data) -> Void, failure: (Error) -> Void)] = []
  func activate() {}
  func send(_ data: Data, reply: @escaping (Data) -> Void, failure: @escaping (Error) -> Void) {
    guard case .valid(let message) = WatchMessages.decode(data) else { fatalError("the Watch sent an invalid message") }
    sent.append((message, reply, failure))
  }

  func deliver(_ message: WatchMessage) -> WatchMessage? {
    var answer: WatchMessage?
    received?(WatchMessages.encode(message)!) { data in
      if case .valid(let decoded) = WatchMessages.decode(data) { answer = decoded }
    }
    return answer
  }

  func last(_ kind: String) -> Int? { sent.lastIndex { $0.message.kind == kind } }
  func answer(_ index: Int, _ message: WatchMessage) { sent[index].reply(WatchMessages.encode(message)!) }
}

final class FakeWorkout: WatchWorkout {
  var onHeartRateReceived: ((LiveHeartRate) -> Void)?
  var onSamplesCollected: (([HealthOriginal]) -> Void)?
  var calls: [String] = []
  var authorizations: [(Error?) -> Void] = []
  var begins: [(HealthPlanWorkout, Bool, Bool, (Error?) -> Void)] = []
  var adds: [([HealthPlanQuantity], (Error?) -> Void)] = []
  var laps: [(Date, (Error?) -> Void)] = []
  var finishes: [(Date, HealthPlanRoute?, (Result<(String, Bool), Error>) -> Void)] = []
  var recoveries: [(String?) -> Void] = []

  func authorize(share: Bool, completion: @escaping (Error?) -> Void) {
    calls.append("authorize \(share)")
    authorizations.append(completion)
  }
  func begin(_ workout: HealthPlanWorkout, indoor: Bool, route: Bool, completion: @escaping (Error?) -> Void) {
    calls.append("begin")
    begins.append((workout, indoor, route, completion))
  }
  func pause() { calls.append("pause") }
  func resume() { calls.append("resume") }
  func add(_ quantities: [HealthPlanQuantity], completion: @escaping (Error?) -> Void) {
    calls.append("add \(quantities.count)")
    adds.append((quantities, completion))
  }
  func addLap(at date: Date, completion: @escaping (Error?) -> Void) {
    calls.append("lap")
    laps.append((date, completion))
  }
  func finish(at date: Date, route: HealthPlanRoute?, completion: @escaping (Result<(String, Bool), Error>) -> Void) {
    calls.append("finish")
    finishes.append((date, route, completion))
  }
  func discard(at date: Date) { calls.append("discard") }
  func recoverOrphan(completion: @escaping (String?) -> Void) {
    calls.append("recoverOrphan")
    recoveries.append(completion)
  }
}

final class MemoryStore: WatchOptionsStore {
  var saved: WatchOptions?
  func load() -> WatchOptions { saved ?? .defaults }
  func save(_ options: WatchOptions) { saved = options }
}

let contractFixtures = URL(
  fileURLWithPath: ProcessInfo.processInfo.environment["POWER_LOG_CONTRACT_FIXTURES"] ?? "tests/fixtures/contract")
let rideA = "11111111-1111-4111-8111-111111111111"
let rideB = "22222222-2222-4222-8222-222222222222"

@MainActor final class Rig {
  let clock = ManualClock()
  let phone = FakePhone()
  let workout = FakeWorkout()
  let store = MemoryStore()
  lazy var controller = WatchRideController(phone: phone, workout: workout, clock: clock, store: store)
  var nowUs: Int64 { HealthInstant.us(clock.now) }

  init() { _ = controller }

  func start(
    _ rideID: String = rideA, event: String = "e-1", request: String? = nil, saving: Bool = true, gps: Bool = true,
    expiresIn: Int64 = 15_000_000
  ) -> StartMessage {
    StartMessage(
      eventID: event, requestID: request, rideID: rideID, startedUtcUs: nowUs, expiresUtcUs: nowUs + expiresIn,
      options: WatchRideOptions(indoor: false, saveToHealth: saving, recordGps: gps, distanceSource: .phoneGps),
      workout: WorkoutIDs(syncID: "powerlog:\(rideID):workout", syncVersion: 1))
  }

  func ride(
    _ rideID: String = rideA, revision: Int64, phase: WatchPhase = .running, laps: [Int64] = [],
    quantities: [HealthPlanQuantity] = [], stride: Int = 1, route: [IndexedRoutePoint] = [], ended: Int64? = nil,
    display: WatchDisplay? = nil, units: UnitSystem = .metric
  ) -> RideMessage {
    RideMessage(
      rideID: rideID, stateRevision: revision, phase: phase,
      timer: RideTimer(state: phase == .running ? .running : .frozen, baseUs: 1_000_000, originUtcUs: nowUs),
      lapsUtcUs: laps, lapCount: laps.count, units: units, display: display, quantities: quantities,
      routeStride: stride, route: route, endedUtcUs: ended)
  }

  @discardableResult func running(saving: Bool = true, gps: Bool = true) -> WatchMessage? {
    var answer: WatchMessage?
    phone.received?(WatchMessages.encode(.start(start(saving: saving, gps: gps)))!) { data in
      if case .valid(let decoded) = WatchMessages.decode(data) { answer = decoded }
    }
    workout.authorizations.last?(nil)
    workout.begins.last?.3(nil)
    _ = phone.deliver(.ride(ride(revision: 1)))
    return answer
  }
}

func ack(_ error: String? = nil, _ sequence: Int64? = nil) -> WatchMessage {
  .ack(AckMessage(error: error, acknowledgedSequence: sequence))
}

func point(_ index: Int64, _ at: Date) -> IndexedRoutePoint {
  IndexedRoutePoint(
    index: index,
    point: RoutePoint(
      at: at, latitude: Double(index), longitude: 0, altitudeM: nil, horizontalAccuracyM: 5, verticalAccuracyM: nil,
      speedMps: nil, courseDeg: nil))
}

MainActor.assumeIsolated {
  // V1: a start past its expiry starts no session.
  let expired = Rig()
  expired.controller.activate()
  let stale = expired.start(expiresIn: -1)
  expect(expired.phone.deliver(.start(stale)) == ack("startExpired"), "a late start is startExpired")
  expect(!expired.workout.calls.contains("begin"), "no session for a late start")

  // A start runs authorize, then begin with the workout ids, and answers ack once the session runs.
  let starting = Rig()
  var startAnswer: WatchMessage?
  starting.phone.received?(WatchMessages.encode(.start(starting.start(request: nil)))!) { data in
    if case .valid(let decoded) = WatchMessages.decode(data) { startAnswer = decoded }
  }
  expect(
    starting.workout.calls == ["recoverOrphan", "authorize true"] || starting.workout.calls == ["authorize true"],
    "authorize(share: true) first: \(starting.workout.calls)")
  expect(starting.controller.facts.phase == .preparing, "a start before its first ride is preparing")
  starting.workout.authorizations[0](nil)
  let begun = starting.workout.begins[0]
  expect(begun.0.syncID == "powerlog:\(rideA):workout" && begun.0.rideID == rideA, "the workout carries its ids")
  expect(begun.2, "an outdoor GPS ride saving to Health records a route")
  expect(startAnswer == nil, "no reply before the session runs")
  begun.3(nil)
  expect(startAnswer == ack(), "ack once the session runs")
  expect(starting.phone.deliver(.start(starting.start())) == ack(), "a repeated start gets the cached reply")

  // V3: a start in progress is joined, a running session refuses another, a tombstoned start is cancelled.
  let joined = Rig()
  var replies: [WatchMessage?] = [nil, nil]
  for index in 0..<2 {
    joined.phone.received?(WatchMessages.encode(.start(joined.start()))!) { data in
      if case .valid(let decoded) = WatchMessages.decode(data) { replies[index] = decoded }
    }
  }
  joined.workout.authorizations[0](nil)
  joined.workout.begins[0].3(nil)
  expect(replies == [ack(), ack()], "a start for the Start in progress joins it: \(replies)")
  expect(joined.workout.begins.count == 1, "one session for both")
  expect(
    joined.phone.deliver(.start(joined.start(rideB, event: "e-2"))) == ack("watchBusy"), "another start is watchBusy")

  // V2: another ride gets a plain ack; a lower revision only updates display and units.
  let current = Rig()
  current.running()
  expect(current.controller.facts.phase == .running, "the first ride runs the ride")
  expect(current.phone.deliver(.ride(current.ride(rideB, revision: 99, phase: .paused))) == ack(), "plain ack")
  expect(current.controller.facts.phase == .running, "another ride changes nothing")
  let display = WatchDisplay(
    powerW: 180, cadenceRpm: 80, distanceM: 1234.56, freshUntilUtcUs: current.nowUs + 6_000_000)
  _ = current.phone.deliver(.ride(current.ride(revision: 0, phase: .paused, display: display, units: .imperial)))
  expect(current.controller.facts.phase == .running, "a lower revision's phase is ignored")
  expect(
    current.controller.facts.display == display && current.controller.facts.units == .imperial,
    "display and units apply from any revision")
  _ = current.phone.deliver(.ride(current.ride(revision: 2, phase: .paused)))
  expect(current.controller.facts.phase == .paused && current.workout.calls.last == "pause", "paused pauses")
  _ = current.phone.deliver(.ride(current.ride(revision: 3, phase: .running, laps: [10, 20])))
  _ = current.phone.deliver(.ride(current.ride(revision: 4, phase: .running, laps: [10, 20])))
  expect(current.workout.calls.filter { $0 == "resume" }.count == 1, "running after paused resumes once")
  expect(current.workout.laps.count == 1, "laps are written one at a time")
  current.workout.laps[0].1(nil)
  expect(current.workout.laps.count == 2 && current.controller.facts.lapCount == 2, "each lap is added once")

  // V4: a Watch startRequest unanswered for 15 s.
  let request = Rig()
  request.controller.activate()
  request.controller.start()
  guard case .startRequest(let sentRequest)? = request.phone.sent.last?.message else {
    expect(false, "start sends startRequest")
    exit(1)
  }
  expect(
    sentRequest.options == WatchStartOptions(indoor: false, saveToHealth: true, recordGps: nil), "auto GPS is null")
  expect(request.controller.facts.phase == .preparing, "a waiting startRequest is preparing")
  request.clock.advance(15)
  expect(request.controller.facts.issue == .failure("startUnconfirmed"), "unanswered: startUnconfirmed")
  expect(request.controller.facts.phase == .idle, "and idle without a start")
  let arrived = Rig()
  arrived.controller.start()
  let requestID = { () -> String in
    if case .startRequest(let message)? = arrived.phone.sent.last?.message { return message.requestID }
    return ""
  }()
  _ = arrived.phone.deliver(.start(arrived.start(request: requestID)))
  arrived.clock.advance(15)
  expect(arrived.controller.facts.phase == .preparing, "with the start arrived the phase is the ride's")
  expect(arrived.controller.facts.issue == nil, "no failure once the start arrived")

  // An unreachable phone at Start.
  let away = Rig()
  away.phone.reachable = false
  away.controller.start()
  expect(away.controller.facts.issue == .phoneUnreachableAtStart && away.phone.sent.isEmpty, "Start needs the phone")
  expect(away.controller.facts.available[.start]?.enabled == true, "an unreachable phone doesn't disable Start")

  // V5 and rule 9: Cancel Start tombstones both ids and resends cancelStart until acknowledged.
  let cancelled = Rig()
  cancelled.controller.start()
  let cancelledRequest = { () -> String in
    if case .startRequest(let message)? = cancelled.phone.sent.last?.message { return message.requestID }
    return ""
  }()
  cancelled.controller.cancelStart()
  expect(cancelled.controller.facts.phase == .idle, "Cancel Start returns to idle")
  let cancel = cancelled.phone.last("cancelStart")!
  expect(
    cancelled.phone.sent[cancel].message
      == .cancelStart(CancelStartMessage(eventID: nil, requestID: cancelledRequest, rideID: nil)),
    "cancelStart names the request")
  expect(
    cancelled.phone.deliver(.start(cancelled.start(request: cancelledRequest))) == ack("startCancelled"),
    "the phone's start meets the tombstone")
  cancelled.controller.tick()
  expect(cancelled.phone.sent.filter { $0.message.kind == "cancelStart" }.count == 2, "resent until acknowledged")
  cancelled.phone.answer(cancelled.phone.last("cancelStart")!, ack())
  cancelled.controller.tick()
  expect(cancelled.phone.sent.filter { $0.message.kind == "cancelStart" }.count == 2, "acknowledged: no more")

  // V6: an unanswered Pause frees the controls; Finish unanswered or unreachable ends locally.
  let command = Rig()
  command.running()
  command.controller.pause()
  expect(command.controller.facts.available[.lap] == .disabled(.commandPending), "a pending command holds controls")
  command.clock.advance(5)
  expect(command.controller.facts.issue == .commandUnconfirmed, "unanswered Pause: commandUnconfirmed")
  expect(command.controller.facts.available[.pause]?.enabled == true, "controls are free again")
  command.controller.finish(rideID: rideA, discard: false)
  command.clock.advance(5)
  expect(command.controller.facts.phase == .completed, "an unanswered Finish ends on the Watch")
  expect(command.controller.facts.issue == .endedLocallyUnconfirmed, "with its text")
  expect(command.workout.calls.last == "finish", "and saves the workout it has")
  let unreachable = Rig()
  unreachable.running(saving: false)
  unreachable.phone.reachable = false
  unreachable.controller.finish(rideID: rideA, discard: true)
  expect(unreachable.controller.facts.issue == .endedLocallyUnreachable, "unreachable at the tap ends locally")
  expect(unreachable.workout.calls.last == "discard", "Discard discards")
  let answered = Rig()
  answered.running()
  answered.controller.lap()
  answered.phone.answer(answered.phone.last("command")!, .ride(answered.ride(revision: 2, laps: [5])))
  expect(answered.controller.facts.lapCount == 1 && answered.controller.facts.issue == nil, "the reply's ride applies")
  answered.controller.pause()
  answered.phone.answer(answered.phone.last("command")!, ack("actionUnavailable"))
  expect(answered.controller.facts.issue == .failure("actionUnavailable"), "an error shows its code")

  // V7: terminal rides.
  let finished = Rig()
  finished.running()
  let endedUs = finished.nowUs + 60_000_000
  _ = finished.phone.deliver(.ride(finished.ride(revision: 2, phase: .completed, ended: endedUs)))
  expect(finished.workout.finishes.count == 1, "a terminal completed ride finishes the workout")
  expect(HealthInstant.us(finished.workout.finishes[0].0) == endedUs, "at endedUtcUs")
  expect(finished.controller.facts.healthOutcome == .pending, "pending while saving")
  finished.workout.finishes[0].2(.success(("w-1", true)))
  guard case .healthOutcome(let outcome)? = finished.phone.sent.last?.message else {
    expect(false, "the outcome is reported")
    exit(1)
  }
  expect(outcome.outcome == .saved && outcome.workoutID == "w-1", "saved with the workout id")
  expect(finished.controller.facts.healthOutcome == .saved, "the Watch shows saved")
  finished.controller.tick()
  expect(finished.phone.sent.filter { $0.message.kind == "healthOutcome" }.count == 2, "resent until acknowledged")
  finished.phone.answer(finished.phone.last("healthOutcome")!, ack("storageBusy"))
  finished.controller.tick()
  expect(finished.phone.sent.filter { $0.message.kind == "healthOutcome" }.count == 3, "an error isn't acknowledgement")
  finished.phone.answer(finished.phone.last("healthOutcome")!, ack())
  finished.controller.tick()
  expect(finished.phone.sent.filter { $0.message.kind == "healthOutcome" }.count == 3, "acknowledged")
  let discarded = Rig()
  discarded.running()
  _ = discarded.phone.deliver(.ride(discarded.ride(revision: 2, phase: .discarded)))
  expect(
    discarded.workout.calls.last == "discard" && discarded.phone.last("healthOutcome") == nil,
    "discarded: nothing saved, nothing reported")
  expect(discarded.controller.facts.phase == .idle, "a discarded ride is idle")
  let healthOff = Rig()
  healthOff.running(saving: false)
  _ = healthOff.phone.deliver(.ride(healthOff.ride(revision: 2, phase: .completed, ended: healthOff.nowUs)))
  guard case .healthOutcome(let off)? = healthOff.phone.sent.last?.message else {
    expect(false, "Health off reports")
    exit(1)
  }
  expect(healthOff.workout.calls.last == "discard" && off.outcome == .notRequested, "Health off: notRequested")

  // V8: begin failures.
  for (domain, code, expected) in [
    ("com.apple.healthkit", 4, "healthDenied"), ("com.apple.healthkit", 5, "healthDenied"),
    ("com.apple.healthkit", 6, "healthLocked"), ("com.apple.healthkit", 1, "healthUnavailable"),
    ("NSCocoaErrorDomain", 9, "startInterrupted"),
  ] {
    let failing = Rig()
    var answer: WatchMessage?
    failing.phone.received?(WatchMessages.encode(.start(failing.start()))!) { data in
      if case .valid(let decoded) = WatchMessages.decode(data) { answer = decoded }
    }
    failing.workout.authorizations[0](nil)
    failing.workout.begins[0].3(NSError(domain: domain, code: code))
    expect(answer == ack(expected), "\(domain) \(code) is \(expected): \(String(describing: answer))")
    expect(failing.controller.facts.phase == .idle, "a failed start leaves no ride")
  }
  expect(
    WatchRideController.startFailure(WorkoutSessionError.unavailable) == "healthUnavailable",
    "WorkoutSession's unavailable is healthUnavailable")
  expect(WatchRideController.startFailure(WorkoutSessionError.busy) == "startInterrupted", "busy is startInterrupted")
  let abandoned = Rig()
  var abandonedAnswer: WatchMessage?
  abandoned.phone.received?(WatchMessages.encode(.start(abandoned.start()))!) { data in
    if case .valid(let decoded) = WatchMessages.decode(data) { abandonedAnswer = decoded }
  }
  abandoned.workout.authorizations[0](nil)
  abandoned.clock.advance(10)
  expect(abandonedAnswer == ack("startUnconfirmed"), "an abandoned begin is startUnconfirmed")
  abandoned.workout.begins[0].3(nil)
  expect(abandoned.workout.calls.last == "discard", "a late session is ended")
  let late = Rig()
  var lateAnswer: WatchMessage?
  late.phone.received?(WatchMessages.encode(.start(late.start(expiresIn: 2_000_000)))!) { data in
    if case .valid(let decoded) = WatchMessages.decode(data) { lateAnswer = decoded }
  }
  late.workout.authorizations[0](nil)
  late.clock.advance(3)
  late.workout.begins[0].3(nil)
  expect(lateAnswer == ack("startExpired") && late.workout.calls.last == "discard", "began after expiry: ended")

  // V9: an abandoned add stops quantities and laps; finish still runs, unknown(ambiguous).
  let quantity = HealthPlanQuantity(syncID: "q", quantity: "rider-power", value: 1, start: Date(), end: Date())
  let stuck = Rig()
  stuck.running()
  _ = stuck.phone.deliver(.ride(stuck.ride(revision: 2, quantities: [quantity])))
  _ = stuck.phone.deliver(.ride(stuck.ride(revision: 3, quantities: [quantity])))
  stuck.clock.advance(10)
  _ = stuck.phone.deliver(.ride(stuck.ride(revision: 4, laps: [1], quantities: [quantity])))
  expect(stuck.workout.adds.count == 1 && stuck.workout.laps.isEmpty, "no write after an abandoned add")
  _ = stuck.phone.deliver(.ride(stuck.ride(revision: 5, phase: .completed, ended: stuck.nowUs)))
  stuck.workout.finishes[0].2(.success(("w-2", false)))
  guard case .healthOutcome(let uncertain)? = stuck.phone.sent.last?.message else {
    expect(false, "the outcome is reported")
    exit(1)
  }
  expect(
    uncertain.outcome == .unknown && uncertain.reason == .ambiguous && uncertain.workoutID == "w-2",
    "unknown(ambiguous) with the saved id")
  let flood = Rig()
  flood.running()
  for revision in 2...35 {
    _ = flood.phone.deliver(.ride(flood.ride(revision: Int64(revision), quantities: [quantity])))
  }
  for _ in 0..<40 { flood.workout.adds.last?.1(nil) }
  expect(flood.workout.adds.count == 1, "the 34th write is abandoned, so no waiting quantity is written after it")
  _ = flood.phone.deliver(.ride(flood.ride(revision: 40, phase: .completed, ended: flood.nowUs)))
  flood.workout.finishes.last?.2(.success(("w-3", true)))
  guard case .healthOutcome(let flooded)? = flood.phone.sent.last?.message else {
    expect(false, "the flooded ride reports")
    exit(1)
  }
  expect(flood.workout.finishes.count == 1 && flooded.outcome == .unknown, "finish runs, unknown(ambiguous)")

  // V10: route stride.
  let route = Rig()
  route.running()
  let at = route.clock.now
  _ = route.phone.deliver(.ride(route.ride(revision: 2, route: [point(1, at), point(2, at), point(3, at)])))
  _ = route.phone.deliver(.ride(route.ride(revision: 3, stride: 2, route: [point(4, at), point(5, at)])))
  _ = route.phone.deliver(
    .ride(route.ride(revision: 4, phase: .completed, stride: 2, route: [point(7, at)], ended: route.nowUs)))
  let kept = route.workout.finishes[0].1?.points.map { Int64($0.latitude) }
  expect(kept == [2, 4, 7], "off-stride points dropped, the terminal point added: \(String(describing: kept))")
  let identity =
    try! JSONSerialization.jsonObject(
      with: Data(contentsOf: contractFixtures.appendingPathComponent("health/identity.json"))) as! [String: Any]
  let canonical = (identity["cases"] as! [[String: Any]]).first { $0["name"] as? String == "canonical-ids" }!
  let canonicalRide = (canonical["input"] as! [String: Any])["rideId"] as! String
  expect(
    WatchRideController.routeSyncID(canonicalRide) == (canonical["expected"] as! [String: Any])["route"] as? String,
    "the route id is the domain's canonical one")
  expect(route.workout.finishes[0].1?.syncID == WatchRideController.routeSyncID(rideA), "the route carries it")

  // V11: the sample buffer.
  var buffer = HealthSampleBuffer()
  let origin = Date(timeIntervalSince1970: 1_000)
  func sample(_ id: String, sync: String? = nil, origin: String = "app") -> HealthOriginal {
    HealthOriginal(
      sampleID: id, member: nil, metric: "heartRateBpm", value: 120, start: Date(), end: Date(),
      dataOrigin: origin, syncID: sync)
  }
  buffer.append([sample("own", sync: "powerlog:x"), sample("a"), sample("b")], at: origin)
  expect(buffer.page()?.samples.map(\.sampleID) == ["a", "b"] && buffer.page()?.through == 2, "own samples dropped")
  buffer.acknowledge(through: 1)
  expect(buffer.page()?.samples.map(\.sampleID) == ["b"], "acknowledged entries leave")
  buffer.trim(at: origin.addingTimeInterval(61))
  expect(buffer.isEmpty && buffer.gaps == 1, "entries older than 60 s are counted gaps")
  buffer.append((0..<2_050).map { sample("s\($0)") }, at: origin)
  expect(buffer.gaps == 3, "beyond 2,048 the oldest go")
  var heavy = HealthSampleBuffer()
  heavy.append([sample("huge", origin: String(repeating: "x", count: 600_000))], at: origin)
  expect(heavy.isEmpty && heavy.gaps == 1, "an entry over 512 KiB is dropped")
  heavy.append((0..<6).map { sample("m\($0)", origin: String(repeating: "y", count: 100_000)) }, at: origin)
  expect(heavy.gaps == 2, "beyond 512 KiB the oldest go")

  // Health samples and live heart rate reach the phone and are acknowledged.
  let samples = Rig()
  samples.running()
  samples.workout.onSamplesCollected?([sample("a"), sample("b")])
  samples.workout.onHeartRateReceived?(LiveHeartRate(bpm: 131, at: samples.clock.now))
  samples.controller.tick()
  guard let samplesIndex = samples.phone.last("healthSamples"),
    case .healthSamples(let sentSamples) = samples.phone.sent[samplesIndex].message
  else {
    expect(false, "healthSamples sent")
    exit(1)
  }
  expect(
    sentSamples.throughSequence == 2 && sentSamples.samples.count == 2 && sentSamples.heartRate?.bpm == 131,
    "samples and heart rate")
  expect(samples.controller.facts.heartRate?.bpm == 131, "the facts carry the newest heart rate")
  samples.phone.answer(samplesIndex, ack(nil, 2))
  samples.controller.tick()
  expect(samples.phone.sent.filter { $0.message.kind == "healthSamples" }.count == 1, "nothing left to send")

  // V12: after the workout ended, and an orphan at launch.
  let after = Rig()
  after.running(saving: false)
  after.phone.reachable = false
  after.controller.finish(rideID: rideA, discard: false)
  let callsAfterEnd = after.workout.calls
  expect(after.phone.deliver(.ride(after.ride(revision: 9, phase: .paused, laps: [3]))) == ack(), "acknowledged")
  expect(after.workout.calls == callsAfterEnd, "no session call after the workout ended")
  expect(after.controller.facts.phase == .completed, "only a terminal phase applies")
  let orphan = Rig()
  orphan.controller.activate()
  orphan.workout.recoveries[0](rideB)
  expect(orphan.controller.facts.phase == .interrupted && orphan.controller.facts.rideID == rideB, "the orphan's ride")
  expect(orphan.controller.facts.issue == .orphanRecovered, "with its text")
  expect(orphan.controller.facts.available[.finish]?.enabled == true, "Finish enabled")
  expect(orphan.controller.facts.available[.discard]?.enabled == true, "Discard enabled")
  expect(orphan.controller.facts.available[.start] == .disabled(.sessionBusy), "no Start")
  _ = orphan.phone.deliver(.ride(orphan.ride(rideB, revision: 3, phase: .paused, laps: [4])))
  expect(
    orphan.controller.facts.phase == .paused && orphan.controller.facts.lapCount == 1,
    "an orphan's non-terminal ride still sets phase and laps")
  orphan.phone.reachable = false
  orphan.controller.finish(rideID: rideB, discard: false)
  expect(orphan.workout.calls == ["recoverOrphan"], "ending an orphan locally calls no session")
  let quiet = Rig()
  quiet.controller.activate()
  quiet.clock.advance(10)
  quiet.workout.recoveries[0](rideB)
  expect(quiet.controller.facts.phase == .idle, "an abandoned recoverOrphan reports none")

  // Rules 1 and 2.
  let rules = Rig()
  var mismatch: WatchMessage?
  rules.phone.received?(Data(#"{"v":2,"kind":"ride"}"#.utf8)) { data in
    if case .valid(let decoded) = WatchMessages.decode(data) { mismatch = decoded }
  }
  expect(mismatch == ack("watchVersionMismatch"), "another version")
  var invalid: WatchMessage?
  rules.phone.received?(Data(#"{"v":1,"kind":"ride"}"#.utf8)) { data in
    if case .valid(let decoded) = WatchMessages.decode(data) { invalid = decoded }
  }
  expect(invalid == ack("watchMessageInvalid"), "an invalid message")

  // Options are stored by the controller.
  let options = Rig()
  options.controller.setOptions(WatchOptions(indoor: true, saveToHealth: false, gps: .off))
  expect(options.store.saved == WatchOptions(indoor: true, saveToHealth: false, gps: .off), "options saved")
  expect(options.controller.options.recordGps == false, "GPS off is recordGps false")

  // A ride the phone ends while the Watch is starting leaves no session and frees Start.
  for duringBegin in [true, false] {
    let ended = Rig()
    var answer: WatchMessage?
    ended.phone.received?(WatchMessages.encode(.start(ended.start()))!) { data in
      if case .valid(let decoded) = WatchMessages.decode(data) { answer = decoded }
    }
    if duringBegin { ended.workout.authorizations[0](nil) }
    _ = ended.phone.deliver(.ride(ended.ride(revision: 1, phase: .discarded)))
    if duringBegin { ended.workout.begins[0].3(nil) } else { ended.workout.authorizations[0](nil) }
    expect(ended.workout.begins.count == (duringBegin ? 1 : 0), "no begin once the ride ended during authorize")
    expect(!duringBegin || ended.workout.calls.last == "discard", "a session begun for an ended ride is discarded")
    expect(answer == ack("startCancelled"), "the start answers that it didn't run")
    expect(ended.controller.facts.phase == .idle, "a discarded ride is idle")
    expect(ended.controller.facts.available[.start]?.enabled == true, "Start is free again")
  }

  // Finish or Discard while a command is pending is ignored, never ended locally.
  let pending = Rig()
  pending.running()
  pending.controller.pause()
  let sentBefore = pending.phone.sent.count
  pending.controller.finish(rideID: rideA, discard: false)
  pending.controller.finish(rideID: rideA, discard: true)
  expect(pending.phone.sent.count == sentBefore, "no command is sent while one is pending")
  expect(pending.controller.facts.phase == .running && pending.controller.facts.issue == nil, "and nothing ends")
  expect(pending.workout.finishes.isEmpty && !pending.workout.calls.contains("discard"), "no session call")

  // Rule 1: the version-mismatch text shows for a reply and for an incoming message.
  let versions = Rig()
  versions.running()
  versions.controller.lap()
  versions.phone.sent[versions.phone.last("command")!].reply(Data(#"{"v":2,"kind":"ack"}"#.utf8))
  expect(versions.controller.facts.issue == .failure("watchVersionMismatch"), "a reply in another version shows it")
  let incoming = Rig()
  incoming.phone.received?(Data(#"{"v":2,"kind":"ride"}"#.utf8)) { _ in }
  expect(incoming.controller.facts.issue == .failure("watchVersionMismatch"), "a message in another version shows it")
  let startMismatch = Rig()
  startMismatch.controller.start()
  startMismatch.phone.sent[startMismatch.phone.last("startRequest")!].reply(Data(#"{"v":2,"kind":"ack"}"#.utf8))
  startMismatch.clock.advance(15)
  expect(startMismatch.controller.facts.issue == .failure("watchVersionMismatch"), "and isn't replaced by unconfirmed")

  // Minor 1: an abandoned begin discards at once.
  let gaveUp = Rig()
  gaveUp.phone.received?(WatchMessages.encode(.start(gaveUp.start()))!) { _ in }
  gaveUp.workout.authorizations[0](nil)
  gaveUp.clock.advance(10)
  expect(gaveUp.workout.calls.last == "discard", "the abandoned session is discarded when given up")

  // V2: quantities and route points apply from a lower revision.
  let lower = Rig()
  lower.running()
  _ = lower.phone.deliver(.ride(lower.ride(revision: 5)))
  let lowerAt = lower.clock.now
  _ = lower.phone.deliver(.ride(lower.ride(revision: 2, quantities: [quantity], route: [point(8, lowerAt)])))
  expect(lower.workout.adds.count == 1, "a lower revision's quantities are written")
  lower.workout.adds[0].1(nil)
  _ = lower.phone.deliver(.ride(lower.ride(revision: 6, phase: .completed, ended: lower.nowUs)))
  expect(lower.workout.finishes.last?.1?.points.map { Int64($0.latitude) } == [8], "and its route points kept")

  // V3: the reply cache keeps the newest 64.
  let cache = Rig()
  let denied = NSError(domain: "com.apple.healthkit", code: 4)
  for index in 0..<65 {
    _ = cache.phone.deliver(.start(cache.start(rideB, event: "c-\(index)")))
    cache.workout.authorizations.last?(denied)
  }
  let authorizations = cache.workout.authorizations.count
  expect(cache.phone.deliver(.start(cache.start(rideB, event: "c-64"))) == ack("healthDenied"), "a cached reply")
  expect(cache.workout.authorizations.count == authorizations, "a cached start runs nothing")
  _ = cache.phone.deliver(.start(cache.start(rideB, event: "c-0")))
  expect(cache.workout.authorizations.count == authorizations + 1, "the 65th-newest left the cache and runs again")

  // Cancel Start while authorizing and while beginning.
  for stage in ["authorize", "begin"] {
    let cancelling = Rig()
    var answer: WatchMessage?
    cancelling.phone.received?(WatchMessages.encode(.start(cancelling.start(request: "r-1")))!) { data in
      if case .valid(let decoded) = WatchMessages.decode(data) { answer = decoded }
    }
    if stage == "begin" { cancelling.workout.authorizations[0](nil) }
    cancelling.controller.cancelStart()
    expect(answer == ack("startCancelled"), "Cancel Start during \(stage) answers startCancelled")
    if stage == "begin" { cancelling.workout.begins[0].3(nil) } else { cancelling.workout.authorizations[0](nil) }
    expect(
      stage == "authorize" ? cancelling.workout.begins.isEmpty : cancelling.workout.calls.last == "discard",
      "a cancelled Start leaves no session (\(stage))")
    expect(cancelling.controller.facts.phase == .idle, "idle after Cancel Start (\(stage))")
  }

  // A heart rate whose send failed is sent again.
  let resend = Rig()
  resend.running()
  resend.workout.onHeartRateReceived?(LiveHeartRate(bpm: 120, at: resend.clock.now))
  resend.controller.tick()
  resend.phone.sent[resend.phone.last("healthSamples")!].failure(CocoaError(.featureUnsupported))
  resend.controller.tick()
  expect(resend.phone.sent.filter { $0.message.kind == "healthSamples" }.count == 2, "an unsent heart rate goes again")

  // Rule 10: a late reply to an earlier ride's message is dropped.
  let lateReply = Rig()
  lateReply.running()
  lateReply.controller.lap()
  let oldCommand = lateReply.phone.last("command")!
  lateReply.clock.advance(5)
  _ = lateReply.phone.deliver(.ride(lateReply.ride(revision: 2, phase: .discarded)))
  _ = lateReply.phone.deliver(.start(lateReply.start(rideB, event: "e-2")))
  lateReply.workout.authorizations.last?(nil)
  lateReply.workout.begins.last?.3(nil)
  _ = lateReply.phone.deliver(.ride(lateReply.ride(rideB, revision: 1)))
  lateReply.phone.answer(oldCommand, .ride(lateReply.ride(rideA, revision: 9, phase: .paused, laps: [1, 2])))
  expect(
    lateReply.controller.facts.rideID == rideB && lateReply.controller.facts.phase == .running,
    "the newer ride is untouched")
  expect(lateReply.controller.facts.lapCount == 0, "and its laps are its own")

  // Resends wait for the phone.
  let waits = Rig()
  waits.controller.start()
  waits.phone.reachable = false
  waits.controller.cancelStart()
  waits.controller.tick()
  waits.controller.tick()
  expect(waits.phone.last("cancelStart") == nil, "nothing is resent while the phone is unreachable")
  waits.phone.reachable = true
  waits.controller.tick()
  expect(waits.phone.last("cancelStart") != nil, "the resend goes once it is reachable")

  // StartTombstones: 60 s, 64 at most.
  var tombstones = StartTombstones()
  tombstones.insert(["a", nil, "b"], at: origin)
  expect(tombstones.contains("a", at: origin.addingTimeInterval(59)), "kept 60 s")
  expect(!tombstones.contains("a", at: origin.addingTimeInterval(60)), "gone after 60 s")
  tombstones.insert((0..<64).map { "t\($0)" }, at: origin)
  expect(!tombstones.contains("b", at: origin) && tombstones.contains("t63", at: origin), "the newest 64")
}

// WatchText against the fixtures.
let contract = URL(
  fileURLWithPath: ProcessInfo.processInfo.environment["POWER_LOG_CONTRACT_FIXTURES"] ?? "tests/fixtures/contract")
func load(_ path: String) -> [String: Any] {
  try! JSONSerialization.jsonObject(with: Data(contentsOf: contract.appendingPathComponent(path))) as! [String: Any]
}
let texts = load("watch/texts.json")
let failures = texts["failures"] as! [String: [String: String]]
expect(Set(failures.keys) == Set(WatchText.failureCodes), "a text for every code the phone may send")
for (code, row) in failures {
  let text = WatchText.failure(code)
  expect(text.message == row["message"] && text.nextStep == row["nextStep"], "\(code)'s text")
}
expect(WatchText.failureLine("noSuchCode") == WatchText.failureLine("unknown"), "another code shows unknown's text")
expect(WatchText.failureLine("startUnconfirmed") == "Power Log couldn't confirm the start. Start again.", "joined")
let issues = texts["issues"] as! [String: String]
let issueCases: [String: WatchIssue] = [
  "phoneUnreachableAtStart": .phoneUnreachableAtStart, "commandUnconfirmed": .commandUnconfirmed,
  "endedLocallyUnreachable": .endedLocallyUnreachable, "endedLocallyUnconfirmed": .endedLocallyUnconfirmed,
  "orphanRecovered": .orphanRecovered,
]
for (name, issue) in issueCases { expect(WatchText.issue(issue) == issues[name], "the \(name) text") }
let reasons = texts["reasons"] as! [String: String]
let reasonCases: [String: WatchReason] = [
  "phoneUnreachable": .phoneUnreachable, "commandPending": .commandPending, "sessionBusy": .sessionBusy,
  "noActiveRide": .noActiveRide,
]
for (name, reason) in reasonCases { expect(WatchText.reason(reason) == reasons[name], "the \(name) hint") }
let health = (load("presentation/health-states.json")["appleHealth"] as! [String: String])
for outcome in [HealthOutcome.saved, .pending, .notRequested, .unknown] {
  expect(WatchText.health(outcome) == health[outcome.rawValue], "the Health line for \(outcome)")
}
for row in load("presentation/units.json")["distance"] as! [[String: Any]] {
  let units = UnitSystem(rawValue: row["units"] as! String)!
  let shown = WatchText.distance((row["distanceM"] as? NSNumber)?.doubleValue, units: units)
  expect(
    shown.text == row["text"] as? String && shown.symbol == row["symbol"] as? String,
    "\(row) is \(shown)")
}
for row in load("presentation/formatting.json")["duration"] as! [[String: Any]] {
  let seconds = (row["seconds"] as? NSNumber)?.doubleValue
  expect(
    WatchText.duration(us: seconds.map { Int64(($0 * 1_000_000).rounded(.down)) }) == row["text"] as? String,
    "duration \(String(describing: seconds))")
}
expect(WatchText.whole(1.5) == "2" && WatchText.whole(-1.5) == "-2" && WatchText.whole(nil) == "—", "whole numbers")

// WatchRideModel.
MainActor.assumeIsolated {
  let rig = Rig()
  let model = WatchRideModel(rig.controller)
  expect(model.state.mode == .idle && model.state.title == "Ready to ride", "idle")
  rig.running()
  let display = WatchDisplay(
    powerW: 182.5, cadenceRpm: 78.4, distanceM: 3456.25, freshUntilUtcUs: rig.nowUs + 6_000_000)
  _ = rig.phone.deliver(.ride(rig.ride(revision: 2, laps: [1], display: display)))
  model.refresh()
  expect(model.state.mode == .active && model.state.status == "RIDING", "active")
  expect(model.state.lap == "Lap 2", "the next lap's label")
  let readings = model.readings(at: rig.clock.now)
  expect(readings.power == "183" && readings.cadence == "78" && readings.controller == "Controller", "fresh readings")
  expect(readings.distance == "3.46" && readings.distanceSymbol == "km", "distance")
  let stale = model.readings(at: rig.clock.now.addingTimeInterval(6))
  expect(stale.power == "—" && stale.controllerWarning, "power and cadence only while fresh")
  model.askToFinish()
  expect(model.confirmingRideID == rideA, "the confirmation keeps its ride")
  model.confirmFinish(discard: false)
  expect(rig.phone.sent.last?.message.kind == "command", "Save ride sends finish")
}
print("Watch app: every row passed")
