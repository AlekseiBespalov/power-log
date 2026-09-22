import Foundation
var assertions = 0
func check(_ value: @autoclosure () -> Bool, _ message: String) {
  assertions += 1
  if !value() { fatalError(message) }
}
var budget = WorkoutTransmissionBudget()
let deletionPackets: [[String: Any]] = [["messageId": "old-samples", "kind": "events"], ["messageId": "delete-request", "kind": "deleteWorkout"]]
var deletionSchedule = WorkoutTransmissionSchedule()
let deletionPriority = Set(deletionPackets.filter(WorkoutTransmissionSchedule.isPriority).compactMap { $0["messageId"] as? String })
check(deletionSchedule.select(order: ["old-samples", "delete-request"], priority: deletionPriority, now: 0) == ["delete-request", "old-samples"],
  "deletion receives a control turn without starving queued sample delivery")
check(WorkoutConnectivityDiagnostics.kind(["kind": "deleteWorkout"]) == "deleteWorkout" &&
  WorkoutConnectivityDiagnostics.kind(["kind": "deleteWorkoutAck"]) == "deleteWorkoutAck", "bounded diagnostics distinguish deletion request from its durable acknowledgement")
check(!WorkoutTransmissionSchedule.acceptsAcknowledgement(kind: "deleteWorkout", allowDeletion: false), "ordinary ACK cannot discard a deletion request")
check(WorkoutTransmissionSchedule.acceptsAcknowledgement(kind: "deleteWorkout", allowDeletion: true), "only the validated deletion receipt can release its outbox entry")
check(WorkoutTransmissionSchedule.acceptsAcknowledgement(kind: "events", allowDeletion: false), "ordinary persisted sample acknowledgement keeps its existing behavior")
// Ordinary packets have no userInfo fallback. After two priority attempts, an
// unfiltered queue otherwise selects its two undeliverable oldest packets forever.
var offlineSchedule = WorkoutTransmissionSchedule()
let offlineOrder = ["retained-events", "retained-seal", "delete-first", "delete-second"]
let offlineKinds = ["retained-events": "events", "retained-seal": "sourceSeal", "delete-first": "deleteWorkout", "delete-second": "deleteWorkout"]
let offlinePriority: Set<String> = ["delete-first", "delete-second"]
var offlineAttempts: [String: [Int]] = [:]
var selectedOfflineOrdinary = false
for tick in 0...120 {
  let unavailable = Set(offlineOrder.filter { !WorkoutTransmissionSchedule.canDispatch(kind: offlineKinds[$0]!, mirrored: false, reachable: false, background: true) })
  let selected = offlineSchedule.select(order: offlineOrder, priority: offlinePriority, unavailable: unavailable, now: Double(tick))
  selectedOfflineOrdinary = selectedOfflineOrdinary || selected.contains { !offlinePriority.contains($0) }
  for id in selected {
    offlineAttempts[id, default: []].append(tick)
    offlineSchedule.attempted(id, priority: true, now: Double(tick))
  }
}
check(!selectedOfflineOrdinary, "offline ordinary work cannot consume background deletion turns")
check(offlineAttempts["delete-first", default: []].count >= 5 && offlineAttempts["delete-second", default: []].count >= 5,
  "both background deletions keep retrying after dropped receipts and earlier attempts")
check(offlineSchedule.select(order: offlineOrder, priority: offlinePriority, now: 121) == ["retained-events", "retained-seal"],
  "ordinary source data retains its age and resumes promptly after reachability returns")
check(!WorkoutTransmissionSchedule.canDispatch(kind: "events", mirrored: false, reachable: false, background: true),
  "an installed mirror callback without an actual mirrored session cannot admit ordinary delivery")
