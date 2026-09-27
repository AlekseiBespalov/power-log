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
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-owner-timing-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"))
let control = WorkoutControlJournal(store: archive.store)
let transfer = WorkoutTransferJournal(archive: archive)
let start = Date(timeIntervalSince1970: 1_780_000_000)
func timing(_ seconds: Double, timer: Double? = nil, utc: Double? = nil) throws -> WorkoutOwnerTiming {
  try WorkoutOwnerTiming(
    timestamp: WorkoutCoding.timestamp(start.addingTimeInterval(utc ?? seconds)), elapsedSeconds: seconds,
    timerSeconds: timer ?? seconds)
}
func options(_ timing: WorkoutOwnerTiming) -> [String: WorkoutJSON] {
  [
    "cutoffUTC": .string(timing.timestamp), "cutoffElapsedSeconds": .number(timing.elapsedSeconds),
    "timerSeconds": .number(timing.timerSeconds),
  ]
}
for sealFirst in [false, true] {
  let ride = try archive.create(
    startedAt: start, indoor: true, watchEnabled: true, saveToHealth: false, recordGPS: false)
  try archive.update(id: ride.id, phase: "running")
  let requested = try timing(10, timer: 8)
  let command = try control.createRemote(
    workoutID: ride.id, origin: "phone", action: "stop", at: start.addingTimeInterval(10), options: options(requested))
  try WorkoutPhoneTerminalProjection.request(archive: archive, command: command)
  let provisional = try archive.metadata(id: ride.id)
  check(
    provisional.ownerTiming == nil && provisional.endedAt == nil && provisional.stopElapsedSeconds == nil,
    "phone Finish retains its request separately from authoritative Watch timing")
  let initial = WorkoutTimelineAnchor(epoch: "phone", monotonicOrigin: 100, startedAt: ride.startedAt)
  let fence = try PowerLogCaptureCutoff.owner(initial, timing: requested)
  let ownerTiming = try timing(12, timer: 10, utc: 1002)
  let owner = WorkoutOwnerSnapshot(
    workoutID: ride.id, owner: "watch", ownerRevision: 2, effectiveAt: ownerTiming.timestamp,
    phase: "completed", healthOutcome: "notRequested", stopCutoff: ownerTiming.timestamp, timing: ownerTiming)
  let seal = WorkoutSeal(
    workoutID: ride.id, sealRevision: 1, collectionRevision: 0, ownerRevision: owner.ownerRevision,
    stopCutoff: ownerTiming.timestamp, healthOutcome: "notRequested",
    requirements: [
      "healthSave": "notRequested", "cycInsertion": "notRequested", "healthExtraction": "notRequested",
      "localSensors": "sealed", "ownerEnded": "sealed", "gps": "notRequested",
    ], sources: [],
    stopElapsedSeconds: ownerTiming.elapsedSeconds, timerSeconds: ownerTiming.timerSeconds,
    saveToHealth: false, recordGPS: false)
  func status() throws {
    _ = try control.accept(snapshot: owner)
    let retained = try WorkoutPhoneTerminalProjection.timing(owner)
    try WorkoutPhoneTerminalProjection.retain(
      archive: archive, id: ride.id, timing: retained, phase: owner.phase, health: owner.healthOutcome)
  }
  func acceptSeal() throws {
    check(
      try WorkoutPhoneTerminalProjection.accept(
        archive: archive, transfer: transfer, incoming: seal, startedAt: start, localPhase: "completed",
        timerSeconds: nil, now: start.addingTimeInterval(5000), uptime: 900, epoch: "phone") != nil,
      "delayed Watch seal is accepted regardless of status delivery order")
  }
  if sealFirst {
    try acceptSeal()
    try status()
  } else {
    try status()
    try acceptSeal()
  }
  let closed = try PowerLogCaptureCutoff.owner(
    fence, timing: WorkoutOwnerTiming.terminal(archive.metadata(id: ride.id)))
  check(
    closed.stopUTC == ownerTiming.timestamp && closed.stopMonotonic == 112,
    "authoritative timing replaces the provisional terminal capture anchor")
  check(try transfer.verify(id: ride.id), "delayed Watch Finish verifies and completes synchronization")
  check(
    try WorkoutOwnerTiming.terminal(archive.metadata(id: ride.id)) == ownerTiming,
    "archive and terminal capture retain the Watch tuple unchanged")
  var conflicting = owner
  conflicting.ownerRevision += 1
  conflicting.timing = try timing(13, timer: 10, utc: 1002)
  check(
    !WorkoutPhoneTerminalProjection.acceptsStatus(conflicting, after: seal),
    "a newer status cannot replace terminal timing under an unchanged UTC cutoff")
  var incomplete = owner
  incomplete.stopCutoff = nil
  rejects("terminal status cannot substitute effective time for an absent cutoff") {
    _ = try WorkoutPhoneTerminalProjection.timing(incomplete)
  }
  incomplete = owner
  incomplete.timing = nil
  rejects("terminal snapshots must carry their complete tuple") {
    _ = try WorkoutControlReducer.accepts(incomplete, previous: nil)
  }
}

