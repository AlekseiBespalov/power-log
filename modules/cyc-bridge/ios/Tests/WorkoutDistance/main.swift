import Foundation
import CryptoKit

var assertions = 0
func check(_ condition: Bool, _ message: String) { assertions += 1; if !condition { fatalError(message) } }
func near(_ a: Double?, _ b: Double, _ message: String, tolerance: Double = 0.00001) { check(a != nil && abs(a! - b) <= tolerance, message + " \(String(describing: a)) != \(b)") }
func rejects(_ message: String, _ body: () throws -> Void) { do { try body(); check(false,message) } catch { check(true,message) } }
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-distance-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
let store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("store.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("archive"), store: store)
let service = WorkoutDistanceStore(store: store), start = Date(timeIntervalSince1970: 1_780_000_000)
var inputRows = 0, largestInputPage = 0
WorkoutDistanceStore.inputPageObserverForTesting = { count in inputRows += count; largestInputPage = max(largestInputPage,count) }
defer { WorkoutDistanceStore.inputPageObserverForTesting = nil }
func ride(indoor: Bool = false, watch: Bool = true, saves: Bool = false) throws -> String {
  try archive.create(startedAt: start, indoor: indoor, watchEnabled: watch, saveToHealth: saves).id
}
func event(_ id: String, _ kind: String, _ source: String, _ time: Double, _ payload: [String: WorkoutJSON]) throws -> WorkoutEvent {
  try WorkoutEvent(workoutId: id, kind: kind, source: source, timestamp: start.addingTimeInterval(time), elapsedSeconds: time, payload: payload)
}
func gps(_ id: String, _ time: Double, source: String = "watch", lat: Double = 0, meters: Double? = nil, accuracy: Double = 2, extra: [String: WorkoutJSON] = [:]) throws -> WorkoutEvent {
  var payload: [String: WorkoutJSON] = ["latitude": .number(lat), "longitude": .number((meters ?? time * 5) / 111195.0802335329), "horizontalAccuracyM": .number(accuracy), "clockEpoch": .string("gps-epoch")]
  payload.merge(extra) { _, value in value }; return try event(id,"location",source,time,payload)
}
func cyc(_ id: String, _ time: Double, speed: Double? = 4, extra: [String: WorkoutJSON] = [:]) throws -> WorkoutEvent {
  var payload: [String: WorkoutJSON] = ["humanPowerW": .number(100), "cadenceRpm": .number(70), "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"), "captureSessionID": .string(id), "connectionEpoch": .string("connection"), "clockEpoch": .string("epoch")]
  payload["controllerSpeedMps"] = speed.map(WorkoutJSON.number) ?? .null; payload.merge(extra) { _, value in value }
  return try event(id,"telemetry","cyc",time,payload)
}
func lifecycle(_ id: String, _ time: Double, _ action: String) throws { try archive.append(event(id,"lifecycle","phone",time,["action":.string(action)])) }
func health(_ id: String, _ a: Double, _ b: Double, _ meters: Double, uuid: String?, count: Double = 1, source: String = "watch", extra: [String: WorkoutJSON] = [:]) throws -> WorkoutEvent {
  var payload: [String: WorkoutJSON] = ["value": .number(meters), "unit": .string("m"), "healthKitIdentifier": .string("HKQuantityTypeIdentifierDistanceCycling"), "sampleUUID": .string(UUID().uuidString.lowercased()), "sampleCount": .number(count), "sampleStart": .string(WorkoutCoding.timestamp(start.addingTimeInterval(a))), "sampleEnd": .string(WorkoutCoding.timestamp(start.addingTimeInterval(b))), "representation": .string("rawQuantity")]
  if let uuid { payload["associatedWorkoutUUID"] = .string(uuid) }; payload.merge(extra) { _, value in value }
  return try event(id,"health",source,b,payload)
}
func fingerprint(_ id: String) throws -> String {
  var sha = SHA256(); try archive.forEachEvent(id: id) { sha.update(data: try WorkoutCoding.encoder().encode($0)) }
  return sha.finalize().map { String(format:"%02x",$0) }.joined()
}

