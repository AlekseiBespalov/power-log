import Foundation
import PowerLogCore

func check(_ value: Bool, _ message: String) { precondition(value, message) }
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-phone-engine-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
func make() throws -> (WorkoutEngine, WorkoutArchive) {
  ControllerEngine.shared.queue.sync {
    ControllerEngine.shared.status = "connected"
    ControllerEngine.shared.requestedRates = []
  }
  let archive = try WorkoutArchive(rootURL: root.appendingPathComponent(UUID().uuidString))
  return (WorkoutEngine(archive: archive), archive)
}
func start(_ engine: WorkoutEngine, watch: Bool = false) throws -> String {
  let finished = DispatchSemaphore(value: 0)
  var result: Result<[String: Any], Error>!
  engine.queue.async {
    engine.start(indoor: true, useWatch: watch, saveToHealth: false, recordGPS: false) {
      result = $0
      finished.signal()
    }
  }
  check(finished.wait(timeout: .now() + 5) == .success, "Start completed")
  return try result.get()["id"] as! String
}
func frame(_ at: Double, sequence: Int) throws -> PowerLogCaptureFrame {
  let date = Date().addingTimeInterval(at - ProcessInfo.processInfo.systemUptime)
  let live = UUID().uuidString.lowercased()
  let origin = at - Double(sequence) / 8
  let start = date.addingTimeInterval(-Double(sequence) / 8)
  var clock = TelemetryCaptureClock(
    origin: origin, wallOrigin: start, sessionID: live, epochID: TelemetryCaptureClock.processEpoch)
  let sample = try clock.observation(
    testGeneratedTelemetryReading([
      "humanPowerW": 100.0 + Double(sequence), "cadenceRpm": 81.5,
      "batteryVoltageV": 52.8, "batteryCurrentA": Double(sequence) / 8, "motorInputPowerW": 211.2,
    ]), connectionEpoch: live, monotonic: at, wall: date)
  return PowerLogCaptureFrame(
    sample: sample, liveID: live, liveStartedAt: WorkoutCoding.timestamp(start), liveOrigin: origin,
    liveElapsed: Double(sequence) / 8)
}
func telemetry(_ archive: WorkoutArchive, _ id: String) throws -> [WorkoutEvent] {
  var events: [WorkoutEvent] = []
  try archive.forEachEvent(id: id) { if $0.kind == "telemetry" { events.append($0) } }
  return events
}
// Neither GPS nor a Watch can admit a new ride before the controller is connected.
for status in ["idle", "connecting", "reconnecting"] {
  for watchRequest in [false, true] {
    let (engine, archive) = try make()
    let transport = WorkoutCompanionTransport.latest!
    let health = WorkoutNativeHealth.latest!
    let location = WorkoutLocation.latest!
    ControllerEngine.shared.queue.sync { ControllerEngine.shared.status = status }
    var rejection: String?
    engine.queue.sync {
      if watchRequest {
        transport.received?(
          WorkoutCompanionMessage(
            kind: .startRequest, commandID: UUID().uuidString, expiresAt: Date().addingTimeInterval(15),
            options: WorkoutCompanionOptions(
              id: UUID().uuidString, start: Date(), indoor: false, saveToHealth: true, recordGPS: true))
        ) { rejection = $0.error }
      } else {
        engine.start(indoor: false, useWatch: false, saveToHealth: true, recordGPS: true) {
          if case .failure(let error) = $0 {
            rejection = error.localizedDescription
            let wire = WorkoutRideFailure.classify(error, stage: "start").wire
            check(wire["code"] == "bikeRequired", "the phone bridge preserves the bike admission failure")
            check(wire["message"] == rejection, "the phone bridge keeps the actionable admission message")
          }
        }
      }
      check(rejection == "Connect your bike to start.", "phone and Watch Start reject a missing connected bike")
      check(engine.state()["phase"] as? String == "idle", "rejected Start stays idle")
      check(health.authorizationRequests == 0 && health.startRequests == 0, "rejection never starts Health")
      check(location.permissionRequests == 0, "rejection never starts GPS preparation")
      check(!transport.sent.contains { $0.kind == .start }, "rejection never launches a Watch session")
    }
    check(try archive.list().isEmpty, "rejected Start creates no recording")
    check(ControllerEngine.shared.queue.sync { ControllerEngine.shared.requestedRates.isEmpty }, "no rate mutation")
  }
}
// Once admitted, a connection outage during permission or Watch preparation is an ordinary reconnect.
for watch in [false, true] {
  for discard in [false, true] {
    let (engine, archive) = try make()
    let transport = WorkoutCompanionTransport.latest!
    let location = WorkoutLocation.latest!
    let completed = DispatchSemaphore(value: 0)
    var result: Result<[String: Any], Error>?
    engine.queue.sync {
      transport.deferStart = watch
      location.deferPermission = !watch
      engine.start(indoor: false, useWatch: watch, saveToHealth: true, recordGPS: true) {
        result = $0
        completed.signal()
      }
    }
    engine.queue.sync {
      check(engine.state()["phase"] as? String == "preparing", "connected Start is admitted without telemetry")
      check(result == nil, "preparation waits for its actual permission or Watch callback")
    }
    ControllerEngine.shared.queue.sync { ControllerEngine.shared.status = "reconnecting" }
    engine.queue.sync {
      if watch {
        let sent = transport.sent.last { $0.kind == .start }!
        transport.startCompletion?(
          .success(
            WorkoutCompanionMessage(kind: .state, rideID: sent.rideID, commandID: sent.commandID, phase: "running")))
      } else {
        location.permissionCompletion?("authorized")
      }
    }
    check(completed.wait(timeout: .now() + 5) == .success, "accepted Start completes during a BLE outage")
    let id = try result!.get()["id"] as! String
    check(try archive.metadata(id: id).phase == "running", "temporary BLE outage does not cancel the accepted ride")
    let stopped = try engine.queue.sync { try engine.stop(expectedID: id, discard: discard) }
    check(stopped["phase"] as? String == (discard ? "idle" : "completed"), "outage leaves Finish and Discard available")
  }
}
// A live Watch is stronger evidence than stale installation metadata, for either Start origin.
do {
  let (engine, archive) = try make()
  let transport = WorkoutCompanionTransport.latest!
  engine.queue.sync { transport.installed = false }
  let id = try start(engine, watch: true)
  check(try archive.metadata(id: id).watchEnabled, "phone Start accepts a reachable Watch with stale metadata")
  check(
    engine.queue.sync { transport.sent.contains { $0.kind == .start && $0.rideID == id } },
    "phone Start still performs the Watch handshake")
  _ = try engine.queue.sync { try engine.stop(expectedID: id, discard: true) }
}
do {
  let (engine, archive) = try make()
  let transport = WorkoutCompanionTransport.latest!
  let request = UUID().uuidString
  let requested = WorkoutCompanionOptions(
    id: UUID().uuidString, start: Date(), indoor: true, saveToHealth: false, recordGPS: false)
  var response: WorkoutCompanionMessage?
  engine.queue.sync {
    transport.installed = false
    transport.deferStart = true
    transport.received?(
      WorkoutCompanionMessage(
        kind: .startRequest, commandID: request, expiresAt: Date().addingTimeInterval(15), options: requested)
    ) { response = $0 }
  }
  engine.queue.sync {
    check(response == nil, "receiving Watch Start does not itself confirm recording")
    check(engine.state()["phase"] as? String == "preparing", "Watch Start waits for its native handshake")
    let sent = transport.sent.last { $0.kind == .start }!
    check(sent.startRequestID == request, "Watch Start preserves its request identity through the handshake")
    transport.startCompletion?(
      .success(
        WorkoutCompanionMessage(kind: .state, rideID: sent.rideID, commandID: sent.commandID, phase: "running")))
    check(response?.phase == "running", "matching Watch confirmation permits recording despite stale metadata")
    check(try! archive.metadata(id: sent.rideID!).phase == "running", "confirmed Watch ride is persisted")
    _ = try! engine.stop(expectedID: sent.rideID, discard: true)
  }
}
// An incoming Start can precede the reachability update, but delivery failure must never imply recording.
do {
  let (engine, _) = try make()
  let transport = WorkoutCompanionTransport.latest!
  let requested = WorkoutCompanionOptions(
    id: UUID().uuidString, start: Date(), indoor: true, saveToHealth: false, recordGPS: false)
  let finished = DispatchSemaphore(value: 0)
  var response: WorkoutCompanionMessage?
  var phases: [String] = []
  engine.queue.sync {
    transport.installed = false
    transport.reachable = false
    engine.addSink(id: UUID()) { if let phase = $0["phase"] as? String { phases.append(phase) } }
    transport.received?(
      WorkoutCompanionMessage(
        kind: .startRequest, commandID: UUID().uuidString, expiresAt: Date().addingTimeInterval(15), options: requested)
    ) {
      response = $0
      finished.signal()
    }
  }
  engine.queue.sync {
    check(response == nil, "incoming Start is not rejected using lagging availability flags")
    check(engine.state()["phase"] as? String == "preparing", "unreachable incoming Start waits for the Watch")
    check(!transport.sent.contains { $0.kind == .start }, "unreachable Watch receives no premature Start")
    transport.startError = WorkoutCompanionError.invalid("Disconnected during Start")
    transport.reachable = true
    transport.changed?()
  }
  check(finished.wait(timeout: .now() + 5) == .success, "failed Watch delivery settles Start")
  engine.queue.sync {
    check(response?.error == "Disconnected during Start", "Watch delivery failure remains visible")
    check(engine.state()["phase"] as? String == "failed", "failed Watch handshake cannot start recording")
    check(!phases.contains("running"), "temporary Watch reachability never substitutes for confirmation")
  }
}
do {
  let (engine, archive) = try make()
  let transport = WorkoutCompanionTransport.latest!
  engine.queue.sync {
    transport.installed = false
    transport.reachable = false
  }
  do {
    _ = try start(engine, watch: true)
    fatalError("phone Start accepted an unavailable Watch")
  } catch {
    check(error.localizedDescription == "The Watch companion is not installed.", "missing Watch is still rejected")
  }
  check(try archive.list().isEmpty, "rejected phone Start does not create a ride")
}
do {
  let (engine, _) = try make()
  let transport = WorkoutCompanionTransport.latest!
  var installed: [Bool] = []
  engine.queue.sync {
    transport.installed = false
    transport.reachable = false
    engine.addSink(id: UUID()) {
      check($0["phase"] as? String == "idle", "Watch availability changes do not start a ride")
      installed.append(($0["watch"] as! [String: Any])["installed"] as! Bool)
    }
    transport.reachable = true
    transport.changed?()
    transport.reachable = false
    transport.changed?()
    transport.installed = true
    transport.changed?()
    check(installed == [false, true, false, true], "idle UI tracks live reachability and installation updates")
  }
}
// A capture flush waiting for the actual SQLite transaction must observe Stop's fence.
do {
  let (engine, archive) = try make()
  let id = try start(engine)
  let held = DispatchSemaphore(value: 0)
  let release = DispatchSemaphore(value: 0)
  let stopped = DispatchSemaphore(value: 0)
  let done = DispatchSemaphore(value: 0)
  WorkoutNativeHealth.latest.onPause = {
    DispatchQueue.global().async {
      try! archive.store.transaction { _ in
        held.signal()
        release.wait()
      }
    }
    held.wait()
    stopped.signal()
  }
  for index in 1...8 { try engine.admitController(frame(ProcessInfo.processInfo.systemUptime, sequence: index)) }
  engine.queue.async {
    defer { done.signal() }
    _ = try! engine.stop(expectedID: id)
  }
  check(stopped.wait(timeout: .now() + 5) == .success, "Stop installs cutoff and suspends capture")
  for index in 9...24 { try engine.admitController(frame(ProcessInfo.processInfo.systemUptime, sequence: index)) }
  release.signal()
  check(done.wait(timeout: .now() + 5) == .success, "Stop completed")
  engine.flushStorage()
  engine.queue.sync {}
  let events = try telemetry(archive, id)
  let saved = try archive.metadata(id: id)
  check(events.count == 8, "pre-Stop originals retained; concurrent post-Stop frames excluded")
  check(events.allSatisfy { $0.elapsedSeconds! <= saved.stopElapsedSeconds! }, "original extent never exceeds Stop")
}
enum Fault: Error { case disk }
for failingDrain in [false, true] {
  for discard in [false, true] {
    let (engine, archive) = try make()
    let id = try start(engine)
    archive.store.beforeCommitForTesting = { throw Fault.disk }
    do {
      _ = try engine.queue.sync {
        if failingDrain { try engine.admitController(frame(ProcessInfo.processInfo.systemUptime, sequence: 1)) }
        return try engine.stop(expectedID: id)
      }
      fatalError("Finish did not expose storage failure")
    } catch {}
    check(
      engine.queue.sync { engine.state()["phase"] as? String } == "interrupted", "failed Finish never silently resumes")
    archive.store.beforeCommitForTesting = nil
    try engine.admitController(frame(ProcessInfo.processInfo.systemUptime, sequence: 2))
    let result = try engine.queue.sync { try engine.stop(expectedID: id, discard: discard) }
    check(result["phase"] as? String == (discard ? "idle" : "completed"), "failed Finish offers Retry and Discard")
    if !discard {
      check(try telemetry(archive, id).count == (failingDrain ? 1 : 0), "retry retains only pre-cutoff originals")
    }
    _ = try start(engine)
  }
}
// Failed Finish still fences Health callbacks, including Watch messages delivered before Retry.
for watch in [false, true] {
  let (engine, archive) = try make()
  let id = try start(engine, watch: watch)
  let startDate = try WorkoutCoding.date(archive.metadata(id: id).startedAt)
  archive.store.beforeCommitForTesting = { throw Fault.disk }
  do {
    _ = try engine.queue.sync { try engine.stop(expectedID: id) }
    fatalError("expected Finish failure")
  } catch {}
  archive.store.beforeCommitForTesting = nil
  let before = WorkoutCompanionObservation(
    id: UUID().uuidString.lowercased(), timestamp: startDate, start: startDate,
    metric: "heartRateBpm", identifier: "HKQuantityTypeIdentifierHeartRate", unit: "bpm", value: 121,
    cumulative: false)
  var after = before
  after.id = UUID().uuidString.lowercased()
  after.timestamp = Date().addingTimeInterval(1)
  after.start = after.timestamp
  engine.queue.sync {
    if watch {
      WorkoutCompanionTransport.latest.received?(
        WorkoutCompanionMessage(kind: .measurements, rideID: id, observations: [before, after], sequence: 1)
      ) { check($0.error == nil, "Watch observations admitted through production receiver") }
    } else {
      WorkoutNativeHealth.latest.observation?(id, before)
      WorkoutNativeHealth.latest.observation?(id, after)
    }
  }
  check(try archive.hasEvent(id: id, eventID: before.id), "late delivery retains a pre-Stop Health original")
  check(try !archive.hasEvent(id: id, eventID: after.id), "failed Finish excludes post-Stop Health originals")
  _ = try engine.queue.sync { try engine.stop(expectedID: id) }
  let finished = try archive.metadata(id: id)
  var originals: [WorkoutEvent] = []
  try archive.forEachEvent(id: id) { originals.append($0) }
  check(
    try originals.allSatisfy { try $0.date <= WorkoutCoding.date(finished.endedAt!) },
    "Retry cannot leave Health originals beyond the saved cutoff")
}
// Energy intervals retain their original bounds and contribute only their overlap with the completed ride.
for watch in [false, true] {
  for (begin, end, expected) in [
    (2.0, 8.0, 6.0), (-2, 2, 2), (8, 12, 2), (-5, 15, 10),
    (-5, -1, 0), (12, 15, 0), (-5, 0, 0), (10, 15, 0),
  ] {
    let (engine, archive) = try make()
    let id = UUID().uuidString.lowercased()
    let startDate = Date(timeIntervalSince1970: 1_700_000_000)
    let stopDate = startDate.addingTimeInterval(10)
    let timing = try WorkoutOwnerTiming(
      timestamp: WorkoutCoding.timestamp(stopDate), elapsedSeconds: 10, timerSeconds: 10)
    _ = try archive.create(
      id: id, startedAt: startDate, indoor: true, watchEnabled: watch, saveToHealth: false, recordGPS: false,
      sampleHz: 2)
    for (date, elapsed, action) in [(startDate, 0.0, "start"), (stopDate, 10.0, "stop")] {
      try archive.append(
        WorkoutEvent(
          workoutId: id, kind: "lifecycle", source: "phone", timestamp: date, elapsedSeconds: elapsed,
          payload: ["action": .string(action)]))
    }
    _ = try archive.update(id: id, phase: "completed", stopElapsedSeconds: 10, ownerTiming: timing)
    _ = try archive.finish(id: id, endedAt: stopDate)
    let observations = [("activeEnergyKcal", "ActiveEnergyBurned"), ("basalEnergyKcal", "BasalEnergyBurned")].map {
      WorkoutCompanionObservation(
        id: UUID().uuidString.lowercased(), timestamp: startDate.addingTimeInterval(end),
        start: startDate.addingTimeInterval(begin), metric: $0.0, identifier: "HKQuantityTypeIdentifier" + $0.1,
        unit: "kcal", value: end - begin, cumulative: true, dataOrigin: "test.provider")
    }
    for sequence in 1...2 {
      engine.queue.sync {
        if watch {
          WorkoutCompanionTransport.latest.received?(
            WorkoutCompanionMessage(
              kind: .measurements, rideID: id, observations: observations, sequence: Int64(sequence))
          ) { check($0.error == nil, "Watch energy delivered through production receiver") }
        } else {
          for observation in observations { WorkoutNativeHealth.latest.observation?(id, observation) }
        }
      }
    }
    var health: [WorkoutEvent] = []
    try archive.forEachEvent(id: id) { if $0.kind == "health" { health.append($0) } }
    check(health.count == (expected > 0 ? 2 : 0), "only positive energy overlap admitted; replay is deduplicated")
    for observation in observations {
      guard expected > 0 else { continue }
      let original = health.first { $0.eventId == observation.id }!
      check(
        original.timestamp == WorkoutCoding.preciseTimestamp(observation.timestamp)
          && original.payload["sampleStart"] == .string(WorkoutCoding.preciseTimestamp(observation.start))
          && original.payload["sampleEnd"] == .string(WorkoutCoding.preciseTimestamp(observation.timestamp))
          && original.payload["value"] == .number(end - begin),
        "energy originals retain their identity, full interval and value")
    }
    let saved = try archive.metadata(id: id)
    check(saved.ownerTiming == timing && saved.endedAt == timing.timestamp, "late intervals cannot extend Stop")
    let summary = try WorkoutAnalysis.summarize(archive: archive, id: id)
    check(summary.elapsedSeconds == 10 && summary.timerSeconds == 10, "energy cannot extend ride duration")
    if expected > 0 {
      check(
        summary.activeEnergyKcal == expected && summary.basalEnergyKcal == expected,
        "summary clips each energy interval to the ride")
    } else {
      check(summary.activeEnergyKcal == nil && summary.basalEnergyKcal == nil, "outside energy stays absent")
    }
  }
}
do {
  let (engine, _) = try make()
  let id = try start(engine, watch: true)
  let transport = WorkoutCompanionTransport.latest!
  try engine.queue.sync {
    transport.reachable = false
    _ = try engine.pause(expectedID: id)
    transport.sent.removeAll()
    transport.reachable = true
    transport.changed?()
    transport.changed?()
  }
  let states = engine.queue.sync { transport.sent.filter { $0.kind == .state } }
  check(
    states.count == 1 && states[0].phase == "paused" && states[0].action == nil,
    "reconnect resends current Pause without replaying actions")
  Thread.sleep(forTimeInterval: 1.1)
  engine.queue.sync {
    transport.changed?()
    transport.changed?()
  }
  let all = engine.queue.sync { transport.sent.filter { $0.kind == .state } }
  check(all.count == 2 && all[1].stateRevision == states[0].stateRevision, "paused routine traffic capped at 1 Hz")
  _ = try engine.queue.sync { try engine.stop(expectedID: id, discard: true) }
}
// Cancellation may overtake Start in transit, or arrive while the native Start reply is outstanding.
do {
  let (engine, _) = try make()
  let transport = WorkoutCompanionTransport.latest!
  let request = UUID().uuidString
  let requested = WorkoutCompanionOptions(
    id: UUID().uuidString, start: Date(), indoor: true, saveToHealth: false, recordGPS: false)
  var rejected: WorkoutCompanionMessage?
  engine.queue.sync {
    transport.received?(
      WorkoutCompanionMessage(
        kind: .cancelStart, commandID: request, startRequestID: request, expiresAt: Date().addingTimeInterval(60))
    ) { _ in }
    transport.received?(
      WorkoutCompanionMessage(
        kind: .startRequest, commandID: request, expiresAt: Date().addingTimeInterval(15), options: requested)
    ) { rejected = $0 }
  }
  check(
    rejected?.error != nil && engine.queue.sync { engine.state()["phase"] as? String } == "idle",
    "cancel-before-Start never creates a ride")
  let nextRequest = UUID().uuidString
  engine.queue.sync {
    transport.deferStart = true
    transport.received?(
      WorkoutCompanionMessage(
        kind: .startRequest, commandID: nextRequest, expiresAt: Date().addingTimeInterval(15), options: requested)
    ) { _ in }
  }
  engine.queue.sync {}
  let oldStart = engine.queue.sync { transport.sent.last { $0.kind == .start }! }
  let oldReply = engine.queue.sync { transport.startCompletion! }
  engine.queue.sync {
    transport.received?(
      WorkoutCompanionMessage(
        kind: .cancelStart, rideID: oldStart.rideID, commandID: oldStart.commandID, startRequestID: nextRequest,
        expiresAt: Date().addingTimeInterval(60))
    ) { _ in }
  }
  check(
    engine.queue.sync { engine.state()["phase"] as? String } == "idle",
    "cancel during Watch handshake discards only its phone ride")
  let newRide = try start(engine)
  engine.queue.sync {
    oldReply(
      .success(
        WorkoutCompanionMessage(kind: .state, rideID: oldStart.rideID, commandID: oldStart.commandID, phase: "running"))
    )
    transport.received?(
      WorkoutCompanionMessage(
        kind: .cancelStart, commandID: nextRequest, startRequestID: nextRequest,
        expiresAt: Date().addingTimeInterval(60))
    ) { _ in }
  }
  check(
    engine.queue.sync { engine.state()["id"] as? String } == newRide
      && engine.queue.sync { engine.state()["phase"] as? String } == "running",
    "old Start replies and cancellation cannot affect a newer phone ride")
}
print(
  "Production phone engine: bike admission, Watch handshake, Stop cutoff, failed Finish, and paused convergence passed")
