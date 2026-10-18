import Foundation
import PowerLogCore

struct WorkoutActivityHandle: Equatable, Sendable {
  let id: String
  let rideID: String
  let state: String
}

@MainActor
protocol WorkoutActivityKit {
  var activities: [WorkoutActivityHandle] { get }
  var enabled: Bool { get }
  func request(_ content: PowerLogRideAttributes.ContentState) throws -> WorkoutActivityHandle
  func update(_ activity: WorkoutActivityHandle, content: PowerLogRideAttributes.ContentState) async
  func end(_ activity: WorkoutActivityHandle) async
  func observe(_ activity: WorkoutActivityHandle, ended: @escaping @MainActor () -> Void) -> Task<Void, Never>?
}

protocol WorkoutActivityTimer: AnyObject {
  func replace(after seconds: Double, handler: @escaping @Sendable () -> Void)
  func cancel()
}

final class DispatchWorkoutActivityTimer: WorkoutActivityTimer {
  private let queue: DispatchQueue
  private var timer: DispatchSourceTimer?
  init(queue: DispatchQueue) { self.queue = queue }
  func replace(after seconds: Double, handler: @escaping @Sendable () -> Void) {
    cancel()
    let timer = DispatchSource.makeTimerSource(queue: queue)
    timer.schedule(deadline: .now() + seconds, leeway: .nanoseconds(0))
    timer.setEventHandler(handler: handler)
    self.timer = timer
    timer.resume()
  }
  func cancel() {
    timer?.cancel()
    timer = nil
  }
  deinit { cancel() }
}

struct WorkoutActivityKey: Equatable {
  let rideID: String?
  let phase: String
  let pendingAction: String?
  let controlToken: String
  let confirmationPending: Bool
}

final class WorkoutActivityCoordinator: @unchecked Sendable {
  private let timer: WorkoutActivityTimer
  private let now: () -> Date
  private let uptime: () -> Double
  private var sequence: Int64 = 0
  private var schedule = WorkoutActivitySchedule()
  private var latest: PowerLogRideAttributes.ContentState?
  private var delivered: PowerLogRideAttributes.ContentState?
  private var deliveredUptime = -Double.infinity
  private var forwarded: (key: WorkoutActivityKey, controlToken: String, uptime: Double)?
  private var deadlineUpdate = false
  let externalActions = WorkoutActivityExternalActions()
  var refresh: () -> Void = {}
  var publishContent: (PowerLogRideAttributes.ContentState?, Int64, Bool) -> Void = { _, _, _ in }
  init(
    timer: WorkoutActivityTimer, now: @escaping () -> Date = Date.init,
    uptime: @escaping () -> Double = { ProcessInfo.processInfo.systemUptime }
  ) {
    self.timer = timer
    self.now = now
    self.uptime = uptime
  }
  /// Whether the presenter would act on a publication with `key` now.
  func needsPublication(_ key: WorkoutActivityKey) -> Bool {
    guard let forwarded, !deadlineUpdate, forwarded.key == key, forwarded.controlToken == externalActions.controlToken
    else { return true }
    guard key.rideID != nil else { return false }
    let shown = delivered?.rideID == key.rideID
    return uptime() - (shown ? deliveredUptime : forwarded.uptime) >= 5
      || WorkoutActivityUpdateGate.deadlineReached(shown ? delivered?.staleDate : nil, at: now())
  }
  func publish(_ snapshot: WorkoutActivitySnapshot) {
    externalActions.adopt(snapshot)
    forwarded = (
      WorkoutActivityKey(
        rideID: snapshot.rideID, phase: snapshot.phase, pendingAction: snapshot.pendingAction,
        controlToken: snapshot.controlToken, confirmationPending: snapshot.confirmationPending),
      externalActions.controlToken, uptime()
    )
    sequence += 1
    let projection = try! snapshot.project(sequence: sequence, controlToken: externalActions.controlToken)
    latest = projection.map {
      WorkoutActivityProjection.content($0, at: snapshot.clock.utcUs, pendingDeadline: snapshot.pendingDeadline)
    }
    replaceDeadline()
    publishContent(latest, sequence, deadlineUpdate)
  }
  func didPublish(_ content: PowerLogRideAttributes.ContentState) {
    guard content.rideID == latest?.rideID, content.publicationSequence > (delivered?.publicationSequence ?? 0) else {
      return
    }
    delivered = content
    deliveredUptime = uptime()
    replaceDeadline()
  }
  func refreshAtDeadline() {
    deadlineUpdate = true
    defer { deadlineUpdate = false }
    refresh()
  }
  private func replaceDeadline() {
    let utcUs = Int64(floor(now().timeIntervalSince1970 * 1_000_000))
    schedule.replace(latest, delivered: delivered, nowUtcUs: utcUs)
    timer.cancel()
    guard let deadline = schedule.deadline else { return }
    arm(deadline)
  }
  private func arm(_ deadline: WorkoutActivityDeadline) {
    let utcUs = Int64(floor(now().timeIntervalSince1970 * 1_000_000))
    timer.replace(after: Double(max(0, deadline.utcUs - utcUs)) / 1_000_000) { [weak self] in
      guard let self, self.schedule.deadline == deadline else { return }
      let firedAt = Int64(floor(self.now().timeIntervalSince1970 * 1_000_000))
      guard firedAt >= deadline.utcUs else {
        self.arm(deadline)
        return
      }
      guard self.schedule.take(deadline) else { return }
      self.refreshAtDeadline()
    }
  }
}

