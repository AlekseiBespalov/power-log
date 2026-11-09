import Foundation
import HealthKit
import PowerLogCore

struct AppleHealthAnchoredPage {
  let samples: [HealthQuerySample]
  let anchor: Data?
}

enum AppleHealthReadError: Error {
  case cursor
}

protocol AppleHealthStore: AnyObject {
  var available: Bool { get }
  var series: HealthSeriesQueries { get }
  func writeStatus(_ type: HKObjectType) -> HKAuthorizationStatus
  func authorize(share: Bool, completion: @escaping (Error?) -> Void)
  func anchored(
    _ metric: AppleHealthTypes.Metric, start: Date, end: Date, anchor: Data?, limit: Int,
    completion: @escaping (Result<AppleHealthAnchoredPage, Error>) -> Void)
}

final class AppleHealthProvider: NSObject, ApplicationHealthProvider {
  static let pageSize = 256

  let kind: DomainHealthProviderKind = .applehealth
  let access: ApplicationHealthAccess
  let reader: ApplicationHealthReader
  let session: ApplicationAppleWorkoutSession?
  let writer: ApplicationHealthConnectWriter? = nil

  init(store: AppleHealthStore, session: WorkoutSession, queue: DispatchQueue, clock: ApplicationClock) {
    access = AppleHealthAccess(store: store, queue: queue)
    reader = AppleHealthReader(store: store, queue: queue)
    self.session = AppleWorkoutSessionAdapter(session: session, queue: queue, clock: clock)
  }

  #if os(iOS)
    convenience init(queue: DispatchQueue, clock: ApplicationClock) {
      let store = HKHealthStore()
      self.init(
        store: HealthKitHealthStore(store: store), session: WorkoutSession(store: store, queue: queue),
        queue: queue, clock: clock)
    }
  #endif

  static func portError(_ error: Error) -> ApplicationPortError {
    if error is AppleHealthReadError { return ApplicationPortError(kind: .invalid, platform: nil) }
    if let session = error as? WorkoutSessionError {
      switch session {
      case .busy: return ApplicationPortError(kind: .busy, platform: nil)
      case .unavailable: return ApplicationPortError(kind: .unsupported, platform: nil)
      case .unknownQuantity: return ApplicationPortError(kind: .invalid, platform: nil)
      case .noSession, .failed: return ApplicationPortError(kind: .failed, platform: nil)
      }
    }
    let ns = error as NSError
    let platform = DomainPlatformCode(domain: ns.domain, code: String(ns.code))
    guard ns.domain == HKErrorDomain, let code = HKError.Code(rawValue: ns.code) else {
      return ApplicationPortError(kind: .failed, platform: platform)
    }
    let kind: ApplicationPortErrorKind
    switch code {
    case .errorAuthorizationDenied, .errorAuthorizationNotDetermined: kind = .denied
    case .errorDatabaseInaccessible: kind = .locked
    case .errorHealthDataUnavailable, .errorHealthDataRestricted: kind = .unsupported
    case .errorInvalidArgument: kind = .invalid
    default: kind = .failed
    }
    return ApplicationPortError(kind: kind, platform: platform)
  }
}

final class AppleHealthAccess: NSObject, ApplicationHealthAccess {
  private let store: AppleHealthStore
  private let queue: DispatchQueue

  init(store: AppleHealthStore, queue: DispatchQueue) {
    self.store = store
    self.queue = queue
  }

  func availability() -> ApplicationHealthAvailability { store.available ? .available : .unsupported }

  func grants(reply: ApplicationReply<ApplicationHealthGrants>) {
    queue.async { reply.ok(value: self.current()) }
  }

  func request(scope: ApplicationHealthScope, reply: ApplicationReply<KotlinUnit>) {
    store.authorize(share: !scope.write.isEmpty) { error in
      if let error {
        reply.failed(error: AppleHealthProvider.portError(error))
      } else {
        reply.ok(value: KotlinUnit())
      }
    }
  }

  private func current() -> ApplicationHealthGrants {
    var write: [DomainHealthComponent: ApplicationPermissionState] = [:]
    for component in [
      DomainHealthComponent.workout, .route, .laps, .riderpower, .cyclingcadence, .cyclingspeed, .cyclingdistance,
      .association,
    ] {
      if let type = Self.type(component) { write[component] = Self.state(store.writeStatus(type)) }
    }
    let read = Dictionary(
      uniqueKeysWithValues: AppleHealthTypes.readback.map { ($0.key, ApplicationPermissionState.hidden) })
    return ApplicationHealthGrants(
      write: write, read: read, route: Self.state(store.writeStatus(HKSeriesType.workoutRoute())), backgroundRead: nil,
      readableFromUtcUs: nil)
  }

  static func type(_ component: DomainHealthComponent) -> HKObjectType? {
    switch component {
    case .workout, .laps, .association: return HKObjectType.workoutType()
    case .route: return HKSeriesType.workoutRoute()
    case .riderpower: return HKQuantityType.quantityType(forIdentifier: .cyclingPower)
    case .cyclingcadence: return HKQuantityType.quantityType(forIdentifier: .cyclingCadence)
    case .cyclingspeed: return HKQuantityType.quantityType(forIdentifier: .cyclingSpeed)
    case .cyclingdistance: return HKQuantityType.quantityType(forIdentifier: .distanceCycling)
    default: return nil
    }
  }

