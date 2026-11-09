import Foundation
import HealthKit

#if os(iOS) || os(watchOS)
  import CoreLocation
#endif

enum WorkoutSessionError: Error, Equatable {
  case unavailable
  case busy
  case noSession
  case unknownQuantity(String)
  case failed(String)
}

protocol WorkoutSessionSeamEvents: AnyObject {
  func stateChanged(_ state: HKWorkoutSessionState, at date: Date)
  func failed(_ error: Error)
  func heartRate(_ heartRate: LiveHeartRate)
}

protocol WorkoutStoreSeam: AnyObject {
  var available: Bool { get }
  func authorize(share: Bool, completion: @escaping (Bool, Error?) -> Void)
  func session(indoor: Bool, route: Bool, events: WorkoutSessionSeamEvents) throws -> WorkoutSessionSeam
  func recover(completion: @escaping (WorkoutSessionSeam?) -> Void)
  func savedWorkout(rideID: String, completion: @escaping (String?, Error?) -> Void)
}

protocol WorkoutSessionSeam: AnyObject {
  var state: HKWorkoutSessionState { get }
  var externalID: String? { get }
  func addMetadata(_ workout: HealthPlanWorkout, completion: @escaping (Bool, Error?) -> Void)
  func startActivity(at date: Date)
  func beginCollection(at date: Date, completion: @escaping (Bool, Error?) -> Void)
  func startQueries(from date: Date, deliver: @escaping ([HealthOriginal]) -> Void)
  func stopQueries()
  func pause()
  func resume()
  func add(_ quantities: [HealthPlanQuantity], workout: HealthPlanWorkout, completion: @escaping (Bool, Error?) -> Void)
  func addLap(at date: Date, completion: @escaping (Bool, Error?) -> Void)
  func stopActivity(at date: Date)
  func insertRoute(_ points: [RoutePoint], completion: @escaping (Bool, Error?) -> Void)
  func endCollection(at date: Date, completion: @escaping (Bool, Error?) -> Void)
  func finishWorkout(completion: @escaping (String?, Error?) -> Void)
  func finishRoute(_ route: HealthPlanRoute, workout: HealthPlanWorkout, completion: @escaping (Bool, Error?) -> Void)
  func discardWorkout()
  func end()
}

final class WorkoutSession {
  private final class Current: WorkoutSessionSeamEvents {
    weak var owner: WorkoutSession?
    var seam: WorkoutSessionSeam?
    let workout: HealthPlanWorkout
    var stopped: (() -> Void)?
    var beginning: ((Error?) -> Void)?
    var finishing = false

    init(owner: WorkoutSession, workout: HealthPlanWorkout) {
      self.owner = owner
      self.workout = workout
    }

    func answerBegin(_ error: Error?) {
      let answer = beginning
      beginning = nil
      answer?(error)
    }

    func stateChanged(_ state: HKWorkoutSessionState, at date: Date) {
      owner?.post(self) { owner in
        owner.onState?(state, date)
        if state == .stopped || state == .ended, let stopped = self.stopped {
          self.stopped = nil
          stopped()
        } else if state == .ended, !self.finishing {
          owner.release(self, WorkoutSessionError.failed("ended"))
        }
      }
    }

    func failed(_ error: Error) {
      owner?.post(self) { owner in
        owner.onFailure?(error)
        if !self.finishing { owner.release(self, error) }
      }
    }

    func heartRate(_ heartRate: LiveHeartRate) {
      let stamp = owner?.heartRateStamp?()
      owner?.post(self) { owner in
        owner.onHeartRateReceived?(heartRate)
        owner.onStampedHeartRate?(heartRate, stamp)
      }
    }
  }

  var onState: ((HKWorkoutSessionState, Date) -> Void)?
  var onFailure: ((Error) -> Void)?
  var onHeartRateReceived: ((LiveHeartRate) -> Void)?
  var onSamplesCollected: (([HealthOriginal]) -> Void)?
  var heartRateStamp: (() -> AnyObject)?
  var onStampedHeartRate: ((LiveHeartRate, AnyObject?) -> Void)?

