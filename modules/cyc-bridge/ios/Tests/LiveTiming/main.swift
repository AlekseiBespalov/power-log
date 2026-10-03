import Foundation

var assertions = 0
func check(_ value: Bool, _ message: String) {
  assertions += 1
  precondition(value, message)
}
func settled<T>(_ body: () throws -> T) throws -> T {
  let deadline = ProcessInfo.processInfo.systemUptime + 10
  while true {
    do { return try body() } catch WorkoutDistanceError.pending {
      guard ProcessInfo.processInfo.systemUptime < deadline else { throw WorkoutDistanceError.pending }
      Thread.sleep(forTimeInterval: 0.005)
    }
  }
}
var freshness = WorkoutLiveFreshness()
check(!freshness.receive(id: "historical", sequence: 1, age: nil, at: 100), "Replay has no live evidence")
check(freshness.age(at: 100) == nil, "Replay cannot establish freshness")
check(freshness.receive(id: "new", sequence: 2, age: 2, at: 100), "Reported age survives transport")
check(freshness.age(at: 104) == 6, "The hold ends at six monotonic seconds")
check(!freshness.receive(id: "new", sequence: 2, age: 0, at: 104), "Duplicate cannot renew freshness")
check(freshness.age(at: 104) == 6, "Earlier receipt wins over an optimistic duplicate")
check(!freshness.receive(id: "new", sequence: 2, age: 10, at: 104), "Duplicate retains conservative evidence")
check(freshness.age(at: 104) == 10, "Older reported acquisition wins")
check(!freshness.receive(id: "old", sequence: 1, age: 0, at: 105), "Backlog cannot replace a newer sequence")
check(freshness.receive(id: "backward-utc", sequence: 3, age: 0, at: 105), "New live reading needs no UTC order")
check(!freshness.receive(id: "bad", sequence: 4, age: .nan, at: 106), "Nonfinite age is rejected")
check(!freshness.receive(id: "bad", sequence: 4, age: -1, at: 106), "Negative age is rejected")
check(WorkoutLiveFreshness.age(acquisition: 100, now: 106) == 6, "Delayed bridge emission retains acquisition age")

let bridged = WorkoutLiveFreshness.sampleEvent(["acquisitionMonotonic": 100.0])!
check(bridged["acquiredAtMonotonic"] as? Double == 100, "Bridge retains acquisition through delayed dispatch")
check(108 - (bridged["acquiredAtMonotonic"] as! Double) == 8, "Queued first delivery is already stale at consumption")
check(bridged["ageSeconds"] == nil, "Live event exposes no emission-relative age")
check(WorkoutLiveFreshness.sampleEvent([:]) == nil, "Live bridge emission requires acquisition evidence")

let fm = FileManager.default
let root = fm.temporaryDirectory.appendingPathComponent("powerlog-live-timing-\(UUID().uuidString)/PowerLog")
try fm.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? fm.removeItem(at: root.deletingLastPathComponent()) }
let store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("power-log.sqlite3"))
let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: store)
let start = Date(timeIntervalSince1970: 1_780_000_000)
var now = 100.0
var beforeReadClock: (() throws -> Void)?
let reader = MonitorDataStore(
  root: root,
  monotonicNow: {
    let action = beforeReadClock
    beforeReadClock = nil
    try! action?()
    return now
  })
let liveID = UUID().uuidString.lowercased()
reader.beginLive(startedAt: WorkoutCoding.timestamp(start), monotonic: now, id: liveID)
let liveRequest = MonitorRequest(source: "live", metrics: ["humanPowerW"])
func acquired(_ response: [String: Any], _ metric: String = "humanPowerW") -> Double? {
  (response["liveAcquiredAt"] as? [String: Any])?[metric] as? Double
}
check(
  (try reader.readLatest(liveRequest)["liveAcquiredAt"] as? [String: Any])?["humanPowerW"] is NSNull,
  "Empty live metric has null evidence")
var capture = CycCaptureClock(origin: 100, wallOrigin: start, sessionID: liveID)
now = 101
var sample = capture.observation(
  ["humanPowerW": 200, "cadenceRpm": 80], monotonic: now, wall: start.addingTimeInterval(3_600))
sample["connectionEpoch"] = "test-connection"
try reader.appendLive(sample, elapsedSeconds: 1)
now = 104
let first = try reader.readLatest(liveRequest)
check(first["nowSeconds"] as? Double == 4, "Live position uses uptime despite a forward UTC jump")
check(acquired(first) == 101, "Latest read retains acquisition on the native clock")
check(first["monotonicAt"] as? Double == 104, "Live time is paired with its native clock snapshot")
check(112 - acquired(first)! == 11, "Delayed first response cannot grant another hold")
now = 107
let repeated = try reader.readLatest(liveRequest)
check(acquired(repeated) == 101, "Reread cannot refresh the stopped stream")
check(repeated["nowSeconds"] as? Double == 7, "Domain extent cannot anchor the live clock")
sample = capture.observation(
  ["humanPowerW": 210, "cadenceRpm": 81], monotonic: now, wall: start.addingTimeInterval(-3_600))