final class WorkoutActivityControls {
  private let actions: WorkoutActivityExternalActions
  private let refresh: () -> Void
  private let changed: () -> Void
  private let execute: (ExternalControlRequest, String, String) throws -> Void
  private let discard: (String) throws -> Void
  init(
    actions: WorkoutActivityExternalActions, refresh: @escaping () -> Void, changed: @escaping () -> Void,
    execute: @escaping (ExternalControlRequest, String, String) throws -> Void,
    discard: @escaping (String) throws -> Void
  ) {
    self.actions = actions
    self.refresh = refresh
    self.changed = changed
    self.execute = execute
    self.discard = discard
  }
  func intent(rideID: String, controlToken: String, action: String, expectedPhase: String) throws {
    refresh()
    let request = try ExternalControlRequest(
      rideId: rideID, controlToken: controlToken, expectedPhase: expectedPhase, action: action)
    try actions.perform(request, confirmed: false) { command, token in try execute(request, command, token) }
    changed()
  }
  func finish(_ request: ExternalControlRequest, discard: Bool) throws {
    refresh()
    guard request.action == "finish" else { throw WorkoutDataError.invalid("Confirm finishing the selected ride.") }
    try actions.perform(request, confirmed: true, discard: discard) { command, token in
      if command == "discard" { try self.discard(request.rideId) } else { try execute(request, "finish", token) }
    }
    changed()
  }
  func cancel(_ request: ExternalControlRequest) {
    refresh()
    actions.cancel(request)
    changed()
  }
}

public enum PowerLogActivityControl {
  public static func perform(rideID: String, controlToken: String, action: String, expectedPhase: String) async throws {
    #if os(iOS)
      try await perform(
        rideID: rideID, controlToken: controlToken, action: action, expectedPhase: expectedPhase,
        queue: WorkoutEngine.shared.queue, controls: { WorkoutLiveActivity.shared.controls })
    #else
      throw WorkoutDataError.invalid("Ride controls are unavailable.")
    #endif
  }
  static func perform(
    rideID: String, controlToken: String, action: String, expectedPhase: String,
    queue: DispatchQueue, controls: @escaping () -> WorkoutActivityControls
  ) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      queue.async {
        do {
          try controls().intent(
            rideID: rideID, controlToken: controlToken, action: action, expectedPhase: expectedPhase)
          continuation.resume()
        } catch { continuation.resume(throwing: error) }
      }
    }
  }
}

@MainActor
final class WorkoutActivityPresenter {
  private static let startedRideKey = "PowerLog.liveActivity.startedRide"
  private let kit: WorkoutActivityKit
  private let defaults: UserDefaults
  private let now: () -> Date
  private let uptime: () -> Double
  private let didPublish: (PowerLogRideAttributes.ContentState, Bool) -> Void
  private let failed: (Error) -> Void

  init(
    kit: WorkoutActivityKit, defaults: UserDefaults, now: @escaping () -> Date = Date.init,
    uptime: @escaping () -> Double = { ProcessInfo.processInfo.systemUptime },
    didPublish: @escaping (PowerLogRideAttributes.ContentState, Bool) -> Void,
    failed: @escaping (Error) -> Void = { _ in }
  ) {
    self.kit = kit
    self.defaults = defaults
    self.now = now
    self.uptime = uptime
    self.didPublish = didPublish
    self.failed = failed
  }

  func flush() async {
    while updating { await Task.yield() }
  }
  private var foreground = false
  private var latest: PowerLogRideAttributes.ContentState?
  private var activity: WorkoutActivityHandle?
  private var observation: Task<Void, Never>?
  private var updating = false
  private var dirty = false
  private var lastPublicationSequence: Int64 = 0
  private var lastLifecycleSequence: Int64 = 0
  private var lastUpdateUptime = -Double.infinity
  private var lastReconcileUptime = -Double.infinity
  private var lastControlToken: String?
  private var lastExpectedPhase: String?
  private var lastStaleDate: Date?
  private var suppressedRideID: String?
  private var failedRequestRideID: String?