  private let store: WorkoutStoreSeam
  private let queue: DispatchQueue
  private var current: Current?
  private var recovering = 0

  init(seam: WorkoutStoreSeam, queue: DispatchQueue) {
    self.store = seam
    self.queue = queue
  }

  #if os(iOS) || os(watchOS)
    convenience init(store: HKHealthStore, queue: DispatchQueue) {
      self.init(seam: HealthKitWorkoutStore(store: store, queue: queue), queue: queue)
    }
  #endif

  func authorize(share: Bool, completion: @escaping (Error?) -> Void) {
    guard store.available else { return completion(WorkoutSessionError.unavailable) }
    store.authorize(share: share) { success, error in
      self.queue.async { completion(success ? nil : error ?? WorkoutSessionError.failed("authorization")) }
    }
  }

  func begin(_ workout: HealthPlanWorkout, indoor: Bool, route: Bool, completion: @escaping (Error?) -> Void) {
    guard current == nil, recovering == 0 else { return completion(WorkoutSessionError.busy) }
    guard store.available else { return completion(WorkoutSessionError.unavailable) }
    let state = Current(owner: self, workout: workout)
    let seam: WorkoutSessionSeam
    do { seam = try store.session(indoor: indoor, route: route, events: state) } catch { return completion(error) }
    state.seam = seam
    state.beginning = completion
    current = state
    func failed(_ error: Error?) {
      seam.discardWorkout()
      seam.end()
      if current === state { current = nil }
      state.answerBegin(error ?? WorkoutSessionError.failed("begin"))
    }
    seam.addMetadata(workout) { success, error in
      self.post(state) { _ in
        guard success else { return failed(error) }
        seam.startActivity(at: workout.start)
        seam.beginCollection(at: workout.start) { success, error in
          self.post(state) { owner in
            guard success else { return failed(error) }
            seam.startQueries(from: workout.start) { originals in
              owner.post(state) { $0.onSamplesCollected?(originals) }
            }
            state.answerBegin(nil)
          }
        }
      }
    }
  }

  func pause() { current?.seam?.pause() }

  func resume() { current?.seam?.resume() }

  func add(_ quantities: [HealthPlanQuantity], completion: @escaping (Error?) -> Void) {
    guard let state = current, let seam = state.seam else { return completion(WorkoutSessionError.noSession) }
    if let unknown = quantities.first(where: { AppleHealthTypes.quantity($0.quantity) == nil }) {
      return completion(WorkoutSessionError.unknownQuantity(unknown.quantity))
    }
    seam.add(quantities, workout: state.workout) { success, error in
      self.post(state) { _ in completion(success ? nil : error ?? WorkoutSessionError.failed("add")) }
    }
  }

  func addLap(at date: Date, completion: @escaping (Error?) -> Void) {
    guard let state = current, let seam = state.seam else { return completion(WorkoutSessionError.noSession) }
    seam.addLap(at: date) { success, error in
      self.post(state) { _ in completion(success ? nil : error ?? WorkoutSessionError.failed("addLap")) }
    }
  }

  func finish(at date: Date, route: HealthPlanRoute?, completion: @escaping (Result<(String, Bool), Error>) -> Void) {
    guard let state = current, let seam = state.seam else { return completion(.failure(WorkoutSessionError.noSession)) }
    state.finishing = true
    seam.stopQueries()
    func done(_ result: Result<(String, Bool), Error>) {
      seam.end()
      if current === state { current = nil }
      completion(result)
    }
    func finishWorkout(routeInserted: Bool) {
      seam.endCollection(at: date) { success, error in
        self.post(state) { _ in
          guard success else { return done(.failure(error ?? WorkoutSessionError.failed("endCollection"))) }
          seam.finishWorkout { id, error in
            self.post(state) { _ in
              guard let id else { return done(.failure(error ?? WorkoutSessionError.failed("finishWorkout"))) }
              guard routeInserted, let route else { return done(.success((id.lowercased(), false))) }
              seam.finishRoute(route, workout: state.workout) { saved, _ in
                self.post(state) { _ in done(.success((id.lowercased(), saved))) }
              }
            }
          }
        }
      }
    }
    func insert(_ points: [RoutePoint], from offset: Int) {
      guard offset < points.count else { return finishWorkout(routeInserted: true) }
      let end = min(offset + WorkoutSession.routeChunk, points.count)
      seam.insertRoute(Array(points[offset..<end])) { success, _ in
        self.post(state) { _ in success ? insert(points, from: end) : finishWorkout(routeInserted: false) }
      }
    }
    func stopped() {
      if let route, !route.points.isEmpty { insert(route.points, from: 0) } else { finishWorkout(routeInserted: false) }
    }
    if seam.state == .stopped || seam.state == .ended {
      stopped()
    } else {
      state.stopped = stopped
      seam.stopActivity(at: date)
    }
  }