for phase in ["finishing", "completed"] {
  for health in ["saved", "discarded", "notRequested"] {
    let malformed = WorkoutOwnerSnapshot(
      workoutID: UUID().uuidString, owner: "watch", ownerRevision: 1, effectiveAt: WorkoutCoding.timestamp(start),
      phase: phase, healthOutcome: health)
    rejects("terminal owner without cutoff and timing must not reach discard or completion") {
      _ = try control.accept(snapshot: malformed)
    }
    check(try control.snapshot(workoutID: malformed.workoutID) == nil, "malformed terminal snapshot is never persisted")
  }
}

let startRide = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: true, recordGPS: false)
try archive.update(id: startRide.id, phase: "preparing")
var admittedStart: WorkoutCommand?
func prepareStart() throws {
  try WorkoutPhoneStartProjection.prepare(
    archive: archive, id: startRide.id, startedAt: start, uptime: 70, epoch: "start-process"
  ) { anchor, timing in
    let command = try control.admitLocal(workoutID: startRide.id, origin: "phone", action: "start", at: start)
    _ = try control.begin(command)
    admittedStart = command
    try archive.store.transaction { db in
      try db.put(
        namespace: "phone-current", key: "workout",
        value: JSONSerialization.data(withJSONObject: [
          "id": startRide.id, "startedAt": anchor.startedAt, "timelineAnchor": WorkoutCoding.dictionary(anchor),
          "checkpointUTC": timing.timestamp, "elapsedSeconds": timing.elapsedSeconds,
          "timerSeconds": timing.timerSeconds,
        ]))
    }
  }
}
archive.store.beforeCommitForTesting = { throw PowerLogStorageError.sqlite(13, "injected start commit failure") }
rejects("failed start intent cannot leave a partial native-start checkpoint") { try prepareStart() }
archive.store.beforeCommitForTesting = nil
check(try control.active(workoutID: startRide.id) == nil, "start command rolls back with timing")
try prepareStart()
let reopened = try WorkoutArchive(rootURL: archive.rootURL)
let saved =
  try JSONSerialization.jsonObject(
    with: reopened.store.read {
      try $0.get(namespace: "phone-current", key: "workout")!
    }) as! [String: Any]
let savedTiming = try WorkoutOwnerTiming.checkpoint(saved)
let savedAnchor = try JSONDecoder().decode(
  WorkoutTimelineAnchor.self, from: JSONSerialization.data(withJSONObject: saved["timelineAnchor"]!))
check(
  try savedTiming == timing(0) && savedAnchor.monotonicOrigin == 70 && savedAnchor.startedAt == savedTiming.timestamp,
  "crash during Health Start retains start UTC, monotonic anchor and initial timing")
check(
  try WorkoutControlJournal(store: reopened.store).active(workoutID: startRide.id) == admittedStart,
  "reopened native Start has its matching durable intent")
let recoveryControl = WorkoutControlJournal(store: reopened.store)
check(
  try !WorkoutPhoneStartProjection.hasConfirmedOwner(id: startRide.id, control: recoveryControl),
  "a persisted anchor is start intent, not confirmed native ownership")
