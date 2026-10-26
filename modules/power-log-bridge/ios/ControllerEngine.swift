import Foundation
import CoreBluetooth
import PowerLogCore

final class ControllerEngine: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
  static let shared = ControllerEngine()
  let queue = DispatchQueue(label: "app.powerlog.controller", qos: .utility)
  private static let uart = CBUUID(string: "6e400001-b5a3-f393-e0a9-e50e24dcca9e")
  private static let writeUUID = CBUUID(string: "6e400002-b5a3-f393-e0a9-e50e24dcca9e")
  private static let notifyUUID = CBUUID(string: "6e400003-b5a3-f393-e0a9-e50e24dcca9e")
  private var central: CBCentralManager?
  private var selected: CBPeripheral?
  private var devices: [UUID: CBPeripheral] = [:]
  private var writer: CBCharacteristic?
  private var notifier: CBCharacteristic?
  private var timer: DispatchSourceTimer?
  private var replyPoll: DispatchWorkItem?
  private let session: TelemetrySession
  private var pending: (request: ControllerRequest, sent: Double)?
  private var waitingToSend: ControllerRequest?
  private var sendBlockedAt: Double?
  private var lastSample: Double?
  private var shouldConnect = false
  private var verified: Bool { connectionAttempt.stage == .ready }
  private var knownControllers: [String: ControllerIdentity] = [:]
  private var deviceRSSI: [UUID: Int] = [:]
  private var scanRequested = false
  private var discovery: TelemetryDiscovery?
  private var discoveryGeneration: Int64 = 0
  private var scanDeadline: Double? { discovery?.deadline }
  private var background = true
  private var lastPresentation: Double = 0
  private var latestSample: [String: Any]?
  private var resumeID: UUID?
  private var connectionDeadline: Double? { session.state.attemptDeadline?.doubleValue }
  private var reconnectDue: Double? { session.state.reconnectDue?.doubleValue }
  private var reconnectAttempts = 0
  private var connectionAttempt: TelemetryTransportState { session.state }
  private var stableSince: Double?
  private var hz: Double = 2
  private var workoutSampling = WorkoutSamplingOwner()
  var currentSampleRate: Double { hz }
  private var nextPoll: Double = 0
  private var sessionStarted: Double = 0
  private var captureClock = TelemetryCaptureClock(origin: ProcessInfo.processInfo.systemUptime)
  private var connectionEpoch = UUID().uuidString.lowercased()
  private var sessionSamples = 0
  private var status = "idle"
  private var errorMessage: String?
  private var recoveryErrorMessage: String?
  private var storeError: String?
  private var diagnosticLog: TelemetryDiagnosticLog?
  private let bluetoothTrace = BluetoothTrace()
  private var diagnosticMetrics = TelemetryDiagnosticMetrics()
  private let diagnosticStarted = ProcessInfo.processInfo.systemUptime
  private var notificationErrors = 0
  private var maxStorageWriteMs = 0.0
  private var scheduledTick: Double?
  private var maxQueueDelaySeconds = 0.0
  private var sinks: [UUID: (String, [String: Any]) -> Void] = [:]
  private let defaults: UserDefaults
  private let monotonicNow: () -> Double
  private let scheduleTimer: ((Double, @escaping () -> Void) -> Void)?
  private let schedulePoll: ((Double, DispatchWorkItem) -> Void)?

  init(
    defaults: UserDefaults = .standard,
    controllerProtocol: any ControllerProtocol = CycControllerDecoder(),
    monotonicNow: @escaping () -> Double = { ProcessInfo.processInfo.systemUptime },
    scheduleTimer: ((Double, @escaping () -> Void) -> Void)? = nil,
    schedulePoll: ((Double, DispatchWorkItem) -> Void)? = nil,
    diagnosticLog: @escaping () throws -> TelemetryDiagnosticLog = { try TelemetryDiagnosticLog() }
  ) {
    self.session = TelemetrySession(protocol: controllerProtocol)
    self.defaults = defaults
    self.monotonicNow = monotonicNow
    self.scheduleTimer = scheduleTimer
    self.schedulePoll = schedulePoll
    super.init()
    queue.async {
      if let data = self.defaults.data(forKey: "PowerLog.knownControllers"),
        let records = try? JSONDecoder().decode([String: ControllerProfileRecord].self, from: data),
        let known = try? records.mapValues({ try $0.restore() })
      {
        self.knownControllers = known
      }
      do { self.diagnosticLog = try diagnosticLog() } catch { /* Logging must not disable ride capture. */  }
      self.note(.engineStarted)
    }
  }

  private var now: Double { monotonicNow() }

  func addSink(id: UUID, sink: @escaping (String, [String: Any]) -> Void) { sinks[id] = sink }
  func removeSink(id: UUID) { sinks.removeValue(forKey: id) }
  func captureResult(_ failure: Error?) {
    let next = failure.map { WorkoutRideFailure.classify($0, stage: "storage").message }
    guard storeError != next else { return }
    storeError = next
    note(.recordingError, reason: .storageFailure, error: failure, fields: ["recordingFailed": next != nil])
    emitState()
  }
  private func emit(_ event: String, _ body: [String: Any]) {
    guard !background else { return }
    for sink in sinks.values { sink(event, body) }
  }
  private func emitState() { emit("onState", state()) }

  func state() -> [String: Any] {
    var value: [String: Any] = ["status": status]
    if let selected {
      value["deviceId"] = selected.identifier.uuidString
      value["deviceName"] = selected.name ?? "CYC bike"
      if let identity = knownControllers[selected.identifier.uuidString] {
        value["controllerModel"] = identity.controllerModel
        value["firmwareLabel"] = identity.firmwareLabel
      }
    }
    let displayError = ControllerErrorPresentation.displayError(
      storage: storeError, other: errorMessage,
      recovery: recoveryErrorMessage, reconnecting: status == "reconnecting")
    if let error = displayError.message { value["error"] = error }
    value["recoverableConnectionError"] = displayError.recoverable
    return value
  }

  func diagnostics() -> [String: Any] {
    let current = now
    return [
      "status": status, "requestedHz": hz,
      "connectionAttempts": diagnosticMetrics.connectionAttempts, "reconnects": diagnosticMetrics.reconnects,
      "lastSampleAgeSeconds": diagnosticMetrics.sampleAge(at: current) as Any? ?? NSNull(),
      "lastGapSeconds": diagnosticMetrics.lastGapSeconds as Any? ?? NSNull(),
      "recentSampleHz": diagnosticMetrics.recentSampleHz(at: current) as Any? ?? NSNull(),
    ]
  }

  func bluetoothTraceState() -> [String: Any] {
    let state = bluetoothTrace.status(now: now)
    return [
      "capturing": state.capturing, "eventCount": state.eventCount, "byteCount": state.byteCount,
      "stopReason": state.stopReason as Any? ?? NSNull(), "lastEvent": state.lastEvent as Any? ?? NSNull(),
    ]
  }

  func startBluetoothTrace() {
    bluetoothTrace.start(now: now)
    trace("capture_started", "platform=ios; status=\(status); requests=111,50")
  }

  func stopBluetoothTrace() { bluetoothTrace.stop() }
  func clearBluetoothTrace() { bluetoothTrace.clear() }
  func readBluetoothTrace() -> String { bluetoothTrace.exportJson(now: now) }

  func readDiagnosticLog(_ completion: @escaping (Result<String, Error>) -> Void) {
    queue.async {
      let log = self.diagnosticLog
      DispatchQueue.global(qos: .utility).async {
        completion(
          Result {
            guard let log else { throw ControllerIOError.invalid("Private diagnostic storage is unavailable.") }
            return try log.read()
          })
      }
    }
  }

  private func trace(_ event: String, _ detail: String, bytes: Data? = nil) {
    guard bluetoothTrace.status(now: now).capturing else { return }
    bluetoothTrace.record(
      now: now, event: event, detail: detail, bytes: bytes.map { WorkoutCoreCodec.bytes($0) })
  }

  private func note(
    _ event: TelemetryDiagnosticEvent, reason: TelemetryDiagnosticReason? = nil, error: Error? = nil,
    fields: [String: Any] = [:]
  ) {
    let current = now
    var data: [String: Any] = [
      "status": status, "requestedHz": hz, "sampleCount": diagnosticMetrics.sampleCount,
      "connectionAttempts": diagnosticMetrics.connectionAttempts, "reconnects": diagnosticMetrics.reconnects,
      "requestTimeouts": diagnosticMetrics.requestTimeouts, "decoderDiscardedBytes": session.discardedBytes,
      "linkSamples": diagnosticMetrics.linkSamples, "background": background,
      "connectionGeneration": connectionAttempt.generation, "transportStage": connectionAttempt.stage.name,
    ]
    if let duration = diagnosticMetrics.connectionSeconds(at: current) { data["connectionSeconds"] = duration }
    if let age = diagnosticMetrics.sampleAge(at: current) { data["lastSampleAgeSeconds"] = age }
    if let pending {
      data["request"] = pending.request.name
      data["requestAgeSeconds"] = max(0, current - pending.sent)
    }
    if let sendBlockedAt { data["writeBlockedSeconds"] = max(0, current - sendBlockedAt) }
    if let reason { data["reason"] = reason.rawValue }
    data.merge(TelemetryDiagnosticLog.errorFields(error)) { _, value in value }
    data.merge(fields) { _, value in value }
    trace(
      event.rawValue,
      "stage=\(connectionAttempt.stage.name); reason=\(reason?.rawValue ?? "none"); "
        + "rejection=\(fields["rejection"] ?? "none"); error=\((error as NSError?)?.code ?? 0)")
    diagnosticLog?.append(event, elapsedSeconds: max(0, current - diagnosticStarted), fields: data)
  }

  private func summarizeIfDue() {
    let current = now
    guard shouldConnect, current >= diagnosticMetrics.nextSummary else { return }
    var fields: [String: Any] = [
      "notificationErrors": notificationErrors,
      "pendingBytes": session.pendingBytes, "queueDelaySeconds": maxQueueDelaySeconds,
      "storageWriteMs": maxStorageWriteMs,
    ]
    if let rate = diagnosticMetrics.recentSampleHz(at: current) { fields["recentSampleHz"] = rate }
    if let latency = diagnosticMetrics.responseLatencyMs {
      fields["latencyMeanMs"] = latency["mean"]
      fields["latencyMaxMs"] = latency["max"]
    }
    fields["cpuSeconds"] = Self.processCPUSeconds()
    fields["thermalState"] = ProcessInfo.processInfo.thermalState.rawValue
    note(.telemetrySummary, fields: fields)
    diagnosticMetrics.resetWindow(at: current)
    maxQueueDelaySeconds = 0
    maxStorageWriteMs = 0
  }

  private func cancel(_ peripheral: CBPeripheral, reason: TelemetryDiagnosticReason) {
    if peripheral.identifier == selected?.identifier {
      if reconnectDue != nil {
        try! session.cancelDuringBackoff()
      } else if !connectionAttempt.cancellationPending {
        try! session.manualDisconnect(cancellationRequired: true)
      }
      diagnosticMetrics.requestedCancellation(reason: reason, at: now)
      note(.disconnectRequested, reason: diagnosticMetrics.cancellationReason ?? reason, fields: ["initiator": "app"])
    } else {
      note(.disconnectRequested, reason: reason, fields: ["initiator": "app"])
    }
    central?.cancelPeripheralConnection(peripheral)
  }

  private func ensureCentral() {
    guard central == nil else { return }
    central = CBCentralManager(
      delegate: self, queue: queue,
      options: [
        CBCentralManagerOptionRestoreIdentifierKey: "app.powerlog.controller.central",
        CBCentralManagerOptionShowPowerAlertKey: true,
      ])
  }

  private func ensureTimer() {
    if let scheduleTimer {
      scheduleTimer(0) { [weak self] in self?.tick() }
      return
    }
    guard timer == nil else { return }
    let timer = DispatchSource.makeTimerSource(queue: queue)
    timer.schedule(deadline: .now())
    timer.setEventHandler { [weak self] in self?.tick() }
    self.timer = timer
    timer.resume()
  }

  func startScan() throws {
    guard !shouldConnect else { throw ControllerIOError.invalid("Disconnect before scanning for another controller.") }
    ensureCentral()
    if let central, central.state != .unknown && central.state != .resetting && central.state != .poweredOn {
      throw bluetoothFailure(central.state)
    }
    scanRequested = true
    discoveryGeneration += 1
    discovery = try TelemetryDiscovery.companion.begin(generation: discoveryGeneration, now: now)
    status = "scanning"
    errorMessage = nil
    recoveryErrorMessage = nil
    devices.removeAll()
    ensureTimer()
    scanIfReady()
    emitState()
  }

  func stopScan() {
    scanRequested = false
    discovery = nil
    central?.stopScan()
    if status == "scanning" { status = "idle" }
    stopTimerIfIdle()
    emitState()
  }

  private func scanIfReady() {
    guard scanRequested, let central, central.state == .poweredOn else { return }
    // Both verified families advertise UART. Always provide its UUID so discovery also
    // works when iOS treats a locked/mirrored app as background for Bluetooth scanning.
    central.scanForPeripherals(
      withServices: [Self.uart],
      options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
  }

  func connect(deviceID: String, rate: Double) throws {
    let rate = try WorkoutRecordingPolicy.sampleHz(rate)
    guard let id = UUID(uuidString: deviceID) else {
      throw ControllerIOError.invalid("Invalid local Bluetooth device identifier.")
    }
    guard !shouldConnect else {
      throw ControllerIOError.invalid("Disconnect the current controller before connecting again.")
    }
    guard !connectionAttempt.cancellationPending else {
      throw ControllerIOError.invalid("Bluetooth is still finishing the previous disconnection. Try again shortly.")
    }
    ensureCentral()
    guard let central, central.state == .poweredOn else {
      throw bluetoothFailure(central?.state ?? .unknown)
    }
    guard let device = devices[id] ?? central.retrievePeripherals(withIdentifiers: [id]).first else {
      throw ControllerIOError.invalid("Device is unknown to iOS. Scan and select it again.")
    }
    stopScan()
    hz = workoutSampling.connectionRate(rate)
    selected = device
    shouldConnect = true
    reconnectAttempts = 0
    sessionStarted = now
    sessionSamples = 0
    captureClock = TelemetryCaptureClock(origin: sessionStarted)
    latestSample = nil
    MonitorDataStore.shared.beginLive(
      startedAt: WorkoutCoding.timestamp(captureClock.wallOrigin), monotonic: sessionStarted, id: captureClock.sessionID
    )
    diagnosticMetrics.beginSession(at: now)

    notificationErrors = 0
    note(.connectRequested)
    defaults.set(id.uuidString, forKey: "PowerLog.selectedPeripheral")
    defaults.set(hz, forKey: "PowerLog.pollHz")
    errorMessage = nil
    recoveryErrorMessage = nil
    ensureTimer()
    beginConnection()
  }

  func setSampleRate(_ rate: Double) throws {
    hz = try WorkoutRecordingPolicy.sampleHz(rate)
    nextPoll = max(now, (pending?.sent ?? lastSample ?? now) + 1 / rate)
    defaults.set(rate, forKey: "PowerLog.pollHz")
    paceNextPoll()
    scheduleNextTick()
    emitState()
  }

  func setWorkoutSamplingOwner(_ id: String?, rate: Double?) {
    let previous = workoutSampling.id
    let persisted = defaults.double(forKey: "PowerLog.pollHz")
    let restored = SampleRates.shared.accepts(value: KotlinDouble(value: persisted)) ? persisted : hz
    workoutSampling.update(id: id, rate: rate ?? restored)
    if previous != nil, id == nil, status == "reconnecting" { try? disconnect() }
    if let admitted = workoutSampling.rate, hz != admitted {
      hz = admitted
      nextPoll = max(now, (pending?.sent ?? lastSample ?? now) + 1 / hz)
      defaults.set(hz, forKey: "PowerLog.pollHz")
      paceNextPoll()
      scheduleNextTick()
    }
  }

  func disconnect() throws {
    if let selected, selected.state != .disconnected || connectionAttempt.systemInitiated {
      cancel(selected, reason: .manualDisconnect)
    }
    shouldConnect = false
    scanRequested = false
    discovery = nil
    try session.manualDisconnect(cancellationRequired: connectionAttempt.cancellationPending)
    central?.stopScan()
    defaults.removeObject(forKey: "PowerLog.selectedPeripheral")
    clearTransport()
    status = "idle"
    errorMessage = nil
    recoveryErrorMessage = nil
    note(.sessionStopped, reason: .manualDisconnect)
    stopTimerIfIdle()
    emitState()
  }

  func restoreOnLaunch(central: Bool) {
    guard central else { return }
    note(.restored)
    ensureCentral()
    ensureTimer()
  }

  func resumeRememberedConnection() {
    guard !shouldConnect, resumeID == nil, let saved = defaults.string(forKey: "PowerLog.selectedPeripheral"),
      let id = UUID(uuidString: saved)
    else { return }
    resumeID = id
    ensureCentral()
    ensureTimer()
    resumeIfPossible()
  }

  private func resumeIfPossible() {
    guard let id = resumeID, let central, central.state == .poweredOn else { return }
    resumeID = nil
    guard !shouldConnect, let device = central.retrievePeripherals(withIdentifiers: [id]).first else { return }
    selected = device
    selected?.delegate = self
    shouldConnect = true
    hz = workoutSampling.connectionRate(min(8, max(1, defaults.double(forKey: "PowerLog.pollHz"))))
    reconnectAttempts = 1
    sessionStarted = now
    sessionSamples = 0
    captureClock = TelemetryCaptureClock(origin: sessionStarted)
    latestSample = nil
    diagnosticMetrics.beginSession(at: now)
    MonitorDataStore.shared.beginLive(
      startedAt: WorkoutCoding.timestamp(captureClock.wallOrigin), monotonic: sessionStarted, id: captureClock.sessionID
    )

    notificationErrors = 0
    note(.resumed)
    status = "reconnecting"
    beginConnection()
    emitState()
  }

  private func emitSample(_ sample: [String: Any]) {
    guard let value = WorkoutLiveFreshness.sampleEvent(sample) else { return }
    emit("onSample", value)
  }

  func setBackground(_ background: Bool) {
    guard self.background != background else { return }
    self.background = background
    note(.backgroundChanged)
    if scanRequested {
      central?.stopScan()
      scanIfReady()
    }
    tick()
    if !background {
      if let latestSample { emitSample(latestSample) }
      emitState()
    }
    updateBackgroundHold()
  }

  #if canImport(UIKit)
    private var backgroundHoldWanted = false
    /// This grants a finite completion window, not an indefinite background runtime.
    private func updateBackgroundHold() {
      let wanted = background && shouldConnect && !verified
      guard wanted != backgroundHoldWanted else { return }
      backgroundHoldWanted = wanted
      DispatchQueue.main.async { ControllerBackgroundHold.shared.set(active: wanted) }
    }
  #else
    private func updateBackgroundHold() {}
  #endif

  func willTerminate() {
    note(.sessionStopped, reason: .appTerminating)
  }

  private func stopTimerIfIdle() {
    if !shouldConnect && !scanRequested {
      timer?.cancel()
      timer = nil
    }
  }

  private func beginConnection(systemInitiated: Bool = false, forceConnect: Bool = false) {
    guard shouldConnect, let central, central.state == .poweredOn, let selected,
      !connectionAttempt.cancellationPending, connectionAttempt.stage == .idle
    else { return }
    clearTransport()
    guard try! session.begin(now: now, systemInitiated: systemInitiated) else { return }
    diagnosticMetrics.beginAttempt(at: now)
    selected.delegate = self
    status = reconnectAttempts == 0 ? "connecting" : "reconnecting"
    note(.connectionAttempt, fields: ["systemReconnect": systemInitiated])
    if selected.state == .connected, !forceConnect {
      handleConnected(selected)
    } else if !systemInitiated {
      // iOS 17/macOS 14 can start reconnecting before delivering the disconnect callback.
      // That attempt is adopted below or explicitly cancelled; never run two retry owners.
      // https://developer.apple.com/documentation/corebluetooth/cbconnectperipheraloptionenableautoreconnect
      if #available(macOS 14.0, *) {
        central.connect(selected, options: [CBConnectPeripheralOptionEnableAutoReconnect: true])
      } else {
        central.connect(selected, options: nil)
      }
    }
    emitState()
    scheduleNextTick()
  }

  private func clearTransport() {
    replyPoll?.cancel()
    replyPoll = nil
    writer = nil
    notifier = nil
    pending = nil
    waitingToSend = nil
    sendBlockedAt = nil
    lastSample = nil
    stableSince = nil
  }

  private func retry(
    _ message: String, reason: TelemetryDiagnosticReason, error: Error? = nil,
    systemIsReconnecting: Bool = false, disconnectCallbackCompleted: Bool = false
  ) {
    guard shouldConnect, reconnectDue == nil else { return }
    let stableTelemetrySeconds = stableSince.map { now - $0 }
    let nextAttempt = reconnectAttempts + 1
    let plan = try! TelemetryReconnectPolicy.shared.plan(
      attempt: Int32(nextAttempt), activeRide: workoutSampling.id != nil,
      callbackCompleted: disconnectCallbackCompleted,
      peerOrSystem: reason == .linkDisconnected && diagnosticMetrics.lastDisconnect?.initiator == "peer_or_system",
      stableTelemetrySeconds: stableTelemetrySeconds.map { KotlinDouble(value: $0) },
      systemIsReconnecting: systemIsReconnecting, systemAttemptPending: connectionAttempt.systemInitiated,
      peripheralIsDisconnected: selected?.state == .disconnected)
    if reason == .responseTimeout || reason == .expiredReply { diagnosticMetrics.timedOut() }
    note(.transportError, reason: reason, error: error)
    guard let plan else {
      fail(
        "Reconnect limit reached. \(message)", reason: .retryLimit,
        cancelConnection: !disconnectCallbackCompleted || systemIsReconnecting)
      return
    }
    try! session.scheduleRetry(plan: plan, now: now)
    if plan.cancelBeforeRetry, let selected { cancel(selected, reason: reason) }
    clearTransport()
    recoveryErrorMessage = message
    reconnectAttempts = min(nextAttempt, 6)
    status = "reconnecting"
    diagnosticMetrics.scheduledReconnect()
    note(
      .reconnectScheduled, reason: reason,
      fields: [
        "retryAttempt": reconnectAttempts, "retryDelaySeconds": plan.delaySeconds,
        "systemReconnect": plan.adoptSystemReconnect,
      ])
    if plan.delaySeconds == 0 {
      beginConnection(systemInitiated: plan.adoptSystemReconnect, forceConnect: !plan.adoptSystemReconnect)
    } else {
      emitState()
      scheduleNextTick()
    }
    updateBackgroundHold()
  }

  private func fail(
    _ message: String, reason: TelemetryDiagnosticReason, error: Error? = nil, cancelConnection: Bool = true
  ) {
    defer { updateBackgroundHold() }
    note(.transportError, reason: reason, error: error)
    if cancelConnection, let selected, selected.state != .disconnected || connectionAttempt.systemInitiated {
      cancel(selected, reason: reason)
    }
    if workoutSampling.id != nil, [.bluetoothUnavailable, .cancellationDeadline, .retryLimit].contains(reason) {
      clearTransport()
      recoveryErrorMessage = message
      errorMessage = nil
      status = "reconnecting"
      if central?.state == .poweredOn {
        let plan = try! TelemetryReconnectPolicy.shared.plan(
          attempt: 6, activeRide: true, callbackCompleted: false, peerOrSystem: false,
          stableTelemetrySeconds: nil, systemIsReconnecting: false,
          systemAttemptPending: connectionAttempt.systemInitiated,
          peripheralIsDisconnected: selected?.state == .disconnected)!
        try! session.scheduleRetry(plan: plan, now: now)
      }
      ensureTimer()
      emitState()
      return
    }
    shouldConnect = false
    try! session.manualDisconnect(cancellationRequired: connectionAttempt.cancellationPending)
    defaults.removeObject(forKey: "PowerLog.selectedPeripheral")
    clearTransport()
    status = "error"
    errorMessage = message
    recoveryErrorMessage = nil
    note(.sessionStopped, reason: reason)
    stopTimerIfIdle()
    emitState()
  }

  private func tick() {
    defer { scheduleNextTick() }
    let current = now
    if let scheduledTick { maxQueueDelaySeconds = max(maxQueueDelaySeconds, max(0, current - scheduledTick)) }
    summarizeIfDue()
    if let scanDeadline, current >= scanDeadline { stopScan() }
    guard shouldConnect else { return }
    guard central?.state == .poweredOn else { return }
    let action = try! session.check(
      now: current, responseSentAt: pending.map { KotlinDouble(value: $0.sent) },
      writeBlockedAt: sendBlockedAt.map { KotlinDouble(value: $0) },
      nativeDisconnecting: selected?.state == .disconnecting)
    if handle(action) { return }
    if reconnectDue != nil || connectionAttempt.cancellationFailed { return }
    if let request = waitingToSend {
      send(request)
      return
    }
    if verified, pending == nil, current >= nextPoll { send(.telemetry) }
  }

  private func scheduleNextTick() {
    guard timer != nil || scheduleTimer != nil else { return }
    let current = now
    let transportDeadline =
      connectionAttempt.cancellationFailed
      ? connectionDeadline : connectionDeadline ?? connectionAttempt.cancellationDeadline?.doubleValue
    let delay = TelemetryTiming.shared.pollingDelay(
      now: current, poweredOn: central?.state == .poweredOn,
      verified: verified, scanning: scanRequested, scanDeadline: scanDeadline.map { KotlinDouble(value: $0) },
      reconnectDue: reconnectDue.map { KotlinDouble(value: $0) },
      attemptDeadline: transportDeadline.map { KotlinDouble(value: $0) },
      cancellationPending: connectionAttempt.cancellationPending || selected?.state == .disconnecting,
      responseDeadline: pending.map { KotlinDouble(value: $0.sent + TelemetryTiming.shared.telemetryTimeoutSeconds) },
      writeDeadline: sendBlockedAt.map { KotlinDouble(value: $0 + TelemetryTiming.shared.telemetryTimeoutSeconds) },
      nextPoll: verified && pending == nil && waitingToSend == nil && reconnectDue == nil
        ? KotlinDouble(value: nextPoll) : nil,
      sampleDeadline: lastSample.map { KotlinDouble(value: $0 + TelemetryTiming.shared.telemetryTimeoutSeconds) })
    scheduledTick = current + delay
    if let scheduleTimer {
      scheduleTimer(delay) { [weak self] in self?.tick() }
    } else {
      timer?.schedule(deadline: .now() + delay, leeway: .milliseconds(2))
    }
  }

  private func paceNextPoll() {
    replyPoll?.cancel()
    replyPoll = nil
    guard shouldConnect, verified, pending == nil, waitingToSend == nil, reconnectDue == nil,
      !connectionAttempt.cancellationPending
    else { return }
    let delay = nextPoll - now
    if delay <= 0 {
      tick()
      return
    }
    let generation = connectionAttempt.generation
    let work = DispatchWorkItem { [weak self] in
      guard let self, self.connectionAttempt.generation == generation else { return }
      self.paceNextPoll()
    }
    replyPoll = work
    if let schedulePoll {
      schedulePoll(delay, work)
    } else {
      queue.asyncAfter(deadline: .now() + delay, execute: work)
    }
  }

  private func send(_ request: ControllerRequest) {
    guard shouldConnect, reconnectDue == nil, !connectionAttempt.cancellationPending,
      pending == nil, let selected, selected.state == .connected, let writer
    else { return }
    guard request == .identity ? connectionAttempt.stage == .identity : verified && connectionAttempt.stage == .ready
    else { return }
    guard selected.canSendWriteWithoutResponse else {
      waitingToSend = request
      if sendBlockedAt == nil { sendBlockedAt = now }
      return
    }
    let frame = session.frame(request)
    guard frame.count <= selected.maximumWriteValueLength(for: .withoutResponse) else {
      fail("UART write capacity is smaller than an allowlisted request.", reason: .writeCapacity)
      return
    }
    waitingToSend = nil
    sendBlockedAt = nil
    replyPoll?.cancel()
    replyPoll = nil
    let sent = now
    pending = (request, sent)
    nextPoll = sent + 1 / hz
    trace("write", "request=\(request.name); characteristic=\(writer.uuid.uuidString); withoutResponse", bytes: frame)
    selected.writeValue(frame, for: writer, type: .withoutResponse)
  }

  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    note(.bluetoothState, fields: ["manager": "central", "bluetoothState": bluetoothStateName(central.state)])
    if central.state == .poweredOn {
      scanIfReady()
      resumeIfPossible()
      if shouldConnect, reconnectDue == nil, connectionAttempt.stage == .idle { beginConnection() }
    } else if central.state != .unknown && central.state != .resetting {
      if scanRequested { stopScan() }
      try! session.radioUnavailable()
      if shouldConnect {
        fail(bluetoothError(central.state), reason: .bluetoothUnavailable, cancelConnection: false)
      } else {
        status = "error"
        errorMessage = bluetoothError(central.state)
        emitState()
      }
    }
  }

  private func bluetoothFailure(_ state: CBManagerState) -> WorkoutRideFailure {
    WorkoutRideFailure(state == .unauthorized ? "bluetoothDenied" : "bikeUnavailable")
  }

  private func bluetoothError(_ state: CBManagerState) -> String {
    switch state {
    case .unauthorized: return "Bluetooth permission is denied. Enable it in iOS Settings."
    case .unsupported: return "Bluetooth Low Energy is unsupported on this device."
    case .poweredOff: return "Bluetooth is switched off."
    default: return "Bluetooth is not ready."
    }
  }

  private func bluetoothStateName(_ state: CBManagerState) -> String {
    switch state {
    case .unknown: return "unknown"
    case .resetting: return "resetting"
    case .unsupported: return "unsupported"
    case .unauthorized: return "unauthorized"
    case .poweredOff: return "poweredOff"
    case .poweredOn: return "poweredOn"
    @unknown default: return "unknown"
    }
  }

  func centralManager(
    _ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
    advertisementData: [String: Any], rssi RSSI: NSNumber
  ) {
    guard scanRequested, discovery?.accepts(generation: discoveryGeneration, now: now) == true else { return }
    let name =
      (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? "Unnamed UART device"
    let services =
      (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? [])
      + (advertisementData[CBAdvertisementDataOverflowServiceUUIDsKey] as? [CBUUID] ?? [])
    guard
      services.contains(Self.uart) || name.uppercased().contains("CYC") || name.uppercased().contains("X6")
        || name.uppercased().contains("X12")
    else { return }
    devices[peripheral.identifier] = peripheral
    trace(
      "advertisement",
      "name=\(name); rssi=\(RSSI); services=\(services.map { $0.uuidString }.joined(separator: ","))",
      bytes: advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data)
    deviceRSSI[peripheral.identifier] = RSSI.intValue
    emitDevice(peripheral, name: name)
  }

  private func emitDevice(_ peripheral: CBPeripheral, name: String? = nil) {
    var value: [String: Any] = [
      "id": peripheral.identifier.uuidString,
      "name": name ?? peripheral.name ?? "CYC bike", "rssi": deviceRSSI[peripheral.identifier] ?? 0,
    ]
    if let identity = knownControllers[peripheral.identifier.uuidString] {
      value["controllerModel"] = identity.controllerModel
      value["firmwareLabel"] = identity.firmwareLabel
    }
    emit("onDevice", value)
  }

  func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    guard shouldConnect, peripheral === selected else {
      cancel(peripheral, reason: .unexpectedConnection)
      return
    }
    guard reconnectDue == nil, !connectionAttempt.cancellationPending else {
      cancel(peripheral, reason: .pendingReconnect)
      return
    }
    handleConnected(peripheral)
  }

  private func handleConnected(_ peripheral: CBPeripheral) {
    guard peripheral.state == .connected, advance(from: .connecting, to: .services) else { return }
    connectionEpoch = UUID().uuidString.lowercased()
    diagnosticMetrics.connected(at: now)
    note(.linkConnected)
    peripheral.delegate = self
    peripheral.discoverServices(bluetoothTrace.status(now: now).capturing ? nil : [Self.uart])
  }

  func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
    guard peripheral === selected else { return }
    completedDisconnection()
    let fields = diagnosticMetrics.disconnected(at: now, error: error, defaultReason: .connectionFailed)
    note(.connectionFailed, fields: fields)
    if shouldConnect, connectionAttempt.cancellationFailed, handle(.cancellationtimeout) { return }
    retry(WorkoutRideFailure("bikeUnavailable").message, reason: .connectionFailed, error: error)
  }

  func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
    handleDisconnected(peripheral, error: error, systemIsReconnecting: false)
  }

  func centralManager(
    _ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
    timestamp: CFAbsoluteTime, isReconnecting: Bool, error: Error?
  ) {
    handleDisconnected(peripheral, error: error, systemIsReconnecting: isReconnecting)
  }

  private func handleDisconnected(_ peripheral: CBPeripheral, error: Error?, systemIsReconnecting: Bool) {
    guard peripheral === selected else {
      if systemIsReconnecting { cancel(peripheral, reason: .unexpectedConnection) }
      return
    }
    completedDisconnection()
    let fields = diagnosticMetrics.disconnected(at: now, error: error)
    note(.disconnected, fields: fields.merging(["systemReconnect": systemIsReconnecting]) { _, value in value })
    if shouldConnect, connectionAttempt.cancellationFailed {
      if systemIsReconnecting { cancel(peripheral, reason: .pendingReconnect) }
      if handle(.cancellationtimeout) { return }
    }
    if shouldConnect {
      // A cancellation/backoff may already own recovery. Stop an unexpected system retry
      // instead of allowing it to escape that attempt's deadline or retry limit.
      if reconnectDue != nil, systemIsReconnecting {
        cancel(peripheral, reason: .pendingReconnect)
      } else {
        retry(
          "Bike disconnected. Reconnecting…", reason: .linkDisconnected, error: error,
          systemIsReconnecting: systemIsReconnecting, disconnectCallbackCompleted: true)
      }
    } else if systemIsReconnecting {
      cancel(peripheral, reason: .manualDisconnect)
    }
  }

  func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
    let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
    let saved = defaults.string(forKey: "PowerLog.selectedPeripheral")
    guard let device = restored.first(where: { $0.identifier.uuidString == saved }) else {
      for device in restored { cancel(device, reason: .restorationCleanup) }
      return
    }
    for other in restored where other.identifier != device.identifier { cancel(other, reason: .restorationCleanup) }
    selected = device
    selected?.delegate = self
    shouldConnect = true
    hz = workoutSampling.connectionRate(min(8, max(1, defaults.double(forKey: "PowerLog.pollHz"))))
    sessionStarted = now
    captureClock = TelemetryCaptureClock(origin: sessionStarted)
    latestSample = nil
    diagnosticMetrics.beginSession(at: now)
    MonitorDataStore.shared.beginLive(
      startedAt: WorkoutCoding.timestamp(captureClock.wallOrigin), monotonic: sessionStarted, id: captureClock.sessionID
    )
    note(.restored)
    status = "reconnecting"
    reconnectAttempts = 1
    ensureTimer()
    if central.state == .poweredOn { beginConnection() }
    emitState()
  }

  private func discoveryFailed(_ peripheral: CBPeripheral, reason: TelemetryDiagnosticReason, error: Error?) {
    if error != nil || workoutSampling.id != nil && knownControllers[peripheral.identifier.uuidString] != nil {
      retry("Could not read the bike's services. Reconnecting…", reason: reason, error: error)
    } else {
      fail("This bike does not provide the controller telemetry service.", reason: reason)
    }
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
    guard peripheral === selected, peripheral.state == .connected, shouldConnect, reconnectDue == nil,
      connectionAttempt.stage == .services
    else { return }
    for service in peripheral.services ?? [] {
      trace("service", service.uuid.uuidString)
      if bluetoothTrace.status(now: now).capturing && service.uuid != Self.uart {
        peripheral.discoverCharacteristics(nil, for: service)
      }
    }
    guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == Self.uart }) else {
      discoveryFailed(peripheral, reason: .serviceDiscovery, error: error)
      return
    }
    guard advance(from: .services, to: .characteristics) else { return }
    note(.transportStage)
    peripheral.discoverCharacteristics(
      bluetoothTrace.status(now: now).capturing ? nil : [Self.writeUUID, Self.notifyUUID], for: service)
  }

  func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
    guard peripheral === selected, peripheral.state == .connected, shouldConnect, reconnectDue == nil,
      invalidatedServices.contains(where: { $0.uuid == Self.uart })
    else { return }

    retry("CYC UART services were invalidated by Bluetooth.", reason: .servicesInvalidated)
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
    if peripheral === selected {
      for characteristic in service.characteristics ?? [] {
        trace(
          "characteristic",
          "service=\(service.uuid.uuidString); uuid=\(characteristic.uuid.uuidString); "
            + "properties=\(characteristic.properties.rawValue)")
      }
    }
    guard peripheral === selected, peripheral.state == .connected, shouldConnect, reconnectDue == nil,
      connectionAttempt.stage == .characteristics, service.uuid == Self.uart,
      peripheral.services?.contains(where: { $0 === service }) == true
    else { return }
    guard error == nil,
      let write = service.characteristics?.first(where: { $0.uuid == Self.writeUUID }),
      write.properties.contains(.writeWithoutResponse),
      let notify = service.characteristics?.first(where: { $0.uuid == Self.notifyUUID }),
      notify.properties.contains(.notify)
    else {
      discoveryFailed(peripheral, reason: .characteristicDiscovery, error: error)
      return
    }
    writer = write
    notifier = notify
    guard advance(from: .characteristics, to: .notifications) else { return }
    note(.transportStage)
    peripheral.setNotifyValue(true, for: notify)
  }

  func peripheral(
    _ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?
  ) {
    guard peripheral === selected, peripheral.state == .connected, characteristic === notifier,
      connectionAttempt.stage == .notifications, shouldConnect, reconnectDue == nil
    else { return }
    guard error == nil, characteristic.isNotifying else {
      retry("Could not subscribe to Controller telemetry.", reason: .notificationSetup, error: error)
      return
    }
    guard advance(from: .notifications, to: .identity) else { return }
    note(.transportStage)
    send(.identity)
    scheduleNextTick()
  }

  func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
    guard peripheral === selected, let request = waitingToSend else { return }
    send(request)
    scheduleNextTick()
  }

  func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
    defer { scheduleNextTick() }
    if peripheral === selected {
      trace(
        "notification",
        "characteristic=\(characteristic.uuid.uuidString); stage=\(connectionAttempt.stage.name); "
          + "request=\(pending?.request.name ?? "none"); error=\((error as NSError?)?.code ?? 0)",
        bytes: characteristic.value)
    }
    guard peripheral === selected, peripheral.state == .connected, characteristic === notifier,
      shouldConnect, reconnectDue == nil, !connectionAttempt.cancellationPending,
      connectionAttempt.stage == .identity || connectionAttempt.stage == .ready
    else { return }
    if let error {
      notificationErrors += 1
      note(.transportError, reason: .notificationError, error: error)
      return
    }
    guard let data = characteristic.value else { return }
    guard let outstanding = pending else { return }
    let current = now
    let decision = try! session.receive(
      data, generation: connectionAttempt.generation, request: outstanding.request, sentAt: outstanding.sent,
      now: current)
    trace(
      "decoder",
      "action=\(decision.action.name); discardedBytes=\(decision.discardedBytes); pendingBytes=\(decision.pendingBytes)"
    )
    if decision.action == .replyrejected, let rejection = decision.replies.first as? ControllerRejectedReply {
      note(.transportError, fields: ["rejection": rejection.reason])
      fail(
        ControllerErrorPresentation.message(rejection),
        reason: rejection.kind == .identity ? .identityRejected : .telemetryRejected)
      return
    }
    if handle(decision.action) { return }
    guard decision.action == .replyaccepted, let reply = decision.replies.first else { return }
    if let identityReply = reply as? ControllerIdentityReply {
      guard advance(from: .identity, to: .ready) else { return }
      knownControllers[peripheral.identifier.uuidString] = identityReply.identity
      if let data = try? JSONEncoder().encode(knownControllers.mapValues { ControllerProfileRecord($0) }) {
        defaults.set(data, forKey: "PowerLog.knownControllers")
      }
      emitDevice(peripheral)
      pending = nil
      nextPoll = current
      note(.identityVerified)
      emitState()
    } else if let readingReply = reply as? ControllerReadingReply {
      pending = nil
      receive(readingReply.reading, at: current, responseSeconds: current - outstanding.sent)
    }
    paceNextPoll()
  }

  private func advance(from expected: TelemetryStage, to next: TelemetryStage) -> Bool {
    let action = try! session.advance(
      generation: connectionAttempt.generation, expected: expected, next: next, now: now)
    _ = handle(action)
    return action == .advanced
  }

  private func completedDisconnection() {
    try! session.disconnected(
      generation: connectionAttempt.cancellationGeneration?.int64Value ?? connectionAttempt.generation, now: now)
  }

  @discardableResult
  private func handle(_ action: TelemetryTransportAction) -> Bool {
    switch action {
    case .beginretry:
      beginConnection(forceConnect: true)
    case .attempttimeout:
      retry("Controller connection or identity handshake timed out.", reason: .connectionDeadline)
    case .responsetimeout:
      retry("Controller response timed out; a telemetry gap was recorded.", reason: .responseTimeout)
    case .writetimeout:
      retry("The bike did not accept a request. Reconnecting…", reason: .writeReadyTimeout)
    case .replyexpired:
      retry("The bike response arrived too late. Reconnecting…", reason: .expiredReply)
    case .cancellationtimeout:
      fail("Bluetooth cancellation did not finish before reconnect deadline.", reason: .cancellationDeadline)
    default:
      return false
    }
    return true
  }

  private func receive(
    _ reading: TelemetryReading, at current: Double, responseSeconds: Double
  ) {
    status = "connected"
    recoveryErrorMessage = nil
    if let gap = diagnosticMetrics.receivedSample(at: current, responseSeconds: responseSeconds) {
      note(.telemetryGap, fields: ["gapSeconds": gap])
    }
    sessionSamples += 1
    var sample: [String: Any]
    do {
      sample = try captureClock.observation(reading, connectionEpoch: connectionEpoch, monotonic: current)
    } catch {
      fail(WorkoutRideFailure("captureRejected").message, reason: .telemetryRejected, error: error)
      return
    }
    sample["elapsedSeconds"] = current - sessionStarted
    sample["sequence"] = sessionSamples
    if stableSince == nil { stableSince = current }
    if let stableSince, current - stableSince >= TelemetryTiming.shared.stableTelemetrySeconds { reconnectAttempts = 0 }
    lastSample = current
    let storageStarted = now
    do {
      #if os(iOS)
        try WorkoutEngine.shared.admitController(
          PowerLogCaptureFrame(
            sample: sample, liveID: captureClock.sessionID,
            liveStartedAt: WorkoutCoding.timestamp(captureClock.wallOrigin), liveOrigin: sessionStarted,
            liveElapsed: current - sessionStarted))
      #else
        try MonitorDataStore.shared.appendLive(sample, elapsedSeconds: current - sessionStarted)
      #endif
    } catch {
      captureResult(error)
    }
    maxStorageWriteMs = max(maxStorageWriteMs, (now - storageStarted) * 1000)
    latestSample = sample
    if !background, current - lastPresentation >= 0.25 {
      lastPresentation = current
      emitSample(sample)
      emitState()
    }
    updateBackgroundHold()
  }
}

