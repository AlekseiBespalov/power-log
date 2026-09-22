import Foundation

/// Derived FIT export over a fixed canonical revision. Original observations remain unchanged.
enum WorkoutFIT {
  static func distanceKey(_ selection: String) throws -> String {
    guard selection == "auto" || WorkoutDistancePolicy.sources.contains(selection) else { throw WorkoutDistanceError.invalid("Unknown distance source") }
    return selection.replacingOccurrences(of: ":", with: "-")
  }
  static func filename(revision: Int64, seal: Int64, distanceSource: String) throws -> String {
    "PowerLog-r\(revision)-s\(seal)-d\(try distanceKey(distanceSource)).fit"
  }
  private struct SummaryRevision: Codable, Equatable {
    let revision: Int64
    let metadata: Data
    let distanceSource: String
    init(archive: WorkoutArchive, id: String, revision: Int64? = nil, distanceSource: String = "auto") throws {
      _ = try WorkoutFIT.distanceKey(distanceSource)
      self.distanceSource = distanceSource
      self.revision = try revision ?? archive.revision(id: id)
      metadata = try WorkoutCoding.encoder().encode(archive.metadata(id: id, atRevision: self.revision))
    }
  }
  private struct CachedSummary: Codable {
    var version = 4
    let revision: SummaryRevision
    let summary: WorkoutSummary
  }
  static func summarize(archive: WorkoutArchive, id: String, revision requestedRevision: Int64? = nil, distanceSource: String = "auto") throws -> WorkoutSummary {
    let revision = try SummaryRevision(archive: archive, id: id, revision: requestedRevision, distanceSource: distanceSource)
    let cache = try archive.directory(id: id).appendingPathComponent("summary-v4-r\(revision.revision)-d\(distanceKey(distanceSource)).json")
    if let values = try? cache.resourceValues(forKeys: [.fileSizeKey]),
       let size = values.fileSize, size <= 1_048_576,
       let data = try? Data(contentsOf: cache), let cached = try? JSONDecoder().decode(CachedSummary.self, from: data),
       cached.version == 4, cached.revision == revision { return cached.summary }
    let prepared = try Prepared(archive: archive, id: id, revision: revision.revision, distanceSource: distanceSource)
    let summary = try prepared.reconcile().summary
    // Late Watch events and metadata changes invalidate the revision. The cache is disposable.
    if try SummaryRevision(archive: archive, id: id, distanceSource: distanceSource) == revision {
      try? WorkoutCoding.encoder().encode(CachedSummary(revision: revision, summary: summary)).write(to: cache, options: .atomic)
    }
    return summary
  }

