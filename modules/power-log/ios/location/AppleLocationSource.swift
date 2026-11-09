import CoreLocation
import Foundation
import PowerLogCore

protocol LocationManagerSeam: AnyObject {
  var authorization: CLAuthorizationStatus { get }
  var fitness: Bool { get set }
  var desiredAccuracy: CLLocationAccuracy { get set }
  var distanceFilter: CLLocationDistance { get set }
  var pausesAutomatically: Bool { get set }
  var showsBackgroundIndicator: Bool { get set }
  var updatesInBackground: Bool { get set }
  func startUpdatingLocation()
  func stopUpdatingLocation()
}

protocol LocationManagerEvents: AnyObject {
  func locationsReceived(_ locations: [CLLocation])
  func authorizationChanged()
  func locationFailed(_ error: Error)
}

final class AppleLocationSource: NSObject, ApplicationLocationSource, LocationManagerEvents {
  private let clock: ApplicationClock
  private let main: (@escaping () -> Void) -> Void
  private let ride: (@escaping () -> Void) -> Void
  private let makeManager: (LocationManagerEvents) -> LocationManagerSeam
  private var events: ApplicationLocationEvents?
  private var manager: LocationManagerSeam?
  private var running = false
  private var paused = false
  private var updating = false
  private var reported: ApplicationLocationProviderState?

  init(
    clock: ApplicationClock, main: @escaping (@escaping () -> Void) -> Void,
    ride: @escaping (@escaping () -> Void) -> Void,
    makeManager: @escaping (LocationManagerEvents) -> LocationManagerSeam
  ) {
    self.clock = clock
    self.main = main
    self.ride = ride
    self.makeManager = makeManager
  }

  convenience init(clock: ApplicationClock, queue: DispatchQueue) {
    self.init(
      clock: clock, main: { DispatchQueue.main.async(execute: $0) }, ride: { queue.async(execute: $0) },
      makeManager: { SystemLocationManager(events: $0) })
  }

  func attach(locationEvents: ApplicationLocationEvents) { events = locationEvents }

  func start() {
    main { [self] in
      guard !running else { return }
      running = true
      paused = false
      reported = nil
      begin()
    }
  }

  func pause() {
    main { [self] in
      paused = true
      manager?.desiredAccuracy = kCLLocationAccuracyThreeKilometers
    }
  }

  func resume() {
    main { [self] in
      paused = false
      manager?.desiredAccuracy = kCLLocationAccuracyBest
    }
  }

  func stop() {
    main { [self] in
      guard running else { return }
      running = false
      updating = false
      manager?.stopUpdatingLocation()
    }
  }

  private func begin() {
    let manager = manager ?? makeManager(self)
    self.manager = manager
    guard Self.authorized(manager.authorization) else { return report(Self.provider(manager.authorization)) }
    guard !updating else { return }
    updating = true
    manager.fitness = true
    manager.desiredAccuracy = paused ? kCLLocationAccuracyThreeKilometers : kCLLocationAccuracyBest
    manager.distanceFilter = kCLDistanceFilterNone
    manager.pausesAutomatically = false
    manager.showsBackgroundIndicator = true
    manager.updatesInBackground = true
    manager.startUpdatingLocation()
    report(.waiting)
  }

  func locationsReceived(_ locations: [CLLocation]) {
    let at = clock.read()
    guard running else { return }
    let batch = locations.map(Self.original)
    report(.available)
    ride { [weak self] in self?.events?.fixesReceived(batch: batch, at: at) }
  }

  func authorizationChanged() {
    guard running, let manager else { return }
    if Self.authorized(manager.authorization) {
      begin()
    } else {
      updating = false
      manager.stopUpdatingLocation()
      report(Self.provider(manager.authorization))
    }
  }

  func locationFailed(_ error: Error) {
    guard running else { return }
    switch (error as? CLError)?.code {
    case .locationUnknown: report(.waiting)
    case .denied: report(.denied)
    default: report(.unavailable)
    }
  }

