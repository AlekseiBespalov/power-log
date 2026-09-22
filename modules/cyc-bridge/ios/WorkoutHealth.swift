#if os(iOS)
import CoreLocation
import HealthKit
import Foundation

/// Owns a phone workout, or mirrors the Watch owner. A mirrored session never saves another workout.
@available(iOS 26.0, *)
final class WorkoutHealth: NSObject, HKWorkoutSessionDelegate, HKLiveWorkoutBuilderDelegate {
  let store = HKHealthStore()
  var archive: WorkoutArchive?
  private var workoutID: String?
  private var session: HKWorkoutSession?
  private var builder: HKLiveWorkoutBuilder?
  private var route: HKWorkoutRouteBuilder?
  private var finishing = false
  private var stopCompletion: ((Result<String, Error>) -> Void)?
  private var stopDate: Date?
  private let writeLock = NSRecursiveLock()
  private var pendingWrites = 0
  private var finishStarted = false
  private var telemetryQueue: [[WorkoutEvent]] = []
  private var telemetryBusy = false
  private var generation: UInt64 = 0
  private var finishAttempt: UInt64 = 0
  private var ownershipInFlight = false
  private var repairInFlight = false
  /// Deletion never interrupts an admitted native effect, including historical repair.
  var deletionBlocked: Bool {
    writeLock.lock(); defer { writeLock.unlock() }
    let retainedOwner = session.map { $0.type != .mirrored || $0.state != .ended } ?? false
    return retainedOwner || ownershipInFlight || repairInFlight || pendingWrites != 0 || telemetryBusy ||
      !telemetryQueue.isEmpty || stopCompletion != nil || finishing || finishStarted
  }
  var mirroringAvailable: Bool {
    writeLock.lock(); defer { writeLock.unlock() }
    return session?.type == .mirrored && session?.state != .ended
  }
  var sessionSnapshot: (type: HKWorkoutSessionType, state: HKWorkoutSessionState)? {
    writeLock.lock(); defer { writeLock.unlock() }
    guard let session else { return nil }
    return (session.type, session.state)
  }
  var nativeWorkoutID: String? {
    writeLock.lock(); defer { writeLock.unlock() }
    return session?.associatedWorkoutBuilder().metadata[HKMetadataKeyExternalUUID] as? String
  }
  var onTelemetryCommitted: ((String, String) -> Void)?
  var onMirror: (() -> Void)?
  var onData: ((Data) -> Void)?
  var onState: ((String, HKWorkoutSessionState, Date) -> Void)?
  var onMetrics: ((String, [String: Any], Date) -> Void)?
  var onError: ((Error) -> Void)?
  var onSessionFailure: ((String, Error) -> Void)?
  var onRemoteDisconnect: (() -> Void)?

  override init() {
    super.init()
    // Install at native application launch, before React or any view exists.
    store.workoutSessionMirroringStartHandler = { [weak self] session in
      guard let self else { return }
      self.writeLock.lock(); defer { self.writeLock.unlock() }
      let admission = WorkoutHealthMirrorAdmission(primaryOwner: self.session?.type == .primary || self.localOwnerID != nil,
        ownershipInFlight: self.ownershipInFlight, repairInFlight: self.repairInFlight, pendingWrites: self.pendingWrites,
        awaitingStopCompletion: self.stopCompletion != nil, finishing: self.finishing, finishStarted: self.finishStarted)
      if !admission.permitted {
        self.onError?(CycError.invalid("A Watch mirror arrived while the phone owns a workout.")); return
      }
      self.generation &+= 1
      self.session = session
      self.builder = nil
      self.route = nil
      session.delegate = self
      self.onMirror?()
    }
  }

  var permissions: [String: Any] {
    var write: [String: String] = [:]
    for type in shareTypes {
      let value = store.authorizationStatus(for: type)
      write[type.identifier] = value == .sharingAuthorized ? "authorized" : value == .sharingDenied ? "denied" : "notDetermined"
    }
    // HealthKit intentionally hides whether reading was denied. Never turn dialog success into read permission.
    return ["available": HKHealthStore.isHealthDataAvailable(), "readAuthorization": "notObservable", "writeAuthorization": write]
  }

  /// Checks whether the same authorization request would show a prompt; does not request access.
  func permissionStatus(_ completion: @escaping (Result<[String: Any], Error>) -> Void) {
    guard HKHealthStore.isHealthDataAvailable() else {
      var value = permissions; value["requestStatus"] = "unknown"; completion(.success(value)); return
    }
    store.getRequestStatusForAuthorization(toShare: shareTypes, read: Set(shareTypes.map { $0 as HKObjectType })) { status, error in
      if let error { completion(.failure(error)); return }
      var value = self.permissions
      switch status {
      case .shouldRequest: value["requestStatus"] = "shouldRequest"
      case .unnecessary: value["requestStatus"] = "unnecessary"
      case .unknown: value["requestStatus"] = "unknown"
      @unknown default: value["requestStatus"] = "unknown"
      }
      completion(.success(value))
    }
  }

  private var shareTypes: Set<HKSampleType> {
    [HKObjectType.workoutType(), HKSeriesType.workoutRoute(), HKQuantityType(.cyclingPower),
      HKQuantityType(.cyclingCadence), HKQuantityType(.cyclingSpeed), HKQuantityType(.distanceCycling),
      HKQuantityType(.activeEnergyBurned), HKQuantityType(.basalEnergyBurned), HKQuantityType(.heartRate)]
  }

