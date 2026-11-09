import Foundation
import PowerLogCore

final class RecordingHost: NSObject, HostFacade {
  var foreground: [Bool] = []
  var other = 0

  func launch(context: ApplicationLaunchContext, done: @escaping (DomainRefusalDetail?) -> Void) { other += 1 }
  func foregroundChanged(foreground: Bool) { self.foreground.append(foreground) }
  func didEnterBackground(done: @escaping () -> Void) { other += 1 }
  func willTerminate(timeoutUs: Int64) -> Bool {
    other += 1
    return true
  }
  func rideCardIntent(intent: ApplicationRideCardIntent, done: @escaping (ApplicationApiFailure?) -> Void) {
    other += 1
  }
  func runHealthWork(rideId: String, attempt: Int32, done: @escaping (ApplicationHealthWorkResult) -> Void) {
    other += 1
  }
}

func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() {
    FileHandle.standardError.write(Data("PowerLogForeground: \(message)\n".utf8))
    exit(1)
  }
}

let center = NotificationCenter()
let host = RecordingHost()
let active = Notification.Name("PowerLogTestDidBecomeActive")
let inactive = Notification.Name("PowerLogTestWillResignActive")
PowerLogForeground.forward(to: host, from: center, active: active, inactive: inactive)
expect(host.foreground.isEmpty, "nothing is forwarded before a notification")
center.post(name: active, object: nil)
expect(host.foreground == [true], "becoming active forwards foreground true")
center.post(name: inactive, object: nil)
expect(host.foreground == [true, false], "resigning active forwards foreground false")
center.post(name: Notification.Name("PowerLogTestDidEnterBackground"), object: nil)
center.post(name: active, object: nil)
expect(host.foreground == [true, false, true], "each change is forwarded once, in order, and nothing else")
expect(host.other == 0, "only foregroundChanged is called")
print("PowerLogForeground: 5 assertions passed")
