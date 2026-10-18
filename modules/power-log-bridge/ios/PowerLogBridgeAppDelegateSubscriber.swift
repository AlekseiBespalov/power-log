import ExpoModulesCore
import UIKit

public final class PowerLogBridgeAppDelegateSubscriber: ExpoAppDelegateSubscriber {
  public func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    #if POWER_LOG_LAB
      if WorkoutLabCrash.isSDKProbe { return true }
    #endif
    let restoreCentral = launchOptions?[.bluetoothCentrals] != nil
    let background = application.applicationState != .active
    _ = WorkoutEngine.shared
    // Creating the engine itself does not request Bluetooth permission; managers are lazy.
    let engine = ControllerEngine.shared
    setBackground(background)
    engine.queue.async { engine.restoreOnLaunch(central: restoreCentral) }
    if !restoreCentral {
      let workout = WorkoutEngine.shared
      workout.queue.async {
        guard background || workout.rideInProgress else { return }
        engine.queue.async { engine.resumeRememberedConnection() }
      }
    }
    return true
  }

  public func applicationDidEnterBackground(_ application: UIApplication) {
    #if POWER_LOG_LAB
      if WorkoutLabCrash.isSDKProbe { return }
    #endif
    setBackground(true)
    WorkoutEngine.shared.flushStorage()
  }

  public func applicationWillResignActive(_ application: UIApplication) {
    setBackground(true)
  }

  public func applicationDidBecomeActive(_ application: UIApplication) {
    setBackground(false)
  }

  private func setBackground(_ background: Bool) {
    #if POWER_LOG_LAB
      if WorkoutLabCrash.isSDKProbe { return }
    #endif
    WorkoutLiveActivity.shared.setForeground(!background)
    let engine = ControllerEngine.shared
    engine.queue.async { engine.setBackground(background) }
    MonitorDataStore.shared.setBackground(background)
    let workout = WorkoutEngine.shared
    workout.queue.async { workout.setBackground(background) }
  }

  public func applicationWillTerminate(_ application: UIApplication) {
    #if POWER_LOG_LAB
      if WorkoutLabCrash.isSDKProbe { return }
    #endif
    let engine = ControllerEngine.shared
    engine.queue.sync { engine.willTerminate() }
  }
}