  func requestPermission(recordGPS: Bool = true, _ completion: @escaping (Result<[String: Any], Error>) -> Void) {
    guard HKHealthStore.isHealthDataAvailable() else { completion(.failure(CycError.invalid("HealthKit is unavailable on this device."))); return }
    let requested = recordGPS ? shareTypes : shareTypes.subtracting([HKSeriesType.workoutRoute()])
    store.requestAuthorization(toShare: requested, read: Set(requested.map { $0 as HKObjectType })) { success, error in
      if let error { completion(.failure(error)) }
      else if !success { completion(.failure(CycError.invalid("HealthKit permission request did not complete."))) }
      else { completion(.success(self.permissions)) }
    }
  }

  static func configuration(indoor: Bool) -> HKWorkoutConfiguration {
    let configuration = HKWorkoutConfiguration()
    configuration.activityType = .cycling
    configuration.locationType = indoor ? .indoor : .outdoor
    return configuration
  }

  func launchWatch(indoor: Bool, completion: @escaping (Result<Void, Error>) -> Void) {
    store.startWatchApp(with: Self.configuration(indoor: indoor)) { success, error in
      if let error { completion(.failure(error)) }
      else if !success { completion(.failure(CycError.invalid("Apple Watch could not open Power Log."))) }
      else { completion(.success(())) }
    }
  }

  private func savedWorkout(id: String, completion: @escaping (Result<HKWorkout?, Error>) -> Void) {
    do { try requireRetained(id) } catch { completion(.failure(error)); return }
    let predicate = HKQuery.predicateForObjects(withMetadataKey: HKMetadataKeyExternalUUID, allowedValues: [id])
    store.execute(HKSampleQuery(sampleType: .workoutType(), predicate: predicate, limit: 1, sortDescriptors: nil) { _, samples, error in
      do { try self.requireRetained(id) } catch { completion(.failure(error)); return }
      if let error { completion(.failure(error)) } else { completion(.success(samples?.first as? HKWorkout)) }
    })
  }

  // Held under writeLock so a Watch mirror cannot replace a native local phone owner.
  private var localOwnerID: String?
  func setLocalOwner(_ id: String?) { writeLock.lock(); localOwnerID = id; writeLock.unlock() }

  private func requireRetained(_ id: String) throws {
    guard let archive else { throw CycError.invalid("Health workout storage is unavailable") }
    try archive.store.requireWorkoutAvailable(id: id)
    try WorkoutRecordingPolicy.requireHealthWrite(id: id, archive: archive)
  }

  private func beginOwnership(_ id: String) throws -> UInt64 {
    writeLock.lock(); defer { writeLock.unlock() }
    try requireRetained(id)
    guard !ownershipInFlight, !repairInFlight, pendingWrites == 0, !telemetryBusy, telemetryQueue.isEmpty,
      stopCompletion == nil, session == nil || (workoutID == id && session?.type == .primary) else {
      throw CycError.invalid("The existing Health session must finish its pending work before another owner can attach.")
    }
    generation &+= 1; workoutID = id; ownershipInFlight = true
    return generation
  }
  private func current(_ id: String, _ token: UInt64, attempt: UInt64? = nil) -> Bool {
    writeLock.lock(); defer { writeLock.unlock() }
    guard let workoutID else { return false }
    return WorkoutHealthCallbackIdentity(workoutID: id, generation: token, finishAttempt: attempt)
      .matches(WorkoutHealthCallbackIdentity(workoutID: workoutID, generation: generation, finishAttempt: finishAttempt))
  }
  private var collectionCanFinish: Bool {
    let phase: String
    switch session?.state { case .stopped: phase = "stopped"; case .ended: phase = "ended"; default: phase = "active" }
    return WorkoutHealthFinalizationGate.canFinish(nativePhase: phase)
  }

  func startPhone(id: String, indoor: Bool, at start: Date, completion: @escaping (Result<Void, Error>) -> Void) {
    let token: UInt64
    do { token = try beginOwnership(id) } catch { completion(.failure(error)); return }
    let finish: (Result<Void, Error>) -> Void = { result in
      self.writeLock.lock()
      guard self.current(id, token) else { self.writeLock.unlock(); completion(.failure(CycError.invalid("Health start was superseded."))); return }
      self.ownershipInFlight = false; self.writeLock.unlock(); completion(result)
    }
    savedWorkout(id: id) { result in
      guard self.current(id, token) else { finish(.failure(CycError.invalid("Health start was superseded."))); return }
      switch result {
      case .failure(let error): finish(.failure(error))
      case .success(let workout):
        guard workout == nil else { finish(.failure(CycError.invalid("This workout is already saved; a second workout was not created."))); return }
        self.store.recoverActiveWorkoutSession { existing, error in
          guard self.current(id, token) else { finish(.failure(CycError.invalid("Health start was superseded."))); return }
          do { try self.requireRetained(id) } catch { finish(.failure(error)); return }
          if let error { finish(.failure(error)); return }
          if let existing {
            let builder = existing.associatedWorkoutBuilder()
            guard existing.type == .primary, builder.metadata[HKMetadataKeyExternalUUID] as? String == id else {
              finish(.failure(CycError.invalid("A different Health workout is active."))); return
            }
            self.writeLock.lock()
            guard self.current(id, token) else { self.writeLock.unlock(); finish(.failure(CycError.invalid("Health start was superseded."))); return }
            self.session = existing; self.builder = builder; existing.delegate = self; builder.delegate = self
            self.route = (try? self.archive?.metadata(id: id).recordsGPS) == true ? builder.seriesBuilder(for: HKSeriesType.workoutRoute()) as? HKWorkoutRouteBuilder : nil
            self.finishing = false; self.finishStarted = false
            self.writeLock.unlock(); finish(.success(())); return
          }
          self.createPhone(id: id, token: token, indoor: indoor, at: start, completion: finish)
        }
      }
    }
  }