check(WorkoutTransmissionSchedule.canDispatch(kind: "events", mirrored: true, reachable: false, background: false), "an actual mirror admits source delivery")
check(!WorkoutTransmissionSchedule.canDispatch(kind: "deleteWorkout", mirrored: false, reachable: false, background: false), "unavailable Watch connectivity does not create a false deletion attempt")
check(budget.reserve(bytes: 60_000, now: 0), "zero-origin first packet must fit")
check(budget.reserve(bytes: 30_000, now: 0.1), "exact 90 KB safety ceiling must fit")
check(!budget.reserve(bytes: 1, now: 9.999), "rolling window may not exceed ceiling")
check(budget.reserve(bytes: 60_000, now: 10), "first charge expires exactly at ten seconds")
check(!budget.reserve(bytes: 1, now: 10.05), "later packet must remain charged")
check(budget.reserve(bytes: 30_000, now: 10.1), "second charge rolls off independently")
check(!budget.reserve(bytes: 90_001, now: 30), "oversized packet cannot bypass empty window")
check(!budget.reserve(bytes: -1, now: 30), "negative length cannot mint budget")
check(!budget.reserve(bytes: 1, now: .nan), "invalid clock rejected")
check(budget.reserve(bytes: 90_000, now: 30), "invalid attempts do not consume budget")
check(!budget.reserve(bytes: 1, now: 29), "backward clock does not prematurely release budget")
check(WorkoutLaunchReadiness.shouldResendStart(kind: "status", remotePhase: "ready", localPhase: "preparing", watch: true), "ID-less launch readiness resends pending start")
check(!WorkoutLaunchReadiness.shouldResendStart(kind: "status", remotePhase: "ready", localPhase: "completed", watch: true), "late ready cannot start another ride")
check(!WorkoutLaunchReadiness.shouldResendStart(kind: "status", remotePhase: "ready", localPhase: "preparing", watch: false), "ready cannot hijack phone ownership")
extension WorkoutTransmissionSchedule {
  mutating func dispatch(order: [String], priority: Set<String>, now: Double) -> [String] {
    let selected = select(order: order, priority: priority, now: now)
    for id in selected { attempted(id, priority: priority.contains(id), now: now) }
    return selected
  }
}
var schedule = WorkoutTransmissionSchedule()
let packets = ["old-stop-a", "old-stop-b", "source-seal", "current-events", "old-events"]
let controls: Set<String> = ["old-stop-a", "old-stop-b"]
check(schedule.dispatch(order: packets, priority: controls, now: 0) == ["old-stop-a", "old-stop-b"], "initial control intent is prompt")
check(schedule.dispatch(order: packets, priority: controls, now: 0.1) == ["source-seal", "current-events"], "unacknowledged controls cannot exclude new source data")
check(schedule.dispatch(order: packets, priority: controls, now: 0.2) == ["old-events"], "unprioritized backlog receives a turn")
check(schedule.dispatch(order: packets, priority: controls, now: 0.3).isEmpty, "repeated enqueue does not busy-loop a pending message")
check(schedule.dispatch(order: packets, priority: controls, now: 3) == ["old-stop-a", "old-stop-b"], "first retry occurs only when due")
check(schedule.dispatch(order: packets, priority: controls, now: 6) == ["source-seal", "current-events"], "retry backoff frees turns for producer data")
check(schedule.dispatch(order: packets, priority: controls, now: 6.1) == ["old-events"], "old data still receives retry turns")
check(schedule.dispatch(order: packets, priority: controls, now: .nan).isEmpty, "invalid scheduling clock does not mutate state")
var continuous = WorkoutTransmissionSchedule(), seen = Set<String>()
var changing = ["source-seal", "final-data"]
for tick in 0..<10 {
  let command = "new-command-\(tick)"; changing.append(command)
  let selected = continuous.dispatch(order: changing, priority: Set(changing.filter { $0.hasPrefix("new-command") }), now: Double(tick))
  check(selected.count <= 2, "one pump has a fixed dispatch bound")
  seen.formUnion(selected)
}
check(seen.contains("source-seal") && seen.contains("final-data"), "continuous new commands cannot starve final data")
check(continuous.select(order: ["in-flight"], priority: [], unavailable: ["in-flight"], now: 100).isEmpty, "in-flight packets cannot consume selection slots")
check(continuous.select(order: ["fresh-after-ack"], priority: [], now: 100) == ["fresh-after-ack"], "removed packet retry state cannot block a new packet")
// Selection, native byte admission and no-ACK retries must be tested together.
var constrained = WorkoutTransmissionSchedule(), constrainedBudget = WorkoutTransmissionBudget()
var admissions: [String: Int] = [:]
for tick in 0..<300 {
  let now = Double(tick)
  for id in constrained.select(order: ["a", "b"], priority: [], now: now) {
    if constrainedBudget.reserve(bytes: 60_000, now: now) {
      constrained.attempted(id, priority: false, now: now)
      admissions[id, default: 0] += 1
    }
  }
}
check(admissions["a", default: 0] >= 8 && admissions["b", default: 0] >= 8,
  "budget-deferred second packet receives actual native attempts without any ACK")
