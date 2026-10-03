import Foundation

var checks = 0
func check(_ value: Bool, _ message: String) {
  checks += 1
  if !value { fatalError(message) }
}
func rejects(_ message: String, _ body: () throws -> Void) {
  do {
    try body()
    fatalError(message)
  } catch { checks += 1 }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-clock-health-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let start = Date(timeIntervalSince1970: 1_780_000_000)
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("phone"))
func event(
  _ id: String, kind: String = "telemetry", utc: Double, elapsed: Double, action: String = "start",
  source: String = "phone"
) throws -> WorkoutEvent {
  try WorkoutEvent(
    workoutId: id, kind: kind, source: kind == "telemetry" ? "cyc" : source,
    timestamp: start.addingTimeInterval(utc), elapsedSeconds: elapsed,
    payload: kind == "telemetry"
      ? ["humanPowerW": .number(150), "cadenceRpm": .number(80)] : ["action": .string(action)])
}
for shift in [-100.0, 100.0] {
  let ride = try archive.create(
    startedAt: start, indoor: false, watchEnabled: true, saveToHealth: true, recordGPS: true)
  let anchor = WorkoutTimelineAnchor(epoch: "watch", monotonicOrigin: 1000, startedAt: ride.startedAt)
  var gps = WorkoutTimelineAnchor.LocationClock()
  var timing = try WorkoutOwnerTiming(timestamp: ride.startedAt, elapsedSeconds: 0, timerSeconds: 0)
  var previousUptime = 1000.0
  var running = true
  for (action, elapsed) in [("start", 0.0), ("pause", 3.0), ("resume", 5.0), ("stop", 10.0)] {
    timing = try timing.advancing(
      anchor: anchor, at: start.addingTimeInterval(elapsed + (elapsed > 1 ? shift : 0)),
      from: previousUptime, to: 1000 + elapsed, running: running)
    previousUptime = 1000 + elapsed
    running = ["start", "resume"].contains(action)
    try archive.append(
      event(
        ride.id, kind: "lifecycle", utc: elapsed + (elapsed > 1 ? shift : 0), elapsed: elapsed, action: action,
        source: "watch"))
  }
  for (acquired, received) in [(1.0, 1.0), (2.0, 2.0), (2.5, 3.5), (4.0, 4.0), (6.0, 6.0)] {
    let fixDate = start.addingTimeInterval(acquired + (acquired > 1 ? shift : 0))
    let receiptDate = start.addingTimeInterval(received + (received > 1 ? shift : 0))
    let fix = try gps.map(
      timestamp: fixDate, latitude: 0, longitude: acquired / 100_000, accuracy: 3, receivedAt: receiptDate,
      uptime: 1000 + received, anchor: anchor)!
    check(fix.elapsed == acquired && gps.admit(fix), "GPS retains monotonic acquisition through either clock jump")
    let duplicate = try gps.map(
      timestamp: fixDate, latitude: 0, longitude: acquired / 100_000, accuracy: 3, receivedAt: receiptDate,
      uptime: 1000 + received, anchor: anchor)!
    check(!gps.admit(duplicate), "duplicate GPS is not a second observation")
    let active = try WorkoutTimelineAnchor.activeInterval(id: ride.id, elapsed: fix.elapsed, archive: archive)
    check((active != nil) == (acquired != 4), "delayed fixes use acquisition phase, including arrival after Pause")
    if acquired > 1 { check(fix.uncertain, "clock-straddling GPS mapping remains uncertain") }
    guard active != nil else { continue }
    let original = try WorkoutEvent(
      workoutId: ride.id, kind: "location", source: "watch", timestamp: fixDate, elapsedSeconds: fix.elapsed,
      payload: [
        "latitude": .number(0), "longitude": .number(acquired / 100_000), "horizontalAccuracyM": .number(3),
        "clockEpoch": .string(fix.epoch), "acquisitionMonotonic": .number(fix.acquisition),
      ])
    try archive.append(original)
    let telemetry = PowerLogCaptureFrame(
      sample: [
        "timestamp": WorkoutCoding.timestamp(fixDate), "observationId": UUID().uuidString,
        "humanPowerW": 150.0, "cadenceRpm": 80.0, "clockEpoch": "watch", "acquisitionMonotonic": fix.acquisition,
      ],
      liveID: UUID().uuidString, liveStartedAt: ride.startedAt, liveOrigin: 1000, liveElapsed: acquired,
      ride: PowerLogCaptureDestination(id: ride.id, generation: UUID(), timeline: anchor))
    let mapped = try telemetry.mappedRide()!
    try archive.append(mapped)
    check(
      mapped.elapsedSeconds == original.elapsedSeconds && mapped.timestamp == original.timestamp,
      "telemetry and GPS persist one timeline and unchanged UTC")
  }
  let reopened = try WorkoutArchive(rootURL: root.appendingPathComponent("phone"))
  let originals = try reopened.pageEvents(id: ride.id, limit: 128).map(\.event)
  check(
    originals.filter { $0.kind == "location" }.map(\.elapsedSeconds) == [1, 2, 2.5, 6],
    "clock-jump originals survive reopen")
  for kind in ["location", "telemetry", "lifecycle"] {
    let elapsed: [Double] = kind == "lifecycle" ? [0, 3, 5, 10] : [1, 2, 2.5, 6]
    check(
      originals.filter { $0.kind == kind }.map(\.timestamp)
        == elapsed.map { WorkoutCoding.timestamp(start.addingTimeInterval($0 + ($0 > 1 ? shift : 0))) },
      "forward and backward clock jumps preserve original UTC after reopen: " + kind)
  }
  check(
    timing.elapsedSeconds == 10 && timing.timerSeconds == 8
      && timing.timestamp == WorkoutCoding.timestamp(start.addingTimeInterval(10 + shift)),
    "terminal timing measures capture and pause time without substituting UTC duration")
  try reopened.update(id: ride.id, stopElapsedSeconds: timing.elapsedSeconds, ownerTiming: timing)
  try reopened.finish(id: ride.id, endedAt: WorkoutCoding.date(timing.timestamp))
  let finished = try WorkoutArchive(rootURL: root.appendingPathComponent("phone"))
  check(
    try WorkoutOwnerTiming.terminal(finished.metadata(id: ride.id)) == timing,
    "terminal UTC, elapsed and active time survive reopening after either clock jump")
  let summary = try WorkoutAnalysis.summarize(archive: finished, id: ride.id)
  check(
    summary.elapsedSeconds == 10 && summary.timerSeconds == 8,
    "FIT timing retains measured capture duration through forward and backward clock jumps")
  let closed = try PowerLogCaptureCutoff.owner(anchor, timing: timing)
  check(
    closed.stopUTC == timing.timestamp && closed.stopMonotonic == 1010,
    "cutoff admission does not order UTC against start")
  check(
    try !closed.map(epoch: "other", acquisition: 1009, timestamp: start.addingTimeInterval(9)).eligible,
    "foreign acquisition epoch cannot fall back to UTC")
}

