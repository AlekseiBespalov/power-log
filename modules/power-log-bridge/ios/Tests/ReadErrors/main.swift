import Foundation
import PowerLogCore
import Darwin

setbuf(stdout, nil)

let cases: [(Error, String?)] = [
  (MonitorError.admission("plot"), "ERR_READ_DEFERRED"),
  (MonitorError.cacheContention("statistics"), "ERR_READ_CONTENTION"),
  (PowerLogStorageError.busy, "ERR_STORAGE_BUSY"),
  (PowerLogStorageError.deleted("ride"), "ERR_RIDE_DELETED"),
  (MonitorError.invalid("Invalid monitor request."), nil),
  (PowerLogStorageError.invalid("Invalid storage request."), nil),
]
var failures = 0
for (error, expected) in cases {
  do {
    throw error
  } catch {
    let actual = readErrorCode(error)
    guard actual == expected else {
      failures += 1
      print("Bridge rejection expected \(expected ?? "uncoded"), received \(actual ?? "uncoded")")
      continue
    }
  }
}
print("Swift read error bridge tests: \(cases.count - failures) passed, \(failures) failed.")
if failures > 0 { exit(1) }

try runSummaryReviewTests()
if ProcessInfo.processInfo.environment["POWER_LOG_SUMMARY_REVERIFY_ONLY"] == "1" { exit(0) }