// Cross-runtime shared controller corpus exercises the production pure accumulator.
let corpus = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: "tests/fixtures/distance-controller.json"))) as! [String: Any]
let defaults = corpus["defaults"] as! [String: Any]
for test in corpus["cases"] as! [[String: Any]] {
  var accumulator = WorkoutControllerDistanceAccumulator(), intervals: [WorkoutDistanceInterval] = []
  for (index, sample) in (test["samples"] as! [[String: Any]]).enumerated() {
    let values = defaults.merging(sample) { _, value in value }
    let input = WorkoutControllerDistanceSample(time: values["time"] as! Double, speed: values["speed"] as? Double, model: values["model"] as? String,
      controllerProtocol: values["protocol"] as? String, identity: values["identity"] as? String, continuity: values["connectionEpoch"] as? String,
      activeInterval: values["active"] as? Bool == true ? values["continuity"] as? Int : nil, anchor: String(index), timestamp: String(index))
    if let interval = accumulator.append(input) { intervals.append(interval) }
  }
  let name = test["name"] as! String
  near(intervals.reduce(0) { $0 + $1.meters }, test["distance"] as! Double, name)
  near(intervals.reduce(0) { $0 + $1.coveredSeconds }, test["covered"] as! Double, name + " coverage")
  check(intervals.count == test["intervals"] as! Int,name + " interval count")
  if let range = test["range"] as? [String: Double] {
    let parts = intervals.map { $0.clipped(start: range["start"]!,end: range["end"]!) }
    near(parts.reduce(0) { $0 + ($1.distanceMeters ?? 0) },range["distance"]!,name + " clipping")
  }
}

let id = try ride()
try archive.appendBatch((0...4).map { try gps(id,Double($0)) })
let originalHash = try fingerprint(id), originalCount = try store.collection(id: id).int("event_count")!
let coldStart = Date(), first = try service.snapshot(id:id), coldSeconds = Date().timeIntervalSince(coldStart)
check(first.source == "gps:watch" && first.method == "gpsGeometry", "owner GPS profile selected")
near(first.totalMeters,20,"geometry distance"); near(first.coveredSeconds,4,"geometry coverage")
let points = try service.page(snapshot:first)
check(points.count == 5 && points[0].startSeconds == points[0].endSeconds,"segment starts have explicit zero-increment anchors")
near(try service.range(snapshot:first,start:0.5,end:2.5).distanceMeters,10,"indexed clipped GPS range")
let boundPlan = try store.read { db in try db.rows("EXPLAIN QUERY PLAN " + WorkoutDistanceStore.endBoundQuery,[.text(id),.integer(first.revision)],limit:10) }
check(boundPlan.contains{$0.string("detail")?.contains("membership_time") == true} && !boundPlan.contains{$0.string("detail")?.contains("TEMP B-TREE") == true},"production end-bound query uses indexed seek without sorting admitted history")
let inputBeforeInspect = inputRows, warmStart = Date()
for _ in 0..<100 {
  check(try service.anchor(snapshot:first,identity:points[2].identity)?.identity == points[2].identity,"stable derived anchor")
  near(try service.range(snapshot:first,start:0.1,end:3.9).distanceMeters,19,"bounded indexed range")
  check(try service.cachedSnapshot(id:id,revision:first.revision)?.generation == first.generation,"cached fixed handle")
}
check(inputRows == inputBeforeInspect,"inspection and warm cache read zero original input pages")
let warmSeconds = Date().timeIntervalSince(warmStart)
try archive.append(gps(id,5)); let beforeAppend = inputRows, appendStart = Date(), appended = try service.snapshot(id:id)
check(appended.generation == first.generation,"ordinary append advances generation checkpoint")
near(appended.totalMeters,25,"append includes only accepted tail")
check(inputRows-beforeAppend == 1,"append processes one relevant input")
near(try service.page(snapshot:first).last?.distanceMeters,20,"old revision never sees appended suffix")
let appendSeconds = Date().timeIntervalSince(appendStart)
try archive.append(gps(id,2.5,meters:12.5)); let corrected = try service.snapshot(id:id)
check(corrected.generation != appended.generation,"late original rebuilds coherent generation")
near(corrected.totalMeters,25,"late geometry preserves total")
near(try service.page(snapshot:first).last?.distanceMeters,20,"prior generation stays coherent")
check(try fingerprint(id) != originalHash && store.collection(id:id).int("event_count") == originalCount+2,"only explicit appended originals changed archive")
let beforeRead = try fingerprint(id); _ = try service.snapshot(id:id,selection:"gps:phone")
check(try service.snapshot(id:id,selection:"gps:phone").totalMeters == nil,"pinned unavailable source cannot silently fall back")
check(try fingerprint(id) == beforeRead,"derived reads preserve all original payloads")

