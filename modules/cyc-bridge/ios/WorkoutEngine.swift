import Foundation
#if os(iOS)
import UIKit
#endif

/// A portable original-data package, separate from FIT's intentionally smaller field set.
enum WorkoutOriginalExport {
  static func write(archive: WorkoutArchive, id: String, to output: URL, revision: Int64? = nil) throws {
    let metadata = try archive.metadata(id: id, atRevision: revision)
    guard metadata.phase == "completed", metadata.endedAt != nil,
      metadata.sealVerified,
      ["complete", "partial"].contains(metadata.finalizationState ?? "") else {
      throw CycError.invalid("Finish the workout and wait for its final Watch archive before exporting original data.")
    }
    let fm = FileManager.default
    if fm.fileExists(atPath: output.path) { return }
    let temporary = output.deletingLastPathComponent().appendingPathComponent("original-" + UUID().uuidString, isDirectory: true)
    let staging = temporary.appendingPathComponent("PowerLog-original", isDirectory: true)
    try fm.createDirectory(at: staging, withIntermediateDirectories: true)
    defer { try? fm.removeItem(at: temporary) }
    #if os(iOS)
    try fm.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: staging.path)
    #endif
    let before = try JSONSerialization.data(withJSONObject: metadata.dictionary, options: [.sortedKeys])
    try before.write(to: staging.appendingPathComponent("metadata.json"))
    let eventsURL = staging.appendingPathComponent("events.jsonl")
    let csvURL = staging.appendingPathComponent("CYCtelemetry.csv")
    fm.createFile(atPath: eventsURL.path, contents: nil)
    fm.createFile(atPath: csvURL.path, contents: Data((CycProtocol.csvHeader + "\n").utf8))
    let events = try FileHandle(forWritingTo: eventsURL), csv = try FileHandle(forWritingTo: csvURL)
    defer { try? events.close(); try? csv.close() }
    try csv.seekToEnd()
    var count = 0
    try archive.forEachEvent(id: id, revision: metadata.collectionRevision) { event in
      let encoded = try JSONSerialization.data(withJSONObject: event.dictionary, options: [.sortedKeys, .withoutEscapingSlashes])
      try events.write(contentsOf: encoded); try events.write(contentsOf: Data([10]))
      if event.source == "cyc", event.kind == "telemetry" {
        let sample = event.payload.mapValues(\.any)
        let fields = CycProtocol.columns.map { key -> String in
          if key == "timestamp" { return event.timestamp }
          if let number = sample[key] as? NSNumber, number.doubleValue.isFinite { return number.stringValue }
          if let text = sample[key] as? String { return text }
          return "" // A partial original event stays partial; never synthesize measurements.
        }
        try csv.write(contentsOf: Data((fields.joined(separator: ",") + "\n").utf8))
      }
      count += 1
    }
    try events.synchronize(); try csv.synchronize()
    guard count == metadata.eventCount else { throw CycError.invalid("The chosen immutable workout snapshot is incomplete.") }
    let manifest: [String: Any] = ["format": "power-log-original", "schemaVersion": 1, "workoutId": metadata.id,
      "collectionRevision": metadata.collectionRevision ?? 0, "sealRevision": metadata.sealRevision ?? 0,
      "events": count, "files": ["metadata.json", "events.jsonl", "CYCtelemetry.csv"],
      "notes": "events.jsonl retains all canonical original event payloads. CYCtelemetry.csv is a convenience table of known fields; empty cells mean unavailable values."]
    try JSONSerialization.data(withJSONObject: manifest, options: [.prettyPrinted, .sortedKeys]).write(to: staging.appendingPathComponent("manifest.json"))
    var coordinationError: NSError?
    var copyError: Error?
    let snapshot = temporary.appendingPathComponent("snapshot.zip")
    // Apple produces a ZIP for a directory with .forUploading; copy it before its accessor returns.
    NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: staging, options: .forUploading, error: &coordinationError) { zipped in
      do { try fm.copyItem(at: zipped, to: snapshot) } catch { copyError = error }
    }
    if let coordinationError { throw coordinationError }
    if let copyError { throw copyError }
    guard fm.fileExists(atPath: snapshot.path) else { throw CycError.invalid("Original-data ZIP could not be created.") }
    #if os(iOS)
    try fm.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: snapshot.path)
    #endif
    if fm.fileExists(atPath: output.path) { _ = try fm.replaceItemAt(output, withItemAt: snapshot) }
    else { try fm.moveItem(at: snapshot, to: output) }
  }
}

#if os(iOS)
import CoreLocation
import Foundation
import HealthKit

/// Native serial owner for complete rides. The React module is only a command/event adapter.
@available(iOS 26.0, *)
final class WorkoutEngine {
  static let shared = WorkoutEngine()
  let queue = DispatchQueue(label: "app.powerlog.workout", qos: .userInitiated)
  let fileQueue = DispatchQueue(label: "app.powerlog.workout.analysis", qos: .utility)
  private let root: URL
  private var archive: WorkoutArchive?
  private var control: WorkoutControlJournal?
  private var transfer: WorkoutTransferJournal?
  private var pendingOwnerCommand: WorkoutCommand?
  private var startCommand: WorkoutCommand?
  private let health = WorkoutHealth()
  private let location = WorkoutLocation()
  private var connectivity: WorkoutPhoneConnectivity?
  private var sinks: [UUID: ([String: Any]) -> Void] = [:]
  private var ticker: DispatchSourceTimer?
  private var background = true
  private let captureInbox = PowerLogCaptureInbox()
  private let captureBatch = PowerLogCaptureBatch()
  private var id: String?
  private var sessionGeneration = UUID()
  private var recoveryState = "idle"
  private var recoveryMessage: String?
  private var recoveryQuery: WorkoutOwnerQuery?
  private var recoveryDeadline: TimeInterval?
  private var phase = "idle"
  private var started: Date?
  private var indoor = false
  private var useWatch = false
  private var saveToHealth = true
  private var recordGPS = true
  private var admittedSampleHz: Double?
  private var elapsedBase: Double = 0
  private var timerBase: Double = 0
  private var elapsedOrigin: TimeInterval?
  private var runningOrigin: TimeInterval?
  private var pendingAction: String?
  private var pendingRemoteAction: WorkoutPendingRemoteAction?
  private var healthKitState = "notSaved"
  private var discardRequested = false
  private var healthKitUUID: String?
  private var warnings: [String] = []
  private var error: String?
  private var gpsStatus = "inactive"
  private var lastGPS: Date?
  private var gpsAccuracy: Double?
  private var lastHeart: Date?
  private var lastCyc: Date?
  private var previousLocation: CLLocation?
  private var gpsDistance = WorkoutGPSDistanceAccumulator()
  private var gpsBarrier = false
  private let distanceQueue = DispatchQueue(label: "app.powerlog.workout.distance", qos: .utility)
  private var distanceInFlight = false
  private var distanceSnapshot: WorkoutDistanceSnapshot?
  private var metrics: [String: Double] = [:]
  private var telemetryBatch: [WorkoutEvent] { captureBatch.records.compactMap(\.ride) }
  var rideInProgress: Bool { ["preparing", "running", "paused", "recoverable", "finishing"].contains(phase) }
  private var startCompletion: ((Result<[String: Any], Error>) -> Void)?
  private var startDeadline: TimeInterval?
  private var startAttemptOrigin: TimeInterval?
  private var checkpoint = 0
  private var stopRequestedAt: Date?
  private var timeline: WorkoutTimelineAnchor?
  private var localInterruption: (id: String, epoch: String, elapsed: Double)?
  private var verificationJobs = Set<String>()
  private var verificationInvalidations = Set<String>()
  private var verificationACKs = Set<String>()
  private var contributorJobs = Set<String>()
  private var phoneFinalizationInFlight = false
  private var phoneSealJobs = Set<String>()
  private var phoneSealCursor = ""
  private var phoneStartInFlight = false
  private var forwardingRefreshNeeded = false
  private var deletionCursor = ""
  private var deletionCleanupID: String?
  private var presentationToken = UUID().uuidString.lowercased()
  private var presentationPhase = ""
  private var lastActivityPublish: Double = 0
  private var verifiedCompletedRideID: String?
  private var discardedCaptureID: String?
  private var activityRequest: WorkoutActivityRequest?
  private var observedCaptureFault: String?
  private var activityOwnerPhase: String?
  private var activityStopOutcome: String?

  private init() {
    root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("PowerLog/workouts", isDirectory: true)
    do {
      try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
      var privateRoot = root; var values = URLResourceValues(); values.isExcludedFromBackup = true
      try privateRoot.setResourceValues(values)
      archive = try WorkoutArchive(rootURL: root)
      health.archive = archive
      control = WorkoutControlJournal(store: archive!.store)
      transfer = WorkoutTransferJournal(archive: archive!)
      connectivity = try WorkoutPhoneConnectivity(rootURL: root, queue: queue, store: archive!.store)
    } catch { self.error = "Workout storage is unavailable: \(error.localizedDescription)" }
    connectivity?.mirrorSend = { [weak self] data, completion in self?.health.send(data, completion: completion) }
    connectivity?.mirrorAvailable = { [weak self] in self?.health.mirroringAvailable == true }
    connectivity?.onPacket = { [weak self] data in self?.receive(data) }
    connectivity?.onArchive = { [weak self] url, metadata, completion in
      guard let self else { completion(false); return }; self.importWatchArchive(url, metadata: metadata, completion: completion)
    }
    connectivity?.onChanged = { [weak self] in self?.emit() }
    health.onTelemetryCommitted = { [weak self] workoutID, _ in self?.queue.async {
      guard let self, self.id == workoutID, !self.useWatch else { return }
      do { try self.persist() } catch { self.storageFailed(error) }
      if !self.useWatch, self.phase == "completed", self.health.sessionSnapshot == nil { self.finishPhoneStop() }
    } }
    health.onMirror = { [weak self] in self?.queue.async { self?.connectivity?.recordDiagnostic(.mirrorStarted); self?.emit() } }
    health.onData = { [weak self] data in self?.queue.async { self?.connectivity?.receiveMirrored(data) } }
    health.onRemoteDisconnect = { [weak self] in self?.queue.async {
      self?.connectivity?.recordDiagnostic(.mirrorDisconnected)
      self?.warn("Watch connection is interrupted. Native records are retained for later synchronization."); self?.emit()
    } }
    health.onMetrics = { [weak self] workoutID, values, date in self?.queue.async {
      guard let self, self.id == workoutID, !self.useWatch, ["running", "paused", "finishing"].contains(self.phase) else { return }
      self.record(kind: "health", source: "phone", date: date, payload: values)
      self.updateMetrics(values, date: date)
    } }
    health.onState = { [weak self] workoutID, state, date in self?.queue.async {
      guard let self, self.id == workoutID, !self.useWatch, self.health.sessionSnapshot?.state == state else { return }
      if self.stopRequestedAt != nil, ["pause", "resume"].contains(self.pendingOwnerCommand?.action ?? ""), state == .paused || state == .running {
        do { try self.commitPhoneOwner(state == .paused ? "paused" : "running", at: date) } catch { self.storageFailed(error) }
        return
      }
      if state == .paused && self.phase == "running" { self.transition("paused", date: date, recordAction: "pause") }
      else if state == .running && self.phase == "paused" { self.transition("running", date: date, recordAction: "resume") }
      else if state == .stopped && self.stopRequestedAt != nil {
        if self.discardRequested || self.pendingOwnerCommand?.action == "discard" { return }
        do { try self.commitPhoneOwner("completed", at: date) } catch { self.storageFailed(error) }
      }
    } }
    health.onError = { [weak self] failure in self?.queue.async {
      self?.connectivity?.recordDiagnostic(.healthError, error: failure)
      self?.warn("HealthKit: \(failure.localizedDescription)"); self?.emit()
    } }
    health.onSessionFailure = { [weak self] workoutID, failure in self?.queue.async {
      guard let self, self.id == workoutID, !self.useWatch else { return }
      if self.healthKitState == "discarded" { return }
      self.connectivity?.recordDiagnostic(.sessionFailed, error: failure, phase: self.phase)
      self.healthKitState = "failed"
      self.warn("The HealthKit session failed: \(failure.localizedDescription)")
      if self.phase == "preparing" { self.startFailed(failure) }
      else if ["running", "paused"].contains(self.phase) {
        self.timerBase = self.timerSeconds; self.runningOrigin = nil; self.phase = "recoverable"
        self.updateMetadata(); try? self.persist(); self.emit()
      }
    } }
    location.onDiscontinuity = { [weak self] in self?.queue.async { self?.gpsBarrier = true; self?.gpsDistance.reset() } }
    location.onLocation = { [weak self] value in self?.queue.async { self?.consumeLocation(value) } }
    location.onStatus = { [weak self] value in self?.queue.async { self?.gpsStatus = value; self?.emit() } }
    queue.async {
      self.restore()
      self.emit()
      self.connectivity?.retryInbox()
      self.advanceDeletions()
      let timer = DispatchSource.makeTimerSource(queue: self.queue)
      timer.schedule(deadline: .now() + .milliseconds(250), repeating: .milliseconds(250))
      timer.setEventHandler { [weak self] in self?.tick() }
      timer.resume(); self.ticker = timer
    }
  }

  func addSink(id: UUID, sink: @escaping ([String: Any]) -> Void) { sinks[id] = sink; sink(state()) }
  func removeSink(id: UUID) { sinks.removeValue(forKey: id) }
  private func emit() {
    updateCaptureDestination()
    let samplingID = ["preparing", "running", "paused", "recoverable", "finishing"].contains(phase) ? id : nil
    let samplingRate = admittedSampleHz, cyc = CycEngine.shared
    cyc.queue.async { cyc.setWorkoutSamplingOwner(samplingID, rate: samplingRate) }
    connectivity?.setActiveWorkoutID(useWatch && (rideInProgress || phase == "completed" && id != verifiedCompletedRideID) ? id : nil)
    publishActivity()
    guard !background, !sinks.isEmpty else { return }
    let body = state()
    for sink in sinks.values { sink(body) }
  }

  private func updateCaptureDestination() {
    let accepts = ["running", "paused", "recoverable", "completed"].contains(phase)
      && !(!useWatch && !saveToHealth && phase == "recoverable")
    captureInbox.setDestination(accepts && id != nil && timeline != nil
      ? PowerLogCaptureDestination(id: id!, generation: sessionGeneration, timeline: timeline!) : nil)
  }