sample["connectionEpoch"] = "test-connection"
try reader.appendLive(sample, elapsedSeconds: 7)
let jumped = try reader.readLatest(liveRequest)
check(acquired(jumped) == 107, "Backward UTC does not reject a new acquisition")
let points = jumped["points"] as! [String: Any]
check(
  (points["humanPowerW"] as? [String: Any])?["value"] as? Double == 210, "Newest observation is selected by elapsed")

let ride = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
let telemetry = try WorkoutEvent(
  workoutId: ride.id, kind: "telemetry", source: "cyc", timestamp: start.addingTimeInterval(-100),
  elapsedSeconds: 7,
  payload: [
    "humanPowerW": .number(220), "cadenceRpm": .number(82), "batteryVoltageV": .number(50),
    "clockEpoch": .string(CycCaptureClock.processEpoch), "acquisitionMonotonic": .number(107),
  ])
_ = try store.appendBatch([telemetry], liveAcquisitions: [telemetry.eventId: 107])
check(
  try WorkoutLiveFreshness.unrecorded([telemetry], archive: archive).isEmpty,
  "Stored originals are recognized across display restarts")
var restartedFreshness = WorkoutLiveFreshness()
check(
  !restartedFreshness.receive(id: telemetry.eventId, sequence: 1, age: 0, at: 110, isNew: false),
  "A packet replay after restart cannot initialize freshness")
check(restartedFreshness.age(at: 110) == nil, "Replay never makes historical readings live")
let ages = WorkoutLiveFreshness.sampleAges([telemetry.dictionary], epoch: CycCaptureClock.processEpoch, now: 110)
check(ages[telemetry.eventId] == 3, "Phone forwarding computes age when sending")
check(
  WorkoutLiveFreshness.sampleAges([telemetry.dictionary], epoch: "restarted", now: 110).isEmpty,
  "Previous-process backlog cannot acquire live evidence")
let packet = try JSONSerialization.data(withJSONObject: ["kind": "events", "events": [telemetry.dictionary]])
let forwarded =
  try JSONSerialization.jsonObject(
    with: WorkoutLiveFreshness.forwardingPacket(
      packet, epoch: CycCaptureClock.processEpoch, now: 111)) as! [String: Any]
check(
  (forwarded["sampleAges"] as? [String: Double])?[telemetry.eventId] == 4, "Transport refreshes ages from originals")
let dispatchLock = NSRecursiveLock()
let dispatchStarted = DispatchSemaphore(value: 0)
let dispatchFinished = DispatchSemaphore(value: 0)
var dispatchClock = 111.0
var mirrorAge: Double?
var connectivityAge: Double?
dispatchLock.lock()
DispatchQueue.global().async {
  dispatchStarted.signal()
  WorkoutLiveFreshness.dispatchPacket(
    packet, epoch: CycCaptureClock.processEpoch, lock: dispatchLock,
    now: { dispatchClock }
  ) { bytes in
    let sent = try! JSONSerialization.jsonObject(with: bytes) as! [String: Any]
    mirrorAge = (sent["sampleAges"] as? [String: Double])?[telemetry.eventId]
    dispatchClock += 10
  }
  WorkoutLiveFreshness.dispatchPacket(packet, epoch: CycCaptureClock.processEpoch, now: { dispatchClock }) { bytes in
    let sent = try! JSONSerialization.jsonObject(with: bytes) as! [String: Any]
    connectivityAge = (sent["sampleAges"] as? [String: Double])?[telemetry.eventId]
  }
  dispatchFinished.signal()
}
dispatchStarted.wait()
dispatchClock += 8
dispatchLock.unlock()
dispatchFinished.wait()
check(mirrorAge == 12, "Phone mirror dispatch includes blocking storage and lock delay")
check(connectivityAge == 22, "Phone connectivity dispatch recomputes age after the failed mirror call")
var fullPacket: [String: Any] = ["kind": "events", "events": [telemetry.dictionary], "padding": ""]
let packetBytes = try JSONSerialization.data(withJSONObject: fullPacket).count
fullPacket["padding"] = String(repeating: "x", count: 60_000 - packetBytes)
let fullData = try JSONSerialization.data(withJSONObject: fullPacket)
check(
  WorkoutLiveFreshness.forwardingPacket(fullData, epoch: CycCaptureClock.processEpoch, now: 111) == fullData,
  "Freshness metadata cannot block a full archival packet")
let anchor = WorkoutTimelineAnchor(
  epoch: CycCaptureClock.processEpoch, monotonicOrigin: 100,
  startedAt: WorkoutCoding.timestamp(start), stopMonotonic: nil, stopUTC: nil)
try store.transaction { db in
  try db.put(
    namespace: "phone-current", key: "workout",
    value: JSONSerialization.data(withJSONObject: [
      "id": ride.id, "timelineAnchor": WorkoutCoding.dictionary(anchor),
    ]))
}
now = 109
let rideRequest = MonitorRequest(source: "workout", id: ride.id, metrics: ["humanPowerW"])
let active = try reader.describeSource(rideRequest)
check(active["nowSeconds"] as? Double == 9, "Active ride reads use the owner anchor")
check(acquired(try reader.readLatest(rideRequest)) == 107, "Active ride acquisition evidence is local-process bound")
let historicalHeart = try WorkoutEvent(
  workoutId: ride.id, kind: "health", source: "phone", timestamp: start.addingTimeInterval(3_610),
  payload: ["heartRateBpm": .number(180), "representation": .string("rawQuantity")])
