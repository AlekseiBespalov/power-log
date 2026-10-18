#if os(iOS) || os(watchOS)
  import Foundation
  import HealthKit
  import CoreLocation
  import CryptoKit

  /// One native session and one append-only writer. Uncertain effects are never replayed.
  final class WorkoutNativeHealth: NSObject, HKWorkoutSessionDelegate, HKLiveWorkoutBuilderDelegate {
    static let rideMetadataKey = "PowerLogRideID"
    let store = HKHealthStore()
    let queue: DispatchQueue
    var observation: ((String, WorkoutCompanionObservation) -> Void)?
    var status: ((String, String, String?, String?) -> Void)?
    private(set) var session: HKWorkoutSession?
    private var builder: HKLiveWorkoutBuilder?
    private var route: HKWorkoutRouteBuilder?
    private var options: WorkoutCompanionOptions?
    private var generation = UUID()
    private var queries: [HKQuery] = []
    private var lastPublishedSecond: Int64?
    private var routePoints = WorkoutCompanionRoute()
    private var seriesQueries: [UUID: HKQuery] = [:]
    var liveReadings: ((String, [String: Double], Date) -> Void)?
    private var writes: [(@escaping (Bool, Error?) -> Void) -> Void] = []
    private var writeInFlight = false
    private var uncertain = false
    private var ending: (date: Date, discard: Bool, completion: () -> Void)?
    private var finishing = false
    private var finishTimer: DispatchWorkItem?
    private var pendingStart: ((Result<Void, Error>) -> Void)?
    private var cleanupAttempt: UUID?
    var busy: Bool { session != nil || pendingStart != nil || cleanupAttempt != nil }
    var permissions: [String: Any] {
      let writes = Dictionary(
        uniqueKeysWithValues: WorkoutAppleHealthMetrics.shareTypes.map { type in
          let status = store.authorizationStatus(for: type)
          return (
            type.identifier,
            status == .sharingAuthorized ? "authorized" : status == .sharingDenied ? "denied" : "notDetermined"
          )
        })
      return [
        "available": HKHealthStore.isHealthDataAvailable(), "provider": "appleHealth", "writeAuthorization": writes,
      ]
    }

    init(queue: DispatchQueue) {
      self.queue = queue
      super.init()
    }

    func authorize(writing: Bool = true, completion: @escaping (Result<Void, Error>) -> Void) {
      guard HKHealthStore.isHealthDataAvailable() else {
        completion(.failure(WorkoutCompanionError.invalid("Health is unavailable.")))
        return
      }
      store.requestAuthorization(
        toShare: writing ? WorkoutAppleHealthMetrics.shareTypes : [], read: WorkoutAppleHealthMetrics.readTypes
      ) {
        success, error in
        self.queue.async {
          completion(
            success ? .success(()) : .failure(error ?? WorkoutCompanionError.invalid("Health authorization failed.")))
        }
      }
    }

    func start(_ options: WorkoutCompanionOptions, completion: @escaping (Result<Void, Error>) -> Void) {
      guard !busy else {
        completion(.failure(WorkoutCompanionError.invalid("End the previous Health session first.")))
        return
      }
      self.options = options
      generation = UUID()
      let token = generation
      lastPublishedSecond = nil
      routePoints = WorkoutCompanionRoute()
      uncertain = false
      finishing = false
      pendingStart = completion
      do {
        let configuration = HKWorkoutConfiguration()
        configuration.activityType = .cycling
        configuration.locationType = options.indoor ? .indoor : .outdoor
        let session = try HKWorkoutSession(healthStore: store, configuration: configuration)
        let builder = session.associatedWorkoutBuilder()
        let source = HKLiveWorkoutDataSource(healthStore: store, workoutConfiguration: configuration)
        for id: HKQuantityTypeIdentifier in [.cyclingPower, .cyclingCadence, .cyclingSpeed, .distanceCycling] {
          if let type = HKQuantityType.quantityType(forIdentifier: id) { source.disableCollection(for: type) }
        }
        builder.dataSource = source
        builder.delegate = self
        session.delegate = self
        self.session = session
        self.builder = builder
        if options.saveToHealth && options.recordGPS {
          route = HKWorkoutRouteBuilder(healthStore: store, device: .local())
        }
        // The external ID also lets native-session recovery identify an orphan without restoring an app archive.
        builder.addMetadata([
          HKMetadataKeyExternalUUID: options.id, HKMetadataKeySyncIdentifier: "powerlog:\(options.id):workout",
          HKMetadataKeySyncVersion: 1, Self.rideMetadataKey: options.id,
        ]) { success, error in
          self.queue.async {
            guard token == self.generation else { return }
            guard success else {
              self.failedStart(error)
              return
            }
            session.startActivity(with: options.start)
            builder.beginCollection(withStart: options.start) { success, error in
              self.queue.async {
                guard token == self.generation else { return }
                guard success else {
                  self.failedStart(error)
                  return
                }
                self.startQueries(start: options.start)
                let done = self.pendingStart
                self.pendingStart = nil
                self.report(options.saveToHealth ? "pending" : "notRequested", nil, nil)
                done?(.success(()))
              }
            }
          }
        }
        queue.asyncAfter(deadline: .now() + 10) {
          guard token == self.generation, self.pendingStart != nil else { return }
          self.failedStart(WorkoutCompanionError.invalid("Health Start timed out."))
        }
      } catch { failedStart(error) }
    }

    private func failedStart(_ error: Error?) {
      let done = pendingStart
      pendingStart = nil
      builder?.discardWorkout()
      session?.end()
      clear()
      done?(.failure(error ?? WorkoutCompanionError.invalid("Health Start failed.")))
    }

    func pause() { session?.pause() }
    func resume() { session?.resume() }
    func lap(at date: Date) {
      guard let builder, options?.saveToHealth == true else { return }
      enqueue { completion in
        builder.addWorkoutEvents(
          [HKWorkoutEvent(type: .lap, dateInterval: DateInterval(start: date, end: date), metadata: nil)],
          completion: completion)
      }
    }

    func publish(readings: [String: Double], at date: Date, locations: [WorkoutCompanionLocation]) {
      guard let options, options.saveToHealth, let builder, ending == nil, !finishing else { return }
      let second = Int64(date.timeIntervalSince1970.rounded(.down))
      guard lastPublishedSecond.map({ second > $0 }) ?? true else { return }
      lastPublishedSecond = second
      let start = max(options.start, Date(timeIntervalSince1970: Double(second)))
      let end = min(Date(), Date(timeIntervalSince1970: Double(second + 1)))
      guard end > start else { return }
      var samples: [HKQuantitySample] = []
      for (key, identifier, unit, maximum) in [
        ("riderPowerW", HKQuantityTypeIdentifier.cyclingPower, HKUnit.watt(), 5_000.0),
        ("cadenceRpm", .cyclingCadence, HKUnit.count().unitDivided(by: .minute()), 300.0),
        ("gpsSpeedMps", .cyclingSpeed, HKUnit.meter().unitDivided(by: .second()), 100.0),
      ] {
        let identity = "powerlog:\(options.id):\(key):second:\(second)"
        guard let value = readings[key], value.isFinite, (0...maximum).contains(value),
          let type = HKQuantityType.quantityType(forIdentifier: identifier)
        else { continue }
        samples.append(
          HKQuantitySample(
            type: type, quantity: HKQuantity(unit: unit, doubleValue: value), start: start, end: end,
            metadata: [
              HKMetadataKeySyncIdentifier: identity, HKMetadataKeySyncVersion: 1, Self.rideMetadataKey: options.id,
            ]))
      }
      if !samples.isEmpty { enqueue { completion in builder.add(samples, completion: completion) } }
      recordLocations(locations)
      let distanceStart = max(
        options.start, Date(timeIntervalSince1970: readings["distanceStartTime"] ?? start.timeIntervalSince1970))
      let distanceEnd = min(
        Date(), Date(timeIntervalSince1970: readings["distanceEndTime"] ?? end.timeIntervalSince1970))
      if let meters = readings["distanceDeltaMeters"], meters.isFinite, meters > 0, distanceEnd > distanceStart,
        meters <= 100 * distanceEnd.timeIntervalSince(distanceStart),
        let type = HKQuantityType.quantityType(forIdentifier: .distanceCycling)
      {
        let sample = HKQuantitySample(
          type: type, quantity: HKQuantity(unit: .meter(), doubleValue: meters), start: distanceStart, end: distanceEnd,
          metadata: [
            HKMetadataKeySyncIdentifier: "powerlog:\(options.id):distance:\(second)", HKMetadataKeySyncVersion: 1,
            Self.rideMetadataKey: options.id,
          ])
        enqueue { completion in builder.add([sample], completion: completion) }
      }
    }

    func recordLocations(_ locations: [WorkoutCompanionLocation]) {
      guard options?.saveToHealth == true, options?.recordGPS == true, ending == nil else { return }
      routePoints.append(locations)
    }
    private func enqueueRoute() {
      guard let route else { return }
      let points = routePoints.points.map {
        CLLocation(
          coordinate: CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude), altitude: $0.altitude,
          horizontalAccuracy: $0.accuracy, verticalAccuracy: $0.verticalAccuracy, course: $0.course, speed: $0.speed,
          timestamp: $0.timestamp)
      }
      guard !points.isEmpty else { return }
      let token = generation
      enqueue { completion in
        func insert(_ offset: Int) {
          guard token == self.generation else { return }
          guard offset < points.count else {
            completion(true, nil)
            return
          }
          let end = min(offset + 1_000, points.count)
          route.insertRouteData(Array(points[offset..<end])) { success, error in
            self.queue.async {
              guard token == self.generation else { return }
              if success { insert(end) } else { completion(false, error) }
            }
          }
        }
        insert(0)
      }
    }

    private func enqueue(_ operation: @escaping (@escaping (Bool, Error?) -> Void) -> Void) {
      guard writes.count < 32, !uncertain else {
        markUncertain("Health write backlog exceeded its bound.")
        return
      }
      writes.append(operation)
      drain()
    }
    private func drain() {
      guard !writeInFlight else { return }
      guard !writes.isEmpty else {
        finishWhenReady()
        return
      }
      writeInFlight = true
      let token = generation
      let operation = writes.removeFirst()
      var settled = false
      operation { success, error in
        self.queue.async {
          guard token == self.generation, !settled else { return }
          settled = true
          self.writeInFlight = false
          if !success {
            self.markUncertain(error?.localizedDescription ?? "Health write failed.")
            self.writes.removeAll()
          }
          self.drain()
        }
      }
      queue.asyncAfter(deadline: .now() + 10) {
        guard token == self.generation, !settled else { return }
        settled = true
        self.writeInFlight = false
        self.markUncertain("A Health write has an uncertain result.")
        self.writes.removeAll()
        self.finishWhenReady()
      }
    }
    private func report(_ state: String, _ healthID: String?, _ reason: String?) {
      guard let options else { return }
      status?(options.id, state, healthID, reason)
    }
    private func markUncertain(_ reason: String) {
      uncertain = true
      report("unknown", nil, reason)
    }

    func finish(at date: Date, discard: Bool, completion: @escaping () -> Void) {
      if pendingStart != nil {
        failedStart(WorkoutCompanionError.invalid("Start cancelled."))
        completion()
        return
      }
      guard let session, let builder else {
        completion()
        return
      }
      if discard {
        stopQueries()
        writes.removeAll()
        session.stopActivity(with: date)
        builder.discardWorkout()
        session.end()
        clear()
        completion()
        return
      }
      guard ending == nil, !finishing else {
        completion()
        return
      }
      if options?.saveToHealth == true { enqueueRoute() }
      ending = (date, options?.saveToHealth != true, completion)
      stopQueries()
      session.stopActivity(with: date)
      let token = generation
      let timeout = DispatchWorkItem { [weak self] in
        guard let self, token == self.generation, let end = self.ending else { return }
        self.markUncertain("Health completion is unconfirmed.")
        self.session?.end()
        self.clear()
        end.completion()
      }
      finishTimer = timeout
      queue.asyncAfter(deadline: .now() + 10, execute: timeout)
      finishWhenReady()
    }
    private func finishWhenReady() {
      guard let end = ending, let session, let builder, !writeInFlight, writes.isEmpty, !finishing,
        session.state == .stopped || session.state == .ended
      else { return }
      finishing = true
      let token = generation
      if end.discard {
        builder.discardWorkout()
        session.end()
        report("notRequested", nil, nil)
        clear()
        end.completion()
        return
      }
      builder.endCollection(withEnd: end.date) { success, error in
        self.queue.async {
          guard token == self.generation else { return }
          guard success else {
            self.finishFailed(error, completion: end.completion)
            return
          }
          self.report("pending", nil, nil)
          builder.finishWorkout { workout, error in
            self.queue.async {
              guard token == self.generation else { return }
              guard let workout else {
                self.finishFailed(
                  error ?? WorkoutCompanionError.invalid("Health save is unconfirmed."), completion: end.completion)
                return
              }
              if let route = self.route, !self.routePoints.points.isEmpty {
                route.finishRoute(
                  with: workout,
                  metadata: [
                    HKMetadataKeySyncIdentifier: "powerlog:\(self.options!.id):route", HKMetadataKeySyncVersion: 1,
                    Self.rideMetadataKey: self.options!.id,
                  ]
                ) { _, error in
                  self.queue.async {
                    guard token == self.generation else { return }
                    self.report(
                      error == nil && !self.uncertain ? "saved" : "unknown", workout.uuid.uuidString.lowercased(),
                      error?.localizedDescription)
                    self.session?.end()
                    self.clear()
                    end.completion()
                  }
                }
              } else {
                self.report(self.uncertain ? "unknown" : "saved", workout.uuid.uuidString.lowercased(), nil)
                session.end()
                self.clear()
                end.completion()
              }
            }
          }
        }
      }
    }
    private func finishFailed(_ error: Error?, completion: () -> Void) {
      markUncertain(error?.localizedDescription ?? "Health completion is unconfirmed.")
      session?.end()
      clear()
      completion()
    }
    private func clear() {
      finishTimer?.cancel()
      finishTimer = nil
      stopQueries()
      generation = UUID()
      session = nil
      builder = nil
      route = nil
      pendingStart = nil
      ending = nil
      finishing = false
      writes.removeAll()
      writeInFlight = false
    }

    /// Cleanup only: never resumes the application recording or creates a replacement workout.
    func recoverForCleanup(completion: @escaping (String?) -> Void) {
      let attempt = UUID()
      cleanupAttempt = attempt
      queue.asyncAfter(deadline: .now() + 10) {
        guard self.cleanupAttempt == attempt else { return }
        self.cleanupAttempt = nil
        completion(nil)
      }
      store.recoverActiveWorkoutSession { session, _ in
        self.queue.async {
          // Even a late recovered orphan is ended. It cannot become this helper's new active session.
          let builder = session?.associatedWorkoutBuilder()
          let id = builder?.metadata[HKMetadataKeyExternalUUID] as? String
          session?.stopActivity(with: Date())
          builder?.discardWorkout()
          session?.end()
          guard self.cleanupAttempt == attempt else { return }
          self.cleanupAttempt = nil
          completion(id)
        }
      }
    }

    /// A bounded, explicit read of the ride window. No provider-history synchronization is retained.
    func readback(
      start: Date, end: Date, receive: @escaping ([WorkoutCompanionObservation]) throws -> Void,
      completion: @escaping (Result<Void, Error>) -> Void
    ) {
      let predicate = HKQuery.predicateForSamples(withStart: start, end: end, options: [])
      var finished = false
      var activeQuery: HKQuery?
      func finish(_ result: Result<Void, Error>) {
        guard !finished else { return }
        finished = true
        if let activeQuery { self.store.stop(activeQuery) }
        completion(result)
      }
      func page(_ index: Int, _ anchor: HKQueryAnchor?) {
        guard !finished else { return }
        guard index < WorkoutAppleHealthMetrics.supported.count else {
          finish(.success(()))
          return
        }
        let metric = WorkoutAppleHealthMetrics.supported[index]
        guard let type = metric.type else {
          page(index + 1, nil)
          return
        }
        let query = HKAnchoredObjectQuery(type: type, predicate: predicate, anchor: anchor, limit: 256) {
          _, samples, _, next, error in
          self.queue.async {
            guard !finished else { return }
            if let error {
              finish(.failure(error))
              return
            }
            self.expand((samples ?? []).compactMap { $0 as? HKQuantitySample }, metric: metric, receive: receive) {
              result in
              guard !finished else { return }
              switch result {
              case .failure(let error): finish(.failure(error))
              case .success:
                if (samples?.count ?? 0) == 256, let next { page(index, next) } else { page(index + 1, nil) }
              }
            }
          }
        }
        activeQuery = query
        self.store.execute(query)
      }
      page(0, nil)
      queue.asyncAfter(deadline: .now() + 10) {
        finish(
          .failure(WorkoutCompanionError.invalid("Health refresh timed out; already received samples were retained.")))
      }
    }

    private func startQueries(start: Date) {
      stopQueries()
      let token = generation
      let predicate = HKQuery.predicateForSamples(withStart: start, end: nil, options: [])
      for metric in WorkoutAppleHealthMetrics.supported {
        guard let type = metric.type else { continue }
        let deliver: ([HKSample]?) -> Void = { samples in
          self.queue.async {
            guard token == self.generation else { return }
            self.expand(
              (samples ?? []).compactMap { $0 as? HKQuantitySample }, metric: metric,
              receive: { observations in
                guard token == self.generation else { return }
                guard let id = self.options?.id else { return }
                for value in observations { self.observation?(id, value) }
              }
            ) { _ in }
          }
        }
        let query = HKAnchoredObjectQuery(type: type, predicate: predicate, anchor: nil, limit: HKObjectQueryNoLimit) {
          _, samples, _, _, _ in deliver(samples)
        }
        query.updateHandler = { _, samples, _, _, _ in deliver(samples) }
        queries.append(query)
        store.execute(query)
      }
    }
    private func stopQueries() {
      for query in queries { store.stop(query) }
      queries.removeAll()
      for query in seriesQueries.values { store.stop(query) }
      seriesQueries.removeAll()
    }

    private func expand(
      _ samples: [HKQuantitySample], metric: WorkoutAppleHealthMetrics.Metric,
      receive: @escaping ([WorkoutCompanionObservation]) throws -> Void,
      completion: @escaping (Result<Void, Error>) -> Void
    ) {
      // HealthKit metadata queries do not support prefix comparisons. Filter app publications after reading.
      let samples = samples.filter {
        !(($0.metadata?[HKMetadataKeySyncIdentifier] as? String)?.hasPrefix("powerlog:") ?? false)
      }
      func one(_ index: Int) {
        guard index < samples.count else {
          completion(.success(()))
          return
        }
        let sample = samples[index]
        func observation(_ quantity: HKQuantity, _ interval: DateInterval, series: Bool) throws {
          let value = quantity.doubleValue(for: metric.unit)
          guard value.isFinite else { return }
          let parent = sample.uuid.uuidString.lowercased()
          let identity: String
          if series {
            let text =
              "\(parent):\(interval.start.timeIntervalSince1970.bitPattern):\(interval.end.timeIntervalSince1970.bitPattern):\(value.bitPattern)"
            let bytes = Array(SHA256.hash(data: Data(text.utf8)).prefix(16))
            identity = UUID(
              uuid: (
                bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7], bytes[8], bytes[9],
                bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]
              )
            ).uuidString.lowercased()
          } else {
            identity = parent
          }
          try receive([
            WorkoutCompanionObservation(
              id: identity, timestamp: interval.end, start: interval.start,
              metric: Self.metricKey(metric.identifier), identifier: metric.identifier.rawValue, unit: metric.unitName,
              value: value, cumulative: metric.cumulative, dataOrigin: sample.sourceRevision.source.bundleIdentifier,
              parentID: series ? parent : nil)
          ])
        }
        guard sample.count > 1, let type = metric.type else {
          do {
            try observation(sample.quantity, DateInterval(start: sample.startDate, end: sample.endDate), series: false)
            one(index + 1)
          } catch { completion(.failure(error)) }
          return
        }
        guard self.seriesQueries.count < 16 else {
          completion(.failure(WorkoutCompanionError.invalid("Health detail query capacity exceeded.")))
          return
        }
        let token = UUID()
        var done = false
        func finish(_ error: Error?) {
          guard !done else { return }
          done = true
          if let query = self.seriesQueries.removeValue(forKey: token) { self.store.stop(query) }
          if let error { completion(.failure(error)) } else { one(index + 1) }
        }
        let query = HKQuantitySeriesSampleQuery(
          quantityType: type, predicate: HKQuery.predicateForObject(with: sample.uuid)
        ) { _, quantity, interval, _, last, error in
          self.queue.async {
            guard !done, self.seriesQueries[token] != nil else { return }
            do { if let quantity, let interval { try observation(quantity, interval, series: true) } } catch {
              finish(error)
              return
            }
            if last || error != nil { finish(error) }
          }
        }
        self.seriesQueries[token] = query
        self.store.execute(query)
        self.queue.asyncAfter(deadline: .now() + 10) {
          finish(WorkoutCompanionError.invalid("Health detail query timed out."))
        }
      }
      one(0)
    }
    static func metricKey(_ id: HKQuantityTypeIdentifier) -> String {
      switch id {
      case .heartRate: return "heartRateBpm"
      case .activeEnergyBurned: return "activeEnergyKcal"
      case .basalEnergyBurned: return "basalEnergyKcal"
      case .distanceCycling: return "cyclingDistanceMeters"
      case .cyclingPower: return "cyclingPowerW"
      case .cyclingCadence: return "cyclingCadenceRpm"
      case .cyclingSpeed: return "cyclingSpeedMps"
      case .respiratoryRate: return "respiratoryRateBrpm"
      case .oxygenSaturation: return "oxygenSaturationFraction"
      case .heartRateVariabilitySDNN: return "heartRateVariabilitySDNNMs"
      case .physicalEffort: return "physicalEffortMET"
      case .cyclingFunctionalThresholdPower: return "cyclingFunctionalThresholdPowerW"
      default: return id.rawValue.contains("Estimated") ? "estimatedWorkoutEffortScore" : "workoutEffortScore"
      }
    }
    func workoutSession(
      _ workoutSession: HKWorkoutSession, didChangeTo toState: HKWorkoutSessionState,
      from fromState: HKWorkoutSessionState, date: Date
    ) {
      queue.async {
        guard workoutSession === self.session else { return }
        self.finishWhenReady()
      }
    }
    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
      queue.async {
        guard workoutSession === self.session else { return }
        self.markUncertain(error.localizedDescription)
      }
    }
    func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {}
    func workoutBuilder(_ workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOf collectedTypes: Set<HKSampleType>) {
      queue.async {
        guard workoutBuilder === self.builder, let id = self.options?.id else { return }
        for metric in WorkoutAppleHealthMetrics.supported {
          guard let type = metric.type, collectedTypes.contains(type),
            let statistics = workoutBuilder.statistics(for: type),
            let quantity = statistics.mostRecentQuantity()
          else { continue }
          let value = quantity.doubleValue(for: metric.unit)
          if value.isFinite {
            self.liveReadings?(
              id, [Self.metricKey(metric.identifier): value],
              statistics.mostRecentQuantityDateInterval()?.end ?? statistics.endDate)
          }
        }
      }
    }
  }
#endif
