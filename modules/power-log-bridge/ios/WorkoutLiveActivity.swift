#if os(iOS)
  import ActivityKit
  import Foundation
  import OSLog
  import UIKit

  final class WorkoutLiveActivity: @unchecked Sendable {
    static let shared = WorkoutLiveActivity()
    private let publicationLock = NSLock()
    private var lifecycleSequence: Int64 = 0
    let coordinator = WorkoutActivityCoordinator(timer: DispatchWorkoutActivityTimer(queue: WorkoutEngine.shared.queue))
    var externalActions: WorkoutActivityExternalActions { coordinator.externalActions }
    var onStateChange: (([String: Any]) -> Void)?
    var refreshPresentation: () -> Void = { WorkoutEngine.shared.publishActivity() }
    lazy var controls = WorkoutActivityControls(
      actions: externalActions,
      refresh: { [weak self] in self?.refreshPresentation() },
      changed: { [weak self] in self?.refreshControls() },
      execute: { request, command, token in
        _ = try WorkoutEngine.shared.performActivityCommand(
          rideID: request.rideId, commandID: token,
          action: command, expectedPhase: request.expectedPhase)
      }, discard: { id in _ = try WorkoutEngine.shared.stop(expectedID: id, discard: true) })
    @MainActor private static let presenter = WorkoutActivityPresenter(
      kit: NativeWorkoutActivityKit(), defaults: .standard,
      didPublish: { content, deadline in
        WorkoutEngine.shared.queue.async { WorkoutLiveActivity.shared.coordinator.didPublish(content) }
        logQualification(content, deadline: deadline)
      },
      failed: { error in
        Logger(subsystem: "app.powerlog", category: "LiveActivity").error(
          "Live Activity start failed: \(error.localizedDescription)")
      })
    private init() {
      coordinator.refresh = { [weak self] in self?.refreshPresentation() }
      coordinator.publishContent = { content, sequence, force in
        Task { @MainActor in Self.presenter.publish(content, sequence: sequence, force: force) }
      }
    }
    #if DEBUG && targetEnvironment(simulator)
      private var qualificationStartedAt: Date?
    #endif
    func publish(_ snapshot: WorkoutActivitySnapshot) {
      #if DEBUG && targetEnvironment(simulator)
        guard qualificationStartedAt == nil else { return }
      #endif
      coordinator.publish(snapshot)
    }
    func state(_ body: [String: Any]) -> [String: Any] { externalActions.state(body) }
    func refreshControls() {
      refreshPresentation()
      onStateChange?(state(WorkoutEngine.shared.state()))
    }
    func setForeground(_ foreground: Bool) {
      publicationLock.lock()
      lifecycleSequence += 1
      let sequence = lifecycleSequence
      publicationLock.unlock()
      Task { @MainActor in Self.presenter.setForeground(foreground, sequence: sequence) }
      if foreground {
        WorkoutEngine.shared.queue.async {
          #if DEBUG && targetEnvironment(simulator)
            if self.startQualificationIfRequested() { return }
          #endif
          self.coordinator.refreshAtDeadline()
        }
      }
    }
    #if DEBUG && targetEnvironment(simulator)
      private func startQualificationIfRequested() -> Bool {
        guard ProcessInfo.processInfo.arguments.contains("--activity-qualification") else { return false }
        if qualificationStartedAt == nil {
          let args = ProcessInfo.processInfo.arguments
          let acquisition =
            args.firstIndex(of: "--activity-qualification-acquiredAt")
            .flatMap { $0 + 1 < args.count ? Double(args[$0 + 1]) : nil }
            .map { Date(timeIntervalSince1970: $0) } ?? Date().addingTimeInterval(-5)
          qualificationStartedAt = acquisition
          refreshPresentation = { [weak self] in self?.publishQualification() }
          WorkoutEngine.shared.queue.asyncAfter(deadline: .now() + max(0, acquisition.timeIntervalSinceNow)) {
            if args.contains("--activity-qualification-running") {
              Task { @MainActor in
                let app = UIApplication.shared
                let task = app.beginBackgroundTask(withName: "Activity qualification")
                DispatchQueue.main.asyncAfter(deadline: .now() + 25) { app.endBackgroundTask(task) }
              }
            }
            self.coordinator.refreshAtDeadline()
          }
          publishQualification()
        }
        return true
      }

      private func publishQualification() {
        guard let start = qualificationStartedAt else { return }
        let now = Date()
        let uptime = ProcessInfo.processInfo.systemUptime
        let elapsed = now.timeIntervalSince(start)
        coordinator.publish(
          WorkoutActivitySnapshot(
            rideID: "11111111-1111-4111-8111-111111111111", phase: "running", pendingAction: nil,
            timerSeconds: max(0, 5 + elapsed), observedAt: now, observedUptime: uptime,
            bikeSampleAgeSeconds: elapsed, heartSampleAgeSeconds: elapsed,
            riderPowerW: 200, heartRateBpm: 150, controlToken: "66666666-6666-4666-8666-666666666666"))
      }
    #endif

    private static func logQualification(_ content: PowerLogRideAttributes.ContentState, deadline: Bool) {
      #if DEBUG && targetEnvironment(simulator)
        guard ProcessInfo.processInfo.arguments.contains("--activity-qualification") else { return }
        let now = Date()
        let render = content.render(at: now, isStale: false)
        let staleDate = content.staleDate?.timeIntervalSince1970 ?? -1
        Logger(subsystem: "app.powerlog.activity.qualification", category: "Publication").notice(
          "PUBLISH sequence=\(content.publicationSequence) now=\(now.timeIntervalSince1970) staleDate=\(staleDate) deadline=\(deadline) bike=\(render.bike.text, privacy: .public) heart=\(render.heartRate.text, privacy: .public)"
        )
      #endif
    }
  }

  @MainActor
  private final class NativeWorkoutActivityKit: WorkoutActivityKit {
    var enabled: Bool { ActivityAuthorizationInfo().areActivitiesEnabled }
    var activities: [WorkoutActivityHandle] {
      Activity<PowerLogRideAttributes>.activities.map { handle($0) }
    }
    private func handle(_ activity: Activity<PowerLogRideAttributes>) -> WorkoutActivityHandle {
      WorkoutActivityHandle(
        id: activity.id, rideID: activity.attributes.rideID,
        state: String(describing: activity.activityState))
    }
    private func find(_ activity: WorkoutActivityHandle) -> Activity<PowerLogRideAttributes>? {
      Activity<PowerLogRideAttributes>.activities.first { $0.id == activity.id }
    }
    func request(_ content: PowerLogRideAttributes.ContentState) throws -> WorkoutActivityHandle {
      handle(
        try Activity.request(
          attributes: PowerLogRideAttributes(rideID: content.rideID),
          content: ActivityContent(state: content, staleDate: content.staleDate), pushType: nil))
    }
    func update(_ activity: WorkoutActivityHandle, content: PowerLogRideAttributes.ContentState) async {
      await find(activity)?.update(ActivityContent(state: content, staleDate: content.staleDate))
    }
    func end(_ activity: WorkoutActivityHandle) async {
      await find(activity)?.end(nil, dismissalPolicy: .immediate)
    }
    func observe(_ activity: WorkoutActivityHandle, ended: @escaping @MainActor () -> Void) -> Task<Void, Never>? {
      guard let activity = find(activity) else { return nil }
      return Task { @MainActor in
        for await state in activity.activityStateUpdates {
          guard !Task.isCancelled else { return }
          if state == .dismissed || state == .ended {
            ended()
            return
          }
        }
      }
    }
  }
#endif
