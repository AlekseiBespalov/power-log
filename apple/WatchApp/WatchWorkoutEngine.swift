import Foundation
import Combine
import HealthKit
import CoreLocation
import WatchConnectivity
import WatchKit
import OSLog

@MainActor
final class WatchWorkoutEngine: NSObject, ObservableObject {
  static let shared = WatchWorkoutEngine()
  @Published private(set) var phase = "ready"
  @Published private(set) var isBusy = false
  @Published private(set) var phoneReachable = false
  @Published private(set) var lapCount = 0
  @Published private(set) var gpsLabel = "GPS off"
  @Published private(set) var issue: String?
  @Published private var syncIssue: SyncIssue?
  @Published private var startHandoff = WatchStartHandoff()
  @Published private(set) var distanceLabel = "— km"
  @Published private(set) var distanceSourceLabel = "Distance"

  private let healthStore = HKHealthStore()
  private let locationManager = CLLocationManager()
  private var journal: WatchWorkoutJournal?
  private var session: HKWorkoutSession?
  private var sessionWorkoutID: String?
  private var sessionGeneration = UUID()
  private var builder: HKLiveWorkoutBuilder?
  private var routeBuilder: HKWorkoutRouteBuilder?
  private var metadata: [String: Any] = [:]
  private var insertingTelemetry: Set<String> = []
  private var pendingOwnerCommand: WorkoutCommand?
  private var applyingCommand = false
  private var backgroundTasks: [WKWatchConnectivityRefreshBackgroundTask] = []
  private var metrics: [String: (Double, Date)] = [:]
  @MainActor private final class RawHealthObservation {
    let type: HKQuantityType
    let predicate: NSPredicate
    let generation: UUID
    var anchor: HKQueryAnchor?
    var observer: HKObserverQuery?
    var page: HKAnchoredObjectQuery?
    var drain = WatchQueryDrain()
    var retryRequired = false
    var completions: [HKObserverQueryCompletionHandler] = []

    init(type: HKQuantityType, predicate: NSPredicate, generation: UUID) {
      self.type = type; self.predicate = predicate; self.generation = generation
    }

    func completeNotifications() {
      let pending = completions
      completions.removeAll()
      for completion in pending { completion() }
    }
  }
  private var rawHealthObservations: [String: RawHealthObservation] = [:]
  private var rawQueryGeneration = UUID()
  private var pendingInserts = 0
  private var acceptingBatches = 0
  private var activated = false
  @Published private var recovering = false
  @Published private var isFinishing = false
  private var sessionReady = false
  private var recoveredForeignWorkoutID: String?
  private var unresolvedNativeOwner = false
  private var nativeAbsenceEvidence: WorkoutNativeAbsenceEvidence?
  private var lastLocation: CLLocation?
  private var gpsBarrier = false
  private var distanceInFlight = false
  private var distanceSnapshot: WorkoutDistanceSnapshot?
  private var lastDistanceRequest = Date.distantPast
  private var mirroring = false
  private var heartbeat: Timer?
  private var archiveWork = WorkoutBoundedWorkQueue()
  private var syncWork = WorkoutBoundedWorkQueue()
  private var mirrorBudget = WorkoutTransmissionBudget()
  private var syncCatalogCursor = ""
  private var replayingTelemetry = false
  private var historicalWork = WorkoutBoundedWorkQueue()
  private var deletionCursor = ""
  private var deletionCleanupID: String?
  private var deletionProbe = WorkoutDeletionProbeGate()
  private struct SyncIssue { let workoutID: String; let operation: String; let message: String }
  private let syncLog = Logger(subsystem: "app.powerlog.watch", category: "synchronization")

  var displayedIssue: String? { issue ?? syncIssue?.message }
  private var prioritySyncID: String? {
    WatchWorkoutJournal.syncPriorityID(metadata: metadata, nativeBusy: isActive || isFinishing || isBusy)
  }
  private func reportSyncFailure(id: String, operation: String, error: Error) {
    syncIssue = SyncIssue(workoutID: id, operation: operation, message: "Ride data needs attention. \(error.localizedDescription)")
    let native = error as NSError
    syncLog.error("Transfer failed: operation=\(operation == "seal" ? "seal" : "chunk", privacy: .public) domain=\(native.domain, privacy: .public) code=\(native.code)")
  }
  private func clearSyncIssue(id: String, operation: String) {
    if syncIssue?.workoutID == id && syncIssue?.operation == operation { syncIssue = nil }
  }
  private func logTransportFailure(operation: String, error: Error) {
    let native = error as NSError
    syncLog.debug("Connection will retry: operation=\(operation, privacy: .public) domain=\(native.domain, privacy: .public) code=\(native.code)")
  }

  var isActive: Bool { ["running", "paused"].contains(phase) }
  var canStart: Bool { session == nil && recoveredForeignWorkoutID == nil && !unresolvedNativeOwner && !isFinishing && !isBusy && !recovering && !deletionProbe.inFlight }
  var canControl: Bool { session?.state == .running || session?.state == .paused }
  var rideDiscarded: Bool { metadata["healthKitState"] as? String == "discarded" }
  var discardingRide: Bool { metadata["discardRequested"] as? Bool == true && !rideDiscarded }
  var needsHealthRetry: Bool {
    if !saveToHealth || rideDiscarded || metadata["discardRequested"] as? Bool == true { return false }
    guard session == nil || session?.state == .stopped || session?.state == .ended else { return false }
    return metadata["healthKitState"] as? String == "failed" || metadata["healthKitState"] as? String == "pending" ||
      metadata["cycHealthSamplesIncomplete"] as? Bool == true || metadata["healthSamplesIncomplete"] as? Bool == true
  }
  var canRetryHealthSave: Bool { needsHealthRetry && !isBusy && !isFinishing && !recovering }
  var savedStatusLabel: String {
    WatchWorkoutPresentation.savedDetail(saveToHealth: saveToHealth, healthState: metadata["healthKitState"] as? String ?? "pending")
  }
  func idlePresentation(at date: Date) -> WatchWorkoutPresentation {
    WatchWorkoutPresentation.idle(phase: phase, busy: isBusy, recovering: recovering, finishing: isFinishing,
      stopPending: pendingOwnerCommand?.endsWorkout == true || metadata["phase"] as? String == "finishing",
      hasNativeSession: session != nil, discarded: rideDiscarded, discardPending: discardingRide,
      hasIssue: displayedIssue != nil, awaitingStart: startHandoff.isWaiting(at: date))
  }
  private var workoutID: String? { metadata["workoutId"] as? String }
  private var nativeWorkoutID: String? { session == nil ? recoveredForeignWorkoutID : sessionWorkoutID }
  private func effectIdentity(_ id: String) -> WorkoutEffectIdentity { WorkoutEffectIdentity(workoutID: id, generation: sessionGeneration) }
  private func isCurrent(_ identity: WorkoutEffectIdentity) -> Bool { identity.matches(workoutID: workoutID, generation: sessionGeneration) }
  private var indoor: Bool { metadata["indoor"] as? Bool ?? false }
  private var saveToHealth: Bool { metadata["saveToHealth"] as? Bool ?? true }
  private var recordGPS: Bool { metadata["recordGPS"] as? Bool ?? !indoor }