  static func state(_ status: HKAuthorizationStatus) -> ApplicationPermissionState {
    switch status {
    case .sharingAuthorized: .granted
    case .sharingDenied: .denied
    default: .notdetermined
    }
  }
}

final class AppleHealthReader: NSObject, ApplicationHealthReader {
  private let store: AppleHealthStore
  private let queue: DispatchQueue

  init(store: AppleHealthStore, queue: DispatchQueue) {
    self.store = store
    self.queue = queue
  }

  func read(
    metric: String, startUtcUs: Int64, endUtcUs: Int64, cursor: String?,
    reply: ApplicationReply<ApplicationHealthOriginalPage>
  ) {
    let invalid = ApplicationPortError(kind: .invalid, platform: nil)
    guard let definition = AppleHealthTypes.readback.first(where: { $0.key == metric }) else {
      return queue.async { reply.failed(error: invalid) }
    }
    var anchor: Data?
    if let cursor {
      guard let decoded = Data(base64Encoded: cursor) else { return queue.async { reply.failed(error: invalid) } }
      anchor = decoded
    }
    store.anchored(
      definition, start: AppleTime.date(startUtcUs), end: AppleTime.date(endUtcUs), anchor: anchor,
      limit: AppleHealthProvider.pageSize
    ) { [store, queue] result in
      switch result {
      case .failure(let error): reply.failed(error: AppleHealthProvider.portError(error))
      case .success(let page):
        let next = page.samples.count >= AppleHealthProvider.pageSize ? page.anchor?.base64EncodedString() : nil
        HealthSeriesExpander(queries: store.series, queue: queue).expand(page.samples, metric: definition) {
          switch $0 {
          case .failure(let error): reply.failed(error: AppleHealthProvider.portError(error))
          case .success(let originals):
            reply.ok(value: ApplicationHealthOriginalPage(samples: originals.map(AppleTime.original), next: next))
          }
        }
      }
    }
  }
}

final class AppleWorkoutSessionAdapter: NSObject, ApplicationAppleWorkoutSession {
  private let session: WorkoutSession
  private let queue: DispatchQueue
  private let clock: ApplicationClock

  init(session: WorkoutSession, queue: DispatchQueue, clock: ApplicationClock) {
    self.session = session
    self.queue = queue
    self.clock = clock
  }

  func attach(sessionEvents: ApplicationWorkoutSessionEvents) {
    queue.async { [self] in
      session.onState = { state, date in
        sessionEvents.stateChanged(state: Self.state(state), atUtcUs: AppleTime.micros(date))
      }
      session.onFailure = { sessionEvents.failed(error: AppleHealthProvider.portError($0)) }
      session.heartRateStamp = { [clock] in clock.read() }
      session.onStampedHeartRate = { [clock] rate, stamp in
        sessionEvents.heartRateReceived(
          heartRate: ApplicationLiveHeartRate(bpm: rate.bpm, utcUs: AppleTime.micros(rate.at)),
          at: stamp as? DomainClockReading ?? clock.read())
      }
      session.onSamplesCollected = { [clock] originals in
        sessionEvents.samplesCollected(samples: originals.map(AppleTime.original), at: clock.read())
      }
    }
  }

  func begin(
    workout: DomainHealthPlanWorkout, options: ApplicationSessionOptions, reply: ApplicationReply<KotlinUnit>
  ) {
    let plan = HealthPlanWorkout(
      rideID: workout.rideId, syncID: workout.syncId, syncVersion: Int(workout.syncVersion),
      start: AppleTime.date(workout.startUtcUs))
    queue.async { [self] in
      session.begin(plan, indoor: options.indoor, route: options.route) { Self.settle(reply, $0) }
    }
  }

  func pause() { queue.async { [self] in session.pause() } }

  func resume() { queue.async { [self] in session.resume() } }

  func add(quantities: [DomainHealthPlanQuantity], reply: ApplicationReply<KotlinUnit>) {
    let plans = quantities.map {
      HealthPlanQuantity(
        syncID: $0.syncId, quantity: AppleTime.serialName($0.quantity), value: $0.value,
        start: AppleTime.date($0.startUtcUs), end: AppleTime.date($0.endUtcUs))
    }
    queue.async { [self] in session.add(plans) { Self.settle(reply, $0) } }
  }

  func addLap(atUtcUs: Int64, reply: ApplicationReply<KotlinUnit>) {
    queue.async { [self] in session.addLap(at: AppleTime.date(atUtcUs)) { Self.settle(reply, $0) } }
  }