check(
  WorkoutUnconfirmedStopPolicy.action(
    hasConfirmedOwner: try WorkoutPhoneStartProjection.hasConfirmedOwner(id: startRide.id, control: recoveryControl),
    preparing: false, hasNativeIntent: true) == .retainOwnerIntent,
  "Finish after pre-native crash requests cancellation through owner recovery")
_ = try recoveryControl.admitLocal(
  workoutID: startRide.id, origin: "phone", action: "stop", at: start.addingTimeInterval(20))
check(
  try WorkoutOwnerAdmission.cancelPhoneAfterLookup(
    WorkoutSavedOwnerLookupError.notAccessible, workoutID: startRide.id, control: recoveryControl,
    nativeAbsenceConfirmed: false, effectInFlight: false) == nil,
  "missing saved history cannot cancel without a fresh native absence probe")
let cancelled = try WorkoutOwnerAdmission.cancelPhoneAfterLookup(
  WorkoutSavedOwnerLookupError.notAccessible, workoutID: startRide.id, control: recoveryControl,
  nativeAbsenceConfirmed: true, effectInFlight: false)
check(cancelled?.isTerminal == true, "confirmed native absence cancels a start with persisted timing")
check(try recoveryControl.active(workoutID: startRide.id) == nil, "cancellation releases the queued stop and start")
for key in ["checkpointUTC", "elapsedSeconds", "timerSeconds"] {
  var incomplete = saved
  incomplete.removeValue(forKey: key)
  rejects("missing checkpoint field is rejected: " + key) { _ = try WorkoutOwnerTiming.checkpoint(incomplete) }
}

let recoveredRide = try archive.create(
  startedAt: start, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: true)
var transitions: [WorkoutEvent] = []
for index in 0...1800 {
  transitions.append(
    try WorkoutEvent(
      workoutId: recoveredRide.id, kind: "lifecycle", source: "watch",
      timestamp: start.addingTimeInterval(Double(index)),
      elapsedSeconds: Double(index), payload: ["action": .string(index % 2 == 0 ? "resume" : "pause")]))
}
for offset in stride(from: 0, to: transitions.count, by: 128) {
  _ = try archive.appendBatch(Array(transitions[offset..<min(offset + 128, transitions.count)]))
}
let checkpoint = try timing(0)
for (kind, seconds) in [("telemetry", 1801.0), ("location", 1802.0)] {
  try archive.append(
    WorkoutEvent(
      workoutId: recoveredRide.id, kind: kind, source: kind == "telemetry" ? "cyc" : "watch",
      timestamp: start.addingTimeInterval(seconds), elapsedSeconds: seconds,
      payload: kind == "telemetry"
        ? ["humanPowerW": .number(100), "cadenceRpm": .number(80)]
        : ["latitude": .number(0), "longitude": .number(0), "horizontalAccuracyM": .number(3)]))
}
for representation in ["rawQuantity", "rawSeries", "builderSnapshot", "workoutAssociation"] {
  try archive.append(
    WorkoutEvent(
      workoutId: recoveredRide.id, kind: "health", source: "watch", timestamp: start.addingTimeInterval(5400),
      elapsedSeconds: 5400, payload: ["representation": .string(representation), "heartRateBpm": .number(120)]))
}
rejects("app telemetry without measured elapsed is rejected") {
  try archive.append(
    WorkoutEvent(
      workoutId: recoveredRide.id, kind: "telemetry", source: "cyc", timestamp: start.addingTimeInterval(7200),
      payload: ["humanPowerW": .number(100), "cadenceRpm": .number(80)]))
}
var scannedLifecycle = 0
var recoveryWork: [Int] = []
WorkoutOwnerTiming.recoveryWorkObserverForTesting = { recoveryWork.append($0) }
WorkoutOwnerTiming.recoveryPageObserverForTesting = { scannedLifecycle += $0 }
let recovered = try WorkoutLocalOwner.restore(
  id: recoveredRide.id, epoch: "old-watch", checkpoint: checkpoint, needsInterruption: true, archive: reopened)