  func activate() async {
    guard !activated else { return }
    activated = true
    do { journal = try WatchWorkoutJournal() } catch { issue = error.localizedDescription }
    locationManager.delegate = self
    locationManager.activityType = .fitness
    locationManager.desiredAccuracy = kCLLocationAccuracyBest
    locationManager.distanceFilter = kCLDistanceFilterNone
    if WCSession.isSupported() { WCSession.default.delegate = self; WCSession.default.activate() }
    await recover()
    advanceDeletionCleanup()
    if !isActive { await retryHealthSave(automatic: true) }
    retrySync()
    heartbeat = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
      Task { @MainActor in
        guard let self else { return }
        // Publish handoff expiry through the existing heartbeat. Interactive UI
        // must use present engine state, not a future-rendered Watch timeline.
        self.startHandoff.expire(at: Date())
        self.advanceDeletionCleanup()
        self.refreshDistance()
        if self.pendingOwnerCommand?.action == "discard", !self.isFinishing {
          if self.session != nil { await self.end() }
          else if let end = (self.metadata["endedAt"] as? String).flatMap({ try? WorkoutCoding.date($0) }) {
            do { try await self.finishDiscard(at: end) } catch { self.issue = "Discard will retry: \(error.localizedDescription)" }
          }
        }
        if !self.saveToHealth, self.metadata["endedAt"] != nil, self.session != nil, !self.isFinishing { await self.end() }
        if self.isActive { self.sendStatus(); self.retryRawHealthQueries(); await self.replayRetainedTelemetry() }
        self.retrySync()
        if let journal = self.journal { Task.detached(priority: .utility) { try? journal.store.checkpoint() } }
      }
    }
  }

  func handleLaunch(configuration: HKWorkoutConfiguration) async {
    await activate()
    guard !isActive, !isBusy else { sendStatus(); return }
    if let command = WCSession.default.receivedApplicationContext["pendingStart"] as? [String: Any] {
      await receive(command)
    }
    if !isActive && !isBusy {
      // HealthKit's configuration has no application workout ID. Ask the phone for its durable start command.
      send(["schemaVersion": 1, "kind": "status", "phase": "ready", "messageId": UUID().uuidString.lowercased()])
      // An old launch must not replace a completed ride or a real start/recovery failure.
      // Launch alone is not evidence of a missing connection or an unresolved request.
      startHandoff.begin(at: Date(), phase: phase, hasRide: workoutID != nil, canStart: canStart, hasIssue: displayedIssue != nil)
    }
  }

  func start(indoor: Bool, eBike: Bool, id: String = UUID().uuidString.lowercased(), command incoming: WorkoutCommand? = nil, saveToHealth: Bool = true, recordGPS: Bool? = nil) async {
    await activate()
    startHandoff.clear()
    guard let journal else { return }
    guard canStart, !recovering, pendingInserts == 0, acceptingBatches == 0 else {
      issue = "The owner is still recovering or finishing. Retry before starting another ride."; return
    }
    if let incoming, incoming.workoutID != id { issue = "Start command belongs to another workout"; return }
    pendingOwnerCommand = incoming
    sessionGeneration = UUID()
    var generation = sessionGeneration
    isBusy = true
    issue = nil
    metadata = [:]; sessionReady = false; phase = "preparing"
    var createdArchive = false
    defer { isBusy = false }
    do {
      let id = try WorkoutCoding.id(id)
      guard HKHealthStore.isHealthDataAvailable() else {
        throw WorkoutDataError.invalid("HealthKit is unavailable on this Watch.")
      }
      let gps = recordGPS ?? !indoor
      let sharing = saveToHealth ? (gps ? WatchHealthMetrics.shareTypes : WatchHealthMetrics.shareTypes.subtracting([HKSeriesType.workoutRoute()])) : [HKObjectType.workoutType()]
      try await healthStore.requestAuthorization(toShare: sharing, read: gps ? WatchHealthMetrics.readTypes : WatchHealthMetrics.readTypes.subtracting([HKSeriesType.workoutRoute()]))
      guard generation == sessionGeneration else { return }
      guard healthStore.authorizationStatus(for: HKObjectType.workoutType()) == .sharingAuthorized else {
        throw WorkoutDataError.invalid("Allow workout access in Health to run the Watch sensor session. Saving to Health follows the ride option.")
      }
      let config = HKWorkoutConfiguration()
      config.activityType = .cycling
      config.locationType = indoor ? .indoor : .outdoor
      if pendingOwnerCommand == nil {
        let command = try journal.control.admitLocal(workoutID: id, origin: "watch", action: "start", at: Date(),
          options: ["indoor": .bool(indoor), "eBike": .bool(eBike), "saveToHealth": .bool(saveToHealth), "recordGPS": .bool(recordGPS ?? !indoor)])
        _ = try journal.control.accept(command)
        let result = try journal.control.begin(command)
        guard result.outcome == "executing" else { throw WorkoutDataError.invalid("The start intent could not execute") }
        pendingOwnerCommand = command
      }
      // A recovered uncertain start must resolve an existing session/workout before this path is called.
      let newSession = try HKWorkoutSession(healthStore: healthStore, configuration: config)
      let start = Date()
      metadata = ["workoutId": id, "startedAt": WorkoutCoding.timestamp(start),
        "startedAtMs": start.timeIntervalSince1970 * 1000, "indoor": indoor, "eBike": eBike,
        "saveToHealth": saveToHealth, "recordGPS": recordGPS ?? !indoor,
        "phase": "running", "phaseTimestamp": WorkoutCoding.timestamp(start), "healthKitState": saveToHealth ? "pending" : "notRequested", "eventCount": 0,
        "workoutMonotonicOrigin": ProcessInfo.processInfo.systemUptime]
      try journal.create(id: id, metadata: metadata)
      createdArchive = true
      metrics.removeAll(); lapCount = 0
      insertingTelemetry.removeAll()
      lastLocation = nil; gpsBarrier = false; distanceSnapshot = nil; distanceSourceLabel = "Distance"; distanceLabel = "— km"
      attach(newSession)
      generation = sessionGeneration
      let identity = effectIdentity(id)
      guard let builder else { throw WorkoutDataError.invalid("The workout builder could not start.") }
      try await builder.addMetadata([
        HKMetadataKeyExternalUUID: id, HKMetadataKeySyncIdentifier: "power-log-workout-\(id)",
        HKMetadataKeySyncVersion: 1, HKMetadataKeyIndoorWorkout: indoor,
        HKMetadataKeyWorkoutBrandName: "Power Log", "PowerLogSport": eBike ? "e_biking" : "cycling",
        "PowerLogRiderPowerSource": "CYC rider power (not motor electrical input)",
        "PowerLogSaveToHealth": saveToHealth, "PowerLogRecordGPS": recordGPS ?? !indoor
      ])
      guard isCurrent(identity), session === newSession else { return }
      newSession.startActivity(with: start)
      try await builder.beginCollection(at: start)
      guard isCurrent(identity), session === newSession else { return }
      phase = "running"
      sessionReady = true
      try commitOwner(phase: "running", at: start, completing: pendingOwnerCommand)
      sendStatus()
      _ = try record(kind: "lifecycle", date: start, payload: ["action": .string("start"),
        "indoor": .bool(indoor), "subSport": .string(eBike ? "e_biking" : "generic")])
      startLocations()
      beginRawHealthQueries(start: start)
      startMirroring()
      sendStatus()
      WKInterfaceDevice.current().play(.start)
    } catch {
      guard generation == sessionGeneration else { return }
      issue = error.localizedDescription
      if let session { session.end(); self.session = nil; sessionWorkoutID = nil; builder = nil; routeBuilder = nil }
      phase = "failed"
      if createdArchive {
        metadata["phase"] = phase; metadata["healthKitState"] = saveToHealth ? "failed" : "notRequested"
        metadata["error"] = error.localizedDescription
        do { try commitOwner(phase: "failed", at: Date(), completing: pendingOwnerCommand, failure: error.localizedDescription) }
        catch { issue = "Failed start result remains pending: \(error.localizedDescription)" }
      } else if let command = pendingOwnerCommand {
        do {
          let snapshot = try journal.control.observe(workoutID: command.workoutID, owner: "watch", phase: "failed", at: Date(),
            health: saveToHealth ? "notSaved" : "notRequested", command: command, failure: error.localizedDescription)
          pendingOwnerCommand = nil
          acknowledge(command.id, id: command.workoutID, result: try journal.control.result(id: command.id))
          send(envelope(kind: "status", values: ["workoutId": command.workoutID, "phase": "failed", "ownerSnapshot": WorkoutCoding.dictionary(snapshot)]))
        } catch { issue = "Start failure result is pending: \(error.localizedDescription)" }
      }
      sendStatus()
    }
  }

  private func attach(_ value: HKWorkoutSession) {
    session = value; value.delegate = self
    recoveredForeignWorkoutID = nil
    sessionWorkoutID = workoutID; sessionGeneration = UUID()
    let live = value.associatedWorkoutBuilder()
    builder = live; live.delegate = self
    if live.dataSource == nil {
      let source = HKLiveWorkoutDataSource(healthStore: healthStore, workoutConfiguration: value.workoutConfiguration)
      // Saving off keeps the sensor session but disables the app builder's automatic additions.
      // Native device queries still retain returned system sensor originals locally.
      for type in WatchHealthMetrics.quantityTypes {
        if saveToHealth { source.enableCollection(for: type, predicate: nil) } else { source.disableCollection(for: type) }
      }
      if let type = HKQuantityType.quantityType(forIdentifier: .cyclingPower) { source.disableCollection(for: type) }
      if let type = HKQuantityType.quantityType(forIdentifier: .cyclingCadence) { source.disableCollection(for: type) }
      live.dataSource = source
    }
    if !saveToHealth, let source = live.dataSource { for type in source.typesToCollect { source.disableCollection(for: type) } }
    routeBuilder = saveToHealth && recordGPS ? live.seriesBuilder(for: HKSeriesType.workoutRoute()) as? HKWorkoutRouteBuilder : nil
  }

  func recover(expectedID: String? = nil, probeID: UUID = UUID()) async {
    guard session == nil, !recovering, !isBusy, !isFinishing, !deletionProbe.inFlight, let journal else { return }
    if let expectedID, (try? journal.store.isWorkoutDeleted(id: expectedID)) != false { return }
    nativeAbsenceEvidence = nil
    gpsBarrier = true; lastLocation = nil; distanceSnapshot = nil
    recovering = true
    let generation = sessionGeneration
    defer { recovering = false }
    do {
      let saved = try await journal.detached { try journal.allMetadata() }
      var candidate = saved.first {
        (expectedID == nil || $0["workoutId"] as? String == expectedID) && WorkoutRecoveryPlanner.inspectsWatchCandidate(
          phase: $0["phase"] as? String ?? "", health: $0["healthKitState"] as? String ?? "unknown", finalHealthExtracted: $0[$0["saveToHealth"] as? Bool == false ? "finalLocalSensorsExtracted" : "finalHealthExtracted"] as? Bool == true)
      }
      unresolvedNativeOwner = true
      let recovered = try await healthStore.recoverActiveWorkoutSession()
      guard generation == sessionGeneration, session == nil else { return }
      recoveredForeignWorkoutID = nil
      unresolvedNativeOwner = false
      var savedWithoutJournal: HKWorkout?
      if WorkoutRecoveryPlanner.needsSavedIdentityLookup(requestedID: expectedID, candidateID: candidate?["workoutId"] as? String, hasNativeSession: recovered != nil), let expectedID {
        savedWithoutJournal = try await savedWorkout(id: expectedID)
        guard generation == sessionGeneration, session == nil else { return }
        if let existing = savedWithoutJournal {
          let replacement: [String: Any] = ["workoutId": expectedID,
            "startedAt": WorkoutCoding.timestamp(existing.startDate), "startedAtMs": existing.startDate.timeIntervalSince1970 * 1000,
            "endedAt": WorkoutCoding.timestamp(existing.endDate), "phase": "completed", "healthKitState": "saved",
            "stopElapsedSeconds": max(0, existing.endDate.timeIntervalSince(existing.startDate)), "timerSeconds": existing.duration,
            "healthKitUUID": existing.uuid.uuidString.lowercased(), "indoor": existing.metadata?[HKMetadataKeyIndoorWorkout] as? Bool ?? false,
            "recoveredWithoutLocalJournal": true, "eventCount": 0]
          if let retained = try? journal.metadata(id: expectedID) { candidate = retained }
          else { try journal.create(id: expectedID, metadata: replacement); candidate = replacement }
        }
      }
      if let recovered {
        let externalID = (recovered.associatedWorkoutBuilder().metadata[HKMetadataKeyExternalUUID] as? String).flatMap { try? WorkoutCoding.id($0) }
        guard let externalID else { unresolvedNativeOwner = true; throw WorkoutDataError.invalid("Recovered Health session lacks its stable external identity; local collections were retained") }
        if try journal.store.isWorkoutDeleted(id: externalID) {
          recoveredForeignWorkoutID = externalID
          throw PowerLogStorageError.deleted(externalID)
        }
        guard expectedID == nil || expectedID == externalID else {
          recoveredForeignWorkoutID = externalID
          throw WorkoutDataError.invalid("A different native workout is active; the requested owner remains unresolved")
        }
        candidate = try? journal.metadata(id: externalID)
        if candidate == nil {
          let live = recovered.associatedWorkoutBuilder()
          let recoveredID = externalID
          let start = recovered.startDate ?? live.startDate ?? Date()
          let replacement: [String: Any] = ["workoutId": recoveredID, "startedAt": WorkoutCoding.timestamp(start),
            "startedAtMs": start.timeIntervalSince1970 * 1000,
            "indoor": recovered.workoutConfiguration.locationType == .indoor,
            "eBike": live.metadata["PowerLogSport"] as? String != "cycling", "phase": "running",
            "saveToHealth": live.metadata["PowerLogSaveToHealth"] as? Bool ?? true,
            "recordGPS": live.metadata["PowerLogRecordGPS"] as? Bool ?? (recovered.workoutConfiguration.locationType != .indoor),
            "healthKitState": live.metadata["PowerLogSaveToHealth"] as? Bool == false ? "notRequested" : "pending", "recoveredWithoutLocalJournal": true, "eventCount": 0]
          if let existing = try? journal.metadata(id: recoveredID) { candidate = existing }
          else {
            try journal.create(id: recoveredID, metadata: replacement)
            candidate = replacement
            issue = "Recovered the active Health workout. Local recording has resumed; earlier local records were unavailable."
          }
        }
        guard let candidate, let id = candidate["workoutId"] as? String else { return }
        metadata = candidate
        if metadata["endedAt"] == nil, let actualEnd = recovered.endDate { metadata["endedAt"] = WorkoutCoding.timestamp(actualEnd) }
        try journal.control.repairTerminalSlot(workoutID: id)
        pendingOwnerCommand = try journal.control.active(workoutID: id)
        let projection = try journal.recoveryProjection(id: id)
        lapCount = projection.lapCount
        for event in projection.events { applyDisplay(event) }
        attach(recovered)
        if metadata["discardRequested"] as? Bool == true || pendingOwnerCommand?.action == "discard" {
          await end(at: pendingOwnerCommand.flatMap { try? WorkoutCoding.date($0.requestedAt) }); return
        }
        let identity = effectIdentity(id)
        // A recovered stop can finish before replaying retained telemetry; preserve the same-workout retry requirement.
        if saveToHealth, try journal.archive.sourceProgress(id: id, producer: "cyc").count > 0 { metadata["cycHealthSamplesIncomplete"] = true }
        sessionReady = true
        phase = [HKWorkoutSessionState.stopped, .ended].contains(recovered.state) || candidate["endedAt"] != nil ? "finished" : recovered.state == .paused ? "paused" : "running"
        if ![HKWorkoutSessionState.stopped, .ended].contains(recovered.state), let start = recovered.startDate { beginRawHealthQueries(start: start) }
        startMirroring()
        if await reconcileRecoveredCommand(recovered) { if isCurrent(identity) { startLocations(); sendStatus() }; return }
        guard isCurrent(identity), session === recovered else { return }
        metadata["phase"] = phase == "finished" ? "completed" : phase; persistMetadata()
        startLocations(); sendStatus()
        await replayRetainedTelemetry()
        guard isCurrent(identity), session === recovered else { return }
        if recovered.state == .stopped || recovered.state == .ended || candidate["endedAt"] != nil {
          await end()
        }
      } else if let candidate, let id = candidate["workoutId"] as? String {
        metadata = candidate
        sessionGeneration = UUID()
        let identity = effectIdentity(id)
        try journal.control.repairTerminalSlot(workoutID: id)
        pendingOwnerCommand = try journal.control.active(workoutID: id)
        if metadata["discardRequested"] as? Bool == true || pendingOwnerCommand?.action == "discard" || rideDiscarded {
          let end = (metadata["endedAt"] as? String).flatMap { try? WorkoutCoding.date($0) } ?? pendingOwnerCommand.flatMap { try? WorkoutCoding.date($0.requestedAt) } ?? Date()
          try await finishDiscard(at: end); return
        }
        if !saveToHealth {
          if let cutoff = (metadata["endedAt"] as? String).flatMap({ try? WorkoutCoding.date($0) }) ?? pendingOwnerCommand.flatMap({ $0.endsWorkout ? try? WorkoutCoding.date($0.requestedAt) : nil }) {
            metadata["endedAt"] = WorkoutCoding.timestamp(cutoff)
            try journal.save(id: id, metadata: metadata)
            try await finishLocalSave(id: id, at: cutoff)
          } else {
            phase = "recoverable"; metadata["phase"] = phase; metadata["interrupted"] = true
            issue = WatchWorkoutJournal.unavailableOwnerIssue; persistMetadata()
          }
          return
        }
        let existing: HKWorkout?
        if let savedWithoutJournal { existing = savedWithoutJournal } else { existing = try await savedWorkout(id: id) }
        guard isCurrent(identity), session == nil else { return }
        if let existing {
          metadata["healthKitUUID"] = existing.uuid.uuidString.lowercased()
          metadata["healthKitState"] = "saved"
          metadata["endedAt"] = (try journal.control.snapshot(workoutID: id)?.stopCutoff) ?? metadata["endedAt"] ?? WorkoutCoding.timestamp(existing.endDate)
          if metadata["timerSeconds"] == nil { metadata["timerSeconds"] = existing.duration }
          if metadata["stopElapsedSeconds"] == nil { metadata["stopElapsedSeconds"] = max(0, existing.endDate.timeIntervalSince(existing.startDate)) }
          await collectFinishedHealth(existing, collectionID: id)
          guard isCurrent(identity), session == nil else { return }
          phase = "finished"; metadata["phase"] = "completed"
          let command = pendingOwnerCommand
          let failure: String? = command != nil && !["start", "stop"].contains(command!.action) ? "Saved workout has no evidence for this pending action" : nil
          try commitOwner(phase: "completed", at: existing.endDate, completing: command, failure: failure)
        } else if candidate["endedAt"] != nil {
          phase = "finished"; metadata["phase"] = "completed"; metadata["healthKitState"] = "pending"
          issue = "The original Health workout is not accessible yet. Finalization will retry with the same workout identity."
        } else if try presentCancelledStart(id: id) {
          // A prior explicit stop already resolved this attempt. Keep Start ride available.
        } else {
          phase = "recoverable"; metadata["phase"] = "recoverable"; metadata["interrupted"] = true
          metadata["healthKitState"] = "unknown"
          issue = WatchWorkoutJournal.unavailableOwnerIssue
          metadata["error"] = issue
        }
        persistMetadata(); queueArchive()
      }
      if recovered == nil, session == nil, let expectedID {
        nativeAbsenceEvidence = WorkoutNativeAbsenceEvidence(identity: effectIdentity(expectedID), probeID: probeID)
      }
    } catch { issue = "Workout recovery: \(error.localizedDescription)" }
  }

  private func replayRetainedTelemetry() async {
    guard saveToHealth, !replayingTelemetry, let journal, let id = workoutID else { return }
    let identity = effectIdentity(id)
    replayingTelemetry = true; defer { replayingTelemetry = false }
    do {
      let events = try await Task.detached(priority: .utility) { () -> [WorkoutEvent] in
        let insertion = WorkoutHealthInsertionJournal(archive: journal.archive)
        _ = try insertion.outcome(id: id) // Incremental cursor reconciliation off the main actor.
        return try insertion.pending(id: id, limit: 32)
      }.value
      guard isCurrent(identity) else { return }
      for event in events { guard isCurrent(identity) else { return }; try await acceptTelemetry(event) }
    } catch { issue = "Retained telemetry remains pending: \(error.localizedDescription)" }
  }

  /// Returns true when replay dispatched an asynchronous native effect or drained a queued stop.
  private func reconcileRecoveredCommand(_ recovered: HKWorkoutSession) async -> Bool {
    guard let journal, let id = workoutID, let builder else { return false }
    guard session === recovered, nativeWorkoutID == id else { return false }
    let identity = effectIdentity(id)
    do {
      let nativePhase = recovered.state == .paused ? "paused" : recovered.state == .stopped ? "stopped" : recovered.state == .ended ? "ended" : "running"
      var dates: [String: Date] = [:]
      for event in builder.workoutEvents {
        if event.type == .pause { dates["pause"] = event.dateInterval.start }
        if event.type == .resume { dates["resume"] = event.dateInterval.start }
        if event.metadata?["PowerLogEventId"] as? String == pendingOwnerCommand?.id { dates["lap"] = event.dateInterval.start }
      }
      if let start = recovered.startDate { dates["start"] = start }
      let laps = Set(builder.workoutEvents.compactMap { $0.metadata?["PowerLogEventId"] as? String })
      let result = try WorkoutRecoveredOwnerCommand.reconcile(workoutID: id, owner: "watch", nativePhase: nativePhase,
        observedAt: Date(), nativeEventDates: dates, observedLapIDs: laps,
        cutoff: (metadata["endedAt"] as? String).flatMap { try? WorkoutCoding.date($0) },
        health: metadata["healthKitState"] as? String ?? "pending", healthID: metadata["healthKitUUID"] as? String,
        archive: journal.archive, control: journal.control) { snapshot in
          self.metadata["ownerRevision"] = String(snapshot.ownerRevision)
          self.metadata["phaseTimestamp"] = snapshot.effectiveAt
          self.metadata["phase"] = snapshot.phase
          self.metadata["endedAt"] = snapshot.stopCutoff
          self.metadata["eventCount"] = try journal.archive.metadata(id: id).eventCount
          try journal.save(id: id, metadata: self.metadata)
        }
      if let command = result.completed {
        pendingOwnerCommand = nil
        if result.insertedLap { lapCount += 1 }
        if let snapshot = result.snapshot { phase = ["completed", "finishing"].contains(snapshot.phase) ? "finished" : snapshot.phase }
        acknowledge(command.id, result: try journal.control.result(id: command.id))
        sendStatus()
      }
      if let next = result.next {
        await receive(next.packet)
        guard isCurrent(identity), session === recovered else { return true }
        if next.endsWorkout { return true }
      }
      if let required = result.required {
        pendingOwnerCommand = required
        switch required.action {
        case "pause": recovered.pause(); return true
        case "resume": recovered.resume(); return true
        case "lap": try await performLap(required); return true
        case "stop", "discard": await end(at: try WorkoutCoding.date(required.requestedAt)); return true
        default: break
        }
      }
      return false
    } catch { issue = "Owner command recovery remains pending: \(error.localizedDescription)"; return true }
  }

  func pause() { localAction("pause") }
  func resume() { localAction("resume") }
  func lap(eventID: String = UUID().uuidString.lowercased()) { localAction("lap") }

  private func localAction(_ action: String) {
    guard let id = workoutID, let journal else { return }
    Task { @MainActor in
      do {
        let command = try journal.control.admitLocal(workoutID: id, origin: "watch", action: action, at: Date())
        if action == "pause" { WKInterfaceDevice.current().play(.directionDown) }
        else if action == "resume" { WKInterfaceDevice.current().play(.directionUp) }
        await receive(command.packet)
      } catch { issue = error.localizedDescription; WKInterfaceDevice.current().play(.failure) }
    }
  }

  private func commitOwner(phase next: String, at date: Date, completing command: WorkoutCommand? = nil,
                           failure: String? = nil) throws {
    guard let id = workoutID, let journal else { throw WorkoutDataError.invalid("Owner storage unavailable") }
    try WorkoutEffectIdentity.require(command: command, workoutID: id, nativeWorkoutID: nativeWorkoutID)
    let next = WorkoutOwnerPhase.canonical(next)
    let cutoff = (metadata["endedAt"] as? String).flatMap { try? WorkoutCoding.date($0) }
    try journal.store.transaction(priority: .capture) { _ in
      let snapshot = try journal.control.observe(workoutID: id, owner: "watch", phase: next, at: date,
        health: metadata["healthKitState"] as? String ?? "pending", healthID: metadata["healthKitUUID"] as? String,
        cutoff: cutoff, command: command, failure: failure)
      metadata["ownerRevision"] = String(snapshot.ownerRevision)
      metadata["phaseTimestamp"] = snapshot.effectiveAt
      metadata["phase"] = snapshot.phase
      try journal.save(id: id, metadata: metadata)
    }
    if let command {
      if pendingOwnerCommand?.id == command.id { pendingOwnerCommand = nil }
      acknowledge(command.id, id: command.workoutID, result: try journal.control.result(id: command.id))
      for origin in ["watch", "phone"] {
        if let next = try journal.control.nextReady(workoutID: id, origin: origin) {
          Task { @MainActor in await self.receive(next.packet) }
          break
        }
      }
    }
  }

  private func performLap(_ command: WorkoutCommand) async throws {
    guard let builder else { throw WorkoutDataError.invalid("The Health workout is unavailable") }
    guard let id = workoutID else { throw WorkoutDataError.invalid("The original workout is unavailable") }
    try WorkoutEffectIdentity.require(command: command, workoutID: id, nativeWorkoutID: nativeWorkoutID)
    let identity = effectIdentity(id)
    let date = try WorkoutCoding.date(command.requestedAt)
    // The builder's persisted event metadata reconciles a successful side effect whose local receipt was lost.
    if saveToHealth, let journal, !builder.workoutEvents.contains(where: { $0.metadata?["PowerLogEventId"] as? String == command.id }) {
      try WorkoutRecordingPolicy.requireHealthWrite(id: command.workoutID, archive: journal.archive)
      try await builder.addWorkoutEvents([HKWorkoutEvent(type: .lap,
        dateInterval: DateInterval(start: date, duration: 0), metadata: ["PowerLogEventId": command.id])])
    }
    guard isCurrent(identity), self.builder === builder else { return }
    let existed = try journal?.contains(id: command.workoutID, eventID: command.id) ?? false
    _ = try record(kind: "lifecycle", date: date, payload: ["action": .string("lap")], eventId: command.id)
    if !existed { lapCount += 1 }
    try commitOwner(phase: phase, at: date, completing: command)
    WKInterfaceDevice.current().play(.click)
  }

  func end(at requestedEnd: Date? = nil, discard: Bool = false) async {
    if discard && (!canControl || isBusy || pendingOwnerCommand != nil) { issue = "Wait for the current ride operation before discarding."; return }
    if (metadata["discardRequested"] as? Bool == true || pendingOwnerCommand?.action == "discard"), builder == nil, !isFinishing {
      isFinishing = true; defer { isFinishing = false }
      do { try await finishDiscard(at: (metadata["endedAt"] as? String).flatMap { try? WorkoutCoding.date($0) } ?? requestedEnd ?? Date()) }
      catch { issue = "Discard will retry: \(error.localizedDescription)" }
      return
    }
    guard let session, let builder, !isFinishing else { return }
    guard let currentID = workoutID else { return }
    do { try WorkoutEffectIdentity.require(command: pendingOwnerCommand, workoutID: currentID, nativeWorkoutID: nativeWorkoutID) }
    catch { issue = error.localizedDescription; return }
    let identity = effectIdentity(currentID)
    if pendingOwnerCommand?.endsWorkout != true, metadata["endedAt"] == nil, let id = workoutID, let journal {
      do {
        let command = try journal.control.admitLocal(workoutID: id, origin: "watch", action: discard ? "discard" : "stop", at: requestedEnd ?? Date())
        let preparation = try journal.control.prepare(command)
        if preparation.execute { pendingOwnerCommand = command }
        else {
          metadata["endedAt"] = command.requestedAt; metadata["phase"] = "finishing"; phase = "finished"
          try journal.save(id: id, metadata: metadata)
          return // The preceding native callback drains this retained command.
        }
      } catch { issue = error.localizedDescription; return }
    }
    isFinishing = true; isBusy = false; phase = "finished"
    defer { isFinishing = false; isBusy = false }
    let startDate = (metadata["startedAt"] as? String).flatMap { try? WorkoutCoding.date($0) } ?? session.startDate ?? Date()
    let proposedEnd = (metadata["endedAt"] as? String).flatMap { try? WorkoutCoding.date($0) } ?? requestedEnd ?? Date()
    let endDate = min(Date(), max(startDate, proposedEnd))
    metadata["endedAt"] = WorkoutCoding.timestamp(endDate); metadata["phase"] = "completed"
    if discard || pendingOwnerCommand?.action == "discard" { metadata["discardRequested"] = true }
    if metadata["stopElapsedSeconds"] == nil {
      metadata["stopElapsedSeconds"] = pendingOwnerCommand?.options["cutoffElapsedSeconds"]?.number ??
        (metadata["workoutMonotonicOrigin"] as? Double).map { max(0, ProcessInfo.processInfo.systemUptime - $0) } ?? max(0, endDate.timeIntervalSince(startDate))
    }
    metadata["phaseTimestamp"] = WorkoutCoding.timestamp(endDate)
    do { if let id = workoutID { try journal?.save(id: id, metadata: metadata) } }
    catch { issue = error.localizedDescription; return }
    do {
      _ = try record(kind: "lifecycle", date: endDate, payload: ["action": .string("stop")],
        eventId: pendingOwnerCommand?.id ?? WorkoutStableIdentity.uuid("stop:" + (workoutID ?? "")))
    } catch { issue = error.localizedDescription; return }
    locationManager.stopUpdatingLocation(); gpsLabel = recordGPS ? "GPS saved" : "GPS off"
    stopQueries()
    if metadata["discardRequested"] as? Bool == true {
      do { try await finishDiscard(at: endDate) } catch { issue = "Discard will retry: \(error.localizedDescription)" }
      return
    }
    if !saveToHealth {
      do { try await finishLocalSave(id: currentID, at: endDate) }
      catch { issue = "Local sensor finalization will retry: \(error.localizedDescription)"; persistMetadata(); queueArchive() }
      return
    }
    do { try commitOwner(phase: "finishing", at: endDate) } catch { issue = error.localizedDescription; return }
    if session.state != .stopped && session.state != .ended { session.stopActivity(with: endDate) }
    sendStatus()
    do {
      // Await all prior route/telemetry additions before closing the same primary builder.
      while pendingInserts > 0 { try await Task.sleep(nanoseconds: 20_000_000) }
      guard isCurrent(identity), self.session === session else { return }
      if let id = workoutID { try journal?.flush(id: id) }
      if builder.endDate == nil { try await builder.endCollection(at: endDate) }
      guard isCurrent(identity), self.session === session else { return }
      let existing = try await savedWorkout(id: workoutID ?? "")
      guard isCurrent(identity), self.session === session else { return }
      let workout: HKWorkout?
      if let existing { workout = existing } else {
        guard let journal else { throw WorkoutDataError.invalid("Missing archive") }
        try WorkoutRecordingPolicy.requireHealthWrite(id: currentID, archive: journal.archive)
        workout = try await builder.finishWorkout()
      }
      guard isCurrent(identity), self.session === session else { return }
      if let workout {
        metadata["healthKitUUID"] = workout.uuid.uuidString.lowercased()
        metadata["healthKitState"] = "saved"
        try commitOwner(phase: "completed", at: endDate, completing: pendingOwnerCommand?.action == "stop" ? pendingOwnerCommand : nil)
        await collectFinishedHealth(workout)
        guard isCurrent(identity), self.session === session else { return }
        try commitOwner(phase: "completed", at: endDate)
      } else {
        // A nil workout without an error can mean success while locked; never manufacture a second workout.
        metadata["healthKitState"] = "pending"
        issue = "Unlock Watch to confirm the saved Health workout. The local ride is retained."
      }
      metadata["phase"] = "completed"
      phase = "finished"
      persistMetadata(); sendStatus(); queueArchive()
      session.end(); self.session = nil; sessionWorkoutID = nil; self.builder = nil; routeBuilder = nil
      WKInterfaceDevice.current().play(.stop)
    } catch {
      guard isCurrent(identity), self.session === session else { return }
      issue = "Health save: \(error.localizedDescription). Local ride data is retained."
      metadata["healthKitState"] = "failed"; metadata["error"] = issue
      // Keep the stopped session/builder so the user can retry saving without creating another workout.
      phase = "finished"; metadata["phase"] = "completed"; persistMetadata(); sendStatus()
      queueArchive()
    }
  }

  /// Normal Save retains the archive and its stop command. Only the ephemeral Health builder is discarded.
  private func finishLocalSave(id: String, at cutoff: Date) async throws {
    guard let journal, !saveToHealth, workoutID == id else { return }
    let identity = effectIdentity(id), native = session, live = builder
    locationManager.stopUpdatingLocation(); stopQueries()
    metadata["timerSeconds"] = live?.elapsedTime ?? metadata["timerSeconds"] ?? 0
    metadata["healthKitState"] = "notRequested"; metadata.removeValue(forKey: "healthKitUUID")
    metadata["phase"] = "finishing"; metadata["endedAt"] = WorkoutCoding.timestamp(cutoff)
    try journal.save(id: id, metadata: metadata)
    try commitOwner(phase: "finishing", at: cutoff)
    if let native, native.state != .stopped && native.state != .ended { native.stopActivity(with: cutoff) }
    let deadline = ProcessInfo.processInfo.systemUptime + 5
    while let native, native.state != .stopped && native.state != .ended, ProcessInfo.processInfo.systemUptime < deadline {
      try await Task.sleep(nanoseconds: 50_000_000)
    }
    guard isCurrent(identity), native == nil || native?.state == .stopped || native?.state == .ended else {
      throw WorkoutDataError.invalid("The sensor session has not stopped yet")
    }
    if let live, live.endDate == nil { try await live.endCollection(at: cutoff) }
    try await collectLocalSensorOriginals(id: id, cutoff: cutoff)
    guard isCurrent(identity) else { return }
    // Apple's discard does not undo prior sample additions: the write policy and disabled
    // automatic builder collection have already prevented this app from adding them.
    live?.discardWorkout()
    if let native, native.state != .ended { native.end() }
    let endDeadline = ProcessInfo.processInfo.systemUptime + 5
    while let native, native.state != .ended, ProcessInfo.processInfo.systemUptime < endDeadline {
      try await Task.sleep(nanoseconds: 50_000_000)
    }
    guard isCurrent(identity), native == nil || native?.state == .ended else {
      throw WorkoutDataError.invalid("The sensor session has not ended yet")
    }
    session = nil; sessionWorkoutID = nil; builder = nil; routeBuilder = nil
    metadata["localRecorderEnded"] = true
    metadata["phase"] = "completed"; phase = "finished"; issue = nil
    try commitOwner(phase: "completed", at: cutoff, completing: pendingOwnerCommand?.action == "stop" ? pendingOwnerCommand : nil)
    persistMetadata(); sendStatus(); queueArchive(id: id)
    WKInterfaceDevice.current().play(.stop)
  }

  /// A bounded device/window read needs no saved workout. Pages and anchors commit atomically;
  /// retries retain stable sample/series identities and never create Health objects.
  private func collectLocalSensorOriginals(id: String, cutoff: Date) async throws {
    guard let journal else { throw WorkoutDataError.invalid("Local sensor storage unavailable") }
    let record = try journal.archive.metadata(id: id), start = try WorkoutCoding.date(record.startedAt)
    guard !record.savesToHealth else { throw WorkoutDataError.invalid("Expected local-only sensor finalization") }
    let window = WorkoutSensorWindow(start: start, cutoff: cutoff)
    let predicate = NSCompoundPredicate(andPredicateWithSubpredicates: [
      // Select overlapping parents so a series crossing the cutoff can still yield its in-window points.
      HKQuery.predicateForSamples(withStart: start, end: cutoff, options: []),
      HKQuery.predicateForObjects(from: [HKDevice.local()])
    ])
    for metric in WatchHealthMetrics.supported {
      guard let type = metric.type else { continue }
      let key = "local-final:" + type.identifier
      var anchor: HKQueryAnchor?
      if let bytes = try journal.healthAnchor(id: id, progressKey: key) {
        anchor = try NSKeyedUnarchiver.unarchivedObject(ofClass: HKQueryAnchor.self, from: bytes)
      }
      while true {
        let page: (samples: [HKQuantitySample], deleted: [HKDeletedObject], anchor: HKQueryAnchor?, count: Int) = try await withCheckedThrowingContinuation { continuation in
          healthStore.execute(HKAnchoredObjectQuery(type: type, predicate: predicate, anchor: anchor, limit: 256) { _, samples, deleted, nextAnchor, error in
            if let error { continuation.resume(throwing: error) }
            else { continuation.resume(returning: (samples?.compactMap { $0 as? HKQuantitySample } ?? [], deleted ?? [], nextAnchor,
              (samples?.count ?? 0) + (deleted?.count ?? 0))) }
          })
        }
        try commitHealthPage(page.samples.filter { $0.count > 1 || window.contains(start: $0.startDate, end: $0.endDate) },
          deleted: page.deleted, anchor: page.anchor, key: key, completed: page.count < 256, collectionID: id)
        guard page.count >= 256, let next = page.anchor else { break }
        anchor = next
        await Task.yield()
      }
      let descriptor = HKQuantitySeriesSampleQueryDescriptor(predicate: .quantitySample(type: type, predicate: predicate),
        options: [.includeSample, .orderByQuantitySampleStartDate])
      var batch: [WorkoutEvent] = []
      for try await point in descriptor.results(for: healthStore) {
        guard let sample = point.sample, sample.count > 1,
          window.contains(start: point.dateInterval.start, end: point.dateInterval.end) else { continue }
        let value = point.quantity.doubleValue(for: metric.unit)
        guard value.isFinite else { continue }
        let identity = "\(sample.uuid):\(point.dateInterval.start.timeIntervalSince1970):\(point.dateInterval.end.timeIntervalSince1970):\(value)"
        var payload: [String: WorkoutJSON] = ["healthKitIdentifier": .string(type.identifier), "value": .number(value),
          "unit": .string(metric.unitName), "representation": .string("rawSeries"), "sampleUUID": .string(sample.uuid.uuidString.lowercased()),
          "sampleStart": .string(WorkoutCoding.timestamp(point.dateInterval.start)), "sampleEnd": .string(WorkoutCoding.timestamp(point.dateInterval.end)),
          "sourceBundleIdentifier": .string(sample.sourceRevision.source.bundleIdentifier)]
        if metric.identifier == .heartRate { payload["heartRateBpm"] = .number(value) }
        batch.append(try WorkoutEvent(workoutId: id, kind: "health", source: "watch", timestamp: point.dateInterval.end,
          elapsedSeconds: max(0, point.dateInterval.end.timeIntervalSince(start)), payload: payload, eventId: WatchHealthMetrics.stableEventID(identity)))
        if batch.count == 128 { _ = try journal.archive.appendBatch(batch); batch.removeAll(keepingCapacity: true); await Task.yield() }
      }
      if !batch.isEmpty { _ = try journal.archive.appendBatch(batch) }
    }
    var latest = workoutID == id ? metadata : try journal.metadata(id: id)
    latest["finalLocalSensorsExtracted"] = true; latest["archiveDirty"] = true
    latest["healthReadAccess"] = "Returned local-device samples only; HealthKit does not reveal denied read access."
    try journal.save(id: id, metadata: latest)
    if workoutID == id { metadata = latest }
  }

  private func finishDiscard(at endDate: Date) async throws {
    sessionGeneration = UUID()
    locationManager.stopUpdatingLocation(); stopQueries()
    let discarded = session
    builder?.discardWorkout(); builder = nil; routeBuilder = nil
    if discarded?.state != .ended { discarded?.end() }
    let deadline = ProcessInfo.processInfo.systemUptime + 5
    while let discarded, discarded.state != .ended, ProcessInfo.processInfo.systemUptime < deadline {
      try await Task.sleep(nanoseconds: 50_000_000)
    }
    guard discarded == nil || discarded?.state == .ended else { throw WorkoutDataError.invalid("The native recorder has not ended yet") }
    session = nil; sessionWorkoutID = nil
    metadata["discardRequested"] = true
    metadata["healthKitState"] = "discarded"; metadata.removeValue(forKey: "healthKitUUID")
    metadata["endedAt"] = WorkoutCoding.timestamp(endDate); metadata["phase"] = "completed"
    phase = "finished"; issue = nil; metrics = [:]
    try commitOwner(phase: "completed", at: endDate, completing: pendingOwnerCommand?.action == "discard" ? pendingOwnerCommand : nil)
    if let id = workoutID { queueDiscardNotice(id: id) }
    WKInterfaceDevice.current().play(.stop)
  }

  func retryHealthSave(automatic: Bool = false) async {
    if session != nil { if !automatic { await end() }; return }
    guard !isBusy, !recovering, !deletionProbe.inFlight, acceptingBatches == 0, let journal else { return }
    isBusy = true
    let generation = sessionGeneration
    defer { isBusy = false }
    do {
      let candidates = try journal.allMetadata().filter {
        $0["saveToHealth"] as? Bool != false && $0["discardRequested"] as? Bool != true && $0["healthKitState"] as? String != "discarded" &&
        ["completed", "failed", "finishing"].contains(WorkoutOwnerPhase.canonical($0["phase"] as? String ?? "")) &&
          ($0["healthKitState"] as? String != "saved" || $0["cycHealthSamplesIncomplete"] as? Bool == true ||
            $0["healthSamplesIncomplete"] as? Bool == true)
      }
      for candidate in candidates {
        guard let id = candidate["workoutId"] as? String else { continue }
        let workout = try await savedWorkout(id: id)
        guard generation == sessionGeneration, session == nil else { return }
        guard let workout else {
          if !automatic { issue = "Health has not returned this workout. Unlock Watch and check Health permissions; the local ride is retained." }
          continue
        }
        var saved = candidate
        saved["healthKitUUID"] = workout.uuid.uuidString.lowercased(); saved["healthKitState"] = "saved"
        saved["phase"] = "completed"
        saved["endedAt"] = (try journal.control.snapshot(workoutID: id)?.stopCutoff) ?? saved["endedAt"] ?? WorkoutCoding.timestamp(workout.endDate)
        try journal.save(id: id, metadata: saved)
        if id == workoutID { metadata = saved; phase = "finished" }
        await collectFinishedHealth(workout, collectionID: id)
        guard generation == sessionGeneration, session == nil else { return }
        try journal.control.repairTerminalSlot(workoutID: id)
        let command = try journal.control.active(workoutID: id)
        let failure: String? = command != nil && !["start", "stop"].contains(command!.action) ? "Saved workout has no evidence for this pending action" : nil
        try journal.control.observe(workoutID: id, owner: "watch", phase: "completed", at: workout.endDate,
          health: "saved", healthID: workout.uuid.uuidString.lowercased(),
          cutoff: (saved["endedAt"] as? String).flatMap { try? WorkoutCoding.date($0) }, command: command, failure: failure)
        try journal.reconcileOwnerMetadata(id: id)
        if id == workoutID {
          metadata = try journal.metadata(id: id)
          if pendingOwnerCommand?.id == command?.id { pendingOwnerCommand = nil }
        }
        if let command { acknowledge(command.id, id: id, result: try journal.control.result(id: command.id)) }
        queueHistoricalTelemetry(id: id); queueArchive(id: id); sendStatus(id: id)
      }
    } catch { issue = "Health retry: \(error.localizedDescription)" }
  }
  private func savedWorkout(id: String) async throws -> HKWorkout? {
    if let record = try? journal?.archive.metadata(id: id), !record.savesToHealth { return nil }
    return try await withCheckedThrowingContinuation { continuation in
      let predicate = HKQuery.predicateForObjects(withMetadataKey: HKMetadataKeyExternalUUID, allowedValues: [id])
      healthStore.execute(HKSampleQuery(sampleType: .workoutType(), predicate: predicate, limit: 1, sortDescriptors: nil) { _, samples, error in
        if let error { continuation.resume(throwing: error) }
        else { continuation.resume(returning: samples?.first as? HKWorkout) }
      })
    }
  }

  private func startMirroring() {
    guard let session, let id = workoutID else { return }
    let identity = effectIdentity(id)
    session.startMirroringToCompanionDevice { [weak self] success, error in
      Task { @MainActor in
        guard let self, self.isCurrent(identity), self.session === session else { return }
        self.mirroring = success
        if let error { self.logTransportFailure(operation: "mirroring", error: error) }
        self.sendStatus()
      }
    }
  }

  private func startLocations() {
    guard recordGPS, phase == "running" else { gpsLabel = recordGPS ? "GPS paused" : "GPS off"; return }
    switch locationManager.authorizationStatus {
    case .authorizedAlways, .authorizedWhenInUse:
      gpsLabel = "Finding GPS"; locationManager.startUpdatingLocation()
    case .notDetermined: gpsLabel = "Allow location"; locationManager.requestWhenInUseAuthorization()
    default: metadata["gpsOutcome"] = "unavailable"; persistMetadata(); gpsLabel = "GPS denied"; issue = "Allow location in Watch Settings to record the outdoor route."
    }
  }

  @discardableResult
  private func record(kind: String, date: Date, payload: [String: WorkoutJSON], eventId: String = UUID().uuidString,
    synchronize: Bool = true) throws -> WorkoutEvent {
    guard let id = workoutID, let journal else { throw WorkoutDataError.invalid("No durable Watch ride is open.") }
    if payload["logicalTotalID"] == nil, let existing = try journal.store.read({ db in
      try db.rows("SELECT m.*,o.original_timestamp,o.extra FROM collection_memberships m JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND m.event_id=? LIMIT 1", [.text(id), .text(eventId.lowercased())], limit: 1).first.map { try journal.store.decodeEvent($0, db: db).event }
    }) { return existing }
    let ownerElapsed = try journal.elapsed(id: id, date: date)
    let elapsed = payload["action"]?.string == "stop" ? (metadata["stopElapsedSeconds"] as? Double ?? ownerElapsed) :
      payload["action"]?.string == "start" ? 0 : ownerElapsed
    var event = try WorkoutEvent(workoutId: id, kind: kind, source: "watch", timestamp: date,
      elapsedSeconds: elapsed, payload: payload, eventId: eventId)
    if let logical = payload["logicalTotalID"]?.string {
      let result = try WorkoutHealthRevisionJournal.append(event, logicalID: logical, archive: journal.archive)
      event = result.event
      if !result.inserted { return event }
    } else {
      let existed = try journal.contains(id: id, eventID: event.eventId)
      try journal.append(id: id, record: WorkoutCoding.encoder().encode(event))
      if existed { return event }
    }
    metadata["eventCount"] = (metadata["eventCount"] as? Int ?? 0) + 1
    metadata["archiveDirty"] = true
    applyDisplay(event)
    if !["rawQuantity", "rawSeries", "healthInsertionReceipt"].contains(payload["representation"]?.string ?? "") {
      let sequence = try journal.transfer.sequence(id: id, eventID: event.eventId, producer: "watch")
      send(envelope(kind: "events", values: ["events": [event.dictionary], "firstSequence": String(sequence)]))
    }
    return event
  }

  private func persistMetadata() {
    guard let id = workoutID else { return }
    if let builder { metadata["timerSeconds"] = builder.elapsedTime }
    do { try journal?.save(id: id, metadata: metadata) }
    catch { issue = "Local storage: \(error.localizedDescription)" }
  }

  private func applyDisplay(_ event: WorkoutEvent) {
    guard let date = try? event.date else { return }
    for key in ["humanPowerW", "cadenceRpm", "heartRateBpm"] {
      if let value = event.number(key), metrics[key].map({ date >= $0.1 }) ?? true { metrics[key] = (value, date) }
    }
    refreshDistance()
  }


  private func refreshDistance() {
    guard !distanceInFlight, Date().timeIntervalSince(lastDistanceRequest) >= 2,
      let id = workoutID, let journal, let revision = try? journal.archive.revision(id: id),
      distanceSnapshot?.id != id || distanceSnapshot?.revision != revision else { return }
    let identity = effectIdentity(id), store = journal.archive.store
    distanceInFlight = true; lastDistanceRequest = Date()
    Task {
      let snapshot = await Task.detached(priority: .utility) {
        try? WorkoutDistanceStore(store: store).snapshot(id: id, revision: revision)
      }.value
      distanceInFlight = false
      guard isCurrent(identity), let snapshot else { return }
      distanceSnapshot = snapshot
      distanceLabel = snapshot.totalMeters.map { String(format: "%.2f km", $0 / 1000) } ?? "— km"
      distanceSourceLabel = snapshot.info.selected.map {
        if $0.estimated { return "Controller estimate" }
        let source = $0.source.hasPrefix("gps:") ? "GPS" : "Health"
        return $0.partial ? "Partial " + source : source
      } ?? "Distance"
    }
  }

  func power(at date: Date) -> String { bikeReading("humanPowerW", at: date) }
  func cadence(at date: Date) -> String { bikeReading("cadenceRpm", at: date) }
  private func bikeFreshness(_ key: String, at date: Date) -> WatchReadingFreshness {
    WatchReadingFreshness.bikeSample(age: metrics[key].map { date.timeIntervalSince($0.1) }, running: phase == "running")
  }
  private func bikeReading(_ key: String, at date: Date) -> String {
    guard let value = metrics[key]?.0, phase == "paused" || bikeFreshness(key, at: date) != .unavailable else { return "—" }
    return String(format: "%.0f", value)
  }
  func heartRate(at date: Date) -> String { fresh("heartRateBpm", date: date, age: 20) }
  private func fresh(_ key: String, date: Date, age: Double) -> String {
    guard phase == "running", let (value, timestamp) = metrics[key], date.timeIntervalSince(timestamp) <= age,
      date.timeIntervalSince(timestamp) >= -2 else { return "—" }
    return String(format: "%.0f", value)
  }
  func elapsed(at date: Date) -> String {
    let cutoff = (metadata["endedAt"] as? String).flatMap { try? WorkoutCoding.date($0) }
    let seconds = max(0, Int(builder?.elapsedTime(at: cutoff ?? date) ?? metadata["timerSeconds"] as? Double ?? 0))
    return seconds >= 3600 ? String(format: "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
      : String(format: "%02d:%02d", seconds / 60, seconds % 60)
  }

  private func envelope(kind: String, values: [String: Any] = [:]) -> [String: Any] {
    var value: [String: Any] = ["schemaVersion": 1, "kind": kind, "messageId": UUID().uuidString.lowercased()]
    if let workoutID { value["workoutId"] = workoutID }
    value.merge(values) { _, new in new }; return value
  }

  private func send(_ value: [String: Any]) {
    guard let data = try? JSONSerialization.data(withJSONObject: value), data.count <= 60_000 else { return }
    if let session, mirroring, mirrorBudget.reserve(bytes: data.count, now: ProcessInfo.processInfo.systemUptime) {
      session.sendToRemoteWorkoutSession(data: data) { [weak self] success, _ in
        if !success { Task { @MainActor in self?.sendConnectivity(data) } }
      }
    } else { sendConnectivity(data) }
  }

  private func sendConnectivity(_ data: Data) {
    guard WCSession.default.activationState == .activated, WCSession.default.isReachable else { return }
    WCSession.default.sendMessageData(data, replyHandler: nil, errorHandler: nil)
  }

  private func sendStatus(id requestedID: String? = nil) {
    if let requestedID, requestedID != workoutID {
      guard let snapshot = try? journal?.control.snapshot(workoutID: requestedID) else { return }
      var values: [String: Any] = ["workoutId": requestedID, "phase": snapshot.phase, "ownerSnapshot": WorkoutCoding.dictionary(snapshot)]
      if let saved = try? journal?.metadata(id: requestedID) {
        for key in ["startedAt", "endedAt", "indoor", "saveToHealth", "recordGPS", "timerSeconds"] { values[key] = saved[key] }
        if let start = (saved["startedAt"] as? String).flatMap({ try? WorkoutCoding.date($0) }) {
          values["elapsedSeconds"] = WorkoutOwnerTiming.elapsed(start: start,
            end: (saved["endedAt"] as? String).flatMap { try? WorkoutCoding.date($0) }, reported: saved["stopElapsedSeconds"] as? Double, now: Date())
        }
      }
      send(envelope(kind: "status", values: values)); return
    }
    var status: [String: Any] = ["phase": phase == "finished" ? "completed" : phase,
      "healthKitState": metadata["healthKitState"] ?? "pending", "watchReachable": true,
      "timestamp": WorkoutCoding.timestamp(Date()),
      "phaseTimestamp": metadata["phaseTimestamp"] ?? WorkoutCoding.timestamp(Date()),
      "timerSeconds": builder?.elapsedTime ?? metadata["timerSeconds"] ?? 0,
      "gpsStatus": gpsLabel]
    if let start = (metadata["startedAt"] as? String).flatMap({ try? WorkoutCoding.date($0) }) {
      let end = (metadata["endedAt"] as? String).flatMap({ try? WorkoutCoding.date($0) })
      let reported = metadata["stopElapsedSeconds"] as? Double ?? (end == nil ? (metadata["workoutMonotonicOrigin"] as? Double).map { max(0, ProcessInfo.processInfo.systemUptime - $0) } : nil)
      status["elapsedSeconds"] = WorkoutOwnerTiming.elapsed(start: start, end: end, reported: reported, now: Date())
    }
    for key in ["startedAt", "endedAt", "healthKitUUID", "indoor", "saveToHealth", "recordGPS", "eBike", "error"] { status[key] = metadata[key] }
    if let id = workoutID, let snapshot = try? journal?.control.snapshot(workoutID: id) {
      status["ownerSnapshot"] = WorkoutCoding.dictionary(snapshot)
      status["phase"] = snapshot.phase
      status["healthKitState"] = snapshot.healthOutcome
    }
    let value = envelope(kind: "status", values: status)
    send(value)
    if WCSession.default.activationState == .activated {
      try? WCSession.default.updateApplicationContext(["workoutStatus": value])
    }
  }

  private func receiveOwnerQuery(_ query: WorkoutOwnerQuery) async throws {
    guard let journal else { return }
    try journal.control.repairTerminalSlot(workoutID: query.workoutID)
    let knownOwner = try journal.control.snapshot(workoutID: query.workoutID)
    let probeID = UUID()
    if session == nil, knownOwner.map({ WorkoutOwnerPhase.terminal($0.phase) }) != true { await recover(expectedID: query.workoutID, probeID: probeID) }
    if nativeWorkoutID == query.workoutID, let session { _ = await reconcileRecoveredCommand(session) }
    if let active = try journal.control.active(workoutID: query.workoutID) {
      let result = try WorkoutOwnerAdmission.query(active, control: journal.control, nativeWorkoutID: nativeWorkoutID)
      if result.isTerminal {
        acknowledge(active.id, id: query.workoutID, result: result)
        if pendingOwnerCommand?.id == active.id { pendingOwnerCommand = nil }
      }
    }
    if (try? journal.metadata(id: query.workoutID)) != nil {
      try journal.reconcileOwnerMetadata(id: query.workoutID)
      if workoutID == query.workoutID { metadata = try journal.metadata(id: query.workoutID) }
    }
    var commandResult: WorkoutCommandResult?
    if let commandID = query.pendingCommandID, let command = try journal.control.command(id: commandID) {
      guard command.workoutID == query.workoutID else { throw WorkoutDataError.invalid("Owner query command belongs to another ride") }
      commandResult = try cancelUnconfirmedStop(command, probeID: probeID) ?? WorkoutOwnerAdmission.query(command, control: journal.control, nativeWorkoutID: nativeWorkoutID)
      if commandResult?.isTerminal == true, pendingOwnerCommand?.id == command.id { pendingOwnerCommand = nil }
    }
    let snapshot = try journal.control.snapshot(workoutID: query.workoutID)
    var reply: [String: Any] = ["schemaVersion": 1, "kind": "ownerReply", "workoutId": query.workoutID,
      "messageId": UUID().uuidString.lowercased(), "queryId": query.id,
      "ownerState": nativeWorkoutID == query.workoutID || snapshot.map({ WorkoutOwnerPhase.terminal($0.phase) }) == true ? "known" : "unresolved"]
    if let snapshot { reply["ownerSnapshot"] = WorkoutCoding.dictionary(snapshot); reply["phase"] = snapshot.phase }
    if let commandResult { reply["commandResult"] = WorkoutCoding.dictionary(commandResult) }
    if let saved = try? journal.metadata(id: query.workoutID) {
      for key in ["startedAt", "endedAt", "indoor", "saveToHealth", "recordGPS", "timerSeconds", "stopElapsedSeconds"] { reply[key] = saved[key] }
      if let start = (saved["startedAt"] as? String).flatMap({ try? WorkoutCoding.date($0) }) {
        reply["elapsedSeconds"] = WorkoutOwnerTiming.elapsed(start: start,
          end: (saved["endedAt"] as? String).flatMap { try? WorkoutCoding.date($0) }, reported: saved["stopElapsedSeconds"] as? Double, now: Date())
      }
    }
    if nativeWorkoutID == query.workoutID, let builder {
      reply["timerSeconds"] = builder.elapsedTime
      reply["timestamp"] = WorkoutCoding.timestamp(Date())
    }
    send(reply)
  }

  private func cancelUnconfirmedStop(_ command: WorkoutCommand, probeID: UUID) throws -> WorkoutCommandResult? {
    guard let journal else { return nil }
    let confirmed = nativeAbsenceEvidence?.matches(workoutID: command.workoutID, generation: sessionGeneration, probeID: probeID) == true
    guard let result = try WorkoutOwnerAdmission.cancelUnconfirmed(command, control: journal.control,
      nativeAbsenceConfirmed: confirmed, effectInFlight: isBusy || recovering || isFinishing || applyingCommand) else { return nil }
    if let pending = pendingOwnerCommand, pending.workoutID == command.workoutID, try journal.control.result(id: pending.id)?.isTerminal == true {
      pendingOwnerCommand = nil
    }
    _ = try presentCancelledStart(id: command.workoutID)
    return result
  }

  private func presentCancelledStart(id: String) throws -> Bool {
    guard workoutID == id, session == nil, recoveredForeignWorkoutID == nil, let journal,
      let reason = try journal.control.cancelledWithoutOwner(workoutID: id),
      let saved = try journal.reconcileCancelledStart(id: id) else { return false }
    metadata = saved; phase = "failed"
    if WatchWorkoutJournal.isResolvedCancellationIssue(issue, reason: reason) { issue = nil }
    return true
  }

  private func deletionBusy(_ id: String) -> Bool {
    isBusy || recovering || isFinishing || applyingCommand || pendingInserts > 0 || acceptingBatches > 0 ||
      archiveWork.active == id || syncWork.active == id || historicalWork.active == id || deletionProbe.inFlight
  }
  private func receiveDeletion(_ request: WorkoutDeletionRequest) async {
    guard let journal else { return }
    do {
      if try journal.store.isWorkoutDeleted(id: request.workoutID) {
        archiveWork.removePending(request.workoutID); syncWork.removePending(request.workoutID); historicalWork.removePending(request.workoutID)
        send(request.acknowledgement(deleted: true)); advanceDeletionCleanup(); return
      }
      guard !deletionBusy(request.workoutID) else {
        send(request.acknowledgement(deleted: false, reason: "Watch work is still in progress.")); return
      }
      var observedID = nativeWorkoutID
      if session == nil {
        guard deletionProbe.begin() else { send(request.acknowledgement(deleted: false)); return }
        defer { deletionProbe.finish() }
        let generation = sessionGeneration
        unresolvedNativeOwner = true
        let observed = try await healthStore.recoverActiveWorkoutSession()
        guard generation == sessionGeneration, session == nil else {
          send(request.acknowledgement(deleted: false, reason: "Watch ownership changed during deletion check.")); return
        }
        observedID = nil; recoveredForeignWorkoutID = nil; unresolvedNativeOwner = false
        if let observed {
          guard let rawID = observed.associatedWorkoutBuilder().metadata[HKMetadataKeyExternalUUID] as? String,
            let stableID = try? WorkoutCoding.id(rawID) else {
            unresolvedNativeOwner = true
            send(request.acknowledgement(deleted: false, reason: "The active native owner could not be identified.")); return
          }
          observedID = stableID; recoveredForeignWorkoutID = stableID
        }
      }
      guard WorkoutDeletionPolicy.watch(targetID: request.workoutID, nativeID: observedID, probeResolved: true, busy: deletionBusy(request.workoutID)) else {
        send(request.acknowledgement(deleted: false, reason: "Finish the active Watch ride before deleting it.")); return
      }
      _ = try journal.store.markWorkoutDeleted(id: request.workoutID, messageID: request.messageID)
      archiveWork.removePending(request.workoutID); syncWork.removePending(request.workoutID); historicalWork.removePending(request.workoutID)
      for transfer in WCSession.default.outstandingFileTransfers where transfer.file.metadata?["workoutId"] as? String == request.workoutID { transfer.cancel() }
      for transfer in WCSession.default.outstandingUserInfoTransfers where transfer.userInfo["workoutId"] as? String == request.workoutID { transfer.cancel() }
      if syncIssue?.workoutID == request.workoutID { syncIssue = nil }
      if workoutID == request.workoutID, session == nil {
        sessionGeneration = UUID(); metadata = [:]; phase = "ready"; pendingOwnerCommand = nil
        nativeAbsenceEvidence = nil; metrics = [:]; lapCount = 0; issue = nil; distanceLabel = "— km"
      }
      advanceDeletionCleanup()
      send(request.acknowledgement(deleted: true))
    } catch {
      send(request.acknowledgement(deleted: false, reason: error.localizedDescription))
    }
  }
  private func advanceDeletionCleanup() {
    guard let journal, deletionCleanupID == nil else { return }
    do {
      let page = try journal.store.deletionPage(after: deletionCursor, limit: 8)
      deletionCursor = page.last?.id ?? ""
      if let pending = page.first(where: { !$0.cleanupComplete }) {
        deletionCleanupID = pending.id; cleanupDeletedRide(pending.id)
      }
    } catch { issue = "Deleted ride cleanup will retry: \(error.localizedDescription)" }
  }
  private func cleanupDeletedRide(_ id: String) {
    guard let journal else { deletionCleanupID = nil; return }
    Task {
      do {
        let finished = try await Task.detached(priority: .utility) { try journal.archive.cleanupDeletedWorkoutPage(id: id) }.value
        if finished { deletionCleanupID = nil }
        else {
          try await Task.sleep(nanoseconds: 10_000_000)
          cleanupDeletedRide(id)
        }
      } catch { deletionCleanupID = nil; issue = "Deleted ride cleanup will retry: \(error.localizedDescription)" }
    }
  }

  private func receive(_ value: [String: Any]) async {
    guard value["schemaVersion"] as? Int == 1, let kind = value["kind"] as? String, let journal else { return }
    guard let rawID = value["workoutId"] as? String, let id = try? WorkoutCoding.id(rawID) else { return }
    do {
      if kind == "deleteWorkout" { await receiveDeletion(try WorkoutDeletionRequest(value)); return }
      if try journal.store.isWorkoutDeleted(id: id) {
        if kind == "command", let command = try? WorkoutCommand.decode(value) {
          acknowledge(command.id, id: id, result: WorkoutCommandResult(commandID: command.id, outcome: "rejected", reason: "This ride was deleted from Power Log."))
        }
        return
      }
      if try journal.control.snapshot(workoutID: id)?.healthOutcome == "discarded" { queueDiscardNotice(id: id); return }
      if kind == "chunkAck", let identity = value["chunkIdentity"] as? String,
        let producer = value["producer"] as? String, let sequence = value["lastSequence"] as? String,
        let last = Int64(sequence), let hash = value["contentHash"] as? String {
        if try WorkoutChunkSender(archive: journal.archive).acknowledge(id: id, producer: producer,
          identity: identity, lastSequence: last, contentHash: hash) {
          clearSyncIssue(id: id, operation: identity)
          for item in WCSession.default.outstandingFileTransfers where item.file.metadata?["chunkIdentity"] as? String == identity { item.cancel() }
          try journal.reconcileChunks(referenced: referencedChunks())
          queueSync(id: id); queueArchive(id: id)
        }
        return
      }
      if kind == "sealAck", let revision = value["sealRevision"] as? String, let sealRevision = Int64(revision),
        let saved = try journal.acknowledgeSeal(id: id, revision: sealRevision) {
        if id == workoutID { metadata = saved }
        for item in WCSession.default.outstandingUserInfoTransfers where item.userInfo["workoutId"] as? String == id && item.userInfo["kind"] as? String == "seal" { item.cancel() }
        clearSyncIssue(id: id, operation: "seal"); return
      }
      if kind == "sourceSeal", let raw = value["sourceSeal"] as? [String: Any] {
        let source = try JSONDecoder().decode(WorkoutSourceSeal.self, from: JSONSerialization.data(withJSONObject: raw))
        guard source.producer == "cyc" else { return }
        try journal.transfer.saveSource(id: id, source: source)
        if id == workoutID { metadata["archiveDirty"] = true; persistMetadata() }
        queueArchive(id: id)
        acknowledge(value["messageId"] as? String ?? "", id: id); return
      }
      if kind == "ownerQuery" { try await receiveOwnerQuery(WorkoutOwnerQuery.decode(value)); return }
      if kind == "command" {
        let command = try WorkoutCommand.decode(value)
        if command.action == "stop", session == nil, try journal.control.snapshot(workoutID: id) == nil {
          let probeID = UUID()
          await recover(expectedID: id, probeID: probeID)
          if let result = try cancelUnconfirmedStop(command, probeID: probeID) {
            acknowledge(command.id, id: id, result: result); sendStatus(id: id); return
          }
        }
        let preparation = try WorkoutOwnerAdmission.prepare(command, control: journal.control,
          nativeWorkoutID: nativeWorkoutID, readyToStart: canStart && pendingInserts == 0 && acceptingBatches == 0,
          recovering: recovering)
        acknowledge(command.id, id: id, result: preparation.result)
        if preparation.result.isTerminal {
          if pendingOwnerCommand?.id == command.id { pendingOwnerCommand = nil }
          sendStatus(id: id); return
        }
        if command.action == "status" { sendStatus(id: id); return }
        guard !applyingCommand else { return }
        if preparation.reconcile {
          if nativeWorkoutID == id, let session {
            pendingOwnerCommand = command
            _ = await reconcileRecoveredCommand(session)
          } else if command.action == "start" {
            await recover(expectedID: id)
            sendStatus(id: id)
          }
          return
        }
        guard preparation.execute else { return }
        applyingCommand = true
        defer { applyingCommand = false }
        if command.action == "start" {
          if Date().timeIntervalSince(try WorkoutCoding.date(command.requestedAt)) > 45 {
            let result = try journal.control.settleWithoutEffect(command, reason: "Start request expired before native application")
            acknowledge(command.id, id: id, result: result); sendStatus(id: id); return
          }
          await start(indoor: command.options["indoor"] == .bool(true), eBike: command.options["eBike"] != .bool(false), id: id, command: command, saveToHealth: command.options["saveToHealth"] != .bool(false), recordGPS: command.options["recordGPS"].map { $0 == .bool(true) })
          return
        }
        try WorkoutEffectIdentity.require(command: command, workoutID: workoutID ?? "", nativeWorkoutID: nativeWorkoutID)
        guard nativeWorkoutID == id else { throw WorkoutDataError.invalid("The matching native owner is unavailable") }
        pendingOwnerCommand = command
        switch command.action {
        case "pause": session?.pause()
        case "resume": session?.resume()
        case "lap": try await performLap(command)
        case "stop", "discard": await end(at: try WorkoutCoding.date(command.requestedAt))
        default: break
        }
        return
      }
      if kind == "events", let values = value["events"] as? [[String: Any]], values.count <= 128 {
        let events = try values.map(WorkoutEvent.init(dictionary:))
        guard events.allSatisfy({ $0.workoutId == id && $0.source == "cyc" && $0.kind == "telemetry" }),
          let firstText = value["firstSequence"] as? String, let first = Int64(firstText) else { throw WorkoutDataError.invalid("Missing CYC archival sequence") }
        try await journal.detached { try journal.acceptTelemetry(events, firstSequence: first) }
        acknowledge(value["messageId"] as? String ?? "", id: id)
        if id != workoutID {
          queueHistoricalTelemetry(id: id); queueArchive(id: id)
          return
        }
        acceptingBatches += 1
        let identity = effectIdentity(id)
        defer { acceptingBatches -= 1; if isCurrent(identity) { persistMetadata() }; queueArchive(id: id) }
        for event in events { guard isCurrent(identity) else { queueHistoricalTelemetry(id: id); return }; try await acceptTelemetry(event) }
        if builder == nil, let workout = try await savedWorkout(id: id) {
          guard isCurrent(identity) else { queueHistoricalTelemetry(id: id); return }
          await collectFinishedHealth(workout, collectionID: id)
          guard isCurrent(identity) else { return }
          try commitOwner(phase: "completed", at: workout.endDate)
        }
      }
    } catch {
      issue = "Workout processing: \(error.localizedDescription)"
      if kind == "command", let messageID = value["messageId"] as? String,
        let command = pendingOwnerCommand, command.id == messageID, command.workoutID == id, command.workoutID == workoutID {
        do { try commitOwner(phase: metadata["phase"] as? String ?? phase, at: Date(), completing: command, failure: error.localizedDescription) }
        catch { issue = "Local command result is pending: \(error.localizedDescription)" }
      }
    }
  }

  private func acknowledge(_ messageID: String, id: String? = nil, result: WorkoutCommandResult? = nil) {
    var value: [String: Any] = ["acknowledgedMessageId": messageID]
    if let id { value["workoutId"] = id }
    if let result { value["commandResult"] = WorkoutCoding.dictionary(result) }
    send(envelope(kind: "ack", values: value))
  }

  private func acceptTelemetry(_ event: WorkoutEvent) async throws {
    guard let id = workoutID, event.workoutId == id, let journal else { return }
    let identity = effectIdentity(id)
    let encoded = try WorkoutCoding.encoder().encode(event)
    let appended = try await journal.detached { () -> Bool in
      if try journal.contains(id: id, eventID: event.eventId) { return false }
      try journal.append(id: id, record: encoded, synchronize: false); return true
    }
    guard isCurrent(identity) else { return }
    if appended {
      metadata["eventCount"] = (metadata["eventCount"] as? Int ?? 0) + 1
      metadata["archiveDirty"] = true
    }
    applyDisplay(event)
    if !saveToHealth { return }
    if try journal.contains(id: id, eventID: WatchHealthMetrics.stableEventID("inserted:" + event.eventId)) { return }
    if insertingTelemetry.contains(event.eventId) { return }
    let insertion = WorkoutHealthInsertionJournal(archive: journal.archive)
    try insertion.prepare([event])
    if try !WorkoutHealthEligibility.permits(event, archive: journal.archive) {
      try insertion.record([event], outcome: "excluded")
      return
    }
    let date = try event.date
    guard let startString = metadata["startedAt"] as? String,
      let start = try? WorkoutCoding.date(startString), date >= start,
      date.timeIntervalSinceNow <= 2,
      let power = event.number("humanPowerW"), let cadence = event.number("cadenceRpm"),
      (0...5000).contains(power), (0...300).contains(cadence) else { return }
    if let endString = metadata["endedAt"] as? String, let end = try? WorkoutCoding.date(endString), date > end { return }
    let pairs: [(HKQuantityTypeIdentifier, HKUnit, Double)] = [
      (.cyclingPower, .watt(), power), (.cyclingCadence, .count().unitDivided(by: .minute()), cadence)]
    let samples = pairs.compactMap { identifier, unit, value -> HKQuantitySample? in
      guard let type = HKQuantityType.quantityType(forIdentifier: identifier),
        healthStore.authorizationStatus(for: type) == .sharingAuthorized else { return nil }
      return HKQuantitySample(type: type, quantity: HKQuantity(unit: unit, doubleValue: value), start: date, end: date,
        metadata: [HKMetadataKeySyncIdentifier: "power-log-\(event.eventId)-\(identifier.rawValue)",
          HKMetadataKeySyncVersion: 1, "PowerLogSource": "CYC rider telemetry"])
    }
    if samples.count < 2 {
      metadata["cycHealthSamplesIncomplete"] = true
      metadata["cycHealthOutcome"] = "unavailable"
      metadata["error"] = "Health write access for CYC power or cadence is unavailable. Raw rider samples remain recorded."
      persistMetadata()
    }
    guard !samples.isEmpty else { try insertion.record([event], outcome: "unavailable"); return }
    pendingInserts += 1
    insertingTelemetry.insert(event.eventId)
    defer { pendingInserts -= 1; insertingTelemetry.remove(event.eventId) }
    do {
      try WorkoutRecordingPolicy.requireHealthWrite(id: id, archive: journal.archive)
      if let builder, builder.endDate == nil { try await builder.addSamples(samples) }
      else if let workout = try await savedWorkout(id: id) {
        guard isCurrent(identity) else { return }
        // Late samples must attach to the saved workout; a new builder would create a duplicate.
        try WorkoutRecordingPolicy.requireHealthWrite(id: id, archive: journal.archive)
        try await healthStore.addSamples(samples, to: workout)
      } else {
        metadata["cycHealthSamplesIncomplete"] = true; persistMetadata()
        return // Retained for retry after HealthKit is unlocked or the workout has been saved.
      }
      try insertion.record([event], outcome: samples.count == 2 ? "applied" : "unavailable")
      guard isCurrent(identity) else { return }
      if samples.count == 2 {
        try record(kind: "health", date: Date(), payload: ["representation": .string("healthInsertionReceipt"),
          "insertedTelemetryEventId": .string(event.eventId)],
          eventId: WatchHealthMetrics.stableEventID("inserted:" + event.eventId))
      }
      if self.builder == nil { persistMetadata() }
    }
    catch {
      guard isCurrent(identity) else { return }
      metadata["cycHealthSamplesIncomplete"] = true
      metadata["cycHealthOutcome"] = "pending"
      metadata["error"] = "Some CYC samples could not be added to Health; the raw ride retains them."
      persistMetadata()
      throw error
    }
  }

  func retrySync() {
    guard WCSession.default.activationState == .activated, let journal else { return }
    do {
      try journal.reconcileChunks(referenced: referencedChunks())
      if let id = workoutID {
        if !saveToHealth, session == nil { queueLocalSensorRecovery(id: id) }
        if rideDiscarded {
          queueDiscardNotice(id: id)
        } else { queueArchive(id: id) }
      }
      let page = try journal.metadataPage(after: syncCatalogCursor)
      for entry in page where entry.value["endedAt"] != nil && entry.id != workoutID {
        if entry.value["healthKitState"] as? String == "discarded" { queueDiscardNotice(id: entry.id); continue }
        if entry.value["saveToHealth"] as? Bool == false { queueLocalSensorRecovery(id: entry.id) }
        if entry.id != workoutID, (entry.value["cycHealthSamplesIncomplete"] as? Bool == true || entry.value["healthSamplesIncomplete"] as? Bool == true) { queueHistoricalTelemetry(id: entry.id) }
        queueArchive(id: entry.id)
      }
      syncCatalogCursor = page.count == 8 ? page.last!.id : ""
      clearSyncIssue(id: workoutID ?? "", operation: "discovery")
    } catch { reportSyncFailure(id: workoutID ?? "", operation: "discovery", error: error) }
  }

  private func queueDiscardNotice(id: String) {
    guard WCSession.default.activationState == .activated,
      let snapshot = try? journal?.control.snapshot(workoutID: id), snapshot.healthOutcome == "discarded" else { return }
    let packet = envelope(kind: "status", values: ["workoutId": id, "phase": "completed", "ownerSnapshot": WorkoutCoding.dictionary(snapshot)])
    send(packet)
    guard !WCSession.default.outstandingUserInfoTransfers.contains(where: { $0.userInfo["kind"] as? String == "discarded" && $0.userInfo["workoutId"] as? String == id }),
      let data = try? JSONSerialization.data(withJSONObject: packet) else { return }
    WCSession.default.transferUserInfo(["kind": "discarded", "workoutId": id, "data": data])
  }

  private func referencedChunks() -> Set<String> {
    Set(WCSession.default.outstandingFileTransfers.compactMap { $0.file.metadata?["chunkIdentity"] as? String })
  }

  private func queueSync(id: String) {
    if (try? journal?.metadata(id: id))?["discardRequested"] as? Bool == true { return }
    guard let journal, WCSession.default.activationState == .activated,
      (try? journal.store.isWorkoutDeleted(id: id)) == false, syncWork.request(id) else { return }
    Task {
      do {
        let prepared = try await Task.detached(priority: .utility) { () -> (WorkoutChunk, Data?, [String: Any])? in
          let sender = WorkoutChunkSender(archive: journal.archive)
          guard let chunk = try sender.prepare(id: id) else { return nil }
          let record = try journal.archive.metadata(id: id)
          let packet = try WorkoutChunkWire.encode(chunk: chunk, startedAt: record.startedAt, indoor: record.indoor, saveToHealth: record.savesToHealth, recordGPS: record.recordsGPS)
          return (chunk, packet, WorkoutChunkWire.metadata(chunk: chunk, startedAt: record.startedAt, indoor: record.indoor, saveToHealth: record.savesToHealth, recordGPS: record.recordsGPS))
        }.value
        clearSyncIssue(id: id, operation: "chunk")
        if let (chunk, packet, transferMetadata) = prepared {
          let sender = WorkoutChunkSender(archive: journal.archive)
          if try sender.pending(id: id) == chunk.manifest, WCSession.default.activationState == .activated {
            let attempt = try sender.attempt(id: id, reachable: WCSession.default.isReachable,
              liveFits: packet != nil, hasOutstandingFile: referencedChunks().contains(chunk.manifest.identity),
              now: Date().timeIntervalSince1970)
            if attempt.sendLive, let packet {
              WCSession.default.sendMessageData(packet, replyHandler: nil) { [weak self] error in
                Task { @MainActor in
                  guard let self, (try? sender.pending(id: id)) == chunk.manifest else { return }
                  self.syncLog.debug("Live chunk awaiting retry: code=\((error as NSError).code)")
                }
              }
            }
            if attempt.sendFile {
              guard try makeStagingRoom(for: chunk) else {
                try sender.deferFile(id: id)
                if let next = syncWork.finish(id) { queueSync(id: next) }
                return
              }
              let file = try journal.stage(chunk)
              let exists = FileManager.default.isReadableFile(atPath: file.path)
              syncLog.debug("Queue file: bytes=\(chunk.data.count) records=\(chunk.manifest.count) readable=\(exists)")
              WCSession.default.transferFile(file, metadata: transferMetadata)
            }
          }
        }
      } catch PowerLogStorageError.deleted { }
      catch {
        let identity = (try? WorkoutChunkSender(archive: journal.archive).pending(id: id))?.identity ?? "chunk"
        reportSyncFailure(id: id, operation: identity, error: error)
      }
      if let next = syncWork.finish(id) { queueSync(id: next) }
    }
  }

  private func makeStagingRoom(for chunk: WorkoutChunk) throws -> Bool {
    guard let journal else { return false }
    return try journal.reserveStaging(for: chunk, priorityID: prioritySyncID,
      referenced: { self.referencedChunks() }, nativeCount: { WCSession.default.outstandingFileTransfers.count },
      cancel: { identity in
        for native in WCSession.default.outstandingFileTransfers where native.file.metadata?["chunkIdentity"] as? String == identity { native.cancel() }
      })
  }

  private func dispatchSeal(id: String, seal: WorkoutSeal, metadata item: [String: Any]) throws {
    guard let journal, WCSession.default.activationState == .activated else { return }
    let submission = WorkoutSealSubmissionJournal(archive: journal.archive)
    guard try submission.shouldSubmit(id: id, revision: seal.sealRevision, now: Date().timeIntervalSince1970) else { return }
    let revision = String(seal.sealRevision)
    for native in WCSession.default.outstandingUserInfoTransfers where native.userInfo["kind"] as? String == "seal" &&
      native.userInfo["workoutId"] as? String == id && native.userInfo["sealRevision"] as? String != revision { native.cancel() }
    guard !WCSession.default.outstandingUserInfoTransfers.contains(where: {
      $0.userInfo["kind"] as? String == "seal" && $0.userInfo["workoutId"] as? String == id && $0.userInfo["sealRevision"] as? String == revision
    }) else { return }
    let priority = id == prioritySyncID
    let limit = priority ? 8 : 7
    if priority, WCSession.default.outstandingUserInfoTransfers.count >= limit {
      WCSession.default.outstandingUserInfoTransfers.first(where: {
        $0.userInfo["kind"] as? String == "seal" && $0.userInfo["workoutId"] as? String != id
      })?.cancel()
    }
    guard WCSession.default.outstandingUserInfoTransfers.count < limit else { return }
    var values: [String: Any] = ["workoutId": id, "seal": WorkoutCoding.dictionary(seal),
      "startedAt": item["startedAt"] ?? seal.stopCutoff, "indoor": item["indoor"] ?? false,
      "saveToHealth": seal.saveToHealth ?? true, "recordGPS": seal.recordGPS ?? !(item["indoor"] as? Bool ?? false)]
    if let active = item["timerSeconds"] as? Double { values["timerSeconds"] = active }
    let packet = envelope(kind: "seal", values: values)
    let data = try JSONSerialization.data(withJSONObject: packet, options: [.sortedKeys])
    guard data.count <= 16_384 else { throw WorkoutDataError.invalid("Final ride description exceeds its transfer bound") }
    try submission.recordSubmission(id: id, revision: seal.sealRevision, now: Date().timeIntervalSince1970)
    send(packet)
    WCSession.default.transferUserInfo(["kind": "seal", "workoutId": id, "sealRevision": revision, "data": data])
  }

  private func queueLocalSensorRecovery(id: String) {
    guard let journal, !recovering, !unresolvedNativeOwner, recoveredForeignWorkoutID != id,
      id != workoutID || session == nil,
      let item = try? journal.metadata(id: id), item["saveToHealth"] as? Bool == false,
      item["discardRequested"] as? Bool != true,
      let cutoff = (item["endedAt"] as? String).flatMap({ try? WorkoutCoding.date($0) }) else { return }
    if item["finalLocalSensorsExtracted"] as? Bool == true, item["localRecorderEnded"] as? Bool == true,
      (try? journal.control.snapshot(workoutID: id)?.phase) == "completed", (try? journal.control.active(workoutID: id)) == nil { return }
    guard historicalWork.request(id) else { return }
    Task {
      do {
        try await collectLocalSensorOriginals(id: id, cutoff: cutoff)
        try journal.store.transaction(priority: .capture) { _ in
          let command = try journal.control.active(workoutID: id)
          let failure = command != nil && command?.action != "stop" ? "Recorder ended before this action was confirmed" : nil
          _ = try journal.control.observe(workoutID: id, owner: "watch", phase: "completed", at: cutoff,
            health: "notRequested", cutoff: cutoff, command: command, failure: failure)
          var finished = try journal.metadata(id: id)
          finished["localRecorderEnded"] = true
          try journal.save(id: id, metadata: finished)
          try journal.reconcileOwnerMetadata(id: id)
        }
        if id == workoutID { metadata = try journal.metadata(id: id); phase = "finished"; pendingOwnerCommand = nil }
        queueArchive(id: id); sendStatus(id: id)
      } catch { reportSyncFailure(id: id, operation: "local sensor extraction", error: error) }
      if let next = historicalWork.finish(id) { queueHistoricalTelemetry(id: next) }
    }
  }

  /// Background history work never swaps the live session or its metadata.
  private func queueHistoricalTelemetry(id: String) {
    if (try? journal?.metadata(id: id))?["discardRequested"] as? Bool == true { return }
    if (try? journal?.archive.metadata(id: id).savesToHealth) == false { queueLocalSensorRecovery(id: id); return }
    guard id != workoutID || session == nil, let journal,
      (try? journal.store.isWorkoutDeleted(id: id)) == false, historicalWork.request(id) else { return }
    Task {
      do {
        guard let workout = try await savedWorkout(id: id) else { throw WorkoutDataError.invalid("Original saved Health workout is not accessible yet") }
        let insertion = WorkoutHealthInsertionJournal(archive: journal.archive)
        let events = try await Task.detached(priority: .utility) { () -> [WorkoutEvent] in
          _ = try insertion.outcome(id: id); return try insertion.pending(id: id, limit: 32)
        }.value
        let eligible = try events.filter { try WorkoutHealthEligibility.permits($0, archive: journal.archive) }
        let excluded = events.filter { event in !eligible.contains(where: { $0.eventId == event.eventId }) }
        if !excluded.isEmpty { try insertion.prepare(excluded); try insertion.record(excluded, outcome: "excluded") }
        var samples: [HKQuantitySample] = []
        for event in eligible {
          let date = try event.date
          for (identifier, unit, value) in [(HKQuantityTypeIdentifier.cyclingPower, HKUnit.watt(), event.number("humanPowerW")),
            (.cyclingCadence, HKUnit.count().unitDivided(by: .minute()), event.number("cadenceRpm"))] {
            guard let value, value.isFinite, value >= 0, let type = HKQuantityType.quantityType(forIdentifier: identifier),
              healthStore.authorizationStatus(for: type) == .sharingAuthorized else { continue }
            samples.append(HKQuantitySample(type: type, quantity: HKQuantity(unit: unit, doubleValue: value), start: date, end: date,
              metadata: [HKMetadataKeySyncIdentifier: "power-log-\(event.eventId)-\(identifier.rawValue)", HKMetadataKeySyncVersion: 1,
                "PowerLogSource": "CYC rider telemetry"]))
          }
        }
        let complete = samples.count == eligible.count * 2
        if !eligible.isEmpty {
          let _: Void = try await withCheckedThrowingContinuation { continuation in
            insertion.perform(eligible, operation: { done in
              Task { @MainActor in
                do {
                  try journal.store.requireWorkoutAvailable(id: id)
                  try WorkoutRecordingPolicy.requireHealthWrite(id: id, archive: journal.archive)
                  if !samples.isEmpty { try await self.healthStore.addSamples(samples, to: workout) }
                  done(.success(complete ? "applied" : "unavailable"))
                } catch { done(.failure(error)) }
              }
            }, completion: { continuation.resume(with: $0) })
          }
        }
        if complete && !eligible.isEmpty {
          let date = Date()
          let receipts = try eligible.compactMap { event -> WorkoutEvent? in
            let receiptID = WatchHealthMetrics.stableEventID("inserted:" + event.eventId)
            if try journal.archive.hasEvent(id: id, eventID: receiptID) { return nil }
            return try WorkoutEvent(workoutId: id, kind: "health", source: "watch", timestamp: date,
              elapsedSeconds: journal.elapsed(id: id, date: date), payload: ["representation": .string("healthInsertionReceipt"),
                "insertedTelemetryEventId": .string(event.eventId)], eventId: receiptID)
          }
          if !receipts.isEmpty { _ = try journal.archive.appendBatch(receipts) }
        }
        var outcome = try await Task.detached(priority: .utility) { try insertion.outcome(id: id) }.value
        if outcome != "pending" {
          await collectFinishedHealth(workout, collectionID: id)
          outcome = try await Task.detached(priority: .utility) { try insertion.outcome(id: id) }.value
        }
        var item = try journal.metadata(id: id)
        item["cycHealthSamplesIncomplete"] = outcome == "pending"; item["cycHealthOutcome"] = outcome
        item["healthKitState"] = "saved"; item["healthKitUUID"] = workout.uuid.uuidString.lowercased(); item["archiveDirty"] = true
        let cutoff = (item["endedAt"] as? String).flatMap { try? WorkoutCoding.date($0) } ?? workout.endDate
        let owner = try journal.control.observe(workoutID: id, owner: "watch", phase: "completed", at: Date(), health: "saved",
          healthID: workout.uuid.uuidString.lowercased(), cutoff: cutoff)
        item["ownerRevision"] = String(owner.ownerRevision)
        try journal.save(id: id, metadata: item)
        if id == workoutID, session == nil { metadata = item }
        if outcome == "pending", !events.isEmpty { _ = historicalWork.request(id) }
        queueArchive(id: id)
      } catch PowerLogStorageError.deleted { }
      catch { issue = "Historical Health insertion remains pending: \(error.localizedDescription)" }
      if let next = historicalWork.finish(id) { queueHistoricalTelemetry(id: next) }
    }
  }

  private func queueArchive(id requestedID: String? = nil) {
    guard let id = requestedID ?? workoutID, let journal else { return }
    if (try? journal.metadata(id: id))?["discardRequested"] as? Bool == true { return }
    queueSync(id: id)
    guard acceptingBatches == 0, (try? journal.store.isWorkoutDeleted(id: id)) == false else { return }
    guard archiveWork.request(id) else { return }
    do {
      let item = id == workoutID ? metadata : try journal.metadata(id: id)
      guard item["endedAt"] != nil else { finishArchiveJob(id); return }
      if item["archiveDirty"] as? Bool != true, let seal = try journal.transfer.currentSeal(id: id),
        item["acknowledgedSealRevision"] as? String == String(seal.sealRevision),
        seal.sources.allSatisfy({ source in
          guard let progress = try? journal.archive.sourceProgress(id: id, producer: source.producer) else { return false }
          return progress.count == source.count && progress.lastSequence == source.lastSequence
        }) { finishArchiveJob(id); return }
      let revision = try journal.archive.metadata(id: id).collectionRevision
      let roster = try journal.transfer.roster(id: id)
      Task {
        do {
          let result = try await Task.detached(priority: .utility) { () -> ([WorkoutSourceSeal], String) in
            var sources: [WorkoutSourceSeal] = []
            for producer in roster {
              var actual = try journal.transfer.sourceSnapshot(id: id, producer: producer).seal
              if producer == "cyc" {
                let declared = try journal.transfer.declaredSource(id: id, producer: producer)
                if declared?.count != actual.count || declared?.digest != actual.digest {
                  actual.outcome = "pending"; actual.reason = "Registered phone input has not sealed"
                }
              }
              sources.append(actual)
            }
            return (sources, try WorkoutHealthInsertionJournal(archive: journal.archive).outcome(id: id))
          }.value
          guard try journal.archive.metadata(id: id).collectionRevision == revision else {
            _ = archiveWork.request(id); finishArchiveJob(id); return
          }
          publishArchive(id: id, sources: result.0, insertionOutcome: result.1)
        } catch PowerLogStorageError.deleted { }
        catch { reportSyncFailure(id: id, operation: "seal", error: error) }
        finishArchiveJob(id)
      }
    } catch { finishArchiveJob(id); reportSyncFailure(id: id, operation: "seal", error: error) }
  }

  private func finishArchiveJob(_ id: String) {
    if let next = archiveWork.finish(id) { queueArchive(id: next) }
  }

  private func publishArchive(id: String, sources: [WorkoutSourceSeal], insertionOutcome: String) {
    guard let journal else { return }
    do {
      var item = id == workoutID ? metadata : try journal.metadata(id: id)
      guard let cutoff = item["endedAt"] as? String else { return }
      let owner = try journal.control.snapshot(workoutID: id)
      let health = item["healthKitState"] as? String ?? "pending"
      let saves = item["saveToHealth"] as? Bool ?? true
      var requirements = ["healthSave": saves ? (health == "saved" ? "sealed" : "pending") : "notRequested",
        "healthExtraction": saves ? (item["healthSamplesIncomplete"] as? Bool == true || item["finalHealthExtracted"] as? Bool != true ? "pending" : "sealed") : "notRequested",
        "cycInsertion": insertionOutcome,
        "gps": (item["recordGPS"] as? Bool ?? !(item["indoor"] as? Bool ?? false)) ? (item["gpsOutcome"] as? String ?? "sealed") : "notRequested"]
      if !saves {
        requirements["localSensors"] = item["finalLocalSensorsExtracted"] as? Bool == true ? "sealed" : "pending"
        requirements["ownerEnded"] = item["localRecorderEnded"] as? Bool == true && owner?.phase == "completed" ? "sealed" : "pending"
      }
      let previous = try journal.transfer.currentSeal(id: id)
      let record = try journal.archive.metadata(id: id)
      let changed = previous == nil || previous!.sources != sources || previous!.requirements != requirements ||
        previous!.ownerRevision != (owner?.ownerRevision ?? 0) || previous!.healthOutcome != health
      if changed {
        let seal = WorkoutSeal(workoutID: id, sealRevision: (previous?.sealRevision ?? 0) + 1,
          collectionRevision: record.collectionRevision ?? 0, ownerRevision: owner?.ownerRevision ?? 0,
          stopCutoff: cutoff, healthOutcome: health, requirements: requirements, sources: sources, stopElapsedSeconds: item["stopElapsedSeconds"] as? Double, saveToHealth: record.savesToHealth, recordGPS: record.recordsGPS)
        item = try journal.publishSeal(seal, metadata: item)
        if id == workoutID { metadata = item }
      } else if let previous, item["sealRevision"] as? String != String(previous.sealRevision) || item["archiveDirty"] as? Bool == true {
        item = try journal.publishSeal(previous, metadata: item)
        if id == workoutID { metadata = item }
      }
      guard WCSession.default.activationState == .activated else { return }
      if let seal = try journal.transfer.currentSeal(id: id) {
        try dispatchSeal(id: id, seal: seal, metadata: item)
      }
    } catch { reportSyncFailure(id: id, operation: "seal", error: error) }
  }

  func ownBackgroundTask(_ task: WKWatchConnectivityRefreshBackgroundTask) async {
    backgroundTasks.append(task)
    await activate()
    retrySync()
    // Keep ownership while WC is draining callbacks, with a bounded task lifetime.
    let deadline = Date().addingTimeInterval(20)
    while (WCSession.default.hasContentPending || applyingCommand || acceptingBatches > 0 || pendingInserts > 0 || archiveWork.active != nil || syncWork.active != nil || historicalWork.active != nil) && Date() < deadline {
      try? await Task.sleep(nanoseconds: 100_000_000)
    }
    backgroundTasks.removeAll { $0 === task }
    task.setTaskCompletedWithSnapshot(false)
  }

}