let replayAnchor = WorkoutTimelineAnchor(
  epoch: "gps-replay", monotonicOrigin: 1000, startedAt: WorkoutCoding.timestamp(start))
var replayClock = WorkoutTimelineAnchor.LocationClock()
let originalFix = try replayClock.map(
  timestamp: start.addingTimeInterval(10), latitude: 1, longitude: 2, accuracy: 3,
  receivedAt: start.addingTimeInterval(10), uptime: 1010, anchor: replayAnchor)!
check(replayClock.admit(originalFix), "original GPS fix is admitted")
let replayFix = try replayClock.map(
  timestamp: start.addingTimeInterval(10), latitude: 1, longitude: 2, accuracy: 3,
  receivedAt: start.addingTimeInterval(15), uptime: 1020, anchor: replayAnchor)!
check(
  replayFix.elapsed == 15 && !replayClock.admit(replayFix),
  "rollback cannot remap the same GPS original into a second observation")
let distinctFix = try replayClock.map(
  timestamp: start.addingTimeInterval(11), latitude: 1, longitude: 2, accuracy: 3,
  receivedAt: start.addingTimeInterval(15), uptime: 1020, anchor: replayAnchor)!
check(replayClock.admit(distinctFix), "a distinct fix after rollback still records")
for owner in ["phone", "watch"] {
  var ending = WorkoutNativeEndingGate()
  ending.begin(id: owner)
  for _ in 0..<3 {
    check(!ending.confirm(id: owner, nativeEnded: false), "native shutdown deadline never confirms termination")
    rejects("Start stays gated while native termination is outstanding") { try ending.requireAvailable() }
  }
  check(!ending.confirm(id: "different", nativeEnded: true), "a foreign callback cannot release native ownership")
  check(ending.confirm(id: owner, nativeEnded: true), "only observed native termination releases ownership")
  try ending.requireAvailable()
}