  private func createPhone(id: String, token: UInt64, indoor: Bool, at start: Date, completion: @escaping (Result<Void, Error>) -> Void) {
    guard current(id, token) else { completion(.failure(CycError.invalid("Health start was superseded."))); return }
    guard store.authorizationStatus(for: HKObjectType.workoutType()) == .sharingAuthorized else {
      completion(.failure(CycError.invalid("Allow Power Log to save workouts in Health permissions before starting."))); return
    }
    do {
      try requireRetained(id)
      let config = Self.configuration(indoor: indoor)
      let session = try HKWorkoutSession(healthStore: store, configuration: config)
      let builder = session.associatedWorkoutBuilder()
      let source = HKLiveWorkoutDataSource(healthStore: store, workoutConfiguration: config)
      // These measurements come exclusively from our timestamped CYC/GPS stream.
      for type in [HKQuantityType(.cyclingPower), HKQuantityType(.cyclingCadence), HKQuantityType(.cyclingSpeed), HKQuantityType(.distanceCycling)] {
        source.disableCollection(for: type)
      }
      self.writeLock.lock()
      guard self.current(id, token) else { self.writeLock.unlock(); session.end(); completion(.failure(CycError.invalid("Health start was superseded."))); return }
      self.session = session; self.builder = builder; self.finishing = false
      self.pendingWrites = 0; self.telemetryQueue = []; self.telemetryBusy = false
      self.finishStarted = false
      self.route = (try? archive?.metadata(id: id).recordsGPS) == true ? builder.seriesBuilder(for: HKSeriesType.workoutRoute()) as? HKWorkoutRouteBuilder : nil
      session.delegate = self; builder.delegate = self; builder.dataSource = source
      self.writeLock.unlock()
      builder.addMetadata([HKMetadataKeyExternalUUID: id, HKMetadataKeySyncIdentifier: "powerlog.workout.\(id)", HKMetadataKeySyncVersion: 1,
        HKMetadataKeyIndoorWorkout: indoor, "PowerLogWorkoutID": id]) { success, error in
        self.writeLock.lock(); defer { self.writeLock.unlock() }
        guard self.current(id, token), self.session === session else { completion(.failure(CycError.invalid("Health start was superseded."))); return }
        do { try self.requireRetained(id) } catch { completion(.failure(error)); return }
        guard success else { session.end(); completion(.failure(error ?? CycError.invalid("Workout metadata could not be saved."))); return }
        session.startActivity(with: start)
        builder.beginCollection(withStart: start) { success, error in
          self.writeLock.lock(); defer { self.writeLock.unlock() }
          guard self.current(id, token), self.session === session else { completion(.failure(CycError.invalid("Health start was superseded."))); return }
          if success { completion(.success(())) }
          else { session.end(); completion(.failure(error ?? CycError.invalid("Workout collection could not start."))) }
        }
      }
    } catch { completion(.failure(error)) }
  }

  func recoverPhone(id: String, _ completion: @escaping (Result<HKWorkoutSession?, Error>) -> Void) {
    let token: UInt64
    do { token = try beginOwnership(id) } catch { completion(.failure(error)); return }
    store.recoverActiveWorkoutSession { session, error in
      self.writeLock.lock(); defer { self.writeLock.unlock() }
      guard self.current(id, token) else { completion(.failure(CycError.invalid("Health recovery was superseded."))); return }
      self.ownershipInFlight = false
      do { try self.requireRetained(id) } catch { completion(.failure(error)); return }
      if let error { completion(.failure(error)); return }
      if let session {
        guard session.type == .primary, session.associatedWorkoutBuilder().metadata[HKMetadataKeyExternalUUID] as? String == id else {
          completion(.failure(CycError.invalid("A different Health workout is active; it was not attached."))); return
        }
        self.session = session; self.builder = session.associatedWorkoutBuilder()
        self.workoutID = self.builder?.metadata[HKMetadataKeyExternalUUID] as? String
        self.route = (try? self.archive?.metadata(id: id).recordsGPS) == true ? self.builder?.seriesBuilder(for: HKSeriesType.workoutRoute()) as? HKWorkoutRouteBuilder : nil
        session.delegate = self; self.builder?.delegate = self
        self.finishing = false; self.finishStarted = false
      }
      completion(.success(session))
    }
  }

  func reconcileSaved(id: String, at cutoff: Date, completion: @escaping (Result<String, Error>) -> Void) {
    let token: UInt64
    do { token = try beginOwnership(id) } catch { completion(.failure(error)); return }
    writeLock.lock()
    guard current(id, token) else { writeLock.unlock(); completion(.failure(CycError.invalid("Health recovery was superseded."))); return }
    finishAttempt &+= 1; let attempt = finishAttempt
    stopDate = cutoff; stopCompletion = completion; ownershipInFlight = false; writeLock.unlock()
    savedWorkout(id: id) { result in
      guard self.current(id, token, attempt: attempt) else { return }
      switch result {
      case .failure(let error): self.completeStop(.failure(error), id: id, token: token, attempt: attempt)
      case .success(let workout):
        guard let workout else {
          self.completeStop(.failure(WorkoutSavedOwnerLookupError.notAccessible), id: id, token: token, attempt: attempt); return
        }
        self.extractFinal(workout, id: id, token: token, attempt: attempt)
      }
    }
  }