extension WatchWorkoutEngine {
  private func collectStatistics(_ types: Set<HKSampleType>) {
    guard let builder, isActive else { return }
    for type in types.compactMap({ $0 as? HKQuantityType }) {
      guard let metric = WatchHealthMetrics.metric(for: type), let stats = builder.statistics(for: type) else { continue }
      let quantity = metric.cumulative ? stats.sumQuantity() : stats.mostRecentQuantity()
      guard let quantity else { continue }
      let value = quantity.doubleValue(for: metric.unit)
      guard value.isFinite else { continue }
      let interval = stats.mostRecentQuantityDateInterval()
      let date = interval?.end ?? Date()
      var payload: [String: WorkoutJSON] = ["healthKitIdentifier": .string(type.identifier),
        "value": .number(value), "unit": .string(metric.unitName),
        "representation": .string(metric.cumulative ? "cumulativeWorkoutTotal" : "builderMostRecent")]
      let key: String?
      switch metric.identifier {
      case .heartRate: key = "heartRateBpm"
      case .activeEnergyBurned: key = "activeEnergyKcal"
      case .basalEnergyBurned: key = "basalEnergyKcal"
      case .distanceCycling: key = "distanceMeters"
      case .cyclingSpeed: key = "speedMps"
      case .respiratoryRate: key = "respiratoryRateBrpm"
      case .oxygenSaturation: key = "oxygenSaturationFraction"
      case .heartRateVariabilitySDNN: key = "heartRateVariabilitySDNNMs"
      case .physicalEffort: key = "physicalEffortMET"
      case .cyclingFunctionalThresholdPower: key = "cyclingFunctionalThresholdPowerW"
      default: key = nil // CYC events already carry the canonical rider power and cadence values.
      }
      if let key { payload[key] = .number(value) }
      if let interval {
        payload["sampleStart"] = .string(WorkoutCoding.timestamp(interval.start))
        payload["sampleEnd"] = .string(WorkoutCoding.timestamp(interval.end))
      }
      do { try record(kind: "health", date: date, payload: payload) }
      catch { issue = "Health recording: \(error.localizedDescription)" }
    }
  }

