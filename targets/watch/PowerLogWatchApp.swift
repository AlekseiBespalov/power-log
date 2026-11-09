import HealthKit
import SwiftUI
import WatchKit

@MainActor
enum PowerLogWatch {
  static let controller = WatchRideController(
    phone: PhoneLink(), workout: WorkoutSession(store: HKHealthStore(), queue: .main), clock: SystemWatchClock(),
    store: DefaultsOptionsStore())
}

@main
struct PowerLogWatchApp: App {
  @WKApplicationDelegateAdaptor(PowerLogWatchDelegate.self) private var delegate
  @StateObject private var model = WatchRideModel(PowerLogWatch.controller)

  var body: some Scene {
    WindowGroup {
      WatchRideView(model: model)
        .task { PowerLogWatch.controller.activate() }
    }
  }
}

final class PowerLogWatchDelegate: NSObject, WKApplicationDelegate {
  func handle(_ workoutConfiguration: HKWorkoutConfiguration) {
    Task { @MainActor in PowerLogWatch.controller.activate() }
  }

  func handleActiveWorkoutRecovery() {
    Task { @MainActor in PowerLogWatch.controller.activate() }
  }

  func handle(_ backgroundTasks: Set<WKRefreshBackgroundTask>) {
    for task in backgroundTasks { task.setTaskCompletedWithSnapshot(false) }
  }
}