_ = try store.appendBatch([historicalHeart], liveAcquisitions: [historicalHeart.eventId: 109])
let mixedRevision = try archive.revision(id: ride.id)
check(
  try archive.metadata(id: ride.id).elapsedSeconds == 7, "UTC-placed Health cannot extend measured catalog duration")
check(
  try archive.list().first(where: { $0.id == ride.id })?.elapsedSeconds == 7, "Catalog pages exclude Health placement")
let mixedRequest = MonitorRequest(source: "workout", id: ride.id, metrics: ["humanPowerW", "heartRateBpm", "speedMps"])
let mixed = try reader.readLatest(mixedRequest)
check(mixed["nowSeconds"] as? Double == 9, "Health chart extent cannot advance the live clock")
check(
  acquired(mixed) == 107 && acquired(mixed, "heartRateBpm") == nil,
  "Historical Health has no live evidence beside fresh telemetry")
check(
  (mixed["points"] as? [String: Any])?["heartRateBpm"] is NSNull,
  "A live metric with stored history but no evidence returns a null point")
let described = try reader.describeSource(mixedRequest)
check((described["domain"] as? [String: Double])?["end"] == 3_610, "Health retains original chart placement")
check(
  described["nowSeconds"] as? Double == 9 && described["monotonicAt"] as? Double == 109,
  "Description time uses the monotonic anchor")
let gps = try WorkoutEvent(
  workoutId: ride.id, kind: "location", source: "phone", timestamp: start.addingTimeInterval(-99), elapsedSeconds: 8,
  payload: [
    "latitude": .number(0), "longitude": .number(0), "speedMps": .number(5),
    "clockEpoch": .string(CycCaptureClock.processEpoch), "acquisitionMonotonic": .number(108),
  ])
_ = try store.appendBatch([gps], liveAcquisitions: [gps.eventId: 108])
for index in 0..<300 {
  let earlier = try WorkoutEvent(
    workoutId: ride.id, kind: "health", source: "phone",
    timestamp: start.addingTimeInterval(1000 + Double(index)),
    payload: ["heartRateBpm": .number(150), "representation": .string("builderMostRecent")])
  _ = try store.appendBatch([earlier], liveAcquisitions: [earlier.eventId: 108 + Double(index) / 1000])
}
let liveHeart = try WorkoutEvent(
  workoutId: ride.id, kind: "health", source: "phone", timestamp: start.addingTimeInterval(-95),
  payload: ["heartRateBpm": .number(123), "representation": .string("builderMostRecent")])
_ = try store.appendBatch([liveHeart], liveAcquisitions: [liveHeart.eventId: 109])
now = 114
let sensors = try reader.readLatest(mixedRequest)
check(
  acquired(sensors) == 107 && acquired(sensors, "speedMps") == 108 && acquired(sensors, "heartRateBpm") == 109,
  "Independent metric acquisitions survive a stale bike stream")
check(
  ((sensors["points"] as? [String: Any])?["heartRateBpm"] as? [String: Any])?["value"] as? Double == 123,
  "Live Health reading uses its own eligible observation, not future-placed history")
_ = try store.appendBatch([liveHeart], liveAcquisitions: [liveHeart.eventId: 114])
check(
  acquired(try reader.readLatest(mixedRequest), "heartRateBpm") == 109, "Repeated live receipt cannot move the deadline"
)
let missingPower = try WorkoutEvent(
  workoutId: ride.id, kind: "telemetry", source: "cyc",
  timestamp: start, elapsedSeconds: 10, payload: ["humanPowerW": .number(221), "cadenceRpm": .number(83)])
_ = try store.appendBatch([missingPower], liveAcquisitions: [missingPower.eventId: 114])
let batteryRequest = MonitorRequest(source: "workout", id: ride.id, metrics: ["batteryVoltageV"])
check(
  acquired(try reader.readLatest(batteryRequest), "batteryVoltageV") == 107,
  "A new frame without battery voltage cannot renew the older metric evidence")
check(
  now - acquired(try reader.readLatest(batteryRequest), "batteryVoltageV")! >= 6, "Missing metric expires independently"
)
let unqueryableMetric = try WorkoutEvent(
  workoutId: ride.id, kind: "telemetry", source: "cyc", timestamp: start,
  elapsedSeconds: 10,
  payload: ["humanPowerW": .number(221), "cadenceRpm": .number(83), "batteryVoltageV": .unsigned(UInt64.max)])
_ = try store.appendBatch([unqueryableMetric], liveAcquisitions: [unqueryableMetric.eventId: 114])
let retainedBattery = try reader.readLatest(batteryRequest)
check(
  acquired(retainedBattery, "batteryVoltageV") == 107,
  "An unqueryable unsigned metric cannot renew live evidence")
check(
  ((retainedBattery["points"] as? [String: Any])?["batteryVoltageV"] as? [String: Any])?["value"] as? Double == 50,
  "An unqueryable unsigned metric cannot hide the valid evidence observation")
let failedHeart = try WorkoutEvent(
  workoutId: ride.id, kind: "health", source: "phone", timestamp: start,
  payload: ["heartRateBpm": .number(140), "representation": .string("builderMostRecent")])