  private func beginRawHealthQueries(start: Date) {
    stopQueries()
    // Native queries retain individual samples in addition to live builder aggregates.
    // Restrict live queries to this Watch's device; final reads use the saved workout association.
    for metric in WatchHealthMetrics.supported {
      guard let type = metric.type else { continue }
      let predicate = NSCompoundPredicate(andPredicateWithSubpredicates: [
        HKQuery.predicateForSamples(withStart: start, end: nil, options: .strictStartDate),
        HKQuery.predicateForObjects(from: [HKDevice.local()])
      ])
      let state = RawHealthObservation(type: type, predicate: predicate, generation: rawQueryGeneration)
      if let id = workoutID, let bytes = try? journal?.healthAnchor(id: id, progressKey: "live:" + type.identifier) {
        state.anchor = try? NSKeyedUnarchiver.unarchivedObject(ofClass: HKQueryAnchor.self, from: bytes)
      }
      rawHealthObservations[type.identifier] = state
      let observer = HKObserverQuery(sampleType: type, predicate: predicate) { [weak self, weak state] query, completion, error in
        Task { @MainActor in
          guard let self, let state, self.isCurrentRawObservation(state), state.observer === query else {
            completion(); return
          }
          if let error {
            self.issue = "Health observations unavailable: \(error.localizedDescription)"
            state.retryRequired = true
            completion(); return
          }
          state.completions.append(completion)
          self.invalidateRawHealthObservation(state)
        }
      }
      state.observer = observer
      // Observe before the initial fetch so additions racing with that fetch trigger another drain.
      healthStore.execute(observer)
      invalidateRawHealthObservation(state)
    }
  }

