#if os(iOS) || (os(macOS) && POWER_LOG_ENGINE_TEST)
  import Foundation
  #if os(iOS)
    import HealthKit
  #endif
  import CoreLocation
  import PowerLogCore

  /// The phone owns the application recording on every ride. Health is an independent best-effort output.
  final class WorkoutEngine {
    static let shared = WorkoutEngine()
    static let watchRoutineSeconds = 2.0
    let queue = DispatchQueue(label: "app.powerlog.workout", qos: .userInitiated)
    let fileQueue = DispatchQueue(label: "app.powerlog.workout.analysis", qos: .utility)
    private var archive: WorkoutArchive?
    private var capture: PowerLogCaptureRecorder?
    private lazy var health = WorkoutNativeHealth(queue: queue)
    private lazy var companion = WorkoutCompanionTransport(queue: queue)
    private let location = WorkoutLocation()
    private var sinks: [UUID: ([String: Any]) -> Void] = [:]
    private var ticker: DispatchSourceTimer?
    private var id: String?
    private var phase = "idle"
    private var options: WorkoutCompanionOptions?
    private var timeline: WorkoutTimelineAnchor?
    private var generation = UUID()
    private var elapsedBase = 0.0
    private var activeBase = 0.0
    private var activeOrigin: Double?
    private var error: String?
    private var healthState = "notSaved"
    private var rideWatch = false
    private var startAttempt: String?
    private var startCompletion: ((Result<[String: Any], Error>) -> Void)?
    private var live = WorkoutLivePresentation()
    private var gpsClock = WorkoutTimelineAnchor.LocationClock()
    private var gpsBarrier = false
    private var locations: [WorkoutCompanionLocation] = []
    private var healthSeconds = WorkoutHealthSeconds()
    private var telemetrySequence: Int64 = 0
    private var stateRevision: Int64 = 0
    private var finalHealthSeconds: [WorkoutCompanionSecond] = []
    private var finalLocations: [WorkoutCompanionLocation] = []
    private var processingCompanionCommand = false
    private var acknowledgedCommands: [String: WorkoutCompanionMessage] = [:]
    private var activityToken = UUID().uuidString.lowercased()
    private var frozenDistance = "controller"
    private var frozenSampleHz = 2.0
    private var watchStartRequestID: String?
    private var cancelledStarts = WorkoutCompanionCancellations()
    private var pendingStopTiming: WorkoutOwnerTiming?
    private var lastRoutineSend = -Double.infinity
    private var background = false
    private var lastCheckpoint = -Double.infinity
    private var pendingTerminal: WorkoutCompanionMessage?
    private var pendingTerminalUntil = Date.distantPast
    private var clock: Double { ProcessInfo.processInfo.systemUptime }
    private var watchAvailable: Bool { companion.reachable || companion.installed }
    var rideInProgress: Bool { ["preparing", "running", "paused", "interrupted"].contains(phase) }
    private var elapsed: Double { timeline.map { max(0, clock - $0.monotonicOrigin) } ?? elapsedBase }
    private var active: Double { activeBase + (activeOrigin.map { max(0, clock - $0) } ?? 0) }

    init(archive suppliedArchive: WorkoutArchive? = nil) {
      do {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
          .appendingPathComponent("PowerLog/workouts", isDirectory: true)
        let archive = try suppliedArchive ?? WorkoutArchive(rootURL: root)
        self.archive = archive
        capture = PowerLogCaptureRecorder(store: archive.store)
        capture?.mapped = { [weak self] event in self?.queue.async { self?.observeController(event) } }
        capture?.failed = { [weak self] failure in self?.queue.async { self?.captureFailed(failure) } }
        capture?.flushed = { ControllerEngine.shared.queue.async { ControllerEngine.shared.captureResult(nil) } }
        if let interrupted = try archive.list(limit: 100).first(where: { $0.phase == "interrupted" }) {
          id = interrupted.id
          rideWatch = interrupted.watchEnabled
          phase = "interrupted"
          stateRevision += 1
          elapsedBase = interrupted.elapsedSeconds
          activeBase = interrupted.ownerTiming?.timerSeconds ?? 0
          options = WorkoutCompanionOptions(
            id: interrupted.id, start: try WorkoutCoding.date(interrupted.startedAt), indoor: interrupted.indoor,
            saveToHealth: interrupted.saveToHealth, recordGPS: interrupted.recordGPS,
            distanceSource: interrupted.distanceSource)
          healthState = interrupted.healthKitState
          frozenDistance = interrupted.distanceSource
          frozenSampleHz = Double(interrupted.sampleHz)
        }
      } catch { self.error = error.localizedDescription }
      companion.changed = { [weak self] in
        self?.synchronizeCompanion()
        self?.replayTerminal()
        self?.publish()
      }
      health.liveReadings = { [weak self] rideID, values, date in
        guard let self, self.id == rideID, self.phase == "running", let heart = values["heartRateBpm"] else { return }
        self.live.receiveHeartRate(
          ["heartRateBpm": heart], identity: UUID().uuidString.lowercased(),
          acquiredAt: self.clock - max(0, Date().timeIntervalSince(date)), now: self.clock)
      }
      companion.received = { [weak self] message, reply in self?.receive(message, reply: reply) }
      health.observation = { [weak self] id, observation in
        guard let self else { return }
        do { try self.receiveObservations([observation], id: id, source: "phone") } catch { self.captureFailed(error) }
      }
      health.status = { [weak self] id, state, healthID, reason in
        guard let self else { return }
        do {
          if try self.archive?.store.isWorkoutDeleted(id: id) == true { return }
          try self.setHealth(id: id, state: state, healthID: healthID, reason: reason)
        } catch {
          self.error = error.localizedDescription
        }
      }
      health.recoverForCleanup { [weak self] id in
        guard let self, let id else { return }
        try? self.setHealth(
          id: id, state: "unknown", healthID: nil, reason: "The interrupted native session was ended.")
      }
      location.onLocation = { [weak self] fixes, at, uptime in
        self?.queue.async { self?.consumeLocations(fixes, receivedAt: at, uptime: uptime) }
      }
      location.onStatus = { [weak self] status in self?.queue.async { self?.live.gpsStatus = status } }
      let ticker = DispatchSource.makeTimerSource(queue: queue)
      ticker.schedule(deadline: .now() + 1, repeating: 1)
      ticker.setEventHandler { [weak self] in self?.tick() }
      self.ticker = ticker
      ticker.resume()
    }

    func addSink(id: UUID, sink: @escaping ([String: Any]) -> Void) {
      sinks[id] = sink
      sink(state())
    }
    func removeSink(id: UUID) { sinks.removeValue(forKey: id) }
    func state() -> [String: Any] {
      let record = id.flatMap { try? archive?.metadata(id: $0) }
      let catalog = try? archive?.store.catalogState()
      let selection = WorkoutStreamSelection(
        phase: phase, controller: rideInProgress,
        heartRate: rideInProgress && (options?.saveToHealth == true || record?.watchEnabled == true),
        gps: rideInProgress && options?.recordGPS == true)
      var value = RideSnapshot(
        supported: true,
        capabilities: PlatformCapabilities.shared.iphone(healthAvailable: HKHealthStore.isHealthDataAvailable()),
        id: id, phase: phase,
        pendingAction: phase == "preparing" ? "start" : nil, timerSeconds: active,
        historyRevision: catalog.map { String($0.revision) } ?? "0",
        lastDeletedWorkoutId: catalog?.lastDeletedWorkoutId,
        collectionRevision: record?.collectionRevision,
        indoor: options?.indoor ?? false, useWatch: record?.watchEnabled ?? false,
        saveToHealth: options?.saveToHealth ?? true,
        recordGPS: options?.recordGPS ?? true, recoveryState: "idle", recoveryMessage: nil, healthKitState: healthState,
        watch: RideWatchSnapshot(installed: watchAvailable),
        streams: RideStreamsSnapshot(
          controller: RideStreamSnapshot(status: live.controllerStatus(selection, now: clock)),
          heartRate: RideStreamSnapshot(status: live.heartRateStatus(selection, now: clock)),
          gps: RideGPSSnapshot(
            status: live.gpsStreamStatus(selection, now: clock), source: .phone, accuracyMeters: live.gpsAccuracy)),
        error: error, sessionBusy: health.busy && phase == "preparing"
      ).wireMap
      value["recordingOwner"] = "phone"
      value["healthPublisher"] = record?.watchEnabled == true ? "watch" : "phone"
      value["distanceSource"] = record?.distanceSource ?? frozenDistance
      value["batteryConsumptionSource"] = record?.batteryConsumptionSource ?? "controller"
      value["targetPending"] = false
      value["targetRecovering"] = false
      value["targetBusy"] = false
      value["exportEligible"] = phase == "completed"
      return value
    }
    private func publish() {
      if !background && !sinks.isEmpty {
        let body = state()
        for sink in sinks.values { sink(body) }
      }
      publishActivity()
    }
    func publishActivity() {
      let pendingAction = phase == "preparing" ? "start" : nil
      let key = WorkoutActivityKey(
        rideID: id, phase: phase, pendingAction: pendingAction, controlToken: activityToken, confirmationPending: true)
      guard WorkoutLiveActivity.shared.needsPublication(key) else { return }
      WorkoutLiveActivity.shared.publish(
        WorkoutActivitySnapshot(
          rideID: id, phase: phase,
          pendingAction: pendingAction, timerSeconds: active, observedAt: Date(),
          observedUptime: clock,
          bikeSampleAgeSeconds: live.controllerFreshness.age(at: clock),
          heartSampleAgeSeconds: live.heartFreshness.age(at: clock),
          riderPowerW: live.metrics["riderPowerW"], heartRateBpm: live.metrics["heartRateBpm"],
          controlToken: activityToken))
    }
    func getPermissions(_ completion: @escaping (Result<[String: Any], Error>) -> Void) {
      location.permissionStatus { result in
        var result = result
        result["health"] = self.health.permissions
        completion(.success(result))
      }
    }
    func requestPermissions(
      indoor: Bool, useWatch: Bool, saveToHealth: Bool = true, recordGPS: Bool? = nil,
      completion: @escaping (Result<[String: Any], Error>) -> Void
    ) {
      let finish = { self.getPermissions(completion) }
      let location = { if recordGPS ?? !indoor { self.location.requestPermission { _ in finish() } } else { finish() } }
      if saveToHealth || useWatch {
        health.authorize(writing: !useWatch && saveToHealth) { result in
          switch result {
          case .success: location()
          case .failure(let error): completion(.failure(error))
          }
        }
      } else {
        location()
      }
    }
    func start(
      indoor: Bool, useWatch: Bool, saveToHealth: Bool = true, recordGPS: Bool? = nil,
      startRequestID: String? = nil, completion: @escaping (Result<[String: Any], Error>) -> Void
    ) {
      guard !rideInProgress, startAttempt == nil, useWatch || !saveToHealth || !health.busy else {
        completion(.failure(WorkoutDataError.invalid("Finish the current ride first.")))
        return
      }
      guard let archive else {
        completion(.failure(WorkoutDataError.invalid("Recording storage is unavailable.")))
        return
      }
      do {
        let preferences = try WorkoutPreferences.read()
        let rate = Int(preferences.sampleHz)
        let gps = recordGPS ?? !indoor
        let selected = try DistancePolicy.shared.resolveAtStart(
          selection: preferences.distanceSource, recordGPS: gps, indoor: indoor)
        // A received Watch Start already proves the companion exists; the handshake still must confirm Start.
        if useWatch && !watchAvailable && startRequestID == nil {
          throw WorkoutDataError.invalid("The Watch companion is not installed.")
        }
        try capture?.barrier()
        let id = UUID().uuidString.lowercased()
        let start = Date()
        try ControllerEngine.shared.queue.sync {
          guard ControllerEngine.shared.state()["status"] as? String == "connected" else {
            throw WorkoutRideFailure("bikeRequired")
          }
          try ControllerEngine.shared.setSampleRate(preferences.sampleHz)
        }
        _ = try archive.create(
          id: id, startedAt: start, indoor: indoor, watchEnabled: useWatch,
          saveToHealth: saveToHealth, recordGPS: gps, sampleHz: rate, distanceSource: selected,
          batteryConsumptionSource: preferences.batteryConsumptionSource)
        _ = try archive.update(id: id, phase: "preparing", healthKitState: saveToHealth ? "pending" : "notRequested")
        self.id = id
        rideWatch = useWatch
        phase = "preparing"
        stateRevision += 1
        error = nil
        healthState = saveToHealth ? "pending" : "notRequested"
        options = WorkoutCompanionOptions(
          id: id, start: start, indoor: indoor, saveToHealth: saveToHealth, recordGPS: gps,
          distanceSource: selected)
        frozenDistance = selected
        frozenSampleHz = preferences.sampleHz
        watchStartRequestID = startRequestID
        pendingStopTiming = nil
        generation = UUID()
        activeBase = 0
        elapsedBase = 0
        activeOrigin = nil
        timeline = nil
        healthSeconds = WorkoutHealthSeconds()
        locations.removeAll()
        gpsClock = WorkoutTimelineAnchor.LocationClock()
        startAttempt = UUID().uuidString.lowercased()
        startCompletion = completion
        activityToken = UUID().uuidString.lowercased()
        setCaptureDestination()
        let attempt = startAttempt!
        let begin: () -> Void = {
          self.queue.async {
            guard self.startAttempt == attempt, let options = self.options else { return }
            if useWatch {
              let configuration = HKWorkoutConfiguration()
              configuration.activityType = .cycling
              configuration.locationType = options.indoor ? .indoor : .outdoor
              if self.companion.reachable {
                self.startWatch(options, attempt: attempt)
              } else {
                self.health.store.startWatchApp(with: configuration) { _, _ in
                  self.queue.async { self.startWatch(options, attempt: attempt) }
                }
              }
            } else if saveToHealth {
              self.health.authorize { result in
                guard self.startAttempt == attempt else { return }
                switch result {
                case .failure(let error): self.startFailed(error)
                case .success:
                  self.health.start(options) { result in
                    guard self.startAttempt == attempt else { return }
                    switch result {
                    case .success: self.beginRunning()
                    case .failure(let error): self.startFailed(error)
                    }
                  }
                }
              }
            } else {
              self.beginRunning()
            }
          }
        }
        if gps { location.requestPermission { _ in begin() } } else { begin() }
        queue.asyncAfter(deadline: .now() + 15) {
          guard self.startAttempt == attempt else { return }
          self.startFailed(WorkoutDataError.invalid("Start timed out. Cancel, or start a new ride without Watch."))
        }
        publish()
      } catch { completion(.failure(error)) }
    }
    private func startWatch(_ options: WorkoutCompanionOptions, attempt: String) {
      guard startAttempt == attempt else { return }
      guard companion.reachable else {
        queue.asyncAfter(deadline: .now() + 0.5) { self.startWatch(options, attempt: attempt) }
        return
      }
      companion.send(
        WorkoutCompanionMessage(
          kind: .start, rideID: options.id, commandID: attempt, startRequestID: watchStartRequestID,
          expiresAt: options.start.addingTimeInterval(15), options: options)
      ) { result in
        guard self.startAttempt == attempt else { return }
        switch result {
        case .success(let response):
          if let error = response.error {
            self.startFailed(WorkoutDataError.invalid(error))
          } else if response.rideID != options.id || response.commandID != attempt {
            self.startFailed(WorkoutDataError.invalid("The Watch Start response changed."))
          } else if response.phase == "running" {
            self.beginRunning()
          } else {
            self.startFailed(WorkoutDataError.invalid("The Watch did not confirm Start."))
          }
        case .failure(let error): self.startFailed(error)
        }
      }
    }
    private func beginRunning() {
      guard let options, let archive else { return }
      do {
        let at = Date()
        // The phone start timestamp is frozen in the handshake; elapsed includes its bounded launch interval.
        let origin = clock - max(0, at.timeIntervalSince(options.start))
        timeline = WorkoutTimelineAnchor(
          epoch: TelemetryCaptureClock.processEpoch, monotonicOrigin: origin,
          startedAt: WorkoutCoding.timestamp(options.start))
        phase = "running"
        stateRevision += 1
        activeOrigin = clock
        try appendLifecycle("start", at: options.start, elapsed: 0)
        _ = try archive.update(id: options.id, phase: phase)
        setCaptureDestination()
        if options.recordGPS { location.start() }
        let callback = startCompletion
        startCompletion = nil
        startAttempt = nil
        publish()
        callback?(.success(state()))
      } catch { startFailed(error) }
    }
    private func startFailed(_ failure: Error) {
      let target = id
      error = failure.localizedDescription
      phase = "failed"
      stateRevision += 1
      startAttempt = nil
      if let target { try? archive?.update(id: target, phase: "failed") }
      let done = startCompletion
      startCompletion = nil
      if health.busy { health.finish(at: Date(), discard: true) {} }
      if let target { sendState(id: target, action: "discard", phase: "idle") }
      setCaptureDestination()
      publish()
      done?(.failure(failure))
    }
    private func setCaptureDestination() {
      archive?.store.liveEvidence.setTimeline(id: id, anchor: timeline)
      capture?.setDestination(
        phase == "running" && id != nil && timeline != nil
          ? PowerLogCaptureDestination(id: id!, generation: generation, timeline: timeline!) : nil)
      let target = RideActions.shared.recording(phase: phase) ? id : nil
      let rate = options == nil ? nil : frozenSampleHz
      ControllerEngine.shared.queue.async { ControllerEngine.shared.setWorkoutSamplingOwner(target, rate: rate) }
    }
    private func require(_ expected: String?, phases: [String]) throws {
      guard expected == nil || expected == id else { throw WorkoutDataError.invalid("The selected ride changed.") }
      guard id != nil, phases.contains(phase) else {
        throw WorkoutDataError.invalid("That action is unavailable for this ride.")
      }
    }
    func pause(expectedID: String? = nil) throws -> [String: Any] {
      try require(expectedID, phases: ["running"])
      try capture?.barrier()
      guard let id, let archive else { throw WorkoutDataError.invalid("Recording storage is unavailable.") }
      let stoppedActive = active
      let timing = try WorkoutOwnerTiming(
        timestamp: WorkoutCoding.timestamp(Date()), elapsedSeconds: elapsed, timerSeconds: min(stoppedActive, elapsed))
      let watch = try archive.metadata(id: id).watchEnabled
      try archive.store.transaction { _ in
        try appendLifecycle("pause")
        _ = try archive.update(id: id, phase: "paused", ownerTiming: timing, elapsedSeconds: timing.elapsedSeconds)
      }
      forwardSeconds(paced: false, watch: watch)
      activeBase = stoppedActive
      activeOrigin = nil
      phase = "paused"
      stateRevision += 1
      healthSeconds.boundary()
      gpsBarrier = true
      location.setPaused(true)
      if watch { sendState(id: id) } else { health.pause() }
      setCaptureDestination()
      publish()
      return state()
    }
    func resume(expectedID: String? = nil) throws -> [String: Any] {
      try require(expectedID, phases: ["paused"])
      guard let id, let archive else { throw WorkoutDataError.invalid("Recording storage is unavailable.") }
      let watch = try archive.metadata(id: id).watchEnabled
      let timing = try WorkoutOwnerTiming(
        timestamp: WorkoutCoding.timestamp(Date()), elapsedSeconds: elapsed, timerSeconds: min(active, elapsed))
      try archive.store.transaction { _ in
        try appendLifecycle("resume")
        _ = try archive.update(id: id, phase: "running", ownerTiming: timing, elapsedSeconds: timing.elapsedSeconds)
      }
      phase = "running"
      stateRevision += 1
      activeOrigin = clock
      gpsBarrier = true
      location.setPaused(false)
      if watch { sendState(id: id) } else { health.resume() }
      setCaptureDestination()
      publish()
      return state()
    }
    func lap(expectedID: String? = nil) throws -> [String: Any] {
      try require(expectedID, phases: ["running", "paused"])
      try appendLifecycle("lap")
      if try archive?.metadata(id: id!).watchEnabled == true {
        sendState(id: id!, action: "lap")
      } else {
        health.lap(at: Date())
      }
      publish()
      return state()
    }
    func stop(expectedID: String? = nil, discard: Bool = false) throws -> [String: Any] {
      try require(expectedID, phases: ["preparing", "running", "paused", "interrupted", "failed"])
      guard let id, let archive else { throw WorkoutDataError.invalid("Recording storage is unavailable.") }
      let watch = try archive.metadata(id: id).watchEnabled
      let timing =
        try pendingStopTiming
        ?? (phase == "interrupted"
          ? try WorkoutOwnerTiming.lastOriginal(id: id, startedAt: archive.metadata(id: id).startedAt, archive: archive)
          : try WorkoutOwnerTiming(
            timestamp: WorkoutCoding.timestamp(Date()), elapsedSeconds: elapsed, timerSeconds: min(active, elapsed)))
      pendingStopTiming = timing
      capture?.stopDestination(id: id, generation: generation, elapsed: timing.elapsedSeconds)
      activeBase = timing.timerSeconds
      activeOrigin = nil
      elapsedBase = timing.elapsedSeconds
      timeline = nil
      phase = "interrupted"
      stateRevision += 1
      startAttempt = nil
      let starting = startCompletion
      startCompletion = nil
      starting?(.failure(WorkoutDataError.invalid("Start cancelled.")))
      location.stop()
      setCaptureDestination()
      if watch { sendState(id: id) } else { health.pause() }
      do {
        if discard { capture?.discardRide(id) }
        try capture?.barrier()
        try archive.store.transaction { _ in
          try appendLifecycle(
            "stop", at: WorkoutCoding.date(timing.timestamp), elapsed: timing.elapsedSeconds)
          _ = try archive.update(
            id: id, phase: "completed", stopElapsedSeconds: timing.elapsedSeconds, ownerTiming: timing)
          _ = try archive.finish(id: id, endedAt: timing.timestamp)
          if discard { try archive.store.markWorkoutDeleted(id: id) }
        }
      } catch {
        self.error = error.localizedDescription
        publish()
        throw error
      }
      error = nil
      pendingStopTiming = nil
      activeBase = timing.timerSeconds
      activeOrigin = nil
      elapsedBase = timing.elapsedSeconds
      phase = "completed"
      stateRevision += 1
      timeline = nil
      startAttempt = nil
      location.stop()
      setCaptureDestination()
      if discard {
        healthSeconds = WorkoutHealthSeconds()
        locations.removeAll()
        finalHealthSeconds = []
        finalLocations = []
      } else {
        forwardSeconds(force: true, watch: watch)
      }
      if watch {
        sendState(id: id, action: discard ? "discard" : "stop", phase: "completed")
      } else {
        health.finish(at: try WorkoutCoding.date(timing.timestamp), discard: discard) {}
      }
      if discard {
        error = nil
        capture?.discardRide(id)
        self.id = nil
        options = nil
        phase = "idle"
        cleanup(id)
      } else {
        try? WorkoutSummaryReads.shared.warm(archive: archive, record: archive.metadata(id: id))
        if options?.saveToHealth == true || watch {
          for delay in [2.0, 900, 3600] {
            queue.asyncAfter(deadline: .now() + delay) { self.refreshHealth(id) { _ in } }
          }
        }
      }
      publish()
      return state()
    }
    func performActivityCommand(rideID: String, commandID: String, action: String, expectedPhase: String) throws {
      guard expectedPhase == phase else { throw WorkoutDataError.invalid("The ride controls changed.") }
      if acknowledgedCommands[commandID] != nil { return }
      switch action {
      case "pause": _ = try pause(expectedID: rideID)
      case "resume": _ = try resume(expectedID: rideID)
      case "finish", "stop": _ = try stop(expectedID: rideID)
      default: throw WorkoutDataError.invalid("Unknown ride control.")
      }
      remember(commandID, result: WorkoutCompanionMessage(kind: .acknowledgement))
    }
    func admitController(_ frame: PowerLogCaptureFrame) throws { _ = try capture?.admit(frame) }
    private func observeController(_ event: WorkoutEvent) {
      guard event.workoutId == id, phase == "running" else { return }
      let at = (try? event.date) ?? Date()
      do { try healthSeconds.append(event) } catch { self.error = error.localizedDescription }
      for (key, payloadKey) in [("riderPowerW", "humanPowerW"), ("cadenceRpm", "cadenceRpm")] {
        if let value = event.number(payloadKey) { live.metrics[key] = value }
      }
      _ = live.controllerFreshness.receive(id: event.eventId, age: max(0, Date().timeIntervalSince(at)), at: clock)
    }
    private func forwardSeconds(force: Bool = false, paced: Bool = true, watch forcedWatch: Bool? = nil) {
      guard let id, let options, phase == "running" || force else { return }
      let watch = forcedWatch ?? rideWatch
      if !force && paced {
        guard clock - lastRoutineSend >= (watch ? Self.watchRoutineSeconds : 1) else { return }
        lastRoutineSend = clock
      }
      var seconds: [WorkoutCompanionSecond] = []
      for _ in 0..<8 {
        guard let result = try? healthSeconds.drain(now: Date(), force: force) else {
          break
        }
        seconds.append(
          WorkoutCompanionSecond(time: Date(timeIntervalSince1970: Double(result.second)), readings: result.values))
      }
      let fixes = Array(locations.suffix(32))
      locations.removeAll(keepingCapacity: true)
      if watch {
        if force {
          finalHealthSeconds = seconds
          finalLocations = fixes
          return
        }
        telemetrySequence += 1
        var display = live.metrics.filter { $0.key != "heartRateBpm" }
        display["distanceMeters"] =
          frozenDistance == "controller" ? healthSeconds.controllerMeters : healthSeconds.gpsMeters
        let sampleTime = live.controllerFreshness.age(at: clock).map { Date().addingTimeInterval(-$0) }
        companion.send(
          WorkoutCompanionMessage(
            kind: .telemetry, rideID: id, sampleTime: sampleTime, phase: phase, stateRevision: stateRevision,
            active: active,
            readings: display, healthSeconds: seconds, locations: fixes, sequence: telemetrySequence))
      } else if options.saveToHealth {
        health.recordLocations(fixes)
        for second in seconds { health.publish(readings: second.readings, at: second.time, locations: []) }
      }
    }
    private func captureFailed(_ failure: Error) {
      ControllerEngine.shared.queue.async { ControllerEngine.shared.captureResult(failure) }
      guard let kind = Self.storageError(failure) else { return }
      error = failure.localizedDescription
      if LifecycleStorage.shared.qualifies(error: kind), ["running", "paused"].contains(phase) {
        activeBase = active
        activeOrigin = nil
        elapsedBase = elapsed
        timeline = nil
        phase = "interrupted"
        stateRevision += 1
        try? persist()
        setCaptureDestination()
        location.stop()
      }
      publish()
    }
    private static func storageError(_ failure: Error) -> LifecycleStorageError? {
      switch failure {
      case PowerLogStorageError.busy: return nil
      case PowerLogStorageError.sqlite: return .sqlite
      case let error as CocoaError where error.isFileError: return .cocoa
      default: return (failure as NSError).domain == NSPOSIXErrorDomain ? .posix : .operation
      }
    }
    private func appendLifecycle(_ action: String, at: Date = Date(), elapsed: Double? = nil) throws {
      guard let id, let archive else { return }
      try archive.append(
        WorkoutEvent(
          workoutId: id, kind: "lifecycle", source: "phone", timestamp: at,
          elapsedSeconds: elapsed ?? self.elapsed, payload: ["action": .string(action)]))
    }
    private func consumeLocations(_ fixes: [CLLocation], receivedAt: Date, uptime: Double) {
      guard phase == "running", options?.recordGPS == true, let timeline, let id, let archive else { return }
      do {
        let originals = try fixes.map {
          try WorkoutTimelineAnchor.LocationClock.original(
            timestamp: $0.timestamp, latitude: $0.coordinate.latitude, longitude: $0.coordinate.longitude,
            accuracy: $0.horizontalAccuracy)
        }
        let mapped = try gpsClock.mapBatch(
          originals: originals,
          received: GPSReceipt(
            timestamp: WorkoutTimelineAnchor.LocationClock.timestamp(receivedAt), monotonicSeconds: uptime),
          anchor: timeline)
        if mapped.discontinuity { gpsBarrier = true }
        for (index, mapping) in mapped.fixes where gpsClock.admit(mapping) {
          let fix = fixes[index]
          var payload: [String: WorkoutJSON] = [
            "latitude": .number(fix.coordinate.latitude), "longitude": .number(fix.coordinate.longitude),
            "horizontalAccuracyM": .number(fix.horizontalAccuracy), "speedAccuracyMps": .number(fix.speedAccuracy),
            "courseAccuracyDegrees": .number(fix.courseAccuracy),
            "distanceBarrier": .bool(gpsBarrier || mapping.uncertain), "clockEpoch": .string(mapping.epoch),
            "acquisitionMonotonic": .number(mapping.acquisition),
          ]
          payload.merge(WorkoutAltitudeCapture.apple(altitude: fix.altitude, verticalAccuracy: fix.verticalAccuracy)) {
            _, new in new
          }
          if fix.speed >= 0 { payload["speedMps"] = .number(fix.speed) }
          if fix.course >= 0 { payload["courseDegrees"] = .number(fix.course) }
          let event = try WorkoutEvent(
            workoutId: id, kind: "location", source: "phone", timestamp: fix.timestamp, elapsedSeconds: mapping.elapsed,
            payload: payload)
          try archive.append(event)
          gpsBarrier = false
          _ = live.gpsFreshness.receive(id: event.eventId, age: max(0, uptime - mapping.acquisition), at: clock)
          live.gpsAccuracy = WorkoutStreamStatus.accuracy(fix.horizontalAccuracy)
          live.gpsStatus = WorkoutStreamStatus.location(live.gpsAccuracy)
          locations.append(
            WorkoutCompanionLocation(
              timestamp: fix.timestamp, latitude: fix.coordinate.latitude, longitude: fix.coordinate.longitude,
              altitude: fix.altitude, accuracy: fix.horizontalAccuracy, verticalAccuracy: fix.verticalAccuracy,
              speed: fix.speed, course: fix.course))
          if locations.count > 32 { locations.removeFirst(locations.count - 32) }
          try healthSeconds.append(event)

        }
      } catch { captureFailed(error) }
    }
    private func receiveObservations(_ observations: [WorkoutCompanionObservation], id: String, source: String) throws {
      guard let archive, try !archive.store.isWorkoutDeleted(id: id) else { return }
      let record = try archive.metadata(id: id)
      let endedAt = record.endedAt ?? (self.id == id ? pendingStopTiming?.timestamp : nil)
      let start = try WorkoutCoding.date(record.startedAt)
      let end = try endedAt.map(WorkoutCoding.date)
      for observation in observations {
        let inRide: Bool
        if observation.metric == "activeEnergyKcal" || observation.metric == "basalEnergyKcal" {
          // Preserve interval originals; analytics clips their energy to the ride window.
          inRide =
            observation.start < observation.timestamp && observation.timestamp > start
            && (end.map { observation.start < $0 } ?? true)
        } else {
          inRide = observation.timestamp >= start && (end.map { observation.timestamp <= $0 } ?? true)
        }
        guard inRide, try !archive.hasEvent(id: id, eventID: observation.id)
        else { continue }
        var payload: [String: WorkoutJSON] = [
          "healthKitIdentifier": .string(observation.identifier), "sampleUUID": .string(observation.id),
          "sampleStart": .string(WorkoutCoding.preciseTimestamp(observation.start)),
          "sampleEnd": .string(WorkoutCoding.preciseTimestamp(observation.timestamp)),
          "value": .number(observation.value), "unit": .string(observation.unit),
          observation.metric: .number(observation.value),
          "representation": .string("rawQuantity"),
        ]
        if let parent = observation.parentID {
          payload["parentSampleUUID"] = .string(parent)
          payload["representation"] = .string("rawSeries")
        }
        var event = try WorkoutEvent(
          workoutId: id, kind: "health", source: source, timestamp: observation.timestamp,
          payload: payload, eventId: observation.id)
        event.timestamp = WorkoutCoding.preciseTimestamp(observation.timestamp)
        _ = try WorkoutHealthObservation.append(
          event, archive: archive, dataOrigin: observation.dataOrigin ?? "app.powerlog")
        if self.id == id, phase == "running", Date().timeIntervalSince(observation.timestamp) < 15 {
          archive.store.liveEvidence.sensor(
            event, identity: try PowerLogStore.physicalIdentity(event),
            acquiredAt: clock - max(0, Date().timeIntervalSince(observation.timestamp)))
        }
        if observation.metric == "heartRateBpm", self.id == id {
          live.receiveHeartRate(
            ["heartRateBpm": observation.value], identity: observation.id,
            acquiredAt: clock - max(0, Date().timeIntervalSince(observation.timestamp)), now: clock)
        }
      }
    }
    private func receive(_ message: WorkoutCompanionMessage, reply: @escaping (WorkoutCompanionMessage) -> Void) {
      if let command = message.commandID, let existing = acknowledgedCommands[command] {
        reply(existing)
        return
      }
      if let expiry = message.expiresAt, expiry < Date() {
        reply(WorkoutCompanionMessage(kind: .acknowledgement, error: "The action expired."))
        return
      }
      if message.kind == .cancelStart {
        cancelledStarts.insert(message.startRequestID)
        cancelledStarts.insert(message.commandID)
        let matches =
          (message.startRequestID != nil && message.startRequestID == watchStartRequestID)
          || (message.commandID != nil && message.commandID == startAttempt)
        if matches, message.rideID == nil || message.rideID == id, rideInProgress {
          do { _ = try stop(expectedID: id, discard: true) } catch {
            self.error = error.localizedDescription
            publish()
          }
        }
        reply(WorkoutCompanionMessage(kind: .acknowledgement, rideID: message.rideID, commandID: message.commandID))
        return
      }
      if message.kind == .startRequest, let requested = message.options {
        guard !cancelledStarts.contains(message.commandID) else {
          reply(
            WorkoutCompanionMessage(kind: .acknowledgement, commandID: message.commandID, error: "Start cancelled."))
          return
        }
        start(
          indoor: requested.indoor, useWatch: true, saveToHealth: requested.saveToHealth,
          recordGPS: requested.recordGPS, startRequestID: message.commandID
        ) { result in
          switch result {
          case .success:
            reply(
              WorkoutCompanionMessage(
                kind: .state, rideID: self.id, commandID: message.commandID, phase: self.phase,
                stateRevision: self.stateRevision, active: self.active))
          case .failure(let error):
            reply(WorkoutCompanionMessage(kind: .acknowledgement, error: error.localizedDescription))
          }
        }
        return
      }
      do {
        guard let target = message.rideID, let archive else {
          throw WorkoutDataError.invalid("Unknown companion ride.")
        }
        if try archive.store.isWorkoutDeleted(id: target) {
          reply(WorkoutCompanionMessage(kind: .acknowledgement, rideID: target))
          return
        }
        guard try archive.metadata(id: target).watchEnabled else {
          throw WorkoutDataError.invalid("Unknown companion ride.")
        }
        if message.kind == .measurements {
          if target == id, let heart = message.readings?["heartRateBpm"], let at = message.sampleTime,
            Date().timeIntervalSince(at) < 15
          {
            live.receiveHeartRate(
              ["heartRateBpm": heart], identity: UUID().uuidString.lowercased(),
              acquiredAt: clock - max(0, Date().timeIntervalSince(at)), now: clock)
          }
          try receiveObservations(message.observations ?? [], id: target, source: "watch")
          reply(WorkoutCompanionMessage(kind: .acknowledgement, rideID: target, acknowledgement: message.sequence))
          return
        }
        if message.kind == .health {
          try setHealth(
            id: target, state: message.healthState ?? "unknown", healthID: message.healthID, reason: message.error)
          reply(WorkoutCompanionMessage(kind: .acknowledgement, rideID: target))
          return
        }
        guard message.kind == .command, target == id else {
          throw WorkoutDataError.invalid("The selected ride changed.")
        }
        processingCompanionCommand = true
        defer { processingCompanionCommand = false }
        switch message.action {
        case "pause": _ = try pause(expectedID: target)
        case "resume": _ = try resume(expectedID: target)
        case "lap": _ = try lap(expectedID: target)
        case "stop": _ = try stop(expectedID: target)
        case "discard": _ = try stop(expectedID: target, discard: true)
        default: throw WorkoutDataError.invalid("Unknown companion action.")
        }
        let terminal =
          ["stop", "discard"].contains(message.action ?? "") && pendingTerminal?.rideID == target
          ? pendingTerminal : nil
        let result = WorkoutCompanionMessage(
          kind: .state, rideID: target, commandID: message.commandID, action: message.action, phase: phase,
          stateRevision: stateRevision,
          active: active, healthSeconds: terminal?.healthSeconds, locations: terminal?.locations)
        remember(message.commandID, result: result)
        reply(result)
      } catch {
        reply(
          WorkoutCompanionMessage(kind: .acknowledgement, rideID: message.rideID, error: error.localizedDescription))
      }
    }
    private func remember(_ id: String?, result: WorkoutCompanionMessage) {
      guard let id else { return }
      if acknowledgedCommands.count >= 64 { acknowledgedCommands.removeAll() }
      acknowledgedCommands[id] = result
    }
    private func sendState(id: String, action: String? = nil, phase: String? = nil) {
      let terminal = phase == "completed" || phase == "idle"
      guard terminal || !processingCompanionCommand else { return }
      let message = WorkoutCompanionMessage(
        kind: .state, rideID: id, commandID: UUID().uuidString.lowercased(),
        expiresAt: Date().addingTimeInterval(terminal ? 60 : 5),
        action: action, phase: phase ?? self.phase, stateRevision: stateRevision, elapsed: elapsed, active: active,
        healthSeconds: terminal ? finalHealthSeconds : nil, locations: terminal ? finalLocations : nil)
      if terminal {
        finalHealthSeconds = []
        finalLocations = []
        pendingTerminal = message
        pendingTerminalUntil = Date().addingTimeInterval(60)
        replayTerminal()
      } else {
        companion.send(message)
      }
    }
    private func setHealth(id: String, state: String, healthID: String?, reason: String?) throws {
      guard let archive else { throw WorkoutDataError.invalid("Recording storage is unavailable.") }
      let stored = try archive.update(id: id, healthKitState: state, healthKitUUID: healthID, healthReason: reason)
      if self.id == id {
        healthState = stored.healthKitState
      }
      publish()
    }
    private func persist() throws {
      guard let id else { return }
      _ = try archive?.update(
        id: id, phase: phase,
        ownerTiming: WorkoutOwnerTiming(
          timestamp: WorkoutCoding.timestamp(Date()), elapsedSeconds: elapsed, timerSeconds: min(active, elapsed)),
        elapsedSeconds: elapsed, onlyIfChanged: true)
    }
    private func synchronizeCompanion() {
      guard let id, companion.reachable, rideWatch else { return }
      if phase == "running" {
        forwardSeconds()
        return
      }
      guard ["paused", "interrupted"].contains(phase), clock - lastRoutineSend >= Self.watchRoutineSeconds else {
        return
      }
      lastRoutineSend = clock
      sendState(id: id)
    }
    private func tick() {
      if phase == "running" { forwardSeconds() } else { synchronizeCompanion() }
      if phase == "running", clock - lastCheckpoint >= 5 {
        lastCheckpoint = clock
        try? persist()
      }
      if rideInProgress { publish() }
      replayTerminal()
    }
    private func replayTerminal() {
      guard let message = pendingTerminal else { return }
      guard Date() <= pendingTerminalUntil else {
        pendingTerminal = nil
        return
      }
      guard companion.reachable else { return }
      companion.send(message) { result in
        if case .success(let response) = result, response.error == nil,
          self.pendingTerminal?.commandID == message.commandID
        {
          self.pendingTerminal = nil
        }
      }
    }
    func flushStorage() {
      queue.async {
        try? self.capture?.barrier()
        self.archive?.store.scheduleCheckpoint()
      }
    }
    func setBackground(_ value: Bool) {
      background = value
      if !value { publish() }
    }
    func addExampleRides() throws -> [String: Any] {
      guard !rideInProgress, let archive else { throw WorkoutDataError.invalid("Finish the current ride first.") }
      let added = try WorkoutExampleRides.write(archive: archive)
      publish()
      return ["added": added.count]
    }
    func list(limit: Int = 100, beforeStartedAt: String? = nil, beforeID: String = "") throws -> [[String: Any]] {
      guard let archive else { throw WorkoutDataError.invalid("Recording storage is unavailable.") }
      return try archive.list(limit: limit, beforeStartedAt: beforeStartedAt, beforeID: beforeID).map {
        try $0.dictionary.merging(archive.healthProjection(id: $0.id)) { _, new in new }
      }
    }
    func read(
      _ id: String, distanceSource: String = "auto", requestID: String, row: String,
      completion: @escaping (Result<[String: Any], Error>) -> Void
    ) throws {
      guard let archive else { throw WorkoutDataError.invalid("Recording storage is unavailable.") }
      let record = try archive.metadata(id: id)
      try WorkoutSummaryReads.shared.read(
        archive: archive, record: record, id: requestID, row: row, distanceSource: record.distanceSource
      ) { result in
        completion(result.map { ["metadata": record.dictionary, "summary": $0] })
      }
    }
    func refreshHealth(_ requestedID: String, completion: @escaping (Result<[String: Any], Error>) -> Void) {
      do {
        guard let archive else { throw WorkoutDataError.invalid("Recording storage is unavailable.") }
        let record = try archive.metadata(id: requestedID)
        guard record.phase == "completed", let end = record.endedAt else {
          throw WorkoutDataError.invalid("Finish the ride before refreshing Health.")
        }
        let start = try WorkoutCoding.date(record.startedAt)
        health.readback(
          start: start, end: try WorkoutCoding.date(end),
          receive: { observations in
            try self.receiveObservations(observations, id: requestedID, source: record.watchEnabled ? "watch" : "phone")
          }
        ) { result in
          self.publish()
          completion(result.map { self.state() })
        }
      } catch { completion(.failure(error)) }
    }
    func deleteWorkout(_ requestedID: String) throws -> [String: Any] {
      guard let archive else { throw WorkoutDataError.invalid("Recording storage is unavailable.") }
      let record = try archive.metadata(id: requestedID)
      guard !["running", "paused", "preparing", "interrupted"].contains(record.phase) else {
        throw WorkoutDataError.invalid("Finish or discard this ride first.")
      }
      guard !PowerLogReadLease.isReading(store: archive.store, id: requestedID) else {
        throw WorkoutDataError.invalid("Wait for the export to finish.")
      }
      try archive.store.markWorkoutDeleted(id: requestedID)
      if id == requestedID {
        id = nil
        options = nil
        phase = "idle"
      }
      cleanup(requestedID)
      publish()
      return state()
    }
    private func cleanup(_ id: String) {
      fileQueue.async { [weak self] in
        guard let archive = self?.archive else { return }
        do { while try !archive.cleanupDeletedWorkoutPage(id: id) {} } catch {
          self?.queue.async { self?.error = error.localizedDescription }
        }
      }
    }
  }
#endif