  func finish(atUtcUs: Int64, route: DomainHealthPlanRoute?, reply: ApplicationReply<ApplicationFinishedWorkout>) {
    let plan = route.map { route in
      HealthPlanRoute(
        syncID: route.syncId,
        points: route.points.map {
          RoutePoint(
            at: AppleTime.date($0.utcUs), latitude: $0.latitude, longitude: $0.longitude,
            altitudeM: $0.altitudeM?.doubleValue, horizontalAccuracyM: $0.horizontalAccuracyM,
            verticalAccuracyM: $0.verticalAccuracyM?.doubleValue, speedMps: $0.speedMps?.doubleValue,
            courseDeg: $0.courseDeg?.doubleValue)
        })
    }
    queue.async { [self] in
      session.finish(at: AppleTime.date(atUtcUs), route: plan) { result in
        switch result {
        case .success(let (id, routeSaved)):
          reply.ok(value: ApplicationFinishedWorkout(workoutId: id, routeSaved: routeSaved))
        case .failure(let error): reply.failed(error: AppleHealthProvider.portError(error))
        }
      }
    }
  }

  func discard(atUtcUs: Int64) { queue.async { [self] in session.discard(at: AppleTime.date(atUtcUs)) } }

  func recoverOrphan(reply: ApplicationReply<NSString>) {
    queue.async { [self] in session.recoverOrphan { reply.ok(value: $0.map { $0 as NSString }) } }
  }

  func savedWorkout(rideId: String, reply: ApplicationReply<NSString>) {
    queue.async { [self] in
      session.savedWorkout(rideID: rideId) { result in
        switch result {
        case .success(let id): reply.ok(value: id.map { $0 as NSString })
        case .failure(let error): reply.failed(error: AppleHealthProvider.portError(error))
        }
      }
    }
  }

  private static func settle(_ reply: ApplicationReply<KotlinUnit>, _ error: Error?) {
    if let error { reply.failed(error: AppleHealthProvider.portError(error)) } else { reply.ok(value: KotlinUnit()) }
  }

  static func state(_ state: HKWorkoutSessionState) -> ApplicationSessionState {
    switch state {
    case .notStarted: .notstarted
    case .prepared: .prepared
    case .running: .running
    case .paused: .paused
    case .stopped: .stopped
    case .ended: .ended
    @unknown default: .ended
    }
  }
}

enum AppleTime {
  static func date(_ micros: Int64) -> Date { Date(timeIntervalSince1970: Double(micros) / 1_000_000) }

  static func micros(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1_000_000).rounded(.down)) }

  static func original(_ original: HealthOriginal) -> ApplicationHealthOriginal {
    ApplicationHealthOriginal(
      sampleId: original.sampleID, member: original.member.map { KotlinInt(int: Int32($0)) }, metric: original.metric,
      value: KotlinDouble(double: original.value), startUtcUs: micros(original.start), endUtcUs: micros(original.end),
      dataOrigin: original.dataOrigin, syncId: original.syncID)
  }

  static func serialName(_ quantity: DomainHealthQuantity) -> String {
    switch quantity {
    case .riderpower: "rider-power"
    case .cyclingcadence: "cycling-cadence"
    case .cyclingspeed: "cycling-speed"
    default: "cycling-distance"
    }
  }
}

#if os(iOS)
  final class HealthKitHealthStore: AppleHealthStore {
    private let store: HKHealthStore
    let series: HealthSeriesQueries

    init(store: HKHealthStore) {
      self.store = store
      series = HealthKitSeriesQueries(store: store)
    }

    var available: Bool { HKHealthStore.isHealthDataAvailable() }

    func writeStatus(_ type: HKObjectType) -> HKAuthorizationStatus { store.authorizationStatus(for: type) }

    func authorize(share: Bool, completion: @escaping (Error?) -> Void) {
      store.requestAuthorization(toShare: share ? AppleHealthTypes.shareTypes : [], read: AppleHealthTypes.readTypes) {
        success, error in completion(success ? nil : error ?? WorkoutSessionError.failed("authorization"))
      }
    }

    func anchored(
      _ metric: AppleHealthTypes.Metric, start: Date, end: Date, anchor: Data?, limit: Int,
      completion: @escaping (Result<AppleHealthAnchoredPage, Error>) -> Void
    ) {
      var previous: HKQueryAnchor?
      if let anchor {
        guard let decoded = try? NSKeyedUnarchiver.unarchivedObject(ofClass: HKQueryAnchor.self, from: anchor) else {
          return completion(.failure(AppleHealthReadError.cursor))
        }
        previous = decoded
      }
      guard let type = metric.type else {
        return completion(.success(AppleHealthAnchoredPage(samples: [], anchor: nil)))
      }
      let query = HKAnchoredObjectQuery(
        type: type, predicate: HKQuery.predicateForSamples(withStart: start, end: end, options: []), anchor: previous,
        limit: limit
      ) { _, samples, _, next, error in
        if let error { return completion(.failure(error)) }
        let quantities = (samples ?? []).compactMap { $0 as? HKQuantitySample }.map {
          HealthQuerySample($0, metric: metric)
        }
        let data = next.flatMap { try? NSKeyedArchiver.archivedData(withRootObject: $0, requiringSecureCoding: true) }
        completion(.success(AppleHealthAnchoredPage(samples: quantities, anchor: data)))
      }
      store.execute(query)
    }
  }
#endif