  func discard(at date: Date) {
    guard let state = current, let seam = state.seam else { return }
    current = nil
    seam.stopQueries()
    seam.stopActivity(at: date)
    seam.discardWorkout()
    seam.end()
    state.answerBegin(WorkoutSessionError.noSession)
  }

  func recoverOrphan(completion: @escaping (String?) -> Void) {
    recovering += 1
    store.recover { seam in
      self.queue.async {
        self.recovering -= 1
        guard let seam else { return completion(nil) }
        let id = seam.externalID
        seam.stopActivity(at: Date())
        seam.discardWorkout()
        seam.end()
        completion(id)
      }
    }
  }

  func savedWorkout(rideID: String, completion: @escaping (Result<String?, Error>) -> Void) {
    store.savedWorkout(rideID: rideID) { id, error in
      self.queue.async {
        if let error { completion(.failure(error)) } else { completion(.success(id?.lowercased())) }
      }
    }
  }

  private func release(_ state: Current, _ error: Error) {
    guard current === state, let seam = state.seam else { return }
    current = nil
    seam.stopQueries()
    seam.discardWorkout()
    seam.end()
    state.answerBegin(error)
  }

  private func post(_ state: Current, _ block: @escaping (WorkoutSession) -> Void) {
    queue.async { [weak self] in
      guard let self, self.current === state else { return }
      block(self)
    }
  }

  static let routeChunk = 1_000
}

struct HealthQuerySample: Equatable {
  let id: UUID
  let count: Int
  let value: Double
  let start: Date
  let end: Date
  let origin: String
  let syncID: String?
}

protocol HealthSeriesQueries: AnyObject {
  func members(
    of sample: HealthQuerySample, metric: AppleHealthTypes.Metric, member: @escaping (Double, Date, Date) -> Void,
    done: @escaping (Error?) -> Void
  ) -> AnyObject?
  func stop(_ query: AnyObject)
}

final class HealthSeriesExpander {
  private let queries: HealthSeriesQueries
  private let queue: DispatchQueue
  private var pending:
    [(
      samples: [HealthQuerySample], metric: AppleHealthTypes.Metric,
      done: (Result<[HealthOriginal], Error>) -> Void
    )] = []
  private var running = false
  private var series: AnyObject?
  private var generation = 0

  init(queries: HealthSeriesQueries, queue: DispatchQueue) {
    self.queries = queries
    self.queue = queue
  }

  func expand(
    _ samples: [HealthQuerySample], metric: AppleHealthTypes.Metric,
    done: @escaping (Result<[HealthOriginal], Error>) -> Void
  ) {
    queue.async {
      self.pending.append((samples, metric, done))
      self.next()
    }
  }

  func stop() {
    queue.async {
      self.generation += 1
      self.pending.removeAll()
      if let series = self.series { self.queries.stop(series) }
      self.series = nil
      self.running = false
    }
  }