  private func publishActivity() {
    let pendingStop = useWatch && pendingRemoteAction.map { ["stop", "discard"].contains($0.action) } == true
    let phoneStopping = !useWatch && saveToHealth && pendingOwnerCommand?.endsWorkout == true
      && health.sessionSnapshot?.state != .stopped && health.sessionSnapshot?.state != .ended
      && !["saved", "discarded"].contains(healthKitState)
    let activityPhase = WorkoutActivityPhase.resolve(phase, watchOwned: useWatch, hasCutoff: stopRequestedAt != nil,
      ownerPhase: activityOwnerPhase, stopOutcome: activityStopOutcome,
      verified: id != nil && verifiedCompletedRideID == id, pendingStop: pendingStop, phoneStopping: phoneStopping)
    let activityPending = pendingStop ? pendingRemoteAction?.action : phoneStopping ? pendingOwnerCommand?.action : pendingAction
    let signature = (id ?? "") + ":" + activityPhase + ":" + (activityPending ?? "")
    if presentationPhase != signature {
      presentationPhase = signature; presentationToken = UUID().uuidString.lowercased()
    }
    lastActivityPublish = clock
    WorkoutLiveActivity.shared.publish(WorkoutActivitySnapshot(rideID: id, phase: activityPhase,
      pendingAction: activityPending, timerSeconds: timerSeconds, observedAt: Date(),
      lastBikeSampleAt: lastCyc, lastHeartSampleAt: lastHeart,
      riderPowerW: metrics["riderPowerW"], heartRateBpm: metrics["heartRateBpm"], controlToken: presentationToken))
  }
  private var clock: TimeInterval { ProcessInfo.processInfo.systemUptime }
  private var hasUnconfirmedWatchStop: Bool {
    WorkoutOwnerStopPolicy.isUnconfirmed(watchOwned: useWatch, phase: phase, hasCutoff: stopRequestedAt != nil,
      ownerPhase: activityOwnerPhase, stopOutcome: activityStopOutcome, verified: id != nil && verifiedCompletedRideID == id)
  }
  private var elapsed: Double { elapsedBase + (elapsedOrigin.map { max(0, clock - $0) } ?? 0) }
  private var timerSeconds: Double { timerBase + (runningOrigin.map { max(0, clock - $0) } ?? 0) }
  private func effectIdentity(_ id: String) -> WorkoutEffectIdentity { WorkoutEffectIdentity(workoutID: id, generation: sessionGeneration) }
  private func isCurrent(_ identity: WorkoutEffectIdentity) -> Bool { identity.matches(workoutID: id, generation: sessionGeneration) }
  private func requireCurrent(_ expectedID: String?) throws {
    if let expectedID, try WorkoutCoding.id(expectedID) != id { throw CycError.invalid("The selected workout changed. Refresh before trying again.") }
  }
  private func age(_ date: Date?) -> Double? { date.map { max(0, Date().timeIntervalSince($0)) } }
  private func stream(_ date: Date?, staleAfter: Double) -> [String: Any] {
    let seconds = age(date)
    return ["status": seconds == nil ? "missing" : seconds! <= staleAfter ? "receiving" : "stale",
      "lastSampleAgeSeconds": seconds as Any? ?? NSNull()]
  }

  func state() -> [String: Any] {
    var values: [String: Any] = [:]
    for key in ["riderPowerW", "cadenceRpm", "heartRateBpm", "activeEnergyKcal", "basalEnergyKcal", "distanceMeters", "speedMps"] {
      var value = metrics[key]
      if ["riderPowerW", "cadenceRpm"].contains(key) && (age(lastCyc) ?? .infinity) > 2.5 { value = nil }
      if key == "heartRateBpm" && (age(lastHeart) ?? .infinity) > 15 { value = nil }
      if key == "speedMps" && (age(lastGPS) ?? .infinity) > 10 { value = nil }
      values[key] = value as Any? ?? NSNull()
    }
    let catalog = id.flatMap { try? archive?.metadata(id: $0) }
    let owner = id.flatMap { try? control?.snapshot(workoutID: $0) }
    let deletion = try? archive?.store.historyDeletion()
    let distance = distanceSnapshot.flatMap { snapshot in snapshot.id == id ? snapshot.dictionary : nil }
    return ["historyRevision": deletion?.revision ?? "", "lastDeletedWorkoutId": deletion?.deletedWorkoutID as Any? ?? NSNull(),
      "collectionRevision": catalog?.collectionRevision as Any? ?? NSNull(),
      "sealRevision": catalog?.sealRevision as Any? ?? NSNull(),
      "verifiedSealRevision": catalog?.verifiedSealRevision as Any? ?? NSNull(),
      "finalizationState": catalog?.finalizationState as Any? ?? NSNull(),
      "ownerRevision": owner.map { String($0.ownerRevision) } as Any? ?? NSNull(), "supported": true,
      "capabilities": ["phoneWorkout": true, "watchWorkout": true, "healthKit": HKHealthStore.isHealthDataAvailable()],
      "id": id as Any? ?? NSNull(), "phase": phase, "startedAt": started.map(CycProtocol.timestamp) as Any? ?? NSNull(),
      "indoor": indoor, "useWatch": useWatch, "saveToHealth": saveToHealth, "recordGPS": recordGPS, "elapsedSeconds": elapsed, "timerSeconds": timerSeconds,
      "pendingAction": pendingAction as Any? ?? NSNull(), "healthKitState": healthKitState,
      "recoveryState": recoveryState, "recoveryMessage": recoveryMessage as Any? ?? NSNull(),
      "healthKitUUID": healthKitUUID as Any? ?? NSNull(), "watch": connectivity?.state ?? ["supported": false],
      "streams": ["cyc": stream(lastCyc, staleAfter: 2.5), "heartRate": stream(lastHeart, staleAfter: 15),
        "gps": ["status": !recordGPS ? "off" : (age(lastGPS) ?? .infinity) > 10 && lastGPS != nil ? "stale" : gpsStatus,
          "source": useWatch ? "watch" : "phone", "lastSampleAgeSeconds": age(lastGPS) as Any? ?? NSNull(),
          "accuracyMeters": gpsAccuracy as Any? ?? NSNull()]],
      "distance": distance as Any? ?? NSNull(),
      "metrics": values, "warnings": warnings, "error": error as Any? ?? NSNull()]
  }

  func getPermissions(_ completion: @escaping (Result<[String: Any], Error>) -> Void) {
    health.permissionStatus { result in
      switch result {
      case .failure(let error): completion(.failure(error))
      case .success(let health): self.location.permissionStatus { location in
        var value = location; value["health"] = health; completion(.success(value))
      }
      }
    }
  }

  func requestPermissions(indoor: Bool, useWatch: Bool, saveToHealth: Bool = true, recordGPS: Bool? = nil, completion: @escaping (Result<[String: Any], Error>) -> Void) {

    let gps = recordGPS ?? !indoor
    if !saveToHealth || useWatch {
      // Watch authorizes its own sensor session. Local phone recording needs no Health access.
      if gps && !useWatch { location.requestPermission { status in
        completion(.success(["health": self.health.permissions, "location": status]))
      } } else { location.permissionStatus { var value = $0; value["health"] = self.health.permissions; completion(.success(value)) } }
      return
    }
    if gps && !useWatch { requestPermissions(completion); return }
    health.requestPermission(recordGPS: gps) { result in
      switch result {
      case .failure(let error): completion(.failure(error))
      case .success(let health): self.location.permissionStatus { location in
        var value = location; value["health"] = health; completion(.success(value))
      }
      }
    }
  }

  func requestPermissions(_ completion: @escaping (Result<[String: Any], Error>) -> Void) {
    health.requestPermission { result in
      switch result {
      case .failure(let error): completion(.failure(error))
      case .success(let health): self.location.requestPermission { location in completion(.success(["health": health, "location": location])) }
      }
    }
  }

  func start(indoor: Bool, useWatch: Bool, saveToHealth: Bool = true, recordGPS: Bool? = nil, sampleHz: Double? = nil, completion: @escaping (Result<[String: Any], Error>) -> Void) {
    guard ["idle", "completed", "failed"].contains(phase), !hasUnconfirmedWatchStop, startCompletion == nil, pendingOwnerCommand == nil, !phoneStartInFlight, !phoneFinalizationInFlight, health.sessionSnapshot?.type != .primary else {
      completion(.failure(CycError.invalid("Finish or recover the current workout before starting another."))); return
    }
    guard let archive else { completion(.failure(CycError.invalid(error ?? "Workout storage is unavailable."))); return }
    do { if let sampleHz { _ = try WorkoutRecordingPolicy.sampleHz(sampleHz) } }
    catch { completion(.failure(error)); return }
    if useWatch {
      let watch = connectivity?.state ?? [:]
      guard watch["paired"] as? Bool == true, watch["installed"] as? Bool == true else {
        completion(.failure(CycError.invalid("Pair Apple Watch and install Power Log on it before starting a Watch workout."))); return
      }
    }
    do { try captureBarrier() } catch { completion(.failure(error)); return }
    guard telemetryBatch.isEmpty else {
      completion(.failure(CycError.invalid("Retained samples must commit before another ride starts."))); return
    }
    let nextWorkoutID = UUID().uuidString.lowercased()
    do { admittedSampleHz = try CycEngine.shared.queue.sync {
      if let sampleHz { try CycEngine.shared.setSampleRate(sampleHz) }
      CycEngine.shared.setWorkoutSamplingOwner(nextWorkoutID, rate: CycEngine.shared.currentSampleRate)
      return CycEngine.shared.currentSampleRate
    } } catch { completion(.failure(error)); return }
    self.id = nextWorkoutID; self.indoor = indoor; self.useWatch = useWatch
    activityOwnerPhase = nil; activityStopOutcome = nil
    self.saveToHealth = saveToHealth; self.recordGPS = recordGPS ?? !indoor
    sessionGeneration = UUID(); localInterruption = nil; recoveryState = "idle"; recoveryMessage = nil; recoveryQuery = nil; recoveryDeadline = nil
    startCommand = nil; timeline = nil
    started = Date(); phase = "preparing"; error = nil; warnings = []; metrics = [:]; gpsDistance = WorkoutGPSDistanceAccumulator(); gpsBarrier = false; distanceSnapshot = nil
    elapsedBase = 0; timerBase = 0; elapsedOrigin = nil; runningOrigin = nil
    previousLocation = nil; gpsDistance.reset(); lastGPS = nil; lastHeart = nil; lastCyc = nil; gpsAccuracy = nil
    discardRequested = false
    healthKitState = saveToHealth ? "pending" : "notRequested"; healthKitUUID = nil; pendingAction = "start"; pendingRemoteAction = nil; stopRequestedAt = nil
    gpsStatus = self.recordGPS ? "waiting" : "off"
    do {
      _ = try archive.create(id: id!, startedAt: started!, indoor: indoor, watchEnabled: useWatch, saveToHealth: saveToHealth, recordGPS: self.recordGPS)
      try archive.update(id: id!, phase: "preparing", healthKitState: healthKitState)
      try persist()
    } catch { self.phase = "failed"; emit(); completion(.failure(error)); return }
    startCompletion = completion; startDeadline = nil; startAttemptOrigin = clock
    let identity = effectIdentity(id!)
    connectivity?.recordDiagnostic(.startRequested, phase: phase)
    connectivity?.recordDiagnostic(.healthPermissionRequested, phase: phase)
    emit()
    let permissionFinished: (Result<[String: Any], Error>) -> Void = { result in self.queue.async {
      guard self.isCurrent(identity), self.phase == "preparing" else { return }
      if case .failure(let failure) = result {
        self.connectivity?.recordDiagnostic(.healthPermissionCompleted, success: false, error: failure, phase: self.phase)
        self.startFailed(failure); return
      }
      self.connectivity?.recordDiagnostic(.healthPermissionCompleted, success: true, phase: self.phase)
      if useWatch { self.startWatch() }
      else if !self.recordGPS { self.startPhone() }
      else {
        self.location.requestPermission { status in self.queue.async {
          guard self.isCurrent(identity), self.phase == "preparing" else { return }
          guard status == "authorizedAlways" || status == "authorizedWhenInUse" else {
            self.startFailed(CycError.invalid("Location permission is required for an outdoor phone workout.")); return
          }
          self.startPhone()
        } }
      }
    } }
    if saveToHealth && !useWatch { health.requestPermission(recordGPS: self.recordGPS, permissionFinished) }
    else { permissionFinished(.success(health.permissions)) }
  }

  private func makeCommand(_ action: String, at date: Date = Date(), cutoffElapsed: Double? = nil, cutoffTimer: Double? = nil) throws -> WorkoutCommand {
    guard let id, let control else { throw CycError.invalid("Durable command storage unavailable") }
    var options: [String: WorkoutJSON] = action == "start" ? ["indoor": .bool(indoor), "eBike": .bool(true), "saveToHealth": .bool(saveToHealth), "recordGPS": .bool(recordGPS)] : ["stop", "discard"].contains(action) ? ["cutoffElapsedSeconds": .number(cutoffElapsed ?? elapsed)] : [:]
    if !useWatch, !saveToHealth, ["stop", "discard"].contains(action) { options["timerSeconds"] = .number(cutoffTimer ?? timerSeconds) }
    let command: WorkoutCommand
    if let request = activityRequest {
      guard request.rideID == id, request.nativeAction == action else { throw CycError.invalid("Ride command changed before admission.") }
      command = try control.admitActivity(request, remote: useWatch, at: date, options: options)
    } else {
      command = try useWatch ? control.createRemote(workoutID: id, origin: "phone", action: action, at: date, options: options) :
        control.admitLocal(workoutID: id, origin: "phone", action: action, at: date, options: options)
    }
    if useWatch, action != "status" { pendingRemoteAction = WorkoutPendingRemoteAction(command) }
    if !useWatch {
      if activityRequest != nil { pendingOwnerCommand = command }
      else {
        let result = try control.begin(command)
        if result.outcome == "executing" { pendingOwnerCommand = command }
        else if !command.endsWorkout { throw CycError.invalid(result.reason ?? "Owner command is pending") }
      }
    }
    return command
  }

  func performActivityCommand(rideID: String, commandID: String, action: String, expectedPhase: String) throws -> [String: Any] {
    let request = try WorkoutActivityRequest(rideID: rideID, token: commandID, action: action, expectedPhase: expectedPhase)
    guard let control else { throw CycError.invalid("Ride controls are unavailable.") }
    if let previous = try control.activityCommand(request) {
      if try control.restageActivity(previous, currentRideID: id) { try connectivity?.refreshOutbox() }
      return state()
    }
    try request.requireCurrent(rideID: id, token: presentationToken, phase: phase, pendingAction: pendingAction)
    activityRequest = request
    defer { activityRequest = nil }
    switch action {
    case "pause": return try pause(expectedID: rideID)
    case "resume": return try resume(expectedID: rideID)
    default: return try stop(expectedID: rideID)
    }
  }

