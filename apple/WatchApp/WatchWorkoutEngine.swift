import Foundation
import Combine
#if os(watchOS)
  import HealthKit
  import WatchKit
#endif

protocol WatchWorkoutTransport: AnyObject {
  var reachable: Bool { get }
  var received: ((WorkoutCompanionMessage, @escaping (WorkoutCompanionMessage) -> Void) -> Void)? { get set }
  var changed: (() -> Void)? { get set }
  func send(_ message: WorkoutCompanionMessage, completion: ((Result<WorkoutCompanionMessage, Error>) -> Void)?)
}

protocol WatchWorkoutHealth: AnyObject {
  var busy: Bool { get }
  var liveReadings: ((String, [String: Double], Date) -> Void)? { get set }
  var observation: ((String, WorkoutCompanionObservation) -> Void)? { get set }
  var status: ((String, String, String?, String?) -> Void)? { get set }
  func authorize(writing: Bool, completion: @escaping (Result<Void, Error>) -> Void)
  func start(_ options: WorkoutCompanionOptions, completion: @escaping (Result<Void, Error>) -> Void)
  func recoverForCleanup(completion: @escaping (String?) -> Void)
  func pause()
  func resume()
  func lap(at date: Date)
  func recordLocations(_ locations: [WorkoutCompanionLocation])
  func publish(readings: [String: Double], at date: Date, locations: [WorkoutCompanionLocation])
  func finish(at date: Date, discard: Bool, completion: @escaping () -> Void)
}

#if os(watchOS)
  extension WorkoutCompanionTransport: WatchWorkoutTransport {}
  extension WorkoutNativeHealth: WatchWorkoutHealth {}
#endif

@MainActor
final class WatchWorkoutEngine: ObservableObject {
  #if os(watchOS)
    static let shared = WatchWorkoutEngine(
      transport: WorkoutCompanionTransport(queue: .main), health: WorkoutNativeHealth(queue: .main))
  #endif
  @Published private(set) var phase = "idle"
  @Published private(set) var displayedIssue: String?
  @Published private(set) var lapCount = 0
  @Published private(set) var selectedRideID: String?
  @Published private(set) var savedStatusLabel = ""
  @Published private(set) var distanceLabel = "—"
  @Published private(set) var distanceSourceLabel = "Phone GPS"
  private let transport: WatchWorkoutTransport
  private let health: WatchWorkoutHealth
  private let schedule: (TimeInterval, @escaping @MainActor () -> Void) -> Void
  private var generation = UUID()
  private var pendingStartRequest: String?
  private var currentStartRequest: String?
  private var startReply: ((WorkoutCompanionMessage) -> Void)?
  private var cancelledStarts = WorkoutCompanionCancellations()
  private var pendingCancellation: WorkoutCompanionMessage?
  private var discarded = false
  private var options: WorkoutCompanionOptions?
  private var buffer = WorkoutCompanionBuffer()
  private var timer: Timer?
  private var activated = false
  private var pendingCommand: String?
  private var currentStart: String?
  private var timerSeconds: Double = 0
  private var timerAt = Date()
  private var readingsAt = Date.distantPast
  private var heartAt = Date.distantPast
  private var sentHeartAt = Date.distantPast
  private var values: [String: Double] = [:]
  private var lastTelemetrySequence: Int64 = 0
  private var lastStateRevision: Int64 = 0
  private var lastSent = Date.distantPast
  private var endedHealth = false
  private var pendingHealth: WorkoutCompanionMessage?
  private var pendingHealthAt = Date.distantPast
  private var completedCommands: [String: WorkoutCompanionMessage] = [:]
  var isActive: Bool { ["running", "paused"].contains(phase) }
  var hasUnfinishedOwner: Bool { health.busy || phase == "preparing" }
  var canFinishAbsentOwner: Bool { phase == "interrupted" }

