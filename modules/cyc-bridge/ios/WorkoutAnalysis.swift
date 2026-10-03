import Foundation

/// History summaries over a fixed canonical revision. Original observations remain unchanged.
enum WorkoutAnalysis {
  static func distanceKey(_ selection: String) throws -> String {
    guard selection == "auto" || WorkoutDistancePolicy.sources.contains(selection) else {
      throw WorkoutDistanceError.invalid("Unknown distance source")
    }
    return selection.replacingOccurrences(of: ":", with: "-")
  }
  private struct SummaryRevision: Codable, Equatable {
    let revision: Int64
    let metadata: Data
    let distanceSource: String
    init(archive: WorkoutArchive, id: String, revision: Int64? = nil, distanceSource: String = "auto") throws {
      _ = try WorkoutAnalysis.distanceKey(distanceSource)
      self.distanceSource = distanceSource
      self.revision = try revision ?? archive.revision(id: id)
      metadata = try WorkoutCoding.encoder().encode(archive.metadata(id: id, atRevision: self.revision))
    }
  }
  private struct CachedSummary: Codable {
    let revision: SummaryRevision
    let summary: WorkoutSummary
  }
  static func summarize(
    archive: WorkoutArchive, id: String, revision requestedRevision: Int64? = nil, distanceSource: String = "auto"
  ) throws -> WorkoutSummary {
    let revision = try SummaryRevision(
      archive: archive, id: id, revision: requestedRevision, distanceSource: distanceSource)
    let cache = try archive.directory(id: id).appendingPathComponent(
      "summary-r\(revision.revision)-d\(distanceKey(distanceSource)).json")
    if let values = try? cache.resourceValues(forKeys: [.fileSizeKey]),
      let size = values.fileSize, size <= 1_048_576,
      let data = try? Data(contentsOf: cache), let cached = try? JSONDecoder().decode(CachedSummary.self, from: data),
      cached.revision == revision
    {
      return cached.summary
    }
    let prepared = try Prepared(archive: archive, id: id, revision: revision.revision, distanceSource: distanceSource)
    let summary = try prepared.reconcile()
    // Late Watch events and metadata changes invalidate the revision. The cache is disposable.
    if try SummaryRevision(archive: archive, id: id, distanceSource: distanceSource) == revision {
      try? WorkoutCoding.encoder().encode(CachedSummary(revision: revision, summary: summary)).write(
        to: cache, options: .atomic)
    }
    return summary
  }

  private static func valid(_ value: Double?, _ range: ClosedRange<Double>) -> Double? {
    guard let value, value.isFinite, range.contains(value) else { return nil }
    return value
  }

  private struct Item {
    var time: Double
    var event: WorkoutEvent
    var producer: String
    var sequence: Int64
    var clockEpoch: String?
    var connectionEpoch: String?
    static func before(_ a: Item, _ b: Item) -> Bool {
      if a.time != b.time { return a.time < b.time }
      if a.event.kind != b.event.kind {
        if a.event.kind == "lifecycle" { return true }
        if b.event.kind == "lifecycle" { return false }
      }
      if a.producer != b.producer { return a.producer < b.producer }
      if a.sequence != b.sequence { return a.sequence < b.sequence }
      return a.event.eventId < b.event.eventId
    }
  }