  private func report(_ state: ApplicationLocationProviderState) {
    guard reported != state else { return }
    reported = state
    ride { [weak self] in self?.events?.providerChanged(state: state) }
  }

  static func authorized(_ status: CLAuthorizationStatus) -> Bool {
    #if os(iOS)
      status == .authorizedAlways || status == .authorizedWhenInUse
    #else
      status == .authorizedAlways
    #endif
  }

  static func provider(_ status: CLAuthorizationStatus) -> ApplicationLocationProviderState {
    if authorized(status) || status == .notDetermined { return .waiting }
    switch status {
    case .denied: return .denied
    case .restricted: return .restricted
    default: return .unavailable
    }
  }

  static func original(_ location: CLLocation) -> DomainLocationOriginal {
    func number(_ value: Double, when valid: Bool = true) -> KotlinDouble? {
      valid && value.isFinite ? KotlinDouble(value: value) : nil
    }
    let altitude = location.altitude
    let vertical = location.verticalAccuracy
    let msl = altitude.isFinite && vertical.isFinite && vertical >= 0
    return DomainLocationOriginal(
      utcUs: Int64((location.timestamp.timeIntervalSince1970 * 1_000_000).rounded(.down)),
      acquiredMonoUs: nil,
      latitude: location.coordinate.latitude,
      longitude: location.coordinate.longitude,
      horizontalAccuracyM: number(location.horizontalAccuracy),
      altitudeM: number(altitude),
      altitudeDatum: .msl,
      verticalAccuracyM: number(vertical),
      mslM: number(altitude, when: msl),
      mslVerticalAccuracyM: number(vertical, when: msl),
      speedMps: number(location.speed, when: location.speed >= 0),
      speedAccuracyMps: number(location.speedAccuracy, when: location.speedAccuracy >= 0),
      courseDeg: number(location.course, when: location.course >= 0),
      courseAccuracyDeg: number(location.courseAccuracy, when: location.courseAccuracy >= 0))
  }
}

final class SystemLocationManager: NSObject, LocationManagerSeam, CLLocationManagerDelegate {
  private let events: LocationManagerEvents
  private let manager = CLLocationManager()

  init(events: LocationManagerEvents) {
    self.events = events
    super.init()
    manager.delegate = self
  }

  var authorization: CLAuthorizationStatus { manager.authorizationStatus }

  var desiredAccuracy: CLLocationAccuracy {
    get { manager.desiredAccuracy }
    set { manager.desiredAccuracy = newValue }
  }

  var distanceFilter: CLLocationDistance {
    get { manager.distanceFilter }
    set { manager.distanceFilter = newValue }
  }

  #if os(iOS)
    var fitness: Bool {
      get { manager.activityType == .fitness }
      set { manager.activityType = newValue ? .fitness : .other }
    }

    var pausesAutomatically: Bool {
      get { manager.pausesLocationUpdatesAutomatically }
      set { manager.pausesLocationUpdatesAutomatically = newValue }
    }

    var showsBackgroundIndicator: Bool {
      get { manager.showsBackgroundLocationIndicator }
      set { manager.showsBackgroundLocationIndicator = newValue }
    }

    // Setting allowsBackgroundLocationUpdates without the location background mode raises an exception.
    var updatesInBackground: Bool {
      get { manager.allowsBackgroundLocationUpdates }
      set {
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        manager.allowsBackgroundLocationUpdates = newValue && modes.contains("location")
      }
    }
  #else
    var fitness = false
    var pausesAutomatically = false
    var showsBackgroundIndicator = false
    var updatesInBackground = false
  #endif

  func startUpdatingLocation() { manager.startUpdatingLocation() }

  func stopUpdatingLocation() { manager.stopUpdatingLocation() }

  func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    events.locationsReceived(locations)
  }

  func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) { events.authorizationChanged() }

  func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) { events.locationFailed(error) }
}
