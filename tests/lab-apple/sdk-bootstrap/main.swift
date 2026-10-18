import Foundation
import UIKit

var nativeStarts = 0
struct PowerLogDatabase {}
struct WorkoutEvent {}
final class WorkoutEngine {
  static let shared = WorkoutEngine()
  let queue = DispatchQueue(label: "sdk-workout")
  let rideInProgress = false
  private init() { nativeStarts += 1 }
  func flushStorage() {}
  func setBackground(_ background: Bool) {}
}
final class ControllerEngine {
  static let shared = ControllerEngine()
  let queue = DispatchQueue(label: "sdk-controller")
  private init() { nativeStarts += 1 }
  func restoreOnLaunch(central: Bool) {}
  func resumeRememberedConnection() {}
  func setBackground(_ background: Bool) {}
  func willTerminate() {}
}
final class WorkoutLiveActivity {
  static let shared = WorkoutLiveActivity()
  private init() { nativeStarts += 1 }
  func setForeground(_ foreground: Bool) {}
}
final class MonitorDataStore {
  static let shared = MonitorDataStore()
  private init() { nativeStarts += 1 }
  func setBackground(_ background: Bool) {}
}
let app = UIApplication()
let subscriber = PowerLogBridgeAppDelegateSubscriber()
for name in ["local-record", "interrupt-watch-finish"] {
  UserDefaults.standard.set(name, forKey: "PowerLogLabCase")
  precondition(subscriber.application(app, didFinishLaunchingWithOptions: nil))
  subscriber.applicationDidBecomeActive(app)
  subscriber.applicationWillResignActive(app)
  subscriber.applicationDidEnterBackground(app)
  subscriber.applicationWillTerminate(app)
  precondition(nativeStarts == 0, "SDK diagnostic initialized production owner or workers")
}
UserDefaults.standard.removeObject(forKey: "PowerLogLabCase")
precondition(subscriber.application(app, didFinishLaunchingWithOptions: nil))
subscriber.applicationDidBecomeActive(app)
precondition(nativeStarts == 4, "A normal launch must execute the production bootstrap")
print("Lab SDK bootstrap: 6 assertions passed")