store.beforeCommitForTesting = { throw PowerLogStorageError.busy }
do {
  _ = try store.appendBatch([failedHeart], liveAcquisitions: [failedHeart.eventId: 114])
  preconditionFailure("Expected injected commit failure")
} catch { assertions += 1 }
store.beforeCommitForTesting = nil
check(
  acquired(try reader.readLatest(mixedRequest), "heartRateBpm") == 109,
  "Failed admission cannot publish live evidence")
let olderArrival = try WorkoutEvent(
  workoutId: ride.id, kind: "health", source: "phone", timestamp: start.addingTimeInterval(4),
  payload: ["heartRateBpm": .number(141), "representation": .string("builderMostRecent")])
_ = try store.appendBatch([olderArrival], liveAcquisitions: [olderArrival.eventId: 108])
check(
  acquired(try reader.readLatest(mixedRequest), "heartRateBpm") == 109,
  "Delayed acquisition cannot move channel evidence backward")
for _ in 0..<4 {
  let sparse = try (0..<256).map { _ in
    try WorkoutEvent(
      workoutId: ride.id, kind: "telemetry", source: "cyc", timestamp: start, elapsedSeconds: 10,
      payload: ["humanPowerW": .number(221), "cadenceRpm": .number(83)])
  }
  _ = try store.appendBatch(sparse)
}
let queryRequest = MonitorRequest(
  source: "workout", id: ride.id,
  metrics: Array(MonitorDataStore.metrics))
_ = try settled {
  try WorkoutDistanceStore(store: store).snapshot(id: ride.id, revision: archive.revision(id: ride.id))
}
var queries: [(String, [PowerLogSQLValue])] = []
try store.read { db in db.queryObserverForTesting = { queries.append(($0, $1)) } }
_ = try reader.readLatest(queryRequest)
_ = try store.appElapsed(id: ride.id, revision: archive.revision(id: ride.id))
try store.read { db in db.queryObserverForTesting = nil }
let queryPlans = try store.read { db in
  try queries.filter { $0.0.hasPrefix("SELECT") }.map { sql, values in
    (sql, try db.rows("EXPLAIN QUERY PLAN " + sql, values, limit: 100).compactMap { $0.string("detail") })
  }
}
for (sql, plan) in queryPlans {
  check(
    !plan.contains(where: { $0.contains("SCAN m") || $0.contains("USE TEMP B-TREE") }),
    "Live reads have no full membership scan or temporary sort: \(sql): \(plan)")
}
check(
  queryPlans.contains { $0.1.contains { $0.contains("membership_app_elapsed") } },
  "Measured elapsed uses its partial index")
check(
  !queries.contains {
    $0.0.contains("health_identifier") || $0.0.contains("ORDER BY m.elapsed_seconds DESC,o.physical_id")
  },
  "Live reads issue no ordinary latest or Health channel discovery queries")
let originalQueries = queries.filter { $0.0.contains(" AS value FROM collection_memberships") }
check(originalQueries.count == 5, "Live reads look up only the five requested metrics with evidence")
check(
  originalQueries.allSatisfy { $0.0.contains("AND o.physical_id=? LIMIT 1") },
  "Every live original lookup is anchored to the selected evidence identity")
check(
  queryPlans.contains { $0.1.contains { $0.contains("membership_observation") } },
  "Live evidence uses an anchored membership lookup")

let pendingDistance = try store.read { _ -> [String: Any] in
  let previousRevision = try archive.revision(id: ride.id)
  let currentPower = try WorkoutEvent(
    workoutId: ride.id, kind: "telemetry", source: "cyc", timestamp: start, elapsedSeconds: 11,
    payload: ["humanPowerW": .number(260), "cadenceRpm": .number(83)])
  _ = try store.appendBatch([currentPower], liveAcquisitions: [currentPower.eventId: 115])
  check(try archive.revision(id: ride.id) == previousRevision + 1, "Distance deliberately lags capture by one revision")
  return try reader.readLatest(
    MonitorRequest(source: "workout", id: ride.id, metrics: ["humanPowerW", "distanceMeters"]))
}
check(pendingDistance["status"] as? String == "ok", "Pending distance does not defer live sensor readings")
check(
  ((pendingDistance["points"] as? [String: Any])?["humanPowerW"] as? [String: Any])?["value"] as? Double == 260
    && acquired(pendingDistance) == 115,
  "Current power evidence survives a lagging distance revision")
check(
  (pendingDistance["points"] as? [String: Any])?["distanceMeters"] is NSNull
    && ((pendingDistance["metricSources"] as? [String: Any])?["distanceMeters"] as? [String: Any])?["source"] as? String
      == "pending",
  "Distance exposes its own pending state")
beforeReadClock = {
  let currentPower = try WorkoutEvent(
    workoutId: ride.id, kind: "telemetry", source: "cyc", timestamp: start, elapsedSeconds: 12,
    payload: ["humanPowerW": .number(270), "cadenceRpm": .number(84)])
  _ = try store.appendBatch([currentPower], liveAcquisitions: [currentPower.eventId: 116])
}
let concurrentCapture = try reader.readLatest(rideRequest)
check(
  ((concurrentCapture["points"] as? [String: Any])?["humanPowerW"] as? [String: Any])?["value"] as? Double == 270
    && acquired(concurrentCapture) == 116,
  "Capture between description and lookup cannot hide the newest evidence")
