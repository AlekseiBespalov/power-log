@_exported import Foundation

public final class UIApplication {
  public enum State { case active }
  public enum LaunchOptionsKey: Hashable { case bluetoothCentrals }
  public let applicationState = State.active
  public init() {}
}