  private func commitPhoneOwner(_ next: String, at date: Date, failure: String? = nil) throws {
    guard !useWatch, let id, let control else { return }
    try WorkoutEffectIdentity.require(command: pendingOwnerCommand, workoutID: id,
      nativeWorkoutID: health.nativeWorkoutID)
    let nativeState = health.sessionSnapshot?.state
    let appliedPhase = ["pause", "resume", "lap"].contains(pendingOwnerCommand?.action ?? "") && next == "completed" && nativeState != .stopped && nativeState != .ended ?
      (nativeState == .paused ? "paused" : "running") : next
    if !saveToHealth, let archive {
      try commitLocalInterruption()
      _ = try WorkoutLocalOwner.observe(id: id, phase: next, at: date, elapsed: elapsed, command: pendingOwnerCommand,
        cutoff: next == "completed" ? stopRequestedAt : nil, discarded: discardRequested, archive: archive, control: control, failure: failure, checkpoint: {
          if self.pendingOwnerCommand?.action == "start", failure == nil {
            self.timeline = try WorkoutPhoneStartProjection.confirm(archive: archive, id: id, startedAt: date, now: date,
              uptime: self.clock, epoch: CycCaptureClock.processEpoch)
            self.started = date; self.elapsedBase = 0; self.timerBase = 0
            self.elapsedOrigin = self.clock; self.runningOrigin = self.clock; self.phase = "running"
          }
          try archive.update(id: id, phase: next, healthKitState: self.discardRequested ? "discarded" : "notRequested")
          self.phase = next
          if next == "completed", let cutoff = self.stopRequestedAt {
            try archive.update(id: id, stopElapsedSeconds: self.elapsed)
            try archive.finish(id: id, endedAt: cutoff, finalPhase: "completed")
          }
          try self.persist()
        })
    } else {
      _ = try control.observe(workoutID: id, owner: "phone", phase: appliedPhase, at: date,
        health: healthKitState, healthID: healthKitUUID, cutoff: appliedPhase == "completed" ? stopRequestedAt : nil,
        command: pendingOwnerCommand, failure: failure)
    }
    pendingOwnerCommand = nil; pendingAction = nil
    flushStorage()
    let identity = effectIdentity(id)
    queue.async { if self.isCurrent(identity) { self.drainDeferredStop() } }
  }

  private func drainDeferredStop() {
    guard !useWatch, let id, let control, pendingOwnerCommand == nil,
      let command = try? control.nextReady(workoutID: id, origin: "phone"), command.endsWorkout else { return }
    do {
      if let result = try control.settleTerminal(command) {
        if result.isTerminal { pendingAction = nil; try persist(); emit() }
        return
      }
      guard timeline != nil else { pendingAction = "stop"; return }
      let preparation = try control.prepare(command)
      guard preparation.execute || preparation.reconcile else { return }
      pendingOwnerCommand = command
      discardRequested = discardRequested || command.action == "discard"
      if stopRequestedAt == nil {
        guard let started else { throw CycError.invalid("The original phone start time is unavailable") }
        let cutoff = WorkoutUnconfirmedStopPolicy.confirmedCutoff(requestedAt: try WorkoutCoding.date(command.requestedAt), actualStart: started, now: Date())
        stopRequestedAt = cutoff
        timerBase = min(timerSeconds, max(0, cutoff.timeIntervalSince(started))); runningOrigin = nil
        elapsedBase = max(0, cutoff.timeIntervalSince(started)); elapsedOrigin = nil
        if !saveToHealth {
          elapsedBase = command.options["cutoffElapsedSeconds"]?.number ?? elapsedBase
          timerBase = command.options["timerSeconds"]?.number ?? timerBase
        }
        timeline?.stopUTC = WorkoutCoding.timestamp(cutoff)
        if let origin = timeline?.monotonicOrigin { timeline?.stopMonotonic = origin + elapsedBase }
        if saveToHealth {
          try archive?.update(id: id, stopElapsedSeconds: elapsedBase)
          try archive?.finish(id: id, endedAt: cutoff, finalPhase: "finishing")
        }
        phase = "completed"; pendingAction = nil; location.stop()
        if saveToHealth {
          record(kind: "lifecycle", source: "phone", date: cutoff, payload: ["action": "stop", "operationId": command.id], eventID: command.id)
          updateMetadata(); try persist(); emit()
        }
      }
      finishPhoneStop()
    } catch { storageFailed(error) }
  }

  private func retryDiscard() throws {
    if useWatch, let id, let control,
      let pending = try control.pendingRemote(workoutID: id), pending.action == "discard",
      let command = try control.remoteCommand(id: pending.commandID, workoutID: id) {
      try connectivity?.enqueue(command.packet)
    } else if !useWatch { finishPhoneStop() }
  }

  private func finishPhoneStop() {
    guard !useWatch, let id, let cutoff = stopRequestedAt, !phoneFinalizationInFlight else { return }
    if !saveToHealth {
      var ownerCommitted = false
      do {
        healthKitState = discardRequested ? "discarded" : "notRequested"; healthKitUUID = nil
        try commitPhoneOwner("completed", at: cutoff)
        ownerCommitted = true
        elapsedOrigin = nil; runningOrigin = nil; location.stop(); health.setLocalOwner(nil); emit()
        if discardRequested { _ = try deleteWorkout(id) } else { try sealPhone(id: id) }
      } catch { if !ownerCommitted { phase = "recoverable"; pendingAction = pendingOwnerCommand?.action }; storageFailed(error) }
      return
    }
    if discardRequested || pendingOwnerCommand?.action == "discard" {
      let identity = effectIdentity(id)
      phoneFinalizationInFlight = true
      let discarded: (Result<Void, Error>) -> Void = { result in self.queue.async {
        guard self.isCurrent(identity) else { return }
        self.phoneFinalizationInFlight = false
        do {
          try result.get()
          self.healthKitState = "discarded"; self.healthKitUUID = nil; self.phase = "completed"
          try self.commitPhoneOwner("completed", at: cutoff)
          self.updateMetadata(); try self.persist()
          _ = try self.deleteWorkout(id)
        } catch { self.storageFailed(error) }
      } }
      if healthKitState == "discarded" { discarded(.success(())) }
      else { health.discardPhone(id: id, completion: discarded) }
      return
    }
    let identity = effectIdentity(id)
    phoneFinalizationInFlight = true
    let finished: (Result<String, Error>) -> Void = { result in self.queue.async {
      guard self.isCurrent(identity) else { return }
      self.phoneFinalizationInFlight = false
      switch result {
      case .success(let uuid): self.healthKitUUID = uuid; self.healthKitState = "saved"
      case .failure(let failure): self.healthKitState = "pending"; self.warn("HealthKit finalization remains pending: \(failure.localizedDescription)")
      }
      do {
        let nativeState = self.health.sessionSnapshot?.state
        if self.healthKitState == "saved" || nativeState == .stopped || nativeState == .ended {
          try self.commitPhoneOwner("completed", at: cutoff)
        }
        try self.sealPhone()
      }
      catch { self.storageFailed(error) }
      self.complete(at: cutoff)
    } }
    if health.sessionSnapshot == nil { health.reconcileSaved(id: id, at: cutoff, completion: finished) }
    else { health.stopPhone(at: cutoff, completion: finished) }
  }

  private func startPhone() {
    guard let id else { return }
    let identity = effectIdentity(id)
    let date = Date()
    do { startCommand = try makeCommand("start", at: date) } catch { startFailed(error); return }
    startDeadline = clock + 45
    connectivity?.recordDiagnostic(.phoneCollectionRequested, phase: phase)
    if !saveToHealth {
      do {
        try commitPhoneOwner("running", at: date)
        health.setLocalOwner(id); startDeadline = nil
        connectivity?.recordDiagnostic(.collectionStarted, phase: phase, elapsedSeconds: startAttemptOrigin.map { max(0, clock - $0) })
        emit(); let completion = startCompletion; startCompletion = nil; completion?(.success(state()))
        if recordGPS { location.start() }
      } catch {
        phase = "preparing"; timeline = nil; elapsedBase = 0; timerBase = 0; elapsedOrigin = nil; runningOrigin = nil
        health.setLocalOwner(nil); startFailed(error)
      }
      return
    }
    phoneStartInFlight = true
    health.startPhone(id: id, indoor: indoor, at: date) { result in self.queue.async {
      guard self.isCurrent(identity) else { return }
      self.phoneStartInFlight = false
      switch result {
      case .success:
        guard ["preparing", "recoverable"].contains(self.phase) else { return }
        do { try self.commitPhoneOwner("running", at: date) } catch { self.startFailed(error); return }
        self.started = date; self.beginRunning(at: date)
        if self.recordGPS { self.location.start() }
      case .failure(let failure): self.startFailed(failure)
      }
    } }
  }

  private func startWatch() {
    guard let id else { return }
    let identity = effectIdentity(id)
    startDeadline = clock + 45
    connectivity?.recordDiagnostic(.watchLaunchRequested, phase: phase)
    do {
      let command = try startCommand ?? makeCommand("start")
      startCommand = command
      try connectivity?.setPendingStart(command.packet)
      try connectivity?.enqueue(command.packet)
      health.launchWatch(indoor: indoor) { result in self.queue.async {
        guard self.isCurrent(identity) else { return }
        if case .failure(let error) = result {
          self.connectivity?.recordDiagnostic(.watchLaunchCompleted, success: false, error: error, phase: self.phase)
          self.startFailed(error)
        } else { self.connectivity?.recordDiagnostic(.watchLaunchCompleted, success: true, phase: self.phase) }
      } }
    } catch { startFailed(error) }
  }

  private func beginRunning(at date: Date, confirmStart: Bool = true) {
    do {
      guard let id, let archive else { throw CycError.invalid("Workout storage unavailable") }
      if confirmStart { timeline = try WorkoutPhoneStartProjection.confirm(archive: archive, id: id, startedAt: date, now: Date(), uptime: clock, epoch: CycCaptureClock.processEpoch) }
    } catch { startFailed(error); return }
    elapsedBase = max(0, clock - (timeline?.monotonicOrigin ?? clock)); timerBase = elapsedBase
    phase = "running"; elapsedOrigin = clock; runningOrigin = clock; if !useWatch { pendingAction = nil }; startDeadline = nil
    connectivity?.recordDiagnostic(.collectionStarted, phase: phase, elapsedSeconds: startAttemptOrigin.map { max(0, clock - $0) })
    connectivity?.clearPendingStart()
    if !useWatch && saveToHealth { record(kind: "lifecycle", source: "phone", date: date, payload: ["action": "start"]) }
    updateMetadata(); try? persist(); emit()
    let completion = startCompletion; startCompletion = nil; completion?(.success(state()))
  }
  private func startFailed(_ failure: Error) {
    guard phase == "preparing" || (!useWatch && phase == "recoverable" && timeline == nil) else { return }
    connectivity?.recordDiagnostic(.startFailed, error: failure, phase: phase, elapsedSeconds: startAttemptOrigin.map { max(0, clock - $0) })
    error = failure.localizedDescription; phase = "failed"; pendingAction = nil; startDeadline = nil
    connectivity?.clearPendingStart()
    if !useWatch { phoneStartInFlight = false; if saveToHealth { health.cancelPendingPhone() } else { health.setLocalOwner(nil) } }
    if useWatch {
      if let cancel = try? makeCommand("stop") { _ = try? connectivity?.enqueue(cancel.packet) } }
    healthKitState = saveToHealth ? "notSaved" : "notRequested"
    if !useWatch { do { try commitPhoneOwner("failed", at: Date(), failure: failure.localizedDescription) } catch { storageFailed(error) } }
    updateMetadata(); try? persist(); emit()
    let completion = startCompletion; startCompletion = nil; completion?(.failure(failure))
  }

