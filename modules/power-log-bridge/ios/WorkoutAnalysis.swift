import Foundation
import PowerLogCore

enum WorkoutAnalysisError: Error, LocalizedError {
  case cancelled, overflow(String), invalid(String)
  var bridgeCode: String {
    switch self {
    case .cancelled: return "ERR_READ_CANCELLED"
    case .overflow: return "ERR_NUMERIC_OVERFLOW"
    case .invalid: return "ERR_INVALID_RECORD"
    }
  }
  var errorDescription: String? {
    switch self {
    case .cancelled: return "Summary read cancelled."
    case .overflow(let value), .invalid(let value): return value
    }
  }
}

final class WorkoutSummaryCancellation {
  private let lock = NSLock()
  private var stopped = false
  func cancel() {
    lock.lock()
    stopped = true
    lock.unlock()
  }
  func publish(_ body: () throws -> Void) throws {
    lock.lock()
    defer { lock.unlock() }
    if stopped { throw WorkoutAnalysisError.cancelled }
    try body()
  }
  func check() throws {
    lock.lock()
    let cancelled = stopped
    lock.unlock()
    if cancelled { throw WorkoutAnalysisError.cancelled }
  }
}

enum WorkoutAnalysis {
  static var pageObserverForTesting: ((Int, Int) -> Void)?
  static var queryObserverForTesting: ((String, [String], Int, Int) -> Void)?
  private struct CachedSummary: Codable {
    let identity: String
    let summary: WorkoutSummary
  }
  static func validateDistance(_ selection: String) throws {
    guard DistancePolicy.shared.validSelection(selection: selection) else {
      throw WorkoutDistanceError.invalid("Unknown distance source")
    }
  }
  static func summarize(
    archive: WorkoutArchive, id: String, revision requestedRevision: Int64? = nil, distanceSource: String = "auto",
    cancellation: WorkoutSummaryCancellation = WorkoutSummaryCancellation(),
    pageLimit: Int = 256,
    onPage: ((AnalysisPass, Int) throws -> Void)? = nil
  ) throws -> WorkoutSummary {
    let readLease = try PowerLogReadLease(store: archive.store, id: id)
    defer { readLease.release() }
    try cancellation.check()
    guard (1...256).contains(pageLimit) else { throw WorkoutAnalysisError.invalid("Invalid summary page limit") }
    try validateDistance(distanceSource)
    let revision = try requestedRevision ?? archive.revision(id: id)
    let metadata = try archive.metadata(id: id, atRevision: revision)
    let pin = try analysisPin(archive: archive, metadata: metadata, revision: revision, selection: distanceSource)
    let identity = CacheIdentity.shared.of(pin: pin)
    let cache = try archive.directory(id: id).appendingPathComponent(CacheIdentity.shared.filename(pin: pin))
    if let values = try? cache.resourceValues(forKeys: [.fileSizeKey]), let size = values.fileSize, size <= 1_048_576,
      let data = try? Data(contentsOf: cache), let cached = try? JSONDecoder().decode(CachedSummary.self, from: data),
      cached.identity == identity, cached.summary.batteryConsumption != nil
    {
      try cancellation.check()
      try archive.store.requireWorkoutAvailable(id: id)
      return cached.summary
    }
    let reader = Reader(archive: archive, pin: pin, cancellation: cancellation, pageLimit: pageLimit, onPage: onPage)
    defer { reader.close() }
    let preparation = SummaryPreparation(pin: pin)
    try reader.each(
      .prepare,
      telemetry: { numbers, keys, text in
        try AppleSummaryPages.shared.prepare(preparation: preparation, numbers: numbers, keys: keys, text: text)
      }
    ) { item, energies in
      preparation.append(item: item)
      for evidence in energies { preparation.energy(evidence: evidence) }
    }
    let distanceStore = WorkoutDistanceStore(
      store: archive.store, checkCancellation: cancellation.check, publishCheckpoint: cancellation.publish)
    let held: (snapshot: WorkoutDistanceSnapshot, lease: WorkoutDistanceLease)
    do {
      held = try distanceStore.leasedSnapshot(id: id, revision: revision, selection: distanceSource)
    } catch WorkoutDistanceError.invalid(let reason) {
      throw WorkoutAnalysisError.invalid(reason)
    }
    defer { held.lease.release() }
    let snapshot = held.snapshot
    let distance = DistanceInfo(
      selection: distanceSource, selected: snapshot.info.selected?.core, available: snapshot.sources.map(\.core))
    let input = try prepared(preparation, distance: distance)
    let selected = try reader.healthSelections(input)
    let composer = SummaryComposer(
      prepared: input, distance: distance, healthSelections: selected)
    try reader.each(
      .compose,
      telemetry: { numbers, keys, text in
        try AppleSummaryPages.shared.compose(composer: composer, numbers: numbers, keys: keys, text: text)
      }
    ) { item, energies in
      let index = input.timeline.interruptionIndex(item: item)
      var mapped = item
      if index != item.continuity.interruptionIndex {
        let continuity = Continuity(
          clockEpoch: item.continuity.clockEpoch, connectionEpoch: item.continuity.connectionEpoch,
          interruptionIndex: index)
        mapped = item.doCopy(
          collectionUs: item.collectionUs, collectionElapsedSeconds: item.collectionElapsedSeconds,
          eventId: item.eventId, observationId: item.observationId, producer: item.producer, sequence: item.sequence,
          kind: item.kind, source: item.source, timestamp: item.timestamp, values: item.values,
          originalElapsedSeconds: item.originalElapsedSeconds, representation: item.representation,
          continuity: continuity, action: item.action, distanceBarrier: item.distanceBarrier,
          interrupted: item.interrupted, controllerSequence: item.controllerSequence,
          hasElapsedMapping: item.hasElapsedMapping, origin: item.origin, declaredMetrics: item.declaredMetrics)

      }
      composer.append(projection: AnalysisProjection.Observation(item: mapped))
      for evidence in energies { composer.energy(evidence: evidence) }
    }
    try cancellation.check()
    var summary: WorkoutSummary
    switch composer.finish() {
    case let complete as SummaryOutcome.Complete:
      summary = try JSONDecoder().decode(
        WorkoutSummary.self,
        from: Data(
          SummaryWire.shared.encode(
            summary: complete.summary, lifecycleCount: input.counts["lifecycle"]?.int64Value ?? 0
          ).utf8))
    case let overflow as SummaryOutcome.Overflow:
      throw WorkoutAnalysisError.overflow("\(overflow.failure.metric):\(overflow.failure.quantity)")
    case let invalid as SummaryOutcome.InvalidEvidence: throw WorkoutAnalysisError.invalid(invalid.reason)
    default: throw WorkoutAnalysisError.cancelled
    }
    let batterySource = metadata.batteryConsumptionSource
    let energy = snapshot.battery[batterySource + "EnergyWh"]
    let charge = snapshot.battery[batterySource + "ChargeAh"]
    summary.batteryConsumption = [
      "source": .string(batterySource), "energyWh": energy.map { .number($0.total) } ?? .null,
      "chargeAh": charge.map { .number($0.total) } ?? .null,
      "partial": .bool(
        energy == nil || charge == nil
          || energy?.partial(activeSeconds: snapshot.activeSeconds) == true
          || charge?.partial(activeSeconds: snapshot.activeSeconds) == true),
    ]
    try archive.store.requireWorkoutAvailable(id: id)
    try archive.store.read(priority: .background) { _ in
      try archive.store.requireWorkoutAvailable(id: id)
      let currentRevision = try archive.revision(id: id)
      let current = try analysisPin(
        archive: archive, metadata: archive.metadata(id: id, atRevision: currentRevision), revision: currentRevision,
        selection: distanceSource)
      try cancellation.publish {
        if CacheIdentity.shared.mayPublish(request: pin, current: current) {
          try? WorkoutCoding.encoder().encode(CachedSummary(identity: identity, summary: summary)).write(
            to: cache, options: .atomic)
        }
      }
    }
    return summary
  }

