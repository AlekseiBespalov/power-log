import ExpoModulesCore
import UIKit

public final class CycBridgeAppDelegateSubscriber: ExpoAppDelegateSubscriber {
  public func application(_ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
    let restoreCentral = launchOptions?[.bluetoothCentrals] != nil
    let background = application.applicationState != .active
    if #available(iOS 26.0, *) { _ = WorkoutEngine.shared }
    // Creating the engine itself does not request Bluetooth permission; managers are lazy.
    let engine = CycEngine.shared
    setBackground(background)
    engine.queue.async { engine.restoreOnLaunch(central: restoreCentral) }
    if !restoreCentral {
      if #available(iOS 26.0, *) {
        let workout = WorkoutEngine.shared
        workout.queue.async {
          guard background || workout.rideInProgress else { return }
          engine.queue.async { engine.resumeRememberedConnection() }
        }
      } else if background {
        engine.queue.async { engine.resumeRememberedConnection() }
      }
    }
    return true
  }

  public func applicationDidEnterBackground(_ application: UIApplication) {
    setBackground(true)
    if #available(iOS 26.0, *) { WorkoutEngine.shared.flushStorage() }
  }

  public func applicationWillResignActive(_ application: UIApplication) {
    setBackground(true)
  }

  public func applicationDidBecomeActive(_ application: UIApplication) {
    setBackground(false)
  }

  private func setBackground(_ background: Bool) {
    if #available(iOS 26.0, *) { WorkoutLiveActivity.shared.setForeground(!background) }
    let engine = CycEngine.shared
    engine.queue.async { engine.setBackground(background) }
    MonitorDataStore.shared.setBackground(background)
    if #available(iOS 26.0, *) {
      let workout = WorkoutEngine.shared
      workout.queue.async { workout.setBackground(background) }
    }
  }

  public func applicationWillTerminate(_ application: UIApplication) {
    let engine = CycEngine.shared
    engine.queue.sync { engine.willTerminate() }
  }
}