  @discardableResult
  static func export(archive: WorkoutArchive, id: String, to destination: URL, revision: Int64? = nil, distanceSource: String = "auto") throws -> WorkoutSummary {
    let prepared = try Prepared(archive: archive, id: id, revision: revision, distanceSource: distanceSource)
    let result = try prepared.reconcile()
    let temp = destination.deletingLastPathComponent().appendingPathComponent(".\(UUID().uuidString).fit")
    defer { try? FileManager.default.removeItem(at: temp) }
    let writer = try FITWriter(temp)
    try writer.message(0, [F.enum8(0, 4), F.u16(1, 255), F.u16(2, 1), F.u32(4, prepared.epoch)])
    try writer.timer(prepared.epoch, start: true)
    var timerIndex = 0, lapIndex = 0
    func boundaries(through time: Double) throws {
      // Summary messages are placed at their chronological lap end, not after later records.
      while true {
        let timerTime = timerIndex < prepared.timerEvents.count ? prepared.timerEvents[timerIndex].time : .infinity
        let lapTime = lapIndex < result.laps.count ? result.laps[lapIndex].end : .infinity
        guard min(timerTime, lapTime) <= time else { return }
        if timerTime <= lapTime {
          let item = prepared.timerEvents[timerIndex]
          try writer.timer(prepared.epoch + floor(item.time), start: item.start)
          timerIndex += 1
        } else {
          try writer.lap(result.laps[lapIndex], index: lapIndex, epoch: prepared.epoch)
          lapIndex += 1
        }
      }
    }
    var bin: RecordBin?
    func emitBin() throws {
      guard let value = bin else { return }
      try boundaries(through: value.second)
      let fields = value.fields(timestamp: prepared.epoch + value.second)
      if fields.count > 1 { try writer.message(20, fields) }
      bin = nil
    }
    let geometry = Geometry(prepared: prepared)
    var distancePage: [WorkoutDistancePoint] = [], distanceIndex = 0
    var distanceCursor: WorkoutDistanceCursor?
    var distanceFinished = false
    func nextDistance() throws -> WorkoutDistancePoint? {
      if distanceIndex == distancePage.count && !distanceFinished {
        distancePage = try prepared.distanceStore.page(snapshot: prepared.distance, after: distanceCursor)
        distanceIndex = 0; distanceFinished = distancePage.isEmpty
      }
      return distanceFinished ? nil : distancePage[distanceIndex]
    }
    func writeDistance(through time: Double) throws {
      while let point = try nextDistance(), point.elapsedSeconds <= time {
        let second = floor(point.elapsedSeconds)
        if bin?.second != second { try emitBin(); bin = RecordBin(second: second) }
        bin?.distance = point.distanceMeters
        distanceCursor = point.cursor; distanceIndex += 1
      }
    }
    try prepared.each { item in
      guard item.time >= 0, item.time <= prepared.end else { return }
      try writeDistance(through: item.time)
      if item.event.kind == "lifecycle" { return }
      let second = floor(item.time)
      if bin?.second != second { try emitBin(); bin = RecordBin(second: second) }
      guard prepared.active(item.time) else { return }
      let event = item.event
      if event.kind == "telemetry" {
        if let n = valid(event.number("humanPowerW"), 0...32766) { bin?.powerTotal += n; bin?.powerCount += 1 }
        if let n = valid(event.number("cadenceRpm"), 0...254) { bin?.cadenceTotal += n; bin?.cadenceCount += 1 }
      } else if event.kind == "location", event.source == prepared.locationSource,
                geometry.accept(item) {
        bin?.latitude = event.number("latitude"); bin?.longitude = event.number("longitude")
        if let n = valid(event.number("altitudeMeters"), -500...20000),
           let accuracy = event.number("verticalAccuracyM"), (0...20).contains(accuracy) { bin?.altitude = n }
        bin?.speed = WorkoutDistancePolicy.validSpeed(event.number("speedMps"), accuracy: event.number("speedAccuracyMps"))
      } else if event.kind == "health" {
        if prepared.useHeartRate(event), let n = valid(event.number("heartRateBpm"), 1...254) {
          bin?.heartRate = n
        }
      }
    }
    try writeDistance(through: prepared.end)
    try emitBin(); try boundaries(through: prepared.end)
    try writer.session(result.summary, epoch: prepared.epoch, indoor: prepared.metadata.indoor)
    try writer.message(34, [F.u32(253, prepared.epoch + floor(prepared.end)), F.u32(0, result.summary.timerSeconds * 1000),
                           F.u16(1, 1), F.enum8(2, 0), F.enum8(3, 26), F.enum8(4, 1)])
    try writer.finish()
    if FileManager.default.fileExists(atPath: destination.path) {
      _ = try FileManager.default.replaceItemAt(destination, withItemAt: temp)
    } else { try FileManager.default.moveItem(at: temp, to: destination) }
    return result.summary
  }

  private static func valid(_ value: Double?, _ range: ClosedRange<Double>) -> Double? {
    guard let value, value.isFinite, range.contains(value) else { return nil }; return value
  }

  private struct Item: Codable {
    var time: Double
    var event: WorkoutEvent
    static func before(_ a: Item, _ b: Item) -> Bool {
      if a.time != b.time { return a.time < b.time }
      if a.event.kind != b.event.kind {
        if a.event.kind == "lifecycle" { return true }
        if b.event.kind == "lifecycle" { return false }
      }
      return a.event.eventId < b.event.eventId
    }
  }
  private struct TimerEvent { var time: Double; var start: Bool }
  private struct Lap { var start: Double; var end: Double; var timer: Double; var distance: Double? }
  private struct Reconciled { var summary: WorkoutSummary; var laps: [Lap] }

  /// Short indexed pages release SQLite between reads. Every pass uses the same immutable revision.
  private final class Prepared {
    let metadata: WorkoutMetadata
    let archive: WorkoutArchive
    let revision: Int64
    let epoch: Double
    let startDate: Date
    let distanceStore: WorkoutDistanceStore
    let distance: WorkoutDistanceSnapshot
    private static let analysisColumns: [(String, String)] = [
      ("humanPowerW", "t.humanPowerW"), ("cadenceRpm", "t.cadenceRpm"),
      ("heartRateBpm", "h.heartRateBpm"), ("activeEnergyKcal", "h.activeEnergyKcal"),
      ("basalEnergyKcal", "h.basalEnergyKcal"), ("distanceMeters", "h.distanceMeters"),
      ("latitude", "l.latitude"), ("longitude", "l.longitude"),
      ("horizontalAccuracyM", "l.horizontalAccuracyM"), ("altitudeMeters", "l.altitudeMeters"),
      ("verticalAccuracyM", "l.verticalAccuracyM"), ("speedMps", "l.speedMps"),
      ("speedAccuracyMps", "l.speedAccuracyMps")]
    var end: Double = 0
    var locationSource: String?
    var healthSources: [String: String] = [:]
    var rawHeartRateSources: Set<String> = []
    var finalTotals: [String: [String: (Double, Double)]] = [:]
    var timerEvents: [TimerEvent] = []
    var intervals: [(Double, Double)] = []
    var lapEnds: [Double] = []
    var locationCount = 0
    var warnings: Set<String> = []
    var totalEvents = 0
    var counts: [String: Int] = [:]