  private func isCurrentRawObservation(_ state: RawHealthObservation) -> Bool {
    state.generation == rawQueryGeneration && rawHealthObservations[state.type.identifier] === state && !state.drain.isStopped
  }

  private func invalidateRawHealthObservation(_ state: RawHealthObservation) {
    guard isCurrentRawObservation(state) else { state.completeNotifications(); return }
    if state.drain.invalidate() { startRawHealthPage(state) }
  }

  private func retryRawHealthQueries() {
    for state in rawHealthObservations.values where state.retryRequired {
      invalidateRawHealthObservation(state)
    }
  }

  private func startRawHealthPage(_ state: RawHealthObservation) {
    guard isCurrentRawObservation(state), state.page == nil else { return }
    state.retryRequired = false
    // A finite HKAnchoredObjectQuery MUST NOT have an updateHandler; HealthKit raises an
    // Objective-C exception for that combination. HKObserverQuery supplies invalidations instead.
    let query = HKAnchoredObjectQuery(type: state.type, predicate: state.predicate, anchor: state.anchor, limit: 256) { [weak self, weak state] query, samples, deleted, nextAnchor, error in
      Task { @MainActor in
        guard let self, let state, self.isCurrentRawObservation(state), state.page === query else { return }
        state.page = nil // Finite queries stop themselves after their result callback.
        if let error {
          self.issue = "Health samples unavailable: \(error.localizedDescription)"
          state.retryRequired = true; state.drain.fail(); state.completeNotifications()
          return
        }
        let hasMore = (samples?.count ?? 0) + (deleted?.count ?? 0) >= 256
        do {
          try self.commitHealthPage(samples?.compactMap { $0 as? HKQuantitySample } ?? [], deleted: deleted ?? [],
            anchor: nextAnchor, key: "live:" + state.type.identifier, completed: !hasMore)
        } catch {
          self.issue = "Health page remains pending: \(error.localizedDescription)"
          state.retryRequired = true; state.drain.fail(); state.completeNotifications(); return
        }
        state.anchor = nextAnchor
        if state.drain.finishPage(hasMore: hasMore) { self.startRawHealthPage(state) }
        else { state.completeNotifications() }
      }
    }
    state.page = query
    healthStore.execute(query)
  }