  init(
    transport: WatchWorkoutTransport, health: WatchWorkoutHealth,
    schedule: @escaping (TimeInterval, @escaping @MainActor () -> Void) -> Void = { delay, work in
      DispatchQueue.main.asyncAfter(deadline: .now() + delay) { work() }
    }
  ) {
    self.transport = transport
    self.health = health
    self.schedule = schedule
    transport.received = { [weak self] message, reply in
      Task { @MainActor in self?.receive(message, reply: reply) }
    }
    transport.changed = { [weak self] in Task { @MainActor in self?.flush() } }
    health.liveReadings = { [weak self] rideID, readings, at in
      Task { @MainActor in
        guard let self, self.selectedRideID == rideID, !self.endedHealth else { return }
        if let heart = readings["heartRateBpm"], at >= self.heartAt {
          self.values["heartRateBpm"] = heart
          self.heartAt = at
        }
      }
    }
    health.observation = { [weak self] rideID, observation in
      Task { @MainActor in
        guard let self, self.selectedRideID == rideID, !self.endedHealth else { return }
        if observation.metric == "heartRateBpm", observation.timestamp >= self.heartAt {
          self.values["heartRateBpm"] = observation.value
          self.heartAt = observation.timestamp
        }
        do { try self.buffer.append(observation, now: Date()) } catch {
          self.displayedIssue = WatchRideText.readingNotSent
        }
      }
    }
    health.status = { [weak self] rideID, state, id, reason in
      Task { @MainActor in
        guard let self, self.selectedRideID == rideID, !self.discarded else { return }
        self.savedStatusLabel = WatchRideText.health(state)
        self.pendingHealthAt = Date()
        self.pendingHealth = WorkoutCompanionMessage(
          kind: .health, rideID: rideID, healthState: state, healthID: id, error: reason)
        self.flush()
      }
    }
  }