check(
  concurrentCapture["revision"] as? String == String(try archive.revision(id: ride.id)),
  "Live sensor response publishes the revision used by its evidence lookup")

let speedRide = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
let absentSpeedRide = try archive.create(
  startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false)
let otherHealthRide = try archive.create(
  startedAt: start, indoor: true, watchEnabled: true, saveToHealth: false, recordGPS: false)
func appendSpeedHistory(_ range: Range<Int>) throws {
  for base in stride(from: range.lowerBound, to: range.upperBound, by: 256) {
    var events: [WorkoutEvent] = []
    for index in base..<min(base + 256, range.upperBound) {
      for (id, raw) in [(speedRide.id, false), (otherHealthRide.id, true)] {
        var payload: [String: WorkoutJSON] = [
          "representation": .string(raw ? "rawQuantity" : "builderMostRecent"),
          "healthKitIdentifier": .string("HKQuantityTypeIdentifierCyclingSpeed"), "unit": .string("m/s"),
        ]
        if raw {
          payload["value"] = .number(9)
          payload["sampleCount"] = .number(1)
        } else {
          payload["speedMps"] = .number(7)
        }
        events.append(
          try WorkoutEvent(
            workoutId: id, kind: "health", source: raw ? "watch" : "phone",
            timestamp: start.addingTimeInterval(Double(index)), payload: payload))
      }
    }
    _ = try store.appendBatch(events)
  }
}
func descriptionWork(_ id: String, speed: Bool) throws -> Int {
  try store.read { db in
    try store.transaction { db in
      try db.put(
        namespace: "phone-current", key: "workout",
        value: JSONSerialization.data(withJSONObject: ["id": id, "timelineAnchor": WorkoutCoding.dictionary(anchor)]))
    }
    var statements: [(String, [PowerLogSQLValue])] = []
    db.queryObserverForTesting = { statements.append(($0, $1)) }
    defer { db.queryObserverForTesting = nil }
    let result = try reader.describeSource(MonitorRequest(source: "workout", id: id))
    db.queryObserverForTesting = nil
    check(result["monotonicAt"] as? Double == now, "Work bound includes live ride presentation timing")
    check(
      (result["availableMetrics"] as? [String])?.contains("healthSpeedMps") == speed,
      "Description resolves Health speed availability from this collection")
    var steps = 0
    for (sql, values) in statements where sql.hasPrefix("SELECT") {
      _ = try db.rows(sql, values, observeWork: { steps += $0 })
    }
    return steps
  }
}
try appendSpeedHistory(0..<1_000)
let smallBuilderWork = try descriptionWork(speedRide.id, speed: true)
let smallAbsentWork = try descriptionWork(absentSpeedRide.id, speed: false)
try appendSpeedHistory(1_000..<10_000)
let largeBuilderWork = try descriptionWork(speedRide.id, speed: true)
let largeAbsentWork = try descriptionWork(absentSpeedRide.id, speed: false)
check(
  largeBuilderWork < 5_000 && largeBuilderWork <= smallBuilderWork + 500,
  "Builder-only Health speed description has bounded VM work: \(smallBuilderWork) -> \(largeBuilderWork)")
check(
  largeAbsentWork < 5_000 && largeAbsentWork <= smallAbsentWork + 500,
  "Absent Health speed does not scan other rides: \(smallAbsentWork) -> \(largeAbsentWork)")
print(
  "Description VM steps: builder \(smallBuilderWork) -> \(largeBuilderWork); absent \(smallAbsentWork) -> \(largeAbsentWork)"
)

var healthRecords = 0
var storageFailures = 0
check(
  !WorkoutLiveFreshness.admitHealth(
    duplicate: { true },
    record: {
      healthRecords += 1
      return true
    },
    storageFailed: { _ in storageFailures += 1 }), "Duplicate Health delivery is ignored")
check(healthRecords == 0 && storageFailures == 0, "Duplicate is not a storage failure")
check(
  !WorkoutLiveFreshness.admitHealth(
    duplicate: { throw PowerLogStorageError.busy },
    record: {
      healthRecords += 1
      return true
    }, storageFailed: { _ in storageFailures += 1 }),
  "Health lookup failure is not a duplicate")
check(storageFailures == 1 && healthRecords == 0, "Health lookup failure reaches storage failure handling")
check(
  WorkoutLiveFreshness.admitHealth(
    duplicate: { false },
    record: {
      healthRecords += 1
      return true
    },
    storageFailed: { _ in storageFailures += 1 }), "New Health delivery is admitted")

check(
  try archive.metadata(id: ride.id, atRevision: mixedRevision).elapsedSeconds == 7,
  "Revision metadata excludes Health placement too")
let forwardedGPS = try WorkoutEvent(
  workoutId: ride.id, kind: "location", source: "watch", timestamp: start.addingTimeInterval(3_611), elapsedSeconds: 9,
  payload: [
    "latitude": .number(0), "longitude": .number(0), "speedMps": .number(6), "clockEpoch": .string("watch-epoch"),
    "acquisitionMonotonic": .number(4),
  ])
