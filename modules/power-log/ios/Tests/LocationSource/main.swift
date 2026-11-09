import CoreLocation
import Foundation
import PowerLogCore

func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() {
    FileHandle.standardError.write(Data("AppleLocationSource: \(message)\n".utf8))
    exit(1)
  }
}

final class StepClock: NSObject, ApplicationClock {
  var mono: Int64 = 0
  func monoUs() -> Int64 { mono }
  func utcUs() -> Int64 { 1_767_225_600_000_000 + mono }
  func read() -> DomainClockReading { DomainClockReading(monoUs: monoUs(), utcUs: utcUs()) }
}

final class FakeManager: LocationManagerSeam {
  var authorization = CLAuthorizationStatus.authorizedAlways
  var fitness = false
  var desiredAccuracy: CLLocationAccuracy = 0
  var distanceFilter: CLLocationDistance = 10
  var pausesAutomatically = true
  var showsBackgroundIndicator = false
  var updatesInBackground = false
  var starts = 0
  var stops = 0
  func startUpdatingLocation() { starts += 1 }
  func stopUpdatingLocation() { stops += 1 }
}

final class Recorded: NSObject, ApplicationLocationEvents {
  var fixes: [([DomainLocationOriginal], DomainClockReading)] = []
  var providers: [String] = []
  func fixesReceived(batch: [DomainLocationOriginal], at: DomainClockReading) { fixes.append((batch, at)) }
  func providerChanged(state: ApplicationLocationProviderState) { providers.append(state.name) }
}

final class Rig {
  let clock = StepClock()
  let manager = FakeManager()
  let events = Recorded()
  var mainQueue: [() -> Void] = []
  var rideQueue: [() -> Void] = []
  var created = 0
  lazy var source = AppleLocationSource(
    clock: clock, main: { self.mainQueue.append($0) }, ride: { self.rideQueue.append($0) },
    makeManager: { _ in
      self.created += 1
      return self.manager
    })

  init() { source.attach(locationEvents: events) }

  func onMain() {
    while !mainQueue.isEmpty { mainQueue.removeFirst()() }
  }

  func onRide() {
    while !rideQueue.isEmpty { rideQueue.removeFirst()() }
  }
}

func location(
  altitude: Double = 41, vertical: Double = 6, speed: Double = 5.5, speedAccuracy: Double = 0.5, course: Double = 270,
  courseAccuracy: Double = 3, timestamp: Double = 1_767_225_600.123456
) -> CLLocation {
  CLLocation(
    coordinate: CLLocationCoordinate2D(latitude: 52.5, longitude: 13.4), altitude: altitude, horizontalAccuracy: 80,
    verticalAccuracy: vertical, course: course, courseAccuracy: courseAccuracy, speed: speed,
    speedAccuracy: speedAccuracy, timestamp: Date(timeIntervalSince1970: timestamp))
}

let start = Rig()
start.source.start()
expect(start.created == 0, "the manager is made on the main thread, not inside start")
start.onMain()
expect(start.manager.starts == 1, "start asks for updates")
expect(start.manager.fitness, "the activity is fitness")
expect(start.manager.desiredAccuracy == kCLLocationAccuracyBest, "the accuracy is the best")
expect(start.manager.distanceFilter == kCLDistanceFilterNone, "there is no distance filter")
expect(!start.manager.pausesAutomatically, "updates never pause on their own")
expect(start.manager.showsBackgroundIndicator, "the indicator shows")
expect(start.manager.updatesInBackground, "updates go on in the background")
start.source.pause()
start.onMain()
expect(start.manager.desiredAccuracy == kCLLocationAccuracyThreeKilometers, "a pause lowers the accuracy to 3 km")
start.source.resume()
start.onMain()
expect(start.manager.desiredAccuracy == kCLLocationAccuracyBest, "a resume restores the accuracy")
start.source.stop()
start.onMain()
expect(start.manager.stops == 1, "stop ends updates")
start.onRide()
expect(start.events.providers == ["waiting"], "a started source waits for its first fix")

let fixes = Rig()
fixes.source.start()
fixes.onMain()
fixes.clock.mono = 9_000
fixes.source.locationsReceived([location(), location(timestamp: 1_000), location()])
fixes.clock.mono = 20_000
fixes.onRide()
expect(fixes.events.fixes.count == 1, "one batch arrives")
let (batch, at) = fixes.events.fixes[0]
expect(at.monoUs == 9_000, "the batch is stamped in the callback")
expect(batch.map(\.utcUs) == [1_767_225_600_123_456, 1_000_000_000, 1_767_225_600_123_456], "every fix, in order")
let first = batch[0]
expect(first.acquiredMonoUs == nil, "the iPhone has no acquisition instant")
expect(first.latitude == 52.5 && first.longitude == 13.4, "the coordinate is the OS's")
expect(first.horizontalAccuracyM?.doubleValue == 80, "the horizontal accuracy is the OS's")
expect(first.altitudeDatum == DomainAltitudeDatum.msl, "the iPhone's altitude is above mean sea level")
expect(first.altitudeM?.doubleValue == 41 && first.verticalAccuracyM?.doubleValue == 6, "the altitude is the OS's")
expect(first.mslM?.doubleValue == 41 && first.mslVerticalAccuracyM?.doubleValue == 6, "a valid altitude is MSL too")
expect(first.speedMps?.doubleValue == 5.5 && first.speedAccuracyMps?.doubleValue == 0.5, "the speed is the OS's")
expect(first.courseDeg?.doubleValue == 270 && first.courseAccuracyDeg?.doubleValue == 3, "the course is the OS's")
expect(fixes.events.providers == ["waiting", "available"], "a fix makes the provider available")

let sentinels = AppleLocationSource.original(
  location(vertical: -1, speed: -1, speedAccuracy: -1, course: -1, courseAccuracy: -1))
expect(sentinels.altitudeM?.doubleValue == 41, "an altitude with a negative accuracy is kept")
expect(sentinels.verticalAccuracyM?.doubleValue == -1, "the negative vertical accuracy is kept")
expect(sentinels.mslM == nil && sentinels.mslVerticalAccuracyM == nil, "an invalid altitude is no MSL value")
expect(sentinels.speedMps == nil && sentinels.speedAccuracyMps == nil, "a negative speed is null")
expect(sentinels.courseDeg == nil && sentinels.courseAccuracyDeg == nil, "a negative course is null")

let refused = Rig()
refused.manager.authorization = .denied
refused.source.start()
refused.onMain()
expect(refused.manager.starts == 0, "a denied source asks for nothing")
refused.manager.authorization = .authorizedAlways
refused.source.authorizationChanged()
expect(refused.manager.starts == 1, "a grant while running starts updates")
refused.source.locationFailed(CLError(.locationUnknown))
refused.source.locationFailed(CLError(.denied))
refused.source.locationFailed(CLError(.network))
refused.manager.authorization = .restricted
refused.source.authorizationChanged()
refused.onRide()
expect(
  refused.events.providers == ["denied", "waiting", "denied", "unavailable", "restricted"],
  "the provider follows authorization and errors: \(refused.events.providers)")
expect(refused.manager.stops == 1, "a revoked authorization ends updates")

let stopped = Rig()
stopped.source.start()
stopped.onMain()
stopped.source.stop()
stopped.onMain()
stopped.source.locationsReceived([location()])
stopped.onRide()
expect(stopped.events.fixes.isEmpty, "a fix after stop is dropped")
print("AppleLocationSource: 38 assertions passed")