  private func stopQueries() {
    rawQueryGeneration = UUID()
    for state in rawHealthObservations.values {
      state.drain.stop()
      if let observer = state.observer { healthStore.stop(observer) }
      if let page = state.page { healthStore.stop(page) }
      state.observer = nil; state.page = nil
      state.completeNotifications()
    }
    rawHealthObservations.removeAll()
  }

  private func rawHealthEvent(_ sample: HKQuantitySample, collectionID: String? = nil, ownerStart: Date? = nil) throws -> WorkoutEvent? {
    guard let id = collectionID ?? workoutID, let journal, let metric = WatchHealthMetrics.metric(for: sample.quantityType) else { return nil }
    let value = sample.quantity.doubleValue(for: metric.unit)
    guard value.isFinite else { throw WorkoutDataError.invalid("Nonfinite Health observation") }
    var payload: [String: WorkoutJSON] = ["healthKitIdentifier": .string(sample.quantityType.identifier),
      "value": .number(value), "unit": .string(metric.unitName), "representation": .string("rawQuantity"),
      "sampleUUID": .string(sample.uuid.uuidString.lowercased()), "sampleCount": .number(Double(sample.count)),
      "sampleStart": .string(WorkoutCoding.timestamp(sample.startDate)), "sampleEnd": .string(WorkoutCoding.timestamp(sample.endDate)),
      "sourceBundleIdentifier": .string(sample.sourceRevision.source.bundleIdentifier)]
    if metric.identifier == .heartRate && sample.count == 1 { payload["heartRateBpm"] = .number(value) }
    return try WorkoutEvent(workoutId: id, kind: "health", source: "watch", timestamp: sample.endDate,
                            elapsedSeconds: try ownerStart.map { max(0, sample.endDate.timeIntervalSince($0)) } ?? journal.elapsed(id: id, date: sample.endDate), payload: payload, eventId: sample.uuid.uuidString)
  }