try archive.append(forwardedGPS)
let watchPacket = try JSONSerialization.data(withJSONObject: [
  "kind": "events", "sampleAges": [forwardedGPS.eventId: 1.0],
])
let fallback =
  try JSONSerialization.jsonObject(with: WorkoutLiveFreshness.delayedPacket(watchPacket, dispatchedAt: 200, now: 210))
  as! [String: Any]
let fallbackAge = (fallback["sampleAges"] as! [String: Double])[forwardedGPS.eventId]!
check(fallbackAge == 11, "Mirroring fallback accounts for the failed attempt's full duration")

let gpsOnly = try archive.create(
  startedAt: start, indoor: false, watchEnabled: true, saveToHealth: false, recordGPS: true)
var watchGPS = forwardedGPS
watchGPS.workoutId = gpsOnly.id
_ = try store.appendBatch([watchGPS], liveAcquisitions: [watchGPS.eventId: now - fallbackAge])
try store.transaction { db in
  try db.put(
    namespace: "phone-current", key: "workout",
    value: JSONSerialization.data(withJSONObject: [
      "id": gpsOnly.id, "timelineAnchor": WorkoutCoding.dictionary(anchor),
    ]))
}
let gpsOnlyResult = try reader.readLatest(
  MonitorRequest(source: "workout", id: gpsOnly.id, metrics: ["speedMps", "humanPowerW"]))
check(
  acquired(gpsOnlyResult, "speedMps") == 103 && acquired(gpsOnlyResult) == nil,
  "Forwarded GPS has conservative phone-clock evidence without a bike")
let watchHeart = try WorkoutEvent(
  workoutId: gpsOnly.id, kind: "health", source: "watch", timestamp: start.addingTimeInterval(-30),
  payload: ["heartRateBpm": .number(124), "representation": .string("builderMostRecent")])
_ = try store.appendBatch([watchHeart], producer: "watch", liveAcquisitions: [watchHeart.eventId: 112])
let watchRead = try reader.readLatest(
  MonitorRequest(source: "workout", id: gpsOnly.id, metrics: ["speedMps", "heartRateBpm"]))
check(
  acquired(watchRead, "speedMps") == 103 && acquired(watchRead, "heartRateBpm") == 112,
  "Watch-forwarded GPS and Health retain independent evidence")
let phoneHeart = try WorkoutEvent(
  workoutId: gpsOnly.id, kind: "health", source: "phone", timestamp: start.addingTimeInterval(-40),
  payload: ["heartRateBpm": .number(128), "representation": .string("builderMostRecent")])
let phoneSpeed = try WorkoutEvent(
  workoutId: gpsOnly.id, kind: "health", source: "phone", timestamp: start.addingTimeInterval(-40),
  payload: [
    "speedMps": .number(7), "representation": .string("builderMostRecent"),
    "healthKitIdentifier": .string("HKQuantityTypeIdentifierCyclingSpeed"), "unit": .string("m/s"),
  ])
let rawSpeed = try WorkoutEvent(
  workoutId: gpsOnly.id, kind: "health", source: "watch", timestamp: start.addingTimeInterval(400),
  payload: [
    "value": .number(12), "speedMps": .number(12), "sampleCount": .number(1),
    "representation": .string("rawQuantity"), "healthKitIdentifier": .string("HKQuantityTypeIdentifierCyclingSpeed"),
    "unit": .string("m/s"),
  ])
_ = try store.appendBatch(
  [phoneHeart, phoneSpeed, rawSpeed],
  liveAcquisitions: [phoneHeart.eventId: 113, phoneSpeed.eventId: 113, rawSpeed.eventId: 114])
let candidateRequest = MonitorRequest(source: "workout", id: gpsOnly.id, metrics: ["heartRateBpm", "healthSpeedMps"])
queries.removeAll()
try store.read { db in db.queryObserverForTesting = { queries.append(($0, $1)) } }
let candidateRead = try reader.readLatest(candidateRequest)
try store.read { db in db.queryObserverForTesting = nil }
check(
  acquired(candidateRead, "heartRateBpm") == 113
    && ((candidateRead["points"] as? [String: Any])?["heartRateBpm"] as? [String: Any])?["value"] as? Double == 128,
  "Newest acquisition wins over the preferred owner and elapsed order")
check(
  acquired(candidateRead, "healthSpeedMps") == 113
    && ((candidateRead["points"] as? [String: Any])?["healthSpeedMps"] as? [String: Any])?["value"] as? Double == 7,
  "Live Health speed selects builder fallback evidence despite preferred raw history")
check(
  queries.filter { $0.0.contains(" AS value FROM collection_memberships") }.count == 2
    && !queries.contains {
      $0.0.contains("health_identifier") || $0.0.contains("ORDER BY m.elapsed_seconds DESC,o.physical_id")
    },
  "Multiple candidate channels use one identity lookup per live metric and no ordinary discovery")
