import Foundation
import PowerLogCore

final class AppleHealthProvider: NSObject, ApplicationHealthProvider {
  let kind: DomainHealthProviderKind = .applehealth
  let session: ApplicationAppleWorkoutSession? = nil
  let writer: ApplicationHealthConnectWriter? = nil
  var access: ApplicationHealthAccess { fatalError("the host suite never starts the runtime") }
  var reader: ApplicationHealthReader { fatalError("the host suite never starts the runtime") }

  init(queue: DispatchQueue, clock: ApplicationClock) {}
}