  func flushStorage() { queue.async { [weak self] in try? self?.archive?.store.checkpoint() } }
  func pause(expectedID: String? = nil) throws -> [String: Any] {
    defer { flushStorage() }
    try requireCurrent(expectedID)
    guard phase == "running", pendingAction == nil else { throw CycError.invalid("The workout is not ready to pause.") }
    try captureBarrier()
    let command = try makeCommand("pause")
    if !useWatch && !saveToHealth { transition("paused", date: try WorkoutCoding.date(command.requestedAt), recordAction: "pause"); return state() }
    if useWatch { try connectivity?.enqueue(command.packet) } else { health.pause() }
    pendingAction = "pause"; emit(); return state()
  }
  func resume(expectedID: String? = nil) throws -> [String: Any] {
    defer { flushStorage() }
    try requireCurrent(expectedID)
    guard phase == "paused", pendingAction == nil else { throw CycError.invalid("The owner must be paused before resuming.") }
    try captureBarrier()
    let command = try makeCommand("resume")
    if !useWatch && !saveToHealth { transition("running", date: try WorkoutCoding.date(command.requestedAt), recordAction: "resume"); return state() }
    if useWatch { try connectivity?.enqueue(command.packet) } else { health.resume() }
    pendingAction = "resume"; emit(); return state()
  }
  func lap(expectedID: String? = nil) throws -> [String: Any] {
    defer { flushStorage() }
    try requireCurrent(expectedID)
    guard phase == "running", pendingAction == nil else { throw CycError.invalid("Start or resume the workout before marking a lap.") }
    let date = Date(), command = try makeCommand("lap", at: Date())
    let identity = effectIdentity(command.workoutID)
    pendingAction = "lap"
    if useWatch { try connectivity?.enqueue(command.packet) }
    else if !saveToHealth {
      do { try commitPhoneOwner(phase, at: date) } catch { storageFailed(error); throw error }
    } else {
      health.lap(at: date, id: command.id) { result in self.queue.async {
        guard self.isCurrent(identity) else { return }
        do {
          switch result {
          case .success: self.record(kind: "lifecycle", source: "phone", date: date, payload: ["action": "lap", "operationId": command.id], eventID: command.id)
          case .failure(let failure): try self.commitPhoneOwner(self.phase, at: date, failure: failure.localizedDescription); self.emit(); return
          }
          try self.commitPhoneOwner(self.phase, at: date)
        } catch { self.storageFailed(error) }
      } }
    }
    emit(); return state()
  }
  func stop(expectedID: String? = nil, discard: Bool = false) throws -> [String: Any] {
    defer { flushStorage() }
    try requireCurrent(expectedID)
    try commitLocalInterruption()
    if discardRequested { try retryDiscard(); return state() }
    if discard {
      guard ["running", "paused"].contains(phase), pendingAction == nil, stopRequestedAt == nil else { throw CycError.invalid("Only an active ride without a pending operation can be discarded.") }
    }
    if !useWatch, timeline == nil, ["preparing", "recoverable"].contains(phase) {
      let decision = WorkoutUnconfirmedStopPolicy.action(hasTimeline: false, preparing: phase == "preparing",
        hasNativeIntent: startCommand != nil || pendingOwnerCommand != nil || health.sessionSnapshot?.type == .primary)
      if decision == .cancelPreparation {
        sessionGeneration = UUID(); phase = "failed"; pendingAction = nil; startDeadline = nil
        recoveryState = "resolved"; recoveryMessage = "Start cancelled before native recording began."; recoveryDeadline = nil
        healthKitState = saveToHealth ? "notSaved" : "notRequested"
        let completion = startCompletion; startCompletion = nil
        updateMetadata(); try persist(); emit(); completion?(.failure(CycError.invalid(recoveryMessage!)))
        return state()
      }
      if pendingAction != "stop" { _ = try makeCommand("stop") }
      pendingAction = "stop"; recoveryState = "checking"; recoveryDeadline = clock + 15
      recoveryMessage = "Stop requested from the original phone owner. Its start or end has not been confirmed."
      try persist(); emit()
      if !WorkoutRecoveryPlanner.waitsForStart(phase: phase, startCompletionPending: startCompletion != nil || phoneStartInFlight) { restore(forceRecovery: true) }
      return state()
    }
    if useWatch, timeline == nil, ["preparing", "recoverable"].contains(phase) {
      let command = try makeCommand("stop")
      guard try connectivity?.enqueue(command.packet) == true else { throw CycError.invalid("Watch transport is busy. Retry the stop request.") }
      pendingAction = "stop"; recoveryState = "checking"
      recoveryMessage = "Stop requested from the original owner. No recording completion has been confirmed."
      startDeadline = nil; phase = "recoverable"; try persist(); try queryOwner(); emit(); return state()
    }
    if phase == "completed", !useWatch, !["saved", "notRequested"].contains(healthKitState) { finishPhoneStop(); return state() }
    guard ["running", "paused", "recoverable", "finishing"].contains(phase) else { throw CycError.invalid("There is no active workout to stop.") }
    if phase == "finishing" { return state() }
    let boundary = CycEngine.shared.queue.sync { (date: Date(), uptime: ProcessInfo.processInfo.systemUptime) }
    let existingStop = !useWatch && !saveToHealth && pendingOwnerCommand?.endsWorkout == true ? pendingOwnerCommand : nil
    let stopDate = try existingStop.map { try WorkoutCoding.date($0.requestedAt) } ?? boundary.date
    var boundaryElapsed = existingStop?.options["cutoffElapsedSeconds"]?.number ?? (elapsedBase + (elapsedOrigin.map { max(0, boundary.uptime - $0) } ?? 0))
    let boundaryTimer = existingStop?.options["timerSeconds"]?.number ?? (timerBase + (runningOrigin.map { max(0, boundary.uptime - $0) } ?? 0))
    let previousTimeline = timeline
    let stopMonotonic = timeline.map { $0.monotonicOrigin + boundaryElapsed }
    timeline?.stopMonotonic = stopMonotonic
    timeline?.stopUTC = WorkoutCoding.timestamp(stopDate)
    do { try drainCapture(); try flushCapture() }
    catch { timeline = previousTimeline; storageFailed(error); throw error }
    if !useWatch, !saveToHealth, phase == "recoverable", let started {
      if let pending = pendingOwnerCommand, !pending.endsWorkout, let id, let control {
        _ = try control.observe(workoutID: id, owner: "phone", phase: "paused", at: stopDate, health: "notRequested",
          command: pending, failure: "The uncommitted local action was cancelled by Finish")
        pendingOwnerCommand = nil; pendingAction = nil
      }
      elapsedBase = max(elapsedBase, stopDate.timeIntervalSince(started)); elapsedOrigin = nil
      boundaryElapsed = elapsedBase
      timeline = WorkoutTimelineAnchor(epoch: CycCaptureClock.processEpoch, monotonicOrigin: clock - elapsedBase,
        startedAt: WorkoutCoding.timestamp(started), uncertainty: "Recording interrupted across process restart; UTC maps the acquisition gap")
    }
    let stopCommand: WorkoutCommand
    do {
      stopCommand = try existingStop ?? makeCommand(discard ? "discard" : "stop", at: stopDate,
        cutoffElapsed: boundaryElapsed, cutoffTimer: boundaryTimer)
    } catch { timeline = previousTimeline; throw error }
    discardRequested = discardRequested || discard || stopCommand.action == "discard"
    flushTelemetry()
    stopRequestedAt = try WorkoutCoding.date(stopCommand.requestedAt)
    if !useWatch, !saveToHealth {
      elapsedBase = stopCommand.options["cutoffElapsedSeconds"]?.number ?? elapsed
      timerBase = stopCommand.options["timerSeconds"]?.number ?? timerSeconds
      elapsedOrigin = nil; runningOrigin = nil
      if let origin = timeline?.monotonicOrigin { timeline?.stopMonotonic = origin + elapsedBase }
    }
    if useWatch || saveToHealth { timeline?.stopMonotonic = boundary.uptime }
    timeline?.stopUTC = stopCommand.requestedAt
    if useWatch || saveToHealth, let id { try archive?.update(id: id, stopElapsedSeconds: boundaryElapsed) }
    timerBase = boundaryTimer; runningOrigin = nil
    elapsedBase = boundaryElapsed; elapsedOrigin = nil
    phase = discardRequested ? "finishing" : "completed"; pendingAction = discardRequested ? "discard" : nil; location.stop(); previousLocation = nil
    if !useWatch, saveToHealth { record(kind: "lifecycle", source: "phone", date: stopRequestedAt!, payload: ["action": "stop"], eventID: stopCommand.id) }
    if useWatch || saveToHealth, let id { _ = try archive?.finish(id: id, endedAt: stopRequestedAt!, finalPhase: "finishing") }
    if useWatch { warn("Waiting for the final Watch archive and HealthKit outcome. FIT export becomes available after synchronization.") }
    if useWatch || saveToHealth { updateMetadata(); try persist(); emit() }
    if useWatch {
      try connectivity?.enqueue(stopCommand.packet)
      try sealPhoneContributor()
      try persist()
    }
    else if pendingOwnerCommand?.endsWorkout == true { finishPhoneStop() }
    // A stop behind an in-flight lap/pause is already durable; its result callback drains it.

    return state()
  }

  private func transition(_ next: String, date: Date, recordAction: String?) {
    if !useWatch, !saveToHealth, let id, let archive, let control {
      let activeBefore = timerSeconds, elapsedBefore = elapsed, transitionClock = clock, phaseBefore = phase
      do {
        try commitLocalInterruption()
        if next == "paused" { timerBase = activeBefore; runningOrigin = nil }
        if next == "running" { runningOrigin = transitionClock }
        phase = next
        _ = try WorkoutLocalOwner.observe(id: id, phase: next, at: date, elapsed: elapsedBefore, command: pendingOwnerCommand,
          cutoff: nil, discarded: false, archive: archive, control: control, checkpoint: {
            try archive.update(id: id, phase: next, healthKitState: "notRequested")
            try self.persist()
          })
        pendingOwnerCommand = nil; pendingAction = nil
        flushStorage()
      } catch {
        timerBase = activeBefore; runningOrigin = nil; elapsedBase = elapsedBefore; elapsedOrigin = nil
        if let timeline { localInterruption = (id, timeline.epoch, elapsedBefore) }
        if WorkoutStorageFaultPolicy.freezesRide(error) { phase = "recoverable"; pendingAction = pendingOwnerCommand?.action } else { phase = phaseBefore }
        storageFailed(error); return
      }
      if next == "paused" { previousLocation = nil; gpsDistance.reset() }
      if recordGPS { if next == "paused" { location.stop() } else if next == "running" { location.start() } }
      let identity = effectIdentity(id)
      queue.async { if self.isCurrent(identity) { self.drainDeferredStop() } }
      emit(); return
    }
    if next == "paused" { timerBase = timerSeconds; runningOrigin = nil; previousLocation = nil; gpsDistance.reset() }
    if next == "running" { runningOrigin = clock }
    if !useWatch && recordGPS { if next == "paused" { location.stop() } else if next == "running" { location.start() } }
    do { try commitPhoneOwner(next, at: date) } catch { storageFailed(error); return }
    phase = next; if !useWatch { pendingAction = nil }
    if let action = recordAction, saveToHealth || useWatch { record(kind: "lifecycle", source: useWatch ? "watch" : "phone", date: date, payload: ["action": action]) }
    updateMetadata(); try? persist(); emit()
  }

  func admitCyc(_ frame: PowerLogCaptureFrame) throws {
    if try captureInbox.admit(frame) {
      queue.async { [weak self] in
        guard let self else { return }
        do { try self.drainCapture() } catch { self.storageFailed(error) }
      }
    }
  }

  private func mappedTelemetry(_ frame: PowerLogCaptureFrame) throws -> WorkoutEvent? {
    let sample = frame.sample
    guard let destination = frame.ride else { return nil }
    guard destination.id != discardedCaptureID else { return nil }
    var admittedTimeline = destination.timeline
    if destination.id == id, destination.generation == sessionGeneration, let timeline {
      admittedTimeline.stopMonotonic = timeline.stopMonotonic
      admittedTimeline.stopUTC = timeline.stopUTC
    }
    guard let event = try frame.mappedRide(timeline: admittedTimeline) else { return nil }
    if let uncertainty = event.payload["timelineMappingUncertainty"]?.string { warn(uncertainty) }
    lastCyc = try event.date
    metrics["riderPowerW"] = sample["humanPowerW"] as? Double; metrics["cadenceRpm"] = sample["cadenceRpm"] as? Double
    return event
  }

  private func drainCapture() throws {
    if let fault = captureInbox.fault, fault.id != observedCaptureFault {
      observedCaptureFault = fault.id
      storageFailed(PowerLogStorageError.invalid(fault.message), captureInterrupted: true)
    }
    if captureBatch.records.count >= PowerLogCaptureBatch.targetFrames { try flushCapture() }
    let admittedCount = captureInbox.count
    for _ in 0..<admittedCount {
      guard captureBatch.available > 0, let frame = captureInbox.first else { break }
      let event = try mappedTelemetry(frame)
      try captureBatch.append(PowerLogCaptureRecord(frame: frame, ride: event), at: clock)
      _ = captureInbox.take(upTo: 1)
      if captureBatch.records.count >= PowerLogCaptureBatch.targetFrames { try flushCapture() }
    }
  }

  private func captureBarrier() throws {
    CycEngine.shared.queue.sync {}
    try drainCapture()
    try flushCapture()
  }

  private func flushCapture() throws {
    guard !captureBatch.isEmpty || captureInbox.fault != nil else { return }
    guard let archive else { throw CycError.invalid("Workout storage unavailable") }
    let events = try captureBatch.flush(store: archive.store) { id in
      try self.transfer?.register(id: id, producer: "cyc")
    }
    if let fault = captureInbox.fault {
      try fault.persist(archive: archive)
      if fault.workoutID == id, let id, try !archive.store.isWorkoutDeleted(id: id) {
        warn(fault.message); try persist()
      }
      captureInbox.acknowledgeFault(fault.id)
    }
    let cyc = CycEngine.shared
    cyc.queue.async { cyc.captureResult(nil) }
    if !useWatch && saveToHealth {
      let current = events.filter { $0.workoutId == id }
      if !current.isEmpty { health.addTelemetry(current) }
    }
  }

  private func flushTelemetry() {
    do {
      try drainCapture(); try flushCapture()
      if let id, !useWatch, !saveToHealth, stopRequestedAt != nil {
        try sealPhone(id: id)
      }
    } catch { storageFailed(error) }
  }

  private func replayUnforwardedTelemetry() throws {
    guard let archive else { return }
    if !useWatch, saveToHealth, let id, health.canReplayTelemetry {
      let pending = try WorkoutHealthInsertionJournal(archive: archive).pending(id: id)
      if !pending.isEmpty { health.addTelemetry(pending) }
    }
    // A damaged historical source cannot prevent the current phone owner's Health replay.
    let forwarder = WorkoutTelemetryForwarder(archive: archive)
    if try forwarder.stageNextPending() { forwardingRefreshNeeded = true }
    if forwarder.deferredSource { warn("An older ride has missing controller samples. Its originals are retained for recovery; other rides can still sync.") }
    if forwardingRefreshNeeded { try connectivity?.refreshOutbox(); forwardingRefreshNeeded = false }
  }

  private func consumeLocation(_ value: CLLocation) {
    guard phase == "running", recordGPS, !useWatch, let started else { return }
    let seconds = value.timestamp.timeIntervalSince(started)
    guard seconds >= 0, previousLocation.map({ value.timestamp > $0.timestamp }) ?? true else {
      gpsBarrier = true; gpsDistance.reset(); return
    }
    let fix = WorkoutGPSFix(time: seconds, latitude: value.coordinate.latitude, longitude: value.coordinate.longitude,
      horizontalAccuracy: value.horizontalAccuracy, speed: value.speed, speedAccuracy: value.speedAccuracy,
      identity: "live", timestamp: WorkoutCoding.timestamp(value.timestamp), barrier: gpsBarrier)
    var next = gpsDistance
    let interval = next.append(fix)
    var payload: [String: Any] = ["latitude": value.coordinate.latitude, "longitude": value.coordinate.longitude,
      "horizontalAccuracyM": value.horizontalAccuracy, "verticalAccuracyM": value.verticalAccuracy,
      "speedAccuracyMps": value.speedAccuracy, "courseAccuracyDegrees": value.courseAccuracy,
      "distanceBarrier": gpsBarrier]
    if value.verticalAccuracy >= 0 { payload["altitudeMeters"] = value.altitude }
    if value.speed >= 0 { payload["speedMps"] = value.speed }
    if value.course >= 0 { payload["courseDegrees"] = value.course }
    guard record(kind: "location", source: "phone", date: value.timestamp, payload: payload) else { gpsBarrier = true; gpsDistance.reset(); return }
    gpsDistance = next; gpsBarrier = false
    if saveToHealth, fix.valid {
      health.addLocation(value, distance: interval?.meters ?? 0,
        from: interval.map { started.addingTimeInterval($0.startSeconds) })
    }
    previousLocation = value; lastGPS = value.timestamp; gpsAccuracy = value.horizontalAccuracy
    metrics["speedMps"] = WorkoutDistancePolicy.validSpeed(value.speed, accuracy: value.speedAccuracy)
  }

  private func refreshDistance() {
    guard !background, !sinks.isEmpty, !distanceInFlight, let id, let archive,
      let revision = try? archive.revision(id: id),
      distanceSnapshot?.id != id || distanceSnapshot?.revision != revision else { return }
    let identity = effectIdentity(id)
    distanceInFlight = true
    distanceQueue.async {
      let result = try? WorkoutDistanceStore(store: archive.store).snapshot(id: id, revision: revision, selection: "auto")
      self.queue.async {
        self.distanceInFlight = false
        guard self.isCurrent(identity), let result else { return }
        self.distanceSnapshot = result
        self.metrics["distanceMeters"] = result.totalMeters
        self.emit()
      }
    }
  }

  @discardableResult
  private func record(kind: String, source: String, date: Date, payload: [String: Any], eventID: String = UUID().uuidString) -> Bool {
    guard let id else { return false }
    if (try? archive?.hasEvent(id: id, eventID: eventID)) == true { return true }
    var dictionary: [String: Any] = ["schemaVersion": 1, "eventId": eventID, "workoutId": id,
      "kind": kind, "source": source, "timestamp": CycProtocol.timestamp(date), "payload": payload]
    if kind == "lifecycle" { dictionary["elapsedSeconds"] = elapsed }
    do { try archive?.append(WorkoutEvent(dictionary: dictionary)); try transfer?.register(id: id, producer: source); return true }
    catch { storageFailed(error); return false }
  }
  private func updateMetrics(_ values: [String: Any], date: Date) {
    for key in ["heartRateBpm", "activeEnergyKcal", "basalEnergyKcal"] {
      if let value = values[key] as? Double, value.isFinite, value >= 0 {
        if key == "heartRateBpm", date < (lastHeart ?? .distantPast) { continue }
        if ["activeEnergyKcal", "basalEnergyKcal", "distanceMeters"].contains(key) { metrics[key] = max(metrics[key] ?? 0, value) }
        else { metrics[key] = value }
      }
    }
    if values["heartRateBpm"] != nil, date >= (lastHeart ?? .distantPast) { lastHeart = date }
  }