let callbackAnchor = WorkoutTimelineAnchor(
  epoch: "watch", monotonicOrigin: 1000, startedAt: WorkoutCoding.timestamp(start))
let beforePause = try WorkoutOwnerTiming(timestamp: WorkoutCoding.timestamp(start), elapsedSeconds: 7, timerSeconds: 7)
let delayedPause = try beforePause.transitioning(
  anchor: callbackAnchor, at: start.addingTimeInterval(-100), from: 1007, to: 1015,
  receivedAt: 1010, wasRunning: true, running: false)
check(
  delayedPause.elapsedSeconds == 15 && delayedPause.timerSeconds == 10,
  "native pause receipt excludes actor delay from active time")
let delayedResume = try delayedPause.transitioning(
  anchor: callbackAnchor, at: start.addingTimeInterval(100), from: 1015, to: 1023,
  receivedAt: 1020, wasRunning: false, running: true)
check(
  delayedResume.elapsedSeconds == 23 && delayedResume.timerSeconds == 13,
  "native resume receipt includes actor delay in active time")
let checkpointAhead = try WorkoutOwnerTiming(
  timestamp: WorkoutCoding.timestamp(start), elapsedSeconds: 12, timerSeconds: 12)
let correctedPause = try checkpointAhead.transitioning(
  anchor: callbackAnchor, at: start, from: 1012, to: 1015, receivedAt: 1010, wasRunning: true, running: false)
check(correctedPause.timerSeconds == 10, "a checkpoint during callback scheduling does not retain extra active time")
rejects("native callback cannot map into a future receipt") {
  _ = try beforePause.transitioning(
    anchor: callbackAnchor, at: start, from: 1007, to: 1015, receivedAt: 1020, wasRunning: true, running: false)
}
rejects("native callback cannot map before its epoch") {
  _ = try beforePause.transitioning(
    anchor: callbackAnchor, at: start, from: 1007, to: 1015, receivedAt: 999, wasRunning: true, running: false)
}

let remote = try archive.create(
  startedAt: start, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false)
try archive.update(id: remote.id, phase: "preparing")
try archive.append(event(remote.id, utc: -500, elapsed: 30))
let report = try WorkoutOwnerTiming(
  timestamp: WorkoutCoding.timestamp(start.addingTimeInterval(-600)), elapsedSeconds: 20, timerSeconds: 15)
let confirmed = try WorkoutPhoneStartProjection.confirmOwnerPhase(
  archive: archive, id: remote.id, localPhase: "preparing", ownerPhase: "running", startedAt: start,
  now: start.addingTimeInterval(6000), uptime: 500, epoch: "phone", ownerTiming: report)!
check(
  confirmed.monotonicOrigin == 470 && confirmed.uncertain == true,
  "first delayed status respects committed floor without cross-device wall delta")
let retained = try WorkoutOwnerTiming.remote(
  id: remote.id, timestamp: report.timestamp, elapsed: 20, timer: 15, archive: archive)
check(
  retained.elapsedSeconds == 30 && retained.timerSeconds == 15,
  "transit and committed floor do not manufacture active time")
