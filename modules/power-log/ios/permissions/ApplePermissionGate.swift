import CoreBluetooth
import CoreLocation
import Foundation
import PowerLogCore

struct LocationAuthorization {
  let status: CLAuthorizationStatus
  let precise: Bool
  let servicesOn: Bool
}

protocol PermissionSystem: AnyObject {
  func bluetooth() -> CBManagerAuthorization
  func promptBluetooth(_ answered: @escaping () -> Void)
  func location(_ read: @escaping (LocationAuthorization) -> Void)
  func promptLocation(_ answered: @escaping () -> Void)
}

final class ApplePermissionGate: NSObject, ApplicationPermissionGate {
  private let system: PermissionSystem
  private let main: (@escaping () -> Void) -> Void
  private var requesting = false

  init(system: PermissionSystem, main: @escaping (@escaping () -> Void) -> Void) {
    self.system = system
    self.main = main
  }

  convenience init(queue: DispatchQueue) {
    self.init(system: SystemPermissions(queue: queue), main: { DispatchQueue.main.async(execute: $0) })
  }

  func status(reply: ApplicationReply<ApplicationPermissionStates>) {
    main { [self] in answer(reply) }
  }

  func request(kinds: Set<ApplicationPermissionKind>, reply: ApplicationReply<ApplicationPermissionStates>) {
    main { [self] in
      guard !requesting else {
        return reply.failed(error: ApplicationPortError(kind: .busy, platform: nil))
      }
      requesting = true
      let finish = { [self] in
        requesting = false
        answer(reply)
      }
      let askLocation = { [self] in
        guard kinds.contains(.location) else { return finish() }
        system.location { [self] authorization in
          main { [self] in
            guard authorization.status == .notDetermined else { return finish() }
            system.promptLocation { [self] in main(finish) }
          }
        }
      }
      if kinds.contains(.bluetooth), system.bluetooth() == .notDetermined {
        system.promptBluetooth { [self] in main(askLocation) }
      } else {
        askLocation()
      }
    }
  }

  private func answer(_ reply: ApplicationReply<ApplicationPermissionStates>) {
    let bluetooth = Self.state(system.bluetooth())
    system.location { authorization in
      let location = Self.state(authorization.status)
      reply.ok(
        value: ApplicationPermissionStates(
          bluetooth: bluetooth, location: location,
          preciseLocation: location == .granted ? KotlinBoolean(value: authorization.precise) : nil,
          locationServicesOn: KotlinBoolean(value: authorization.servicesOn), notifications: nil))
    }
  }

  static func state(_ authorization: CBManagerAuthorization) -> ApplicationPermissionState {
    switch authorization {
    case .allowedAlways: .granted
    case .denied: .denied
    case .restricted: .restricted
    case .notDetermined: .notdetermined
    @unknown default: .unavailable
    }
  }

  static func state(_ status: CLAuthorizationStatus) -> ApplicationPermissionState {
    #if os(iOS)
      if status == .authorizedWhenInUse { return .granted }
    #endif
    switch status {
    case .authorizedAlways: return .granted
    case .denied: return .denied
    case .restricted: return .restricted
    case .notDetermined: return .notdetermined
    default: return .unavailable
    }
  }
}

final class SystemPermissions: NSObject, PermissionSystem, CBCentralManagerDelegate, CLLocationManagerDelegate {
  private let queue: DispatchQueue
  private var prompt: CBCentralManager?
  private var bluetoothAnswered: (() -> Void)?
  private var manager: CLLocationManager?
  private var locationAnswered: (() -> Void)?

  init(queue: DispatchQueue) { self.queue = queue }

  func bluetooth() -> CBManagerAuthorization { CBManager.authorization }

  func promptBluetooth(_ answered: @escaping () -> Void) {
    queue.async { [self] in
      bluetoothAnswered = answered
      prompt = CBCentralManager(delegate: self, queue: queue, options: [CBCentralManagerOptionShowPowerAlertKey: false])
    }
  }

  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    guard central === prompt else { return }
    prompt = nil
    let answered = bluetoothAnswered
    bluetoothAnswered = nil
    answered?()
  }

  // CLLocationManager.locationServicesEnabled() can block, so it is read off the main thread.
  func location(_ read: @escaping (LocationAuthorization) -> Void) {
    let manager = locationManager()
    let status = manager.authorizationStatus
    let precise = manager.accuracyAuthorization == .fullAccuracy
    DispatchQueue.global(qos: .utility).async {
      read(
        LocationAuthorization(status: status, precise: precise, servicesOn: CLLocationManager.locationServicesEnabled())
      )
    }
  }

  func promptLocation(_ answered: @escaping () -> Void) {
    let manager = locationManager()
    guard manager.authorizationStatus == .notDetermined else { return answered() }
    locationAnswered = answered
    #if os(iOS)
      manager.requestWhenInUseAuthorization()
    #else
      manager.requestAlwaysAuthorization()
    #endif
  }

  func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    guard manager.authorizationStatus != .notDetermined, let answered = locationAnswered else { return }
    locationAnswered = nil
    answered()
  }

  private func locationManager() -> CLLocationManager {
    if let manager { return manager }
    let created = CLLocationManager()
    created.delegate = self
    manager = created
    return created
  }
}