  private static func prepared(_ preparation: SummaryPreparation, distance: DistanceInfo?) throws -> PreparedSummary {
    switch preparation.finishOutcome(distance: distance) {
    case let value as PreparationOutcome.Prepared: return value.summary
    case let overflow as PreparationOutcome.Overflow:
      throw WorkoutAnalysisError.overflow("\(overflow.failure.metric):\(overflow.failure.quantity)")
    case let invalid as PreparationOutcome.InvalidEvidence: throw WorkoutAnalysisError.invalid(invalid.reason)
    default: throw WorkoutAnalysisError.invalid("Unknown preparation outcome")
    }
  }

  static func semanticIdentity(archive: WorkoutArchive, record: WorkoutMetadata, selection: String = "auto") throws
    -> String
  {
    try validateDistance(selection)
    return try archive.rootURL.path + ":"
      + CacheIdentity.shared.of(
        pin: analysisPin(
          archive: archive, metadata: record, revision: record.collectionRevision ?? archive.revision(id: record.id),
          selection: selection))
  }

  private static func analysisPin(
    archive: WorkoutArchive, metadata m: WorkoutMetadata, revision: Int64, selection: String
  ) throws -> AnalysisPin {
    let sources = [
      MeasurementSource(source: .phone, device: .iphone, provider: .applehealth, dataOrigin: nil),
      MeasurementSource(source: .watch, device: .applewatch, provider: .applehealth, dataOrigin: nil),
    ]
    let metadata = AnalysisMetadata(
      id: m.id, startedAt: m.startedAt, elapsedSeconds: m.elapsedSeconds, indoor: m.indoor,
      watchEnabled: m.watchEnabled, recordGPS: m.recordGPS, saveToHealth: m.saveToHealth, sampleHz: Int32(m.sampleHz),
      endedAt: m.endedAt, stopElapsedSeconds: m.stopElapsedSeconds.map(KotlinDouble.init(value:)),
      sources: sources, completeEncodedMetadata: String(decoding: try WorkoutCoding.encoder().encode(m), as: UTF8.self),
      phase: m.phase, healthOutcome: HealthOutcome.entries.first { $0.name == m.healthKitState } ?? .unknown,
      healthReadbackPending: false)
    return AnalysisPin(rideId: m.id, observationRevision: revision, metadata: metadata, distanceSelection: selection)
  }

