import ExpoModulesCore
import UIKit

public final class PowerLogAppDelegateSubscriber: ExpoAppDelegateSubscriber {
  public func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    PowerLogHost.start(launchOptions: launchOptions)
    return true
  }
}