  private func commitHealthPage(_ samples: [HKQuantitySample], deleted: [HKDeletedObject],
                                anchor: HKQueryAnchor?, key: String, completed: Bool, collectionID: String? = nil) throws {
    guard let id = collectionID ?? workoutID, let journal, let anchor else { throw WorkoutDataError.invalid("Health query did not return durable progress") }
    let ownerStart = try WorkoutCoding.date(journal.archive.metadata(id: id).startedAt)
    var events = try samples.compactMap { try rawHealthEvent($0, collectionID: id, ownerStart: ownerStart) }
    for removed in deleted {
      let uuid = removed.uuid.uuidString.lowercased()
      let observedAt = Date()
      events.append(try WorkoutEvent(workoutId: id, kind: "health", source: "watch", timestamp: observedAt,
        elapsedSeconds: max(0, observedAt.timeIntervalSince(ownerStart)), payload: ["representation": .string("healthTombstone"), "sampleUUID": .string(uuid),
          "supersedesEventId": .string(uuid), "deleted": .bool(true)], eventId: WatchHealthMetrics.stableEventID("deleted:" + uuid)))
    }
    let bytes = try NSKeyedArchiver.archivedData(withRootObject: anchor, requiringSecureCoding: true)
    try journal.commitHealthPage(id: id, events: events, progressKey: key, anchor: bytes, completed: completed)
    if id == workoutID { metadata["archiveDirty"] = true; for event in events { applyDisplay(event) } }
  }