let resumed = confirmed.resuming(timing: retained, epoch: "restarted-phone", uptime: 10)
check(
  try !resumed.map(epoch: resumed.epoch, acquisition: 9, timestamp: start).eligible,
  "restart cannot admit acquisition before the new epoch")
rejects("missing owner duration must not use UTC") {
  _ = try WorkoutOwnerTiming.remote(
    id: remote.id, timestamp: report.timestamp, elapsed: nil, timer: nil, archive: archive)
}
let phoneStart = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false)
try archive.update(id: phoneStart.id, phase: "preparing")
let phoneAnchor = try WorkoutPhoneStartProjection.confirm(
  archive: archive, id: phoneStart.id, startedAt: start, now: start.addingTimeInterval(-500), uptime: 105,
  epoch: "phone", startedUptime: 100)
check(phoneAnchor.monotonicOrigin == 100, "Health Start callback clock change cannot change captured monotonic start")

let healthRide = try archive.create(
  startedAt: start, indoor: false, watchEnabled: false, saveToHealth: true, recordGPS: true)
try archive.append(event(healthRide.id, kind: "lifecycle", utc: 0, elapsed: 0))
let insertion = WorkoutHealthInsertionJournal(archive: archive)
var bounds = WorkoutHealthWriteBounds()
let later = try event(healthRide.id, utc: 10, elapsed: 1)
let backward = try event(healthRide.id, utc: 5, elapsed: 2)
let prestart = try event(healthRide.id, utc: -1, elapsed: 3)
let future = try event(healthRide.id, utc: 100, elapsed: 4)
var excessive = try event(healthRide.id, utc: 11, elapsed: 5)
excessive.payload["humanPowerW"] = .number(6000)
for original in [later, backward, prestart, future, excessive] { try archive.append(original) }
check(
  try insertion.admit([later], bounds: &bounds, now: start.addingTimeInterval(10), historical: false) == [later],
  "production admission allows real live telemetry")
bounds.settle(writer: "telemetry", id: later.eventId, success: true)
var laterResult: Result<Void, Error>?
insertion.insert(
  [later], authorized: { _ in true },
  operation: { writes, done in
    check(
      writes.count == 2 && writes.allSatisfy { $0.date == start.addingTimeInterval(10) },
      "Apple Health writes use original UTC rather than start plus capture elapsed")
    done(.success(()))
  }, completion: { laterResult = $0 })
try laterResult!.get()
for original in [backward, prestart, future] {
  check(
    try insertion.admit([original], bounds: &bounds, now: start.addingTimeInterval(11), historical: false).isEmpty,
    "permanent telemetry drops settle excluded receipts")
}
let admittedExcessive = try insertion.admit(
  [excessive], bounds: &bounds, now: start.addingTimeInterval(11), historical: false)
check(admittedExcessive == [excessive], "out-of-range power cannot exclude valid cadence")
var excessiveResult: Result<Void, Error>?
insertion.insert(
  admittedExcessive, authorized: { _ in true },
  operation: { writes, done in
    check(
      writes.count == 1 && writes[0].quantity.metric == "cadenceRpm" && writes[0].quantity.value == 80,
      "production insertion writes only the excessive power event's valid cadence")
    done(.success(()))
  }, completion: { excessiveResult = $0 })
try excessiveResult!.get()
bounds.settle(writer: "telemetry", id: excessive.eventId, success: true)
check(
  try insertion.metricResults([excessive])[excessive.eventId] == ["humanPowerW": "invalid", "cadenceRpm": "applied"],
  "excessive power settles per metric")
check(try !insertion.needsRepair(excessive), "invalid power is terminal while valid cadence is retained")
check(
  try insertion.outcome(id: healthRide.id) == "sealed" && !insertion.requiresAutomaticHistory(id: healthRide.id),
  "invalid values and whole time exclusions settle without stalling bounded discovery")
check(
  try insertion.admit([backward], bounds: &bounds, now: start.addingTimeInterval(11), historical: false).isEmpty,
  "duplicate excluded event stays settled")