  private final class Reader {
    let archive: WorkoutArchive
    let pin: AnalysisPin
    let cancellation: WorkoutSummaryCancellation
    let pageLimit: Int
    let onPage: ((AnalysisPass, Int) throws -> Void)?
    init(
      archive: WorkoutArchive, pin: AnalysisPin, cancellation: WorkoutSummaryCancellation,
      pageLimit: Int,
      onPage: ((AnalysisPass, Int) throws -> Void)?
    ) {
      self.archive = archive
      self.pin = pin
      self.cancellation = cancellation
      self.pageLimit = pageLimit
      self.onPage = onPage
    }
    private let stage = "summary_health_" + UUID().uuidString.replacingOccurrences(of: "-", with: "")
    private var staged = false
    func close() {
      if staged { _ = try? archive.store.read(priority: .background) { try $0.execute("DROP TABLE \(stage)") } }
    }
    private static let columns: [(String, String)] = [
      ("humanPowerW", "t.humanPowerW"), ("cadenceRpm", "t.cadenceRpm"), ("heartRateBpm", "h.heartRateBpm"),
      ("activeEnergyKcal", "h.activeEnergyKcal"), ("basalEnergyKcal", "h.basalEnergyKcal"),
      ("latitude", "l.latitude"), ("longitude", "l.longitude"), ("horizontalAccuracyM", "l.horizontalAccuracyM"),
      ("altitudeMeters", "l.altitudeMeters"), ("verticalAccuracyM", "l.verticalAccuracyM"), ("speedMps", "l.speedMps"),
      ("speedAccuracyMps", "l.speedAccuracyMps"),
    ]
    private static func declaredMetrics(_ row: PowerLogRow) throws -> [String]? {
      var declared = try row.string("declaredMetrics").map {
        try JSONDecoder().decode([String].self, from: Data($0.utf8))
      }
      if ["workoutAssociation", "workoutMetadata", "healthTombstone"].contains(row.string("representation") ?? "") {
        declared = []
      }
      if declared == nil, row.string("kind") == "health", let identifier = row.string("identifier") {
        let fields = [
          "HKQuantityTypeIdentifierHeartRate": "heartRateBpm",
          "HKQuantityTypeIdentifierActiveEnergyBurned": "activeEnergyKcal",
          "HKQuantityTypeIdentifierBasalEnergyBurned": "basalEnergyKcal",
        ]
        declared = fields[identifier].map { [$0] } ?? []
      }
      return declared
    }
    func each(
      _ pass: AnalysisPass, healthOnly: Bool = false, intervalOrder: Bool = false,
      telemetry: ((Data, Data, [String]) throws -> Void)? = nil,
      _ body: (AnalysisItem, [EnergyEvidence]) throws -> Void
    ) throws {
      let rank = "CASE WHEN m.kind='lifecycle' THEN 0 ELSE 1 END"
      let intervalStart =
        "CASE WHEN o.representation IN ('intervalRecord','rawQuantity','rawSeries') THEN coalesce(m.elapsed_us+h.interval_start_us-o.query_us,m.elapsed_us) ELSE m.elapsed_us END"
      let channel = healthOnly ? (intervalOrder ? "energy" : "heart") : "original"
      let order =
        healthOnly
        ? (intervalOrder ? "s.order_us,s.id" : "s.order_us,s.producer,s.sequence,s.id")
        : "m.elapsed_us,\(rank),m.producer,m.sequence,m.id"
      let keyColumns =
        healthOnly
        ? (intervalOrder ? ["order_us", "id"] : ["order_us", "producer", "sequence", "id"])
        : ["elapsed_us", "rank", "producer", "sequence", "id"]
      var cursor: PowerLogRow?
      var pageIndex = 0
      while true {
        try cancellation.check()
        let page = try archive.store.read(priority: .background) { db in
          try archive.store.requireWorkoutAvailable(id: pin.rideId)
          let extra = "CAST(o.extra AS TEXT)"
          if !staged {
            try db.execute(
              "CREATE TEMP TABLE \(stage)(channel TEXT NOT NULL,order_us INTEGER NOT NULL,id INTEGER NOT NULL,producer TEXT NOT NULL,sequence INTEGER NOT NULL,PRIMARY KEY(channel,order_us,id)) WITHOUT ROWID"
            )
            try db.execute("CREATE INDEX \(stage)_heart ON \(stage)(channel,order_us,producer,sequence,id)")
            staged = true
          }
          let columns =
            healthOnly
            ? Self.columns.filter { intervalOrder ? $0.0.hasSuffix("EnergyKcal") : $0.0 == "heartRateBpm" }
            : Self.columns
          let activeColumn = columns.firstIndex { $0.0 == "activeEnergyKcal" }.map { Int32($0 + 17) }
          let basalColumn = columns.firstIndex { $0.0 == "basalEnergyKcal" }.map { Int32($0 + 17) }
          let fields = columns.map { "\($0.1) AS \($0.0)" }.joined(separator: ",")
          let action = healthOnly ? "NULL" : "lc.action"
          let mappedStart = healthOnly ? "s.order_us" : intervalStart
          var sql =
            "SELECT m.id,m.event_id,m.producer,m.sequence,m.kind,m.source,m.elapsed_seconds,m.elapsed_us,m.original_elapsed_seconds,m.revision,o.physical_id,o.original_timestamp,o.clock_epoch,o.representation,\(action) AS action,\(rank) AS rank,\(mappedStart) AS order_us,\(fields),h.start_timestamp,h.end_timestamp,h.interval_start_us,h.interval_end_us,o.query_us AS original_us,h.identifier,json_valid(\(extra)) AS valid_extra,json_type(\(extra)) AS extra_type"
          for key in [
            "origin", "declaredMetrics", "connectionEpoch", "controllerSequence", "distanceBarrier", "interrupted",
          ] {
            let projected =
              ["distanceBarrier", "interrupted"].contains(key)
              ? "json_type(\(extra),'$.\(key)')='true'" : "json_extract(\(extra),'$.\(key)')"
            sql += ",\(projected) AS \(key)"
          }
          if healthOnly {
            let index = intervalOrder ? "" : " INDEXED BY \(stage)_heart"
            sql +=
              " FROM \(stage) s\(index) JOIN collection_memberships m ON m.id=s.id JOIN observations o ON o.id=m.observation_id JOIN health_samples h ON h.observation_id=m.observation_id WHERE s.channel=?"
          } else {
            sql +=
              " FROM collection_memberships m INDEXED BY membership_summary_time JOIN observations o ON o.id=m.observation_id LEFT JOIN telemetry_frames t ON t.observation_id=m.observation_id LEFT JOIN locations l ON l.observation_id=m.observation_id LEFT JOIN health_samples h ON h.observation_id=m.observation_id LEFT JOIN lifecycle_records lc ON lc.observation_id=m.observation_id WHERE m.collection_id=? AND m.revision<=? AND \(PowerLogStore.selectedMembershipSQL)"
          }
          let values: [PowerLogSQLValue] =
            healthOnly
            ? [.text(channel)]
            : [
              .text(pin.rideId), .integer(pin.observationRevision), .integer(pin.observationRevision),
              .integer(pin.observationRevision),
            ]
          var bounds: [(String, [PowerLogSQLValue], String)] = []
          if let cursor, !healthOnly {
            let expressions = ["m.elapsed_us", rank, "m.producer", "m.sequence", "m.id"]
            for depth in expressions.indices.reversed() {
              let prefix = expressions.prefix(depth).map { "\($0)=?" }
              let condition = (prefix + ["\(expressions[depth])>?"]).joined(separator: " AND ")
              bounds.append(
                (
                  condition, keyColumns.prefix(depth + 1).map { cursor[$0] },
                  expressions.suffix(from: depth).joined(separator: ",")
                ))
            }
          } else if let cursor {
            bounds.append(
              (
                "(\(order))>(\(Array(repeating: "?", count: keyColumns.count).joined(separator: ",")))",
                keyColumns.map { cursor[$0] }, order
              ))
          } else {
            bounds.append(("1", [], order))
          }
          var bytes = 0
          var projections = 0
          var steps = 0
          var rows: [PowerLogRow] = []
          var plans: [String] = []
          var bounded = false
          for (condition, continuation, ordering) in bounds {
            try cancellation.check()
            let query = sql + " AND " + condition + " ORDER BY " + ordering + " LIMIT \(pageLimit - rows.count)"
            let arguments = values + continuation
            let batch = try db.rows(
              query, arguments, limit: pageLimit - rows.count,
              observeWork: WorkoutAnalysis.queryObserverForTesting == nil ? nil : { steps += $0 },
              admit: { row in
                let count =
                  1 + (activeColumn.flatMap(row.double) == nil ? 0 : 1)
                  + (basalColumn.flatMap(row.double) == nil ? 0 : 1)
                let size = row.conservativeBytes * count
                guard size <= 524_288 else {
                  throw WorkoutAnalysisError.invalid("Summary projection exceeds page bound")
                }
                guard bytes + size <= 524_288, projections + count <= 256 else {
                  bounded = true
                  return false
                }
                bytes += size
                projections += count
                return true
              })
            rows += batch
            if WorkoutAnalysis.queryObserverForTesting != nil {
              plans += try db.rows("EXPLAIN QUERY PLAN " + query, arguments).compactMap { $0.string("detail") }
            }
            if bounded || rows.count == pageLimit { break }
          }
          if pass == .prepare && !healthOnly {
            for row in rows where row.string("kind") == "health" {
              if try Self.declaredMetrics(row)?.contains("heartRateBpm") ?? true {
                try db.execute(
                  "INSERT INTO \(stage) VALUES('heart',?,?,?,?)",
                  [row["elapsed_us"], row["id"], row["producer"], row["sequence"]])
              }
              if row.double("activeEnergyKcal") != nil || row.double("basalEnergyKcal") != nil {
                try db.execute(
                  "INSERT INTO \(stage) VALUES('energy',?,?,?,?)",
                  [row["order_us"], row["id"], row["producer"], row["sequence"]])
              }
            }
          }
          if let observer = WorkoutAnalysis.queryObserverForTesting {
            observer(channel, plans, steps, rows.count)
          }
          return (rows: rows, projections: projections, bytes: bytes)

        }
        if page.rows.isEmpty { return }
        WorkoutAnalysis.pageObserverForTesting?(page.projections, page.bytes)
        try autoreleasepool {
          var numbers: [Double] = []
          var keys: [Int64] = []
          var text: [String] = []
          if telemetry != nil {
            numbers.reserveCapacity(page.rows.count * 4)
            keys.reserveCapacity(page.rows.count * 5)
            text.reserveCapacity(page.rows.count * 6)
          }
          func flushTelemetry() throws {
            guard !keys.isEmpty, let telemetry else { return }
            try numbers.withUnsafeBytes { numberBytes in
              try keys.withUnsafeBytes { keyBytes in
                try telemetry(Data(numberBytes), Data(keyBytes), text)
              }
            }
            numbers.removeAll(keepingCapacity: true)
            keys.removeAll(keepingCapacity: true)
            text.removeAll(keepingCapacity: true)
          }
          for row in page.rows {
            try cancellation.check()
            guard row.int("valid_extra") == 1, row.string("extra_type") == "object", let time = row.int("elapsed_us"),
              let seconds = row.double("elapsed_seconds"), let identity = row.string("physical_id"),
              let event = row.string("event_id"), let producer = row.string("producer"),
              let sequence = row.int("sequence"), let kind = row.string("kind"), let source = row.string("source"),
              let timestamp = row.string("original_timestamp")
            else { throw WorkoutAnalysisError.invalid("Unreadable summary original") }
            guard kind == "health" || row.double("original_elapsed_seconds") != nil else {
              throw WorkoutAnalysisError.invalid("App original has no retained elapsed mapping")
            }
            if kind == "telemetry", telemetry != nil {
              let declared = try Self.declaredMetrics(row)
              func scalar(_ metric: String, integerFlag: Int64, missingFlag: Int64) throws -> (Double, Int64, Int64) {
                switch row[metric] {
                case .integer(let value): return (Double(value), value, integerFlag)
                case .real(let value): return (value, 0, 0)
                case .null: return (.nan, 0, missingFlag)
                default: throw WorkoutAnalysisError.invalid("Invalid summary metric \(metric)")
                }
              }
              let power = try scalar("humanPowerW", integerFlag: 1, missingFlag: 2)
              let cadence = try scalar("cadenceRpm", integerFlag: 4, missingFlag: 8)
              if let origin = row.string("origin") { _ = try HealthCodec.shared.decodeOrigin(text: origin) }
              let originalElapsed = row.double("original_elapsed_seconds")
              let clock = row.string("clock_epoch")
              let connection = row.string("connectionEpoch")
              var flags = power.2 | cadence.2
              if originalElapsed != nil { flags |= 16 }
              if clock != nil { flags |= 32 }
              if connection != nil { flags |= 64 }
              if declared?.contains("humanPowerW") ?? true { flags |= 128 }
              if declared?.contains("cadenceRpm") ?? true { flags |= 256 }
              numbers.append(seconds)
              numbers.append(originalElapsed ?? .nan)
              numbers.append(power.0)
              numbers.append(cadence.0)
              keys.append(time)
              keys.append(sequence)
              keys.append(flags)
              keys.append(power.1)
              keys.append(cadence.1)
              text.append(identity)
              text.append(event)
              text.append(timestamp)
              text.append(producer)
              text.append(clock ?? "")
              text.append(connection ?? "")
              continue
            }
            try flushTelemetry()
            var values: [String: Scalar] = [:]
            for (key, _) in Self.columns where !key.hasSuffix("EnergyKcal") {
              switch row[key] {
              case .integer(let n): values[key] = Scalar.Integer64(value: n)
              case .real(let n): values[key] = Scalar.Real64(value: n)
              case .null: break
              default: throw WorkoutAnalysisError.invalid("Invalid summary metric \(key)")
              }
            }
            var origin = try row.string("origin").map { try HealthCodec.shared.decodeOrigin(text: $0) }
            if origin == nil && kind == "health" {
              origin = Origin(
                provider: .applehealth, dataOrigin: nil, providerObservationId: nil, originEventId: event,
                producer: source == "watch" ? .watch : .phone, sequence: sequence, originRevision: nil)
            }
            let declared = try Self.declaredMetrics(row)
            let controllerSequence =
              row.int("controllerSequence") ?? row.string("controllerSequence").flatMap(Int64.init)
            let item = AnalysisItem(
              collectionUs: time, collectionElapsedSeconds: seconds, eventId: event, observationId: identity,
              producer: producer, sequence: sequence, kind: kind, source: source, timestamp: timestamp, values: values,
              originalElapsedSeconds: row.double("original_elapsed_seconds").map(KotlinDouble.init(value:)),
              representation: row.string("representation"),
              continuity: Continuity(
                clockEpoch: row.string("clock_epoch"), connectionEpoch: row.string("connectionEpoch"),
                interruptionIndex: 0), action: row.string("action"), distanceBarrier: row.int("distanceBarrier") == 1,
              interrupted: row.int("interrupted") == 1,
              controllerSequence: controllerSequence.map(KotlinLong.init(value:)),
              hasElapsedMapping: row.double("original_elapsed_seconds") != nil, origin: origin,
              declaredMetrics: declared)
            var energies: [EnergyEvidence] = []
            if kind == "health" {
              for metric in ["active", "basal"] {
                guard let value = row.double(metric + "EnergyKcal"),
                  declared == nil || declared!.contains(metric + "EnergyKcal")
                else { continue }
                guard let origin else { throw WorkoutAnalysisError.invalid("Missing Health origin") }
                let evidence: EnergyEvidence
                if item.representation == "intervalRecord" || item.representation == "rawQuantity"
                  || item.representation == "rawSeries"
                {
                  guard let first = row.string("start_timestamp"), let last = row.string("end_timestamp") else {
                    throw WorkoutAnalysisError.invalid("Energy interval has no original bounds")
                  }
                  guard let originalUs = row.int("original_us"), let firstUs = row.int("interval_start_us"),
                    let lastUs = row.int("interval_end_us")
                  else { throw WorkoutAnalysisError.invalid("Missing energy timestamp keys") }
                  let offset = FixedPoint.shared.subtract(a: time, b: originalUs)
                  let interval = EnergyInterval(
                    source: source == "watch" ? .watch : .phone, origin: origin,
                    observationRevision: row.int("revision")!,
                    startUs: FixedPoint.shared.add(a: firstUs, b: offset),
                    endUs: FixedPoint.shared.add(a: lastUs, b: offset), originalStartTimestamp: first,
                    originalEndTimestamp: last, valueKcal: value)
                  evidence = EnergyEvidence.IntervalRecord(metric: metric, interval: interval)
                } else {
                  let point = EnergyPoint(
                    source: source == "watch" ? .watch : .phone, origin: origin,
                    observationRevision: row.int("revision")!, collectionUs: time, originalTimestamp: timestamp,
                    valueKcal: value, association: Association.Workout(identity: pin.rideId))
                  evidence =
                    item.representation == "finalWorkoutTotal"
                    ? EnergyEvidence.FinalWorkoutTotal(metric: metric, point: point)
                    : EnergyEvidence.CumulativeSnapshot(metric: metric, point: point)
                }
                energies.append(evidence)
              }
            }
            try body(item, energies)
          }
          try flushTelemetry()
        }
        try onPage?(pass, pageIndex)
        try cancellation.check()
        cursor = page.rows.last
        pageIndex += 1
      }
    }
    func healthSelections(_ prepared: PreparedSummary) throws -> [HealthSelection] {
      guard !prepared.healthSources.isEmpty else { return [] }
      let window = AnalysisRange(startUs: 0, endUs: prepared.actualEndUs)
      var heart: HeartRateOriginPreparation?
      if let source = prepared.healthSources["heartRateBpm"] {
        heart = HeartRateOriginPreparation(source: source, window: window)
      }
      let energy = Dictionary(
        uniqueKeysWithValues: ["active", "basal"].compactMap { metric in
          prepared.healthSources[metric].map { (metric, EnergyOriginPreparation(source: $0, window: window)) }
        })
      if let heart {
        try each(.prepare, healthOnly: true) { item, _ in
          guard let origin = item.origin, item.kind == "health", item.isReadingOf(metric: "heartRateBpm"),
            prepared.heartRateEligible(item: item, dataOrigin: origin.dataOrigin)
          else { return }
          let evidence = HeartRateEvidence(
            origin: origin, source: item.source == "watch" ? .watch : .phone,
            association: Association.Workout(identity: self.pin.rideId), originalStartTimestamp: item.timestamp,
            originalEndTimestamp: item.timestamp, startUs: item.collectionUs, endUs: item.collectionUs,
            comparisonUs: item.collectionUs, collectionUs: item.collectionUs,
            continuity: Continuity(
              clockEpoch: item.continuity.clockEpoch, connectionEpoch: item.continuity.connectionEpoch,
              interruptionIndex: prepared.timeline.interruptionIndex(item: item)),
            representation: item.representation ?? "builderSnapshot", memberIdentity: item.observationId,
            memberCount: 1,
            valueBpm: item.number(metric: "heartRateBpm"), unit: "bpm",
            observationRevision: self.pin.observationRevision)
          try heart.append(record: evidence, activeInterval: prepared.timeline.membership(item: item))
        }
      }
      if !energy.isEmpty {
        try each(.prepare, healthOnly: true, intervalOrder: true) { _, energies in
          for evidence in energies where SummaryKt.energyAvailable(evidence: evidence) {
            try energy[evidence.metric]?.append(evidence: evidence)
          }
        }
      }
      var selections: [HealthSelection] = []
      for metric in ["heartRateBpm", "active", "basal"] {
        let winner = try metric == "heartRateBpm" ? heart?.selected() : energy[metric]?.selected()
        if let winner, let source = prepared.healthSources[metric] {
          selections.append(
            HealthSelection(
              metric: metric, source: source == "watch" ? .watch : .phone,
              dataOrigin: winner.dataOrigin.isEmpty ? nil : winner.dataOrigin, unionCoverageUs: winner.coveredUs,
              contributingOrigins: [], association: Association.Workout(identity: pin.rideId)))
        }
      }
      return selections
    }
  }
}