  /// Short indexed pages release SQLite between reads. Every pass uses the same immutable revision.
  private final class Prepared {
    let metadata: WorkoutMetadata
    let archive: WorkoutArchive
    let revision: Int64
    let distance: WorkoutDistanceSnapshot
    private static let analysisColumns: [(String, String)] = [
      ("humanPowerW", "t.humanPowerW"), ("cadenceRpm", "t.cadenceRpm"), ("heartRateBpm", "h.heartRateBpm"),
      ("activeEnergyKcal", "h.activeEnergyKcal"),
      ("basalEnergyKcal", "h.basalEnergyKcal"),
      ("latitude", "l.latitude"), ("longitude", "l.longitude"),
      ("horizontalAccuracyM", "l.horizontalAccuracyM"), ("altitudeMeters", "l.altitudeMeters"),
      ("verticalAccuracyM", "l.verticalAccuracyM"), ("speedMps", "l.speedMps"),
      ("speedAccuracyMps", "l.speedAccuracyMps"),
    ]
    var end: Double = 0
    var lastTimestamp: String?
    var locationSource: String?
    var healthSources: [String: String] = [:]
    var rawHeartRateSources: Set<String> = []
    var finalTotals: [String: [String: (Double, Double)]] = [:]
    var intervals: [(Double, Double)] = []
    var interruptions: [WorkoutInterruptionBoundary] = []
    var lapEnds: [Double] = []
    var locationCount = 0
    var totalEvents = 0
    var counts: [String: Int] = [:]