WorkoutOwnerTiming.recoveryPageObserverForTesting = nil
WorkoutOwnerTiming.recoveryWorkObserverForTesting = nil
check(
  recoveryWork.count == 4 && recoveryWork[2] <= recoveryWork[1] + 100,
  "recovery page work stays bounded instead of rescanning earlier pages: \(recoveryWork)")
check(scannedLifecycle == 1800, "recovery reads only lifecycle transitions after the checkpoint across indexed pages")
check(
  try recovered.timing == timing(1802, timer: 902),
  "forward UTC jump and Health receipts cannot advance recovered elapsed or active time")
let repeated = try WorkoutLocalOwner.restore(
  id: recoveredRide.id, epoch: "old-watch", checkpoint: checkpoint, needsInterruption: true, archive: reopened)
check(repeated.timing == recovered.timing, "paged recovery stays stable after its interruption commits")

let tiedRide = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
for elapsed in [1.0, 2.0] {
  for offset in stride(from: 0, to: 1536, by: 128) {
    let events = try (offset..<offset + 128).map { index in
      try WorkoutEvent(
        workoutId: tiedRide.id, kind: "lifecycle", source: "phone",
        timestamp: start.addingTimeInterval(elapsed), elapsedSeconds: elapsed,
        payload: ["action": .string(index % 2 == 0 ? "resume" : "pause")])
    }
    _ = try archive.appendBatch(events)
  }
}
try archive.append(
  WorkoutEvent(
    workoutId: tiedRide.id, kind: "telemetry", source: "cyc",
    timestamp: start.addingTimeInterval(3), elapsedSeconds: 3,
    payload: ["humanPowerW": .number(100), "cadenceRpm": .number(80)]))
recoveryWork = []
scannedLifecycle = 0
WorkoutOwnerTiming.recoveryWorkObserverForTesting = { recoveryWork.append($0) }
WorkoutOwnerTiming.recoveryPageObserverForTesting = { scannedLifecycle += $0 }
let tiedTiming = try WorkoutOwnerTiming.retained(id: tiedRide.id, checkpoint: timing(1, timer: 1), archive: archive)
WorkoutOwnerTiming.recoveryWorkObserverForTesting = nil
WorkoutOwnerTiming.recoveryPageObserverForTesting = nil
check(
  scannedLifecycle == 1536 && tiedTiming.timerSeconds == 1,
  "checkpoint ties are excluded and cursor ties on later pages are retained")
check(
  recoveryWork.count == 4 && recoveryWork[2] <= recoveryWork[1] + 100,
  "equal-time lifecycle pages seek past the full indexed cursor: \(recoveryWork)")

let failedRide = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
_ = try control.observe(workoutID: failedRide.id, owner: "phone", phase: "running", at: start, health: "notRequested")
let frozen = try timing(12, timer: 8, utc: -30)
try WorkoutLocalOwner.interrupt(id: failedRide.id, epoch: "failed-storage", timing: frozen, archive: archive)
let delayed = try WorkoutOwnerTiming.stopping(
  command: nil, frozen: frozen, at: start.addingTimeInterval(3600), elapsed: 3612, timer: 3608)
check(delayed == frozen, "storage failure followed by delayed Finish freezes UTC, elapsed and active time")
let stop = try control.admitLocal(
  workoutID: failedRide.id, origin: "phone", action: "stop", at: start.addingTimeInterval(3600),
  options: options(delayed))
_ = try control.prepare(stop)
try archive.update(id: failedRide.id, stopElapsedSeconds: delayed.elapsedSeconds, ownerTiming: delayed)
try archive.finish(id: failedRide.id, endedAt: WorkoutCoding.date(delayed.timestamp), finalPhase: "finishing")
_ = try WorkoutPhoneSealRepair.seal(id: failedRide.id, archive: reopened, transfer: transfer, control: control)
check(
  try control.snapshot(workoutID: failedRide.id)?.timing == frozen,
  "crash after archive cutoff repairs a terminal owner receipt with exact retained timing")
check(
  try transfer.currentSeal(id: failedRide.id)?.timerSeconds == 8,
  "delayed Finish seals only the frozen active duration")
print("Owner timing regressions: \(checks) checks passed")
