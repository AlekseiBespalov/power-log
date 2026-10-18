import Foundation

func runSummaryContractFixtures() throws {
  let base = URL(
    fileURLWithPath: ProcessInfo.processInfo.environment["POWER_LOG_CONTRACT_FIXTURES"] ?? "tests/fixtures/contract")
  func cases(_ name: String) throws -> [[String: Any]] {
    (try JSONSerialization.jsonObject(
      with: Data(contentsOf: base.appendingPathComponent("analytics/" + name + ".json"))) as! [String: Any])["cases"]
      as! [[String: Any]]
  }
  func overlay(_ original: [String: Any], _ replacement: [String: Any]) -> [String: Any] {
    original.merging(replacement) { first, second in
      if let first = first as? [String: Any], let second = second as? [String: Any] { return overlay(first, second) }
      if let first = first as? [[String: Any]], let second = second as? [[String: Any]], first.count == second.count {
        return zip(first, second).map { overlay($0, $1) }
      }
      return second
    }
  }
  let deltas = try cases("approved-deltas")
  let review = try cases("review-required")
  let labels = deltas.first { $0["testId"] as? String == "source-labels" }!["expectedApproved"] as! [String: Any]
  var jobs: [(String, [String: Any], [String: Any], [String], Bool)] = []
  for row in try cases("iphone-reference") {
    let name = row["name"] as! String
    var expected = overlay(
      row["expectedSummary"] as! [String: Any], (labels["summaries"] as! [String: [String: Any]])[name]!)
    if name == "moving-gps-gap" {
      expected = overlay(
        expected,
        review.first { $0["testId"] as? String == "gps-fixed-point-summary" }!["expectedApproved"] as! [String: Any])
    }
    jobs.append((name, row["input"] as! [String: Any], expected, row["expectedAbsentSummaryFields"] as! [String], true))
  }
  for row in review {
    let name = row["testId"] as! String
    guard
      [
        "pause-stop-coincident", "paused-final-endpoint", "recovery-interruption", "checkpoint-valid",
        "checkpoint-paused", "checkpoint-changed-epoch", "admission-quantization", "fractional-partition-100.1",
        "fractional-partition-0.1",
      ].contains(name)
    else { continue }
    let input = row["input"] as! [String: Any]
    let job = input["archiveJob"] as! [String: Any]
    let approved = row["expectedApproved"] as! [String: Any]
    var expected: [String: Any]
    if let summary = approved["summary"] as? [String: Any] {
      expected = summary
    } else if let weightedMean = approved["weightedMean"], let integral = approved["integral"] {
      expected = [
        "averageRiderPowerW": weightedMean, "riderWorkJoules": integral,
        "telemetryCoveredSeconds":
          ((approved["coveredUs"] as? NSNumber)?.doubleValue ?? Double(approved["coveredUs"] as! String)!) / 1_000_000,
      ]
    } else {
      expected = approved
    }
    var projectedInput = job["input"] as! [String: Any]
    if name == "fractional-partition-0.1",
      ProcessInfo.processInfo.environment["POWER_LOG_STRESS_ARGUMENT"] != "--full"
    {
      var repeated = projectedInput["repeatedTelemetry"] as! [String: Any]
      repeated["count"] = 2_400
      projectedInput["repeatedTelemetry"] = repeated
      projectedInput["stopElapsedSeconds"] = 300
      projectedInput["endedAt"] = "2026-01-01T00:05:00.000Z"
      expected = ["averageRiderPowerW": 0.1, "riderWorkJoules": 29.9875, "telemetryCoveredSeconds": 299.875]
    }
    projectedInput["interrupt"] = job["interrupt"]
    projectedInput["afterInterruptEvents"] = job["afterInterruptEvents"]
    jobs.append((name, projectedInput, expected, approved["absentSummaryFields"] as? [String] ?? [], false))
  }
  let clipped = review.first { $0["testId"] as? String == "clipped-rational-half-even" }!
  let clippedExpected = clipped["expectedApproved"] as! [String: [String: Any]]
  for (index, job) in ((clipped["input"] as! [String: Any])["archiveJobs"] as! [[String: Any]]).enumerated() {
    jobs.append(
      (
        job["name"] as! String, job["input"] as! [String: Any],
        clippedExpected[index == 0 ? "trapezoid" : "endpoint"]!["summary"] as! [String: Any], [], false
      ))
  }
  var assertions = 0
  var failures: [String] = []
  func check(_ value: Bool, _ message: String) {
    assertions += 1
    if !value { failures.append(message) }
  }
  func compare(_ actual: Any?, _ expected: Any, _ path: String) {
    if let expected = expected as? [String: Any] {
      let actual = actual as? [String: Any] ?? [:]
      for (key, value) in expected { compare(actual[key], value, path + "/" + key) }
    } else if let expected = expected as? [Any] {
      let actual = actual as? [Any] ?? []
      check(actual.count == expected.count, path + " count")
      for (i, pair) in zip(actual, expected).enumerated() { compare(pair.0, pair.1, path + "/" + String(i)) }
    } else if let expected = expected as? NSNumber {
      let value = (actual as? NSNumber)?.doubleValue ?? (actual as? String).flatMap(Double.init)
      check(value == expected.doubleValue, "\(path): \(String(describing: value)) != \(expected)")
    } else {
      check((actual as? NSObject)?.isEqual(expected) == true, "\(path): \(String(describing: actual)) != \(expected)")
    }
  }
  for (name, input, expected, absent, full) in jobs {
    if let selected = ProcessInfo.processInfo.environment["POWER_LOG_SUMMARY_CASE"], selected != name { continue }
    let root = FileManager.default.temporaryDirectory.appendingPathComponent("summary-fixture-" + UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("store.sqlite3"))
    var archive = try WorkoutArchive(rootURL: root.appendingPathComponent("workouts"), store: store)
    let create = input["create"] as! [String: Any]
    let id = create["id"] as! String
    _ = try archive.create(
      id: id, startedAt: WorkoutCoding.date(create["startedAt"] as! String), indoor: create["indoor"] as! Bool,
      watchEnabled: create["watchEnabled"] as! Bool, saveToHealth: create["saveToHealth"] as! Bool,
      recordGPS: create["recordGPS"] as! Bool, sampleHz: 8)
    let events = input["events"] as! [[String: Any]]
    for first in stride(from: 0, to: events.count, by: 128) {
      try archive.appendBatch(
        try events[first..<min(first + 128, events.count)].map {
          try JSONDecoder().decode(WorkoutEvent.self, from: JSONSerialization.data(withJSONObject: $0))
        })
    }
    if let repeatInput = input["repeatedTelemetry"] as? [String: Any] {
      let count = repeatInput["count"] as! Int
      let startUs = repeatInput["startUs"] as! Int
      let step = repeatInput["stepUs"] as! Int
      let payload = try JSONDecoder().decode(
        [String: WorkoutJSON].self, from: JSONSerialization.data(withJSONObject: repeatInput["payload"]!))
      let date = try WorkoutCoding.date(create["startedAt"] as! String)
      for first in stride(from: 0, to: count, by: 128) {
        try archive.appendBatch(
          try (first..<min(first + 128, count)).map { index in
            let seconds = Double(startUs + index * step) / 1_000_000
            return try WorkoutEvent(
              workoutId: id, kind: "telemetry", source: "controller", timestamp: date.addingTimeInterval(seconds),
              elapsedSeconds: seconds, payload: payload, eventId: String(format: "10000000-0000-4000-8000-%012x", index)
            )
          })
      }
    }
    if let interruption = input["interrupt"] as? [String: Any] {
      try phoneFixtureInterruption(
        archive: archive, id: id, epoch: interruption["clockEpoch"] as! String,
        timing: WorkoutOwnerTiming(
          timestamp: interruption["timestamp"] as! String, elapsedSeconds: interruption["elapsedSeconds"] as! Double,
          timerSeconds: interruption["timerSeconds"] as! Double))
      try archive.appendBatch(
        try (input["afterInterruptEvents"] as! [[String: Any]]).map {
          try JSONDecoder().decode(WorkoutEvent.self, from: JSONSerialization.data(withJSONObject: $0))
        })
    }
    let metadata = input["metadataUpdate"] as? [String: Any] ?? [:]
    _ = try archive.update(
      id: id, healthKitState: metadata["healthKitState"] as? String,
      healthKitUUID: metadata["healthKitUUID"] as? String,
      stopElapsedSeconds: input["stopElapsedSeconds"] as? Double)
    _ = try archive.finish(id: id, endedAt: WorkoutCoding.date(input["endedAt"] as! String))
    archive = try WorkoutArchive(rootURL: archive.rootURL, store: store)
    let selection = input["distanceSource"] as? String ?? "auto"
    let summary = try WorkoutAnalysis.summarize(archive: archive, id: id, distanceSource: selection)
    let actual = summary.dictionary
    compare(actual, expected, name)
    for field in absent { check(actual[field] == nil, name + "/absent/" + field) }
    if full {
      check(
        Set(actual.keys).subtracting(["lifecycleCount", "batteryConsumption"]) == Set(expected.keys),
        name + " all fields and omissions")
    }
    let warm = try WorkoutAnalysis.summarize(archive: archive, id: id, distanceSource: selection)
    check(
      try WorkoutCoding.encoder().encode(summary) == WorkoutCoding.encoder().encode(warm), name + " warm equals cold")
  }
  print(
    "Swift summary contract fixtures: \(assertions - failures.count) passed, \(failures.count) failed, \(jobs.count) cases."
  )
  for failure in failures { FileHandle.standardError.write(Data((failure + "\n").utf8)) }
  if !failures.isEmpty { throw WorkoutDataError.invalid("Summary contract fixtures failed") }
  try runHealthSummaryFixtures(base)
}