for (sql, values) in queries where sql.contains(" AS value FROM collection_memberships") {
  let plan = try store.read { db in
    try db.rows("EXPLAIN QUERY PLAN " + sql, values, limit: 100).compactMap { $0.string("detail") }
  }
  check(
    plan.contains { $0.contains("membership_observation") }
      && !plan.contains { $0.contains("SCAN m") || $0.contains("USE TEMP B-TREE") },
    "Heart and Health speed evidence use bounded identity lookups: \(plan)")
}
store.liveEvidence.clear(id: gpsOnly.id)
check(
  acquired(
    try reader.readLatest(MonitorRequest(source: "workout", id: gpsOnly.id, metrics: ["heartRateBpm"])), "heartRateBpm")
    == nil,
  "Changing the ride clears its evidence")
try store.transaction { db in
  try db.put(
    namespace: "phone-current", key: "workout",
    value: JSONSerialization.data(withJSONObject: ["id": ride.id, "timelineAnchor": WorkoutCoding.dictionary(anchor)]))
}
try archive.update(id: ride.id, elapsedSeconds: 12)
check(
  try archive.list().first(where: { $0.id == ride.id })?.elapsedSeconds == 12,
  "Catalog publishes retained active elapsed")
try archive.append(
  WorkoutEvent(
    workoutId: ride.id, kind: "lifecycle", source: "phone", timestamp: start.addingTimeInterval(50_000),
    elapsedSeconds: 30, payload: ["action": .string("stop")]))
let cutoff = start.addingTimeInterval(-200)
let timing = try WorkoutOwnerTiming(timestamp: WorkoutCoding.timestamp(cutoff), elapsedSeconds: 20, timerSeconds: 20)
try archive.update(id: ride.id, stopElapsedSeconds: 20, ownerTiming: timing)
try archive.finish(id: ride.id, endedAt: cutoff)
let listed = try archive.list().first(where: { $0.id == ride.id })!
check(listed.elapsedSeconds == 20, "Terminal catalog elapsed ignores UTC and conflicting lifecycle stop")
check(listed.dictionary["elapsedSeconds"] as? Double == 20, "Catalog bridge includes required elapsed")
var malformed = listed.dictionary
malformed.removeValue(forKey: "elapsedSeconds")
let malformedData = try JSONSerialization.data(withJSONObject: malformed)
check(
  (try? JSONDecoder().decode(WorkoutMetadata.self, from: malformedData)) == nil,
  "Catalog elapsed is required in storage")
let summary = try settled { try WorkoutAnalysis.summarize(archive: archive, id: ride.id) }
check(summary.elapsedSeconds == 20, "FIT summary prefers retained cutoff over a conflicting stop event")
check(summary.endedAt == WorkoutCoding.timestamp(cutoff), "Summary retains the original backward cutoff UTC")
let completed = try reader.readLatest(rideRequest)
check(
  completed["liveAcquiredAt"] == nil && completed["monotonicAt"] == nil && completed["nowSeconds"] == nil,
  "Completed rereads carry no live clock or evidence")
let savedHeart = try reader.readLatest(MonitorRequest(source: "workout", id: ride.id, metrics: ["heartRateBpm"]))
check(
  ((savedHeart["points"] as? [String: Any])?["heartRateBpm"] as? [String: Any])?["value"] as? Double == 180,
  "A saved ride still returns its last historical value without live evidence")
let distance = try settled {
  try WorkoutDistanceStore(store: store).snapshot(id: ride.id, revision: archive.revision(id: ride.id))
}
check(distance.endSeconds == 20, "Distance cutoff is independent of end UTC")

let indexedRide = try archive.create(
  startedAt: start, indoor: false, watchEnabled: false, saveToHealth: false, recordGPS: true)
let indexedRequest = MonitorRequest(source: "workout", id: indexedRide.id, metrics: ["speedMps", "heartRateBpm"])
let validFix = try WorkoutEvent(
  workoutId: indexedRide.id, kind: "location", source: "phone", timestamp: start,
  elapsedSeconds: 1, payload: ["latitude": .number(0), "longitude": .number(0), "speedMps": .number(5)])
let invalidFix = try WorkoutEvent(
  workoutId: indexedRide.id, kind: "location", source: "phone", timestamp: start,
  elapsedSeconds: 2,
  payload: ["latitude": .number(0), "longitude": .number(0), "speedMps": .number(9), "speedAccuracyMps": .number(-1)])
try archive.append(validFix)
try archive.append(invalidFix)
func reading(_ request: MonitorRequest, _ metric: String) throws -> [String: Any]? {
  (try reader.readLatest(request)["points"] as? [String: Any])?[metric] as? [String: Any]
}
check(try reading(indexedRequest, "speedMps")?["value"] as? Double == 5, "Saved latest skips invalid metric values")
let correction = try WorkoutEvent(
  workoutId: indexedRide.id, kind: "location", source: "phone", timestamp: start,
  elapsedSeconds: 0.5,
  payload: [
    "latitude": .number(0), "longitude": .number(0), "speedMps": .number(4),
    "supersedesEventId": .string(validFix.eventId),
  ])
try archive.append(correction)
check(try reading(indexedRequest, "speedMps")?["value"] as? Double == 4, "Saved latest follows selected corrections")
let externalID = UUID().uuidString.lowercased()
let rawHeart = try WorkoutEvent(
  workoutId: indexedRide.id, kind: "health", source: "phone", timestamp: start,
  payload: ["heartRateBpm": .number(130), "representation": .string("rawQuantity"), "sampleUUID": .string(externalID)])