  func send(_ data: Data, completion: @escaping (Bool) -> Void) {
    writeLock.lock(); defer { writeLock.unlock() }
    guard mirroringAvailable, let session else { completion(false); return }
    session.sendToRemoteWorkoutSession(data: data) { success, _ in completion(success) }
  }

  func pause() {
    writeLock.lock(); defer { writeLock.unlock() }
    guard let workoutID, (try? requireRetained(workoutID)) != nil else { return }; session?.pause()
  }
  func resume() {
    writeLock.lock(); defer { writeLock.unlock() }
    guard let workoutID, (try? requireRetained(workoutID)) != nil else { return }; session?.resume()
  }
  func cancelPendingPhone() {
    writeLock.lock(); defer { writeLock.unlock() }
    guard session == nil || session?.type == .primary else { return }
    generation &+= 1; ownershipInFlight = false
    let completion = stopCompletion; stopCompletion = nil
    finishing = false; finishStarted = false
    builder?.discardWorkout(); if session?.state != .ended { session?.end() }; builder = nil; route = nil; session = nil
    telemetryQueue = []; telemetryBusy = false; pendingWrites = 0
    completion?(.failure(CycError.invalid("Health operation was canceled.")))
  }

  func discardPhone(id: String, completion: @escaping (Result<Void, Error>) -> Void) {
    writeLock.lock()
    guard workoutID == id, !ownershipInFlight, !finishing, !finishStarted,
      session == nil || (session?.type == .primary && session?.associatedWorkoutBuilder().metadata[HKMetadataKeyExternalUUID] as? String == id) else {
      writeLock.unlock()
      completion(.failure(CycError.invalid("The original recorder must be available before discarding the ride."))); return
    }
    let ending = session
    cancelPendingPhone()
    ownershipInFlight = true
    let token = generation
    writeLock.unlock()
    let finish: (Result<Void, Error>) -> Void = { result in
      self.writeLock.lock()
      guard self.generation == token, self.workoutID == id else {
        self.writeLock.unlock(); completion(.failure(CycError.invalid("Discard was superseded."))); return
      }
      self.ownershipInFlight = false
      switch result {
      case .success: self.workoutID = nil
      case .failure: self.session = ending
      }
      self.writeLock.unlock(); completion(result)
    }
    Task {
      let deadline = ProcessInfo.processInfo.systemUptime + 5
      while let ending, ending.state != .ended, ProcessInfo.processInfo.systemUptime < deadline {
        try? await Task.sleep(nanoseconds: 50_000_000)
      }
      if let ending, ending.state != .ended {
        finish(.failure(CycError.invalid("The native recorder has not ended yet; discard will retry.")))
      } else { finish(.success(())) }
    }
  }

  func lap(at date: Date, id: String, completion: @escaping (Result<Void, Error>) -> Void) {
    writeLock.lock(); defer { writeLock.unlock() }
    do {
      guard let workoutID else { throw CycError.invalid("Health workout unavailable") }
      try requireRetained(workoutID)
    } catch { completion(.failure(error)); return }
    guard let builder else { completion(.failure(CycError.invalid("Health workout unavailable"))); return }
    if builder.workoutEvents.contains(where: { $0.metadata?["PowerLogEventId"] as? String == id }) { completion(.success(())); return }
    beginWrite()
    builder.addWorkoutEvents([HKWorkoutEvent(type: .lap, dateInterval: DateInterval(start: date, duration: 0),
      metadata: ["PowerLogEventId": id])]) { success, error in
      self.endWrite(success, error, builder: builder)
      completion(success ? .success(()) : .failure(error ?? CycError.invalid("Health lap was not applied")))
    }
  }

  var canReplayTelemetry: Bool {
    writeLock.lock(); defer { writeLock.unlock() }; return !ownershipInFlight && !repairInFlight && !telemetryBusy && telemetryQueue.isEmpty && (!finishing || builder == nil)
  }
  func addTelemetry(_ events: [WorkoutEvent]) {
    writeLock.lock()
    guard let workoutID, events.allSatisfy({ $0.workoutId == workoutID }), !events.isEmpty,
      (try? requireRetained(workoutID)) != nil,
      builder == nil || (session?.type == .primary && !finishing),
      !ownershipInFlight, !repairInFlight, telemetryQueue.count < 8 else { writeLock.unlock(); return } // Missing receipts remain canonical and retry by indexed lookup.
    telemetryQueue.append(events); pendingWrites += 1; writeLock.unlock()
    pumpTelemetry()
  }

  private func pumpTelemetry() {
    writeLock.lock()
    guard !telemetryBusy, !telemetryQueue.isEmpty, let id = workoutID else { writeLock.unlock(); return }
    let builder = self.builder, token = generation
    telemetryBusy = true
    let events = telemetryQueue.removeFirst()
    writeLock.unlock()
    let settle: (Result<Void, Error>) -> Void = { result in
      self.writeLock.lock()
      guard self.current(id, token) else { self.writeLock.unlock(); return }
      self.telemetryBusy = false; self.pendingWrites -= 1
      let shouldFinish = self.finishing && self.pendingWrites == 0
      self.writeLock.unlock()
      switch result {
      case .success: if let last = events.last { self.onTelemetryCommitted?(id, last.eventId) }
      case .failure(let error): self.onError?(error)
      }
      if shouldFinish { self.finishCollection() }
      self.pumpTelemetry()
    }
    do {
      guard let archive else { throw CycError.invalid("Health eligibility storage unavailable") }
      try requireRetained(id)
      let eligible = try events.filter { try WorkoutHealthEligibility.permits($0, archive: archive) }
      let excluded = events.filter { event in !eligible.contains(where: { $0.eventId == event.eventId }) }
      if !excluded.isEmpty {
        let journal = WorkoutHealthInsertionJournal(archive: archive)
        try journal.prepare(excluded); try journal.record(excluded, outcome: "excluded")
      }
      insertTelemetry(eligible, id: id, token: token, builder: builder, completion: settle)
    } catch { settle(.failure(error)) }
  }