private func runHealthSummaryFixtures(_ base: URL) throws {
  func load(_ path: String) throws -> [[String: Any]] {
    (try JSONSerialization.jsonObject(with: Data(contentsOf: base.appendingPathComponent(path))) as! [String: Any])[
      "cases"] as! [[String: Any]]
  }
  let root = FileManager.default.temporaryDirectory.appendingPathComponent("summary-health-" + UUID().uuidString)
  defer { try? FileManager.default.removeItem(at: root) }
  var archives: [String: WorkoutArchive] = [:]
  func storeFor(_ id: String) -> WorkoutArchive { archives[id]! }
  let start = try WorkoutCoding.date("2026-01-01T00:00:00Z")
  var assertions = 0
  func check(_ value: Bool, _ message: String) throws {
    assertions += 1
    guard value else { throw WorkoutDataError.invalid(message) }
  }
  func create(_ watch: Bool = false) throws -> String {
    let archive = try WorkoutArchive(rootURL: root.appendingPathComponent(UUID().uuidString + "/workouts"))
    let id = try archive.create(
      startedAt: start, indoor: true, watchEnabled: watch, saveToHealth: true, recordGPS: false, sampleHz: 8
    ).id
    archives[id] = archive
    return id
  }
  func finish(_ id: String, limit: Int = 256) throws -> WorkoutSummary {
    _ = try storeFor(id).update(id: id, stopElapsedSeconds: 10)
    _ = try storeFor(id).finish(id: id, endedAt: start.addingTimeInterval(10))
    return try WorkoutAnalysis.summarize(archive: storeFor(id), id: id, pageLimit: limit)
  }
  func importSnapshot(
    _ id: String, source: String = "phone", origin: String, parent: String, metric: String, representation: String,
    members: [(Double, Double, Double)], reverse: Bool = false, singleton: Bool = false
  ) throws {
    let importer = HealthSamplesFixture(archive: storeFor(id), source: source)
    let parentID = UUID(uuidString: parent) == nil ? WorkoutStableIdentity.uuid(parent) : parent
    let key = try importer.key(dataOrigin: origin, parentID: parentID)
    let inputs = try members.map { row in
      try WorkoutHealthObservation.input(
        start: start.addingTimeInterval(row.0), end: start.addingTimeInterval(row.1), metric: metric,
        unit: metric == "HKQuantityTypeIdentifierHeartRate" ? "count/min" : "kcal", value: row.2)
    }
    try importer.begin(id: id, key: key)
    let ordered = reverse ? Array(inputs.reversed()) : inputs
    if singleton {
      for member in ordered { try importer.stage(id: id, key: key, inputs: [member]) }
    } else {
      try importer.stage(id: id, key: key, inputs: ordered)
    }
    try importer.finish(
      id: id, key: key, dataOrigin: origin, parentID: parentID, metric: metric,
      originalStart: inputs.first!.originalStart, originalEnd: inputs.last!.originalEnd, count: Int64(inputs.count),
      base: ["representation": .string(representation)])
  }
  let energy = try load("analytics/approved-deltas.json").first { $0["testId"] as? String == "energy-representations" }!
  let scenarios = (energy["input"] as! [String: Any])["energyEvidence"] as! [String: [[String: Any]]]
  let expectedEnergy = energy["expectedApproved"] as! [String: Any]
  for name in scenarios.keys.sorted() {
    let id = try create(name == "owner")
    let rows = scenarios[name]!
    for row in rows {
      let origin = row["origin"] as! [String: Any]
      let metric = row["metric"] as! String
      let kind = row["kind"] as! String
      let first = try WorkoutCoding.date(
        row[kind == "intervalRecord" ? "originalStartTimestamp" : "originalTimestamp"] as! String
      ).timeIntervalSince(start)
      let last = try WorkoutCoding.date(
        row[kind == "intervalRecord" ? "originalEndTimestamp" : "originalTimestamp"] as! String
      ).timeIntervalSince(start)
      for _ in 0..<2 {
        try importSnapshot(
          id, source: row["source"] as! String, origin: origin["dataOrigin"] as! String,
          parent: origin["providerObservationId"] as! String,
          metric: metric == "active"
            ? "HKQuantityTypeIdentifierActiveEnergyBurned" : "HKQuantityTypeIdentifierBasalEnergyBurned",
          representation: kind, members: [(first, last, row["valueKcal"] as! Double)])
      }
    }
    let summary = try finish(id)
    try check(summary.activeEnergyKcal == expectedEnergy[name] as? Double, name + " selected/clipped energy")
    try check(
      summary.healthCount == rows.count && summary.eventCount == rows.count,
      name + " evidence counted once after replay")
  }
  let readback = try load("health/readback.json")
  for name in ["origin-largest-coverage", "origin-union-tie"] {
    let fixture = readback.first { $0["name"] as? String == name }!
    let rows = (fixture["input"] as! [String: Any])["records"] as! [[String: Any]]
    let expected = fixture["expected"] as! [String: Any]
    for order in [0, 1, 2] {
      let id = try create()
      for metric in ["active", "basal"] {
        for row in order == 1 ? Array(rows.reversed()) : rows {
          let first = Double(row["startUs"] as! String)! / 1_000_000
          let last = Double(row["endUs"] as! String)! / 1_000_000
          try importSnapshot(
            id, origin: row["dataOrigin"] as! String,
            parent: WorkoutStableIdentity.uuid(metric + ":" + (row["providerId"] as! String)),
            metric: metric == "active"
              ? "HKQuantityTypeIdentifierActiveEnergyBurned" : "HKQuantityTypeIdentifierBasalEnergyBurned",
            representation: "rawQuantity", members: [(first, last, row["valueKcal"] as! Double)], singleton: order == 2)
        }
      }
      let summary = try finish(id, limit: order == 2 ? 1 : 256)
      try check(summary.activeEnergyKcal == expected["valueKcal"] as? Double, name + " active origin")
      try check(summary.basalEnergyKcal == expected["valueKcal"] as? Double, name + " independent basal origin")
      try check(summary.healthCount == rows.count * 2, name + " selected counts")
      try check(
        try storeFor(id).store.read { db in
          try db.scalarInt("SELECT count(*) FROM collection_memberships WHERE collection_id=?", [.text(id)])
        } == Int64(rows.count * 2), name + " originals remain stored")
    }
  }
  for name in ["heart-rate-one-origin", "heart-rate-supported-coverage-not-parent-window"] {
    let fixture = readback.first { $0["name"] as? String == name }!
    let parents = (fixture["input"] as! [String: Any])["parents"] as! [[String: Any]]
    let expected = fixture["expected"] as! [String: Any]
    for order in [0, 1, 2] {
      let id = try create()
      var count = 0
      for parent in order == 1 ? Array(parents.reversed()) : parents {
        let origin = parent["dataOrigin"] as! String
        let members = (parent["members"] as! [[String: Any]]).map { member -> (Double, Double, Double) in
          let t = Double(member["offsetUs"] as! String)! / 1_000_000
          return (t, t, member["bpm"] as! Double)
        }
        count += members.count
        try importSnapshot(
          id, origin: origin, parent: WorkoutStableIdentity.uuid(name + ":" + origin),
          metric: "HKQuantityTypeIdentifierHeartRate", representation: "rawSeries", members: members,
          reverse: order == 1, singleton: order == 2)
      }
      let summary = try finish(id, limit: order == 2 ? 1 : 256)
      try check(summary.averageHeartRateBpm == expected["averageBpm"] as? Double, name + " supported origin mean")
      try check(summary.maximumHeartRateBpm == expected["maximumBpm"] as? Double, name + " selected origin maximum")
      try check(
        summary.heartRateCoveredSeconds == Double(expected["coveredUs"] as! String)! / 1_000_000,
        name + " supported coverage")
      try check(summary.healthCount == count, name + " excluded originals retained")
    }
  }
  for limit in [1, 256] {
    let id = try create()
    var samples: [(String, Double, Double, String)] = [
      ("app.a", 0, 60, "rawQuantity"), ("app.a", 2, 60, "rawQuantity"),
      ("app.b", 0, 100, "rawQuantity"), ("app.b", 6, 100, "rawQuantity"),
    ]
    samples += (0...10).map { ("app.a", Double($0), 180, "builderSnapshot") }
    for (index, sample) in samples.enumerated() {
      let eventID = UUID().uuidString.lowercased()
      let origin: [String: WorkoutJSON] = [
        "provider": .string("appleHealth"), "dataOrigin": .string(sample.0),
        "providerObservationId": .string(eventID), "originEventId": .string(eventID), "producer": .string("phone"),
        "sequence": .string(String(index + 1)),
      ]
      try storeFor(id).append(
        WorkoutEvent(
          workoutId: id, kind: "health", source: "phone",
          timestamp: start.addingTimeInterval(sample.1), elapsedSeconds: sample.1,
          payload: ["heartRateBpm": .number(sample.2), "representation": .string(sample.3), "origin": .object(origin)],
          eventId: eventID))
    }
    let summary = try finish(id, limit: limit)
    try check(
      summary.averageHeartRateBpm == 100 && summary.maximumHeartRateBpm == 100,
      "Core raw eligibility excludes builder coverage before origin selection")
    try check(
      summary.heartRateCoveredSeconds == 6 && summary.healthCount == samples.count,
      "Core eligibility retains all originals and selected raw support")
  }
  for limit in [1, 256] {
    let id = try create()
    for (index, row) in [
      (6.0, 10.0, 4.0, 40.0, "app.a"), (0.0, 2.0, 8.0, 20.0, "app.a"), (0.0, 5.0, 5.0, 50.0, "app.b"),
    ].enumerated() {
      let eventID = String(format: "40000000-0000-4000-8000-%012x", index)
      let origin: [String: WorkoutJSON] = [
        "provider": .string("appleHealth"), "dataOrigin": .string(row.4), "providerObservationId": .string(eventID),
        "originEventId": .string(eventID), "producer": .string("phone"), "sequence": .string(String(index + 1)),
      ]
      try storeFor(id).append(
        WorkoutEvent(
          workoutId: id, kind: "health", source: "phone", timestamp: start.addingTimeInterval(row.1),
          elapsedSeconds: row.2,
          payload: [
            "activeEnergyKcal": .number(row.3), "representation": .string("rawQuantity"), "origin": .object(origin),
            "sampleStart": .string(WorkoutCoding.timestamp(start.addingTimeInterval(row.0))),
            "sampleEnd": .string(WorkoutCoding.timestamp(start.addingTimeInterval(row.1))),
          ], eventId: eventID))
    }
    let summary = try finish(id, limit: limit)
    try check(
      summary.activeEnergyKcal == 60 && summary.healthCount == 3,
      "Origin union coverage orders mapped interval starts across clock offsets")
  }
  print("Swift Health summary fixtures: \(assertions) passed, 0 failed.")
}