for saves in [true,false] {
  let id = try ride(saves:saves); try archive.appendBatch([gps(id,0),gps(id,1)])
  near(try service.snapshot(id:id).totalMeters,5,"Health saving does not change GPS")
}
let alternate = try ride(); try archive.appendBatch([gps(alternate,0),gps(alternate,0,source:"phone"),gps(alternate,1,source:"phone")])
check(try service.snapshot(id:alternate).source == "gps:phone","alternate GPS selected when owner has no interval")
let zero = try ride(); try archive.appendBatch([gps(zero,0,meters:0,extra:["speedMps":.number(0)]),gps(zero,1,meters:4,extra:["speedMps":.number(0)])])
near(try service.snapshot(id:zero).totalMeters,0,"stationary jitter yields a usable zero distance")
let negativeAccuracy = try ride(); try archive.appendBatch([gps(negativeAccuracy,0,extra:["speedMps":.number(0),"speedAccuracyMps":.number(-1)]),gps(negativeAccuracy,1,extra:["speedMps":.number(0),"speedAccuracyMps":.number(-1)])])
near(try service.snapshot(id:negativeAccuracy).totalMeters,5,"invalid speed accuracy cannot suppress valid GPS geometry")
let barrier = try ride(); try archive.appendBatch([gps(barrier,0),gps(barrier,1),gps(barrier,2,extra:["distanceBarrier":.bool(true)]),gps(barrier,3),gps(barrier,4)])
let broken = try service.snapshot(id:barrier); near(broken.totalMeters,10,"retained invalid-location barrier breaks continuity"); check(broken.info.selected!.partial,"gap is partial")
let paused = try ride(); try lifecycle(paused,0,"start"); try lifecycle(paused,2,"pause"); try lifecycle(paused,4,"resume"); try lifecycle(paused,6,"stop")
try archive.appendBatch((0...7).map { try gps(paused,Double($0)) }); let pauseProfile = try service.snapshot(id:paused)
near(pauseProfile.totalMeters,20,"no bridge across pause or after stop"); near(pauseProfile.coveredSeconds,4,"paused time excluded from coverage")
let indoor = try ride(indoor:true); try archive.appendBatch([gps(indoor,0),gps(indoor,1),cyc(indoor,0),cyc(indoor,1)])
check(try service.snapshot(id:indoor).source == "controller","indoor skips GPS and labels controller estimate")
near(try service.snapshot(id:indoor).totalMeters,4,"known controller interval")
check(try service.snapshot(id:indoor,selection:"gps:watch").source == "gps:watch","explicit saved source remains available")

let healthID = try ride(saves:true), uuid = UUID().uuidString.lowercased()
try archive.update(id:healthID,healthKitUUID:uuid)
try archive.append(health(healthID,0,100,1000,uuid:uuid))
let h = try service.snapshot(id:healthID)
check(h.source == "health:watch","eligible associated Health interval fallback")
near(h.totalMeters,1000,"Health amount retained whole")
let cut = try service.range(snapshot:h,start:20,end:80)
near(cut.distanceMeters,0,"Health amount cannot be allocated fractionally"); check(cut.unresolvedBoundary && cut.partial,"Health boundary explicitly unresolved")
near(try service.range(snapshot:h,start:0,end:100).distanceMeters,1000,"full Health interval retained")
try archive.append(health(healthID,50,150,1000,uuid:uuid)); let overlap = try service.snapshot(id:healthID)
check(overlap.totalMeters == nil && overlap.generation != h.generation,"late overlap rejects entire cluster via new generation")
try archive.append(health(healthID,140,160,100,uuid:uuid)); check(try service.snapshot(id:healthID).totalMeters == nil,"transitive overlap cluster remains rejected across append")
let unassociated = try ride(saves:true); try archive.update(id:unassociated,healthKitUUID:uuid)
try archive.append(health(unassociated,0,10,50,uuid:nil)); check(try service.snapshot(id:unassociated).totalMeters == nil,"time-window alone is not Health association")
let condensed = try ride(saves:true); try archive.update(id:condensed,healthKitUUID:uuid)
try archive.append(health(condensed,0,10,50,uuid:uuid,count:4)); check(try service.snapshot(id:condensed).totalMeters == nil,"condensed parent is not an interval amount")
let finalOnly = try ride(saves:true); try archive.append(event(finalOnly,"health","watch",10,["distanceMeters":.number(100),"representation":.string("finalWorkoutTotal")]))
let reported = try service.snapshot(id:finalOnly); check(reported.totalMeters == nil,"final total cannot fabricate a timeline"); near(reported.healthReportedMeters,100,"final reported total separately available")
try archive.append(event(finalOnly,"health","watch",11,["distanceMeters":.number(80),"representation":.string("finalWorkoutTotal")]))
near(try service.snapshot(id:finalOnly).healthReportedMeters,80,"downward final correction stays reported")