final class WorkoutSummaryReads {
  static let shared = WorkoutSummaryReads()
  private final class Subscriber {
    let id: String
    let key = UUID().uuidString
    let row: String
    let cancellation = WorkoutSummaryCancellation()
    let completion: (Result<[String: Any], Error>) -> Void
    let readLease: PowerLogReadLease?
    init(
      id: String, row: String, readLease: PowerLogReadLease?,
      completion: @escaping (Result<[String: Any], Error>) -> Void
    ) {
      self.id = id
      self.row = row
      self.readLease = readLease
      self.completion = completion
    }
  }
  private final class Flight {
    let cancellation = WorkoutSummaryCancellation()
    var subscribers: [String: Subscriber] = [:]
  }
  private let lock = NSLock()
  private let queue: OperationQueue
  var beforeDeliveryForTesting: ((String) -> Void)?
  private var flights: [String: Flight] = [:]
  private var jobs: [String: (key: String, subscriber: Subscriber)] = [:]
  private var warmed: [String: String] = [:]
  private var rowRequests: [String: String] = [:]
  init() {
    queue = OperationQueue()
    queue.name = "app.powerlog.summary.reads"
    queue.qualityOfService = .utility
    queue.maxConcurrentOperationCount = 2
  }
  private func cancelLocked(_ id: String) {
    guard let job = jobs[id] else { return }
    job.subscriber.cancellation.cancel()
    if let flight = flights[job.key], flight.subscribers.values.allSatisfy({ (try? $0.cancellation.check()) == nil }) {
      flight.cancellation.cancel()
    }
  }
  func submit(
    id: String, row: String, foreground: Bool = true, semanticPin: String? = nil, readLease: PowerLogReadLease? = nil,
    work: @escaping (WorkoutSummaryCancellation) throws -> [String: Any],
    completion: @escaping (Result<[String: Any], Error>) -> Void
  ) {
    let key = semanticPin ?? UUID().uuidString
    let subscriber = Subscriber(id: id, row: row, readLease: readLease, completion: completion)
    lock.lock()
    for (oldID, job) in jobs where job.subscriber.row == row { cancelLocked(oldID) }
    rowRequests[row] = subscriber.key
    if let flight = flights[key], (try? flight.cancellation.check()) != nil {
      flight.subscribers[subscriber.key] = subscriber
      jobs[id] = (key, subscriber)
      lock.unlock()
      return
    }
    let flight = Flight()
    flight.subscribers[subscriber.key] = subscriber
    flights[key] = flight
    jobs[id] = (key, subscriber)
    lock.unlock()
    let operation = BlockOperation {
      let result = Result {
        try flight.cancellation.check()
        let deadline = ProcessInfo.processInfo.systemUptime + 60
        while true {
          try flight.cancellation.check()
          do {
            let value = try work(flight.cancellation)
            try flight.cancellation.check()
            return value
          } catch WorkoutDistanceError.pending where ProcessInfo.processInfo.systemUptime < deadline {
            Thread.sleep(forTimeInterval: 0.25)
          } catch WorkoutDistanceError.expired where ProcessInfo.processInfo.systemUptime < deadline {
            Thread.sleep(forTimeInterval: 0.25)
          }
        }
      }
      self.lock.lock()
      if self.flights[key] === flight { self.flights.removeValue(forKey: key) }
      let subscribers = flight.subscribers
      self.lock.unlock()
      for subscriber in subscribers.values {
        self.beforeDeliveryForTesting?(subscriber.id)
        self.lock.lock()
        let current = self.jobs[subscriber.id]?.subscriber === subscriber
        let accepted = current && self.rowRequests[subscriber.row] == subscriber.key
        let delivery: Result<[String: Any], Error> =
          (try? subscriber.cancellation.check()) == nil
          ? .failure(WorkoutAnalysisError.cancelled) : result
        if current { self.jobs.removeValue(forKey: subscriber.id) }
        if accepted { self.rowRequests.removeValue(forKey: subscriber.row) }
        self.lock.unlock()
        subscriber.readLease?.release()
        if accepted { subscriber.completion(delivery) }
      }
    }
    operation.queuePriority = foreground ? .high : .low
    queue.addOperation(operation)
  }
  func read(
    archive: WorkoutArchive, record: WorkoutMetadata, id: String, row: String, distanceSource: String = "auto",
    completion: @escaping (Result<[String: Any], Error>) -> Void
  ) throws {
    let lease = try PowerLogReadLease(store: archive.store, id: record.id)
    let pin = try WorkoutAnalysis.semanticIdentity(archive: archive, record: record, selection: distanceSource)
    submit(
      id: id, row: row, semanticPin: pin, readLease: lease,
      work: { token in
        try WorkoutAnalysis.summarize(
          archive: archive, id: record.id, revision: record.collectionRevision,
          distanceSource: distanceSource, cancellation: token
        ).dictionary
      }, completion: completion)
  }
  func handoff(archive: WorkoutArchive, id: String, completion: () -> Void) throws {
    try warm(archive: archive, record: archive.metadata(id: id))
    completion()
  }
  func warm(archive: WorkoutArchive, record: WorkoutMetadata) throws {
    guard record.phase == "completed" else { return }
    let lease = try PowerLogReadLease(store: archive.store, id: record.id)
    let pin = try WorkoutAnalysis.semanticIdentity(archive: archive, record: record)
    let key = archive.rootURL.path + ":" + record.id
    lock.lock()
    let changed = warmed[key] != pin
    warmed[key] = pin
    lock.unlock()
    guard changed else { return }
    submit(
      id: "completion:" + key, row: "completion:" + key, foreground: false, semanticPin: pin, readLease: lease,
      work: { token in
        try WorkoutAnalysis.summarize(
          archive: archive, id: record.id, revision: record.collectionRevision,
          cancellation: token
        ).dictionary
      },
      completion: { result in
        if case .failure = result {
          self.lock.lock()
          if self.warmed[key] == pin { self.warmed.removeValue(forKey: key) }
          self.lock.unlock()
        }
      })
  }
  func cancel(_ id: String) {
    lock.lock()
    cancelLocked(id)
    lock.unlock()
  }
  func cancel(prefix: String) {
    lock.lock()
    for id in jobs.keys where id.hasPrefix(prefix) { cancelLocked(id) }
    lock.unlock()
  }
  func drain() { queue.waitUntilAllOperationsAreFinished() }
}