  func activate() async {
    guard !activated else { return }
    activated = true
    let token = generation
    health.recoverForCleanup { [weak self] id in
      Task { @MainActor in
        guard let self, let id, self.generation == token else { return }
        self.selectedRideID = id
        self.phase = "interrupted"
        self.displayedIssue = WatchRideText.interrupted
      }
    }
    timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
      Task { @MainActor in self?.flush() }
    }
  }
  func foreground() async {
    await activate()
    flush()
  }
  #if os(watchOS)
    func handleLaunch(configuration: HKWorkoutConfiguration) async { await activate() }
    func handleActiveWorkoutRecovery() async { await activate() }
    func ownBackgroundTask(_ task: WKWatchConnectivityRefreshBackgroundTask) async {
      await activate()
      flush()
      task.setTaskCompletedWithSnapshot(false)
    }
  #endif

  func startRide(indoor: Bool, saveToHealth: Bool, gpsSetting: Int) async {
    guard actionAvailable(.start) else { return }
    guard transport.reachable else {
      displayedIssue = "Open Power Log on your phone to start a ride."
      return
    }
    let request = UUID().uuidString.lowercased()
    phase = "preparing"
    pendingCommand = request
    pendingStartRequest = request
    currentStartRequest = nil
    displayedIssue = nil
    let options = WorkoutCompanionOptions(
      id: UUID().uuidString.lowercased(), start: Date(), indoor: indoor,
      saveToHealth: saveToHealth, recordGPS: gpsSetting == 0 ? !indoor : gpsSetting == 1)
    sendControl(
      WorkoutCompanionMessage(
        kind: .startRequest, commandID: request, expiresAt: Date().addingTimeInterval(15), options: options),
      timeout: 15)
  }
  func cancelStart() {
    guard phase == "preparing" else { return }
    let request = currentStartRequest ?? pendingStartRequest
    let attempt = currentStart
    cancelledStarts.insert(request)
    cancelledStarts.insert(attempt)
    let cancellation = WorkoutCompanionMessage(
      kind: .cancelStart, rideID: currentStart == nil ? nil : selectedRideID,
      commandID: attempt ?? request, startRequestID: request, expiresAt: Date().addingTimeInterval(60))
    let reply = startReply
    startReply = nil
    generation = UUID()
    endHealth(discard: true)
    phase = "idle"
    selectedRideID = nil
    options = nil
    currentStartRequest = nil
    reply?(WorkoutCompanionMessage(kind: .acknowledgement, commandID: attempt, error: "Start cancelled."))
    pendingCancellation = cancellation
    sendCancellation()
  }
  private func sendCancellation() {
    guard let message = pendingCancellation else { return }
    guard message.expiresAt.map({ $0 >= Date() }) == true else {
      pendingCancellation = nil
      return
    }
    guard transport.reachable else { return }
    transport.send(message) { [weak self] result in
      Task { @MainActor in
        guard let self, self.pendingCancellation?.commandID == message.commandID,
          case .success(let response) = result, response.error == nil
        else { return }
        self.pendingCancellation = nil
      }
    }
  }
  func pause() { command("pause") }
  func resume() { command("resume") }
  func lap() { command("lap") }
  func finishRide(discard: Bool, confirmedRideID: String?) async {
    guard confirmedRideID == selectedRideID else {
      displayedIssue = WatchRideText.rideChanged
      return
    }
    if transport.reachable {
      command(discard ? "discard" : "stop")
    } else {
      endLocally(discard: discard, WatchRideText.endedUnreachable)
    }
  }
  private func endLocally(discard: Bool, _ issue: String) {
    endHealth(discard: discard)
    displayedIssue = issue
    phase = "completed"
  }
  private func command(_ action: String) {
    guard let id = selectedRideID, pendingCommand == nil else { return }
    let commandID = UUID().uuidString.lowercased()
    pendingCommand = commandID
    sendControl(
      WorkoutCompanionMessage(
        kind: .command, rideID: id, commandID: commandID,
        expiresAt: Date().addingTimeInterval(5), action: action), timeout: 5)
  }
  private func sendControl(_ message: WorkoutCompanionMessage, timeout: Double) {
    let token = generation
    let isStart = message.kind == .startRequest
    let isCurrent: @MainActor (WatchWorkoutEngine) -> Bool = { engine in
      engine.pendingCommand == message.commandID
        && (isStart
          ? engine.pendingStartRequest == message.commandID
          : engine.generation == token && engine.selectedRideID == message.rideID)
    }
    let unconfirmed = isStart ? WatchRideText.startUnconfirmed : WatchRideText.phoneUnconfirmed
    let failed: @MainActor (WatchWorkoutEngine, String) -> Void = { engine, issue in
      if ["stop", "discard"].contains(message.action) {
        engine.endLocally(discard: message.action == "discard", WatchRideText.endedUnconfirmed)
        return
      }
      if engine.phase == "preparing" && engine.currentStart == nil { engine.phase = "idle" }
      engine.displayedIssue = issue
    }
    transport.send(message) { [weak self] result in
      Task { @MainActor in
        guard let self, isCurrent(self) else { return }
        self.pendingCommand = nil
        if isStart { self.pendingStartRequest = nil }
        switch result {
        case .success(let reply):
          if let error = reply.error { failed(self, error) } else { self.apply(reply) }
        case .failure: failed(self, unconfirmed)
        }
      }
    }
    schedule(timeout) { [weak self] in
      guard let self, isCurrent(self) else { return }
      self.pendingCommand = nil
      if isStart { self.pendingStartRequest = nil }
      failed(self, unconfirmed)
    }
  }

  private func receive(_ message: WorkoutCompanionMessage, reply: @escaping (WorkoutCompanionMessage) -> Void) {
    if message.kind == .start,
      cancelledStarts.contains(message.commandID) || cancelledStarts.contains(message.startRequestID)
    {
      reply(WorkoutCompanionMessage(kind: .acknowledgement, commandID: message.commandID, error: "Start cancelled."))
      return
    }
    if let command = message.commandID, let result = completedCommands[command] {
      reply(result)
      return
    }
    if let expiry = message.expiresAt, expiry < Date() {
      reply(
        WorkoutCompanionMessage(kind: .acknowledgement, commandID: message.commandID, error: "The command expired."))
      return
    }
    if message.kind == .start {
      guard let options = message.options, !health.busy, currentStart == nil else {
        reply(
          WorkoutCompanionMessage(
            kind: .acknowledgement, commandID: message.commandID, error: "End the previous Watch session first."))
        return
      }
      generation = UUID()
      if message.startRequestID == nil || message.startRequestID != pendingStartRequest {
        pendingCommand = nil
        pendingStartRequest = nil
      }
      currentStart = message.commandID
      currentStartRequest = message.startRequestID
      startReply = reply
      selectedRideID = options.id
      self.options = options
      lastTelemetrySequence = 0
      lastStateRevision = 0
      distanceLabel = "—"
      distanceSourceLabel = options.distanceSource == "controller" ? "Controller" : "Phone GPS"
      phase = "preparing"
      endedHealth = false
      discarded = false
      savedStatusLabel = ""
      displayedIssue = nil
      pendingHealth = nil
      heartAt = .distantPast
      sentHeartAt = .distantPast
      readingsAt = .distantPast
      buffer = WorkoutCompanionBuffer()
      lapCount = 0
      values = [:]
      health.authorize(writing: true) { [weak self] result in
        Task { @MainActor in
          guard let self, self.currentStart == message.commandID else { return }
          if case .failure(let error) = result {
            self.startFailed(message, issue: WatchRideText.startFailure(error), reply: reply)
            return
          }
          guard message.expiresAt.map({ $0 >= Date() }) == true else {
            self.startFailed(message, issue: WatchRideText.startExpired, reply: reply)
            return
          }
          self.health.start(options) { result in
            Task { @MainActor in
              guard self.currentStart == message.commandID else { return }
              self.currentStart = nil
              switch result {
              case .failure(let error):
                self.startFailed(message, issue: WatchRideText.startFailure(error), reply: reply)
              case .success:
                guard message.expiresAt.map({ $0 >= Date() }) == true else {
                  self.endHealth(discard: true)
                  self.startFailed(message, issue: WatchRideText.startExpired, reply: reply)
                  return
                }
                self.startReply = nil
                self.phase = "running"
                self.timerAt = Date()
                self.timerSeconds = 0
                let result = WorkoutCompanionMessage(
                  kind: .state, rideID: options.id, commandID: message.commandID, phase: "running")
                self.remember(message.commandID, result: result)
                reply(result)
              }
            }
          }
        }
      }
      return
    }
    guard message.rideID == selectedRideID else {
      reply(WorkoutCompanionMessage(kind: .acknowledgement, rideID: message.rideID, error: "The ride changed."))
      return
    }
    if message.kind == .telemetry {
      let sequence = message.sequence ?? 0
      if sequence <= lastTelemetrySequence {
        reply(WorkoutCompanionMessage(kind: .acknowledgement, rideID: selectedRideID))
        return
      }
      lastTelemetrySequence = sequence
      if !endedHealth {
        readingsAt = message.sampleTime ?? .distantPast
        values.merge(message.readings ?? [:]) { _, new in new }
      }
    }
    apply(message)
    let result = WorkoutCompanionMessage(kind: .acknowledgement, rideID: selectedRideID, commandID: message.commandID)
    remember(message.commandID, result: result)
    reply(result)
  }
  private func startFailed(
    _ message: WorkoutCompanionMessage, issue: String, reply: (WorkoutCompanionMessage) -> Void
  ) {
    currentStart = nil
    startReply = nil
    phase = "idle"
    displayedIssue = issue
    reply(WorkoutCompanionMessage(kind: .acknowledgement, commandID: message.commandID, error: issue))
  }
  private func remember(_ id: String?, result: WorkoutCompanionMessage) {
    guard let id else { return }
    if completedCommands.count >= 64 { completedCommands.removeAll() }
    completedCommands[id] = result
  }
  private func apply(_ message: WorkoutCompanionMessage) {
    guard let rideID = message.rideID, rideID == selectedRideID else { return }
    if let error = message.error {
      displayedIssue = error
      return
    }
    if !endedHealth && message.action != "discard" {
      health.recordLocations(message.locations ?? [])
      for second in message.healthSeconds ?? [] {
        health.publish(readings: second.readings, at: second.time, locations: [])
      }
    }
    guard message.mayApplyState(after: lastStateRevision, ended: endedHealth) else { return }
    if let phase = message.phase {
      lastStateRevision = message.stateRevision ?? lastStateRevision
      if ["paused", "interrupted"].contains(phase) && !["paused", "interrupted"].contains(self.phase) {
        health.pause()
      }
      if phase == "running" && ["paused", "interrupted"].contains(self.phase) { health.resume() }
      if phase == "completed" || phase == "idle" { endHealth(discard: message.action == "discard") }
      self.phase = phase
    }
    if let active = message.active {
      timerSeconds = active
      timerAt = Date()
    }
    if message.action == "lap" {
      lapCount += 1
      health.lap(at: Date())
      #if os(watchOS)
        WKInterfaceDevice.current().play(.click)
      #endif
    }
    if let distance = message.readings?["distanceMeters"] { distanceLabel = String(format: "%.2f km", distance / 1000) }
  }
  private func endHealth(discard: Bool) {
    currentStart = nil
    pendingCommand = nil
    pendingStartRequest = nil
    currentStartRequest = nil
    if discard {
      displayedIssue = nil
      discarded = true
      savedStatusLabel = ""
      pendingHealth = nil
      buffer = WorkoutCompanionBuffer()
    }
    guard !endedHealth else { return }
    endedHealth = true
    health.finish(at: Date(), discard: discard) {}
  }
  private func flush() {
    guard transport.reachable, Date().timeIntervalSince(lastSent) >= 1 else { return }
    lastSent = Date()
    sendCancellation()
    if Date().timeIntervalSince(pendingHealthAt) > 60 { pendingHealth = nil }
    if let health = pendingHealth {
      transport.send(health) { [weak self] result in
        Task { @MainActor in
          if case .success(let response) = result, response.error == nil,
            self?.pendingHealth?.healthState == health.healthState, self?.pendingHealth?.rideID == health.rideID
          {
            self?.pendingHealth = nil
          }
        }
      }
    }
    let page = buffer.page(now: Date())
    let readings = Date().timeIntervalSince(heartAt) < 15 ? values.filter { $0.key == "heartRateBpm" } : [:]
    let heart = readings.isEmpty ? nil : heartAt
    guard let id = selectedRideID, !page.isEmpty || (isActive && heart.map { $0 > sentHeartAt } == true) else { return }
    let token = generation
    transport.send(
      WorkoutCompanionMessage(
        kind: .measurements, rideID: id, sampleTime: heartAt, observations: page.map(\.observation), readings: readings,
        sequence: page.last?.sequence)
    ) { [weak self] result in
      Task { @MainActor in
        guard let self, self.generation == token, self.selectedRideID == id,
          case .success(let reply) = result, reply.rideID == id, reply.error == nil
        else { return }
        if let heart, heart > self.sentHeartAt { self.sentHeartAt = heart }
        if let acknowledgement = reply.acknowledgement { self.buffer.acknowledge(acknowledgement) }
      }
    }
  }
  func elapsed() -> String {
    let seconds = Int(timerSeconds + (phase == "running" ? max(0, Date().timeIntervalSince(timerAt)) : 0))
    return String(format: "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
  }
  func actionAvailable(_ action: WatchRideAction, confirmation: String? = nil) -> Bool {
    if action == .start { return !hasUnfinishedOwner && pendingCommand == nil }
    if action == .stop { return isActive || canFinishAbsentOwner }
    return isActive && pendingCommand == nil && transport.reachable
  }
  func idlePresentation() -> WatchIdlePresentation {
    phase == "preparing" ? .starting : phase == "completed" ? .saved : .ready
  }
  func readings(tick: Date) -> WatchReadingsProjection {
    let fresh = tick.timeIntervalSince(readingsAt) < 6
    let heart = tick.timeIntervalSince(heartAt) < 15
    return WatchReadingsProjection(
      power: fresh ? format(values["riderPowerW"]) : "—", cadence: fresh ? format(values["cadenceRpm"]) : "—",
      heart: heart ? format(values["heartRateBpm"]) : "—",
      controller: WatchSourcePresentation(
        label: fresh ? "Controller" : "Controller unavailable", tone: fresh ? "ok" : "warning"),
      gps: options?.recordGPS == true ? "Phone GPS" : "GPS off")
  }
  private func format(_ value: Double?) -> String { value.map { String(format: "%.0f", $0) } ?? "—" }
}