  func setForeground(_ value: Bool, sequence: Int64) {
    guard sequence > lastLifecycleSequence else { return }
    lastLifecycleSequence = sequence
    if value && !foreground { failedRequestRideID = nil }
    foreground = value
    schedule()
  }

  private var forcedUpdate = false

  func publish(_ content: PowerLogRideAttributes.ContentState?, sequence: Int64, force: Bool) {
    guard sequence > lastPublicationSequence else { return }
    lastPublicationSequence = sequence
    let controlChanged =
      latest?.controlToken != content?.controlToken || latest?.expectedPhase != content?.expectedPhase
    latest = content
    forcedUpdate = forcedUpdate || force
    if content == nil || controlChanged || force || WorkoutActivityUpdateGate.deadlineReached(lastStaleDate, at: now())
      || uptime() - lastReconcileUptime >= 5
    {
      schedule()
    }
  }

  private func schedule() {
    dirty = true
    guard !updating else { return }
    updating = true
    Task { @MainActor in
      defer { updating = false }
      while dirty {
        dirty = false
        await reconcile()
      }
    }
  }

  private func reconcile() async {
    let snapshot = latest
    let force = forcedUpdate || WorkoutActivityUpdateGate.deadlineReached(lastStaleDate, at: now())
    lastReconcileUptime = uptime()
    let rideID = snapshot?.rideID
    let active =
      rideID != nil
      && ["preparing", "running", "paused", "interrupted", "finishing"].contains(snapshot?.expectedPhase ?? "")
    let existingActivities = kit.activities
    let matching = existingActivities.filter {
      $0.rideID == rideID && ["active", "stale"].contains($0.state)
    }
    let retainedID = matching.first(where: { $0.id == activity?.id })?.id ?? matching.first?.id
    for existing in existingActivities where !active || existing.id != retainedID {
      await kit.end(existing)
      guard isCurrent(snapshot) else {
        dirty = true
        return
      }
    }
    if !active {
      activity = nil
      observation?.cancel()
      observation = nil
      forcedUpdate = false
      lastControlToken = nil
      lastExpectedPhase = nil
      lastStaleDate = nil
      suppressedRideID = nil
      failedRequestRideID = nil
      if defaults.object(forKey: Self.startedRideKey) != nil {
        defaults.removeObject(forKey: Self.startedRideKey)
      }
      return
    }
    guard let rideID, let snapshot else { return }
    if activity?.rideID != rideID {
      observation?.cancel()
      observation = nil
      activity = matching.first { $0.id == retainedID }
      lastControlToken = nil
    }
    if activity == nil {
      // An absent previously started activity was dismissed or expired; do not recreate it after relaunch.
      if defaults.string(forKey: Self.startedRideKey) == rideID { suppressedRideID = rideID }
      guard foreground, kit.enabled,
        suppressedRideID != rideID, failedRequestRideID != rideID,
        ["running", "paused"].contains(snapshot.expectedPhase)
      else { return }
      do {
        forcedUpdate = false
        activity = try kit.request(snapshot)
        defaults.set(rideID, forKey: Self.startedRideKey)
      } catch {
        failedRequestRideID = rideID
        failed(error)
        return
      }
      didPublish(snapshot, force)
      lastUpdateUptime = uptime()
      lastControlToken = snapshot.controlToken
      lastExpectedPhase = snapshot.expectedPhase
      lastStaleDate = snapshot.staleDate
    } else if WorkoutActivityUpdateGate.allows(
      deadline: force,
      controlChanged: snapshot.controlToken != lastControlToken || snapshot.expectedPhase != lastExpectedPhase,
      lastUpdate: lastUpdateUptime, now: uptime())
    {
      forcedUpdate = false
      if let activity { await kit.update(activity, content: snapshot) }
      didPublish(snapshot, force)
      lastUpdateUptime = uptime()
      lastControlToken = snapshot.controlToken
      lastExpectedPhase = snapshot.expectedPhase
      lastStaleDate = snapshot.staleDate
      guard isCurrent(snapshot) else {
        dirty = true
        return
      }
    }
    if observation == nil, let activity {
      observation = kit.observe(activity) { [weak self] in
        guard let self else { return }
        self.suppressedRideID = rideID
        self.activity = nil
        self.observation = nil
      }
    }
  }

  private func isCurrent(_ snapshot: PowerLogRideAttributes.ContentState?) -> Bool {
    latest?.rideID == snapshot?.rideID && latest?.publicationSequence == snapshot?.publicationSequence
  }
}