  private func collectFinishedHealth(_ workout: HKWorkout, collectionID: String? = nil) async {
    guard let id = collectionID ?? workoutID, let journal else { return }
    guard (try? journal.archive.metadata(id: id).savesToHealth) == true else { return }
    guard workout.metadata?[HKMetadataKeyExternalUUID] as? String == id else { issue = "Finished Health workout identity does not match the local collection"; return }
    guard let ownerStart = try? WorkoutCoding.date(journal.archive.metadata(id: id).startedAt) else { return }
    var extraction: [String: Any] = ["healthSamplesIncomplete": false, "archiveDirty": true]
    func storeFinalHealth(date: Date, payload: [String: WorkoutJSON], eventId: String) throws {
      let event = try WorkoutEvent(workoutId: id, kind: "health", source: "watch", timestamp: date,
        elapsedSeconds: max(0, date.timeIntervalSince(ownerStart)), payload: payload, eventId: eventId)
      if let logical = payload["logicalTotalID"]?.string { _ = try WorkoutHealthRevisionJournal.append(event, logicalID: logical, archive: journal.archive) }
      else if try !journal.archive.hasEvent(id: id, eventID: event.eventId) { try journal.archive.append(event) }
    }
    for (key, value) in workout.metadata ?? [:] {
      guard let typedValue = WatchHealthMetrics.metadataValue(value) else { continue }
      do {
        let identity = "metadata:\(workout.uuid):\(key):\(String(data: try WorkoutCoding.encoder().encode(typedValue), encoding: .utf8) ?? "")"
        try storeFinalHealth(date: workout.endDate, payload: ["representation": .string("workoutMetadata"),
          "key": .string(key), "typedValue": typedValue], eventId: WatchHealthMetrics.stableEventID(identity))
      } catch { extraction["healthSamplesIncomplete"] = true; issue = "Workout metadata: \(error.localizedDescription)" }
    }
    var availableTypes: [WorkoutJSON] = []
    for metric in WatchHealthMetrics.supported {
      guard let type = metric.type else { continue }
      do {
        let progressKey = "final:" + workout.uuid.uuidString.lowercased() + ":" + type.identifier
        var anchor: HKQueryAnchor?
        if let bytes = try journal.healthAnchor(id: id, progressKey: progressKey) {
          anchor = try NSKeyedUnarchiver.unarchivedObject(ofClass: HKQueryAnchor.self, from: bytes)
        }
        var hasSamples = false
        while true {
          let page: (samples: [HKQuantitySample], deleted: [HKDeletedObject], anchor: HKQueryAnchor?, count: Int) = try await withCheckedThrowingContinuation { continuation in
            let predicate = HKQuery.predicateForObjects(from: workout)
            healthStore.execute(HKAnchoredObjectQuery(type: type, predicate: predicate, anchor: anchor, limit: 512) { _, samples, deleted, nextAnchor, error in
              if let error { continuation.resume(throwing: error) }
              else { continuation.resume(returning: (samples?.compactMap { $0 as? HKQuantitySample } ?? [], deleted ?? [], nextAnchor,
                (samples?.count ?? 0) + (deleted?.count ?? 0))) }
            })
          }
          try journal.archive.associateHealthSamples(id: id, source: "watch", sampleIDs: page.samples.map { $0.uuid.uuidString },
            healthWorkoutID: workout.uuid.uuidString, at: workout.endDate)
          try commitHealthPage(page.samples, deleted: page.deleted, anchor: page.anchor, key: progressKey, completed: page.count < 512, collectionID: id)
          hasSamples = hasSamples || !page.samples.isEmpty
          guard page.count >= 512, let nextAnchor = page.anchor else { break }
          anchor = nextAnchor
        }
        if hasSamples { availableTypes.append(.string(type.identifier)) }
        // A quantity sample may contain hundreds of points. Preserve every series member at its original interval.
        let descriptor = HKQuantitySeriesSampleQueryDescriptor(
          predicate: .quantitySample(type: type, predicate: HKQuery.predicateForObjects(from: workout)),
          options: [.includeSample, .orderByQuantitySampleStartDate])
        var series: [WorkoutEvent] = []
        for try await point in descriptor.results(for: healthStore) {
          guard let sample = point.sample, sample.count > 1 else { continue }
          let value = point.quantity.doubleValue(for: metric.unit)
          guard value.isFinite else { continue }
          let identity = "\(sample.uuid):\(point.dateInterval.start.timeIntervalSince1970):\(point.dateInterval.end.timeIntervalSince1970):\(value)"
          var payload: [String: WorkoutJSON] = ["healthKitIdentifier": .string(type.identifier),
            "value": .number(value), "unit": .string(metric.unitName), "representation": .string("rawSeries"),
            "sampleUUID": .string(sample.uuid.uuidString.lowercased()),
            "sampleStart": .string(WorkoutCoding.timestamp(point.dateInterval.start)),
            "sampleEnd": .string(WorkoutCoding.timestamp(point.dateInterval.end)),
            "sourceBundleIdentifier": .string(sample.sourceRevision.source.bundleIdentifier)]
          if metric.identifier == .heartRate { payload["heartRateBpm"] = .number(value) }
          series.append(try WorkoutEvent(workoutId: id, kind: "health", source: "watch", timestamp: point.dateInterval.end,
            elapsedSeconds: max(0, point.dateInterval.end.timeIntervalSince(ownerStart)), payload: payload, eventId: WatchHealthMetrics.stableEventID(identity)))
          if series.count >= 128 { _ = try journal.archive.appendBatch(series); series.removeAll(keepingCapacity: true) }
        }
        if !series.isEmpty { _ = try journal.archive.appendBatch(series) }
        if metric.cumulative, let sum = workout.statistics(for: type)?.sumQuantity() {
          let value = sum.doubleValue(for: metric.unit)
          let key = metric.identifier == .activeEnergyBurned ? "activeEnergyKcal" :
            metric.identifier == .basalEnergyBurned ? "basalEnergyKcal" : "distanceMeters"
          try storeFinalHealth(date: workout.endDate, payload: [key: .number(value),
            "healthKitIdentifier": .string(type.identifier), "representation": .string("finalWorkoutTotal"),
            "value": .number(value), "unit": .string(metric.unitName),
            "logicalTotalID": .string("final:\(workout.uuid):\(type.identifier)"),
            "supersedesLogicalTotal": .string("final:\(workout.uuid):\(type.identifier)")],
            eventId: WatchHealthMetrics.stableEventID("final:\(workout.uuid):\(type.identifier):\(value)"))
        }
      } catch {
        issue = "Some Health samples are unavailable: \(error.localizedDescription). Available records are retained."
        extraction["healthSamplesIncomplete"] = true
      }
    }
    extraction["finalHealthExtracted"] = extraction["healthSamplesIncomplete"] as? Bool != true
    extraction["availableHealthTypes"] = availableTypes.map(\.any)
    extraction["healthReadAccess"] = "Only returned samples are available; HealthKit does not reveal denied read permissions."
    do {
      var latest = id == workoutID ? metadata : try journal.metadata(id: id)
      latest.merge(extraction) { _, new in new }
      latest["eventCount"] = try journal.archive.metadata(id: id).eventCount
      try journal.save(id: id, metadata: latest)
      if id == workoutID { metadata = latest }
    } catch { issue = "Health extraction metadata remains pending: \(error.localizedDescription)" }
  }
}

extension WatchWorkoutEngine: HKWorkoutSessionDelegate {
  nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didChangeTo toState: HKWorkoutSessionState,
    from fromState: HKWorkoutSessionState, date: Date) {
    Task { @MainActor in
      guard self.session === workoutSession else { return }
      guard self.sessionReady else { return }
      if toState == .paused || toState == .running {
        if self.metadata["endedAt"] != nil {
          self.phase = "finished"
          do { try self.commitOwner(phase: "finishing", at: date, completing: self.pendingOwnerCommand?.endsWorkout == true ? nil : self.pendingOwnerCommand) }
          catch { self.issue = error.localizedDescription }
          self.sendStatus(); return
        }
        self.phase = toState == .paused ? "paused" : "running"
        self.metadata["phase"] = self.phase
        self.metadata["phaseTimestamp"] = WorkoutCoding.timestamp(date)
        if fromState == .running || fromState == .paused {
          do { try self.record(kind: "lifecycle", date: date,
            payload: ["action": .string(toState == .paused ? "pause" : "resume")],
            eventId: self.pendingOwnerCommand?.action == (toState == .paused ? "pause" : "resume") ? self.pendingOwnerCommand!.id :
              WatchHealthMetrics.stableEventID("state:\(self.workoutID ?? ""):\(toState.rawValue):\(date.timeIntervalSince1970)")) }
          catch { self.issue = error.localizedDescription }
        }
        if toState == .paused {
          self.locationManager.stopUpdatingLocation(); self.lastLocation = nil
          self.gpsLabel = self.recordGPS ? "GPS paused" : "GPS off"
        } else { self.startLocations() }
        do { try self.commitOwner(phase: self.phase, at: date, completing: self.pendingOwnerCommand) }
        catch { self.issue = "Owner transition commit: \(error.localizedDescription)"; return }
        self.sendStatus()
      } else if toState == .stopped || toState == .ended {
        if !self.saveToHealth, self.metadata["discardRequested"] as? Bool != true, self.pendingOwnerCommand?.action != "discard" {
          if !self.isFinishing { await self.end(at: date) }; return
        }
        if self.pendingOwnerCommand?.action == "discard" || self.metadata["discardRequested"] as? Bool == true {
          if !self.isFinishing { await self.end(at: date) }; return
        }
        do { try self.commitOwner(phase: "completed", at: date,
          completing: self.pendingOwnerCommand?.action == "stop" ? self.pendingOwnerCommand : nil) }
        catch { self.issue = error.localizedDescription; return }
        self.sendStatus()
        if !self.isFinishing { await self.end(at: date) }
      }
    }
  }

  nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
    Task { @MainActor in
      guard self.session === workoutSession else { return }
      self.issue = "Workout session: \(error.localizedDescription)"
      self.metadata["error"] = self.issue
      await self.end()
    }
  }

  nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didReceiveDataFromRemoteWorkoutSession data: [Data]) {
    for bytes in data where bytes.count <= 262_144 {
      guard let value = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { continue }
      Task { @MainActor in await self.receive(value) }
    }
  }

  nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didDisconnectFromRemoteDeviceWithError error: Error?) {
    Task { @MainActor in
      guard self.session === workoutSession else { return }
      self.mirroring = false
      self.phoneReachable = WCSession.default.isReachable
    }
  }
}

extension WatchWorkoutEngine: HKLiveWorkoutBuilderDelegate {
  nonisolated func workoutBuilder(_ workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOf collectedTypes: Set<HKSampleType>) {
    Task { @MainActor in
      guard self.builder === workoutBuilder else { return }
      self.collectStatistics(collectedTypes)
    }
  }
  nonisolated func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {
    Task { @MainActor in self.objectWillChange.send() }
  }
}

extension WatchWorkoutEngine: CLLocationManagerDelegate {
  nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    Task { @MainActor in if self.isActive { self.startLocations() } }
  }

  nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    Task { @MainActor in
      guard self.phase == "running", self.recordGPS, let builder = self.builder else { return }
      var route: [CLLocation] = []
      for location in locations.sorted(by: { $0.timestamp < $1.timestamp }) {
        guard CLLocationCoordinate2DIsValid(location.coordinate), location.horizontalAccuracy.isFinite,
          location.timestamp >= (builder.startDate ?? .distantFuture),
          location.timestamp.timeIntervalSinceNow <= 2,
          Date().timeIntervalSince(location.timestamp) <= 15,
          self.lastLocation.map({ location.timestamp > $0.timestamp }) ?? true else { self.gpsBarrier = true; continue }
        var payload: [String: WorkoutJSON] = ["latitude": .number(location.coordinate.latitude),
          "longitude": .number(location.coordinate.longitude), "horizontalAccuracyM": .number(location.horizontalAccuracy),
          "distanceBarrier": .bool(self.gpsBarrier)]
        if location.verticalAccuracy >= 0 {
          payload["altitudeMeters"] = .number(location.altitude)
          payload["verticalAccuracyM"] = .number(location.verticalAccuracy)
        }
        if location.speed >= 0 { payload["speedMps"] = .number(location.speed) }
        payload["speedAccuracyMps"] = .number(location.speedAccuracy)
        if location.course >= 0 { payload["courseDegrees"] = .number(location.course) }
        payload["courseAccuracyDegrees"] = .number(location.courseAccuracy)
        do { try self.record(kind: "location", date: location.timestamp, payload: payload) }
        catch { self.gpsBarrier = true; self.issue = "GPS storage: \(error.localizedDescription)"; continue }
        self.gpsBarrier = false
        self.lastLocation = location
        if (0...50).contains(location.horizontalAccuracy) { route.append(location) }
        self.gpsLabel = (0...50).contains(location.horizontalAccuracy) ? "GPS ±\(Int(location.horizontalAccuracy)) m" : "Weak GPS"
      }
      guard self.saveToHealth, !route.isEmpty, let routeBuilder = self.routeBuilder else { return }
      guard let id = self.workoutID else { return }
      let identity = self.effectIdentity(id)
      self.pendingInserts += 1
      defer { self.pendingInserts -= 1 }
      do {
        guard let journal = self.journal else { throw WorkoutDataError.invalid("Missing archive") }
        try WorkoutRecordingPolicy.requireHealthWrite(id: id, archive: journal.archive)
        try await routeBuilder.insertRouteData(route)
      }
      catch {
        guard self.isCurrent(identity), self.routeBuilder === routeBuilder else { return }
        self.issue = "Health route unavailable; raw GPS remains in the local ride. \(error.localizedDescription)"
        self.metadata["routeSaveFailed"] = true; self.persistMetadata()
      }
    }
  }

  nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    Task { @MainActor in
      guard self.phase == "running", self.recordGPS else { return }
      if (error as? CLError)?.code == .locationUnknown {
        // Core Location is still trying. This is not a workout save failure.
        self.gpsLabel = "Waiting for GPS"
        return
      }
      self.gpsLabel = "GPS unavailable"
      self.issue = "GPS: \(error.localizedDescription)"
    }
  }
}

extension WatchWorkoutEngine: WCSessionDelegate {
  nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
    Task { @MainActor in
      self.phoneReachable = session.isReachable
      if let error { self.logTransportFailure(operation: "activation", error: error) }
      if activationState == .activated {
        self.clearSyncIssue(id: self.workoutID ?? "", operation: "connection")
        if let command = session.receivedApplicationContext["pendingStart"] as? [String: Any] { await self.receive(command) }
        self.retrySync(); if self.isActive { self.sendStatus() }
      }
    }
  }

  nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
    Task { @MainActor in
      self.phoneReachable = session.isReachable
      if session.isReachable {
        self.retrySync()
        if self.isActive { if !self.mirroring { self.startMirroring() }; self.sendStatus() }
      }
    }
  }

  nonisolated func session(_ session: WCSession, didReceiveMessageData messageData: Data) {
    guard messageData.count <= 262_144, let value = try? JSONSerialization.jsonObject(with: messageData) as? [String: Any] else { return }
    Task { @MainActor in await self.receive(value) }
  }

  nonisolated func session(_ session: WCSession, didReceiveMessageData messageData: Data, replyHandler: @escaping (Data) -> Void) {
    self.session(session, didReceiveMessageData: messageData)
    // Radio reply merely closes WC's request. The explicit ACK is emitted only after durable processing.
    replyHandler(Data("{}".utf8))
  }

  nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
    Task { @MainActor in await self.receive(message) }
  }

  nonisolated func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any]) {
    if let data = userInfo["data"] as? Data {
      self.session(session, didReceiveMessageData: data)
    } else { Task { @MainActor in await self.receive(userInfo) } }
  }

  nonisolated func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
    guard let command = applicationContext["pendingStart"] as? [String: Any] else { return }
    Task { @MainActor in await self.receive(command) }
  }

  nonisolated func session(_ session: WCSession, didFinish fileTransfer: WCSessionFileTransfer, error: Error?) {
    Task { @MainActor in
      guard let journal = self.journal,
        let id = fileTransfer.file.metadata?["workoutId"] as? String,
        let identity = fileTransfer.file.metadata?["chunkIdentity"] as? String else { return }
      let pending = try? WorkoutChunkSender(archive: journal.archive).pending(id: id)
      if pending?.identity == identity, let error { self.logTransportFailure(operation: "chunk", error: error) }
      do { try journal.reconcileChunks(referenced: self.referencedChunks()) }
      catch { self.syncLog.error("File reconciliation failed: code=\((error as NSError).code)") }
      self.queueSync(id: id)
    }
  }

  nonisolated func session(_ session: WCSession, didFinish userInfoTransfer: WCSessionUserInfoTransfer, error: Error?) {
    Task { @MainActor in
      guard userInfoTransfer.userInfo["kind"] as? String == "seal",
        let id = userInfoTransfer.userInfo["workoutId"] as? String, let journal = self.journal,
        (try? journal.store.isWorkoutDeleted(id: id)) == false else { return }
      if let error, let seal = try? journal.transfer.currentSeal(id: id),
        userInfoTransfer.userInfo["sealRevision"] as? String == String(seal.sealRevision),
        (try? journal.metadata(id: id)["acknowledgedSealRevision"] as? String) != String(seal.sealRevision) {
        self.logTransportFailure(operation: "seal", error: error)
      }
      self.queueArchive(id: id)
    }
  }
}