  private func next() {
    guard !running, !pending.isEmpty else { return }
    running = true
    let job = pending.removeFirst()
    let token = generation
    var originals: [HealthOriginal] = []
    func finish(_ result: Result<[HealthOriginal], Error>) {
      running = false
      series = nil
      job.done(result)
      queue.async { self.next() }
    }
    func original(
      _ sample: HealthQuerySample, _ member: Int?, _ value: Double, _ start: Date, _ end: Date
    ) -> HealthOriginal {
      HealthOriginal(
        sampleID: sample.id.uuidString.lowercased(), member: member, metric: job.metric.key, value: value,
        start: start, end: end, dataOrigin: sample.origin, syncID: sample.syncID)
    }
    func one(_ first: Int) {
      guard token == generation else { return }
      var index = first
      while index < job.samples.count, job.samples[index].count <= 1 {
        let sample = job.samples[index]
        if sample.value.isFinite { originals.append(original(sample, nil, sample.value, sample.start, sample.end)) }
        index += 1
      }
      guard index < job.samples.count else { return finish(.success(originals)) }
      let sample = job.samples[index]
      var member = 0
      var settled = false
      series = queries.members(
        of: sample, metric: job.metric,
        member: { value, start, end in
          self.queue.async {
            guard token == self.generation, !settled else { return }
            if value.isFinite { originals.append(original(sample, member, value, start, end)) }
            member += 1
          }
        },
        done: { error in
          self.queue.async {
            guard token == self.generation, !settled else { return }
            settled = true
            if let error { return finish(.failure(error)) }
            self.series = nil
            one(index + 1)
          }
        })
    }
    one(0)
  }
}