try archive.append(rawHeart)
let deletedHeart = try WorkoutEvent(
  workoutId: indexedRide.id, kind: "health", source: "phone", timestamp: start,
  payload: ["deleted": .bool(true), "sampleUUID": .string(externalID)])
try archive.append(deletedHeart)
check(try reading(indexedRequest, "heartRateBpm") == nil, "Saved latest honors Health deletion fences")
let lateHeart = try WorkoutEvent(
  workoutId: indexedRide.id, kind: "health", source: "phone", timestamp: start.addingTimeInterval(1),
  payload: ["heartRateBpm": .number(135), "representation": .string("rawSeries"), "sampleUUID": .string(externalID)])
try archive.append(lateHeart)
check(try reading(indexedRequest, "heartRateBpm") == nil, "Late series cannot bypass an existing deletion fence")

let restartRoot = root.appendingPathComponent("restart")
let restartID = UUID().uuidString.lowercased()
func seedRestart() throws {
  let firstReader = MonitorDataStore(root: restartRoot, monotonicNow: { 500 })
  firstReader.beginLive(startedAt: WorkoutCoding.timestamp(start), monotonic: 500, id: restartID)
  var firstCapture = CycCaptureClock(origin: 500, wallOrigin: start, sessionID: restartID)
  try firstReader.appendLive(
    firstCapture.observation(["humanPowerW": 201, "cadenceRpm": 80], monotonic: 501, wall: start), elapsedSeconds: 1)
  check(acquired(try firstReader.readLatest(liveRequest)) == 501, "First process records live evidence")
}
try seedRestart()
let reopenedReader = MonitorDataStore(root: restartRoot, monotonicNow: { 510 })
reopenedReader.beginLive(startedAt: WorkoutCoding.timestamp(start), monotonic: 500, id: restartID)
let reopenedReading = try reopenedReader.readLatest(liveRequest)
check(acquired(reopenedReading) == nil, "A new evidence map after restart has no live evidence")
check(
  (reopenedReading["points"] as? [String: Any])?["humanPowerW"] is NSNull,
  "Restarted live reads return no point without process-local evidence")

let provisional = try archive.create(
  startedAt: start, indoor: true, watchEnabled: true, saveToHealth: true, recordGPS: false)
try archive.update(id: provisional.id, phase: "preparing")
let health = try WorkoutEvent(
  workoutId: provisional.id, kind: "health", source: "watch", timestamp: start.addingTimeInterval(5),
  payload: ["heartRateBpm": .number(123), "representation": .string("rawQuantity")])
try archive.append(health)
try archive.confirmStart(id: provisional.id, startedAt: start.addingTimeInterval(100))
try archive.update(id: provisional.id, stopElapsedSeconds: 10)
try archive.finish(id: provisional.id, endedAt: start.addingTimeInterval(110))
let healthSummary = try settled { try WorkoutAnalysis.summarize(archive: archive, id: provisional.id) }
check(healthSummary.maximumHeartRateBpm == 123, "FIT retains admitted Health placement after start confirmation")
check(
  try reader.readLatest(MonitorRequest(source: "workout", id: provisional.id, metrics: ["heartRateBpm"]))[
    "liveAcquiredAt"] == nil,
  "Historical Health backfill never creates live evidence")
for maximumAge in [6.0, 15.0] {
  for age in [Double.nan, .infinity, -1, maximumAge, maximumAge + 1] {
    check(
      !PowerLogRideAttributes.ContentState.isFresh(age, maximumAge: maximumAge),
      "Activity age limit is strict and finite")
  }
  check(
    !PowerLogRideAttributes.ContentState.isFresh(nil, maximumAge: maximumAge), "Missing Activity age is unavailable")
  check(PowerLogRideAttributes.ContentState.isFresh(0, maximumAge: maximumAge), "New Activity observation is fresh")
  check(
    PowerLogRideAttributes.ContentState.isFresh(maximumAge - 0.001, maximumAge: maximumAge),
    "Activity retains values until the deadline")
}
for utcShift in [-3600.0, 3600.0] {
  var activity = PowerLogRideAttributes.ContentState(
    phase: "running", timerSeconds: 10, observedAt: start.addingTimeInterval(utcShift),
    observedUptime: ProcessInfo.processInfo.systemUptime - 7,
    bikeSampleAgeSeconds: 0, heartSampleAgeSeconds: 9, riderPowerW: 200, heartRateBpm: 120,
    controlToken: UUID().uuidString)
  check(activity.isBikeUnavailable, "Activity bike freshness expires through either UTC jump")
  check(
    !PowerLogRideAttributes.ContentState.isFresh(activity.heartAge, maximumAge: 15),
    "Heart freshness includes monotonic delivery time")
  activity.observedUptime = ProcessInfo.processInfo.systemUptime
  activity.heartSampleAgeSeconds = 0
  check(!activity.isBikeUnavailable, "Fresh bike evidence ignores the original UTC offset")
  check(
    PowerLogRideAttributes.ContentState.isFresh(activity.heartAge, maximumAge: 15),
    "Fresh heart evidence ignores the original UTC offset")
}
print("Native live timing: \(assertions) assertions passed")