let restart = try ride(indoor:true,watch:false); try archive.appendBatch([cyc(restart,0),cyc(restart,1)])
let old = try service.snapshot(id:restart)
for from in stride(from:2,through:601,by:100) { try archive.appendBatch((from..<min(from+100,602)).map { try cyc(restart,Double($0)) }) }
let rebuilt = try WorkoutDistanceStore(store:store).snapshot(id:restart)
check(rebuilt.generation != old.generation,"missing 512-entry dependency history forces rebuild")
near(rebuilt.totalMeters,2404,"restart rebuild retains long controller total")
check(largestInputPage <= 128,"every original input page is bounded")

// A separate immutable association receipt can qualify originals without rewriting them.
let associationID = try ride(saves:true); try archive.update(id:associationID,healthKitUUID:uuid)
let raw = try health(associationID,0,10,50,uuid:nil)
try archive.append(raw); let unlinked = try service.snapshot(id:associationID)
check(unlinked.totalMeters == nil,"unlinked raw remains unavailable")
try archive.append(event(associationID,"health","watch",11,["representation":.string("workoutAssociation"),"sampleUUID":raw.payload["sampleUUID"]!,"associatedWorkoutUUID":.string(uuid)]))
let linked = try service.snapshot(id:associationID)
near(linked.totalMeters,50,"selected same-workout association receipt qualifies raw amount")
check(linked.generation != unlinked.generation,"association invalidates previous unavailable generation")
check(try archive.pageEvents(id:associationID).first!.event == raw,"association never mutates raw original")
let associationPlan = try store.read { db in try db.rows("EXPLAIN QUERY PLAN SELECT 1 FROM health_samples h INDEXED BY health_external CROSS JOIN observations o ON o.id=h.observation_id CROSS JOIN collection_memberships m INDEXED BY membership_observation ON m.observation_id=o.id WHERE h.external_id=? AND m.collection_id=? AND o.representation='workoutAssociation' LIMIT 1",[.text("absent"),.text(associationID)],limit:10) }
check(associationPlan.first?.string("detail")?.contains("health_external") == true,"association starts exact external-ID index, never scans ride per sample")
let seriesID = try ride(saves:true); try archive.update(id:seriesID,healthKitUUID:uuid)
try archive.appendBatch([health(seriesID,0,10,50,uuid:uuid,count:2),health(seriesID,0,5,20,uuid:uuid,extra:["representation":.string("rawSeries")]),health(seriesID,5,10,30,uuid:uuid,extra:["representation":.string("rawSeries")])])
near(try service.snapshot(id:seriesID).totalMeters,50,"series children count once while condensed parent remains ineligible")
let legacy = try ride(saves:true); try lifecycle(legacy,10,"stop"); try archive.appendBatch([gps(legacy,0),gps(legacy,10)])
try archive.update(id:legacy,stopElapsedSeconds:10); _ = try archive.finish(id:legacy,endedAt:start.addingTimeInterval(10))
try archive.append(event(legacy,"health","watch",8,["distanceMeters":.number(100)]))
near(try service.snapshot(id:legacy).healthReportedMeters,100,"legacy nonraw snapshot remains reported")
check(try service.snapshot(id:legacy).healthReportedAt == WorkoutCoding.timestamp(start.addingTimeInterval(8)),"provisional Health report exposes original qualified timestamp")
near(try service.snapshot(id:legacy).totalMeters,50,"legacy stop-only lifecycle starts at ride origin")
try archive.append(event(legacy,"health","watch",20,["distanceMeters":.number(90),"representation":.string("finalWorkoutTotal")]))
near(try service.snapshot(id:legacy).healthReportedMeters,90,"delayed final total after cutoff is reported")
let controllerBarrier = try ride(indoor:true,watch:false)
try archive.appendBatch([cyc(controllerBarrier,0),cyc(controllerBarrier,1),cyc(controllerBarrier,2,extra:["clockEpoch":.string("new-epoch")]),cyc(controllerBarrier,3,extra:["clockEpoch":.string("new-epoch")])])
near(try service.snapshot(id:controllerBarrier).totalMeters,8,"epoch reset breaks controller continuity even with same connection")
var counterAccumulator = WorkoutControllerDistanceAccumulator()
func counter(_ time:Double,_ counter:Double) -> WorkoutControllerDistanceSample { WorkoutControllerDistanceSample(time:time,speed:4,model:"X6",controllerProtocol:"5.3",identity:"bike",continuity:"connection",activeInterval:0,anchor:String(time),timestamp:String(time),counter:counter) }
_ = counterAccumulator.append(counter(0,10)); check(counterAccumulator.append(counter(1,9)) == nil,"retained counter reset breaks integration")
near(counterAccumulator.append(counter(2,10))?.meters,4,"counter reset reanchors following interval")