    init(archive: WorkoutArchive, id: String, revision: Int64? = nil, distanceSource: String = "auto") throws {
      self.archive = archive
      self.revision = try revision ?? archive.revision(id: id)
      metadata = try archive.metadata(id: id, atRevision: self.revision)
      distance = try WorkoutDistanceStore(store: archive.store).snapshot(
        id: id, revision: self.revision, selection: distanceSource)
      var lifecycle: [Item] = []
      var locations: [String: Int] = [:]
      var healthAvailable: [String: Set<String>] = [:]
      var maxTime: Double = 0
      try analysis { item in
        let event = item.event
        totalEvents += 1
        counts[event.kind, default: 0] += 1
        let t = item.time
        guard t >= -1, t <= 2_678_400 else { return }
        if event.kind != "health", event.elapsedSeconds != nil {
          if item.time >= maxTime { lastTimestamp = event.timestamp }
          maxTime = max(maxTime, item.time)
        }
        if event.kind == "lifecycle" {
          guard lifecycle.count < 10_000 else { throw WorkoutDataError.invalid("Too many workout lifecycle events") }
          lifecycle.append(item)
        } else if event.kind == "location", Self.goodLocation(event) {
          locations[event.source, default: 0] += 1
        } else if event.kind == "health" {
          if valid(event.number("heartRateBpm"), 1...254) != nil,
            ["rawQuantity", "rawSeries"].contains(event.payload["representation"]?.string ?? "")
          {
            rawHeartRateSources.insert(event.source)
          }
          for key in ["heartRateBpm", "activeEnergyKcal", "basalEnergyKcal"]
          where event.number(key) != nil {
            if key == "heartRateBpm", valid(event.number(key), 1...254) == nil { continue }
            healthAvailable[key, default: []].insert(event.source)
            if key != "heartRateBpm", event.payload["representation"]?.string == "finalWorkoutTotal",
              let n = event.number(key)
            {
              if finalTotals[event.source]?[key] == nil || item.time >= finalTotals[event.source]![key]!.0 {
                finalTotals[event.source, default: [:]][key] = (item.time, n)
              }
            }
          }
        }
      }
      lifecycle.sort(by: Item.before)
      interruptions = try WorkoutInterruptionBoundary.load(
        store: archive.store, id: metadata.id, revision: self.revision)
      end = metadata.stopElapsedSeconds ?? max(metadata.elapsedSeconds, maxTime)
      guard end <= 2_678_400 else { throw WorkoutDataError.invalid("Workout duration exceeds 31 days") }
      var activeStart: Double? = 0
      for item in lifecycle where item.time <= end {
        switch item.event.payload["action"]?.string {
        case "pause", "interruption":
          if let start = activeStart {
            intervals.append((start, item.time))
            activeStart = nil
          }
        case "resume":
          if activeStart == nil {
            activeStart = item.time
          }
        case "lap":
          if item.time > 0, item.time < end, lapEnds.last != item.time { lapEnds.append(item.time) }
        default: break
        }
      }
      if let start = activeStart { intervals.append((start, end)) }
      lapEnds.append(end)
      locationSource =
        (metadata.watchEnabled && (locations["watch"] ?? 0) > 0)
        ? "watch" : ((locations["phone"] ?? 0) > 0 ? "phone" : ((locations["watch"] ?? 0) > 0 ? "watch" : nil))
      if let source = distance.source, source.hasPrefix("gps:") { locationSource = String(source.dropFirst(4)) }
      locationCount = locationSource.flatMap { locations[$0] } ?? 0
      for (key, values) in healthAvailable {
        let owner = metadata.watchEnabled ? "watch" : "phone"
        healthSources[key] = values.contains(owner) ? owner : (values.contains("phone") ? "phone" : "watch")
      }
    }
    private func analysis(_ body: (Item) throws -> Void) throws {
      let rank = "CASE WHEN m.kind='lifecycle' THEN 0 ELSE 1 END"
      let columns = Self.analysisColumns.map { "\($0.1) AS \($0.0)" }.joined(separator: ",")
      var cursor: PowerLogRow?
      while true {
        let page = try archive.store.read(priority: .background) { db in
          try archive.store.requireWorkoutAvailable(id: metadata.id)
          var sql =
            "SELECT m.id,m.event_id,m.kind,m.source,m.producer,m.sequence,m.elapsed_seconds,m.original_elapsed_seconds,o.original_timestamp,o.clock_epoch,o.representation,lc.action,json_extract(CAST(o.extra AS TEXT),'$.connectionEpoch') AS connection_epoch,json_type(CAST(o.extra AS TEXT),'$.interrupted')='true' AS interrupted,\(rank) AS rank,\(columns),json_type(CAST(o.extra AS TEXT),'$.distanceBarrier')='true' AS distance_barrier FROM collection_memberships m INDEXED BY membership_export_time JOIN observations o ON o.id=m.observation_id LEFT JOIN telemetry_frames t ON t.observation_id=m.observation_id LEFT JOIN locations l ON l.observation_id=m.observation_id LEFT JOIN health_samples h ON h.observation_id=m.observation_id LEFT JOIN lifecycle_records lc ON lc.observation_id=m.observation_id WHERE m.collection_id=? AND m.revision<=? AND "
            + PowerLogStore.selectedMembershipSQL
          var values: [PowerLogSQLValue] = [
            .text(metadata.id), .integer(revision), .integer(revision), .integer(revision),
          ]
          if let cursor {
            sql += " AND (m.elapsed_seconds,\(rank),m.producer,m.sequence,m.id)>(?,?,?,?,?)"
            values += [cursor["elapsed_seconds"], cursor["rank"], cursor["producer"], cursor["sequence"], cursor["id"]]
          }
          sql += " ORDER BY m.elapsed_seconds,\(rank),m.producer,m.sequence,m.id LIMIT 256"
          return try db.rows(sql, values, limit: 256)
        }
        if page.isEmpty { return }
        try autoreleasepool {
          for row in page {
            var payload: [String: WorkoutJSON] = [:]
            for (key, _) in Self.analysisColumns { if let value = row.double(key) { payload[key] = .number(value) } }
            if let action = row.string("action") { payload["action"] = .string(action) }
            if let representation = row.string("representation") { payload["representation"] = .string(representation) }
            if row.int("distance_barrier") == 1 { payload["distanceBarrier"] = .bool(true) }
            if row.int("interrupted") == 1 { payload["interrupted"] = .bool(true) }
            let originalElapsed = row.double("original_elapsed_seconds")
            let event = try WorkoutEvent(
              storedEventID: row.string("event_id")!, workoutID: metadata.id,
              kind: row.string("kind")!, source: row.string("source")!,
              originalTimestamp: row.string("original_timestamp")!,
              elapsedSeconds: originalElapsed, payload: payload)
            try body(
              Item(
                time: row.double("elapsed_seconds")!, event: event,
                producer: row.string("producer")!, sequence: row.int("sequence")!,
                clockEpoch: row.string("clock_epoch"), connectionEpoch: row.string("connection_epoch")))
          }
        }
        cursor = page.last
      }
    }
    func each(_ body: (Item) throws -> Void) throws {
      try analysis { item in
        guard item.time >= -1, item.time <= 2_678_400 else { return }
        try body(item)
      }
    }
    static func goodLocation(_ event: WorkoutEvent) -> Bool {
      guard let accuracy = event.number("horizontalAccuracyM"), (0...50).contains(accuracy) else { return false }
      return true
    }
    func interruptionIndex(_ item: Item) -> Int {
      WorkoutInterruptionBoundary.index(
        interruptions, time: item.time, producer: item.producer, sequence: item.sequence, clockEpoch: item.clockEpoch)
    }
    func intervalIndex(_ item: Item) -> Int? {
      let time = item.time
      let resumed = WorkoutInterruptionBoundary.resumedAtBoundary(
        interruptions, time: time, producer: item.producer, sequence: item.sequence, clockEpoch: item.clockEpoch)
      let cutoff = resumed == false
      var low = 0
      var high = intervals.count
      while low < high {
        let mid = (low + high) / 2
        if intervals[mid].0 < time || (intervals[mid].0 == time && !cutoff) { low = mid + 1 } else { high = mid }
      }
      guard low > 0, time < intervals[low - 1].1 || ((time == end || cutoff) && time == intervals[low - 1].1) else {
        return nil
      }
      return low - 1
    }
    func active(_ item: Item) -> Bool { intervalIndex(item) != nil }
    func useHeartRate(_ event: WorkoutEvent) -> Bool {
      guard event.source == healthSources["heartRateBpm"] else { return false }
      return !rawHeartRateSources.contains(event.source)
        || ["rawQuantity", "rawSeries"].contains(event.payload["representation"]?.string ?? "")
    }
    func sameRun(_ a: Item, _ b: Item) -> Bool {
      guard a.clockEpoch == b.clockEpoch, a.connectionEpoch == b.connectionEpoch else { return false }
      return interruptionIndex(a) == interruptionIndex(b)
    }
    func continuous(_ a: Item, _ b: Item) -> Bool {
      guard let index = intervalIndex(a), intervalIndex(b) == index else { return false }
      return b.time >= a.time && sameRun(a, b)
    }
    func timer(_ a: Double, _ b: Double) -> Double {
      intervals.reduce(0) { $0 + max(0, min(b, $1.1) - max(a, $1.0)) }
    }
    func reconcile() throws -> WorkoutSummary {
      var s = WorkoutSummary(
        id: metadata.id, startedAt: metadata.startedAt,
        endedAt: metadata.endedAt ?? lastTimestamp ?? metadata.startedAt)
      s.elapsedSeconds = end
      s.timerSeconds = timer(0, end)
      s.eventCount = totalEvents
      s.lapCount = lapEnds.count
      s.telemetryCount = counts["telemetry"] ?? 0
      s.locationCount = counts["location"] ?? 0
      s.healthCount = counts["health"] ?? 0
      var power = Weighted(gap: 2.5)
      var cadence = Weighted(gap: 2.5)
      var hr = Weighted(gap: 10)
      let geometry = Geometry(prepared: self)
      var totals: [String: Double] = [:]
      var previewIndex = 0
      var lastPreview: [String: Double]?
      var routeSegment = -1
      let previewStride = max(1, Int(ceil(Double(locationCount) / 254)))
      try each { item in
        guard item.time <= end else { return }
        let event = item.event
        let isActive = active(item)
        switch event.kind {
        case "telemetry":
          guard isActive else {
            power.reset()
            cadence.reset()
            return
          }
          power.add(valid(event.number("humanPowerW"), 0...32766), item: item, prepared: self)
          cadence.add(valid(event.number("cadenceRpm"), 0...254), item: item, prepared: self)
        case "location":
          guard event.source == locationSource else { return }
          if !isActive {
            geometry.reset()
            return
          }
          guard geometry.accept(item) else { return }
          if geometry.startsSegment { routeSegment += 1 }
          var point = [
            "latitude": event.number("latitude")!, "longitude": event.number("longitude")!, "elapsedSeconds": item.time,
          ]
          point["segment"] = Double(routeSegment)
          point["startsSegment"] = geometry.startsSegment ? 1 : 0
          if previewIndex % previewStride == 0 && s.routePreview.count < 255 { s.routePreview.append(point) }
          lastPreview = point
          previewIndex += 1
          if let speed = WorkoutDistancePolicy.validSpeed(
            event.number("speedMps"), accuracy: event.number("speedAccuracyMps"))
          {
            s.maximumSpeedMps = max(s.maximumSpeedMps ?? speed, speed)
          }
        case "health":
          if useHeartRate(event), isActive {
            hr.add(valid(event.number("heartRateBpm"), 1...254), item: item, prepared: self)
          }
          // HealthKit builder values are cumulative snapshots. Raw associated samples are separate archival evidence.
          for key in ["activeEnergyKcal", "basalEnergyKcal"] where event.source == healthSources[key] {
            if let value = event.number(key) { totals[key] = max(totals[key] ?? value, value) }
          }
        default: break
        }
      }
      // A saved workout's final aggregate is authoritative even when finalization arrives after phone stop.
      for key in ["activeEnergyKcal", "basalEnergyKcal"] {
        if let source = healthSources[key], let final = finalTotals[source]?[key]?.1 { totals[key] = final }
      }
      if let point = lastPreview, s.routePreview.last?["elapsedSeconds"] != point["elapsedSeconds"] {
        s.routePreview.append(point)
      }
      s.averageRiderPowerW = power.average
      s.maximumRiderPowerW = power.maximum
      s.averageCadenceRpm = cadence.average
      s.maximumCadenceRpm = cadence.maximum
      s.averageHeartRateBpm = hr.average
      s.maximumHeartRateBpm = hr.maximum
      s.telemetryCoveredSeconds = power.covered
      s.heartRateCoveredSeconds = hr.covered
      s.riderWorkJoules = power.covered > 0 ? power.integral : nil
      s.gpsDistanceMeters = geometry.segmentCount > 0 ? geometry.distance : nil
      s.healthDistanceMeters = distance.healthReportedMeters
      s.distanceMeters = distance.totalMeters
      if distance.healthReportedMeters != nil {
        s.healthDistanceProvisional = distance.healthReportedProvisional
        s.healthDistanceSource = distance.healthReportedSource
        s.healthDistanceReportedAt = distance.healthReportedAt
      }
      s.distance = try JSONDecoder().decode(
        [String: WorkoutJSON].self, from: JSONSerialization.data(withJSONObject: distance.dictionary))
      if let d = s.distanceMeters, distance.coveredSeconds > 0 { s.averageSpeedMps = d / distance.coveredSeconds }
      s.activeEnergyKcal = totals["activeEnergyKcal"]
      s.basalEnergyKcal = totals["basalEnergyKcal"]
      s.ascentMeters = geometry.altitudeSegments > 0 ? geometry.ascent : nil
      s.descentMeters = geometry.altitudeSegments > 0 ? geometry.descent : nil
      let watchSyncPending = metadata.watchEnabled && metadata.watchSyncState != "received"
      s.provenance = [
        "riderPower": "cyc.humanPowerW; trapezoidal integral over adjacent active observations <=2.5 s",
        "cadence": "cyc.cadenceRpm; smoothed controller RPM", "gps": locationSource ?? "unavailable",
        "distance": distance.source.map { "\($0); \(distance.method ?? "unknown")" } ?? "unavailable",
        "heartRate": healthSources["heartRateBpm"] ?? "unavailable",
        "speed": "CoreLocation m/s; controller speed is not used for FIT",
        "rawData":
          "Canonical SQLite preserves every accepted source event; original ZIP exports that evidence and FIT contains a derived subset",
      ]
      s.completeness = [
        "distance": distance.info.selected.map { $0.partial ? "partial" : "observed" } ?? "unavailable",
        "riderPower": power.maximum == nil
          ? "unavailable" : (power.covered + 2.5 < s.timerSeconds ? "partial" : "observed"),
        "heartRate": hr.maximum == nil ? "unavailable" : (hr.covered + 10 < s.timerSeconds ? "partial" : "observed"),
        "route": !metadata.recordGPS
          ? "notRequested" : locationSource == nil ? "unavailable" : (geometry.rejected > 0 ? "partial" : "observed"),
        "watchSync": watchSyncPending ? "pending" : (metadata.watchEnabled ? "received" : "notRequired"),
      ]
      if let source = healthSources["heartRateBpm"], rawHeartRateSources.contains(source) {
        s.provenance["heartRate"] = "\(source).rawQuantity/rawSeries; builder snapshots excluded"
      }
      return s
    }
  }

