import Foundation

final class Transport: WatchWorkoutTransport {
  var reachable = true
  var received: ((WorkoutCompanionMessage, @escaping (WorkoutCompanionMessage) -> Void) -> Void)?
  var changed: (() -> Void)?
  struct Sent {
    let message: WorkoutCompanionMessage
    let completion: ((Result<WorkoutCompanionMessage, Error>) -> Void)?
    func reply(_ message: WorkoutCompanionMessage) { completion?(.success(message)) }
  }
  var sent: [Sent] = []
  func send(_ message: WorkoutCompanionMessage, completion: ((Result<WorkoutCompanionMessage, Error>) -> Void)?) {
    sent.append(Sent(message: message, completion: completion))
  }
  func receive(_ message: WorkoutCompanionMessage) async -> WorkoutCompanionMessage {
    await withCheckedContinuation { continuation in received?(message) { continuation.resume(returning: $0) } }
  }
}
final class Health: WatchWorkoutHealth {
  var busy = false
  var liveReadings: ((String, [String: Double], Date) -> Void)?
  var observation: ((String, WorkoutCompanionObservation) -> Void)?
  var status: ((String, String, String?, String?) -> Void)?
  var finishes: [Bool] = []
  var publications = 0
  var pauses = 0
  var resumes = 0
  var laps = 0
  var started: String?
  var deferAuthorization = false
  var deferStart = false
  var authorizations: [(Result<Void, Error>) -> Void] = []
  var starts: [(Result<Void, Error>) -> Void] = []
  func authorize(writing: Bool, completion: @escaping (Result<Void, Error>) -> Void) {
    if deferAuthorization { authorizations.append(completion) } else { completion(.success(())) }
  }
  func start(_ options: WorkoutCompanionOptions, completion: @escaping (Result<Void, Error>) -> Void) {
    busy = true
    started = options.id
    if deferStart { starts.append(completion) } else { completion(.success(())) }
  }
  func recoverForCleanup(completion: @escaping (String?) -> Void) { completion(nil) }
  func pause() { pauses += 1 }
  func resume() { resumes += 1 }
  func lap(at date: Date) { laps += 1 }
  func recordLocations(_ locations: [WorkoutCompanionLocation]) {}
  func publish(readings: [String: Double], at date: Date, locations: [WorkoutCompanionLocation]) { publications += 1 }
  func finish(at date: Date, discard: Bool, completion: @escaping () -> Void) {
    finishes.append(discard)
    busy = false
    completion()
  }
}
@MainActor
func check(_ value: Bool, _ message: String) { precondition(value, message) }
func settle() async { for _ in 0..<20 { await Task.yield() } }
func options(_ id: String) -> WorkoutCompanionOptions {
  WorkoutCompanionOptions(id: id, start: Date(), indoor: true, saveToHealth: true, recordGPS: false)
}
func start(_ transport: Transport, id: String, request: String? = nil) async {
  let response = await transport.receive(
    WorkoutCompanionMessage(
      kind: .start, rideID: id, commandID: UUID().uuidString, startRequestID: request,
      expiresAt: Date().addingTimeInterval(15), options: options(id)))
  precondition(response.phase == "running", "native Start handshake completes")
}
func observation() -> WorkoutCompanionObservation {
  WorkoutCompanionObservation(
    id: UUID().uuidString, timestamp: Date(), start: Date().addingTimeInterval(-1), metric: "heartRateBpm",
    identifier: "HKQuantityTypeIdentifierHeartRate", unit: "bpm", value: 123, cumulative: false)
}
@MainActor
func exercise() async throws {
  for discarded in [false, true] {
    let transport = Transport()
    let health = Health()
    var timeouts: [@MainActor () -> Void] = []
    let engine = WatchWorkoutEngine(
      transport: transport, health: health, schedule: { _, task in timeouts.append(task) })
    let first = UUID().uuidString
    let second = UUID().uuidString
    await start(transport, id: first)
    health.observation?(first, observation())
    await settle()
    transport.changed?()
    await settle()
    let acknowledgement = transport.sent.last { $0.message.kind == .measurements }!
    await engine.finishRide(discard: discarded, confirmedRideID: first)
    let oldControl = transport.sent.last { $0.message.kind == .command }!
    _ = await transport.receive(
      WorkoutCompanionMessage(
        kind: .state, rideID: first, commandID: UUID().uuidString, action: discarded ? "discard" : "stop",
        phase: "completed", stateRevision: 3))
    check(!health.busy && health.finishes == [discarded], "terminal push ends only its own native session")
    await start(transport, id: second)
    health.observation?(second, observation())
    await settle()
    oldControl.reply(
      WorkoutCompanionMessage(
        kind: .state, rideID: first, commandID: oldControl.message.commandID, action: discarded ? "discard" : "stop",
        phase: "completed", stateRevision: 3,
        healthSeconds: [WorkoutCompanionSecond(time: Date(), readings: ["riderPowerW": 300])]))
    acknowledgement.reply(
      WorkoutCompanionMessage(kind: .acknowledgement, rideID: first, acknowledgement: acknowledgement.message.sequence))
    health.status?(first, "unknown", nil, "late old error")
    health.liveReadings?(first, ["heartRateBpm": 999], Date())
    for timeout in timeouts { timeout() }
    await settle()
    check(engine.selectedRideID == second && engine.phase == "running" && health.busy, "old reply cannot end new ride")
    check(health.publications == 0 && health.finishes.count == 1, "old reply has no Health side effects")
    check(engine.savedStatusLabel.isEmpty && engine.displayedIssue == nil, "old status/timeouts cannot change new UI")
    check(engine.readings(tick: Date()).heart == "123", "old native readings cannot enter new ride")
    try await Task.sleep(nanoseconds: 1_050_000_000)
    transport.changed?()
    await settle()
    check(
      transport.sent.last { $0.message.kind == .measurements }?.message.observations?.count == 1,
      "old observation ACK cannot remove the new ride buffer")
    _ = await transport.receive(
      WorkoutCompanionMessage(kind: .state, rideID: second, phase: "paused", stateRevision: 4))
    _ = await transport.receive(
      WorkoutCompanionMessage(kind: .state, rideID: second, phase: "running", stateRevision: 3))
    check(engine.phase == "paused" && health.pauses == 1 && health.resumes == 0, "late state cannot undo Pause")
    let lap = WorkoutCompanionMessage(
      kind: .state, rideID: second, commandID: UUID().uuidString, action: "lap", phase: "paused", stateRevision: 4)
    _ = await transport.receive(lap)
    _ = await transport.receive(lap)
    check(health.laps == 1 && engine.lapCount == 1, "duplicate commands do not duplicate laps")
    engine.resume()
    timeouts.last?()
    check(engine.actionAvailable(.resume), "a timed-out current command releases controls")
    check(engine.displayedIssue != nil, "the current command timeout remains actionable")
    health.status?(second, "saved", nil, nil)
    await settle()
    transport.reachable = false
    await engine.finishRide(discard: true, confirmedRideID: second)
    health.status?(second, "unknown", nil, "discard status")
    await settle()
    check(!health.busy && health.finishes.last == true, "offline Discard still ends the native session")
    check(engine.savedStatusLabel.isEmpty, "Discard has no Health status, including delayed callbacks")
  }
  // Explicit cancellation remains local and immediate at every asynchronous Start boundary.
  for stage in ["before-phone", "authorization", "native-start"] {
    let transport = Transport()
    let health = Health()
    let engine = WatchWorkoutEngine(transport: transport, health: health, schedule: { _, _ in })
    await engine.startRide(indoor: true, saveToHealth: true, gpsSetting: 2)
    let request = transport.sent.last!
    let first = UUID().uuidString
    let command = UUID().uuidString
    let message = WorkoutCompanionMessage(
      kind: .start, rideID: first, commandID: command, startRequestID: request.message.commandID,
      expiresAt: Date().addingTimeInterval(15), options: options(first))
    var response: WorkoutCompanionMessage?
    if stage != "before-phone" {
      health.deferAuthorization = stage == "authorization"
      health.deferStart = stage == "native-start"
      transport.received?(message) { response = $0 }
      await settle()
      check(engine.phase == "preparing", "Start remains cancellable during native preparation")
    }
    transport.reachable = false
    engine.cancelStart()
    check(engine.phase == "idle" && !health.busy, "Cancel Start ends the local pending native session while offline")
    if stage != "before-phone" { check(response?.error != nil, "cancel resolves the phone's pending Start reply") }
    transport.reachable = true
    await engine.foreground()
    let cancellation = transport.sent.last { $0.message.kind == .cancelStart }!
    check(
      cancellation.message.startRequestID == request.message.commandID,
      "cancellation carries the original request identity")
    let rejected = await transport.receive(message)
    check(rejected.error != nil && engine.phase == "idle", "delayed Start cannot revive a cancelled attempt")
    health.deferAuthorization = false
    health.deferStart = false
    let second = UUID().uuidString
    await start(transport, id: second)
    let finishes = health.finishes.count
    for callback in health.authorizations + health.starts { callback(.success(())) }
    request.reply(WorkoutCompanionMessage(kind: .state, rideID: first, phase: "running", stateRevision: 2))
    await settle()
    check(
      engine.selectedRideID == second && engine.phase == "running" && health.busy,
      "late authorization/native callbacks cannot replace the newer ride")
    check(
      health.finishes.count == finishes && health.started == second,
      "late cancelled callback never finishes or starts the newer native session")
  }
  let transport = Transport()
  let health = Health()
  var timeouts: [@MainActor () -> Void] = []
  let engine = WatchWorkoutEngine(transport: transport, health: health, schedule: { _, task in timeouts.append(task) })
  await engine.startRide(indoor: true, saveToHealth: true, gpsSetting: 2)
  let request = transport.sent.last!
  let ride = UUID().uuidString
  await start(transport, id: ride, request: request.message.commandID)
  request.reply(WorkoutCompanionMessage(kind: .state, rideID: ride, phase: "running", stateRevision: 1))
  await settle()
  for timeout in timeouts { timeout() }
  check(engine.phase == "running" && engine.actionAvailable(.pause), "Watch Start keeps its correlated reply valid")
  check(
    engine.displayedIssue == nil && health.started == ride, "Watch Start does not leave an obsolete pending command")
  _ = await transport.receive(WorkoutCompanionMessage(kind: .state, rideID: ride, phase: "completed", stateRevision: 2))
  await engine.startRide(indoor: true, saveToHealth: true, gpsSetting: 2)
  let nextRequest = transport.sent.last!
  engine.cancelStart()
  check(
    transport.sent.last!.message.startRequestID == nextRequest.message.commandID,
    "a second Watch Start cancels its own request, never the previous ride's request")
  let delayed = await transport.receive(
    WorkoutCompanionMessage(
      kind: .start, rideID: UUID().uuidString, commandID: UUID().uuidString,
      startRequestID: nextRequest.message.commandID, expiresAt: Date().addingTimeInterval(15),
      options: options(UUID().uuidString)))
  check(
    delayed.error != nil && engine.phase == "idle", "the cancelled second Start cannot revive after its phone reply")
  check(
    !(String(data: try request.message.encoded(), encoding: .utf8)!.contains("sampleHz")),
    "Watch requests no capture rate")
  do {
    let transport = Transport()
    let health = Health()
    let engine = WatchWorkoutEngine(transport: transport, health: health, schedule: { _, _ in })
    let ride = UUID().uuidString
    await start(transport, id: ride)
    func heartSends() -> [Transport.Sent] {
      transport.sent.filter { $0.message.kind == .measurements && $0.message.readings?["heartRateBpm"] != nil }
    }
    health.liveReadings?(ride, ["heartRateBpm": 131], Date())
    await settle()
    transport.changed?()
    await settle()
    check(
      engine.phase == "running" && heartSends().count == 1 && heartSends()[0].message.observations?.isEmpty == true,
      "a new heart rate is sent without buffered observations")
    heartSends()[0].completion?(.failure(WorkoutCompanionError.invalid("Transport failed.")))
    await settle()
    try await Task.sleep(nanoseconds: 1_050_000_000)
    transport.changed?()
    await settle()
    check(heartSends().count == 2, "an undelivered heart rate is sent again")
    heartSends()[1].reply(WorkoutCompanionMessage(kind: .acknowledgement, rideID: ride))
    await settle()
    try await Task.sleep(nanoseconds: 1_050_000_000)
    transport.changed?()
    await settle()
    check(heartSends().count == 2, "a delivered heart rate is not sent again without new data")
  }
  print(
    "Production Watch callbacks: stale replies/ACKs/status, Start correlation, ordering, timeouts and Discard passed")
}
Task { @MainActor in
  do {
    try await exercise()
    exit(0)
  } catch { fatalError("Watch engine test failed: \(error)") }
}
dispatchMain()