// A failed publication cannot expose its new suffix; restart removes and recomputes it.
let faultID = try ride(); try archive.appendBatch([gps(faultID,0),gps(faultID,1)])
let committed = try service.snapshot(id:faultID); try archive.append(gps(faultID,2))
let targetRevision = try store.collection(id:faultID).int("revision")!
store.beforeCommitForTesting = {
  let publishing = try store.read { db in try db.scalarInt("SELECT 1 FROM distance_snapshots WHERE collection_id=? AND revision=?",[.text(faultID),.integer(targetRevision)]) != nil }
  if publishing { throw WorkoutDistanceError.invalid("Injected publication failure") }
}
rejects("publication fault propagates") { _ = try service.snapshot(id:faultID) }
store.beforeCommitForTesting = nil
check(try service.cachedSnapshot(id:faultID,revision:targetRevision) == nil,"failed header is never visible")
near(try service.page(snapshot:committed).last?.distanceMeters,5,"committed handle excludes failed suffix")
near(try WorkoutDistanceStore(store:store).snapshot(id:faultID).totalMeters,10,"restart recomputes uncommitted suffix once")
let doomed = try ride(); try archive.appendBatch([gps(doomed,0),gps(doomed,1)])
var deletedDuringBuild = false
WorkoutDistanceStore.inputPageObserverForTesting = { _ in
  if !deletedDuringBuild { deletedDuringBuild = true; _ = try! store.markWorkoutDeleted(id:doomed) }
}
rejects("deletion during rebuild prevents publication") { _ = try service.snapshot(id:doomed) }
check(try store.read { try $0.scalarInt("SELECT count(*) FROM distance_snapshots WHERE collection_id=?",[.text(doomed)]) } == 0,"deleted build publishes no header")
WorkoutDistanceStore.inputPageObserverForTesting = nil
for _ in 0..<100 { let record = try store.cleanupWorkoutPage(id:doomed); if record.cleanupPhase > 0 { break } }
check(try store.read { try $0.scalarInt("SELECT count(*) FROM distance_generations WHERE collection_id=?",[.text(doomed)]) } == 0,"deletion cleanup removes derived state in bounded pages")