var restartedBounds = WorkoutHealthWriteBounds()
let restartedEvent = try event(healthRide.id, utc: 6, elapsed: 6)
try archive.append(restartedEvent)
check(
  try insertion.admit([restartedEvent], bounds: &restartedBounds, now: start.addingTimeInterval(12), historical: false)
    == [restartedEvent], "restart resets only the in-memory writer bound")
try insertion.recordMetrics(
  [restartedEvent], results: WorkoutHealthTelemetryPlan(events: [restartedEvent]) { _ in true }.committed)
for writer in ["route", "speed", "distance", "lap"] {
  var clock = WorkoutHealthWriteBounds()
  check(
    clock.omission(writer: writer, start: start.addingTimeInterval(-1), end: start, workoutStart: start) != nil,
    "each app writer rejects pre-start UTC")
  clock.reserve(writer: writer, id: "one", end: start.addingTimeInterval(10))
  clock.settle(writer: writer, id: "one", success: true)
  check(
    clock.omission(
      writer: writer, start: start.addingTimeInterval(5), end: start.addingTimeInterval(6), workoutStart: start) != nil,
    "each app writer rejects backward UTC")
  check(
    WorkoutHealthWriteBounds().omission(
      writer: writer, start: start.addingTimeInterval(5), end: start.addingTimeInterval(6), workoutStart: start) == nil,
    "each app writer bound resets on restart")
}
check(
  bounds.omission(
    writer: "distance", start: start.addingTimeInterval(10), end: start.addingTimeInterval(9), workoutStart: start,
    interval: true) != nil, "positive elapsed distance with backward UTC endpoints is omitted")
let validStop = try WorkoutOwnerTiming(
  timestamp: WorkoutCoding.timestamp(start.addingTimeInterval(20)), elapsedSeconds: 50, timerSeconds: 50)
try archive.update(id: healthRide.id, stopElapsedSeconds: 50, ownerTiming: validStop)
try archive.finish(id: healthRide.id, endedAt: WorkoutCoding.date(validStop.timestamp))
let outside = try event(healthRide.id, utc: 30, elapsed: 10)
try archive.append(outside)
var historical = WorkoutHealthWriteBounds()
check(
  try insertion.admit([outside], bounds: &historical, now: start.addingTimeInterval(1000), historical: true).isEmpty,
  "historical writer omits UTC outside final interval despite valid elapsed")
let healthOriginals = try WorkoutArchive(rootURL: root.appendingPathComponent("phone"))
  .pageEvents(id: healthRide.id, limit: 128).map(\.event)
for original in [later, backward, prestart, future, excessive, restartedEvent, outside] {
  check(
    healthOriginals.first { $0.eventId == original.eventId } == original,
    "Health insertion and UTC omissions retain original timestamps, elapsed and values")
}

