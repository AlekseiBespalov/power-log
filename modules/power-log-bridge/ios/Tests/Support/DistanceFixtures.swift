import Foundation
import PowerLogCore

func runDistanceContractFixtures() throws {
  try runDistanceReviewFixtures()
  var assertions = 0
  func check(_ value: Bool, _ message: String) {
    assertions += 1
    if !value { FileHandle.standardError.write(Data((message + "\n").utf8)) }
    precondition(value, message)
  }
  func equal(_ value: Double?, _ expected: Double?, _ name: String) {
    check(value == expected, "\(name): \(String(describing: value)) != \(String(describing: expected))")
  }
  let base = URL(
    fileURLWithPath: ProcessInfo.processInfo.environment["POWER_LOG_CONTRACT_FIXTURES"] ?? "tests/fixtures/contract")
  func cases(_ name: String) throws -> [[String: Any]] {
    let data = try Data(contentsOf: base.appendingPathComponent("analytics/" + name + ".json"))
    return (try JSONSerialization.jsonObject(with: data) as! [String: Any])["cases"] as! [[String: Any]]
  }
  let reference = try cases("iphone-reference")
  let review = try cases("review-required")
  let moving = reference.first { $0["name"] as? String == "moving-gps-gap" }!
  let approved =
    review.first { $0["testId"] as? String == "gps-fixed-point-summary" }!["expectedApproved"] as! [String: Any]
  var jobs: [(String, [String: Any], [String: Any], [Int]?, Bool)] = [
    ("moving-gps-gap", moving["input"] as! [String: Any], approved, nil, false)
  ]
  for row in review {
    let name = row["testId"] as! String
    guard ["controller-valid", "explicit-unavailable"].contains(name) else { continue }
    let input = row["input"] as! [String: Any]
    let expected = row["expectedApproved"] as! [String: Any]
    if let job = input["archiveJob"] as? [String: Any] {
      jobs.append(
        (name, job["input"] as! [String: Any], expected, job["partitions"] as? [Int], job["reopen"] as? Bool ?? false))
    }
    for job in input["archiveJobs"] as? [[String: Any]] ?? [] {
      let name = job["name"] as! String
      jobs.append(
        (
          name, job["input"] as! [String: Any], expected[name] as! [String: Any], job["partitions"] as? [Int],
          job["reopen"] as? Bool ?? false
        ))
    }
  }
  for (name, input, expected, partitions, reopen) in jobs {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent("distance-contract-" + UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    var store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("store.sqlite3"))
    var archive = try WorkoutArchive(rootURL: root.appendingPathComponent("archive"), store: store)
    let create = input["create"] as! [String: Any]
    let id = create["id"] as! String
    _ = try archive.create(
      id: id, startedAt: WorkoutCoding.date(create["startedAt"] as! String), indoor: create["indoor"] as! Bool,
      watchEnabled: create["watchEnabled"] as! Bool, saveToHealth: create["saveToHealth"] as! Bool,
      recordGPS: create["recordGPS"] as! Bool, sampleHz: 4)
    let events = try (input["events"] as! [[String: Any]]).map {
      try JSONDecoder().decode(WorkoutEvent.self, from: JSONSerialization.data(withJSONObject: $0))
    }
    let metadata = input["metadataUpdate"] as? [String: Any] ?? [:]
    try archive.update(
      id: id, healthKitUUID: metadata["healthKitUUID"] as? String,
      stopElapsedSeconds: input["stopElapsedSeconds"] as? Double)
    let sizes = partitions ?? [events.count]
    check(sizes.reduce(0, +) == events.count, name + " partitions cover all originals")
    var offset = 0
    for (index, size) in sizes.enumerated() {
      for first in stride(from: offset, to: offset + size, by: 128) {
        try archive.appendBatch(Array(events[first..<min(first + 128, offset + size)]))
      }
      offset += size
      _ = try WorkoutDistanceStore(store: store).snapshot(
        id: id, selection: input["distanceSource"] as? String ?? "auto")
      if reopen && index + 1 < sizes.count {
        PowerLogStore.evictSharedForTesting(databaseURL: store.databaseURL)
        let previous = store
        store = try PowerLogStore.shared(databaseURL: previous.databaseURL)
        check(store !== previous, name + " partition opens a new store connection")
        archive = try WorkoutArchive(rootURL: root.appendingPathComponent("archive"), store: store)
      }
    }
    let service = WorkoutDistanceStore(store: store)
    let snapshot = try service.snapshot(id: id, selection: input["distanceSource"] as? String ?? "auto")
    if let distance = expected["distance"] as? [String: Any] {
      let selected = distance["selected"] as? [String: Any]
      equal(snapshot.totalMeters, selected?["distanceMeters"] as? Double, name + " fixed-point total")
      check(snapshot.source == selected?["source"] as? String, name + " selected source")
      let available = distance["available"] as! [[String: Any]]
      check(
        snapshot.sources.map(\.source) == available.map { $0["source"] as! String },
        name + " ordered available profiles")
      for (profile, actual) in zip(available, snapshot.sources) {
        equal(actual.distanceMeters, profile["distanceMeters"] as? Double, name + " profile meters")
        equal(actual.coveredSeconds, profile["coveredSeconds"] as? Double, name + " profile coverage")
        check(
          actual.partial == profile["partial"] as! Bool && actual.label == profile["label"] as! String,
          name + " profile policy")
      }
    }
    if let statistics = expected["statistics"] as? [[String: Any]], let queries = input["queries"] as? [[String: Any]] {
      for (query, result) in zip(queries, statistics) {
        let range = try service.range(
          snapshot: snapshot, start: query["startSeconds"] as! Double, end: query["endSeconds"] as! Double)
        if let distance = result["distanceMeters"] as? [String: Any] {
          equal(range.distanceMeters, distance["distance"] as? Double, name + " clip")
        }
      }
    }
    for point in try service.page(snapshot: snapshot) {
      let exact = try service.anchor(snapshot: snapshot, identity: point.identity)
      check(exact?.pointID == point.pointID && exact?.timestamp == point.timestamp, name + " exact stored anchor")
    }
  }
  let apple =
    try JSONSerialization.jsonObject(
      with: Data(contentsOf: URL(fileURLWithPath: "tests/fixtures/contract/analytics/apple-distance.json")))
    as! [String: Any]
  var appleCases = apple["cases"] as! [[String: Any]]
  let partial = review.first { $0["testId"] as? String == "distance-partial-microsecond" }!
  let partialInput = partial["input"] as! [String: Any]
  let partialExpected = partial["expectedApproved"] as! [String: Any]
  for (index, covered) in (partialInput["coveredUs"] as! [Int64]).enumerated() {
    var samples: [[String: Any]] = (0...9).map { ["time": Double($0), "speed": 0.0] }
    samples.append(["time": WorkoutDistancePolicy.seconds(covered), "speed": 0.0])
    appleCases.append([
      "id": "distance-partial-microsecond-\(covered)", "source": "controller", "samples": samples,
      "stopElapsedSeconds": WorkoutDistancePolicy.seconds(partialInput["timerUs"] as! Int64),
      "distanceQ": "0", "coveredUs": String(covered),
      "partial": (partialExpected["partial"] as! [Bool])[index],
    ])
  }
  for row in appleCases {
    let name = row["id"] as! String
    let source = row["source"] as! String
    let samples = row["samples"] as! [[String: Any]]
    let producer = source
    for incremental in [false, true] {
      let root = FileManager.default.temporaryDirectory.appendingPathComponent(
        "distance-checkpoint-" + UUID().uuidString)
      defer { try? FileManager.default.removeItem(at: root) }
      var store = try PowerLogStore.shared(databaseURL: root.appendingPathComponent("store.sqlite3"))
      var archive = try WorkoutArchive(rootURL: root.appendingPathComponent("archive"), store: store)
      let start = try WorkoutCoding.date("2026-01-01T00:00:00Z")
      let id = try archive.create(
        startedAt: start, indoor: source == "controller", watchEnabled: false, saveToHealth: false,
        recordGPS: source != "controller", sampleHz: 4
      ).id
      try archive.update(id: id, stopElapsedSeconds: row["stopElapsedSeconds"] as? Double)
      var snapshot: WorkoutDistanceSnapshot?
      for (index, sample) in samples.enumerated() {
        let time = sample["time"] as! Double
        var payload: [String: WorkoutJSON] = ["clockEpoch": .string(sample["epoch"] as? String ?? "run")]
        if source == "controller" {
          payload.merge([
            "humanPowerW": .number(100), "cadenceRpm": .number(80), "controllerModel": .string("X6"),
            "controllerProtocol": .string("5.3"), "captureSessionID": .string(id), "connectionEpoch": .string("link"),
            "controllerSpeedMps": .number(sample["speed"] as! Double),
          ]) { _, new in new }
          if let counter = sample["counter"] as? String { payload["observationSequence"] = .string(counter) }
        } else {
          payload.merge([
            "latitude": .number(0), "longitude": .number(sample["longitude"] as! Double),
            "horizontalAccuracyM": .number(2),
          ]) { _, new in new }
          if let altitude = sample["altitude"] as? Double {
            payload.merge(
              WorkoutAltitudeCapture.apple(altitude: altitude, verticalAccuracy: sample["verticalAccuracy"] as! Double)
            ) { _, new in new }
          }
        }
        var event = try WorkoutEvent(
          workoutId: id, kind: source == "controller" ? "telemetry" : "location", source: producer,
          timestamp: start.addingTimeInterval(time), elapsedSeconds: time, payload: payload)
        if let original = sample["timestamp"] as? String {
          var encoded = try JSONSerialization.jsonObject(with: JSONEncoder().encode(event)) as! [String: Any]
          encoded["timestamp"] = original
          encoded.removeValue(forKey: "elapsedSeconds")
          event = try JSONDecoder().decode(WorkoutEvent.self, from: JSONSerialization.data(withJSONObject: encoded))
        }
        try archive.appendBatch([event], producer: producer, firstSequence: Int64(index + 1))
        if incremental {
          let old = snapshot
          let revision = try store.collection(id: id).int("revision")!
          store.beforeCommitForTesting = {
            let publishing = try store.read {
              try $0.scalarInt(
                "SELECT 1 FROM distance_snapshots WHERE collection_id=? AND revision=?",
                [.text(id), .integer(revision)]) != nil
            }
            if publishing { throw WorkoutDistanceError.invalid("Injected fixture publication failure") }
          }
          do {
            _ = try WorkoutDistanceStore(store: store).snapshot(id: id)
            preconditionFailure("Fixture publication fault did not fire")
          } catch { assertions += 1 }
          store.beforeCommitForTesting = nil
          if let old {
            equal(
              try WorkoutDistanceStore(store: store).page(snapshot: old).last?.distanceMeters, old.totalMeters,
              name + " rollback keeps old anchor")
          }
          snapshot = try WorkoutDistanceStore(store: store).snapshot(id: id)
          PowerLogStore.evictSharedForTesting(databaseURL: store.databaseURL)
          store = try PowerLogStore.shared(databaseURL: store.databaseURL)
          archive = try WorkoutArchive(rootURL: root.appendingPathComponent("archive"), store: store)
        }
      }
      let service = WorkoutDistanceStore(store: store)
      snapshot = try service.snapshot(id: id)
      if row["expectUnavailable"] as? Bool == true { equal(snapshot!.totalMeters, nil, name + " overlap unavailable") }
      if let q = row["distanceQ"] as? String {
        equal(snapshot!.totalMeters, WorkoutDistancePolicy.seconds(Int64(q)!), name + " total")
      }
      if let us = row["coveredUs"] as? String {
        equal(snapshot!.coveredSeconds, WorkoutDistancePolicy.seconds(Int64(us)!), name + " coverage")
      }
      if let partial = row["partial"] as? Bool {
        check(snapshot!.sources.first!.partial == partial, name + " partial microsecond threshold")
        let range = try service.range(snapshot: snapshot!, start: 0, end: snapshot!.endSeconds)
        check(range.partial == partial, name + " range partial microsecond threshold")
      }
      if row["preserveEndpointSpeeds"] as? Bool == true {
        let interval = try service.page(snapshot: snapshot!).last!.interval
        equal(interval.startSpeed, samples.first!["speed"] as? Double, name + " original start speed")
        equal(interval.endSpeed, samples.last!["speed"] as? Double, name + " original end speed")
      }
      for query in row["ranges"] as? [[String: Any]] ?? [] {
        let range = try service.range(
          snapshot: snapshot!, start: query["start"] as! Double, end: query["end"] as! Double)
        equal(
          range.distanceMeters, WorkoutDistancePolicy.seconds(Int64(query["distanceQ"] as! String)!),
          name + " direct clip")
        equal(
          range.coveredSeconds, WorkoutDistancePolicy.seconds(Int64(query["coveredUs"] as! String)!),
          name + " clip coverage")
      }
      if let ascent = row["ascent"] as? Double {
        let state = try store.read {
          try $0.rows(
            "SELECT state FROM distance_generations WHERE collection_id=? AND generation=?",
            [.text(id), .text(snapshot!.generation)], limit: 1
          ).first!.data("state")!
        }
        let decoded = try JSONSerialization.jsonObject(with: state) as! [String: Any]
        let gps = ((decoded["profiles"] as! [String: Any])["gps:phone"] as! [String: Any])["gps"]!
        let accumulator = try JSONDecoder().decode(
          WorkoutGPSDistanceAccumulator.self, from: JSONSerialization.data(withJSONObject: gps))
        if let previous = accumulator.previous {
          let physical = try store.read {
            try $0.rows(
              "SELECT o.physical_id FROM collection_memberships m JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND m.event_id=?",
              [.text(id), .text(previous.identity)], limit: 1
            ).first!.string("physical_id")!
          }
          check(previous.observationID == physical, name + " retains canonical observation identity")
        }
        equal(accumulator.ascentMeters, ascent, name + " committed altitude ascent")
        equal(accumulator.descentMeters, row["descent"] as? Double, name + " committed altitude descent")
      }
      for point in try service.page(snapshot: snapshot!) {
        check(
          try service.anchor(snapshot: snapshot!, identity: point.identity)?.pointID == point.pointID,
          name + " exact original anchors")
      }
    }
  }
  print("Distance contract store: \(assertions) assertions passed.")
}

