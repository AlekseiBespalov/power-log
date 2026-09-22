#if os(iOS)
import ActivityKit
import Foundation
import OSLog

struct WorkoutActivitySnapshot: Sendable {
  let rideID: String?
  let phase: String
  let pendingAction: String?
  let timerSeconds: Double
  let observedAt: Date
  let lastBikeSampleAt: Date?
  let lastHeartSampleAt: Date?
  let riderPowerW: Double?
  let heartRateBpm: Double?
  let controlToken: String
}

@available(iOS 26.0, *)
final class WorkoutLiveActivity: @unchecked Sendable {
  static let shared = WorkoutLiveActivity()
  private let publicationLock = NSLock()
  private var sequence: UInt64 = 0
  private init() {}

  func publish(_ snapshot: WorkoutActivitySnapshot) {
    let sequence = nextSequence()
    Task { @MainActor in WorkoutActivityPresenter.shared.publish(snapshot, sequence: sequence) }
  }

  func setForeground(_ foreground: Bool) {
    let sequence = nextSequence()
    Task { @MainActor in WorkoutActivityPresenter.shared.setForeground(foreground, sequence: sequence) }
  }

  private func nextSequence() -> UInt64 {
    publicationLock.lock(); defer { publicationLock.unlock() }
    sequence += 1
    return sequence
  }
}

@available(iOS 26.0, *)
public enum PowerLogActivityControl {
  public static func perform(rideID: String, commandID: String, action: String, expectedPhase: String) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      let engine = WorkoutEngine.shared
      engine.queue.async {
        do {
          _ = try engine.performActivityCommand(rideID: rideID, commandID: commandID, action: action, expectedPhase: expectedPhase)
          continuation.resume()
        } catch { continuation.resume(throwing: error) }
      }
    }
  }
}

@available(iOS 26.0, *)
@MainActor
private final class WorkoutActivityPresenter {
  static let shared = WorkoutActivityPresenter()
  private static let startedRideKey = "PowerLog.liveActivity.startedRide"
  private let logger = Logger(subsystem: "app.powerlog", category: "LiveActivity")
  private var foreground = false
  private var latest: WorkoutActivitySnapshot?
  private var activity: Activity<PowerLogRideAttributes>?
  private var observation: Task<Void, Never>?
  private var updating = false
  private var dirty = false
  private var lastPublicationSequence: UInt64 = 0
  private var lastLifecycleSequence: UInt64 = 0
  private var lastUpdateUptime = -Double.infinity
  private var lastReconcileUptime = -Double.infinity
  private var lastControlToken: String?
  private var suppressedRideID: String?
  private var failedRequestRideID: String?

  func setForeground(_ value: Bool, sequence: UInt64) {
    guard sequence > lastLifecycleSequence else { return }
    lastLifecycleSequence = sequence
    if value && !foreground { failedRequestRideID = nil }
    foreground = value
    schedule()
  }

  func publish(_ snapshot: WorkoutActivitySnapshot, sequence: UInt64) {
    guard sequence > lastPublicationSequence else { return }
    lastPublicationSequence = sequence
    let controlChanged = !isCurrent(snapshot)
    latest = snapshot
    if controlChanged || ProcessInfo.processInfo.systemUptime - lastReconcileUptime >= 5 { schedule() }
  }

  private func schedule() {
    dirty = true
    guard !updating else { return }
    updating = true
    Task { @MainActor in
      defer { updating = false }
      while dirty { dirty = false; await reconcile() }
    }
  }

  private func reconcile() async {
    guard let snapshot = latest else { return }
    lastReconcileUptime = ProcessInfo.processInfo.systemUptime
    let rideID = snapshot.rideID
    let active = rideID != nil && ["preparing", "running", "paused", "recoverable", "finishing"].contains(snapshot.phase)
    let existingActivities = Activity<PowerLogRideAttributes>.activities
    let matching = existingActivities.filter { $0.attributes.rideID == rideID && [.active, .stale].contains($0.activityState) }
    let retainedID = matching.first(where: { $0.id == activity?.id })?.id ?? matching.first?.id
    for existing in existingActivities where !active || existing.id != retainedID {
      await existing.end(nil, dismissalPolicy: .immediate)
      guard isCurrent(snapshot) else { dirty = true; return }
    }
    if !active {
      activity = nil; observation?.cancel(); observation = nil
      lastControlToken = nil; suppressedRideID = nil; failedRequestRideID = nil
      if UserDefaults.standard.object(forKey: Self.startedRideKey) != nil {
        UserDefaults.standard.removeObject(forKey: Self.startedRideKey)
      }
      return
    }
    guard let rideID else { return }
    if activity?.attributes.rideID != rideID {
      observation?.cancel(); observation = nil
      activity = matching.first { $0.id == retainedID }
      lastControlToken = nil
    }
    let content = ActivityContent(state: PowerLogRideAttributes.ContentState(
      phase: snapshot.phase, pendingAction: snapshot.pendingAction,
      timerSeconds: max(0, snapshot.timerSeconds), observedAt: snapshot.observedAt,
      lastBikeSampleAt: snapshot.lastBikeSampleAt, lastHeartSampleAt: snapshot.lastHeartSampleAt,
      riderPowerW: snapshot.riderPowerW, heartRateBpm: snapshot.heartRateBpm,
      controlToken: snapshot.controlToken), staleDate: snapshot.observedAt.addingTimeInterval(20))
    if activity == nil {
      // An absent previously started activity was dismissed or expired; do not recreate it after relaunch.
      if UserDefaults.standard.string(forKey: Self.startedRideKey) == rideID { suppressedRideID = rideID }
      guard foreground, ActivityAuthorizationInfo().areActivitiesEnabled,
        suppressedRideID != rideID, failedRequestRideID != rideID,
        ["running", "paused"].contains(snapshot.phase) else { return }
      do {
        activity = try Activity.request(attributes: PowerLogRideAttributes(rideID: rideID), content: content, pushType: nil)
        UserDefaults.standard.set(rideID, forKey: Self.startedRideKey)
      } catch {
        failedRequestRideID = rideID
        logger.error("Live Activity start failed: \(error.localizedDescription)")
        return
      }
      lastUpdateUptime = ProcessInfo.processInfo.systemUptime; lastControlToken = snapshot.controlToken
    } else if snapshot.controlToken != lastControlToken || ProcessInfo.processInfo.systemUptime - lastUpdateUptime >= 5 {
      await activity?.update(content)
      lastUpdateUptime = ProcessInfo.processInfo.systemUptime; lastControlToken = snapshot.controlToken
      guard isCurrent(snapshot) else { dirty = true; return }
    }
    if observation == nil, let activity {
      observation = Task { @MainActor [weak self] in
        for await state in activity.activityStateUpdates {
          guard !Task.isCancelled, let self else { return }
          if state == .dismissed || state == .ended {
            self.suppressedRideID = rideID
            self.activity = nil
            self.observation = nil
            return
          }
        }
      }
    }
  }

  private func isCurrent(_ snapshot: WorkoutActivitySnapshot) -> Bool {
    latest?.rideID == snapshot.rideID && latest?.controlToken == snapshot.controlToken &&
      latest?.phase == snapshot.phase && latest?.pendingAction == snapshot.pendingAction
  }
}
#endif
