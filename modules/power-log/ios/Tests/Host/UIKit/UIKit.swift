import Foundation

public struct UIBackgroundTaskIdentifier: Hashable, RawRepresentable {
  public let rawValue: Int

  public init(rawValue: Int) { self.rawValue = rawValue }

  public static let invalid = UIBackgroundTaskIdentifier(rawValue: 0)
}

open class UIViewController {
  public init() {}
}

open class UIApplication {
  public static let shared = UIApplication()

  public func beginBackgroundTask(withName name: String?, expirationHandler: (() -> Void)?)
    -> UIBackgroundTaskIdentifier
  { .invalid }

  public func endBackgroundTask(_ identifier: UIBackgroundTaskIdentifier) {}

  public struct LaunchOptionsKey: Hashable, RawRepresentable {
    public let rawValue: String

    public init(rawValue: String) { self.rawValue = rawValue }

    public static let bluetoothCentrals = LaunchOptionsKey(rawValue: "UIApplicationLaunchOptionsBluetoothCentralsKey")
  }

  public static let didBecomeActiveNotification = Notification.Name("UIApplicationDidBecomeActiveNotification")
  public static let willResignActiveNotification = Notification.Name("UIApplicationWillResignActiveNotification")
  public static let didEnterBackgroundNotification = Notification.Name("UIApplicationDidEnterBackgroundNotification")
  public static let willTerminateNotification = Notification.Name("UIApplicationWillTerminateNotification")
}