for saves in [true, false] {
  let watchRoot = root.appendingPathComponent(saves ? "watch-health" : "watch-local")
  let watch = try WatchWorkoutJournal(rootURL: watchRoot)
  let id = UUID().uuidString.lowercased()
  let initial: [String: Any] = [
    "workoutId": id, "startedAt": WorkoutCoding.timestamp(start), "saveToHealth": saves, "recordGPS": false,
    "phase": "running", "healthKitState": saves ? "pending" : "notRequested",
  ]
  try watch.create(id: id, metadata: initial)
  try watch.archive.append(event(id, kind: "lifecycle", utc: 0, elapsed: 0, source: "watch"))
  try watch.archive.append(event(id, kind: "lifecycle", utc: -100, elapsed: 10, action: "stop", source: "watch"))
  let cutoff = try WorkoutOwnerTiming(
    timestamp: WorkoutCoding.timestamp(start.addingTimeInterval(-100)), elapsedSeconds: 10, timerSeconds: 10)
  var finished = initial
  WatchWorkoutJournal.retain(cutoff, in: &finished, terminal: true)
  finished["phase"] = "finishing"
  try watch.save(id: id, metadata: finished)
  finished = try watch.settleInvalidClock(id: id)!
  check(
    finished["healthKitState"] as? String == (saves ? "unavailable" : "notRequested"),
    "terminal clock decision preserves frozen Health intent")
  let finishingOwner = try watch.control.observe(
    workoutID: id, owner: "watch", phase: "finishing", at: WorkoutCoding.date(cutoff.timestamp),
    health: saves ? "unavailable" : "notRequested", cutoff: WorkoutCoding.date(cutoff.timestamp), timing: cutoff,
    healthReason: WorkoutHealthFinalizationGate.clockReason)
  check(
    try WatchWorkoutJournal.requirements(finished, owner: finishingOwner, insertionOutcome: "unavailable")["ownerEnded"]
      == "pending", "Health settlement cannot substitute for native termination")
  rejects("native termination must be confirmed before sealing") {
    _ = try watch.confirmNativeEnd(id: id, nativeEnded: false)
  }
  finished = try watch.confirmNativeEnd(id: id, nativeEnded: true)
  finished["phase"] = "completed"
  try watch.save(id: id, metadata: finished)
  let owner = try watch.control.observe(
    workoutID: id, owner: "watch", phase: "completed", at: WorkoutCoding.date(cutoff.timestamp),
    health: saves ? "unavailable" : "notRequested", cutoff: WorkoutCoding.date(cutoff.timestamp), timing: cutoff,
    healthReason: WorkoutHealthFinalizationGate.clockReason)
  let reopened = try WatchWorkoutJournal(rootURL: watchRoot)
  let recovered = try reopened.metadata(id: id)
  check(
    !WorkoutRecoveryPlanner.inspectsWatchCandidate(
      phase: "completed", health: owner.healthOutcome,
      finalHealthExtracted: saves ? false : WatchWorkoutJournal.localSensorsSettled(recovered)),
    "restart does not discover impossible Health retry")
  let late = try event(id, utc: -105, elapsed: 5)
  try reopened.acceptTelemetry([late], firstSequence: 1)
  check(
    try reopened.metadata(id: id)["cycHealthSamplesIncomplete"] as? Bool == false,
    "late originals cannot reactivate abandoned Health work")
  let nextID = UUID().uuidString.lowercased()
  var next = initial
  next["workoutId"] = nextID
  try reopened.create(id: nextID, metadata: next)
  check(
    try reopened.metadata(id: nextID)["phase"] as? String == "running",
    "next local ride can be recorded after terminal settlement")
  let requirements = try WatchWorkoutJournal.requirements(
    recovered, owner: owner, insertionOutcome: WorkoutHealthInsertionJournal(archive: reopened.archive).outcome(id: id))
  let sources = try reopened.transfer.roster(id: id).map { try reopened.transfer.source(id: id, producer: $0) }
  let seal = WorkoutSeal(
    workoutID: id, sealRevision: 1, collectionRevision: try reopened.archive.revision(id: id),
    ownerRevision: owner.ownerRevision, stopCutoff: cutoff.timestamp, healthOutcome: owner.healthOutcome,
    requirements: requirements, sources: sources, stopElapsedSeconds: 10, timerSeconds: 10, saveToHealth: saves,
    recordGPS: false, healthReason: owner.healthReason)
  _ = try reopened.publishSeal(seal, metadata: reopened.metadata(id: id))
  let phone = try WorkoutArchive(rootURL: root.appendingPathComponent("destination-" + id))
  _ = try phone.create(
    id: id, startedAt: start, indoor: false, watchEnabled: true, saveToHealth: saves, recordGPS: false)
  try phone.update(id: id, phase: "preparing")
  let receiver = WorkoutTransferJournal(archive: phone)
  _ = try WorkoutPhoneTerminalProjection.accept(
    archive: phone, transfer: receiver, incoming: seal, startedAt: start, localPhase: "preparing", timerSeconds: nil,
    now: start.addingTimeInterval(5000), uptime: 1, epoch: "receiver")
  check(try !receiver.verify(id: id), "a disconnected Watch seal cannot verify without its originals")
  let phoneOriginals = try reopened.archive.pageEvents(id: id, limit: 128, producer: "cyc").map(\.event)
  try receiver.receiveLive(phoneOriginals, producer: "cyc", firstSequence: 1)
  let sender = WorkoutChunkSender(archive: reopened.archive)
  var transferred = 0
  while let chunk = try reopened.prepareOriginalChunk(id: id) {
    let wire = try WorkoutChunkWire.encode(
      chunk: chunk, startedAt: initial["startedAt"] as! String, indoor: false, saveToHealth: saves, recordGPS: false)!
    let decoded = try WorkoutChunkWire.decode(wire).chunk
    _ = try receiver.receiveWatch(
      decoded, startedAt: initial["startedAt"] as! String, indoor: false, saveToHealth: saves, recordGPS: false)
    _ = try sender.acknowledge(
      id: id, producer: "watch", identity: chunk.manifest.identity, lastSequence: chunk.manifest.lastSequence,
      contentHash: chunk.manifest.contentHash)
    transferred += chunk.manifest.count
  }
  check(transferred == 2, "reconnected Watch transfers originals after terminal Health abandonment")
  let again = try WorkoutArchive(rootURL: root.appendingPathComponent("destination-" + id))
  check(
    try WorkoutTransferJournal(archive: again).verify(id: id),
    "seal-first transfer verifies after phone restart with backward cutoff")
  let opened = try ExportSource { again }.open([
    "rideId": id, "kind": "fit", "context": ["exportedAt": cutoff.timestamp, "platform": "ios"],
  ])
  check(
    try opened["elapsedEnd"] as? Double == 10 && again.metadata(id: id).endedAt == cutoff.timestamp,
    "verified FIT export retains local timing and original cutoff")
  check(
    try again.metadata(id: id).healthReason == (saves ? WorkoutHealthFinalizationGate.clockReason : nil),
    "seal-first recovery keeps the clock reason only for a requested Health save")
  if saves {
    var stale = owner
    stale.ownerRevision += 1
    stale.healthOutcome = "pending"
    rejects("late owner callback cannot resurrect Health") {
      _ = try WorkoutControlReducer.accepts(stale, previous: owner)
    }
    rejects("late metadata callback cannot resurrect Health") {
      try reopened.archive.update(id: id, healthKitState: "saved")
    }
  }
  let identity = WorkoutEffectIdentity(workoutID: id, generation: UUID())
  check(
    !identity.matches(workoutID: nextID, generation: UUID()),
    "abandoned native callbacks do not belong to the new owner")
}

