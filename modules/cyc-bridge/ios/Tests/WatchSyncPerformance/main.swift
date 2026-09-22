import Foundation
import CryptoKit

// Optional computational stress workload; all identities and coordinates are generated fixtures.
// 8 CYC observations/s each induce 2 raw quantities, 1 insertion receipt and 2 builder observations.
// One raw heart-rate observation and one GPS observation/s add up to 42 Watch originals/s.
func require(_ condition: Bool, _ message: String) throws {
  if !condition { throw WorkoutDataError.invalid(message) }
}
func now() -> Double { ProcessInfo.processInfo.systemUptime }
func report(_ text: String) { FileHandle.standardOutput.write(Data((text + "\n").utf8)) }
func identifier(_ namespace: UInt32, _ sequence: Int64) -> String {
  String(format: "%08x-0000-4000-8000-%012llx", namespace, sequence)
}
func digest(_ hash: SHA256) -> String { hash.finalize().map { String(format: "%02x", $0) }.joined() }
let arguments = Array(CommandLine.arguments.dropFirst())
let durations: [Int]
if arguments == ["--smoke"] { durations = [2] }
else if arguments.isEmpty { durations = [57 * 60, 8 * 60 * 60] }
else {
  durations = try arguments.map {
    guard let seconds = Int($0), (1...28_800).contains(seconds) else { throw WorkoutDataError.invalid("Use --smoke or durations in seconds (1...28800)") }
    return seconds
  }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-watch-stress-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: root) }
report("Synthetic native compute benchmark only; no Watch radio, OS scheduling latency or battery measurement.")