func runDistanceReviewFixtures() throws {
  let selected = ProcessInfo.processInfo.environment["POWER_LOG_DISTANCE_REVIEW_CASE"]
  var assertions = 0
  func check(_ value: Bool, _ name: String) {
    assertions += 1
    if !value { FileHandle.standardError.write(Data((name + "\n").utf8)) }
    precondition(value, name)
  }
  let start = "2026-01-01T00:00:00Z"
  for name in [
    "sourceOrder", "corePages", "bufferSharing",
  ] {
    guard selected == nil || selected == name else { continue }
    let root = FileManager.default.temporaryDirectory.appendingPathComponent("distance-review-" + UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let database = root.appendingPathComponent("store.sqlite3")
    let store = try PowerLogStore.shared(databaseURL: database)
    let archive = try WorkoutArchive(rootURL: root.appendingPathComponent("archive"), store: store)
    let ride = try archive.create(
      startedAt: WorkoutCoding.date(start), indoor: false, watchEnabled: false,
      saveToHealth: false, recordGPS: true, sampleHz: 4
    ).id
    try archive.update(id: ride, healthKitUUID: ride, stopElapsedSeconds: 2)
    if name == "bufferSharing" {
      let numbers = NSMutableData(length: 8 * MemoryLayout<Double>.stride)!
      let keys = NSMutableData(length: 12 * MemoryLayout<Int64>.stride)!
      let values = numbers.mutableBytes.assumingMemoryBound(to: Double.self)
      let integers = keys.mutableBytes.assumingMemoryBound(to: Int64.self)
      for index in 0..<2 {
        values[index * 4] = Double(index)
        values[index * 4 + 1] = 4
        values[index * 4 + 2] = .nan
        values[index * 4 + 3] = .nan
        integers[index * 6] = Int64(index) * 1_000_000
        integers[index * 6 + 1] = 0
        integers[index * 6 + 5] = 15
      }
      AppleDistancePages.shared.pageEntryObserverForTesting = { _, _ in
        values[1] = 5
        values[5] = 5
      }
      defer { AppleDistancePages.shared.pageEntryObserverForTesting = nil }
      let output = try AppleDistancePages.shared.controller(
        kernel: ControllerDistanceKernel(), numbers: numbers, keys: keys,
        text: ["X6", "5.3", "device", "link", "a", "start", "X6", "5.3", "device", "link", "b", "end"],
        totalQ: 0, coveredUs: 0)
      check(output.totalQ == 5_000_000, "Apple page entry borrows the packed buffers")
      check(
        WorkoutDistancePageProjection(output).intervals.first?.value.meters == 5,
        "Apple page output projects the shared buffer")
    } else {
      var sizes: [Int] = []
      AppleDistancePages.shared.pageEntryObserverForTesting = { _, count in sizes.append(Int(count.int32Value)) }
      defer { AppleDistancePages.shared.pageEntryObserverForTesting = nil }
      var events: [WorkoutEvent] = []
      for index in 0..<257 {
        let time = Double(index) / 256
        events.append(
          try WorkoutEvent(
            workoutId: ride, kind: "telemetry", source: "controller",
            timestamp: WorkoutCoding.date(start).addingTimeInterval(time), elapsedSeconds: time,
            payload: [
              "humanPowerW": .number(150), "cadenceRpm": .number(80), "controllerSpeedMps": .number(4),
              "controllerModel": .string("X6"), "controllerProtocol": .string("5.3"),
              "captureSessionID": .string(ride), "connectionEpoch": .string("link"), "clockEpoch": .string("epoch"),
            ]))
      }
      for source in ["phone", "watch"] {
        for time in [0.0, 1.0] {
          events.append(
            try WorkoutEvent(
              workoutId: ride, kind: "location", source: source,
              timestamp: WorkoutCoding.date(start).addingTimeInterval(time), elapsedSeconds: time,
              payload: [
                "latitude": .number(time * 0.00001), "longitude": .number(0), "horizontalAccuracyM": .number(3),
                "clockEpoch": .string("epoch"),
              ]))
          events.append(
            try WorkoutEvent(
              workoutId: ride, kind: "health", source: source,
              timestamp: WorkoutCoding.date(start).addingTimeInterval(time), elapsedSeconds: time,
              payload: [
                "value": .number(5), "unit": .string("m"), "representation": .string("rawQuantity"),
                "sampleCount": .number(1),
                "associatedWorkoutUUID": .string(ride),
                "healthKitIdentifier": .string("HKQuantityTypeIdentifierDistanceCycling"),
                "sampleStart": .string(start), "sampleEnd": .string("2026-01-01T00:00:01Z"),
              ]))
          break
        }
        events.append(
          try WorkoutEvent(
            workoutId: ride, kind: "location", source: source,
            timestamp: WorkoutCoding.date(start).addingTimeInterval(1), elapsedSeconds: 1,
            payload: [
              "latitude": .number(0.00001), "longitude": .number(0), "horizontalAccuracyM": .number(3),
              "clockEpoch": .string("epoch"),
            ]))
      }
      try archive.appendBatch(events)
      let snapshot = try WorkoutDistanceStore(store: store).snapshot(id: ride)
      if name == "corePages" {
        check(
          sizes.reduce(0, +) == 261 && sizes.allSatisfy { (1...128).contains($0) },
          "physical reduction uses bounded core pages")
        check(sizes.sorted() == [1, 2, 2, 128, 128], "physical reduction enters the core exactly once per page")
        check(
          snapshot.sources.first { $0.source == "controller" }?.distanceMeters == 4,
          "core page returns exact controller total")
      } else {
        check(
          snapshot.sources.map(\.source) == ["gps:phone", "gps:watch", "controller"],
          "phone-owner source order without Health cycling distance")
        let indoorID = try archive.create(
          startedAt: WorkoutCoding.date(start), indoor: true, watchEnabled: false,
          saveToHealth: false, recordGPS: false, sampleHz: 4
        ).id
        try archive.update(id: indoorID, healthKitUUID: indoorID, stopElapsedSeconds: 2)
        let copied = events.map { event -> WorkoutEvent in
          var copy = event
          copy.workoutId = indoorID
          copy.eventId = UUID().uuidString.lowercased()
          if copy.kind == "health" { copy.payload["associatedWorkoutUUID"] = .string(indoorID) }
          return copy
        }
        try archive.appendBatch(copied)
        let indoor = try WorkoutDistanceStore(store: store).snapshot(id: indoorID)
        check(
          indoor.sources.map(\.source) == ["controller", "gps:watch", "gps:phone"] && indoor.source == "controller",
          "indoor rides use the controller estimate under Auto")
      }
    }
  }
  print("Distance review production store: \(assertions) assertions passed.")
}