  private func envelope(kind: String, fields: [String: Any]) -> [String: Any] {
    var packet = fields; packet["schemaVersion"] = 1; packet["kind"] = kind
    packet["workoutId"] = id ?? ""; packet["messageId"] = UUID().uuidString.lowercased()
    return packet
  }
  private func applyRemoteResult(_ result: WorkoutCommandResult, acknowledgedID: String, workoutID: String) throws {
    guard let control, result.commandID == acknowledgedID,
      let command = try control.remoteCommand(id: acknowledgedID, workoutID: workoutID) else { return }
    if let retry = try control.retryForAcknowledgement(result, acknowledgedID: acknowledgedID, workoutID: workoutID) {
      _ = try connectivity?.enqueue(retry.packet)
    }
    guard result.isTerminal else { return }
    let cleared = try control.completeRemote(result, acknowledgedID: acknowledgedID, workoutID: workoutID)
    connectivity?.acknowledge(acknowledgedID)
    guard workoutID == id, cleared, pendingRemoteAction?.commandID == command.id else { return }
    if command.endsWorkout { activityStopOutcome = result.outcome }
    pendingRemoteAction = nil; pendingAction = nil; recoveryState = "resolved"; recoveryMessage = nil
    if result.outcome != "applied" {
      recoveryMessage = result.reason ?? "The original owner rejected the action."; warn(recoveryMessage!)
      if timeline == nil, ["preparing", "recoverable"].contains(phase) {
        phase = "failed"; startDeadline = nil; connectivity?.clearPendingStart()
        let completion = startCompletion; startCompletion = nil
        completion?(.failure(CycError.invalid(recoveryMessage!)))
      }
    }
    updateMetadata(); try persist(); emit()
  }
  private func queryOwner() throws {
    guard useWatch, let id else { return }
    if let old = recoveryQuery { connectivity?.acknowledge(old.id, discarded: true) }
    let query = try WorkoutOwnerQuery(workoutID: id, pendingCommandID: pendingRemoteAction?.commandID)
    guard try connectivity?.enqueue(query.packet) == true else { throw CycError.invalid("Owner query is waiting for transport capacity. Retry recovery.") }
    recoveryQuery = query; recoveryDeadline = clock + 15; recoveryState = "checking"
    recoveryMessage = "Checking the original Watch owner. Recording and synchronization remain separate."
  }
  private func receiveOwnerReply(_ packet: [String: Any]) {
    guard let query = recoveryQuery, query.matches(packet) else { return }
    connectivity?.acknowledge(query.id); recoveryQuery = nil; recoveryDeadline = nil
    do {
      if let raw = packet["commandResult"] as? [String: Any] {
        let result = try JSONDecoder().decode(WorkoutCommandResult.self, from: JSONSerialization.data(withJSONObject: raw))
        if result.commandID == query.pendingCommandID { try applyRemoteResult(result, acknowledgedID: result.commandID, workoutID: query.workoutID) }
      }
      if let raw = packet["ownerSnapshot"] as? [String: Any],
        packet["ownerState"] as? String == "known" || WorkoutOwnerPhase.terminal(raw["phase"] as? String ?? "") { try receiveStatus(packet) }
      if phase == "failed", pendingAction == nil, recoveryMessage != nil {
        recoveryState = "resolved" // Keep the explicit terminal cancellation/rejection reason.
      } else if pendingAction != nil || packet["ownerState"] as? String == "unresolved" {
        recoveryState = "unresolved"
        recoveryMessage = "The original owner has not confirmed this action. Open Power Log on Watch, then check recovery or request stop again."
      } else { recoveryState = "resolved"; recoveryMessage = nil }
      try persist(); emit()
    } catch { recoveryState = "unresolved"; recoveryMessage = error.localizedDescription; emit() }
  }

  func recover(_ requestedID: String, completion: @escaping (Result<[String: Any], Error>) -> Void) {
    do {
      let targetID = try WorkoutCoding.id(requestedID)
      guard let archive else { throw CycError.invalid("The original workout is unavailable.") }
      let metadata = try archive.metadata(id: targetID)
      if !metadata.watchEnabled, !metadata.savesToHealth, metadata.endedAt != nil, metadata.healthKitState == "notRequested" {
        try sealPhone(id: targetID) { result in completion(result.map { self.state() }) }
        return
      }
      // A historical saved phone ride may be repaired by ID; it never becomes the current owner.
      if metadata.phase == "completed", !metadata.watchEnabled, metadata.savesToHealth, metadata.healthKitState == "saved" {
        let selected = id == targetID
        let identity = selected ? effectIdentity(targetID) : nil
        if selected { recoveryState = "checking"; recoveryMessage = "Checking retained samples in the original saved workout."; recoveryDeadline = clock + 15 }
        health.repairUnavailableTelemetry(id: targetID) { result in self.queue.async {
          let publish: (Result<Void, Error>) -> Void = { result in
            if identity == nil { completion(result.map { self.state() }); self.emit(); return }
            if let identity, self.isCurrent(identity) {
              self.recoveryDeadline = nil
              switch result {
              case .success: self.recoveryState = "resolved"; self.recoveryMessage = nil
              case .failure(let failure): self.recoveryState = "unresolved"; self.recoveryMessage = failure.localizedDescription
              }
              self.emit()
            }
          }
          switch result {
          case .success:
            do { try self.sealPhone(id: targetID, completion: publish) } catch { publish(.failure(error)) }
          case .failure(let failure): publish(.failure(failure))
          }
        } }
        emit(); if selected { completion(.success(state())) }; return
      }
      try requireCurrent(targetID)
      guard id != nil else { throw CycError.invalid("Choose the original workout to recover.") }
      recoveryState = "checking"; recoveryMessage = "Checking the original workout owner."; recoveryDeadline = clock + 15
      if useWatch { try queryOwner(); emit(); completion(.success(state())); return }
      if WorkoutRecoveryPlanner.waitsForStart(phase: phase, startCompletionPending: startCompletion != nil || phoneStartInFlight) {
        recoveryMessage = "The original phone start is still pending. You can request stop while it finishes."; emit(); completion(.success(state())); return
      }
      if phoneFinalizationInFlight { recoveryMessage = "The original phone finalization is still running."; emit(); completion(.success(state())); return }
      try commitLocalInterruption(); try persist(); restore(forceRecovery: true)
      emit(); completion(.success(state()))
    } catch { completion(.failure(error)) }
  }
  private func receive(_ data: Data) {
    guard data.count <= 100_000, let packet = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
      packet["schemaVersion"] as? Int == 1, let kind = packet["kind"] as? String else { return }
    if kind == "deleteWorkoutAck", let targetID = packet["workoutId"] as? String,
      let messageID = packet["acknowledgedMessageId"] as? String,
      let outcome = packet["outcome"] as? String, ["deleted", "deferred"].contains(outcome) {
      do {
        if try archive?.store.acknowledgeWorkoutDeletion(id: targetID, messageID: messageID, deleted: outcome == "deleted") == true {
          connectivity?.acknowledge(messageID, allowDeletion: true)
          emit()
        }
      } catch { warn("Deletion receipt could not be saved; delivery will retry."); emit() }
      return
    }
    if let targetID = packet["workoutId"] as? String, UUID(uuidString: targetID) != nil {
      do { if try archive?.store.isWorkoutDeleted(id: targetID) == true { return } }
      catch { storageFailed(error); return }
    }
    if kind == "ownerReply" { receiveOwnerReply(packet); return }
    if kind == "ack", let ack = packet["acknowledgedMessageId"] as? String {
      if let raw = packet["commandResult"] as? [String: Any], let workoutID = packet["workoutId"] as? String,
        let bytes = try? JSONSerialization.data(withJSONObject: raw),
        let result = try? JSONDecoder().decode(WorkoutCommandResult.self, from: bytes) {
        do { try applyRemoteResult(result, acknowledgedID: ack, workoutID: workoutID) }
        catch { storageFailed(error) }
      } else { connectivity?.acknowledge(ack) }
      return
    }
    if kind == "seal" { receiveSeal(packet); return }
    // A freshly launched Watch does not know the workout ID until it receives pendingStart.
    if WorkoutLaunchReadiness.shouldResendStart(kind: kind, remotePhase: packet["phase"] as? String, localPhase: phase, watch: useWatch) {
      do { try receiveStatus(packet) } catch { warn("The Watch start command could not be persisted.") }
      return
    }
    guard let workoutID = packet["workoutId"] as? String, let normalizedID = try? WorkoutCoding.id(workoutID),
      let messageID = packet["messageId"] as? String, UUID(uuidString: messageID) != nil else { return }
    do {
      var adoptedOwner: WorkoutOwnerSnapshot?
      if kind == "status", let raw = packet["ownerSnapshot"] as? [String: Any], let archive, let control {
        let snapshot = try JSONDecoder().decode(WorkoutOwnerSnapshot.self, from: JSONSerialization.data(withJSONObject: raw)).normalized
        if snapshot.workoutID == normalizedID, snapshot.owner == "watch", snapshot.phase == "completed", snapshot.healthOutcome == "discarded" {
          guard normalizedID != id || useWatch,
            (try? archive.metadata(id: normalizedID))?.watchEnabled != false else { return }
          let previous = try control.snapshot(workoutID: normalizedID)
          guard try previous == snapshot || control.accept(snapshot: snapshot) else { return }
          if normalizedID == id {
            discardedCaptureID = normalizedID
            try captureBatch.discardRide(normalizedID)
          }
          _ = try archive.store.markWorkoutDeleted(id: normalizedID, watchRequired: true)
          clearDeletedSelection(normalizedID)
          connectivity?.discardWorkoutPackets(workoutId: normalizedID)
          advanceDeletions(); emit(); return
        }
      }
      if kind == "status", normalizedID != id, ["idle", "completed", "failed"].contains(phase),
        !phoneFinalizationInFlight, health.sessionSnapshot?.type != .primary,
        let raw = packet["ownerSnapshot"] as? [String: Any],
        let text = packet["startedAt"] as? String, let actualStart = WorkoutHealth.date(text), let archive, let control {
        let incoming = try JSONDecoder().decode(WorkoutOwnerSnapshot.self, from: JSONSerialization.data(withJSONObject: raw))
        try captureBarrier()
        guard telemetryBatch.isEmpty else { throw CycError.invalid("Previous workout samples must commit before adopting another ride") }
        guard incoming.workoutID == normalizedID,
          let adopted = try WorkoutOwnerAdoption.activate(incoming, archive: archive, control: control,
            startedAt: actualStart, indoor: packet["indoor"] as? Bool ?? false, now: Date(), uptime: clock,
            epoch: CycCaptureClock.processEpoch, reportedElapsed: packet["elapsedSeconds"] as? Double,
            reportedTimer: packet["timerSeconds"] as? Double, saveToHealth: packet["saveToHealth"] as? Bool ?? true, recordGPS: packet["recordGPS"] as? Bool) else { return }
        // This state is already active. Neither an existing ride nor a newly adopted Watch ride starts again.
        id = normalizedID; started = try WorkoutCoding.date(adopted.metadata.startedAt); useWatch = true; indoor = adopted.metadata.indoor
        saveToHealth = adopted.metadata.savesToHealth; recordGPS = adopted.metadata.recordsGPS
        admittedSampleHz = CycEngine.shared.queue.sync {
          CycEngine.shared.setWorkoutSamplingOwner(normalizedID, rate: CycEngine.shared.currentSampleRate)
          return CycEngine.shared.currentSampleRate
        }
        sessionGeneration = UUID(); localInterruption = nil; recoveryState = "idle"; recoveryMessage = nil
        phase = adopted.phase; metrics = [:]; warnings = adopted.metadata.warnings; error = nil
        elapsedBase = adopted.elapsed; timerBase = adopted.active; elapsedOrigin = clock; runningOrigin = phase == "running" ? clock : nil
        stopRequestedAt = nil; timeline = adopted.timeline
        startDeadline = nil; startCommand = nil; pendingOwnerCommand = nil; pendingAction = nil; pendingRemoteAction = nil
        healthKitState = incoming.healthOutcome; healthKitUUID = incoming.healthWorkoutID
        activityOwnerPhase = incoming.phase; activityStopOutcome = nil
        adoptedOwner = incoming
      }
      if kind == "events", let dictionaries = packet["events"] as? [[String: Any]], dictionaries.count <= 128 {
        let events = try dictionaries.map(WorkoutEvent.init(dictionary:))
        guard let firstText = packet["firstSequence"] as? String, let first = Int64(firstText),
          events.allSatisfy({ $0.workoutId == normalizedID && $0.source == "watch" && ["health", "location", "lifecycle"].contains($0.kind) }) else {
          throw CycError.invalid("Watch live packet has no canonical source sequence")
        }
        try transfer?.receiveLive(events, producer: "watch", firstSequence: first)
        if normalizedID == id { for event in events { consumeWatchEvent(event) } }
      } else if kind == "status", normalizedID == id { try receiveStatus(packet, acceptedSnapshot: adoptedOwner) }
      else { return }
      try archive?.flush()
      connectivity?.sendEphemeral(["schemaVersion": 1, "kind": "ack", "workoutId": normalizedID,
        "messageId": UUID().uuidString, "acknowledgedMessageId": messageID])
      emit()
    } catch { warn("Watch data could not be archived: \(error.localizedDescription)"); emit() }
  }

  private func consumeWatchEvent(_ event: WorkoutEvent) {
    let date = (try? event.date) ?? Date()
    let values = event.payload.mapValues(\.any)
    if event.kind == "health" { updateMetrics(values, date: date) }
    if event.kind == "location", date >= (lastGPS ?? .distantPast) { lastGPS = date; gpsStatus = "receiving"; gpsAccuracy = values["horizontalAccuracyM"] as? Double; metrics["speedMps"] = WorkoutDistancePolicy.validSpeed(values["speedMps"] as? Double, accuracy: values["speedAccuracyMps"] as? Double) }
  }