    init(archive: WorkoutArchive, id: String, revision: Int64? = nil, distanceSource: String = "auto") throws {
      self.archive = archive
      self.revision = try revision ?? archive.revision(id: id)
      metadata = try archive.metadata(id: id, atRevision: self.revision)
      startDate = try WorkoutCoding.date(metadata.startedAt)
      epoch = floor(startDate.timeIntervalSince1970 - 631065600)
      distanceStore = WorkoutDistanceStore(store: archive.store)
      distance = try distanceStore.snapshot(id: id, revision: self.revision, selection: distanceSource)
      var lifecycle: [Item] = []
      var locations: [String: Int] = [:], healthAvailable: [String: Set<String>] = [:]
      var maxTime: Double = 0
      try analysis { item, wall in
          let event = item.event
          totalEvents += 1; counts[event.kind, default: 0] += 1
          let t = event.elapsedSeconds ?? wall
          guard t >= -1, t <= 2_678_400 else {
            warnings.insert("Events outside the supported workout timeline remain in the original archive and were excluded."); return
          }
          if event.elapsedSeconds != nil, abs(wall - t) > 2 {
            warnings.insert("UTC and elapsed clocks differ; elapsed time anchors the derived workout while original timestamps remain archived.")
          }
          maxTime = max(maxTime, item.time)
          if event.kind == "lifecycle" {
            guard lifecycle.count < 10_000 else { throw WorkoutDataError.invalid("Too many workout lifecycle events") }
            lifecycle.append(item)
          } else if event.kind == "location", Self.goodLocation(event) {
            locations[event.source, default: 0] += 1
          } else if event.kind == "health" {
            if valid(event.number("heartRateBpm"), 1...254) != nil,
               ["rawQuantity", "rawSeries"].contains(event.payload["representation"]?.string ?? "") { rawHeartRateSources.insert(event.source) }
            for key in ["heartRateBpm", "activeEnergyKcal", "basalEnergyKcal", "distanceMeters"] where event.number(key) != nil {
              if key == "heartRateBpm", valid(event.number(key), 1...254) == nil { continue }
              healthAvailable[key, default: []].insert(event.source)
              if key != "heartRateBpm", event.payload["representation"]?.string == "finalWorkoutTotal", let n = event.number(key) {
                if finalTotals[event.source]?[key] == nil || item.time >= finalTotals[event.source]![key]!.0 {
                  finalTotals[event.source, default: [:]][key] = (item.time, n)
                }
              }
            }
          }
      }
        lifecycle.sort(by: Item.before)
        let stops = lifecycle.filter { $0.event.payload["action"]?.string == "stop" }
        if let stop = stops.last(where: { $0.event.source == "phone" }) ?? stops.last {
          end = stop.time
        } else if let cutoff = metadata.stopElapsedSeconds {
          end = cutoff
        } else if let endedAt = metadata.endedAt {
          let wallEnd = try WorkoutCoding.date(endedAt).timeIntervalSince(startDate)
          end = max(0, wallEnd)
        } else { end = maxTime }
        guard end <= 2_678_400 else { throw WorkoutDataError.invalid("Workout duration exceeds 31 days") }
        var activeStart: Double? = 0
        for item in lifecycle where item.time <= end {
          switch item.event.payload["action"]?.string {
          case "pause":
            if let start = activeStart { intervals.append((start, item.time)); activeStart = nil; timerEvents.append(TimerEvent(time: item.time, start: false)) }
          case "resume":
            if activeStart == nil { activeStart = item.time; timerEvents.append(TimerEvent(time: item.time, start: true)) }
          case "lap":
            if item.time > 0, item.time < end, lapEnds.last != item.time { lapEnds.append(item.time) }
          default: break
          }
        }
        if let start = activeStart { intervals.append((start, end)) }
        timerEvents.append(TimerEvent(time: end, start: false)); lapEnds.append(end)
        locationSource = (metadata.watchEnabled && (locations["watch"] ?? 0) > 0) ? "watch" : ((locations["phone"] ?? 0) > 0 ? "phone" : ((locations["watch"] ?? 0) > 0 ? "watch" : nil))
        if let source = distance.source, source.hasPrefix("gps:") { locationSource = String(source.dropFirst(4)) }
        locationCount = locationSource.flatMap { locations[$0] } ?? 0
        for (key, values) in healthAvailable { let owner = metadata.watchEnabled ? "watch" : "phone"; healthSources[key] = values.contains(owner) ? owner : (values.contains("phone") ? "phone" : "watch") }
    }
    private func analysis(_ body: (Item, Double) throws -> Void) throws {
      let rank = "CASE WHEN m.kind='lifecycle' THEN 0 ELSE 1 END"
      let columns = Self.analysisColumns.map { "\($0.1) AS \($0.0)" }.joined(separator: ",")
      var cursor: PowerLogRow?
      while true {
        let page = try archive.store.read(priority: .background) { db in
          try archive.store.requireWorkoutAvailable(id: metadata.id)
          var sql = "SELECT m.id,m.event_id,m.kind,m.source,m.elapsed_seconds,m.original_elapsed_seconds,o.original_timestamp,o.utc_seconds,o.representation,lc.action,\(rank) AS rank,\(columns),json_type(CAST(o.extra AS TEXT),'$.distanceBarrier')='true' AS distance_barrier FROM collection_memberships m INDEXED BY membership_export_time JOIN observations o ON o.id=m.observation_id LEFT JOIN telemetry_frames t ON t.observation_id=m.observation_id LEFT JOIN locations l ON l.observation_id=m.observation_id LEFT JOIN health_samples h ON h.observation_id=m.observation_id LEFT JOIN lifecycle_records lc ON lc.observation_id=m.observation_id WHERE m.collection_id=? AND m.revision<=? AND " + PowerLogStore.selectedMembershipSQL
          var values: [PowerLogSQLValue] = [.text(metadata.id), .integer(revision), .integer(revision), .integer(revision)]
          if let cursor {
            sql += " AND (m.elapsed_seconds,\(rank),m.event_id,m.id)>(?,?,?,?)"
            values += [cursor["elapsed_seconds"], cursor["rank"], cursor["event_id"], cursor["id"]]
          }
          sql += " ORDER BY m.elapsed_seconds,\(rank),m.event_id,m.id LIMIT 256"
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
            let originalElapsed = row.double("original_elapsed_seconds")
            let wall = row.double("utc_seconds")! - startDate.timeIntervalSince1970
            let event = WorkoutEvent(storedEventID: row.string("event_id")!, workoutID: metadata.id,
              kind: row.string("kind")!, source: row.string("source")!, originalTimestamp: row.string("original_timestamp")!,
              elapsedSeconds: originalElapsed, payload: payload)
            try body(Item(time: max(0, originalElapsed ?? wall), event: event), wall)
          }
        }
        cursor = page.last
      }
    }
    func each(_ body: (Item) throws -> Void) throws {
      try analysis { item, wall in
        let time = item.event.elapsedSeconds ?? wall
        guard time >= -1, time <= 2_678_400 else { return }
        try body(item)
      }
    }
    static func goodLocation(_ event: WorkoutEvent) -> Bool {
      guard let accuracy = event.number("horizontalAccuracyM"), (0...50).contains(accuracy) else { return false }
      return true
    }
    private func intervalIndex(_ time: Double) -> Int? {
      var low = 0, high = intervals.count
      while low < high { let mid = (low + high) / 2; if intervals[mid].0 <= time { low = mid + 1 } else { high = mid } }
      guard low > 0, time < intervals[low - 1].1 || (time == end && time == intervals[low - 1].1) else { return nil }
      return low - 1
    }
    func active(_ time: Double) -> Bool { intervalIndex(time) != nil }
    func useHeartRate(_ event: WorkoutEvent) -> Bool {
      guard event.source == healthSources["heartRateBpm"] else { return false }
      return !rawHeartRateSources.contains(event.source) || ["rawQuantity", "rawSeries"].contains(event.payload["representation"]?.string ?? "")
    }
    func continuous(_ a: Double, _ b: Double) -> Bool {
      guard let index = intervalIndex(a) else { return false }
      return b >= a && b <= intervals[index].1
    }
    func timer(_ a: Double, _ b: Double) -> Double {
      intervals.reduce(0) { $0 + max(0, min(b, $1.1) - max(a, $1.0)) }
    }
    func reconcile() throws -> Reconciled {
      var s = WorkoutSummary(id: metadata.id, startedAt: metadata.startedAt, endedAt: WorkoutCoding.timestamp(startDate.addingTimeInterval(end)))
      s.elapsedSeconds = end; s.timerSeconds = timer(0, end); s.eventCount = totalEvents; s.lapCount = lapEnds.count
      s.telemetryCount = counts["telemetry"] ?? 0; s.locationCount = counts["location"] ?? 0; s.healthCount = counts["health"] ?? 0
      var power = Weighted(gap: 2.5), cadence = Weighted(gap: 2.5), hr = Weighted(gap: 10)
      let geometry = Geometry(prepared: self)
      var totals: [String: Double] = [:], lastTotals: [String: Double] = [:]
      var laps: [Lap] = [], lapStart = 0.0
      var previewIndex = 0, lastPreview: [String: Double]?, routeSegment = -1
      let previewStride = max(1, Int(ceil(Double(locationCount) / 254)))
      try each { item in
        guard item.time <= end else { return }
        let event = item.event, isActive = active(item.time)
        switch event.kind {
        case "telemetry":
          guard isActive else { power.reset(); cadence.reset(); return }
          let p = valid(event.number("humanPowerW"), 0...32766), c = valid(event.number("cadenceRpm"), 0...254)
          if p == nil || c == nil { warnings.insert("Some CYC values are outside FIT athlete-sensor ranges and remain only in the original archive.") }
          power.add(p, time: item.time, prepared: self); cadence.add(c, time: item.time, prepared: self)
        case "location":
          guard event.source == locationSource else { return }
          if !isActive { geometry.reset(); return }
          guard geometry.accept(item) else { return }
          if geometry.startsSegment { routeSegment += 1 }
          var point = ["latitude": event.number("latitude")!, "longitude": event.number("longitude")!, "elapsedSeconds": item.time]
          point["segment"] = Double(routeSegment)
          point["startsSegment"] = geometry.startsSegment ? 1 : 0
          if previewIndex % previewStride == 0 && s.routePreview.count < 255 { s.routePreview.append(point) }
          lastPreview = point; previewIndex += 1
          if let speed = WorkoutDistancePolicy.validSpeed(event.number("speedMps"), accuracy: event.number("speedAccuracyMps")) { s.maximumSpeedMps = max(s.maximumSpeedMps ?? speed, speed) }
        case "health":
          if useHeartRate(event), isActive {
            hr.add(valid(event.number("heartRateBpm"), 1...254), time: item.time, prepared: self)
          }
          // HealthKit builder values are cumulative snapshots. Raw associated samples are separate archival evidence.
          for key in ["activeEnergyKcal", "basalEnergyKcal"] where event.source == healthSources[key] {
            if let value = event.number(key) {
              if let previous = lastTotals[key], value < previous { warnings.insert("A cumulative HealthKit quantity decreased; totals use its observed maximum and do not add snapshots.") }
              totals[key] = max(totals[key] ?? value, value); lastTotals[key] = value
            }
          }
        default: break
        }
      }
      // A saved workout's final aggregate is authoritative even when finalization arrives after phone stop.
      for key in ["activeEnergyKcal", "basalEnergyKcal"] {
        if let source = healthSources[key], let final = finalTotals[source]?[key]?.1 {
          if let live = totals[key], abs(live - final) > 0.001 {
            warnings.insert("Final saved HealthKit totals replace provisional cumulative snapshots; raw values remain archived.")
          }
          totals[key] = final
        }
      }
      for lapEnd in lapEnds {
        let range = try distanceStore.range(snapshot: distance, start: lapStart, end: lapEnd)
        laps.append(Lap(start: lapStart, end: lapEnd, timer: timer(lapStart, lapEnd),
          distance: range.unresolvedBoundary ? nil : range.distanceMeters))
        if range.unresolvedBoundary { warnings.insert("A Health distance interval crosses a lap boundary; that lap distance is unavailable.") }
        lapStart = lapEnd
      }
      if let point = lastPreview, s.routePreview.last?["elapsedSeconds"] != point["elapsedSeconds"] { s.routePreview.append(point) }
      s.averageRiderPowerW = power.average; s.maximumRiderPowerW = power.maximum
      s.averageCadenceRpm = cadence.average; s.maximumCadenceRpm = cadence.maximum
      s.averageHeartRateBpm = hr.average; s.maximumHeartRateBpm = hr.maximum
      s.telemetryCoveredSeconds = power.covered; s.heartRateCoveredSeconds = hr.covered
      s.riderWorkJoules = power.covered > 0 ? power.integral : nil
      s.gpsDistanceMeters = geometry.segmentCount > 0 ? geometry.distance : nil
      s.healthDistanceMeters = distance.healthReportedMeters; s.distanceMeters = distance.totalMeters
      if distance.healthReportedMeters != nil {
        s.healthDistanceProvisional = distance.healthReportedProvisional
        s.healthDistanceSource = distance.healthReportedSource
        s.healthDistanceReportedAt = distance.healthReportedAt
      }
      s.distance = try JSONDecoder().decode([String: WorkoutJSON].self, from: JSONSerialization.data(withJSONObject: distance.dictionary))
      if let d = s.distanceMeters, distance.coveredSeconds > 0 { s.averageSpeedMps = d / distance.coveredSeconds }
      if distance.info.selected?.partial == true { warnings.insert("Distance covers only recorded intervals. Average speed uses that covered time.") }
      if distance.estimated { warnings.insert("Controller distance is an estimate based on configured wheel speed.") }
      s.activeEnergyKcal = totals["activeEnergyKcal"]; s.basalEnergyKcal = totals["basalEnergyKcal"]
      s.ascentMeters = geometry.altitudeSegments > 0 ? geometry.ascent : nil
      s.descentMeters = geometry.altitudeSegments > 0 ? geometry.descent : nil
      if let gps = s.gpsDistanceMeters, let health = s.healthDistanceMeters, abs(gps - health) > max(100, health * 0.1) {
        warnings.insert("GPS geometry and Health reported distance differ by more than 10% / 100 m; Ride distance uses the selected source.")
      }
      if s.telemetryCoveredSeconds + 2.5 < s.timerSeconds { warnings.insert("Rider power has uncovered time; averages and work use observed intervals only.") }
      if locationSource == nil && metadata.recordsGPS { warnings.insert("No GPS locations with usable accuracy were available.") }
      if s.maximumHeartRateBpm == nil { warnings.insert("Heart rate was unavailable.") }
      let watchSyncPending = metadata.watchEnabled && metadata.watchSyncState != "received"
      if watchSyncPending { warnings.insert("Watch synchronization is pending; later events can change this summary/export.") }
      if geometry.rejected > 0 { warnings.insert("GPS fixes/segments with poor accuracy, implausible speed, pauses or gaps were excluded from geometry.") }
      s.warnings = Array(Set(metadata.warnings).union(warnings)).sorted()
      s.provenance = ["riderPower": "cyc.humanPowerW; trapezoidal integral over adjacent active observations <=2.5 s",
                      "cadence": "cyc.cadenceRpm; smoothed controller RPM", "gps": locationSource ?? "unavailable",
                      "distance": distance.source.map { "\($0); \(distance.method ?? "unknown"); policy \(distance.policyVersion)" } ?? "unavailable",
                      "heartRate": healthSources["heartRateBpm"] ?? "unavailable", "speed": "CoreLocation m/s; controller speed is not used for FIT",
                      "rawData": "Canonical SQLite preserves every accepted source event; original ZIP exports that evidence and FIT contains a derived subset"]
      s.completeness = ["distance": distance.info.selected.map { $0.partial ? "partial" : "observed" } ?? "unavailable", "riderPower": power.maximum == nil ? "unavailable" : (power.covered + 2.5 < s.timerSeconds ? "partial" : "observed"),
                        "heartRate": hr.maximum == nil ? "unavailable" : (hr.covered + 10 < s.timerSeconds ? "partial" : "observed"),
                        "route": !metadata.recordsGPS ? "notRequested" : locationSource == nil ? "unavailable" : (geometry.rejected > 0 ? "partial" : "observed"),
                        "watchSync": watchSyncPending ? "pending" : (metadata.watchEnabled ? "received" : "notRequired")]
      if let source = healthSources["heartRateBpm"], rawHeartRateSources.contains(source) {
        s.provenance["heartRate"] = "\(source).rawQuantity/rawSeries; builder snapshots excluded"
      }
      return Reconciled(summary: s, laps: laps)
    }
  }

  private struct Weighted {
    let gap: Double
    var previous: (Double, Double)?
    var covered = 0.0, integral = 0.0
    var maximum: Double?
    var average: Double? { covered > 0 ? integral / covered : nil }
    mutating func reset() { previous = nil }
    mutating func add(_ value: Double?, time: Double, prepared: Prepared) {
      guard let value else { reset(); return }
      maximum = max(maximum ?? value, value)
      if let (t, old) = previous, time > t, time - t <= gap, prepared.continuous(t, time) {
        covered += time - t; integral += (old + value) * 0.5 * (time - t)
      }
      previous = (time, value)
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
    func reset() { previous = nil; altitudeAnchor = nil; accumulator.reset() }
    func accept(_ item: Item) -> Bool {
      let event = item.event
      let fix = WorkoutGPSFix(time: item.time, latitude: event.number("latitude") ?? .nan,
        longitude: event.number("longitude") ?? .nan, horizontalAccuracy: event.number("horizontalAccuracyM") ?? -1,
        speed: event.number("speedMps"), speedAccuracy: event.number("speedAccuracyMps"),
        activeInterval: prepared.active(item.time) ? 0 : nil, identity: event.eventId, timestamp: event.timestamp,
        barrier: event.payload["distanceBarrier"] == .bool(true))
      let interval = accumulator.append(fix)
      guard fix.valid else { rejected += 1; reset(); return false }
      startsSegment = true
      if let old = previous {
        if let interval, prepared.continuous(old.time, item.time) {
          distance += interval.meters; segmentCount += 1; startsSegment = false
          if let altitude = valid(item.event.number("altitudeMeters"), -500...20000),
             let accuracy = item.event.number("verticalAccuracyM"), (0...20).contains(accuracy),
             let oldAccuracy = old.event.number("verticalAccuracyM"), (0...20).contains(oldAccuracy) {
            if let anchor = altitudeAnchor {
              altitudeSegments += 1
              let delta = altitude - anchor
              if abs(delta) >= max(3, max(accuracy, oldAccuracy)) {
                if delta > 0 { ascent += delta } else { descent -= delta }
                altitudeAnchor = altitude
              }
            } else { altitudeAnchor = altitude }
          } else { altitudeAnchor = nil }
        } else { rejected += 1; altitudeAnchor = nil }
      }
      if startsSegment, let altitude = valid(item.event.number("altitudeMeters"), -500...20000),
         let accuracy = item.event.number("verticalAccuracyM"), (0...20).contains(accuracy) { altitudeAnchor = altitude }
      previous = item; return true
    }
  }

  private struct RecordBin {
    let second: Double
    var powerTotal = 0.0, cadenceTotal = 0.0
    var powerCount = 0, cadenceCount = 0
    var latitude: Double?, longitude: Double?, altitude: Double?, speed: Double?, distance: Double?, heartRate: Double?
    func fields(timestamp: Double) -> [F] {
      var f = [F.u32(253, timestamp)]
      if let latitude, let longitude {
        let longitudeUnits = longitude / 180 * 2147483648
        f.append(F.s32(0, latitude / 180 * 2147483648))
        f.append(F.s32(1, longitudeUnits.rounded() >= 2147483647 ? -2147483648 : longitudeUnits))
      }
      if let altitude { f.append(F.u32(78, (altitude + 500) * 5)) }
      if let speed { f.append(F.u32(73, speed * 1000)) }
      if let distance { f.append(F.u32(5, distance * 100)) }
      if let heartRate { f.append(F.u8(3, heartRate)) }
      if powerCount > 0 { f.append(F.u16(7, powerTotal / Double(powerCount))) }
      if cadenceCount > 0 { f.append(F.u8(4, cadenceTotal / Double(cadenceCount))) }
      return f
    }
  }

  private struct F {
    let number: UInt8, type: UInt8, bytes: [UInt8]
    static func value(_ n: Int, _ type: UInt8, _ value: Double?, _ size: Int, signed: Bool = false) -> F {
      let maximum = signed ? 2147483646.0 : pow(2, Double(size * 8)) - 2
      let minimum = signed ? -2147483648.0 : 0
      let integer: UInt64
      if let value, value.isFinite, value.rounded() >= minimum, value.rounded() <= maximum {
        integer = UInt64(bitPattern: Int64(value.rounded()))
      } else { integer = signed ? 0x7fffffff : UInt64(pow(2, Double(size * 8)) - 1) }
      return F(number: UInt8(n), type: type, bytes: (0..<size).map { UInt8(truncatingIfNeeded: integer >> ($0 * 8)) })
    }
    static func enum8(_ n: Int, _ v: Double?) -> F { value(n, 0, v, 1) }
    static func u8(_ n: Int, _ v: Double?) -> F { value(n, 2, v, 1) }
    static func u16(_ n: Int, _ v: Double?) -> F { value(n, 0x84, v, 2) }
    static func u32(_ n: Int, _ v: Double?) -> F { value(n, 0x86, v, 4) }
    static func s32(_ n: Int, _ v: Double?) -> F { value(n, 0x85, v, 4, signed: true) }
  }

  private final class FITWriter {
    let url: URL
    let handle: FileHandle
    private var signature: [UInt8] = []
    init(_ url: URL) throws {
      self.url = url
      try Data(repeating: 0, count: 14).write(to: url)
      handle = try FileHandle(forUpdating: url); try handle.seekToEnd()
    }
    deinit { try? handle.close() }
    func message(_ global: UInt16, _ fields: [F]) throws {
      var definition: [UInt8] = [0x40, 0, 0, UInt8(truncatingIfNeeded: global), UInt8(global >> 8), UInt8(fields.count)]
      for f in fields { definition += [f.number, UInt8(f.bytes.count), f.type] }
      if definition != signature { try handle.write(contentsOf: Data(definition)); signature = definition }
      var bytes: [UInt8] = [0]
      for f in fields { bytes += f.bytes }
      try handle.write(contentsOf: Data(bytes))
    }
    func timer(_ timestamp: Double, start: Bool) throws {
      try message(21, [F.u32(253, timestamp), F.enum8(0, 0), F.enum8(1, start ? 0 : 4), F.u32(3, 0)])
    }
    func lap(_ lap: Lap, index: Int, epoch: Double) throws {
      try message(19, [F.u32(253, epoch + floor(lap.end)), F.u16(254, Double(index)), F.enum8(0, 9), F.enum8(1, 1),
                       F.u32(2, epoch + floor(lap.start)), F.u32(7, (lap.end - lap.start) * 1000), F.u32(8, lap.timer * 1000),
                       F.u32(9, lap.distance.map { $0 * 100 }), F.enum8(24, index == 0 ? 0 : 1), F.enum8(25, 2)])
    }
    func session(_ s: WorkoutSummary, epoch: Double, indoor: Bool) throws {
      let partial: Bool
      if case .object(let selected) = s.distance?["selected"] { partial = selected["partial"] == .bool(true) } else { partial = false }
      try message(18, [F.u32(253, epoch + floor(s.elapsedSeconds)), F.u16(254, 0), F.enum8(0, 8), F.enum8(1, 1),
                       F.u32(2, epoch), F.enum8(5, 2), F.enum8(6, indoor ? 6 : 28),
                       F.u32(7, s.elapsedSeconds * 1000), F.u32(8, s.timerSeconds * 1000),
                       F.u32(9, s.distanceMeters.map { $0 * 100 }), F.u16(11, s.activeEnergyKcal),
                       F.u16(14, partial ? nil : s.averageSpeedMps.map { $0 * 1000 }), F.u16(15, s.maximumSpeedMps.map { $0 * 1000 }),
                       F.u8(16, s.averageHeartRateBpm), F.u8(17, s.maximumHeartRateBpm),
                       F.u8(18, s.averageCadenceRpm), F.u8(19, s.maximumCadenceRpm),
                       F.u16(20, s.averageRiderPowerW), F.u16(21, s.maximumRiderPowerW),
                       F.u16(22, s.ascentMeters), F.u16(23, s.descentMeters), F.u16(25, 0), F.u16(26, Double(s.lapCount)),
                       F.u32(48, s.riderWorkJoules)])
    }
    func finish() throws {
      let size = try handle.offset()
      guard size >= 14, size - 14 < UInt64(UInt32.max) else { throw WorkoutDataError.invalid("FIT file exceeds size limit") }
      let length = UInt32(size - 14)
      // Profile 21.214 (official Garmin SDK); all encoded fields are standard activity fields.
      var header = Data([14, 0x20, 0xde, 0x52, UInt8(truncatingIfNeeded: length), UInt8(truncatingIfNeeded: length >> 8),
                         UInt8(truncatingIfNeeded: length >> 16), UInt8(truncatingIfNeeded: length >> 24), 46, 70, 73, 84])
      let headerCRC = Self.crc(header); header.append(UInt8(truncatingIfNeeded: headerCRC)); header.append(UInt8(headerCRC >> 8))
      try handle.seek(toOffset: 0); try handle.write(contentsOf: header); try handle.seek(toOffset: 0)
      var crc: UInt16 = 0
      while let bytes = try handle.read(upToCount: 65_536), !bytes.isEmpty { crc = Self.crc(bytes, initial: crc) }
      try handle.write(contentsOf: Data([UInt8(truncatingIfNeeded: crc), UInt8(crc >> 8)])); try handle.synchronize(); try handle.close()
    }
    static func crc(_ data: Data, initial: UInt16 = 0) -> UInt16 {
      var crc = initial
      for byte in data {
        crc ^= UInt16(byte)
        for _ in 0..<8 { crc = (crc & 1) == 1 ? (crc >> 1) ^ 0xa001 : crc >> 1 }
      }
      return crc
    }
  }
}