  private struct Weighted {
    let gap: Double
    var previous: (Item, Double)?
    var covered = 0.0, integral = 0.0
    var maximum: Double?
    var average: Double? { covered > 0 ? integral / covered : nil }
    mutating func reset() { previous = nil }
    mutating func add(_ value: Double?, item: Item, prepared: Prepared) {
      guard let value else {
        reset()
        return
      }
      maximum = max(maximum ?? value, value)
      if let (oldItem, old) = previous {
        let duration = item.time - oldItem.time
        if duration > 0, duration <= gap, prepared.continuous(oldItem, item) {
          covered += duration
          integral += (old + value) * 0.5 * duration
        }
      }
      previous = (item, value)
    }
  }

  private final class Geometry {
    let prepared: Prepared
    var previous: Item?
    var accumulator = WorkoutGPSDistanceAccumulator()
    var altitudeAnchor: Double?
    var distance = 0.0, ascent = 0.0, descent = 0.0
    var segmentCount = 0, altitudeSegments = 0, rejected = 0
    var startsSegment = true
    init(prepared: Prepared) { self.prepared = prepared }
    func reset() {
      previous = nil
      altitudeAnchor = nil
      accumulator.reset()
    }
    func accept(_ item: Item) -> Bool {
      let event = item.event
      let fix = WorkoutGPSFix(
        time: item.time, latitude: event.number("latitude") ?? .nan,
        longitude: event.number("longitude") ?? .nan, horizontalAccuracy: event.number("horizontalAccuracyM") ?? -1,
        speed: event.number("speedMps"), speedAccuracy: event.number("speedAccuracyMps"),
        epoch: item.clockEpoch, activeInterval: prepared.intervalIndex(item), identity: event.eventId,
        timestamp: event.timestamp,
        barrier: event.payload["distanceBarrier"] == .bool(true))
      let interval = accumulator.append(fix)
      guard fix.valid else {
        rejected += 1
        reset()
        return false
      }
      startsSegment = true
      if let old = previous {
        if let interval, prepared.continuous(old, item) {
          distance += interval.meters
          segmentCount += 1
          startsSegment = false
          if let altitude = valid(item.event.number("altitudeMeters"), -500...20000),
            let accuracy = item.event.number("verticalAccuracyM"), (0...20).contains(accuracy),
            let oldAccuracy = old.event.number("verticalAccuracyM"), (0...20).contains(oldAccuracy)
          {
            if let anchor = altitudeAnchor {
              altitudeSegments += 1
              let delta = altitude - anchor
              if abs(delta) >= max(3, max(accuracy, oldAccuracy)) {
                if delta > 0 { ascent += delta } else { descent -= delta }
                altitudeAnchor = altitude
              }
            } else {
              altitudeAnchor = altitude
            }
          } else {
            altitudeAnchor = nil
          }
        } else {
          rejected += 1
          altitudeAnchor = nil
        }
      }
      if startsSegment, let altitude = valid(item.event.number("altitudeMeters"), -500...20000),
        let accuracy = item.event.number("verticalAccuracyM"), (0...20).contains(accuracy)
      {
        altitudeAnchor = altitude
      }
      previous = item
      return true
    }
  }
}