for (scenario, seconds) in durations.enumerated() {
  let caseRoot = root.appendingPathComponent("case-\(scenario)")
  let sourceStore = try PowerLogStore.shared(databaseURL: caseRoot.appendingPathComponent("watch.sqlite3"))
  let phoneStore = try PowerLogStore.shared(databaseURL: caseRoot.appendingPathComponent("phone.sqlite3"))
  let source = try WorkoutArchive(rootURL: caseRoot.appendingPathComponent("watch"), store: sourceStore)
  let phone = try WorkoutArchive(rootURL: caseRoot.appendingPathComponent("phone"), store: phoneStore)
  let transfer = WorkoutTransferJournal(archive: source), receiver = WorkoutTransferJournal(archive: phone)
  var sender = WorkoutChunkSender(archive: source)
  let id = identifier(0x10000000 + UInt32(scenario), 1)
  let start = Date(timeIntervalSince1970: 1_780_000_000), startedAt = WorkoutCoding.timestamp(start)
  _ = try source.create(id: id, startedAt: start, indoor: false, watchEnabled: true)
  _ = try phone.create(id: id, startedAt: start, indoor: false, watchEnabled: true)
  let inbox = try WorkoutChunkInbox(root: caseRoot.appendingPathComponent("inbox"), store: phoneStore)
  inbox.setPreferredWorkoutID(id)
  var watchHash = SHA256(), cycHash = SHA256(), originalBytes = 0
  var watchCount: Int64 = 0, cycCount: Int64 = 0
  var watchBatch: [WorkoutEvent] = [], cycBatch: [WorkoutEvent] = []
  watchBatch.reserveCapacity(512); cycBatch.reserveCapacity(512)
  func flushWatch() throws {
    guard !watchBatch.isEmpty else { return }
    _ = try source.appendBatch(watchBatch, producer: "watch", firstSequence: watchCount - Int64(watchBatch.count) + 1)
    watchBatch.removeAll(keepingCapacity: true)
  }
  func flushCyc() throws {
    guard !cycBatch.isEmpty else { return }
    let first = cycCount - Int64(cycBatch.count) + 1
    _ = try source.appendBatch(cycBatch, producer: "cyc", firstSequence: first)
    _ = try phone.appendBatch(cycBatch, producer: "cyc", firstSequence: first)
    cycBatch.removeAll(keepingCapacity: true)
  }
  func watchEvent(kind: String = "health", elapsed: Double, payload: [String: WorkoutJSON]) throws {
    watchCount += 1
    let event = try WorkoutEvent(workoutId: id, kind: kind, source: "watch", timestamp: start.addingTimeInterval(elapsed),
      elapsedSeconds: elapsed, payload: payload, eventId: identifier(0x30000000 + UInt32(scenario), watchCount))
    let bytes = try WorkoutCoding.encoder().encode(event)
    watchHash.update(data: bytes); watchHash.update(data: Data([10])); originalBytes += bytes.count + 1
    watchBatch.append(event)
    if watchBatch.count == 512 { try flushWatch() }
  }
  func rawPayload(metric: String, unit: String, value: Double, elapsed: Double) -> [String: WorkoutJSON] {
    let timestamp = WorkoutCoding.timestamp(start.addingTimeInterval(elapsed))
    return ["healthKitIdentifier": .string(metric), "value": .number(value), "unit": .string(unit),
      "representation": .string("rawQuantity"), "sampleUUID": .string(identifier(0x30000000 + UInt32(scenario), watchCount + 1)),
      "sampleCount": .number(1), "sampleStart": .string(timestamp), "sampleEnd": .string(timestamp),
      "sourceBundleIdentifier": .string("com.powerlog.synthetic")]
  }
  report("duration_s=\(seconds) target_watch_records=\(seconds * 42) target_cyc_records=\(seconds * 8) phase=generate")
  let generationStart = now()
  for second in 0..<seconds {
    try autoreleasepool {
      for tick in 0..<8 {
        let elapsed = Double(second) + Double(tick) / 8
        let power = 140.25 + Double((second * 8 + tick) % 120), cadence = 72.5 + Double(tick)
        cycCount += 1
        let cyc = try WorkoutEvent(workoutId: id, kind: "telemetry", source: "cyc", timestamp: start.addingTimeInterval(elapsed),
          elapsedSeconds: elapsed, payload: ["humanPowerW": .number(power), "cadenceRpm": .number(cadence),
            "batteryVoltageV": .number(52.375), "motorInputPowerW": .number(power * 2.125)],
          eventId: identifier(0x20000000 + UInt32(scenario), cycCount))
        cycHash.update(data: try WorkoutCoding.encoder().encode(cyc)); cycHash.update(data: Data([10]))
        cycBatch.append(cyc); if cycBatch.count == 512 { try flushCyc() }
        for (metric, unit, value) in [("HKQuantityTypeIdentifierCyclingPower", "W", power), ("HKQuantityTypeIdentifierCyclingCadence", "count/min", cadence)] {
          try watchEvent(elapsed: elapsed, payload: rawPayload(metric: metric, unit: unit, value: value, elapsed: elapsed))
        }
        try watchEvent(elapsed: elapsed, payload: ["representation": .string("healthInsertionReceipt"), "insertedTelemetryEventId": .string(cyc.eventId)])
        for (metric, unit, value) in [("HKQuantityTypeIdentifierCyclingPower", "W", power), ("HKQuantityTypeIdentifierCyclingCadence", "count/min", cadence)] {
          let timestamp = WorkoutCoding.timestamp(start.addingTimeInterval(elapsed))
          try watchEvent(elapsed: elapsed, payload: ["representation": .string("builderMostRecent"),
            "healthKitIdentifier": .string(metric), "unit": .string(unit), "value": .number(value),
            "sampleStart": .string(timestamp), "sampleEnd": .string(timestamp)])
        }
      }
      let elapsed = Double(second) + 0.999
      var heart = rawPayload(metric: "HKQuantityTypeIdentifierHeartRate", unit: "count/min", value: Double(90 + second % 60), elapsed: elapsed)
      heart["heartRateBpm"] = .number(Double(90 + second % 60))
      try watchEvent(elapsed: elapsed, payload: heart)
      try watchEvent(kind: "location", elapsed: elapsed, payload: ["latitude": .number(Double(second % 1000) / 1_000_000),
        "longitude": .number(Double(second % 2000) / 1_000_000), "horizontalAccuracyM": .number(3.25), "speedMps": .number(7.125)])
    }
  }
  try flushWatch(); try flushCyc()
  let generationSeconds = now() - generationStart
  try require(watchCount == Int64(seconds * 42) && cycCount == Int64(seconds * 8), "Generated source cardinality differs from workload")
  report(String(format: "duration_s=%d phase=source_digest generate_s=%.3f", seconds, generationSeconds))
  let sourceVerificationStart = now()
  let expectedWatch = digest(watchHash), expectedCyc = digest(cycHash)
  let watchSeal = try transfer.source(id: id, producer: "watch")
  let cycSeal = try transfer.source(id: id, producer: "cyc")
  try require(watchSeal.digest == expectedWatch && watchSeal.count == watchCount, "Watch storage differs from generated exact original digest")
  try require(cycSeal.digest == expectedCyc && cycSeal.count == cycCount, "CYC storage differs from generated exact original digest")
  let sourceVerificationSeconds = now() - sourceVerificationStart
  _ = try source.finish(id: id, endedAt: start.addingTimeInterval(Double(seconds)), finalPhase: "completed")
  let seal = WorkoutSeal(workoutID: id, sealRevision: 1, collectionRevision: try source.revision(id: id), ownerRevision: 1,
    stopCutoff: WorkoutCoding.timestamp(start.addingTimeInterval(Double(seconds))), healthOutcome: "saved",
    requirements: ["healthExtraction": "sealed", "cycInsertion": "sealed"], sources: [cycSeal, watchSeal])
  _ = try transfer.accept(seal: seal)
  var chunkCount = 0, attempts = 0, replayCount = 0, wireBytes = 0, compressedBytes = 0, maximumWire = 0, maximumDecoded = 0
  var sealArrived = false
  var staleChunk: WorkoutChunk?
  let incoming = caseRoot.appendingPathComponent("incoming.plchunk")
  let transferStart = now()
  report("duration_s=\(seconds) phase=transfer")
  func deliver(_ chunk: WorkoutChunk, byFile: Bool) throws {
    let metadata = WorkoutChunkWire.metadata(chunk: chunk, startedAt: startedAt, indoor: false)
    attempts += 1; compressedBytes += chunk.data.count
    if byFile {
      try chunk.data.write(to: incoming, options: .atomic)
      try require(inbox.stage(file: incoming, metadata: metadata), "Bounded file inbox rejected sequential workload")
    } else {
      guard let data = try WorkoutChunkWire.encode(chunk: chunk, startedAt: startedAt, indoor: false) else {
        throw WorkoutDataError.invalid("Representative Watch source unexpectedly requires a file-only chunk")
      }
      wireBytes += data.count; maximumWire = max(maximumWire, data.count)
      let decoded = try WorkoutChunkWire.decode(data)
      try require(inbox.stage(data: decoded.chunk.data, metadata: decoded.metadata), "Bounded live inbox rejected sequential workload")
    }
    try require(inbox.pendingCount == 1, "Sequential producer unexpectedly grew inbox")
    guard let item = try inbox.claim() else { throw WorkoutDataError.invalid("Admitted import is not runnable") }
    let staged = WorkoutChunk(manifest: chunk.manifest, data: try Data(contentsOf: inbox.url(item)))
    _ = try receiver.receiveWatch(staged, startedAt: startedAt, indoor: false)
    try inbox.finish(item, success: true)
  }
  while let next = try sender.prepare(id: id) {
    try autoreleasepool {
      let m = next.manifest
      chunkCount += 1; maximumDecoded = max(maximumDecoded, m.uncompressedBytes)
      try require(m.producer == "watch", "Phone CYC originals were echoed back")
      if m.lastSequence == watchCount {
        _ = try receiver.accept(seal: seal); sealArrived = true
        try require(!receiver.verify(id: id), "Seal incorrectly verified before last original chunk")
      }
      let decision = try sender.attempt(id: id, reachable: true, liveFits: true, hasOutstandingFile: false, now: Double(chunkCount * 100))
      try require(decision.sendLive, "New pending range did not reset retry eligibility")
      try deliver(next, byFile: false)
      if chunkCount % 97 == 0 || (seconds <= 2 && chunkCount == 1) {
        // Receiver committed, but the ACK vanished. Recreate the sender and retry the same manifest by file.
        sender = WorkoutChunkSender(archive: source)
        guard let recovered = try sender.prepare(id: id) else { throw WorkoutDataError.invalid("Lost ACK discarded pending original range") }
        try require(recovered.manifest == m && recovered.data == next.data, "Restart changed frozen chunk identity or content")
        let fallback = try sender.attempt(id: id, reachable: false, liveFits: true, hasOutstandingFile: false, now: Double(chunkCount * 100 + 15))
        try require(fallback.sendFile, "Unreachable ACK retry did not admit file fallback")
        try deliver(recovered, byFile: true); replayCount += 1
      }
      try require(sender.acknowledge(id: id, producer: "watch", identity: m.identity, lastSequence: m.lastSequence, contentHash: m.contentHash), "Exact committed ACK did not advance sender")
      if chunkCount % 193 == 0, let stale = staleChunk {
        // An old OS-scheduled file may arrive after later live ranges were already acknowledged.
        try deliver(stale, byFile: true); replayCount += 1
        try require(!sender.acknowledge(id: id, producer: "watch", identity: stale.manifest.identity,
          lastSequence: stale.manifest.lastSequence, contentHash: stale.manifest.contentHash), "Reordered stale ACK advanced source cursor")
      }
      if chunkCount % 97 == 1 { staleChunk = next }
    }
  }
  let transferSeconds = now() - transferStart
  try require(sealArrived && sender.acknowledgedSequence(id: id) == watchCount, "Final tail was skipped")
  try require(inbox.pendingCount == 0, "Completed receiver retained staged work")
  let verificationStart = now()
  report("duration_s=\(seconds) phase=verify")
  try require(receiver.verify(id: id), "Final original/seal verification did not converge")
  let actualWatch = try receiver.source(id: id, producer: "watch"), actualCyc = try receiver.source(id: id, producer: "cyc")
  try require(actualWatch.count == watchCount && actualWatch.lastSequence == watchCount && actualWatch.digest == expectedWatch,
    "Receiver Watch originals do not exactly match generated identities, timestamps and payload bytes")
  try require(actualCyc.count == cycCount && actualCyc.lastSequence == cycCount && actualCyc.digest == expectedCyc,
    "Canonical phone CYC originals changed during Watch delivery")
  let verifySeconds = now() - verificationStart
  let output: [String: Any] = ["durationSeconds": seconds, "cycHz": 8, "watchRecordsPerSecond": 42,
    "watchRecords": watchCount, "canonicalCycRecordsPerDevice": cycCount, "watchOriginalBytes": originalBytes,
    "chunks": chunkCount, "attempts": attempts, "replays": replayCount, "compressedBytesWithReplays": compressedBytes,
    "liveEnvelopeBytes": wireBytes, "maximumLiveEnvelopeBytes": maximumWire, "maximumDecodedChunkBytes": maximumDecoded,
    "generateSeconds": generationSeconds, "sourceDigestSeconds": sourceVerificationSeconds,
    "transferSeconds": transferSeconds, "verifySeconds": verifySeconds, "exactDigestMatch": true,
    "maximumAdmittedFilesObserved": 1, "radioLatencyMeasured": false, "watchBatteryMeasured": false]
  let result = try JSONSerialization.data(withJSONObject: output, options: [.sortedKeys])
  report(String(data: result, encoding: .utf8)!)
}