// Report checkpoints preserve source precedence and avoid rescanning absent final totals.
let reportID = try ride(saves:true)
try archive.append(event(reportID,"health","watch",1,["distanceMeters":.number(100),"representation":.string("cumulativeWorkoutTotal")]))
for first in stride(from:2,through:601,by:100) {
  try archive.appendBatch((first..<min(first+100,602)).map { try event(reportID,"health","watch",Double($0),["heartRateBpm":.number(110),"representation":.string("rawQuantity")]) })
}
let initialReport = try service.snapshot(id:reportID)
near(initialReport.healthReportedMeters,100,"long active ride retains latest qualified provisional report")
var reportSpans:[Int64] = []
WorkoutDistanceStore.reportRevisionObserverForTesting = { lower,upper in reportSpans.append(upper-lower) }
try archive.append(event(reportID,"health","watch",602,["heartRateBpm":.number(110),"representation":.string("rawQuantity")]))
let noFinal = try service.snapshot(id:reportID)
check(noFinal.generation == initialReport.generation && reportSpans.allSatisfy{$0==1},"absent final lookup is bounded to one newly admitted revision")
near(noFinal.healthReportedMeters,100,"absent final never clears provisional checkpoint")
try archive.append(event(reportID,"health","phone",603,["distanceMeters":.number(200),"representation":.string("cumulativeWorkoutTotal")]))
check(try service.snapshot(id:reportID).healthReportedSource == "watch","owner provisional outranks alternate provisional")
let phoneFinal = try event(reportID,"health","phone",604,["distanceMeters":.number(150),"representation":.string("finalWorkoutTotal")])
try archive.append(phoneFinal)
let alternateFinal = try service.snapshot(id:reportID)
check(alternateFinal.healthReportedSource == "phone" && !alternateFinal.healthReportedProvisional,"alternate final outranks owner provisional")
let watchFinal = try event(reportID,"health","watch",605,["distanceMeters":.number(90),"representation":.string("finalWorkoutTotal")])
try archive.append(watchFinal); near(try service.snapshot(id:reportID).healthReportedMeters,90,"owner final outranks alternate final")
try archive.append(event(reportID,"health","watch",2,["distanceMeters":.number(1000),"representation":.string("finalWorkoutTotal")]))
near(try service.snapshot(id:reportID).healthReportedMeters,90,"out-of-order final cannot replace a later retained final")
try archive.append(event(reportID,"health","watch",605,["distanceMeters":.number(75),"representation":.string("finalWorkoutTotal"),"supersedesEventId":.string(watchFinal.eventId)]))
near(try service.snapshot(id:reportID).healthReportedMeters,75,"selected replacement invalidates and reconstructs report checkpoint")
WorkoutDistanceStore.reportRevisionObserverForTesting = nil

// Adjacent Health intervals share drawing continuity, while amounts remain indivisible.
let adjacentID = try ride(saves:true); try archive.update(id:adjacentID,healthKitUUID:uuid)
try archive.appendBatch((0..<100).map { try health(adjacentID,Double($0),Double($0+1),5,uuid:uuid) })
let adjacentProfile = try service.snapshot(id:adjacentID), adjacentPoints = try service.page(snapshot:adjacentProfile)
check(adjacentPoints.count == 101 && Set(adjacentPoints.map(\.segment)).count == 1,"adjacent Health profile keeps one segment and each exact boundary")
near(try service.range(snapshot:adjacentProfile,start:0.5,end:99.5).distanceMeters,490,"shared drawing segment never permits fractional Health allocation")
check(try service.range(snapshot:adjacentProfile,start:0.5,end:99.5).unresolvedBoundary,"partial Health boundary remains explicit")
try archive.append(health(adjacentID,100,101,5,uuid:uuid))
let adjacentAppend = try service.snapshot(id:adjacentID)
check(adjacentAppend.generation == adjacentProfile.generation,"adjacent Health append remains incremental")
check(try service.page(snapshot:adjacentAppend,start:100).last?.segment == adjacentPoints.last?.segment,"checkpoint preserves adjacent Health segment")
try archive.append(health(adjacentID,103,104,5,uuid:uuid))
let withGap = try service.snapshot(id:adjacentID)
check(try service.page(snapshot:withGap,start:103).first?.segment != adjacentPoints.last?.segment,"actual missing Health interval breaks segment")
let overlapBreakID = try ride(saves:true); try archive.update(id:overlapBreakID,healthKitUUID:uuid)
try archive.appendBatch([health(overlapBreakID,0,1,5,uuid:uuid),health(overlapBreakID,1,3,10,uuid:uuid),health(overlapBreakID,2,4,10,uuid:uuid),health(overlapBreakID,4,5,5,uuid:uuid)])
let overlapBreak = try service.snapshot(id:overlapBreakID), overlapPoints = try service.page(snapshot:overlapBreak)
check(Set(overlapPoints.map(\.segment)).count == 2,"rejected overlap cluster breaks continuity between retained amounts")
let healthPauseID = try ride(saves:true); try archive.update(id:healthPauseID,healthKitUUID:uuid)
try lifecycle(healthPauseID,0,"start");try lifecycle(healthPauseID,1,"pause");try lifecycle(healthPauseID,2,"resume");try lifecycle(healthPauseID,3,"stop")
try archive.appendBatch([health(healthPauseID,0,1,5,uuid:uuid),health(healthPauseID,2,3,5,uuid:uuid)])
check(try Set(service.page(snapshot:service.snapshot(id:healthPauseID)).map(\.segment)).count == 2,"Health lifecycle pause retains a segment boundary")

print("Workout distance: \(assertions) assertions passed. cold=\(coldSeconds)s warm100=\(warmSeconds)s append=\(appendSeconds)s maxInputPage=\(largestInputPage)")
