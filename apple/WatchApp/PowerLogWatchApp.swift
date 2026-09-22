import SwiftUI
import WatchKit
import HealthKit

@main
struct PowerLogWatchApp: App {
  @WKApplicationDelegateAdaptor(PowerLogWatchDelegate.self) private var delegate
  @StateObject private var engine = WatchWorkoutEngine.shared
  @Environment(\.scenePhase) private var scenePhase

  var body: some Scene {
    WindowGroup {
      WatchWorkoutView(engine: engine)
        .task { await engine.activate() }
        .onChange(of: scenePhase) { _, phase in
          if phase == .active {
            engine.retrySync()
            Task { await engine.retryHealthSave(automatic: true) }
          }
        }
    }
  }
}

final class PowerLogWatchDelegate: NSObject, WKApplicationDelegate {
  func handle(_ workoutConfiguration: HKWorkoutConfiguration) {
    Task { @MainActor in
      await WatchWorkoutEngine.shared.handleLaunch(configuration: workoutConfiguration)
    }
  }

  func handle(_ backgroundTasks: Set<WKRefreshBackgroundTask>) {
    for task in backgroundTasks {
      if let connectivity = task as? WKWatchConnectivityRefreshBackgroundTask {
        Task { @MainActor in await WatchWorkoutEngine.shared.ownBackgroundTask(connectivity) }
      } else { task.setTaskCompletedWithSnapshot(false) }
    }
  }

  func handleActiveWorkoutRecovery() {
    Task { @MainActor in await WatchWorkoutEngine.shared.recover() }
  }
}
