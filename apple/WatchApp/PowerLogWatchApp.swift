import SwiftUI
import WatchKit
import HealthKit

@main
struct PowerLogWatchApp: App {
  @WKApplicationDelegateAdaptor(PowerLogWatchDelegate.self) private var delegate
  @StateObject private var engine = WatchWorkoutEngine.shared
  @Environment(\.scenePhase) private var scenePhase

  init() {
    #if POWER_LOG_LAB
      PowerLogWatchLab.observeRestart()
    #endif
  }

  var body: some Scene {
    WindowGroup {
      WatchWorkoutView(engine: engine)
        .task {
          #if POWER_LOG_LAB
            if PowerLogWatchLab.isRequested {
              await PowerLogWatchLab.run()
              return
            }
          #endif
          await engine.activate()
        }
        .onChange(of: scenePhase) { _, phase in
          if phase == .active {
            #if POWER_LOG_LAB
              if PowerLogWatchLab.isRequested { return }
            #endif
            Task { await engine.foreground() }
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
      } else {
        task.setTaskCompletedWithSnapshot(false)
      }
    }
  }

  func handleActiveWorkoutRecovery() {
    Task { @MainActor in await WatchWorkoutEngine.shared.handleActiveWorkoutRecovery() }
  }
}