  private func receiveStatus(_ packet: [String: Any], acceptedSnapshot: WorkoutOwnerSnapshot? = nil) throws {
    guard useWatch, var watchPhase = packet["phase"] as? String else { return }
    watchPhase = WorkoutOwnerPhase.canonical(watchPhase)
    if let id, let record = try archive?.metadata(id: id) { try WorkoutRecordingPolicy.requireOptions(record, saveToHealth: packet["saveToHealth"] as? Bool ?? true, recordGPS: packet["recordGPS"] as? Bool ?? !(packet["indoor"] as? Bool ?? indoor)) }
    var acceptedOwner: WorkoutOwnerSnapshot?
    if watchPhase != "ready" {
      guard let id, let raw = packet["ownerSnapshot"] as? [String: Any], let control else { return }
      let snapshot = try JSONDecoder().decode(WorkoutOwnerSnapshot.self, from: JSONSerialization.data(withJSONObject: raw)).normalized
      guard snapshot.workoutID == id, snapshot.owner == "watch" else { return }
      guard WorkoutPhoneTerminalProjection.acceptsStatus(snapshot, after: try transfer?.currentSeal(id: id)) else { return }
      if acceptedSnapshot == nil {
        if try control.snapshot(workoutID: id) != snapshot { guard try control.accept(snapshot: snapshot) else { return }; flushStorage() }
      }
      else { guard acceptedSnapshot == snapshot else { return } }
      acceptedOwner = snapshot; watchPhase = snapshot.phase; activityOwnerPhase = snapshot.phase
    }
    var confirmedPreparation = false
    if let id, let archive, let confirmed = try WorkoutPhoneStartProjection.confirmOwnerPhase(archive: archive, id: id,
      localPhase: timeline == nil && phase == "recoverable" ? "preparing" : phase, ownerPhase: watchPhase, startedAt: (packet["startedAt"] as? String).flatMap(WorkoutHealth.date),
      now: Date(), uptime: clock, epoch: CycCaptureClock.processEpoch) {
      timeline = confirmed; started = try WorkoutCoding.date(confirmed.startedAt); confirmedPreparation = true
      elapsedBase = max(0, clock - confirmed.monotonicOrigin); startDeadline = nil
      connectivity?.clearPendingStart()
    }
    connectivity?.recordDiagnostic(.statusReceived, kind: "status", phase: watchPhase)
    if let owner = acceptedOwner { healthKitState = owner.healthOutcome; healthKitUUID = owner.healthWorkoutID }
    if let status = packet["gpsStatus"] as? String { gpsStatus = String(status.prefix(100)) }
    if let reason = packet["error"] as? String { warn(String(reason.prefix(500))) }
    let date = ((packet["phaseTimestamp"] ?? packet["timestamp"]) as? String).flatMap(WorkoutHealth.date) ?? Date()
    if ["finishing", "completed"].contains(watchPhase), let timeline, let id {
      let cutoff = stopRequestedAt ?? acceptedOwner?.stopCutoff.flatMap(WorkoutHealth.date)
        ?? (packet["endedAt"] as? String).flatMap(WorkoutHealth.date)
        ?? acceptedOwner.flatMap { WorkoutHealth.date($0.effectiveAt) } ?? date
      let closed = try PowerLogCaptureCutoff.owner(timeline, at: cutoff, elapsedSeconds: packet["elapsedSeconds"] as? Double)
      self.timeline = closed; stopRequestedAt = cutoff
      updateCaptureDestination()
      try captureBarrier()
      let seconds = closed.stopMonotonic! - closed.monotonicOrigin
      try archive?.update(id: id, stopElapsedSeconds: seconds)
      elapsedBase = seconds; elapsedOrigin = nil
    }
    if watchPhase == "ready" && phase == "preparing" {
      let command = try startCommand ?? makeCommand("start")
      startCommand = command
      try connectivity?.setPendingStart(command.packet)
      try connectivity?.enqueue(command.packet)
    }
    if watchPhase == "running" && (phase == "preparing" || confirmedPreparation) {
      if acceptedSnapshot == nil { started = (packet["startedAt"] as? String).flatMap(WorkoutHealth.date) ?? date }
      beginRunning(at: started!, confirmStart: !confirmedPreparation)
      if let active = packet["timerSeconds"] as? Double, active.isFinite, active >= 0 {
        let sentAt = (packet["timestamp"] as? String).flatMap(WorkoutHealth.date) ?? Date()
        timerBase = active + max(0, Date().timeIntervalSince(sentAt)); runningOrigin = clock
      }
    } else if watchPhase == "running" && ["paused", "recoverable"].contains(phase) {
      if elapsedOrigin == nil { elapsedOrigin = clock }
      transition("running", date: date, recordAction: nil)
    } else if watchPhase == "paused" && ["preparing", "running", "recoverable"].contains(phase) {
      if phase == "preparing" || confirmedPreparation { beginRunning(at: started ?? date, confirmStart: !confirmedPreparation) }
      transition("paused", date: date, recordAction: nil)
      if let active = packet["timerSeconds"] as? Double, active.isFinite, active >= 0 { timerBase = active }
    }
    else if watchPhase == "finishing", !["completed", "failed"].contains(phase) {
      stopRequestedAt = stopRequestedAt ?? (packet["endedAt"] as? String).flatMap(WorkoutHealth.date) ?? date
      elapsedBase = elapsed; timerBase = timerSeconds; elapsedOrigin = nil; runningOrigin = nil
      phase = "completed"; pendingAction = nil
      warn("Waiting for the final Watch archive and HealthKit outcome. FIT export becomes available after synchronization.")
    }
    else if watchPhase == "completed" {
      if phase != "completed" {
        stopRequestedAt = stopRequestedAt ?? (packet["endedAt"] as? String).flatMap(WorkoutHealth.date) ?? date
        elapsedBase = elapsed; timerBase = timerSeconds; elapsedOrigin = nil; runningOrigin = nil
        phase = "completed"; pendingAction = nil
        warn("Waiting for the final Watch archive and HealthKit outcome. FIT export becomes available after synchronization.")
      }
      reconcileWatchCompletion()
    }
    else if watchPhase == "failed" {
      if timeline == nil, ["preparing", "recoverable"].contains(phase) {
        phase = "failed"; pendingAction = nil; startDeadline = nil; recoveryDeadline = nil
        recoveryState = "resolved"; recoveryMessage = packet["error"] as? String ?? "The original Watch owner reported that this start failed."
        connectivity?.clearPendingStart()
        let completion = startCompletion; startCompletion = nil; completion?(.failure(CycError.invalid(recoveryMessage!)))
      }
      else { healthKitState = saveToHealth ? "failed" : "notRequested"; warn("Apple Watch reported a workout failure. Raw native data remains available.") }
    }
    if phase == "completed", let active = packet["timerSeconds"] as? Double, active.isFinite, active >= 0 { timerBase = active }
    updateMetadata(); try persist()
    if confirmedPreparation, phase == "completed" {
      let completion = startCompletion; startCompletion = nil; completion?(.success(state()))
    }
  }

  private func sealPhoneContributor() throws {
    guard useWatch, stopRequestedAt != nil, let id, let archive, let transfer,
      try transfer.roster(id: id).contains("cyc"), !contributorJobs.contains(id) else { return }
    try captureBarrier()
    contributorJobs.insert(id)
    fileQueue.async {
      do {
        let source = try transfer.source(id: id, producer: "cyc")
        let value = try WorkoutCoding.encoder().encode(source), key = id + ":cyc"
        let previous = try archive.store.read { db in try db.get(namespace: "submitted-source-seal", key: key) }
        self.queue.async {
          defer { self.contributorJobs.remove(id) }
          guard previous != value else { return }
          do {
            let packet: [String: Any] = ["schemaVersion": 1, "kind": "sourceSeal", "workoutId": id,
              "messageId": UUID().uuidString.lowercased(), "sourceSeal": WorkoutCoding.dictionary(source)]
            if try self.connectivity?.enqueue(packet) == true {
              try archive.store.transaction { db in try db.put(namespace: "submitted-source-seal", key: key, value: value) }
            }
          } catch { self.storageFailed(error) }
        }
      } catch { self.queue.async { self.contributorJobs.remove(id); self.storageFailed(error) } }
    }
  }

  private func sendStopIfDrained() { /* Stop is durably enqueued at its original cutoff immediately. */ }

  private func reconcileWatchCompletion() {
    guard useWatch, let id else { return }
    if let m = try? archive?.metadata(id: id), m.verifiedSealRevision == m.sealRevision,
      ["complete", "partial"].contains(m.finalizationState ?? "") { return }
    requestVerification(id: id)
  }

  private func requestVerification(id: String, acknowledge: Bool = false) {
    if acknowledge { verificationACKs.insert(id) }
    guard let archive, let transfer else { return }
    guard verificationJobs.insert(id).inserted else { verificationInvalidations.insert(id); return }
    fileQueue.async {
      do {
        let verified = try transfer.verify(id: id)
        let seal = try transfer.currentSeal(id: id)
        self.queue.async {
          defer {
            self.verificationJobs.remove(id)
            if self.verificationInvalidations.remove(id) != nil { self.requestVerification(id: id) }
          }
          guard verified, let seal, let current = try? archive.metadata(id: id),
            current.verifiedSealRevision == seal.sealRevision, current.sealRevision == seal.sealRevision,
            ["complete", "partial"].contains(current.finalizationState ?? "") else { return }
          do {
            if try archive.metadata(id: id).watchSyncState != "received" { try archive.update(id: id, watchSyncState: "received") }
            self.verifiedCompletedRideID = id
            if self.verificationACKs.remove(id) != nil {
              self.connectivity?.sendEphemeral(["schemaVersion": 1, "kind": "sealAck", "workoutId": id,
                "messageId": UUID().uuidString.lowercased(), "sealRevision": String(seal.sealRevision)])
            }
          } catch { self.storageFailed(error) }
          self.emit()
        }
      } catch { self.queue.async { self.verificationJobs.remove(id); self.warn("Archive verification remains pending: \(error.localizedDescription)") } }
    }
  }

  private func sealPhone(id requestedID: String? = nil, completion: ((Result<Void, Error>) -> Void)? = nil) throws {
    guard let targetID = requestedID ?? id, let archive, let transfer, let control else {
      throw CycError.invalid("The original phone workout is unavailable")
    }
    if targetID == id { try captureBarrier() }
    guard targetID != id || telemetryBatch.isEmpty else {
      throw CycError.invalid("Retained ride samples must commit before its final archive can be verified")
    }
    guard phoneSealJobs.count < 8, phoneSealJobs.insert(targetID).inserted else {
      completion?(.failure(CycError.invalid("Local finalization is already running; its originals are retained."))); return
    }
    fileQueue.async {
      let result = Result<Void, Error> { _ = try WorkoutPhoneSealRepair.seal(id: targetID, archive: archive, transfer: transfer, control: control) }
      self.queue.async {
        self.phoneSealJobs.remove(targetID)
        if case .success = result, self.id == targetID, !self.useWatch, !self.saveToHealth {
          do {
            self.pendingOwnerCommand = try control.active(workoutID: targetID)
            if self.pendingOwnerCommand == nil {
              self.localInterruption = nil; self.pendingAction = nil; self.phase = "completed"; self.healthKitState = "notRequested"
              self.health.setLocalOwner(nil)
              try self.persist()
            }
          } catch { self.storageFailed(error) }
        }
        if let completion { completion(result) }
        else if case .failure(let error) = result, self.id == targetID { self.storageFailed(error) }
        self.emit()
      }
    }
  }

  private func receiveSeal(_ packet: [String: Any]) {
    do {
      guard let archive, let transfer, let raw = packet["seal"] as? [String: Any] else { return }
      let incoming = try JSONDecoder().decode(WorkoutSeal.self, from: JSONSerialization.data(withJSONObject: raw))
      if (try? archive.metadata(id: incoming.workoutID)) == nil,
        let start = packet["startedAt"] as? String {
        _ = try archive.create(id: incoming.workoutID, startedAt: WorkoutCoding.date(start), indoor: packet["indoor"] as? Bool ?? false, watchEnabled: true, saveToHealth: incoming.saveToHealth ?? true, recordGPS: incoming.recordGPS)
      }
      guard let projection = try WorkoutPhoneTerminalProjection.accept(archive: archive, transfer: transfer, incoming: incoming,
        startedAt: (packet["startedAt"] as? String).flatMap(WorkoutHealth.date), localPhase: incoming.workoutID == id ? phase : nil,
        timerSeconds: packet["timerSeconds"] as? Double, now: Date(), uptime: clock, epoch: CycCaptureClock.processEpoch) else { return }
      let seal = projection.seal
      if seal.workoutID == id {
        started = try WorkoutCoding.date(projection.startedAt)
        if let anchor = projection.preparationAnchor { timeline = anchor }
        timerBase = projection.timerSeconds ?? timerSeconds
        elapsedBase = projection.elapsedSeconds; elapsedOrigin = nil; runningOrigin = nil
        stopRequestedAt = try WorkoutCoding.date(seal.stopCutoff); phase = "completed"; pendingAction = nil
        healthKitState = seal.healthOutcome; startDeadline = nil; startCommand = nil
        connectivity?.clearPendingStart()
        timeline?.stopUTC = seal.stopCutoff
        if timeline?.stopMonotonic == nil, let origin = timeline?.monotonicOrigin, let seconds = seal.stopElapsedSeconds { timeline?.stopMonotonic = origin + seconds }
        try persist()
        // Resolve once only after the terminal local state is durable. A duplicate
        // can finish a previous interrupted persistence attempt without restarting Health.
        let completion = startCompletion; startCompletion = nil; completion?(.success(state()))
      }
      requestVerification(id: seal.workoutID, acknowledge: true)
      emit()
    } catch { warn("Seal verification remains pending: \(error.localizedDescription)") }
  }

  private func complete(at date: Date) {
    guard let id else { return }
    elapsedBase = elapsed; timerBase = timerSeconds; elapsedOrigin = nil; runningOrigin = nil
    phase = "completed"; pendingAction = nil; location.stop()
    do { _ = try archive?.finish(id: id, endedAt: date, finalPhase: "completed"); updateMetadata(); try persist() }
    catch { storageFailed(error) }
    emit()
  }

  private func tick() {
    let slow = background ? 20 : 4
    if checkpoint % slow == 0 { refreshDistance() }
    if checkpoint % 20 == 0, discardRequested { do { try retryDiscard() } catch { storageFailed(error) } }
    if healthKitState == "discarded", let id {
      do { _ = try deleteWorkout(id) } catch { storageFailed(error) }
    }
    if checkpoint % 20 == 0 {
      advanceDeletions()
      if let archive {
        do {
          let pending = try WorkoutPhoneSealRepair.pendingLocal(archive: archive, afterID: phoneSealCursor)
          for target in pending { try sealPhone(id: target) }
          phoneSealCursor = pending.count == 8 ? pending.last! : ""
        } catch { warn("Local archive finalization will retry: \(error.localizedDescription)") }
      }
    }
    if let deadline = recoveryDeadline, clock >= deadline {
      recoveryDeadline = nil; recoveryState = "unresolved"
      recoveryMessage = useWatch ? "The original Watch has not replied. Open Power Log on Watch, then check recovery or request stop." :
        "The original phone owner has not confirmed recovery yet. Retry; retained data remains available."
      emit()
    }
    if let deadline = startDeadline, clock >= deadline {
      connectivity?.recordDiagnostic(.readinessExpired, phase: phase, elapsedSeconds: startAttemptOrigin.map { max(0, clock - $0) })
      if !useWatch, phoneStartInFlight {
        startDeadline = nil; phase = "recoverable"; recoveryState = "unresolved"
        recoveryMessage = "The original phone start has not confirmed readiness. Retry or request stop; the original native operation is retained."
        let completion = startCompletion; startCompletion = nil
        updateMetadata(); try? persist(); emit(); completion?(.failure(CycError.invalid(recoveryMessage!)))
      } else { startFailed(CycError.invalid("Apple Watch or HealthKit did not confirm readiness within 45 seconds. The start was not confirmed; any pending Watch start will be stopped.")) }
    }
    if !captureInbox.isEmpty || captureInbox.fault != nil || captureBatch.oldestAdmission.map({ clock - $0 >= 1 }) == true { flushTelemetry() }
    if rideInProgress, clock - lastActivityPublish >= 5 { publishActivity() }
    if checkpoint % 4 == 0 {
      do { try replayUnforwardedTelemetry(); if useWatch { try sealPhoneContributor() } } catch { storageFailed(error) }
    }
    sendStopIfDrained(); reconcileWatchCompletion()
    checkpoint += 1
    if checkpoint % 20 == 0, id != nil { do { try persist(); try archive?.flush() } catch { storageFailed(error) } }
    if checkpoint % slow == 0, !["idle", "completed"].contains(phase) { emit() }
  }