var readAssertions = 0
do {
  let root = FileManager.default.temporaryDirectory.appendingPathComponent("summary-reads-" + UUID().uuidString)
  defer { try? FileManager.default.removeItem(at: root) }
  let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"))
  let start = try WorkoutCoding.date("2026-01-01T00:00:00Z")
  func expect(_ condition: Bool, _ message: String) {
    readAssertions += 1
    if !condition { fatalError(message) }
  }
  func ride(_ count: Int = 2, connectionEpoch: String? = nil) throws -> String {
    let id = try archive.create(
      startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8
    ).id
    for first in stride(from: 0, to: count, by: 128) {
      try archive.appendBatch(
        try (first..<min(first + 128, count)).map { i in
          var payload: [String: WorkoutJSON] = ["humanPowerW": .number(100), "cadenceRpm": .number(80)]
          if let connectionEpoch { payload["connectionEpoch"] = .string(connectionEpoch) }
          return try WorkoutEvent(
            workoutId: id, kind: "telemetry", source: "controller", timestamp: start.addingTimeInterval(Double(i)),
            elapsedSeconds: Double(i), payload: payload)
        })
    }
    _ = try archive.update(id: id, stopElapsedSeconds: Double(count))
    _ = try archive.finish(id: id, endedAt: start.addingTimeInterval(Double(count)))
    return id
  }
  func cached(_ id: String, _ revision: Int64) throws -> Bool {
    FileManager.default.fileExists(
      atPath: try archive.directory(id: id).appendingPathComponent("summary-r\(revision)-dauto.json").path)
  }
  func rejection(_ code: String, _ action: () throws -> Void) {
    do {
      try action()
      fatalError("Expected \(code)")
    } catch {
      let actual = (error as? WorkoutAnalysisError)?.bridgeCode ?? readErrorCode(error)
      expect(actual == code, "Expected \(code), got \(String(describing: actual))")
    }
  }
  func lifecycleBoundTest() throws {
    let before = readAssertions
    let lifecycleBound = try ride()
    for first in stride(from: 0, to: 10_001, by: 128) {
      try archive.appendBatch(
        try (first..<min(first + 128, 10_001)).map { index in
          try WorkoutEvent(
            workoutId: lifecycleBound, kind: "lifecycle", source: "phone",
            timestamp: start.addingTimeInterval(Double(index)), elapsedSeconds: Double(index),
            payload: ["action": .string("pause")])
        })
    }
    rejection("ERR_INVALID_RECORD") { _ = try WorkoutAnalysis.summarize(archive: archive, id: lifecycleBound) }
    expect(
      try archive.metadata(id: lifecycleBound).eventCount == 10_003
        && !cached(lifecycleBound, archive.revision(id: lifecycleBound)),
      "Lifecycle overflow preserves originals without a summary cache")
    print("Swift summary lifecycle bound: \(readAssertions - before) passed, 0 failed.")
  }
  if ProcessInfo.processInfo.environment["POWER_LOG_SUMMARY_READ_CASE"] == "lifecycle" {
    try lifecycleBoundTest()
    exit(0)
  }

  let pinned = try ride()
  let revision = try archive.revision(id: pinned)
  var appended = false
  let old = try WorkoutAnalysis.summarize(
    archive: archive, id: pinned,
    onPage: { pass, _ in
      if pass == .prepare && !appended {
        appended = true
        try archive.append(
          WorkoutEvent(
            workoutId: pinned, kind: "telemetry", source: "controller", timestamp: start.addingTimeInterval(2),
            elapsedSeconds: 2, payload: ["humanPowerW": .number(100), "cadenceRpm": .number(80)]))
      }
    })
  expect(old.riderWorkJoules == 100 && old.eventCount == 2, "Append cannot alter admitted pin")
  expect(try !cached(pinned, revision), "Old pin cannot publish cache")
  let latest = try WorkoutAnalysis.summarize(archive: archive, id: pinned)
  expect(latest.riderWorkJoules == 200 && latest.eventCount == 3, "Latest pin includes append")

  let cancelled = try ride(300)
  let cancelledRevision = try archive.revision(id: cancelled)
  let token = WorkoutSummaryCancellation()
  var pages = 0
  rejection("ERR_READ_CANCELLED") {
    _ = try WorkoutAnalysis.summarize(
      archive: archive, id: cancelled, cancellation: token,
      onPage: { pass, _ in
        if pass == .compose {
          pages += 1
          token.cancel()
        }
      })
  }
  expect(try pages == 1 && !cached(cancelled, cancelledRevision), "Cancellation stops pages and publication")
  let alreadyCancelled = WorkoutSummaryCancellation()
  alreadyCancelled.cancel()
  rejection("ERR_READ_CANCELLED") {
    _ = try WorkoutAnalysis.summarize(archive: archive, id: pinned, cancellation: alreadyCancelled)
  }

  let deleted = try ride(300)
  rejection("ERR_RIDE_DELETED") {
    _ = try WorkoutAnalysis.summarize(
      archive: archive, id: deleted,
      onPage: { pass, page in
        if pass == .compose && page == 0 { _ = try archive.store.markWorkoutDeleted(id: deleted) }
      })
  }
  let overflow = try ride()
  try archive.append(
    WorkoutEvent(
      workoutId: overflow, kind: "health", source: "phone", timestamp: start, elapsedSeconds: 0,
      payload: ["activeEnergyKcal": .number(1e13), "representation": .string("finalWorkoutTotal")]))
  rejection("ERR_NUMERIC_OVERFLOW") { _ = try WorkoutAnalysis.summarize(archive: archive, id: overflow) }
  expect(
    try archive.metadata(id: overflow).eventCount == 3 && !cached(overflow, archive.revision(id: overflow)),
    "Overflow preserves originals without cache")

  let malformed = try ride()
  _ = try archive.store.transaction { db in
    try db.execute(
      "UPDATE observations SET extra=? WHERE id=(SELECT observation_id FROM collection_memberships WHERE collection_id=? LIMIT 1)",
      [.blob(Data("malformed".utf8)), .text(malformed)])
  }
  do {
    _ = try WorkoutAnalysis.summarize(archive: archive, id: malformed)
    fatalError("Expected original read failure")
  } catch { readAssertions += 1 }
  expect(
    try archive.metadata(id: malformed).eventCount == 2 && !cached(malformed, archive.revision(id: malformed)),
    "Unreadable original remains visible and uncached")

  let reads = WorkoutSummaryReads()
  let malformedShape = try ride()
  _ = try archive.store.transaction { db in
    try db.execute(
      "UPDATE observations SET extra=? WHERE id=(SELECT observation_id FROM collection_memberships WHERE collection_id=? LIMIT 1)",
      [.blob(Data("[]".utf8)), .text(malformedShape)])
  }
  rejection("ERR_INVALID_RECORD") { _ = try WorkoutAnalysis.summarize(archive: archive, id: malformedShape) }
  expect(
    try !cached(malformedShape, archive.revision(id: malformedShape)),
    "Non-object original extension cannot publish a summary")
  var maximumProjections = 0
  var maximumBytes = 0
  WorkoutAnalysis.pageObserverForTesting = { count, bytes in
    maximumProjections = max(maximumProjections, count)
    maximumBytes = max(maximumBytes, bytes)
  }
  let multiMetric = try ride()
  for first in stride(from: 0, to: 300, by: 100) {
    try archive.appendBatch(
      try (first..<first + 100).map { i in
        try WorkoutEvent(
          workoutId: multiMetric, kind: "health", source: "phone", timestamp: start, elapsedSeconds: 0,
          payload: ["activeEnergyKcal": .number(Double(i)), "basalEnergyKcal": .number(Double(i))])
      })
  }
  let multiSummary = try WorkoutAnalysis.summarize(archive: archive, id: multiMetric)
  WorkoutAnalysis.pageObserverForTesting = nil
  expect(
    maximumProjections <= 256 && maximumBytes <= 524_288,
    "Every observation and energy projection counts against page bounds")
  expect(
    multiSummary.healthCount == 300 && multiSummary.eventCount == 302,
    "Multiplexed energy projections count their originals once")
  let sparseEnergy = try ride()
  for first in stride(from: 0, to: 1000, by: 128) {
    try archive.appendBatch(
      try (first..<min(first + 128, 1000)).map { _ in
        try WorkoutEvent(
          workoutId: sparseEnergy, kind: "health", source: "phone", timestamp: start, elapsedSeconds: 0,
          payload: ["heartRateBpm": .number(60), "representation": .string("rawQuantity")])
      })
  }
  try archive.append(
    WorkoutEvent(
      workoutId: sparseEnergy, kind: "health", source: "phone", timestamp: start, elapsedSeconds: 0,
      payload: [
        "activeEnergyKcal": .number(100), "basalEnergyKcal": .number(50),
        "representation": .string("finalWorkoutTotal"),
      ]))
  var prepareStage = 0
  var energyPages = 0
  let sparseSummary = try WorkoutAnalysis.summarize(
    archive: archive, id: sparseEnergy,
    onPage: { pass, page in
      if pass == .prepare {
        if page == 0 { prepareStage += 1 }
        if prepareStage == 3 { energyPages += 1 }
      }
    })
  expect(energyPages == 1, "Energy origin pages exclude unrelated heart-rate originals")
  expect(
    sparseSummary.healthCount == 1001 && sparseSummary.eventCount == 1003
      && sparseSummary.activeEnergyKcal == 100 && sparseSummary.basalEnergyKcal == 50,
    "Sparse energy query preserves all original counts and selected totals")
  let intervalsRide = try ride(3000)
  for first in stride(from: 0, to: 3000, by: 128) {
    try archive.appendBatch(
      try (first..<min(first + 128, 3000)).map { i in
        let time = Double(2999 - i)
        return try WorkoutEvent(
          workoutId: intervalsRide, kind: "health", source: "phone",
          timestamp: start.addingTimeInterval(time + 1), elapsedSeconds: time + 1,
          payload: [
            "activeEnergyKcal": .number(1), "representation": .string("intervalRecord"),
            "sampleStart": .string(WorkoutCoding.timestamp(start.addingTimeInterval(time))),
            "sampleEnd": .string(WorkoutCoding.timestamp(start.addingTimeInterval(time + 1))),
          ])
      })
  }
  var energyWork: [Int] = []
  var energyPlans: [[String]] = []
  var heartQueries = 0
  WorkoutAnalysis.queryObserverForTesting = { channel, plan, steps, rows in
    if channel == "energy" && rows > 0 {
      energyWork.append(steps)
      energyPlans.append(plan)
    }
    if channel == "heart" { heartQueries += 1 }
  }
  let intervalSummary = try WorkoutAnalysis.summarize(archive: archive, id: intervalsRide, pageLimit: 64)
  WorkoutAnalysis.queryObserverForTesting = nil
  expect(intervalSummary.activeEnergyKcal == 3000, "Thousands of mapped intervals retain selected energy")
  expect(
    energyWork.count >= 47 && energyWork.count < 100 && energyWork.max()! < 20000,
    "Mapped energy page work is bounded independently of ride length")
  expect(
    energyPlans.allSatisfy { plan in
      !plan.contains { $0.contains("TEMP B-TREE") } && plan.contains { $0.contains("SEARCH s USING PRIMARY KEY") }
    }, "Mapped energy query plan uses index seeks without temporary sorting")
  expect(heartQueries == 0, "Absent heart-rate channel does not schedule an origin reader")
  let planData: [String: Any] = [
    "pages": energyWork.count, "maximumSteps": energyWork.max()!, "firstPagePlan": energyPlans.first!,
    "nextPagePlan": energyPlans[1], "steps": energyWork,
  ]
  try FileManager.default.createDirectory(atPath: "test-results/as-review", withIntermediateDirectories: true)
  try JSONSerialization.data(withJSONObject: planData, options: [.prettyPrinted, .sortedKeys]).write(
    to: URL(fileURLWithPath: "test-results/as-review/energy-query-plan.json"))

  let byteRide = try ride(400, connectionEpoch: String(repeating: "epoch", count: 800))
  var largestHydrated = 0
  var bytePages = 0
  WorkoutAnalysis.queryObserverForTesting = { channel, _, _, rows in
    if channel == "original" && rows > 0 {
      largestHydrated = max(largestHydrated, rows)
      bytePages += 1
    }
  }
  let byteSummary = try WorkoutAnalysis.summarize(archive: archive, id: byteRide)
  WorkoutAnalysis.queryObserverForTesting = nil
  expect(
    byteSummary.eventCount == 400 && largestHydrated < 32 && bytePages > 25,
    "Byte bound stops hydration while retaining all original counts")

  let completed = try ride()
  let warmStarted = DispatchSemaphore(value: 0)
  let warmGate = DispatchSemaphore(value: 0)
  let historyDone = DispatchSemaphore(value: 0)
  let cancelledHistoryDone = DispatchSemaphore(value: 0)
  let warmLock = NSLock()
  var warmBlocked = false
  var originalPages = 0
  WorkoutAnalysis.pageObserverForTesting = { _, _ in
    warmLock.lock()
    let first = !warmBlocked
    warmBlocked = true
    warmLock.unlock()
    if first {
      warmStarted.signal()
      warmGate.wait()
    }
  }
  WorkoutAnalysis.queryObserverForTesting = { channel, _, _, rows in
    if channel == "original" && rows > 0 { originalPages += 1 }
  }
  try reads.handoff(archive: archive, id: completed) {
    try! reads.read(archive: archive, record: archive.metadata(id: completed), id: "history", row: "history") {
      result in
      expect((try? result.get()["eventCount"] as? String) == "2", "History receives warmed production summary")
      historyDone.signal()
    }
  }
  try reads.read(
    archive: archive, record: archive.metadata(id: completed), id: "cancelled-history", row: "cancelled-history"
  ) { result in
    if case .failure(let error) = result {
      expect(
        (error as? WorkoutAnalysisError)?.bridgeCode == "ERR_READ_CANCELLED",
        "Joined History cancellation settles independently")
    } else {
      fatalError("Cancelled History subscriber returned a summary")
    }
    cancelledHistoryDone.signal()
  }
  reads.cancel("cancelled-history")
  expect(warmStarted.wait(timeout: .now() + 10) == .success, "Finalization starts warming before History handoff")
  expect(historyDone.wait(timeout: .now() + 0.1) == .timedOut, "Immediate History joins in-flight warming")
  warmGate.signal()
  expect(historyDone.wait(timeout: .now() + 10) == .success, "Joined History settles after warm work")
  expect(cancelledHistoryDone.wait(timeout: .now() + 10) == .success, "Cancelling joined History preserves warm work")
  reads.drain()
  WorkoutAnalysis.pageObserverForTesting = nil
  WorkoutAnalysis.queryObserverForTesting = nil
  expect(originalPages == 2, "Finalization handoff and first History share one cold summary")

  let longRide = try archive.create(
    startedAt: start, indoor: true, watchEnabled: false,
    saveToHealth: false, recordGPS: false, sampleHz: 8
  ).id
  let cancellationSamples =
    ProcessInfo.processInfo.environment["POWER_LOG_STRESS_ARGUMENT"] == "--full" ? 230400 : 4096
  let cancellationSeconds = Double(cancellationSamples) / 8
  for first in stride(from: 0, to: cancellationSamples, by: 512) {
    try archive.appendBatch(
      try (first..<min(first + 512, cancellationSamples)).map { i in
        try WorkoutEvent(
          workoutId: longRide, kind: "telemetry", source: "controller",
          timestamp: start.addingTimeInterval(Double(i) / 8), elapsedSeconds: Double(i) / 8,
          payload: ["humanPowerW": .number(100), "cadenceRpm": .number(80)])
      })
  }
  _ = try archive.update(id: longRide, stopElapsedSeconds: cancellationSeconds)
  _ = try archive.finish(id: longRide, endedAt: start.addingTimeInterval(cancellationSeconds))
  let cancelReads = WorkoutSummaryReads()
  let distanceDone = DispatchSemaphore(value: 0)
  var distancePages = 0
  var cancelledAt = 0.0
  WorkoutDistanceStore.jobObserverForTesting = { job in
    if job.name == "controller" && job.examined > 0 {
      distancePages += 1
      if distancePages == 1 {
        cancelledAt = ProcessInfo.processInfo.systemUptime
        cancelReads.cancel("distance-cold")
      }
    }
  }
  cancelReads.submit(
    id: "distance-cold", row: "distance-cold",
    work: { token in
      try WorkoutAnalysis.summarize(archive: archive, id: longRide, cancellation: token).dictionary
    },
    completion: { result in
      expect(
        { () -> String? in
          if case .failure(let error) = result { return (error as? WorkoutAnalysisError)?.bridgeCode }
          return nil
        }() == "ERR_READ_CANCELLED", "Distance cancellation reaches summary rejection")
      distanceDone.signal()
    })
  expect(distanceDone.wait(timeout: .now() + 60) == .success, "Cancelled cold build frees summary slot")
  expect(
    distancePages == 1 && ProcessInfo.processInfo.systemUptime - cancelledAt < 1,
    "Distance cancellation checks before the next build page")
  cancelReads.drain()
  WorkoutDistanceStore.jobObserverForTesting = nil
  let published = try archive.store.read { db in
    try db.scalarInt("SELECT count(*) FROM distance_snapshots WHERE collection_id=?", [.text(longRide)])!
  }
  expect(published == 0, "Cancelled distance build does not publish a checkpoint")

  let missingBounds = try ride()
  try archive.append(
    WorkoutEvent(
      workoutId: missingBounds, kind: "health", source: "phone", timestamp: start, elapsedSeconds: 0,
      payload: ["activeEnergyKcal": .number(10), "representation": .string("rawQuantity")]))
  rejection("ERR_INVALID_RECORD") { _ = try WorkoutAnalysis.summarize(archive: archive, id: missingBounds) }
  expect(
    try !cached(missingBounds, archive.revision(id: missingBounds)),
    "Malformed interval cannot become cumulative energy")

  let gate = DispatchSemaphore(value: 0)
  let started = DispatchSemaphore(value: 0)
  let finished = DispatchSemaphore(value: 0)
  let stateLock = NSLock()
  var active = 0
  var maximum = 0
  for i in 0..<3 {
    reads.submit(
      id: "read-\(i)", row: "row-\(i)",
      work: { token in
        stateLock.lock()
        active += 1
        maximum = max(maximum, active)
        stateLock.unlock()
        started.signal()
        gate.wait()
        defer {
          stateLock.lock()
          active -= 1
          stateLock.unlock()
        }
        return try WorkoutAnalysis.summarize(archive: archive, id: completed, cancellation: token).dictionary
      }, completion: { _ in finished.signal() })
  }
  expect(
    started.wait(timeout: .now() + 10) == .success && started.wait(timeout: .now() + 10) == .success,
    "Two summary slots execute")
  reads.cancel("read-0")
  expect(started.wait(timeout: .now() + 0.1) == .timedOut, "Cancellation retains slot until execution drains")
  gate.signal()
  gate.signal()
  expect(started.wait(timeout: .now() + 10) == .success, "Pending summary starts after drain")
  gate.signal()
  for _ in 0..<3 { expect(finished.wait(timeout: .now() + 10) == .success, "Every read settles") }
  reads.drain()
  expect(maximum == 2, "Summary concurrency is bounded to two")
  print("Swift summary read/store tests: \(readAssertions) passed, 0 failed.")

  let fixtureRoot = URL(
    fileURLWithPath: ProcessInfo.processInfo.environment["POWER_LOG_CONTRACT_FIXTURES"] ?? "tests/fixtures/contract")
  let transitions =
    (try JSONSerialization.jsonObject(
      with: Data(contentsOf: fixtureRoot.appendingPathComponent("analytics/review-required.json"))) as! [String: Any])[
      "cases"] as! [[String: Any]]
  func compareSummary(_ actual: Any?, _ expected: Any, _ name: String) {
    if let expected = expected as? [String: Any] {
      let actual = actual as? [String: Any] ?? [:]
      for (key, value) in expected { compareSummary(actual[key], value, name + "/" + key) }
    } else if let expected = expected as? NSNumber {
      expect((actual as? NSNumber)?.doubleValue == expected.doubleValue, name)
    } else {
      expect((actual as? NSObject)?.isEqual(expected) == true, name)
    }
  }
  let transitionStart = readAssertions
  for row in transitions
  where ["append-between-passes", "correction-between-pages", "deletion-between-pages", "metadata-only-finality"]
    .contains(row["testId"] as! String)
  {
    let name = row["testId"] as! String
    let input = row["input"] as! [String: Any]
    let job = input["archiveJob"] as! [String: Any]
    let setup = job["input"] as! [String: Any]
    let create = setup["create"] as! [String: Any]
    let id = create["id"] as! String
    _ = try archive.create(
      id: id, startedAt: WorkoutCoding.date(create["startedAt"] as! String), indoor: create["indoor"] as! Bool,
      watchEnabled: create["watchEnabled"] as! Bool, saveToHealth: create["saveToHealth"] as! Bool,
      recordGPS: create["recordGPS"] as! Bool, sampleHz: 8)
    try archive.appendBatch(
      try (setup["events"] as! [[String: Any]]).map {
        try JSONDecoder().decode(WorkoutEvent.self, from: JSONSerialization.data(withJSONObject: $0))
      })
    let metadata = setup["metadataUpdate"] as! [String: Any]
    _ = try archive.update(
      id: id, healthKitState: metadata["healthKitState"] as? String,
      stopElapsedSeconds: setup["stopElapsedSeconds"] as? Double)
    _ = try archive.finish(id: id, endedAt: WorkoutCoding.date(setup["endedAt"] as! String))
    let revision = try archive.revision(id: id)
    var changed = false
    let pinned = try WorkoutAnalysis.summarize(
      archive: archive, id: id, pageLimit: input["pageLimit"] as! Int,
      onPage: { pass, index in
        let boundary: AnalysisPass = name == "append-between-passes" ? .prepare : .compose
        if pass == boundary && index == 0 && !changed {
          changed = true
          for mutation in job["mutations"] as! [[String: Any]] {
            if let event = mutation["event"] as? [String: Any] {
              try archive.append(
                JSONDecoder().decode(WorkoutEvent.self, from: JSONSerialization.data(withJSONObject: event)))
            }
            if let metadata = mutation["metadataUpdate"] as? [String: Any] {
              _ = try archive.update(
                id: id, healthKitState: metadata["healthKitState"] as? String)
            }
          }
        }
      })
    let expected = row["expectedApproved"] as! [String: Any]
    let state = (expected["states"] as! [[String: Any]])[0]
    compareSummary(pinned.dictionary, state["pinned"]!, name + "/pinned")
    expect(try !cached(id, revision), name + "/no stale cache")
    compareSummary(
      try WorkoutAnalysis.summarize(archive: archive, id: id, pageLimit: 1).dictionary, state["latest"]!,
      name + "/latest"
    )
  }
  print("Swift summary transition fixtures: \(readAssertions - transitionStart) passed, 0 failed.")

  try lifecycleBoundTest()

} catch {
  print("Summary read failed after \(readAssertions) assertions: \(error)")
  exit(1)
}
