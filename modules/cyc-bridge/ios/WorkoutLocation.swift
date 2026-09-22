#if os(iOS)
import CoreLocation
import Foundation

/// CLLocationManager stays on the main run loop; consumers receive timestamped native fixes.
final class WorkoutLocation: NSObject, CLLocationManagerDelegate {
  var onLocation: ((CLLocation) -> Void)?
  var onDiscontinuity: (() -> Void)?
  var onStatus: ((String) -> Void)?
  private var manager: CLLocationManager?
  private var recording = false
  private var permissionCompletion: ((String) -> Void)?

  func permissionStatus(_ completion: @escaping ([String: Any]) -> Void) {
    DispatchQueue.main.async {
      self.configure()
      guard let manager = self.manager else { completion(["location": "unavailable", "locationServicesEnabled": false, "locationAccuracyAuthorization": "unknown"]); return }
      let status = self.authorization(manager.authorizationStatus)
      let accuracy: String
      switch manager.accuracyAuthorization {
      case .fullAccuracy: accuracy = "full"
      case .reducedAccuracy: accuracy = "reduced"
      @unknown default: accuracy = "unknown"
      }
      DispatchQueue.global(qos: .utility).async {
        completion(["location": status, "locationServicesEnabled": CLLocationManager.locationServicesEnabled(), "locationAccuracyAuthorization": accuracy])
      }
    }
  }

  func requestPermission(_ completion: @escaping (String) -> Void) {
    DispatchQueue.main.async {
      self.configure()
      guard let manager = self.manager else { completion("unavailable"); return }
      if manager.authorizationStatus == .notDetermined {
        self.permissionCompletion = completion
        manager.requestWhenInUseAuthorization()
      } else { completion(self.authorization(manager.authorizationStatus)) }
    }
  }

  func start() {
    DispatchQueue.main.async {
      self.configure()
      guard let manager = self.manager else { return }
      self.recording = true
      guard [.authorizedAlways, .authorizedWhenInUse].contains(manager.authorizationStatus) else {
        self.onStatus?(self.authorization(manager.authorizationStatus)); return
      }
      manager.activityType = .fitness
      manager.desiredAccuracy = kCLLocationAccuracyBest
      manager.distanceFilter = kCLDistanceFilterNone
      manager.pausesLocationUpdatesAutomatically = false
      manager.showsBackgroundLocationIndicator = true
      manager.allowsBackgroundLocationUpdates = (Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String])?.contains("location") == true
      manager.startUpdatingLocation()
      self.onStatus?("waiting")
    }
  }

  func stop() {
    DispatchQueue.main.async { self.recording = false; self.manager?.stopUpdatingLocation(); self.onStatus?("inactive") }
  }

  private func configure() {
    guard manager == nil else { return }
    manager = CLLocationManager()
    manager?.delegate = self
  }

  private func authorization(_ value: CLAuthorizationStatus) -> String {
    switch value {
    case .authorizedAlways: return "authorizedAlways"
    case .authorizedWhenInUse: return "authorizedWhenInUse"
    case .denied: return "denied"
    case .restricted: return "restricted"
    case .notDetermined: return "notDetermined"
    @unknown default: return "unknown"
    }
  }

  func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    let state = authorization(manager.authorizationStatus)
    onStatus?(state)
    if manager.authorizationStatus != .notDetermined {
      permissionCompletion?(state); permissionCompletion = nil
      if recording { start() }
    }
  }

  func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    guard recording else { return }
    for location in locations.sorted(by: { $0.timestamp < $1.timestamp }) {
      guard CLLocationCoordinate2DIsValid(location.coordinate), location.horizontalAccuracy.isFinite,
        abs(location.timestamp.timeIntervalSinceNow) <= 15 else { onDiscontinuity?(); continue }
      onLocation?(location)
      onStatus?((0...50).contains(location.horizontalAccuracy) ? "receiving" : "weak")
    }
  }

  func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    guard recording else { return }
    let code = (error as? CLError)?.code
    onStatus?(code == .locationUnknown ? "waiting" : code == .denied ? "denied" : "unavailable")
  }
}
#endif
