import Foundation
import PowerLogCore

enum PowerLogForeground {
  static func forward(
    to host: HostFacade, from center: NotificationCenter, active: Notification.Name, inactive: Notification.Name
  ) {
    _ = center.addObserver(forName: active, object: nil, queue: nil) { _ in host.foregroundChanged(foreground: true) }
    _ = center.addObserver(forName: inactive, object: nil, queue: nil) { _ in
      host.foregroundChanged(foreground: false)
    }
  }
}