  func setBackground(_ value: Bool) {
    guard value != background else { return }
    background = value
    if value {
      do { try captureBarrier() } catch { storageFailed(error) }
    } else { emit() }
  }

  private func warn(_ value: String) { if !warnings.contains(value) { warnings.append(value); if warnings.count > 32 { warnings.removeFirst() } } }
  private func storageFailed(_ failure: Error, captureInterrupted: Bool = false) {
    let cyc = CycEngine.shared
    cyc.queue.async { cyc.captureResult(failure) }
    guard captureInterrupted || WorkoutStorageFaultPolicy.freezesRide(failure) else {
      error = "Workout state was not saved: \(failure.localizedDescription)"; warn(error!); emit(); return
    }
    error = "Workout storage failed: \(failure.localizedDescription)"
    warn(error!); if phase == "running" || (!useWatch && !saveToHealth && phase == "paused") {
      timerBase = timerSeconds; runningOrigin = nil
      if !useWatch, !saveToHealth, let id, let timeline {
        // A local owner stops admitting active time when storage fails. Freeze
        // this checkpoint so later persistence/restart cannot move the pause.
        elapsedBase = elapsed; elapsedOrigin = nil
        localInterruption = (id, timeline.epoch, elapsedBase)
      }
      phase = "recoverable"
    }
    emit()
  }
  private func updateMetadata() {
    guard let id else { return }
    do { try archive?.update(id: id, phase: phase, healthKitState: healthKitState, healthKitUUID: healthKitUUID, warnings: warnings) }
    catch { self.error = "Workout metadata could not be saved." }
  }
  private func persist() throws {
    guard let id else { return }
    let value: [String: Any] = ["id": id, "phase": phase, "startedAt": started.map(CycProtocol.timestamp) as Any? ?? NSNull(),
      "indoor": indoor, "useWatch": useWatch, "saveToHealth": saveToHealth, "recordGPS": recordGPS, "elapsedSeconds": elapsed, "timerSeconds": timerSeconds,
      "sampleHz": admittedSampleHz as Any? ?? NSNull(),
      "healthKitState": healthKitState, "healthKitUUID": healthKitUUID as Any? ?? NSNull(), "warnings": warnings,
      "error": error as Any? ?? NSNull(),
      "discardRequested": discardRequested, "stopRequestedAt": stopRequestedAt.map(CycProtocol.timestamp) as Any? ?? NSNull(),
      "timelineAnchor": timeline.map(WorkoutCoding.dictionary) as Any? ?? NSNull()]
    guard let archive else { throw CycError.invalid("Workout state storage unavailable") }
    try archive.store.transaction(priority: .capture) { db in
      try archive.store.requireWorkoutAvailable(id: id)
      try db.put(namespace: "phone-current", key: "workout", value: JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]))
    }
  }

  private func commitLocalInterruption() throws {
    guard let pending = localInterruption else { return }
    guard pending.id == id, !useWatch, !saveToHealth, let archive else {
      throw CycError.invalid("The interrupted local owner changed before its checkpoint was retained")
    }
    try WorkoutLocalOwner.interrupt(id: pending.id, epoch: pending.epoch, checkpointElapsed: pending.elapsed, archive: archive)
    localInterruption = nil
  }

  private func restore(forceRecovery: Bool = false) {
    guard let data = try? archive?.store.read({ db in try db.get(namespace: "phone-current", key: "workout") }), data.count <= 32_768,
      let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
      let savedID = value["id"] as? String, let normalizedID = try? WorkoutCoding.id(savedID),
      (try? archive?.metadata(id: normalizedID)) != nil else { return }
    sessionGeneration = UUID(); localInterruption = nil
    gpsBarrier = true; gpsDistance = WorkoutGPSDistanceAccumulator(); previousLocation = nil; distanceSnapshot = nil
    let identity = WorkoutEffectIdentity(workoutID: normalizedID, generation: sessionGeneration)
    id = normalizedID; indoor = value["indoor"] as? Bool ?? false; useWatch = value["useWatch"] as? Bool ?? false
    activityOwnerPhase = (try? control?.snapshot(workoutID: normalizedID))?.phase
    activityStopOutcome = (try? control?.confirmedRemoteStop(workoutID: normalizedID))?.outcome
    started = (value["startedAt"] as? String).flatMap(WorkoutHealth.date)
    elapsedBase = value["elapsedSeconds"] as? Double ?? 0; timerBase = value["timerSeconds"] as? Double ?? 0
    healthKitState = value["healthKitState"] as? String ?? "unknown"; healthKitUUID = value["healthKitUUID"] as? String
    warnings = value["warnings"] as? [String] ?? []
    error = value["error"] as? String
    stopRequestedAt = (value["stopRequestedAt"] as? String).flatMap(WorkoutHealth.date)
    pendingRemoteAction = try? control?.pendingRemote(workoutID: normalizedID)
    if let raw = value["timelineAnchor"] as? [String: Any], let bytes = try? JSONSerialization.data(withJSONObject: raw) { timeline = try? JSONDecoder().decode(WorkoutTimelineAnchor.self, from: bytes) }
    let oldPhase = value["phase"] as? String ?? "recoverable"
    do { try control?.repairTerminalSlot(workoutID: normalizedID); pendingOwnerCommand = try control?.active(workoutID: normalizedID) }
    catch { storageFailed(error); return }
    discardRequested = value["discardRequested"] as? Bool == true || pendingOwnerCommand?.action == "discard" || pendingRemoteAction?.action == "discard"
    let catalog = try? archive?.metadata(id: normalizedID)
    if let rate = value["sampleHz"] as? Double, rate.isFinite, (1...8).contains(rate) { admittedSampleHz = rate }
    else { admittedSampleHz = nil }
    saveToHealth = catalog?.savesToHealth ?? true; recordGPS = catalog?.recordsGPS ?? !indoor
    if !saveToHealth, !discardRequested { healthKitState = "notRequested"; healthKitUUID = nil }
    if !useWatch, !saveToHealth, let archive {
      let interrupted = stopRequestedAt == nil && ["running", "recoverable"].contains(oldPhase)
      if interrupted, let timeline { localInterruption = (normalizedID, timeline.epoch, elapsedBase) }
      do {
        let restored = try WorkoutLocalOwner.restore(id: normalizedID, epoch: timeline?.epoch, checkpointElapsed: elapsedBase,
          needsInterruption: interrupted, archive: archive, pendingCommand: pendingOwnerCommand)
        if let cutoff = restored.cutoff {
          stopRequestedAt = cutoff; elapsedBase = restored.elapsed; elapsedOrigin = nil; runningOrigin = nil
          if let timer = restored.timer { timerBase = timer }
        }
        localInterruption = nil
      } catch { storageFailed(error); phase = "recoverable"; return }
    }
    if !useWatch, !saveToHealth, timeline == nil, stopRequestedAt == nil, [nil, "start"].contains(pendingOwnerCommand?.action) {
      // Local Start has no external effect. Its missing atomic checkpoint means
      // it never began; settle the retained intent without inventing active time.
      do { phase = "failed"; try commitPhoneOwner("failed", at: Date(), failure: "Local Start did not commit before interruption") }
      catch { phase = "recoverable"; storageFailed(error) }
      emit(); return
    }
    let finalized = catalog?.sealVerified == true &&
      ["complete", "partial"].contains(catalog?.finalizationState ?? "")
    if !forceRecovery, timeline == nil, stopRequestedAt == nil, pendingRemoteAction == nil {
      do {
        let terminalReason = try useWatch ? control?.rejectedRemoteWithoutOwner(workoutID: normalizedID) : control?.cancelledWithoutOwner(workoutID: normalizedID)
        if let reason = terminalReason {
          phase = "failed"; pendingAction = nil; pendingOwnerCommand = nil
          healthKitState = saveToHealth ? "unknown" : "notRequested"; recoveryState = "resolved"; recoveryMessage = reason; recoveryDeadline = nil
          startDeadline = nil; if useWatch { connectivity?.clearPendingStart() }
          updateMetadata(); try persist(); emit(); return
        }
      } catch { storageFailed(error); return }
    }
    if !forceRecovery, oldPhase == "failed", pendingOwnerCommand == nil, pendingRemoteAction == nil { phase = "failed"; return }
    if (oldPhase == "completed" || oldPhase == "failed") && finalized && pendingOwnerCommand == nil { phase = oldPhase; return }
    phase = stopRequestedAt != nil ? "completed" : "recoverable"; pendingAction = stopRequestedAt == nil ? (useWatch ? pendingRemoteAction?.action : pendingOwnerCommand?.action) : nil
    if discardRequested { phase = "finishing"; pendingAction = "discard" }
    warn("Native workout was interrupted. Recovery must confirm the original owner before continuing.")
    if useWatch {
      do { try replayUnforwardedTelemetry() } catch { storageFailed(error) }
      do { try queryOwner() } catch { recoveryState = "unresolved"; recoveryMessage = error.localizedDescription }
    } else if !saveToHealth {
      // Process death interrupts local capture. An explicit recovery resumes into paused state,
      // preserving the acquisition gap and never inventing a Health session or saved workout.
      health.setLocalOwner(normalizedID)
      if stopRequestedAt != nil { finishPhoneStop(); return }
      if forceRecovery, let archive, let control, let started {
        do {
          let now = Date()
          elapsedBase = max(elapsedBase, now.timeIntervalSince(started)); elapsedOrigin = clock; runningOrigin = nil
          timeline = WorkoutTimelineAnchor(epoch: CycCaptureClock.processEpoch, monotonicOrigin: clock - elapsedBase,
            startedAt: WorkoutCoding.timestamp(started), uncertainty: "Recording interrupted across process restart; UTC maps the acquisition gap")
          if let command = pendingOwnerCommand {
            if command.endsWorkout {
              stopRequestedAt = try WorkoutCoding.date(command.requestedAt); finishPhoneStop(); return
            }
            _ = try control.observe(workoutID: normalizedID, owner: "phone", phase: "paused", at: now,
              health: "notRequested", command: command, failure: "Recording interrupted before this action was confirmed")
            pendingOwnerCommand = nil
          }
          _ = try control.observe(workoutID: normalizedID, owner: "phone", phase: "paused", at: now, health: "notRequested")
          phase = "paused"; pendingAction = nil; recoveryState = "resolved"; recoveryMessage = "Local ride recovered paused; the recording gap is retained."; recoveryDeadline = nil
          record(kind: "lifecycle", source: "phone", date: now, payload: ["action": "pause"])
          try archive.update(id: normalizedID, phase: "paused", healthKitState: "notRequested")
          try persist()
        } catch { storageFailed(error) }
      } else { recoveryState = "unresolved"; recoveryMessage = "Local recording was interrupted. Recover it paused or finish the retained ride."; recoveryDeadline = nil }
      emit()
    } else {
      health.recoverPhone(id: normalizedID) { result in self.queue.async {
        guard self.isCurrent(identity) else { return }
        switch result {
        case .success(let session):
          if self.discardRequested {
            self.stopRequestedAt = self.stopRequestedAt ?? self.pendingOwnerCommand.flatMap { try? WorkoutCoding.date($0.requestedAt) }
            self.finishPhoneStop(); return
          }
          let nativePhase = session.map { $0.state == .paused ? "paused" : $0.state == .stopped ? "stopped" : $0.state == .ended ? "ended" : "running" }
          if let session {
            do {
              if self.timeline == nil, let actualStart = session.startDate, let archive = self.archive {
                self.timeline = try WorkoutPhoneStartProjection.confirm(archive: archive, id: normalizedID,
                  startedAt: actualStart, now: Date(), uptime: self.clock, epoch: CycCaptureClock.processEpoch)
                self.started = actualStart
                self.elapsedBase = max(0, self.clock - self.timeline!.monotonicOrigin)
              }
              self.timerBase = session.associatedWorkoutBuilder().elapsedTime
              if self.stopRequestedAt == nil, let actualEnd = session.endDate {
                self.stopRequestedAt = actualEnd
                try self.archive?.finish(id: normalizedID, endedAt: actualEnd, finalPhase: "finishing")
              }
            } catch { self.storageFailed(error); return }
          }
          let recovery = WorkoutRecoveryPlanner.action(hasCutoff: self.stopRequestedAt != nil, verifiedFinality: finalized,
            nativePhase: nativePhase, pendingAction: self.pendingOwnerCommand?.action)
          let finished: (Result<String, Error>) -> Void = { result in self.queue.async {
            guard self.isCurrent(identity) else { return }
            do {
              switch result {
              case .success(let uuid):
                self.healthKitUUID = uuid; self.healthKitState = "saved"; self.recoveryState = "resolved"; self.recoveryMessage = nil
                guard let archive = self.archive,
                  let cutoffText = try archive.metadata(id: normalizedID).endedAt else {
                  throw CycError.invalid("Saved workout recovery has not returned its original end time")
                }
                let cutoff = try WorkoutCoding.date(cutoffText)
                self.stopRequestedAt = self.stopRequestedAt ?? cutoff
                self.elapsedOrigin = nil; self.runningOrigin = nil
                self.elapsedBase = max(0, cutoff.timeIntervalSince(self.started ?? cutoff))
                self.phase = "completed"; self.recoveryDeadline = nil
                let command = self.pendingOwnerCommand
                let recorded = try command.map { try archive.hasEvent(id: normalizedID, eventID: $0.id) } ?? false
                let nativeLap = command?.action == "lap" && session?.associatedWorkoutBuilder().workoutEvents.contains(where: {
                  $0.metadata?["PowerLogEventId"] as? String == command?.id
                }) == true
                let unresolvedAction = command != nil && !["start", "stop"].contains(command!.action) && !recorded && !nativeLap
                try self.commitPhoneOwner("completed", at: cutoff,
                  failure: unresolvedAction ? "Saved owner ended without evidence for the pending action" : nil)
                try self.sealPhone(id: normalizedID)
              case .failure(let failure):
                if self.timeline == nil, let control = self.control,
                  let cancelled = try WorkoutOwnerAdmission.cancelPhoneAfterLookup(failure, workoutID: normalizedID, control: control,
                    nativeAbsenceConfirmed: session == nil, effectInFlight: self.phoneStartInFlight || self.phoneFinalizationInFlight) {
                  self.pendingOwnerCommand = nil; self.pendingAction = nil; self.phase = "failed"; self.healthKitState = "unknown"
                  self.recoveryState = "resolved"; self.recoveryMessage = cancelled.reason; self.recoveryDeadline = nil
                  let completion = self.startCompletion; self.startCompletion = nil
                  self.updateMetadata(); try self.persist(); self.emit()
                  completion?(.failure(CycError.invalid(cancelled.reason ?? "Unconfirmed start cancelled")))
                  return
                }
                self.healthKitState = "pending"; self.recoveryState = "unresolved"
                self.recoveryMessage = "Original Health recovery remains pending: \(failure.localizedDescription)"; self.warn(self.recoveryMessage!)
              }
              self.updateMetadata(); try self.persist(); self.emit()
            } catch { self.storageFailed(error) }
          } }
          switch recovery {
          case .settled: return
          case .findSavedWorkout:
            self.health.reconcileSaved(id: normalizedID, at: self.stopRequestedAt ?? Date(), completion: finished)
            return
          case .finishSameSession:
            if self.stopRequestedAt == nil, let command = self.pendingOwnerCommand, command.action == "stop", let actualStart = session?.startDate {
              do {
                let cutoff = WorkoutUnconfirmedStopPolicy.confirmedCutoff(requestedAt: try WorkoutCoding.date(command.requestedAt), actualStart: actualStart, now: Date())
                self.stopRequestedAt = cutoff
                self.elapsedBase = max(0, cutoff.timeIntervalSince(actualStart)); self.elapsedOrigin = nil
                self.timerBase = min(self.timerSeconds, self.elapsedBase); self.runningOrigin = nil
                self.timeline?.stopUTC = WorkoutCoding.timestamp(cutoff)
                if let origin = self.timeline?.monotonicOrigin { self.timeline?.stopMonotonic = origin + self.elapsedBase }
                try self.archive?.update(id: normalizedID, stopElapsedSeconds: self.elapsedBase)
                try self.archive?.finish(id: normalizedID, endedAt: cutoff, finalPhase: "finishing")
                self.phase = "completed"; self.pendingAction = nil
                self.record(kind: "lifecycle", source: "phone", date: cutoff, payload: ["action": "stop", "operationId": command.id], eventID: command.id)
                try self.persist()
              } catch { self.storageFailed(error); return }
            }
            do { try self.replayUnforwardedTelemetry() } catch { self.storageFailed(error) }
            guard let cutoff = self.stopRequestedAt ?? session?.endDate else {
              self.recoveryState = "unresolved"; self.recoveryMessage = "The stopped native owner has not returned its original end time."; self.emit(); return
            }
            self.health.stopPhone(at: cutoff, completion: finished)
            return
          case .applyPause: self.health.pause()
          case .applyResume: self.health.resume()
          case .reconcileLap:
            if let command = self.pendingOwnerCommand, let date = try? WorkoutCoding.date(command.requestedAt) {
              self.health.lap(at: date, id: command.id) { result in self.queue.async {
                guard self.isCurrent(identity) else { return }
                do {
                  if case .failure(let error) = result { throw error }
                  self.record(kind: "lifecycle", source: "phone", date: date, payload: ["action": "lap", "operationId": command.id], eventID: command.id)
                  try self.commitPhoneOwner(nativePhase ?? "recoverable", at: Date())
                } catch { self.storageFailed(error) }
              } }
            }
          case .observeSession:
            do { try self.commitPhoneOwner(nativePhase ?? "recoverable", at: Date()) } catch { self.storageFailed(error); return }
          }
          do { try self.replayUnforwardedTelemetry() } catch { self.storageFailed(error) }
          self.elapsedOrigin = self.clock
          self.phase = nativePhase ?? "recoverable"
          self.recoveryState = "resolved"; self.recoveryMessage = nil; self.recoveryDeadline = nil
          if self.phase == "running" { self.runningOrigin = self.clock }
          if self.recordGPS { self.location.start() }
          self.warn("Recovered the original phone workout. The process interruption remains a data gap.")
        case .failure(let failure):
          self.recoveryState = "unresolved"; self.recoveryMessage = "Original owner recovery is unavailable: \(failure.localizedDescription)"
          self.warn(self.recoveryMessage!)
        }
        self.updateMetadata(); try? self.persist(); self.emit()
      } }
    }
    updateMetadata(); emit()
  }

  func addExampleRides() throws -> [String: Any] {
    guard ["idle", "completed", "failed"].contains(phase) else { throw CycError.invalid("Finish the current ride before adding example rides.") }
    guard let archive else { throw CycError.invalid("Workout storage is unavailable.") }
    let added = try WorkoutExampleRides.write(archive: archive)
    emit()
    return ["added": added.count]
  }
  func list(limit: Int = 100, beforeStartedAt: String? = nil, beforeID: String = "") throws -> [[String: Any]] {
    guard let archive else { throw CycError.invalid(error ?? "Workout storage is unavailable.") }
    return try archive.list(limit: limit, beforeStartedAt: beforeStartedAt, beforeID: beforeID).map(\.dictionary)
  }
  func read(_ id: String, distanceSource: String = "auto") throws -> [String: Any] {
    guard let archive else { throw CycError.invalid("Workout storage is unavailable.") }
    let metadata = try archive.metadata(id: id)
    let summary = try WorkoutFIT.summarize(archive: archive, id: id, revision: metadata.collectionRevision, distanceSource: distanceSource)
    try archive.store.requireWorkoutAvailable(id: id)
    return ["metadata": metadata.dictionary, "summary": summary.dictionary]
  }
  func export(_ id: String, distanceSource: String = "auto") throws -> String {
    guard let archive else { throw CycError.invalid("Workout storage is unavailable.") }
    let metadata = try archive.metadata(id: id)
    guard metadata.endedAt != nil, metadata.phase == "completed", metadata.sealVerified, ["complete", "partial"].contains(metadata.finalizationState ?? "") else { throw CycError.invalid("Finish the workout and wait for its final Watch archive before exporting FIT.") }
    let output = try archive.directory(id: id).appendingPathComponent(WorkoutFIT.filename(revision: metadata.collectionRevision ?? 0, seal: metadata.sealRevision ?? 0, distanceSource: distanceSource))
    _ = try WorkoutFIT.export(archive: archive, id: id, to: output, revision: metadata.collectionRevision, distanceSource: distanceSource)
    do { try archive.store.requireWorkoutAvailable(id: id) }
    catch { try? FileManager.default.removeItem(at: output); throw error }
    return output.absoluteString
  }

  func exportOriginal(_ id: String) throws -> String {
    guard let archive else { throw CycError.invalid("Workout storage is unavailable.") }
    let metadata = try archive.metadata(id: id)
    let output = try archive.directory(id: id).appendingPathComponent("PowerLog-original-r\(metadata.collectionRevision ?? 0)-s\(metadata.sealRevision ?? 0).zip")
    try WorkoutOriginalExport.write(archive: archive, id: id, to: output, revision: metadata.collectionRevision)
    do { try archive.store.requireWorkoutAvailable(id: id) }
    catch { try? FileManager.default.removeItem(at: output); throw error }
    return output.absoluteString
  }

  func prepareUpload(_ id: String, distanceSource: String = "auto", completion: @escaping (Result<(URL, Bool), Error>) -> Void) {
    fileQueue.async { completion(Result { try self.uploadFile(id, distanceSource: distanceSource) }) }
  }

  func uploadFile(_ id: String, distanceSource: String = "auto") throws -> (URL, Bool) {
    guard let archive else { throw CycError.invalid("Workout storage is unavailable.") }
    let metadata = try archive.metadata(id: id)
    guard metadata.phase == "completed", metadata.endedAt != nil,
      metadata.sealVerified,
      ["complete", "partial"].contains(metadata.finalizationState ?? "") else {
      throw CycError.invalid("Finish the workout and wait for the final Watch archive before uploading.")
    }
    guard let url = URL(string: try export(id, distanceSource: distanceSource)) else { throw CycError.invalid("FIT export did not return a file.") }
    return (url, metadata.indoor)
  }

  private func importWatchArchive(_ url: URL, metadata: [String: Any], completion: @escaping (Bool) -> Void) {
    let lease = WorkoutImportLease()
    DispatchQueue.main.async {
      var background: UIBackgroundTaskIdentifier = .invalid
      background = UIApplication.shared.beginBackgroundTask(withName: "Power Log archive chunk") {
        lease.expire()
        if background != .invalid { UIApplication.shared.endBackgroundTask(background); background = .invalid }
      }
      self.fileQueue.async {
        defer { DispatchQueue.main.async { if background != .invalid { UIApplication.shared.endBackgroundTask(background); background = .invalid } } }
        do {
        guard lease.permits() else { throw CycError.invalid("Archive processing budget expired") }
        guard let archive = self.archive, let transfer = self.transfer,
          let raw = metadata["manifest"] as? [String: Any] else { throw CycError.invalid("Invalid Watch chunk manifest") }
        let manifest = try JSONDecoder().decode(WorkoutChunkManifest.self, from: JSONSerialization.data(withJSONObject: raw))
        if try archive.store.isWorkoutDeleted(id: manifest.workoutID) { self.queue.async { completion(true) }; return }
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        guard (attributes[.size] as? NSNumber)?.intValue == manifest.compressedBytes,
          manifest.compressedBytes <= WorkoutChunkCodec.maximumBytes else { throw CycError.invalid("Watch chunk exceeds bound") }
        guard let start = metadata["startedAt"] as? String else { throw CycError.invalid("Missing Watch recording start") }
        guard lease.permits() else { throw CycError.invalid("Archive processing budget expired") }
        _ = try transfer.receiveWatch(WorkoutChunk(manifest: manifest, data: Data(contentsOf: url)),
          startedAt: start, indoor: metadata["indoor"] as? Bool ?? false, saveToHealth: metadata["saveToHealth"] as? Bool ?? true, recordGPS: metadata["recordGPS"] as? Bool)
        self.queue.async {
          self.connectivity?.sendEphemeral(["schemaVersion": 1, "kind": "chunkAck", "workoutId": manifest.workoutID,
            "messageId": UUID().uuidString.lowercased(), "chunkIdentity": manifest.identity,
            "producer": manifest.producer, "lastSequence": String(manifest.lastSequence), "contentHash": manifest.contentHash])
          if let seal = metadata["seal"] { var packet = metadata; packet["seal"] = seal; self.receiveSeal(packet) }
          else if (try? transfer.currentSeal(id: manifest.workoutID)) != nil {
            self.requestVerification(id: manifest.workoutID, acknowledge: true)
          }
          completion(true)
          self.emit()
        }
      } catch PowerLogStorageError.deleted { self.queue.async { completion(true) } }
      catch { self.queue.async { completion(false); self.warn("Watch chunk remains pending: \(error.localizedDescription)"); self.emit() } }
    }
    }
  }

  func deleteWorkout(_ requestedID: String) throws -> [String: Any] {
    let targetID = try WorkoutCoding.id(requestedID)
    guard let archive, let control else { throw CycError.invalid("Workout storage is unavailable.") }
    if try archive.store.isWorkoutDeleted(id: targetID) { clearDeletedSelection(targetID); advanceDeletions(); emit(); return state() }
    let saved = try archive.metadata(id: targetID)
    try control.repairTerminalSlot(workoutID: targetID)
    guard try WorkoutDeletionPolicy.phone(phase: saved.phase, selectedPhase: targetID == id ? phase : nil,
      healthBusy: health.deletionBlocked || phoneStartInFlight || phoneFinalizationInFlight,
      pendingAction: control.active(workoutID: targetID) != nil || control.pendingRemote(workoutID: targetID) != nil,
      backgroundBusy: verificationJobs.contains(targetID) || contributorJobs.contains(targetID) || phoneSealJobs.contains(targetID)) else {
      throw CycError.invalid("Finish the ride and wait for its pending operation before deleting it.")
    }
    if targetID == id { try captureBarrier() }
    _ = try archive.store.markWorkoutDeleted(id: targetID, watchRequired: saved.watchEnabled)
    try captureBatch.discardRide(targetID)
    clearDeletedSelection(targetID)
    connectivity?.discardWorkoutPackets(workoutId: targetID)
    advanceDeletions(); emit(); return state()
  }
  private func clearDeletedSelection(_ targetID: String) {
    if id == targetID {
      sessionGeneration = UUID(); localInterruption = nil; id = nil; phase = "idle"; started = nil; timeline = nil
      elapsedBase = 0; timerBase = 0; elapsedOrigin = nil; runningOrigin = nil; metrics = [:]
      pendingAction = nil; pendingOwnerCommand = nil; pendingRemoteAction = nil; startCommand = nil
      startCompletion = nil; startDeadline = nil; recoveryQuery = nil; recoveryDeadline = nil
      recoveryState = "idle"; recoveryMessage = nil; stopRequestedAt = nil
      discardRequested = false; location.stop(); previousLocation = nil
      if !useWatch && !saveToHealth { health.setLocalOwner(nil) }
      healthKitState = "notSaved"; healthKitUUID = nil; warnings = []; error = nil
      lastHeart = nil; lastCyc = nil; lastGPS = nil
    }
  }

  /// A rotating catalog page discovers both cleanup and offline Watch work beyond outbox capacity.
  private func advanceDeletions() {
    guard let archive else { return }
    do {
      let page = try archive.store.deletionPage(after: deletionCursor, limit: 8)
      deletionCursor = page.last?.id ?? ""
      for record in page {
        connectivity?.discardWorkoutPackets(workoutId: record.id)
        if record.watchRequired, !record.watchAcknowledged, record.retryAfter <= Date().timeIntervalSince1970 {
          do { _ = try connectivity?.enqueue(record.packet) }
          catch { warn("Watch deletion delivery will retry: \(error.localizedDescription)") }
        }
        if !record.cleanupComplete, deletionCleanupID == nil {
          deletionCleanupID = record.id; cleanupDeletedRide(record.id)
        }
      }
    } catch { warn("Deleted ride cleanup will retry: \(error.localizedDescription)") }
  }
  private func cleanupDeletedRide(_ targetID: String) {
    guard let archive else { deletionCleanupID = nil; return }
    fileQueue.async {
      do {
        let finished = try archive.cleanupDeletedWorkoutPage(id: targetID) { MonitorDataStore.shared.discardWorkout(id: targetID) }
        self.queue.async {
          if finished { self.deletionCleanupID = nil }
          else { self.queue.asyncAfter(deadline: .now() + .milliseconds(10)) { self.cleanupDeletedRide(targetID) } }
        }
      } catch {
        self.queue.async {
          self.deletionCleanupID = nil
          self.warn("Deleted ride cleanup will retry: \(error.localizedDescription)"); self.emit()
        }
      }
    }
  }
}
#endif
