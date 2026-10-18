import Foundation

func runSummaryReviewTests() throws {
  let root = FileManager.default.temporaryDirectory.appendingPathComponent("summary-review-" + UUID().uuidString)
  defer { try? FileManager.default.removeItem(at: root) }
  let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"))
  let start = try WorkoutCoding.date("2026-01-01T00:00:00Z")
  var assertions = 0
  func expect(_ value: Bool, _ message: String) {
    assertions += 1
    if !value { fatalError(message) }
  }
  func selected(_ name: String) -> Bool {
    ProcessInfo.processInfo.environment["POWER_LOG_SUMMARY_REVERIFY_CASE"].map { $0 == name } ?? true
  }
  func create() throws -> String {
    try archive.create(
      startedAt: start, indoor: true, watchEnabled: false, saveToHealth: false, recordGPS: false, sampleHz: 8
    ).id
  }
  func finish(_ id: String, _ seconds: Double) throws {
    _ = try archive.update(id: id, stopElapsedSeconds: seconds)
    _ = try archive.finish(id: id, endedAt: start.addingTimeInterval(seconds))
  }
  if selected("heart") {
    for limit in [1, 256] {
      let id = try create()
      let samples: [(String, Double, Double?)] = [
        ("app.a", 0, 100), ("app.a", 5, nil), ("app.a", 10, 100), ("app.b", 0, 150), ("app.b", 8, 150),
      ]
      for (index, sample) in samples.enumerated() {
        let eventID = UUID().uuidString.lowercased()
        var payload: [String: WorkoutJSON] = [
          "representation": .string("rawQuantity"), "declaredMetrics": .array([.string("heartRateBpm")]),
          "origin": .object([
            "provider": .string("appleHealth"), "dataOrigin": .string(sample.0),
            "providerObservationId": .string(eventID), "originEventId": .string(eventID),
            "producer": .string("phone"), "sequence": .string(String(index + 1)),
          ]),
        ]
        if let value = sample.2 { payload["heartRateBpm"] = .number(value) }
        try archive.append(
          WorkoutEvent(
            workoutId: id, kind: "health", source: "phone", timestamp: start.addingTimeInterval(sample.1),
            elapsedSeconds: sample.1, payload: payload, eventId: eventID))
      }
      try finish(id, 10)
      let summary = try WorkoutAnalysis.summarize(archive: archive, id: id, pageLimit: limit)
      expect(
        summary.averageHeartRateBpm == 150 && summary.maximumHeartRateBpm == 150
          && summary.heartRateCoveredSeconds == 8,
        "Missing selected HR barrier reaches origin scoring and selects B with 150 bpm and eight seconds")
      expect(summary.healthCount == 5 && summary.eventCount == 5, "Missing HR original remains counted")
    }
  }
  if selected("ties") {
    let id = try create()
    let count = 4096
    for first in stride(from: 0, to: count, by: 128) {
      try archive.appendBatch(
        try (first..<min(first + 128, count)).map { i in
          try WorkoutEvent(
            workoutId: id, kind: "health", source: i.isMultiple(of: 2) ? "phone" : "watch",
            timestamp: start, elapsedSeconds: 0,
            payload: ["heartRateBpm": .number(100), "representation": .string("rawQuantity")])
        })
    }
    for (time, action) in [(0.0, "pause"), (0.0, "resume"), (2.0, "lap")] {
      try archive.append(
        WorkoutEvent(
          workoutId: id, kind: "lifecycle", source: "phone", timestamp: start.addingTimeInterval(time),
          elapsedSeconds: time, payload: ["action": .string(action)]))
    }
    for time in [0.0, 1.0, 2.0] {
      try archive.append(
        WorkoutEvent(
          workoutId: id, kind: "telemetry", source: "controller", timestamp: start.addingTimeInterval(time),
          elapsedSeconds: time, payload: ["humanPowerW": .number(100), "cadenceRpm": .number(80)]))
    }
    try finish(id, 3)
    var steps: [Int] = []
    var plans: [[String]] = []
    WorkoutAnalysis.queryObserverForTesting = { channel, plan, work, rows in
      if channel == "original" && rows > 0 {
        steps.append(work)
        plans.append(plan)
      }
    }
    defer { WorkoutAnalysis.queryObserverForTesting = nil }
    let summary = try WorkoutAnalysis.summarize(archive: archive, id: id, pageLimit: 64)
    WorkoutAnalysis.queryObserverForTesting = nil
    print("Original tie pages: \(steps.count), maximum VM steps: \(steps.max() ?? 0)")
    expect(
      steps.count >= 128 && steps.max()! < 20000, "Original continuation stays below 20000 VM steps per 64-row tie page"
    )
    expect(
      plans.allSatisfy {
        !$0.contains { $0.contains("TEMP B-TREE") }
          && $0.contains { $0.contains("SEARCH m USING INDEX membership_summary_time") }
      },
      "Original tie pages seek the index without temporary sorting")
    expect(
      summary.eventCount == count + 6 && summary.healthCount == count,
      "Tie continuation retains every selected identity")
    expect(
      summary.lifecycleCount == 3 && summary.timerSeconds == 3 && summary.riderWorkJoules == 200
        && summary.telemetryCount == 3 && summary.lapCount == 2,
      "Lifecycle rank and producer/sequence tie edges retain their summary")
    let singleton = try WorkoutAnalysis.summarize(archive: archive, id: id, distanceSource: "controller", pageLimit: 1)
    expect(
      singleton.eventCount == summary.eventCount && singleton.riderWorkJoules == summary.riderWorkJoules
        && singleton.timerSeconds == summary.timerSeconds && singleton.lapCount == summary.lapCount,
      "One-row tie edges produce the same counts, timing and work")
    let output = URL(fileURLWithPath: "test-results/as-reverify/original-query-plan.json")
    try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
    try JSONSerialization.data(
      withJSONObject: ["maximumSteps": steps.max()!, "steps": steps, "plans": plans],
      options: [.prettyPrinted, .sortedKeys]
    ).write(to: output)
  }
  if selected("delivery") {
    for superseded in [false, true] {
      let reads = WorkoutSummaryReads()
      let removed = DispatchSemaphore(value: 0)
      let gate = DispatchSemaphore(value: 0)
      let done = DispatchSemaphore(value: 0)
      var oldSuccess = false
      var oldDeliveries = 0
      var cancellationRejected = false
      reads.beforeDeliveryForTesting = { id in
        if id == "old" {
          removed.signal()
          gate.wait()
        }
      }
      reads.submit(id: "old", row: "history", work: { _ in ["generation": 1] }) { result in
        oldDeliveries += 1
        if case .success = result { oldSuccess = true }
        if case .failure(let error) = result {
          cancellationRejected = (error as? WorkoutAnalysisError)?.bridgeCode == "ERR_READ_CANCELLED"
        }
      }
      expect(removed.wait(timeout: .now() + 10) == .success, "Delivery seam follows completed work")
      if superseded {
        reads.submit(id: "new", row: "history", work: { _ in ["generation": 2] }) { result in
          expect((try? result.get()["generation"] as? Int) == 2, "New row request receives its own result")
          done.signal()
        }
        expect(done.wait(timeout: .now() + 10) == .success, "New row settles while old delivery is held")
      } else {
        reads.cancel("old")
      }
      gate.signal()
      reads.drain()
      expect(!oldSuccess, "Cancellation or supersession between job removal and delivery suppresses old success")
      expect(
        superseded ? oldDeliveries == 0 : (oldDeliveries == 1 && cancellationRejected),
        "Delivery accepts only the current request generation and rejects its cancellation")
    }
  }
  if selected("commit") {
    for nested in [false, true] {
      let id = try create()
      for time in [0.0, 1.0] {
        try archive.append(
          WorkoutEvent(
            workoutId: id, kind: "telemetry", source: "controller", timestamp: start.addingTimeInterval(time),
            elapsedSeconds: time, payload: ["humanPowerW": .number(100), "cadenceRpm": .number(80)]))
      }
      try finish(id, 2)
      let token = WorkoutSummaryCancellation()
      var intercepted = false
      archive.store.beforeCommitForTesting = {
        let inserted = try archive.store.read { db in
          try db.scalarInt("SELECT count(*) FROM distance_snapshots WHERE collection_id=?", [.text(id)])!
        }
        if inserted > 0 {
          intercepted = true
          token.cancel()
        }
      }
      do {
        if nested {
          let distance = WorkoutDistanceStore(
            store: archive.store, checkCancellation: token.check, publishCheckpoint: token.publish)
          _ = try archive.store.transaction { _ in try distance.snapshot(id: id) }
        } else {
          _ = try WorkoutAnalysis.summarize(archive: archive, id: id, cancellation: token)
        }
        fatalError("Expected checkpoint cancellation")
      } catch {
        expect(
          (error as? WorkoutAnalysisError)?.bridgeCode == "ERR_READ_CANCELLED",
          "Commit-boundary cancellation rejects summary")
      }
      archive.store.beforeCommitForTesting = nil
      let retained = try archive.store.read { db in
        try db.scalarInt("SELECT count(*) FROM distance_snapshots WHERE collection_id=?", [.text(id)])!
      }
      let advanced = try archive.store.read { db in
        try db.scalarInt(
          "SELECT count(*) FROM distance_generations WHERE collection_id=? AND revision>=?",
          [.text(id), .integer(try archive.revision(id: id))])!
      }
      expect(advanced == 0, "Cancelled checkpoint also rolls back the generation revision")
      expect(
        intercepted && retained == 0, "Cancellation after checkpoint INSERTs and before COMMIT rolls back the snapshot")
      let recovered = try WorkoutAnalysis.summarize(archive: archive, id: id)
      expect(recovered.riderWorkJoules == 100, "A cancelled checkpoint can be rebuilt from retained originals")
    }
  }

  if selected("warming") {
    let id = try create()
    for time in [0.0, 1.0] {
      try archive.append(
        WorkoutEvent(
          workoutId: id, kind: "telemetry", source: "controller", timestamp: start.addingTimeInterval(time),
          elapsedSeconds: time, payload: ["humanPowerW": .number(100), "cadenceRpm": .number(80)]))
    }
    try finish(id, 2)
    let timing = try WorkoutOwnerTiming(
      timestamp: WorkoutCoding.timestamp(start.addingTimeInterval(2)), elapsedSeconds: 2, timerSeconds: 2)
    _ = try archive.update(id: id, ownerTiming: timing)
    let reads = WorkoutSummaryReads()
    let warmStarted = DispatchSemaphore(value: 0)
    let gate = DispatchSemaphore(value: 0)
    let done = DispatchSemaphore(value: 0)
    let lock = NSLock()
    var pages = 0
    var blocked = false
    WorkoutAnalysis.pageObserverForTesting = { _, _ in
      lock.lock()
      let first = !blocked
      blocked = true
      lock.unlock()
      if first {
        warmStarted.signal()
        gate.wait()
      }
    }
    WorkoutAnalysis.queryObserverForTesting = { channel, _, _, rows in
      if channel == "original" && rows > 0 { pages += 1 }
    }
    try reads.warm(archive: archive, record: archive.metadata(id: id))
    expect(warmStarted.wait(timeout: .now() + 10) == .success, "Completed ride warming starts promptly")
    try reads.read(archive: archive, record: archive.metadata(id: id), id: "history", row: "history") { result in
      expect((try? result.get()["eventCount"] as? String) == "2", "First History receives the local completed summary")
      done.signal()
    }
    gate.signal()
    expect(done.wait(timeout: .now() + 30) == .success, "History joins the engine finalization continuation")
    reads.drain()
    WorkoutAnalysis.pageObserverForTesting = nil
    WorkoutAnalysis.queryObserverForTesting = nil
    expect(pages == 2, "Engine finalization and first History execute one cold preparation and composition")
  }
  print("Swift summary re-verification tests: \(assertions) passed, 0 failed.")
}