#if os(iOS) || os(watchOS)
  final class HealthKitWorkoutStore: WorkoutStoreSeam {
    private let store: HKHealthStore
    private let queue: DispatchQueue

    init(store: HKHealthStore, queue: DispatchQueue) {
      self.store = store
      self.queue = queue
    }

    var available: Bool { HKHealthStore.isHealthDataAvailable() }

    func authorize(share: Bool, completion: @escaping (Bool, Error?) -> Void) {
      store.requestAuthorization(toShare: share ? AppleHealthTypes.shareTypes : [], read: AppleHealthTypes.readTypes) {
        success, error in completion(success, error)
      }
    }

    func session(indoor: Bool, route: Bool, events: WorkoutSessionSeamEvents) throws -> WorkoutSessionSeam {
      try HealthKitWorkoutSession(store: store, queue: queue, indoor: indoor, route: route, events: events)
    }

    func recover(completion: @escaping (WorkoutSessionSeam?) -> Void) {
      store.recoverActiveWorkoutSession { session, _ in
        completion(session.map { HealthKitWorkoutSession(recovered: $0, store: self.store, queue: self.queue) })
      }
    }

    func savedWorkout(rideID: String, completion: @escaping (String?, Error?) -> Void) {
      let query = HKSampleQuery(
        sampleType: .workoutType(),
        predicate: HKQuery.predicateForObjects(withMetadataKey: HKMetadataKeyExternalUUID, allowedValues: [rideID]),
        limit: 1, sortDescriptors: nil
      ) { _, samples, error in completion(samples?.first?.uuid.uuidString, error) }
      store.execute(query)
    }
  }

  final class HealthKitWorkoutSession: NSObject, WorkoutSessionSeam, HKWorkoutSessionDelegate,
    HKLiveWorkoutBuilderDelegate
  {
    static let rideMetadataKey = "PowerLogRideID"

    private let store: HKHealthStore
    private let session: HKWorkoutSession
    private let builder: HKLiveWorkoutBuilder
    private let route: HKWorkoutRouteBuilder?
    private weak var events: WorkoutSessionSeamEvents?
    private let expander: HealthSeriesExpander
    private var queries: [HKQuery] = []
    private var finished: HKWorkout?

    init(
      store: HKHealthStore, queue: DispatchQueue, indoor: Bool, route: Bool, events: WorkoutSessionSeamEvents
    ) throws {
      let configuration = HKWorkoutConfiguration()
      configuration.activityType = .cycling
      configuration.locationType = indoor ? .indoor : .outdoor
      self.store = store
      session = try HKWorkoutSession(healthStore: store, configuration: configuration)
      builder = session.associatedWorkoutBuilder()
      let source = HKLiveWorkoutDataSource(healthStore: store, workoutConfiguration: configuration)
      for identifier in AppleHealthTypes.liveQuantities {
        if let type = HKQuantityType.quantityType(forIdentifier: identifier) { source.disableCollection(for: type) }
      }
      builder.dataSource = source
      self.route = route ? HKWorkoutRouteBuilder(healthStore: store, device: .local()) : nil
      self.events = events
      expander = HealthSeriesExpander(queries: HealthKitSeriesQueries(store: store), queue: queue)
      super.init()
      session.delegate = self
      builder.delegate = self
    }

    init(recovered: HKWorkoutSession, store: HKHealthStore, queue: DispatchQueue) {
      self.store = store
      session = recovered
      builder = recovered.associatedWorkoutBuilder()
      route = nil
      expander = HealthSeriesExpander(queries: HealthKitSeriesQueries(store: store), queue: queue)
      super.init()
    }

    var state: HKWorkoutSessionState { session.state }

    var externalID: String? { builder.metadata[HKMetadataKeyExternalUUID] as? String }

    func addMetadata(_ workout: HealthPlanWorkout, completion: @escaping (Bool, Error?) -> Void) {
      builder.addMetadata(
        [
          HKMetadataKeyExternalUUID: workout.rideID, HKMetadataKeySyncIdentifier: workout.syncID,
          HKMetadataKeySyncVersion: workout.syncVersion, Self.rideMetadataKey: workout.rideID,
        ], completion: completion)
    }

    func startActivity(at date: Date) { session.startActivity(with: date) }

    func beginCollection(at date: Date, completion: @escaping (Bool, Error?) -> Void) {
      builder.beginCollection(withStart: date, completion: completion)
    }

    func startQueries(from date: Date, deliver: @escaping ([HealthOriginal]) -> Void) {
      stopQueries()
      let predicate = HKQuery.predicateForSamples(withStart: date, end: nil, options: [])
      for metric in AppleHealthTypes.readback {
        guard let type = metric.type else { continue }
        let handle: ([HKSample]?) -> Void = { [expander] samples in
          let quantities = (samples ?? []).compactMap { $0 as? HKQuantitySample }.map {
            HealthQuerySample($0, metric: metric)
          }
          expander.expand(quantities, metric: metric) { result in
            if case .success(let originals) = result, !originals.isEmpty { deliver(originals) }
          }
        }
        let query = HKAnchoredObjectQuery(type: type, predicate: predicate, anchor: nil, limit: HKObjectQueryNoLimit) {
          _, samples, _, _, _ in handle(samples)
        }
        query.updateHandler = { _, samples, _, _, _ in handle(samples) }
        queries.append(query)
        store.execute(query)
      }
    }

    func stopQueries() {
      for query in queries { store.stop(query) }
      queries.removeAll()
      expander.stop()
    }

    func pause() { session.pause() }

    func resume() { session.resume() }

    func add(
      _ quantities: [HealthPlanQuantity], workout: HealthPlanWorkout, completion: @escaping (Bool, Error?) -> Void
    ) {
      let samples = quantities.compactMap { plan -> HKQuantitySample? in
        guard let (identifier, unit) = AppleHealthTypes.quantity(plan.quantity),
          let type = HKQuantityType.quantityType(forIdentifier: identifier)
        else { return nil }
        return HKQuantitySample(
          type: type, quantity: HKQuantity(unit: unit, doubleValue: plan.value), start: plan.start, end: plan.end,
          metadata: [
            HKMetadataKeySyncIdentifier: plan.syncID, HKMetadataKeySyncVersion: workout.syncVersion,
            Self.rideMetadataKey: workout.rideID,
          ])
      }
      builder.add(samples, completion: completion)
    }

    func addLap(at date: Date, completion: @escaping (Bool, Error?) -> Void) {
      builder.addWorkoutEvents(
        [HKWorkoutEvent(type: .lap, dateInterval: DateInterval(start: date, duration: 0), metadata: nil)],
        completion: completion)
    }

    func stopActivity(at date: Date) { session.stopActivity(with: date) }

    func insertRoute(_ points: [RoutePoint], completion: @escaping (Bool, Error?) -> Void) {
      guard let route else { return completion(false, nil) }
      route.insertRouteData(
        points.map {
          CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude),
            altitude: $0.altitudeM ?? 0, horizontalAccuracy: $0.horizontalAccuracyM,
            verticalAccuracy: $0.altitudeM == nil ? -1 : $0.verticalAccuracyM ?? -1, course: $0.courseDeg ?? -1,
            speed: $0.speedMps ?? -1, timestamp: $0.at)
        }, completion: completion)
    }

    func endCollection(at date: Date, completion: @escaping (Bool, Error?) -> Void) {
      builder.endCollection(withEnd: date, completion: completion)
    }

    func finishWorkout(completion: @escaping (String?, Error?) -> Void) {
      builder.finishWorkout { workout, error in
        self.finished = workout
        completion(workout?.uuid.uuidString, error)
      }
    }

    func finishRoute(
      _ route: HealthPlanRoute, workout: HealthPlanWorkout, completion: @escaping (Bool, Error?) -> Void
    ) {
      guard let builder = self.route, let saved = finished else { return completion(false, nil) }
      builder.finishRoute(
        with: saved,
        metadata: [
          HKMetadataKeySyncIdentifier: route.syncID, HKMetadataKeySyncVersion: workout.syncVersion,
          Self.rideMetadataKey: workout.rideID,
        ]
      ) { route, error in completion(route != nil, error) }
    }

    func discardWorkout() { builder.discardWorkout() }

    func end() { session.end() }

    func workoutSession(
      _ workoutSession: HKWorkoutSession, didChangeTo toState: HKWorkoutSessionState,
      from fromState: HKWorkoutSessionState, date: Date
    ) { events?.stateChanged(toState, at: date) }

    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) { events?.failed(error) }

    func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {}

    func workoutBuilder(_ workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOf collectedTypes: Set<HKSampleType>) {
      guard let type = HKQuantityType.quantityType(forIdentifier: .heartRate), collectedTypes.contains(type),
        let statistics = workoutBuilder.statistics(for: type), let quantity = statistics.mostRecentQuantity(),
        let at = statistics.mostRecentQuantityDateInterval()?.end
      else { return }
      let bpm = quantity.doubleValue(for: .count().unitDivided(by: .minute()))
      if bpm.isFinite { events?.heartRate(LiveHeartRate(bpm: bpm, at: at)) }
    }
  }

  final class HealthKitSeriesQueries: HealthSeriesQueries {
    private let store: HKHealthStore

    init(store: HKHealthStore) { self.store = store }

    func members(
      of sample: HealthQuerySample, metric: AppleHealthTypes.Metric, member: @escaping (Double, Date, Date) -> Void,
      done: @escaping (Error?) -> Void
    ) -> AnyObject? {
      guard let type = metric.type else {
        done(nil)
        return nil
      }
      let query = HKQuantitySeriesSampleQuery(
        quantityType: type, predicate: HKQuery.predicateForObject(with: sample.id)
      ) {
        _, quantity, interval, _, last, error in
        if let error { return done(error) }
        if let quantity, let interval {
          member(quantity.doubleValue(for: metric.unit), interval.start, interval.end)
        }
        if last || quantity == nil { done(nil) }
      }
      store.execute(query)
      return query
    }

    func stop(_ query: AnyObject) {
      if let query = query as? HKQuery { store.stop(query) }
    }
  }

  extension HealthQuerySample {
    init(_ sample: HKQuantitySample, metric: AppleHealthTypes.Metric) {
      self.init(
        id: sample.uuid, count: sample.count, value: sample.quantity.doubleValue(for: metric.unit),
        start: sample.startDate, end: sample.endDate, origin: sample.sourceRevision.source.bundleIdentifier,
        syncID: sample.metadata?[HKMetadataKeySyncIdentifier] as? String)
    }
  }
#endif
