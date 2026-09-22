import Foundation
import CoreBluetooth

/// One native singleton, independent of the React tree and module listener lifecycle.
final class CycEngine: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
  static let shared = CycEngine()
  let queue = DispatchQueue(label: "app.powerlog.cyc", qos: .utility)
  private let exportQueue = DispatchQueue(label: "app.powerlog.cyc.export", qos: .utility)
  private static let uart = CBUUID(string: "6e400001-b5a3-f393-e0a9-e50e24dcca9e")
  private static let writeUUID = CBUUID(string: "6e400002-b5a3-f393-e0a9-e50e24dcca9e")
  private static let notifyUUID = CBUUID(string: "6e400003-b5a3-f393-e0a9-e50e24dcca9e")
  private var central: CBCentralManager?
  private var selected: CBPeripheral?
  private var devices: [UUID: CBPeripheral] = [:]
  private var writer: CBCharacteristic?
  private var notifier: CBCharacteristic?
  private var timer: DispatchSourceTimer?
  private var decoder = CycFrameDecoder()
  private var pending: (request: CycRequest, sent: Double)?
  private var waitingToSend: CycRequest?
  private var sendBlockedAt: Double?
  private var lastNotification: Double?
  private var lastSample: Double?
  private var shouldConnect = false
  private var verified = false
  private var controllerAdapter: CycControllerAdapter?
  // Only sanitized model/version metadata is cached, never the opaque identity tail.
  private var knownControllers: [String: CycControllerIdentity] = [:]
  private var deviceRSSI: [UUID: Int] = [:]
  private var scanRequested = false
  private var scanDeadline: Double?
  private var background = true
  private var lastPresentation: Double = 0
  private var latestSample: [String: Any]?
  private var resumeID: UUID?
  private var connectionDeadline: Double?
  private var reconnectDue: Double?
  private var reconnectAttempts = 0
  private var connectionAttempt = CycConnectionAttempt()
  private var stableSince: Double?
  private var hz: Double = 2
  private var workoutSampling = WorkoutSamplingOwner()
  var currentSampleRate: Double { hz }
  private var nextPoll: Double = 0
  private var sessionStarted: Double = 0
  private var captureClock = CycCaptureClock(origin: ProcessInfo.processInfo.systemUptime)
  private var connectionEpoch = UUID().uuidString.lowercased()
  private var sessionSamples = 0
  private var status = "idle"
  private var errorMessage: String?
  private var recoveryErrorMessage: String?
  private var storeError: String?
  private var diagnosticLog: CycDiagnosticLog?
  private var diagnosticMetrics = CycDiagnosticMetrics()
  private let diagnosticStarted = ProcessInfo.processInfo.systemUptime
  private var notificationErrors = 0
  private var unexpectedReplies = 0
  private var maxStorageWriteMs = 0.0
  private var scheduledTick: Double?
  private var maxQueueDelaySeconds = 0.0
  private var sinks: [UUID: (String, [String: Any]) -> Void] = [:]
  private let defaults = UserDefaults.standard

  private override init() {
    super.init()
    queue.async {
      if let data = self.defaults.data(forKey: "PowerLog.knownControllers"),
        let known = try? JSONDecoder().decode([String: CycControllerIdentity].self, from: data) {
        self.knownControllers = known
      }
      do { self.diagnosticLog = try CycDiagnosticLog() } catch { /* Logging must not disable ride capture. */ }
      self.note(.engineStarted)
    }
  }

  private var now: Double { ProcessInfo.processInfo.systemUptime }

  func addSink(id: UUID, sink: @escaping (String, [String: Any]) -> Void) { sinks[id] = sink }
  func removeSink(id: UUID) { sinks.removeValue(forKey: id) }
  func captureResult(_ failure: Error?) {
    let next = failure.map { "Recording storage failed: \($0.localizedDescription)" }
    guard storeError != next else { return }
    storeError = next; emitState()
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
    let displayError = CycReconnectPolicy.displayError(storage: storeError, other: errorMessage,
      recovery: recoveryErrorMessage, reconnecting: status == "reconnecting")
    if let error = displayError.message { value["error"] = error }
    value["recoverableConnectionError"] = displayError.recoverable
    return value
  }

  func diagnostics() -> [String: Any] {
    let current = now
    return ["schemaVersion": 1, "timestamp": CycProtocol.timestamp(), "status": status, "requestedHz": hz,
      "sampleCount": diagnosticMetrics.sampleCount, "connectionAttempts": diagnosticMetrics.connectionAttempts,
      "reconnects": diagnosticMetrics.reconnects, "requestTimeouts": diagnosticMetrics.requestTimeouts,
      "decoderDiscardedBytes": decoder.discardedBytes,
      "lastSampleAgeSeconds": diagnosticMetrics.sampleAge(at: current) as Any? ?? NSNull(),
      "lastGapSeconds": diagnosticMetrics.lastGapSeconds as Any? ?? NSNull(),
      "recentSampleHz": diagnosticMetrics.recentSampleHz(at: current) as Any? ?? NSNull(),
      "responseLatencyMs": diagnosticMetrics.responseLatencyMs as Any? ?? NSNull(),
      "lastDisconnect": diagnosticMetrics.lastDisconnect?.dictionary as Any? ?? NSNull(), "background": background]
  }

  func readDiagnostics(completion: @escaping (Result<String, Error>) -> Void) {
    guard let diagnosticLog else { completion(.failure(CycError.invalid("Private diagnostic storage is unavailable."))); return }
    exportQueue.async { completion(Result { try diagnosticLog.read() }) }
  }

  private func note(_ event: CycDiagnosticEvent, reason: CycDiagnosticReason? = nil, error: Error? = nil, fields: [String: Any] = [:]) {
    let current = now
    var data: [String: Any] = ["status": status, "requestedHz": hz, "sampleCount": diagnosticMetrics.sampleCount,
      "connectionAttempts": diagnosticMetrics.connectionAttempts, "reconnects": diagnosticMetrics.reconnects,
      "requestTimeouts": diagnosticMetrics.requestTimeouts, "decoderDiscardedBytes": decoder.discardedBytes,
      "linkSamples": diagnosticMetrics.linkSamples, "background": background,
      "connectionGeneration": connectionAttempt.generation, "transportStage": connectionAttempt.stage.rawValue]
    if let duration = diagnosticMetrics.connectionSeconds(at: current) { data["connectionSeconds"] = duration }
    if let age = diagnosticMetrics.sampleAge(at: current) { data["lastSampleAgeSeconds"] = age }
    if let pending { data["request"] = pending.request == .identity ? "identity" : "selective"; data["requestAgeSeconds"] = max(0, current - pending.sent) }
    if let sendBlockedAt { data["writeBlockedSeconds"] = max(0, current - sendBlockedAt) }
    if let reason { data["reason"] = reason.rawValue }
    data.merge(CycDiagnosticLog.errorFields(error)) { _, value in value }
    data.merge(fields) { _, value in value }
    diagnosticLog?.append(event, elapsedSeconds: max(0, current - diagnosticStarted), fields: data)
  }

  private func summarizeIfDue() {
    let current = now
    guard shouldConnect, current >= diagnosticMetrics.nextSummary else { return }
    var fields: [String: Any] = ["notificationErrors": notificationErrors, "unexpectedReplies": unexpectedReplies,
      "pendingBytes": decoder.buffer.count, "queueDelaySeconds": maxQueueDelaySeconds, "storageWriteMs": maxStorageWriteMs]
    if let rate = diagnosticMetrics.recentSampleHz(at: current) { fields["recentSampleHz"] = rate }
    if let latency = diagnosticMetrics.responseLatencyMs { fields["latencyMeanMs"] = latency["mean"]; fields["latencyMaxMs"] = latency["max"] }
    fields["cpuSeconds"] = Self.processCPUSeconds()
    note(.telemetrySummary, fields: fields)
    diagnosticMetrics.resetWindow(at: current)
    maxQueueDelaySeconds = 0
    maxStorageWriteMs = 0
  }

  private func cancel(_ peripheral: CBPeripheral, reason: CycDiagnosticReason) {
    if peripheral.identifier == selected?.identifier {
      connectionAttempt.requestedCancellation()
      diagnosticMetrics.requestedCancellation(reason: reason, at: now)
      note(.disconnectRequested, reason: diagnosticMetrics.cancellationReason ?? reason, fields: ["initiator": "app"])
    } else { note(.disconnectRequested, reason: reason, fields: ["initiator": "app"]) }
    central?.cancelPeripheralConnection(peripheral)
  }

  private func ensureCentral() {
    guard central == nil else { return }
    central = CBCentralManager(delegate: self, queue: queue,
      options: [CBCentralManagerOptionRestoreIdentifierKey: "app.powerlog.cyc.central",
        CBCentralManagerOptionShowPowerAlertKey: true])
  }

  private func ensureTimer() {
    guard timer == nil else { return }
    let timer = DispatchSource.makeTimerSource(queue: queue)
    timer.schedule(deadline: .now())
    timer.setEventHandler { [weak self] in self?.tick() }
    self.timer = timer
    timer.resume()
  }

  func startScan() throws {
    guard !shouldConnect else { throw CycError.invalid("Disconnect before scanning for another controller.") }
    ensureCentral()
    if let central, central.state != .unknown && central.state != .resetting && central.state != .poweredOn {
      throw CycError.invalid(bluetoothError(central.state))
    }
    scanRequested = true
    scanDeadline = now + 20
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
    scanDeadline = nil
    central?.stopScan()
    if status == "scanning" { status = "idle" }
    stopTimerIfIdle()
    emitState()
  }

  private func scanIfReady() {
    guard scanRequested, let central, central.state == .poweredOn else { return }
    // Both verified families advertise UART. Always provide its UUID so discovery also
    // works when iOS treats a locked/mirrored app as background for Bluetooth scanning.
    central.scanForPeripherals(withServices: [Self.uart],
      options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
  }

  func connect(deviceID: String, rate: Double) throws {
    guard rate.isFinite, (1...8).contains(rate) else { throw CycError.invalid("Polling rate must be between 1 and 8 Hz.") }
    guard let id = UUID(uuidString: deviceID) else { throw CycError.invalid("Invalid local Bluetooth device identifier.") }
    guard !shouldConnect else { throw CycError.invalid("Disconnect the current controller before connecting again.") }
    guard !connectionAttempt.cancellationPending else { throw CycError.invalid("Bluetooth is still finishing the previous disconnection. Try again shortly.") }
    ensureCentral()
    guard let central, central.state == .poweredOn else { throw CycError.invalid("Bluetooth must be powered on; scan first to request permission.") }
    guard let device = devices[id] ?? central.retrievePeripherals(withIdentifiers: [id]).first else {
      throw CycError.invalid("Device is unknown to iOS. Scan and select it again.")
    }
    stopScan()
    hz = workoutSampling.connectionRate(rate)
    selected = device
    shouldConnect = true
    reconnectAttempts = 0
    sessionStarted = now
    sessionSamples = 0
    captureClock = CycCaptureClock(origin: sessionStarted)
    MonitorDataStore.shared.beginLive(startedAt: WorkoutCoding.timestamp(captureClock.wallOrigin), monotonic: sessionStarted, id: captureClock.sessionID)
    diagnosticMetrics.beginSession(at: now)
    decoder = CycFrameDecoder()
    notificationErrors = 0
    unexpectedReplies = 0
    note(.connectRequested)
    defaults.set(id.uuidString, forKey: "PowerLog.selectedPeripheral")
    defaults.set(hz, forKey: "PowerLog.pollHz")
    errorMessage = nil
    recoveryErrorMessage = nil
    ensureTimer()
    beginConnection()
  }

  /// Called on the native capture queue at a new phone-initiated ride boundary.
  /// Existing UART requests and the BLE connection retain their ownership.
  func setSampleRate(_ rate: Double) throws {
    hz = try WorkoutRecordingPolicy.sampleHz(rate)
    nextPoll = max(now, (pending?.sent ?? lastSample ?? now) + 1 / rate)
    defaults.set(rate, forKey: "PowerLog.pollHz")
    scheduleNextTick(); emitState()
  }

  func setWorkoutSamplingOwner(_ id: String?, rate: Double?) {
    let previous = workoutSampling.id
    let persisted = defaults.double(forKey: "PowerLog.pollHz")
    let restored = persisted.isFinite && (1...8).contains(persisted) ? persisted : hz
    workoutSampling.update(id: id, rate: rate ?? restored)
    if previous != nil, id == nil, status == "reconnecting" { try? disconnect() }
    if let admitted = workoutSampling.rate, hz != admitted {
      hz = admitted; nextPoll = max(now, (pending?.sent ?? lastSample ?? now) + 1 / hz)
      defaults.set(hz, forKey: "PowerLog.pollHz"); scheduleNextTick()
    }
  }

  func disconnect() throws {
    if let selected, selected.state != .disconnected || connectionAttempt.systemInitiated { cancel(selected, reason: .manualDisconnect) }
    shouldConnect = false
    scanRequested = false
    scanDeadline = nil
    reconnectDue = nil
    connectionDeadline = nil
    central?.stopScan()
    defaults.removeObject(forKey: "PowerLog.selectedPeripheral")
    clearTransport()
    status = "idle"
    errorMessage = nil
    recoveryErrorMessage = nil
    note(.sessionStopped, reason: .manualDisconnect)
    stopTimerIfIdle(); emitState()
  }

  // Called from the AppDelegate subscriber without depending on a JS runtime.
  func restoreOnLaunch(central: Bool) {
    guard central else { return }
    note(.restored); ensureCentral(); ensureTimer()
  }

  /// A relaunch that CoreBluetooth did not initiate still reconnects the remembered controller.
  func resumeRememberedConnection() {
    guard !shouldConnect, resumeID == nil, let saved = defaults.string(forKey: "PowerLog.selectedPeripheral"),
      let id = UUID(uuidString: saved) else { return }
    resumeID = id
    ensureCentral(); ensureTimer()
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
    captureClock = CycCaptureClock(origin: sessionStarted)
    diagnosticMetrics.beginSession(at: now)
    MonitorDataStore.shared.beginLive(startedAt: WorkoutCoding.timestamp(captureClock.wallOrigin), monotonic: sessionStarted, id: captureClock.sessionID)
    decoder = CycFrameDecoder()
    notificationErrors = 0
    unexpectedReplies = 0
    note(.resumed)
    status = "reconnecting"
    beginConnection()
    emitState()
  }

  func setBackground(_ background: Bool) {
    guard self.background != background else { return }
    self.background = background
    note(.backgroundChanged)
    if scanRequested { central?.stopScan(); scanIfReady() }
    tick() // Immediately re-evaluate stale state after a suspension gap.
    if !background {
      if let latestSample { emit("onSample", latestSample) }
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
    DispatchQueue.main.async { CycBackgroundHold.shared.set(active: wanted) }
  }
  #else
  private func updateBackgroundHold() {}
  #endif

  func willTerminate() {
    note(.sessionStopped, reason: .appTerminating)
  }

  private func stopTimerIfIdle() {
    if !shouldConnect && !scanRequested { timer?.cancel(); timer = nil }
  }

  private func beginConnection(systemInitiated: Bool = false, forceConnect: Bool = false) {
    guard shouldConnect, let central, central.state == .poweredOn, let selected,
      !connectionAttempt.cancellationPending, connectionAttempt.stage == .idle else { return }
    clearTransport()
    guard connectionAttempt.begin(systemInitiated: systemInitiated) else { return }
    diagnosticMetrics.beginAttempt(at: now)
    selected.delegate = self
    status = reconnectAttempts == 0 ? "connecting" : "reconnecting"
    connectionDeadline = now + 20
    reconnectDue = nil
    note(.connectionAttempt, fields: ["systemReconnect": systemInitiated])
    if selected.state == .connected, !forceConnect { handleConnected(selected) }
    else if !systemInitiated {
      // iOS 17/macOS 14 can start reconnecting before delivering the disconnect callback.
      // That attempt is adopted below or explicitly cancelled; never run two retry owners.
      // https://developer.apple.com/documentation/corebluetooth/cbconnectperipheraloptionenableautoreconnect
      if #available(iOS 17.0, macOS 14.0, *) {
        central.connect(selected, options: [CBConnectPeripheralOptionEnableAutoReconnect: true])
      } else { central.connect(selected, options: nil) }
    }
    emitState()
    scheduleNextTick()
  }

  private func clearTransport() {
    connectionAttempt.invalidate()
    verified = false
    controllerAdapter = nil
    writer = nil
    notifier = nil
    pending = nil
    waitingToSend = nil
    sendBlockedAt = nil
    lastSample = nil
    lastNotification = nil
    stableSince = nil
    decoder.reset()
  }

  private func retry(_ message: String, reason: CycDiagnosticReason, error: Error? = nil,
    systemIsReconnecting: Bool = false, disconnectCallbackCompleted: Bool = false) {
    guard shouldConnect, reconnectDue == nil else { return }
    let stableTelemetrySeconds = verified ? stableSince.map { now - $0 } : nil
    let confirmedPeerDisconnect = reason == .linkDisconnected && CycReconnectPolicy.confirmedPeerDisconnect(
      callbackCompleted: disconnectCallbackCompleted, initiator: diagnosticMetrics.lastDisconnect?.initiator)
    let nextAttempt = reconnectAttempts + 1
    let adoptSystemReconnect = systemIsReconnecting && CycReconnectPolicy.adoptSystemReconnect(attempt: nextAttempt,
      confirmedPeerDisconnect: confirmedPeerDisconnect, stableTelemetrySeconds: stableTelemetrySeconds)
    if reason == .responseTimeout || reason == .expiredReply { diagnosticMetrics.timedOut() }
    note(.transportError, reason: reason, error: error)
    if let selected, CycReconnectPolicy.mustCancelBeforeRetry(callbackCompleted: disconnectCallbackCompleted,
      systemIsReconnecting: systemIsReconnecting, systemAttemptPending: connectionAttempt.systemInitiated,
      peripheralIsDisconnected: selected.state == .disconnected, adoptingSystemReconnect: adoptSystemReconnect) {
      cancel(selected, reason: reason)
    }
    clearTransport()
    connectionDeadline = nil
    recoveryErrorMessage = message
    reconnectAttempts = nextAttempt
    guard CycReconnectPolicy.mayRetry(attempt: reconnectAttempts, activeRide: workoutSampling.id != nil) else {
      fail("Reconnect limit reached. \(message)", reason: .retryLimit,
        cancelConnection: !disconnectCallbackCompleted || systemIsReconnecting)
      return
    }
    let retryDelay = CycReconnectPolicy.recoveryDelay(attempt: reconnectAttempts, activeRide: workoutSampling.id != nil,
      confirmedPeerDisconnect: confirmedPeerDisconnect, stableTelemetrySeconds: stableTelemetrySeconds)
    reconnectAttempts = min(reconnectAttempts, 6)
    reconnectDue = now + retryDelay
    connectionDeadline = reconnectDue! + 5
    status = "reconnecting"
    diagnosticMetrics.scheduledReconnect()
    note(.reconnectScheduled, reason: reason, fields: ["retryAttempt": reconnectAttempts, "retryDelaySeconds": retryDelay,
      "systemReconnect": adoptSystemReconnect])
    // The disconnect callback has completed teardown; a stable link needs no extra app delay.
    // Failed/short attempts still back off, and app cancellations still wait for their callback.
    if retryDelay == 0 { beginConnection(systemInitiated: adoptSystemReconnect, forceConnect: !adoptSystemReconnect) }
    else { emitState(); scheduleNextTick() }
    updateBackgroundHold()
  }

  private func fail(_ message: String, reason: CycDiagnosticReason, error: Error? = nil, cancelConnection: Bool = true) {
    defer { updateBackgroundHold() }
    note(.transportError, reason: reason, error: error)
    if cancelConnection, let selected, selected.state != .disconnected || connectionAttempt.systemInitiated { cancel(selected, reason: reason) }
    if workoutSampling.id != nil, [.bluetoothUnavailable, .cancellationDeadline, .retryLimit].contains(reason) {
      clearTransport()
      recoveryErrorMessage = message; errorMessage = nil; status = "reconnecting"
      reconnectDue = central?.state == .poweredOn ? now + 30 : nil
      connectionDeadline = reconnectDue.map { $0 + 5 }
      ensureTimer(); emitState(); return
    }
    shouldConnect = false
    reconnectDue = nil
    connectionDeadline = nil
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
    if let due = reconnectDue {
      if current >= due {
        guard central?.state == .poweredOn else { fail("Bluetooth is unavailable during reconnect.", reason: .bluetoothUnavailable); return }

        if connectionAttempt.cancellationPending || selected?.state == .disconnecting {
          if let deadline = connectionDeadline, current >= deadline { fail("Bluetooth cancellation did not finish before reconnect deadline.", reason: .cancellationDeadline) }
          return
        }
        // This path follows confirmed teardown/cancellation, not restoration of a live link.
        beginConnection(forceConnect: true)
      }
      return
    }
    if let deadline = connectionDeadline, current >= deadline { retry("CYC connection or identity handshake timed out.", reason: .connectionDeadline); return }
    if let pending, current - pending.sent >= CycProtocol.maximumGap {
      // Reconnect, instead of reissuing an ambiguous same-command request on the old link.
      retry("Controller response timed out; a telemetry gap was recorded.", reason: .responseTimeout)
      return
    }
    if let request = waitingToSend {
      if let sendBlockedAt, current - sendBlockedAt >= CycProtocol.maximumGap { retry("UART write queue remained blocked.", reason: .writeReadyTimeout); return }
      send(request)
      return
    }
    if verified, pending == nil, current >= nextPoll { send(.selective) }
  }

  private func scheduleNextTick() {
    guard let timer else { return }
    let current = now
    let delay = CycPollingSchedule.delay(now: current, poweredOn: central?.state == .poweredOn,
      verified: verified, scanning: scanRequested, scanDeadline: scanDeadline, reconnectDue: reconnectDue,
      connectionDeadline: connectionDeadline, cancellationPending: connectionAttempt.cancellationPending || selected?.state == .disconnecting,
      responseDeadline: pending.map { $0.sent + CycProtocol.maximumGap },
      writeDeadline: sendBlockedAt.map { $0 + CycProtocol.maximumGap },
      nextPoll: verified && pending == nil && waitingToSend == nil && reconnectDue == nil ? nextPoll : nil,
      sampleDeadline: lastSample.map { $0 + CycProtocol.maximumGap })
    scheduledTick = current + delay
    timer.schedule(deadline: .now() + delay, leeway: .milliseconds(2))
  }

  private func send(_ request: CycRequest) {
    guard shouldConnect, reconnectDue == nil, !connectionAttempt.cancellationPending,
      pending == nil, let selected, selected.state == .connected, let writer else { return }
    guard request == .identity ? connectionAttempt.stage == .identity : verified && connectionAttempt.stage == .ready else { return }
    guard selected.canSendWriteWithoutResponse else {
      waitingToSend = request
      if sendBlockedAt == nil { sendBlockedAt = now }
      return
    }
    let frame = request.frame
    guard frame.count <= selected.maximumWriteValueLength(for: .withoutResponse) else {
      fail("UART write capacity is smaller than an allowlisted request.", reason: .writeCapacity); return
    }
    waitingToSend = nil
    sendBlockedAt = nil
    pending = (request, now)
    nextPoll = now + 1 / hz
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
      if shouldConnect { fail(bluetoothError(central.state), reason: .bluetoothUnavailable) }
      else { status = "error"; errorMessage = bluetoothError(central.state); emitState() }
      // Below poweredOn the manager has already torn down its links; a cancellation callback
      // is no longer a prerequisite for a later, newly selected connection.
      connectionAttempt.disconnected()
    }
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

  func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
    advertisementData: [String: Any], rssi RSSI: NSNumber) {
    guard scanRequested else { return }
    let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? "Unnamed UART device"
    let services = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []) +
      (advertisementData[CBAdvertisementDataOverflowServiceUUIDsKey] as? [CBUUID] ?? [])
    guard services.contains(Self.uart) || name.uppercased().contains("CYC") || name.uppercased().contains("X6") || name.uppercased().contains("X12") else { return }
    devices[peripheral.identifier] = peripheral
    deviceRSSI[peripheral.identifier] = RSSI.intValue
    emitDevice(peripheral, name: name)
  }

  private func emitDevice(_ peripheral: CBPeripheral, name: String? = nil) {
    var value: [String: Any] = ["id": peripheral.identifier.uuidString,
      "name": name ?? peripheral.name ?? "CYC bike", "rssi": deviceRSSI[peripheral.identifier] ?? 0]
    if let identity = knownControllers[peripheral.identifier.uuidString] {
      value["controllerModel"] = identity.controllerModel
      value["firmwareLabel"] = identity.firmwareLabel
    }
    emit("onDevice", value)
  }

  func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    guard shouldConnect, peripheral === selected else { cancel(peripheral, reason: .unexpectedConnection); return }
    guard reconnectDue == nil, !connectionAttempt.cancellationPending else { cancel(peripheral, reason: .pendingReconnect); return }
    handleConnected(peripheral)
  }

  private func handleConnected(_ peripheral: CBPeripheral) {
    guard peripheral.state == .connected, connectionAttempt.advance(from: .connecting, to: .services) else { return }
    connectionEpoch = UUID().uuidString.lowercased()
    diagnosticMetrics.connected(at: now)
    note(.linkConnected)
    peripheral.delegate = self
    peripheral.discoverServices([Self.uart])
  }

  func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
    guard peripheral === selected else { return }
    connectionAttempt.disconnected()
    let fields = diagnosticMetrics.disconnected(at: now, error: error, defaultReason: .connectionFailed)
    note(.connectionFailed, fields: fields)
    retry("Could not connect to CYC controller: \(error?.localizedDescription ?? "connection failed").", reason: .connectionFailed, error: error)
  }

  func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
    handleDisconnected(peripheral, error: error, systemIsReconnecting: false)
  }

  func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
    timestamp: CFAbsoluteTime, isReconnecting: Bool, error: Error?) {
    handleDisconnected(peripheral, error: error, systemIsReconnecting: isReconnecting)
  }

  private func handleDisconnected(_ peripheral: CBPeripheral, error: Error?, systemIsReconnecting: Bool) {
    guard peripheral === selected else {
      if systemIsReconnecting { cancel(peripheral, reason: .unexpectedConnection) }
      return
    }
    connectionAttempt.disconnected()
    let fields = diagnosticMetrics.disconnected(at: now, error: error)
    note(.disconnected, fields: fields.merging(["systemReconnect": systemIsReconnecting]) { _, value in value })
    if shouldConnect {
      // A cancellation/backoff may already own recovery. Stop an unexpected system retry
      // instead of allowing it to escape that attempt's deadline or retry limit.
      if reconnectDue != nil, systemIsReconnecting { cancel(peripheral, reason: .pendingReconnect) }
      else { retry("Bike disconnected. Reconnecting…", reason: .linkDisconnected, error: error,
        systemIsReconnecting: systemIsReconnecting, disconnectCallbackCompleted: true) }
    } else if systemIsReconnecting { cancel(peripheral, reason: .manualDisconnect) }
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
    captureClock = CycCaptureClock(origin: sessionStarted)
    diagnosticMetrics.beginSession(at: now)
    MonitorDataStore.shared.beginLive(startedAt: WorkoutCoding.timestamp(captureClock.wallOrigin), monotonic: sessionStarted, id: captureClock.sessionID)
    note(.restored)
    status = "reconnecting"
    reconnectAttempts = 1
    ensureTimer()
    // Re-discover and verify identity. Old notifications never bypass the handshake.
    if central.state == .poweredOn { beginConnection() }
    emitState()
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
    guard peripheral === selected, peripheral.state == .connected, shouldConnect, reconnectDue == nil,
      connectionAttempt.stage == .services else { return }
    guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == Self.uart }) else {
      fail("Expected CYC UART service was not discovered.", reason: .serviceDiscovery, error: error); return
    }
    guard connectionAttempt.advance(from: .services, to: .characteristics) else { return }
    note(.transportStage)
    peripheral.discoverCharacteristics([Self.writeUUID, Self.notifyUUID], for: service)
  }

  func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
    guard peripheral === selected, peripheral.state == .connected, shouldConnect, reconnectDue == nil,
      invalidatedServices.contains(where: { $0.uuid == Self.uart }) else { return }

    retry("CYC UART services were invalidated by Bluetooth.", reason: .servicesInvalidated)
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
    guard peripheral === selected, peripheral.state == .connected, shouldConnect, reconnectDue == nil,
      connectionAttempt.stage == .characteristics, service.uuid == Self.uart,
      peripheral.services?.contains(where: { $0 === service }) == true else { return }
    guard error == nil,
      let write = service.characteristics?.first(where: { $0.uuid == Self.writeUUID }), write.properties.contains(.writeWithoutResponse),
      let notify = service.characteristics?.first(where: { $0.uuid == Self.notifyUUID }), notify.properties.contains(.notify) else {
      fail("CYC UART properties do not match the verified transport.", reason: .characteristicDiscovery, error: error); return
    }
    writer = write
    notifier = notify
    guard connectionAttempt.advance(from: .characteristics, to: .notifications) else { return }
    note(.transportStage)
    peripheral.setNotifyValue(true, for: notify)
  }

  func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
    guard peripheral === selected, peripheral.state == .connected, characteristic === notifier,
      connectionAttempt.stage == .notifications, shouldConnect, reconnectDue == nil else { return }
    guard error == nil, characteristic.isNotifying else { retry("Could not subscribe to CYC telemetry.", reason: .notificationSetup, error: error); return }
    guard connectionAttempt.advance(from: .notifications, to: .identity) else { return }
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
    guard peripheral === selected, peripheral.state == .connected, characteristic === notifier,
      shouldConnect, reconnectDue == nil, !connectionAttempt.cancellationPending,
      connectionAttempt.stage == .identity || connectionAttempt.stage == .ready else { return }
    if let error {
      notificationErrors += 1
      // Report the actual callback error before the normal outstanding-request deadline decides recovery.
      note(.transportError, reason: .notificationError, error: error)
      return
    }
    guard let data = characteristic.value else { return }
    let current = now
    if let lastNotification, current - lastNotification > CycProtocol.maximumGap { decoder.reset() }
    lastNotification = current
    var identityVerified = false
    for payload in decoder.feed(data) {
      guard let outstanding = pending,
        payload.first == outstanding.request.rawValue || (outstanding.request == .identity && payload.first == 0) else { unexpectedReplies += 1; continue }
      // The queue may resume after iOS suspension; do not accept an expired reply then.
      guard current - outstanding.sent <= CycProtocol.maximumGap else { retry("Telemetry reply arrived after its freshness deadline.", reason: .expiredReply); return }
      do {
        if outstanding.request == .identity {
          let identity = try CycProtocol.validateIdentity(payload)
          guard connectionAttempt.advance(from: .identity, to: .ready) else { return }
          controllerAdapter = identity.adapter
          knownControllers[peripheral.identifier.uuidString] = identity
          if let data = try? JSONEncoder().encode(knownControllers) {
            defaults.set(data, forKey: "PowerLog.knownControllers")
          }
          emitDevice(peripheral)
          pending = nil
          verified = true
          connectionDeadline = nil
          nextPoll = current
          identityVerified = true
          note(.identityVerified)
          emitState()
        } else {
          guard let controllerAdapter, let identity = knownControllers[peripheral.identifier.uuidString] else { throw CycError.invalid("Controller identity has not been verified.") }
          let values = try controllerAdapter.decodeTelemetry(payload)
          pending = nil
          receive(values, identity: identity, at: current, responseSeconds: current - outstanding.sent)
        }
      } catch { fail(error.localizedDescription, reason: outstanding.request == .identity ? .identityRejected : .telemetryRejected); return }
    }
    // Drain this callback's pre-existing bytes before issuing the new request: trailing old
    // telemetry in an identity notification must not become the reply to the first poll.
    if identityVerified { send(.selective) }
  }

  private func receive(_ values: [String: Double], identity: CycControllerIdentity, at current: Double, responseSeconds: Double) {
    // Identity establishes the allowlist; only an actual fresh measurement ends recovery.
    status = "connected"
    recoveryErrorMessage = nil
    if let gap = diagnosticMetrics.receivedSample(at: current, responseSeconds: responseSeconds) {
      note(.telemetryGap, fields: ["gapSeconds": gap])
    }
    sessionSamples += 1
    var sample = identity.annotate(captureClock.observation(values, monotonic: current))
    sample["connectionEpoch"] = connectionEpoch
    sample["elapsedSeconds"] = current - sessionStarted
    sample["sequence"] = sessionSamples
    if stableSince == nil { stableSince = current }
    if let stableSince, current - stableSince >= CycReconnectPolicy.stablePeriod { reconnectAttempts = 0 }
    lastSample = current
    let storageStarted = now
    do {
      #if os(iOS)
      if #available(iOS 26.0, *) {
        try WorkoutEngine.shared.admitCyc(PowerLogCaptureFrame(sample: sample, liveID: captureClock.sessionID,
          liveStartedAt: WorkoutCoding.timestamp(captureClock.wallOrigin), liveOrigin: sessionStarted,
          liveElapsed: current - sessionStarted))
      } else { try MonitorDataStore.shared.appendLive(sample, elapsedSeconds: current - sessionStarted) }
      #else
      try MonitorDataStore.shared.appendLive(sample, elapsedSeconds: current - sessionStarted)
      #endif
    }
    catch {
      note(.recordingError, reason: .storageFailure, error: error)
      storeError = "Live capture storage failed: \(error.localizedDescription)"
    }
    maxStorageWriteMs = max(maxStorageWriteMs, (now - storageStarted) * 1000)
    latestSample = sample
    if !background, current - lastPresentation >= 0.25 {
      lastPresentation = current
      emit("onSample", sample)
      emitState()
    }
    updateBackgroundHold()
  }
}

extension CycEngine {
  static func processCPUSeconds() -> Double {
    var time = timespec()
    clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &time)
    return Double(time.tv_sec) + Double(time.tv_nsec) / 1_000_000_000
  }
}

#if canImport(UIKit)
import UIKit

final class CycBackgroundHold {
  static let shared = CycBackgroundHold()
  private var task: UIBackgroundTaskIdentifier = .invalid
  func set(active: Bool) {
    if active, task == .invalid {
      task = UIApplication.shared.beginBackgroundTask(withName: "Power Log bike reconnect") { [weak self] in self?.set(active: false) }
    } else if !active, task != .invalid {
      UIApplication.shared.endBackgroundTask(task)
      task = .invalid
    }
  }
}
#endif