  /// Receipts are per metric: retrying denied cadence never replaces already committed power.
  private func insertTelemetry(_ events: [WorkoutEvent], id: String, token: UInt64? = nil, builder: HKLiveWorkoutBuilder? = nil,
                               saved: HKWorkout? = nil, minimumVersion: Int = 1, completion: @escaping (Result<Void, Error>) -> Void) {
    guard let archive else { completion(.failure(CycError.invalid("Health insertion storage unavailable"))); return }
    let journal = WorkoutHealthInsertionJournal(archive: archive)
    do {
      try requireRetained(id)
      try journal.prepare(events)
      let plan = WorkoutHealthTelemetryPlan(events: events, previous: try journal.metricResults(events)) { metric in
        self.store.authorizationStatus(for: HKQuantityType(metric == "humanPowerW" ? .cyclingPower : .cyclingCadence)) == .sharingAuthorized
      }
      let versions = try journal.reserveVersions(plan.quantities, minimumVersion: minimumVersion)
      let dates = try Dictionary(uniqueKeysWithValues: events.map { ($0.eventId, try $0.date) })
      let quantities = plan.quantities.map { point -> HKQuantitySample in
        let power = point.metric == "humanPowerW", date = dates[point.eventID]!
        return HKQuantitySample(type: HKQuantityType(power ? .cyclingPower : .cyclingCadence),
          quantity: HKQuantity(unit: power ? .watt() : .count().unitDivided(by: .minute()), doubleValue: point.value), start: date, end: date,
          metadata: [HKMetadataKeySyncIdentifier: "powerlog.cyc.\(point.eventID).\(point.metric)",
            HKMetadataKeySyncVersion: versions[point.eventID + "." + point.metric]!])
      }
      let done: (Bool, Error?) -> Void = { success, error in
        do {
          guard success else { throw error ?? CycError.invalid("Health telemetry insertion failed") }
          try self.requireRetained(id)
          try journal.recordMetrics(events, results: plan.committed); completion(.success(()))
        } catch { completion(.failure(error)) }
      }
      let apply: (HKWorkout?) -> Void = { workout in
        self.writeLock.lock(); defer { self.writeLock.unlock() }
        if let token, !self.current(id, token) { completion(.failure(CycError.invalid("Health insertion was superseded."))); return }
        do { try self.requireRetained(id) } catch { completion(.failure(error)); return }
        if quantities.isEmpty { done(true, nil) }
        else if let builder, builder.endDate == nil { builder.add(quantities, completion: done) }
        else if let workout { self.store.add(quantities, to: workout, completion: done) }
        else { completion(.failure(CycError.invalid("Original saved Health workout is pending"))) }
      }
      if quantities.isEmpty || builder?.endDate == nil && builder != nil || saved != nil { apply(saved) }
      else {
        savedWorkout(id: id) { result in
          do {
            guard let workout = try result.get() else { throw CycError.invalid("Original saved Health workout is pending") }
            apply(workout)
          } catch { completion(.failure(error)) }
        }
      }
    } catch { completion(.failure(error)) }
  }