var flooded = WorkoutTransmissionSchedule(), floodedBudget = WorkoutTransmissionBudget()
var floodOrder = ["retained-data"], floodPriorities = Set<String>(), dataAttempt = false
for tick in 0..<40 {
  let id = "control-\(tick)"; floodOrder.append(id); floodPriorities.insert(id)
  for selected in flooded.select(order: floodOrder, priority: floodPriorities, now: Double(tick)) {
    if floodedBudget.reserve(bytes: 60_000, now: Double(tick)) {
      flooded.attempted(selected, priority: floodPriorities.contains(selected), now: Double(tick))
      if selected == "retained-data" { dataAttempt = true }
    }
  }
}
check(dataAttempt, "continuous priority traffic plus byte pressure cannot starve retained data")
let diagnosticRoot = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-connectivity-test-" + UUID().uuidString)
try FileManager.default.createDirectory(at: diagnosticRoot, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: diagnosticRoot) }
let diagnostics = WorkoutConnectivityDiagnostics(rootURL: diagnosticRoot)
var connection = WorkoutConnectivityDiagnostics.Connection()
connection.supported = true; connection.activated = true; connection.paired = true; connection.installed = true
connection.reachable = false; connection.pendingMessages = 1
let stamp = Date(timeIntervalSince1970: 1_780_000_000)
let fakePrivatePacket: [String: Any] = ["kind": "command", "action": "start", "workoutId": "PRIVATE_WORKOUT_IDENTIFIER", "payload": ["heartRateBpm": 999, "GPS": "PRIVATE_LOCATION"], "sessionToken": "PRIVATE_TOKEN"]
check(WorkoutConnectivityDiagnostics.kind(fakePrivatePacket) == "command.start", "transport tag extracts only known kind/action")
diagnostics.record(.contextSubmitted, connection: connection, kind: WorkoutConnectivityDiagnostics.kind(fakePrivatePacket), transport: "applicationContext", now: stamp, uptime: 1)
check(diagnostics.report.lastSent?.event == .contextSubmitted && diagnostics.report.lastDelivered == nil, "queued context is not falsely reported delivered")
diagnostics.record(.sendAttempt, connection: connection, kind: "command.start", transport: "watchConnectivity", now: stamp, uptime: 2)
check(diagnostics.report.lastAcknowledged == nil, "send attempt is not an application ACK")
let nativeError = NSError(domain: "WCErrorDomain", code: 7012, userInfo: [NSLocalizedDescriptionKey: "PRIVATE_TOKEN PRIVATE_LOCATION", "identifier": "PRIVATE_WORKOUT_IDENTIFIER"])
diagnostics.record(.sendFailed, connection: connection, kind: "command.start", transport: "watchConnectivity", error: nativeError, now: stamp, uptime: 3)
check(diagnostics.report.lastNativeError?.errorDomain == "WCErrorDomain" && diagnostics.report.lastNativeError?.errorCode == 7012, "actual native error domain/code retained")
diagnostics.record(.transportDelivered, connection: connection, kind: "command.start", transport: "watchConnectivity", success: true, now: stamp, uptime: 4)
check(diagnostics.report.lastDelivered != nil && diagnostics.report.lastAcknowledged == nil, "transport reply remains distinct from durable app ACK")
diagnostics.record(.discarded, connection: connection, kind: "command.start", now: stamp, uptime: 5)
check(diagnostics.report.lastAcknowledged == nil, "discarding obsolete start cannot fabricate an acknowledgement")
diagnostics.record(.acknowledged, connection: connection, kind: "command.stop", now: stamp, uptime: 6)
check(diagnostics.report.lastAcknowledged?.kind == "command.stop", "only explicit app ACK updates acknowledgement slot")
diagnostics.record(.received, connection: connection, kind: "PRIVATE_TOKEN", transport: "PRIVATE_LOCATION", phase: "PRIVATE_WORKOUT_IDENTIFIER", now: stamp, uptime: 7)
for index in 0..<100 { diagnostics.record(.reachabilityChanged, connection: connection, now: stamp, uptime: Double(index + 8)) }
let encodedReport = try Data(contentsOf: diagnostics.url)
let diagnosticText = String(data: encodedReport, encoding: .utf8)!
check(!diagnosticText.contains("PRIVATE_TOKEN") && !diagnosticText.contains("PRIVATE_LOCATION") && !diagnosticText.contains("PRIVATE_WORKOUT_IDENTIFIER") && !diagnosticText.contains("heartRateBpm"), "diagnostic file excludes packet bodies, sensor values, IDs and NSError userInfo")
check(diagnostics.report.history.count == 64 && encodedReport.count <= 131_072, "diagnostic history and persistent JSON stay bounded")
let restoredDiagnostics = WorkoutConnectivityDiagnostics(rootURL: diagnosticRoot)
check(restoredDiagnostics.report.connection.installed && !restoredDiagnostics.report.connection.reachable, "actual connection snapshot survives process restart")
check(restoredDiagnostics.report.lastNativeError?.errorCode == 7012 && restoredDiagnostics.report.lastAcknowledged?.kind == "command.stop", "last native failure and ACK survive process restart")
let coalesced = WorkoutConnectivityDiagnostics(rootURL: diagnosticRoot)
for index in 0..<1_000 {
  let uptime = 200 + Double(index) / 100
  for kind in ["workoutChunk", "chunkAck", "sealAck"] {
    coalesced.record(.received, connection: connection, kind: kind, now: stamp, uptime: uptime)
    coalesced.record(.sendAttempt, connection: connection, kind: kind, now: stamp, uptime: uptime)
    coalesced.record(.transportDelivered, connection: connection, kind: kind, now: stamp, uptime: uptime)
  }
}
check(coalesced.persistenceCount == 2, "chunk and seal traffic coalesces nine thousand diagnostic events into two writes over ten seconds")
let beforeError = coalesced.persistenceCount
coalesced.record(.received, connection: connection, kind: "workoutChunk", error: nativeError, now: stamp, uptime: 209.995)
check(coalesced.persistenceCount == beforeError + 1, "a failure on routine chunk traffic is persisted immediately")
coalesced.record(.acknowledged, connection: connection, kind: "command.stop", now: stamp, uptime: 209.999)
let finalDiagnostics = WorkoutConnectivityDiagnostics(rootURL: diagnosticRoot)
check(finalDiagnostics.report.lastAcknowledged?.kind == "command.stop", "final control acknowledgement survives coalesced traffic")
check(finalDiagnostics.report.counts[WorkoutConnectivityDiagnostics.Event.transportDelivered.rawValue] ==
  coalesced.report.counts[WorkoutConnectivityDiagnostics.Event.transportDelivered.rawValue], "coalescing preserves aggregate transport counts")
print("Workout phone policy: \(assertions) assertions passed")