for utc in [-10.0, 0.0] {
  let ride = try archive.create(
    startedAt: start, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false)
  let control = WorkoutControlJournal(store: archive.store)
  try archive.append(event(ride.id, kind: "lifecycle", utc: 0, elapsed: 0))
  let uncertain = try event(ride.id, utc: 2, elapsed: 2)
  try archive.append(uncertain)
  try insertion.prepare([uncertain])
  let timing = try WorkoutOwnerTiming(
    timestamp: WorkoutCoding.timestamp(start.addingTimeInterval(utc)), elapsedSeconds: 8, timerSeconds: 8)
  try archive.update(id: ride.id, stopElapsedSeconds: 8, ownerTiming: timing)
  try archive.finish(id: ride.id, endedAt: WorkoutCoding.date(timing.timestamp))
  try archive.append(event(ride.id, kind: "lifecycle", utc: utc, elapsed: 8, action: "stop"))
  check(
    try WorkoutHealthFinalizationGate.settle(id: ride.id, archive: archive)
      == WorkoutHealthFinalizationGate.clockReason, "phone terminal clock predicate includes zero UTC duration")
  check(
    try archive.metadata(id: ride.id).healthKitState == "unavailable"
      && archive.metadata(id: ride.id).healthReason == WorkoutHealthFinalizationGate.clockReason,
    "unavailable Health keeps its clock reason with the ride")
  _ = try control.observe(
    workoutID: ride.id, owner: "phone", phase: "completed", at: WorkoutCoding.date(timing.timestamp),
    health: "unavailable", cutoff: WorkoutCoding.date(timing.timestamp), timing: timing,
    healthReason: WorkoutHealthFinalizationGate.clockReason)
  rejects("late native receipt cannot settle successful Health after abandonment") {
    try insertion.recordMetrics(
      [uncertain], results: WorkoutHealthTelemetryPlan(events: [uncertain]) { _ in true }.committed)
  }
  check(
    try insertion.pending(id: ride.id).isEmpty && insertion.outcome(id: ride.id) == "unavailable",
    "abandoned pending effects are invalidated durably")
  let reopened = try WorkoutArchive(rootURL: root.appendingPathComponent("phone"))
  let transfer = WorkoutTransferJournal(archive: reopened)
  let seal = try WorkoutPhoneSealRepair.seal(
    id: ride.id, archive: reopened, transfer: transfer, control: WorkoutControlJournal(store: reopened.store))
  check(
    seal.healthOutcome == "unavailable" && seal.healthReason == WorkoutHealthFinalizationGate.clockReason
      && seal.resolved, "phone restart seals terminal Health with its clock reason")
  check(try transfer.verify(id: ride.id), "phone terminal archive remains verified")
  let opened = try ExportSource { reopened }.open([
    "rideId": ride.id, "kind": "fit", "context": ["exportedAt": "2026-06-01T00:00:00.000Z", "platform": "ios"],
  ])
  check(opened["elapsedEnd"] as? Double == 8, "phone local FIT remains exportable after Health abandonment")
}
for kind in ["telemetry", "location", "lifecycle"] {
  let ride = try archive.create(
    startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: true)
  let payload: [String: WorkoutJSON]
  switch kind {
  case "telemetry": payload = ["humanPowerW": .number(100), "cadenceRpm": .number(80)]
  case "location": payload = ["latitude": .number(0), "longitude": .number(0), "horizontalAccuracyM": .number(3)]
  default: payload = ["action": .string("start")]
  }
  try archive.append(
    WorkoutEvent(
      workoutId: ride.id, kind: kind, source: kind == "telemetry" ? "cyc" : "phone",
      timestamp: start, elapsedSeconds: 0, payload: payload))
  try archive.update(id: ride.id, stopElapsedSeconds: 1)
  try archive.finish(id: ride.id, endedAt: start.addingTimeInterval(1))
  _ = try archive.store.transaction { db in
    try db.execute(
      "UPDATE collection_memberships SET original_elapsed_seconds=NULL WHERE collection_id=?", [.text(ride.id)])
  }
  rejects("FIT must reject malformed stored app timing: " + kind) {
    _ = try WorkoutAnalysis.summarize(archive: archive, id: ride.id)
  }
}
let healthOnly = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
try archive.append(
  WorkoutEvent(
    workoutId: healthOnly.id, kind: "health", source: "phone", timestamp: start.addingTimeInterval(2),
    payload: ["heartRateBpm": .number(123)]))
try archive.update(id: healthOnly.id, stopElapsedSeconds: 3)
try archive.finish(id: healthOnly.id, endedAt: start.addingTimeInterval(3))
let healthSummary = try WorkoutAnalysis.summarize(archive: archive, id: healthOnly.id)
check(healthSummary.elapsedSeconds == 3, "Health originals retain UTC placement without app elapsed")
check(
  WorkoutHealthFinalizationGate.unavailableReason(start: start, cutoff: start.addingTimeInterval(1)) == nil,
  "positive final UTC interval follows normal finalization")
for health in ["unavailable", "notRequested"] {
  check(
    WorkoutRecoveryPlanner.inspectsWatchCandidate(
      phase: "completed", health: health, finalHealthExtracted: true, nativeOwnerEnded: false),
    "restart between terminal persistence and native end still completes ownership")
  check(
    !WorkoutRecoveryPlanner.inspectsWatchCandidate(
      phase: "completed", health: health, finalHealthExtracted: true, nativeOwnerEnded: true),
    "ended terminal owner does not retry impossible Health work")
}
print(
  "Clock/Health production helpers: \(checks) checks passed; native Health and Bluetooth execution require device validation."
)
