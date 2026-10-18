import Foundation
import PowerLogCore

var checks = 0
func check(_ condition: Bool, _ message: String) {
  checks += 1
  if !condition { fatalError(message) }
}
func rejects(_ operation: () throws -> Void, _ message: String) {
  do {
    try operation()
    fatalError(message)
  } catch { checks += 1 }
}
final class TestTimer: WorkoutActivityTimer {
  var handler: (@Sendable () -> Void)?
  var delay: Double?
  var cancelled = 0
  func replace(after seconds: Double, handler: @escaping @Sendable () -> Void) {
    delay = seconds
    self.handler = handler
  }
  func cancel() {
    handler = nil
    delay = nil
    cancelled += 1
  }
}
@MainActor
final class TestActivityKit: WorkoutActivityKit {
  var enabled = true
  var activities: [WorkoutActivityHandle] = []
  var publications: [PowerLogRideAttributes.ContentState] = []
  var starts: [Double] = []
  var clock = 105.0
  var delayed = false
  var pending: CheckedContinuation<Void, Never>?
  var ended: (@MainActor () -> Void)?
  func request(_ content: PowerLogRideAttributes.ContentState) throws -> WorkoutActivityHandle {
    let handle = WorkoutActivityHandle(id: "activity", rideID: content.rideID, state: "active")
    activities = [handle]
    publications.append(content)
    starts.append(clock)
    return handle
  }
  func update(_ activity: WorkoutActivityHandle, content: PowerLogRideAttributes.ContentState) async {
    starts.append(clock)
    if delayed { await withCheckedContinuation { pending = $0 } }
    publications.append(content)
  }
  func complete(at time: Double) {
    clock = time
    let continuation = pending
    pending = nil
    continuation?.resume()
  }
  func end(_ activity: WorkoutActivityHandle) async { activities.removeAll { $0.id == activity.id } }
  func observe(_ activity: WorkoutActivityHandle, ended: @escaping @MainActor () -> Void) -> Task<Void, Never>? {
    self.ended = ended
    return nil
  }
}

func dispatchTimerCheck(queue: DispatchQueue) {
  let realTimer = DispatchWorkoutActivityTimer(queue: queue)
  let fired = DispatchSemaphore(value: 0)
  realTimer.replace(after: 0.02) { fired.signal() }
  check(fired.wait(timeout: .now() + 2) == .success, "Dispatch timer invokes its installed production handler")
  realTimer.cancel()
}