  /// Explicit repair is bounded by the canonical revision captured before its first side effect.
  /// The caller publishes a new archive seal only after this completion succeeds.
  func repairUnavailableTelemetry(id: String, completion: @escaping (Result<Void, Error>) -> Void) {
    writeLock.lock()
    do { try requireRetained(id) } catch { writeLock.unlock(); completion(.failure(error)); return }
    guard session == nil, !ownershipInFlight, !repairInFlight, pendingWrites == 0, !telemetryBusy,
      telemetryQueue.isEmpty, stopCompletion == nil, let archive else {
      writeLock.unlock(); completion(.failure(CycError.invalid("Health repair requires a saved workout with no pending owner work."))); return
    }
    repairInFlight = true
    writeLock.unlock()
    let finish: (Result<Void, Error>) -> Void = { result in
      self.writeLock.lock(); self.repairInFlight = false; self.writeLock.unlock(); completion(result)
    }
    do {
      let metadata = try archive.metadata(id: id)
      guard !metadata.watchEnabled, metadata.endedAt != nil else { throw CycError.invalid("Only a saved phone-owned workout can repair phone telemetry.") }
      let revision = try archive.revision(id: id)
      savedWorkout(id: id) { result in
        do {
          guard let workout = try result.get() else { throw CycError.invalid("The original saved Health workout is not accessible yet.") }
          let journal = WorkoutHealthInsertionJournal(archive: archive)
          try journal.beginRepair(id: id)
          Task {
            do {
              var after: Int64 = 0
              while true {
                try self.requireRetained(id)
                let page = try archive.pageEvents(id: id, afterSequence: after, limit: 16, producer: "cyc", throughRevision: revision)
                if page.isEmpty { break }
                let candidates = try page.map(\.event).filter { try journal.needsRepair($0) }
                var eligible: [WorkoutEvent] = []
                for event in candidates {
                  if try WorkoutHealthEligibility.permits(event, archive: archive) { eligible.append(event) }
                  else { try journal.prepare([event]); try journal.record([event], outcome: "excluded") }
                }
                if !eligible.isEmpty {
                  try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    self.insertTelemetry(eligible, id: id, saved: workout, minimumVersion: 2) { continuation.resume(with: $0) }
                  }
                }
                after = page.last!.sequence
                if page.count < 16 { break }
              }
              try self.requireRetained(id)
              try journal.finishRepair(id: id); finish(.success(()))
            } catch { finish(.failure(error)) }
          }
        } catch { finish(.failure(error)) }
      }
    } catch { finish(.failure(error)) }
  }

  func addLocation(_ location: CLLocation, distance: Double, from previousDate: Date?) {
    writeLock.lock(); defer { writeLock.unlock() }
    guard let builder, let workoutID, (try? requireRetained(workoutID)) != nil,
      (try? archive?.metadata(id: workoutID).recordsGPS) == true,
      session?.type == .primary, !finishing else { return }
    if (0...50).contains(location.horizontalAccuracy), let route { beginWrite(); route.insertRouteData([location]) { success, error in self.endWrite(success, error, builder: builder) } }
    var samples: [HKQuantitySample] = []
    if distance > 0, let previousDate {
      samples.append(HKQuantitySample(type: HKQuantityType(.distanceCycling), quantity: HKQuantity(unit: .meter(), doubleValue: distance), start: previousDate, end: location.timestamp))
    }
    if WorkoutDistancePolicy.validSpeed(location.speed, accuracy: location.speedAccuracy) != nil {
      samples.append(HKQuantitySample(type: HKQuantityType(.cyclingSpeed), quantity: HKQuantity(unit: .meter().unitDivided(by: .second()), doubleValue: location.speed), start: location.timestamp, end: location.timestamp))
    }
    if !samples.isEmpty { beginWrite(); builder.add(samples) { success, error in self.endWrite(success, error, builder: builder) } }
  }

  func stopPhone(at date: Date, completion: @escaping (Result<String, Error>) -> Void) {
    writeLock.lock(); defer { writeLock.unlock() }
    do {
      guard let workoutID else { throw CycError.invalid("No phone workout is available to finish.") }
      try requireRetained(workoutID)
    } catch { completion(.failure(error)); return }
    guard session?.type == .primary, builder != nil, !finishing else {
      completion(.failure(CycError.invalid("No phone workout is available to finish."))); return
    }
    finishAttempt &+= 1
    finishing = true; stopCompletion = completion; stopDate = date
    if collectionCanFinish { finishCollection() }
    else { session?.stopActivity(with: date) }
  }

  private func finishCollection() {
    writeLock.lock()
    guard let builder, let date = stopDate, let id = workoutID, stopCompletion != nil, pendingWrites == 0,
      collectionCanFinish, !finishStarted else { writeLock.unlock(); return }
    let token = generation, attempt = finishAttempt
    do { try requireRetained(id) } catch {
      writeLock.unlock(); completeStop(.failure(error), id: id, token: token, attempt: attempt); return
    }
    finishStarted = true
    writeLock.unlock()
    let finish: () -> Void = {
      guard self.current(id, token, attempt: attempt) else { return }
      self.savedWorkout(id: id) { result in
        self.writeLock.lock(); defer { self.writeLock.unlock() }
        guard self.current(id, token, attempt: attempt) else { return }
        do { try self.requireRetained(id) } catch {
          self.completeStop(.failure(error), id: id, token: token, attempt: attempt); return
        }
        switch result {
        case .failure(let error): self.completeStop(.failure(error), id: id, token: token, attempt: attempt)
        case .success(let existing):
          if let existing { self.extractFinal(existing, id: id, token: token, attempt: attempt); return }
          builder.finishWorkout { workout, error in
            self.writeLock.lock(); defer { self.writeLock.unlock() }
            guard self.current(id, token, attempt: attempt), self.builder === builder else { return }
            guard let workout else { self.completeStop(.failure(error ?? CycError.invalid("Health save outcome is unresolved; retry this same workout.")), id: id, token: token, attempt: attempt); return }
            self.extractFinal(workout, id: id, token: token, attempt: attempt)
          }
        }
      }
    }
    if builder.endDate != nil { finish() }
    else {
      builder.endCollection(withEnd: date) { success, error in
        self.writeLock.lock(); defer { self.writeLock.unlock() }
        guard self.current(id, token, attempt: attempt), self.builder === builder else { return }
        guard success else { self.completeStop(.failure(error ?? CycError.invalid("Workout collection could not end.")), id: id, token: token, attempt: attempt); return }
        finish()
      }
    }
  }

  private func extractFinal(_ workout: HKWorkout, id: String, token: UInt64, attempt: UInt64) {
    guard current(id, token, attempt: attempt) else { return }
    guard let archive else { completeStop(.failure(CycError.invalid("Health extraction storage unavailable")), id: id, token: token, attempt: attempt); return }
    Task {
      do {
        try self.requireRetained(id)
        for (type, unit) in [(HKQuantityType(.heartRate), HKUnit.count().unitDivided(by: .minute())),
          (HKQuantityType(.activeEnergyBurned), .kilocalorie()), (HKQuantityType(.basalEnergyBurned), .kilocalorie()),
          (HKQuantityType(.cyclingPower), .watt()), (HKQuantityType(.cyclingCadence), .count().unitDivided(by: .minute())),
          (HKQuantityType(.distanceCycling), .meter()), (HKQuantityType(.cyclingSpeed), .meter().unitDivided(by: .second()))] {
          let key = id + ":final:" + type.identifier
          var anchor: HKQueryAnchor?
          if let bytes = try archive.store.read({ db in try db.get(namespace: "health-query-progress", key: key) }) {
            anchor = try NSKeyedUnarchiver.unarchivedObject(ofClass: HKQueryAnchor.self, from: bytes)
          }
          while true {
            let page: ([HKQuantitySample], [HKDeletedObject], HKQueryAnchor) = try await withCheckedThrowingContinuation { continuation in
              self.store.execute(HKAnchoredObjectQuery(type: type, predicate: HKQuery.predicateForObjects(from: workout), anchor: anchor, limit: 256) { _, samples, deleted, next, error in
                if let error { continuation.resume(throwing: error) }
                else if let next { continuation.resume(returning: (samples?.compactMap { $0 as? HKQuantitySample } ?? [], deleted ?? [], next)) }
                else { continuation.resume(throwing: CycError.invalid("Health final page has no anchor")) }
              })
            }
            guard self.current(id, token, attempt: attempt) else { return }
            try self.requireRetained(id)
            try archive.associateHealthSamples(id: id, source: "phone", sampleIDs: page.0.map { $0.uuid.uuidString },
              healthWorkoutID: workout.uuid.uuidString, at: workout.endDate)
            var events = try page.0.map { sample in
              let value = sample.quantity.doubleValue(for: unit)
              var payload: [String: WorkoutJSON] = ["representation": .string("rawQuantity"),
                "sampleUUID": .string(sample.uuid.uuidString.lowercased()), "sampleCount": .number(Double(sample.count)),
                "healthKitIdentifier": .string(type.identifier), "value": .number(value), "unit": .string(unit.unitString),
                "sampleStart": .string(WorkoutCoding.timestamp(sample.startDate)), "sampleEnd": .string(WorkoutCoding.timestamp(sample.endDate))]
              if type.identifier == HKQuantityTypeIdentifier.heartRate.rawValue, sample.count == 1 { payload["heartRateBpm"] = .number(value) }
              return try WorkoutEvent(workoutId: id, kind: "health", source: "phone", timestamp: sample.endDate,
                payload: payload, eventId: sample.uuid.uuidString)
            }
            for removed in page.1 {
              let uuid = removed.uuid.uuidString.lowercased()
              events.append(try WorkoutEvent(workoutId: id, kind: "health", source: "phone", timestamp: workout.endDate,
                payload: ["representation": .string("healthTombstone"), "sampleUUID": .string(uuid), "supersedesEventId": .string(uuid), "deleted": .bool(true)],
                eventId: WorkoutStableIdentity.uuid("deleted:" + uuid)))
            }
            let bytes = try NSKeyedArchiver.archivedData(withRootObject: page.2, requiringSecureCoding: true)
            try archive.store.transaction(priority: .capture) { db in
              try self.requireRetained(id)
              _ = try archive.appendBatch(events)
              try db.put(namespace: "health-query-progress", key: key, value: bytes)
            }
            anchor = page.2
            if page.0.count + page.1.count < 256 { break }
          }
          let descriptor = HKQuantitySeriesSampleQueryDescriptor(
            predicate: .quantitySample(type: type, predicate: HKQuery.predicateForObjects(from: workout)),
            options: [.includeSample, .orderByQuantitySampleStartDate])
          var series: [WorkoutEvent] = []
          for try await point in descriptor.results(for: self.store) {
            guard self.current(id, token, attempt: attempt) else { return }
            try self.requireRetained(id)
            guard let sample = point.sample, sample.count > 1 else { continue }
            let value = point.quantity.doubleValue(for: unit)
            var payload: [String: WorkoutJSON] = ["representation": .string("rawSeries"),
              "sampleUUID": .string(sample.uuid.uuidString.lowercased()), "healthKitIdentifier": .string(type.identifier),
              "value": .number(value), "unit": .string(unit.unitString),
              "sampleStart": .string(WorkoutCoding.timestamp(point.dateInterval.start)),
              "sampleEnd": .string(WorkoutCoding.timestamp(point.dateInterval.end))]
            if type.identifier == HKQuantityTypeIdentifier.heartRate.rawValue { payload["heartRateBpm"] = .number(value) }
            let identity = "series:\(sample.uuid):\(point.dateInterval.start.timeIntervalSince1970):\(point.dateInterval.end.timeIntervalSince1970):\(value)"
            series.append(try WorkoutEvent(workoutId: id, kind: "health", source: "phone", timestamp: point.dateInterval.end,
              payload: payload, eventId: WorkoutStableIdentity.uuid(identity)))
            if series.count >= 128 { _ = try archive.appendBatch(series); series.removeAll(keepingCapacity: true) }
          }
          if !series.isEmpty { _ = try archive.appendBatch(series) }
          let totalKey: String? = type.identifier == HKQuantityTypeIdentifier.activeEnergyBurned.rawValue ? "activeEnergyKcal" :
            type.identifier == HKQuantityTypeIdentifier.basalEnergyBurned.rawValue ? "basalEnergyKcal" :
            type.identifier == HKQuantityTypeIdentifier.distanceCycling.rawValue ? "distanceMeters" : nil
          if let totalKey, let sum = workout.statistics(for: type)?.sumQuantity() {
            let value = sum.doubleValue(for: unit), logicalID = "final:\(workout.uuid):\(type.identifier)"
            let event = try WorkoutEvent(workoutId: id, kind: "health", source: "phone", timestamp: workout.endDate,
              payload: ["representation": .string("finalWorkoutTotal"), "logicalTotalID": .string(logicalID),
                "healthKitIdentifier": .string(type.identifier), "value": .number(value), "unit": .string(unit.unitString), totalKey: .number(value)])
            try WorkoutHealthRevisionJournal.append(event, logicalID: logicalID, archive: archive)
          }
        }
        guard self.current(id, token, attempt: attempt) else { return }
        // Recovery learns the original Health cutoff; a previously committed owner cutoff wins.
        try archive.store.transaction { _ in
          try self.requireRetained(id)
          if try archive.metadata(id: id).endedAt == nil {
            _ = try archive.finish(id: id, endedAt: workout.endDate, finalPhase: "finishing")
          }
        }
        self.completeStop(.success(workout.uuid.uuidString.lowercased()), id: id, token: token, attempt: attempt)
      } catch { self.completeStop(.failure(error), id: id, token: token, attempt: attempt) }
    }
  }

  private func beginWrite() { writeLock.lock(); pendingWrites += 1; writeLock.unlock() }
  private func endWrite(_ success: Bool, _ error: Error?, builder: HKLiveWorkoutBuilder) {
    writeLock.lock()
    guard self.builder === builder else { writeLock.unlock(); return }
    pendingWrites -= 1; let shouldFinish = finishing && pendingWrites == 0; writeLock.unlock()
    if !success, let error { onError?(error) }
    if shouldFinish { finishCollection() }
  }

  private func completeStop(_ result: Result<String, Error>, id: String? = nil, token: UInt64? = nil, attempt: UInt64? = nil) {
    writeLock.lock()
    if let id, let token, !current(id, token, attempt: attempt) { writeLock.unlock(); return }
    guard let completion = stopCompletion else { writeLock.unlock(); return }
    stopCompletion = nil; finishAttempt &+= 1
    if case .failure = result {
      // The same stopped builder/session remains available for reconciliation and retry.
      finishing = false; finishStarted = false
      writeLock.unlock(); completion(result); return
    }
    let previousSession = session
    builder = nil; route = nil; session = nil
    finishing = false; finishStarted = false
    telemetryQueue = []; telemetryBusy = false; pendingWrites = 0
    writeLock.unlock()
    previousSession?.end()
    completion(result)
  }

  private static let dateLock = NSLock()
  private static let fractionalDates: ISO8601DateFormatter = {
    let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]; return formatter
  }()
  private static let wholeDates = ISO8601DateFormatter()
  static func date(_ text: String) -> Date? {
    dateLock.lock(); defer { dateLock.unlock() }
    return fractionalDates.date(from: text) ?? wholeDates.date(from: text)
  }

  func workoutSession(_ workoutSession: HKWorkoutSession, didChangeTo toState: HKWorkoutSessionState, from fromState: HKWorkoutSessionState, date: Date) {
    writeLock.lock(); defer { writeLock.unlock() }
    guard workoutSession === session else { return }
    if workoutSession.type == .mirrored {
      if toState == .ended { session = nil }
      return
    }
    guard workoutSession.type == .primary, let id = workoutID else { return }
    onState?(id, toState, date)
    if collectionCanFinish && workoutSession.type == .primary && finishing { finishCollection() }
  }
  func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
    writeLock.lock(); defer { writeLock.unlock() }
    guard workoutSession === session else { return }
    guard workoutSession.type == .primary, let id = workoutID else { onError?(error); return }
    if stopCompletion != nil { completeStop(.failure(error)) }
    onSessionFailure?(id, error)
  }
  func workoutSession(_ workoutSession: HKWorkoutSession, didReceiveDataFromRemoteWorkoutSession data: [Data]) {
    writeLock.lock(); defer { writeLock.unlock() }
    guard workoutSession === session else { return }
    for packet in data { onData?(packet) }
  }
  func workoutSession(_ workoutSession: HKWorkoutSession, didDisconnectFromRemoteDeviceWithError error: Error?) {
    writeLock.lock(); defer { writeLock.unlock() }
    guard workoutSession === session else { return }
    if workoutSession.type == .mirrored { session = nil }
    onRemoteDisconnect?()
  }
  func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {}
  func workoutBuilder(_ workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOf collectedTypes: Set<HKSampleType>) {
    writeLock.lock(); defer { writeLock.unlock() }
    guard workoutBuilder === builder, let id = workoutID else { return }
    var metrics: [String: Any] = [:]
    var date = Date()
    for type in collectedTypes {
      guard let type = type as? HKQuantityType, let statistics = workoutBuilder.statistics(for: type) else { continue }
      switch type.identifier {
      case HKQuantityTypeIdentifier.heartRate.rawValue:
        if let quantity = statistics.mostRecentQuantity() {
          metrics["heartRateBpm"] = quantity.doubleValue(for: .count().unitDivided(by: .minute()))
          date = statistics.mostRecentQuantityDateInterval()?.end ?? statistics.endDate
        }
      case HKQuantityTypeIdentifier.activeEnergyBurned.rawValue: metrics["activeEnergyKcal"] = statistics.sumQuantity()?.doubleValue(for: .kilocalorie())
      case HKQuantityTypeIdentifier.basalEnergyBurned.rawValue: metrics["basalEnergyKcal"] = statistics.sumQuantity()?.doubleValue(for: .kilocalorie())
      default: break
      }
    }
    if !metrics.isEmpty { onMetrics?(id, metrics, date) }
  }
}
#endif