#if POWER_LOG_LAB
  extension ControllerEngine {
    func labBeginCapture() {
      status = "connected"
      sessionStarted = now
      captureClock = TelemetryCaptureClock(origin: sessionStarted)
      sessionSamples = 0
      MonitorDataStore.shared.beginLive(
        startedAt: WorkoutCoding.timestamp(captureClock.wallOrigin), monotonic: sessionStarted,
        id: captureClock.sessionID)
    }
    func labReceive(_ reading: TelemetryReading) {
      receive(reading, at: now, responseSeconds: 0)
    }
  }
#endif

extension ControllerEngine {
  static func processCPUSeconds() -> Double {
    var time = timespec()
    clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &time)
    return Double(time.tv_sec) + Double(time.tv_nsec) / 1_000_000_000
  }
}

#if canImport(UIKit)
  import UIKit

  final class ControllerBackgroundHold {
    static let shared = ControllerBackgroundHold()
    private var task: UIBackgroundTaskIdentifier = .invalid
    func set(active: Bool) {
      if active, task == .invalid {
        task = UIApplication.shared.beginBackgroundTask(withName: "Power Log bike reconnect") { [weak self] in
          self?.set(active: false)
        }
      } else if !active, task != .invalid {
        UIApplication.shared.endBackgroundTask(task)
        task = .invalid
      }
    }
  }
#endif