@MainActor
func runtimeChecks() async throws {
  let ride = "11111111-1111-4111-8111-111111111111"
  let token = "66666666-6666-4666-8666-666666666666"
  func snapshot(_ now: Double, acquired: Double = 100) throws -> WorkoutActivitySnapshot {
    try activitySnapshot(
      rideID: ride, token: token, now: Int64(now * 1_000_000),
      bike: Int64(acquired * 1_000_000), heart: Int64(acquired * 1_000_000))
  }
  let kit = TestActivityKit()
  let suite = "PowerLog.activity.tests." + UUID().uuidString
  let defaults = UserDefaults(suiteName: suite)!
  defer { defaults.removePersistentDomain(forName: suite) }
  let presenter = WorkoutActivityPresenter(
    kit: kit, defaults: defaults,
    now: { Date(timeIntervalSince1970: kit.clock) }, uptime: { kit.clock }, didPublish: { _, _ in })
  func content(_ now: Double, sequence: Int64, acquired: Double = 100) throws -> PowerLogRideAttributes.ContentState {
    let input = try activitySnapshot(
      rideID: ride, token: token, now: Int64(now * 1_000_000),
      bike: acquired == 100 ? 100_000_000 : nil, heart: Int64(acquired * 1_000_000))
    return WorkoutActivityProjection.content(try input.project(sequence: sequence)!, at: input.clock.utcUs)
  }
  let reversedKit = TestActivityKit()
  let reversedDefaults = UserDefaults(suiteName: suite + ".reversed")!
  defer { reversedDefaults.removePersistentDomain(forName: suite + ".reversed") }
  let reversedPresenter = WorkoutActivityPresenter(
    kit: reversedKit, defaults: reversedDefaults,
    now: { Date(timeIntervalSince1970: reversedKit.clock) }, uptime: { reversedKit.clock }, didPublish: { _, _ in })
  reversedPresenter.setForeground(true, sequence: 1)
  reversedPresenter.publish(try content(105, sequence: 3), sequence: 3, force: false)
  reversedPresenter.publish(try content(105, sequence: 2), sequence: 2, force: true)
  reversedPresenter.publish(try content(105, sequence: 1), sequence: 1, force: true)
  await reversedPresenter.flush()
  check(
    reversedKit.publications.map(\.publicationSequence) == [3],
    "Reversed publications cannot replace the newer presentation before delivery")
  reversedKit.clock = 111
  reversedPresenter.publish(try content(111, sequence: 2), sequence: 2, force: true)
  await reversedPresenter.flush()
  check(
    reversedKit.publications.map(\.publicationSequence) == [3],
    "An older forced publication cannot replace the newer delivered presentation")

  presenter.setForeground(true, sequence: 1)
  presenter.publish(try content(105, sequence: 1), sequence: 1, force: false)
  await presenter.flush()
  kit.delayed = true
  kit.clock = 106
  presenter.publish(try content(106, sequence: 2), sequence: 2, force: true)
  for _ in 0..<100 { await Task.yield() }
  check(kit.pending != nil, "Deadline enters the real presenter update")
  kit.clock = 107
  presenter.publish(try content(107, sequence: 3, acquired: 107), sequence: 3, force: false)
  kit.complete(at: 107.1)
  for _ in 0..<100 { await Task.yield() }
  check(kit.pending == nil && kit.starts == [105, 106], "One forced deadline cannot propagate into ordinary updates")
  for second in 108...120 {
    if kit.pending != nil && kit.starts.last! + 1.1 < Double(second) {
      kit.complete(at: kit.starts.last! + 1.1)
      for _ in 0..<100 { await Task.yield() }
    }
    kit.clock = Double(second)
    presenter.publish(
      try content(Double(second), sequence: Int64(second - 104), acquired: Double(second)),
      sequence: Int64(second - 104), force: false)
    for _ in 0..<100 { await Task.yield() }
  }
  if kit.pending != nil {
    kit.complete(at: kit.starts.last! + 1.1)
    for _ in 0..<100 { await Task.yield() }
  }
  check(kit.starts.count >= 3, "Continuous observations still publish after throttle")
  for pair in zip(kit.starts.dropFirst(2), kit.starts.dropFirst()) {
    check(pair.0 - pair.1 >= 6.1 - 0.000001, "Updates taking 1.1 seconds retain five-second throttle after completion")
  }
  kit.delayed = false
  await presenter.flush()

  for controlChanged in [false, true] {
    let delayedKit = TestActivityKit()
    let delayedDefaults = UserDefaults(suiteName: suite + String(controlChanged))!
    defer { delayedDefaults.removePersistentDomain(forName: suite + String(controlChanged)) }
    let delayedPresenter = WorkoutActivityPresenter(
      kit: delayedKit, defaults: delayedDefaults,
      now: { Date(timeIntervalSince1970: delayedKit.clock) }, uptime: { delayedKit.clock }, didPublish: { _, _ in })
    delayedPresenter.setForeground(true, sequence: 1)
    delayedPresenter.publish(try content(105, sequence: 1), sequence: 1, force: false)
    await delayedPresenter.flush()
    delayedKit.delayed = true
    delayedKit.clock = 106
    delayedPresenter.publish(try content(106, sequence: 2), sequence: 2, force: true)
    for _ in 0..<100 { await Task.yield() }
    let nextTime = controlChanged ? 107.0 : 115.0
    let input = try activitySnapshot(
      rideID: ride, token: token, phase: controlChanged ? "paused" : "running",
      now: Int64(nextTime * 1_000_000), bike: 100_000_000, heart: 100_000_000)
    delayedKit.clock = nextTime
    delayedPresenter.publish(
      WorkoutActivityProjection.content(try input.project(sequence: 3)!, at: input.clock.utcUs),
      sequence: 3, force: false)
    delayedKit.complete(at: nextTime + 0.1)
    for _ in 0..<100 { await Task.yield() }
    check(
      delayedKit.pending != nil && delayedKit.starts == [105, 106, nextTime + 0.1],
      controlChanged
        ? "Current controls independently bypass throttle after a superseded update"
        : "Delivered expiry independently bypasses throttle after a superseded update")
    delayedKit.delayed = false
    delayedKit.complete(at: nextTime + 1.2)
    await delayedPresenter.flush()
  }

  var now = 105.0
  let timer = TestTimer()
  var advanceOnRead = false
  let coordinator = WorkoutActivityCoordinator(
    timer: timer,
    now: {
      let date = Date(timeIntervalSince1970: now)
      if advanceOnRead {
        now = 106
        advanceOnRead = false
      }
      return date
    })
  var outputs: [(PowerLogRideAttributes.ContentState?, Bool)] = []
  coordinator.publishContent = { value, _, force in outputs.append((value, force)) }
  coordinator.refresh = { coordinator.publish(try! snapshot(now)) }
  coordinator.publish(try snapshot(now))
  check(timer.delay == 1, "Production coordinator arms actual supplied bike deadline")
  let oldHandler = timer.handler!
  now = 105.5
  oldHandler()
  check(outputs.count == 1 && timer.delay == 0.5, "An early timer wake rearms without consuming the expiry")
  now = 105.999999
  advanceOnRead = true
  timer.handler!()
  check(outputs.count == 1 && timer.delay == 0, "Crossing the deadline during rearm retains an immediate callback")
  now = 106
  oldHandler()
  check(outputs.count == 2 && outputs.last!.1, "Production timer handler refreshes and forces the deadline")
  check(
    timer.delay == 9 && outputs.last!.0!.staleDate == Date(timeIntervalSince1970: 115),
    "Handler schedules the next HR deadline")
  now = 107
  coordinator.publish(try snapshot(now, acquired: 107))
  let count = outputs.count
  oldHandler()
  check(outputs.count == count && timer.delay == 6, "Changed observation rejects the old timer callback")
  let cancelledHandler = timer.handler!
  coordinator.publish(
    WorkoutActivitySnapshot(
      rideID: nil, phase: "idle", pendingAction: nil, timerSeconds: 0,
      observedAt: Date(timeIntervalSince1970: now), observedUptime: now, bikeSampleAgeSeconds: nil,
      heartSampleAgeSeconds: nil, riderPowerW: nil, heartRateBpm: nil, controlToken: token))
  cancelledHandler()
  check(timer.handler == nil && outputs.count == count + 1, "Ending the ride cancels and fences the timer")

  let queue = DispatchQueue(label: "app.powerlog.activity.tests")
  dispatchTimerCheck(queue: queue)

  let endingKit = TestActivityKit()
  endingKit.activities = [WorkoutActivityHandle(id: "retained", rideID: ride, state: "active")]
  let endingDefaults = UserDefaults(suiteName: suite + ".ending")!
  defer { endingDefaults.removePersistentDomain(forName: suite + ".ending") }
  let endingPresenter = WorkoutActivityPresenter(
    kit: endingKit, defaults: endingDefaults, now: { Date(timeIntervalSince1970: endingKit.clock) },
    uptime: { endingKit.clock }, didPublish: { _, _ in })
  let endingTimer = TestTimer()
  let endingCoordinator = WorkoutActivityCoordinator(
    timer: endingTimer,
    now: { Date(timeIntervalSince1970: endingKit.clock) })
  endingCoordinator.publishContent = { content, sequence, force in
    endingPresenter.publish(content, sequence: sequence, force: force)
  }
  func endingSnapshot() -> WorkoutActivitySnapshot {
    WorkoutActivitySnapshot(
      rideID: ride, phase: "finishing", pendingAction: "stop", timerSeconds: 7.032,
      observedAt: Date(timeIntervalSince1970: endingKit.clock), observedUptime: endingKit.clock,
      bikeSampleAgeSeconds: nil, heartSampleAgeSeconds: nil, riderPowerW: nil, heartRateBpm: nil,
      controlToken: token, pendingSince: Date(timeIntervalSince1970: 100))
  }
  endingCoordinator.refresh = { endingCoordinator.publish(endingSnapshot()) }
  endingPresenter.setForeground(true, sequence: 1)
  endingCoordinator.publish(endingSnapshot())
  await endingPresenter.flush()
  check(endingTimer.delay == 10, "Pending terminal action schedules recovery without any live readings")
  check(
    endingKit.publications.last?.render(at: Date(timeIntervalSince1970: 116), isStale: true).status.text
      == "Open Power Log",
    "System stale Activity exposes recovery when the phone cannot publish at the deadline")
  endingKit.clock = 110
  endingCoordinator.publish(endingSnapshot())
  check(endingTimer.delay == 5, "Republishing pending Stop does not renew its wait")
  endingKit.clock = 115
  endingTimer.handler!()
  await endingPresenter.flush()
  check(
    endingKit.publications.last?.bikeExpiredStatus.text == "Open Power Log",
    "Actual Activity timer and presenter expose recovery at the pending deadline")
  for phase in ["finishing", "interrupted"] {
    let healthy = WorkoutActivitySnapshot(
      rideID: ride, phase: phase, pendingAction: "stop", timerSeconds: 120,
      observedAt: Date(timeIntervalSince1970: 200), observedUptime: 200,
      bikeSampleAgeSeconds: nil, heartSampleAgeSeconds: nil, riderPowerW: nil, heartRateBpm: nil,
      controlToken: token, pendingSince: Date(timeIntervalSince1970: 100), confirmationPending: false)
    check(healthy.pendingDeadline == nil, "confirmed Save work does not acquire a missing-confirmation deadline")
    if phase == "finishing" {
      check(
        try healthy.project(sequence: 1)!.bikeExpiredStatus.text == "Finishing…",
        "slow confirmed Save keeps ordinary progress instead of expiring into recovery")
    }
  }
  let afterExpiry = endingKit.starts.count
  for second in 116...119 {
    endingKit.clock = Double(second)
    endingCoordinator.publish(endingSnapshot())
    await endingPresenter.flush()
  }
  check(
    endingKit.starts.count == afterExpiry,
    "expired pending deadlines cannot bypass the five-second Activity update gate on every publication")
  endingCoordinator.publish(
    WorkoutActivitySnapshot(
      rideID: nil, phase: "idle", pendingAction: nil, timerSeconds: 0,
      observedAt: Date(timeIntervalSince1970: 115), observedUptime: 115,
      bikeSampleAgeSeconds: nil, heartSampleAgeSeconds: nil, riderPowerW: nil, heartRateBpm: nil, controlToken: token))
  await endingPresenter.flush()
  check(
    endingKit.activities.isEmpty && endingTimer.handler == nil,
    "Proven owner Discard ends the actual Activity and cancels pending recovery")

  func liveActivityUpdates(gated: Bool) async throws -> (built: Int, starts: [Double], phases: [String]) {
    let kit = TestActivityKit()
    let name = suite + ".gate." + String(gated)
    let defaults = UserDefaults(suiteName: name)!
    defer { defaults.removePersistentDomain(forName: name) }
    var coordinator: WorkoutActivityCoordinator?
    let presenter = WorkoutActivityPresenter(
      kit: kit, defaults: defaults, now: { Date(timeIntervalSince1970: kit.clock) }, uptime: { kit.clock },
      didPublish: { content, _ in coordinator?.didPublish(content) })
    let publisher = WorkoutActivityCoordinator(
      timer: TestTimer(), now: { Date(timeIntervalSince1970: kit.clock) }, uptime: { kit.clock })
    coordinator = publisher
    var built = 0
    publisher.publishContent = { content, sequence, force in
      built += 1
      presenter.publish(content, sequence: sequence, force: force)
    }
    presenter.setForeground(true, sequence: 1)
    for second in 0...21 {
      kit.clock = 300 + Double(second)
      let phase = second == 21 ? "paused" : "running"
      let now = Int64(kit.clock * 1_000_000)
      let input = try activitySnapshot(rideID: ride, token: token, phase: phase, now: now, bike: now, heart: now)
      let key = WorkoutActivityKey(
        rideID: ride, phase: phase, pendingAction: nil, controlToken: token,
        confirmationPending: input.confirmationPending)
      if !gated || publisher.needsPublication(key) { publisher.publish(input) }
      await presenter.flush()
    }
    return (built, kit.starts, kit.publications.map(\.expectedPhase))
  }
  let everyPublication = try await liveActivityUpdates(gated: false)
  let shownPublications = try await liveActivityUpdates(gated: true)
  check(
    shownPublications.starts == everyPublication.starts && shownPublications.phases == everyPublication.phases,
    "Building Live Activity projections only when the presenter acts keeps every Activity update")
  check(
    everyPublication.built == 22 && shownPublications.built == shownPublications.starts.count,
    "One-second ride publications build one projection per shown Live Activity update")

  let actions = WorkoutActivityExternalActions()
  var ownerLoaded = false
  var commands: [String] = []
  var stateChanges = 0
  let controls = WorkoutActivityControls(
    actions: actions,
    refresh: {
      ownerLoaded = true
      actions.adopt(try! snapshot(105))
    }, changed: { stateChanges += 1 }, execute: { _, command, _ in commands.append(command) },
    discard: { _ in commands.append("discard") })
  try await PowerLogActivityControl.perform(
    rideID: ride, controlToken: token, action: "finish", expectedPhase: "running",
    queue: queue, controls: { controls })
  check(
    ownerLoaded && commands.isEmpty && actions.finish != nil && stateChanges == 1,
    "Cold intent loads owner before admission and opens confirmation without Stop")
  let request = actions.finish!
  controls.cancel(request)
  rejects({ try controls.finish(request, discard: false) }, "Cancelled same-ride bridge Finish must not execute")
  check(commands.isEmpty, "Bridge cancellation rejects before existing owner Stop")
  do {
    try await PowerLogActivityControl.perform(
      rideID: ride, controlToken: token, action: "finish", expectedPhase: "running",
      queue: queue, controls: { controls })
    fatalError("Cold replay of cancelled intent must reject")
  } catch { checks += 1 }
  let freshToken = actions.controlToken
  try await PowerLogActivityControl.perform(
    rideID: ride, controlToken: freshToken, action: "finish", expectedPhase: "running",
    queue: queue, controls: { controls })
  let fresh = actions.finish!
  try controls.finish(fresh, discard: false)
  check(commands == ["finish"], "Confirmed bridge request invokes existing owner once")
  rejects({ try controls.finish(fresh, discard: false) }, "Confirmed bridge replay cannot execute again")
  check(commands == ["finish"], "Replay has no additional native effect")
}
try await runtimeChecks()
print("Activity runtime: \(checks) checks passed")
