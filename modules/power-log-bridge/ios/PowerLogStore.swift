import Foundation
import PowerLogCore
import SQLite3
import CryptoKit

enum PowerLogStorageLimits {
  static let pageRows = 512
  static let pageBytes = 8 * 1024 * 1024
  static let transactionRecords = 512
  static let extensionBytes = 32_768
  static let recordBytes = 65_536
  static let transactionBytes = 4 * 1024 * 1024
  static let changeRows = 512
  static let pendingJobs = 64
  static let maximumCollectionRecords: Int64 = 8_000_000
  static let maximumRevision: Int64 = 9_007_199_254_740_991
  static let derivedValueBytes = 4 * 1024 * 1024
}

enum PowerLogRevision {
  static func validate(_ value: Int64) throws {
    guard (0...PowerLogStorageLimits.maximumRevision).contains(value) else {
      throw PowerLogStorageError.invalid("Recording revision exceeds the exact storage range")
    }
  }

  static func next(_ value: Int64) throws -> Int64 {
    try validate(value)
    guard value < PowerLogStorageLimits.maximumRevision else {
      throw PowerLogStorageError.invalid("Recording revision limit reached")
    }
    return value + 1
  }
}

enum PowerLogStorageError: Error, LocalizedError {
  case sqlite(Int32, String), invalid(String), conflict(String), missing(String), deleted(String), busy, revision(
    expected: Int64, actual: Int64)
  var bridgeCode: String? {
    switch self {
    case .deleted: return "ERR_RIDE_DELETED"
    case .busy: return "ERR_STORAGE_BUSY"
    default: return nil
    }
  }
  var errorDescription: String? {
    switch self {
    case .sqlite(let code, let message): return "Storage error \(code): \(message)"
    case .invalid(let message), .conflict(let message), .missing(let message): return message
    case .deleted: return "This ride was deleted from Power Log."
    case .busy: return "Storage work queue is full. Retry the pending operation."
    case .revision(let expected, let actual):
      return "Source revision changed from \(expected) to \(actual). Retry the read."
    }
  }
}

enum PowerLogSQLValue: Equatable {
  case integer(Int64), real(Double), text(String), blob(Data), null
  var int: Int64? {
    if case .integer(let v) = self { return v }
    return nil
  }
  var double: Double? {
    switch self {
    case .integer(let v): return Double(v)
    case .real(let v): return v
    default: return nil
    }
  }
  var string: String? {
    if case .text(let v) = self { return v }
    return nil
  }
  var data: Data? {
    if case .blob(let v) = self { return v }
    return nil
  }
  static func optional(_ value: Double?) -> Self { value.map(Self.real) ?? .null }
  static func optional(_ value: String?) -> Self { value.map(Self.text) ?? .null }
}

struct PowerLogRow {
  let values: [String: PowerLogSQLValue]
  subscript(_ key: String) -> PowerLogSQLValue { values[key] ?? .null }
  func int(_ key: String) -> Int64? { self[key].int }
  func double(_ key: String) -> Double? { self[key].double }
  func string(_ key: String) -> String? { self[key].string }
  func data(_ key: String) -> Data? { self[key].data }
}

enum PowerLogJobPriority: Int { case interactive = 0, capture = 1, normal = 2, background = 3 }

enum PowerLogCanonicalization {
  private static let lock = NSLock()
  private static var count = 0
  static var countForTesting: Int {
    lock.lock()
    defer { lock.unlock() }
    return count
  }
  static func run<T>(_ body: () throws -> T) rethrows -> T {
    lock.lock()
    count += 1
    lock.unlock()
    return try body()
  }
}

/// Content identity of one physical original, shared by every membership of that original.
struct PowerLogObservationIdentity: Equatable {
  // Envelope fields absent from the identity form: event and collection IDs, elapsed time and schema version.
  private static let envelopeBytes = 256
  let digest: Data
  let canonicalBytes: Int
  init(_ event: WorkoutEvent) throws {
    var payload = event.payload
    for name in PowerLogStore.mappingKeys { payload.removeValue(forKey: name) }
    payload.removeValue(forKey: "timestamp")
    payload["_timestamp"] = .string(event.timestamp)
    payload["_kind"] = .string(event.kind)
    payload["_source"] = .string(event.source)
    let canonical = try PowerLogCanonicalization.run {
      try WatchProtocol.shared.canonicalizeJson(bytes: WorkoutCoreCodec.bytes(WorkoutCoding.encoder().encode(payload)))
    }
    let bytes = WorkoutCoreCodec.data(canonical)
    digest = Data(SHA256.hash(data: bytes))
    canonicalBytes = bytes.count
  }
  var eventBytes: Int { canonicalBytes + Self.envelopeBytes }
}

/// Originals validated and identified before the store transaction that appends them.
struct PowerLogPreparedBatch {
  let entries: [(event: WorkoutEvent, identity: PowerLogObservationIdentity)]
  let producer: String?
  let firstSequence: Int64?
  let encodedBytes: Int

  init(_ events: [WorkoutEvent], producer: String? = nil, firstSequence: Int64? = nil) throws {
    guard events.count <= PowerLogStorageLimits.transactionRecords else {
      throw PowerLogStorageError.invalid("Observation batch exceeds 512 records")
    }
    self.producer = producer
    self.firstSequence = firstSequence
    guard !events.isEmpty else {
      entries = []
      encodedBytes = 0
      return
    }
    guard producer == nil || !(producer!.isEmpty) && producer!.utf8.count <= 128 else {
      throw PowerLogStorageError.invalid("Invalid archival producer")
    }
    if let firstSequence {
      guard firstSequence > 0, firstSequence <= Int64.max - Int64(events.count - 1) else {
        throw PowerLogStorageError.invalid("Invalid archival sequence range")
      }
    }
    if firstSequence != nil && producer == nil {
      throw PowerLogStorageError.invalid("Explicit sequence requires a producer")
    }
    var encodedBytes = 0
    for event in events { encodedBytes += try event.validate() }
    self.encodedBytes = encodedBytes
    entries = try events.map { ($0, try PowerLogObservationIdentity($0)) }
  }
}

/// This handle is valid only inside the owning store's read/transaction closure.
/// Access is checked at runtime so accidentally retained handles cannot race SQLite.
final class PowerLogDatabase {
  fileprivate let handle: OpaquePointer
  fileprivate var assertExecutor: () -> Void = {}
  private var statements: [String: OpaquePointer] = [:]
  var queryObserverForTesting: ((String, [PowerLogSQLValue]) -> Void)?
  private var order: [String] = []
  private var drained = (steps: 0, reprepares: 0)
  fileprivate init(_ handle: OpaquePointer) { self.handle = handle }
  deinit { for statement in statements.values { sqlite3_finalize(statement) } }
  private func drain(_ s: OpaquePointer) -> (steps: Int, reprepares: Int) {
    let work = (
      steps: Int(sqlite3_stmt_status(s, SQLITE_STMTSTATUS_VM_STEP, 1)),
      reprepares: Int(sqlite3_stmt_status(s, SQLITE_STMTSTATUS_REPREPARE, 1))
    )
    drained.steps += work.steps
    drained.reprepares += work.reprepares
    return work
  }
  var workForTesting: (steps: Int, reprepares: Int) {
    assertExecutor()
    for statement in statements.values { _ = drain(statement) }
    return drained
  }
  private func statement(_ sql: String, _ values: [PowerLogSQLValue]) throws -> OpaquePointer {
    queryObserverForTesting?(sql, values)
    assertExecutor()
    let s: OpaquePointer
    if let cached = statements[sql] {
      s = cached
    } else {
      var prepared: OpaquePointer?
      guard sqlite3_prepare_v2(handle, sql, -1, &prepared, nil) == SQLITE_OK, let prepared else { throw error() }
      s = prepared
      if order.count >= 64, let first = order.first {
        order.removeFirst()
        if let old = statements.removeValue(forKey: first) {
          _ = drain(old)
          sqlite3_finalize(old)
        }
      }
      order.append(sql)
      statements[sql] = s
    }
    sqlite3_reset(s)
    sqlite3_clear_bindings(s)
    guard sqlite3_bind_parameter_count(s) == Int32(values.count) else {
      throw PowerLogStorageError.invalid("SQL parameter count mismatch")
    }
    let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    for (offset, value) in values.enumerated() {
      let i = Int32(offset + 1)
      let rc: Int32
      switch value {
      case .integer(let v): rc = sqlite3_bind_int64(s, i, v)
      case .real(let v):
        guard v.isFinite else { throw PowerLogStorageError.invalid("Non-finite storage value") }
        rc = sqlite3_bind_double(s, i, v)
      case .text(let v): rc = v.withCString { sqlite3_bind_text(s, i, $0, -1, transient) }
      case .blob(let v):
        if v.isEmpty {
          rc = sqlite3_bind_zeroblob(s, i, 0)
        } else {
          rc = v.withUnsafeBytes { sqlite3_bind_blob(s, i, $0.baseAddress, Int32(v.count), transient) }
        }
      case .null: rc = sqlite3_bind_null(s, i)
      }
      guard rc == SQLITE_OK else { throw error() }
    }
    return s
  }
  fileprivate func error() -> PowerLogStorageError {
    .sqlite(sqlite3_extended_errcode(handle), String(cString: sqlite3_errmsg(handle)))
  }
  @discardableResult
  func execute(_ sql: String, _ values: [PowerLogSQLValue] = []) throws -> Int {
    let s = try statement(sql, values)
    defer { sqlite3_reset(s) }
    guard sqlite3_step(s) == SQLITE_DONE else { throw error() }
    return Int(sqlite3_changes(handle))
  }
  func rows(
    _ sql: String, _ values: [PowerLogSQLValue] = [], limit: Int = PowerLogStorageLimits.pageRows,
    observeWork: ((Int) -> Void)? = nil, admit: ((PowerLogStatementRow) throws -> Bool)? = nil
  ) throws
    -> [PowerLogRow]
  {
    guard (1...PowerLogStorageLimits.pageRows).contains(limit) else {
      throw PowerLogStorageError.invalid("Storage page limit must be 1...512")
    }
    let s = try statement(sql, values)
    if observeWork != nil { _ = drain(s) }
    defer {
      if let observeWork { observeWork(drain(s).steps) }
      sqlite3_reset(s)
    }
    let keys = (0..<sqlite3_column_count(s)).map { String(cString: sqlite3_column_name(s, $0)) }
    var result: [PowerLogRow] = []
    var pageBytes = 0
    while true {
      let rc = sqlite3_step(s)
      if rc == SQLITE_DONE { return result }
      guard rc == SQLITE_ROW else { throw error() }
      guard result.count < limit else {
        throw PowerLogStorageError.invalid("Unbounded database read rejected; use keyset pages")
      }
      if let admit, try !admit(PowerLogStatementRow(statement: s)) { return result }
      var row: [String: PowerLogSQLValue] = [:]
      for i in 0..<sqlite3_column_count(s) {
        pageBytes += max(8, Int(sqlite3_column_bytes(s, i)))
        guard pageBytes <= PowerLogStorageLimits.pageBytes else {
          throw PowerLogStorageError.invalid("Database page byte limit exceeded; request fewer rows")
        }
        let key = keys[Int(i)]
        let value: PowerLogSQLValue
        switch sqlite3_column_type(s, i) {
        case SQLITE_INTEGER: value = .integer(sqlite3_column_int64(s, i))
        case SQLITE_FLOAT: value = .real(sqlite3_column_double(s, i))
        case SQLITE_TEXT: value = .text(String(cString: sqlite3_column_text(s, i)))
        case SQLITE_BLOB:
          let count = Int(sqlite3_column_bytes(s, i))
          guard count <= PowerLogStorageLimits.derivedValueBytes else {
            throw PowerLogStorageError.invalid("Stored value exceeds read byte limit")
          }
          value = count == 0 ? .blob(Data()) : .blob(Data(bytes: sqlite3_column_blob(s, i)!, count: count))
        default: value = .null
        }
        row[key] = value
      }
      result.append(PowerLogRow(values: row))
    }
  }
  @discardableResult
  func scan(
    _ sql: String, _ values: [PowerLogSQLValue] = [], limit: Int,
    observeWork: ((_ steps: Int, _ reprepares: Int) -> Void)? = nil, _ body: (PowerLogStatementRow) throws -> Bool
  ) throws -> Int {
    guard (1...PowerLogStorageLimits.pageRows).contains(limit) else {
      throw PowerLogStorageError.invalid("Storage page limit must be 1...512")
    }
    let s = try statement(sql, values)
    if observeWork != nil { _ = drain(s) }
    defer {
      if let observeWork {
        let work = drain(s)
        observeWork(work.steps, work.reprepares)
      }
      sqlite3_reset(s)
    }
    let columns = sqlite3_column_count(s)
    var count = 0
    var pageBytes = 0
    while true {
      let rc = sqlite3_step(s)
      if rc == SQLITE_DONE { return count }
      guard rc == SQLITE_ROW else { throw error() }
      guard count < limit else {
        throw PowerLogStorageError.invalid("Unbounded database read rejected; use keyset pages")
      }
      for i in 0..<columns {
        let type = sqlite3_column_type(s, i)
        pageBytes += type == SQLITE_TEXT || type == SQLITE_BLOB ? max(8, Int(sqlite3_column_bytes(s, i))) : 8
      }
      guard pageBytes <= PowerLogStorageLimits.pageBytes else {
        throw PowerLogStorageError.invalid("Database page byte limit exceeded; request fewer rows")
      }
      count += 1
      guard try body(PowerLogStatementRow(statement: s)) else { return count }
    }
  }
  func scalarInt(_ sql: String, _ values: [PowerLogSQLValue] = []) throws -> Int64? {
    let s = try statement(sql, values)
    defer { sqlite3_reset(s) }
    let rc = sqlite3_step(s)
    if rc == SQLITE_DONE { return nil }
    guard rc == SQLITE_ROW else { throw error() }
    return sqlite3_column_type(s, 0) == SQLITE_NULL ? nil : sqlite3_column_int64(s, 0)
  }
  var lastInsertedID: Int64 {
    assertExecutor()
    return sqlite3_last_insert_rowid(handle)
  }

  func get(namespace: String, key: String) throws -> Data? {
    let query = try statement(
      "SELECT value FROM durable_records WHERE namespace=? AND key=?", [.text(namespace), .text(key)])
    defer { sqlite3_reset(query) }
    let result = sqlite3_step(query)
    if result == SQLITE_DONE { return nil }
    guard result == SQLITE_ROW else { throw error() }
    let count = Int(sqlite3_column_bytes(query, 0))
    let maximum = namespace.hasPrefix("publication-") ? 64 * 1024 * 1024 : PowerLogStorageLimits.derivedValueBytes
    guard count <= maximum else { throw PowerLogStorageError.invalid("Durable record exceeds bounds") }
    guard count > 0, let pointer = sqlite3_column_blob(query, 0) else { return Data() }
    return Data(bytes: pointer, count: count)
  }
  func put(namespace: String, key: String, ride: String? = nil, value: Data, immutable: Bool = false) throws {
    guard !namespace.isEmpty, namespace.utf8.count <= 128, !key.isEmpty, key.utf8.count <= 512,
      value.count <= (namespace.hasPrefix("publication-") ? 64 * 1024 * 1024 : PowerLogStorageLimits.derivedValueBytes)
    else { throw PowerLogStorageError.invalid("Durable record exceeds bounds") }
    let ride = try ride.map(WorkoutCoding.id)
    if let previous = try rows(
      "SELECT ride FROM durable_records WHERE namespace=? AND key=?",
      [.text(namespace), .text(key)], limit: 1
    ).first {
      guard previous.string("ride") == ride else {
        throw PowerLogStorageError.conflict("Durable record ride cannot change")
      }
    }
    if immutable, let old = try get(namespace: namespace, key: key) {
      guard old == value else { throw PowerLogStorageError.conflict("Conflicting immutable record: \(namespace)") }
      return
    }
    try execute(
      "INSERT INTO durable_records(namespace,key,ride,value) VALUES(?,?,?,?) ON CONFLICT(namespace,key) DO UPDATE SET value=excluded.value",
      [.text(namespace), .text(key), .optional(ride), .blob(value)])
  }
  func putStream(
    namespace: String, key: String, ride: String, size: Int, write: (@escaping (Data) throws -> Void) throws -> Void
  ) throws {
    assertExecutor()
    guard size > 0, size <= 64 * 1024 * 1024 else { throw PowerLogStorageError.invalid("Health record exceeds bounds") }
    try execute(
      "INSERT INTO durable_records(namespace,key,ride,value) VALUES(?,?,?,zeroblob(?)) ON CONFLICT(namespace,key) DO UPDATE SET value=excluded.value",
      [.text(namespace), .text(key), .text(ride), .integer(Int64(size))])
    guard
      let row = try scalarInt(
        "SELECT rowid FROM durable_records WHERE namespace=? AND key=?", [.text(namespace), .text(key)])
    else {
      throw PowerLogStorageError.invalid("Missing streamed Health record")
    }
    var opened: OpaquePointer?
    guard sqlite3_blob_open(handle, "main", "durable_records", "value", row, 1, &opened) == SQLITE_OK, let blob = opened
    else { throw error() }
    defer { sqlite3_blob_close(blob) }
    var offset = 0
    try write { bytes in
      guard bytes.count <= 32768, offset + bytes.count <= size else {
        throw PowerLogStorageError.invalid("Invalid Health stream page")
      }
      let result = bytes.withUnsafeBytes { sqlite3_blob_write(blob, $0.baseAddress, Int32(bytes.count), Int32(offset)) }
      guard result == SQLITE_OK else { throw self.error() }
      offset += bytes.count
    }
    guard offset == size else { throw PowerLogStorageError.invalid("Incomplete Health record stream") }
  }
  func page(namespace: String, after: String = "", limit: Int = 256) throws -> [(key: String, value: Data)] {
    try rows(
      "SELECT key,value FROM durable_records WHERE namespace=? AND key>? ORDER BY key LIMIT ?",
      [.text(namespace), .text(after), .integer(Int64(limit))], limit: limit
    ).map { ($0.string("key")!, $0.data("value")!) }
  }
  func remove(namespace: String, key: String) throws {
    try execute("DELETE FROM durable_records WHERE namespace=? AND key=?", [.text(namespace), .text(key)])
  }
  func nextSequence(namespace: String, key: String) throws -> Int64 {
    let previous =
      try scalarInt(
        "SELECT value FROM counters WHERE namespace=? AND key=?", [.text(namespace), .text(key)]) ?? 0
    let next = try PowerLogRevision.next(previous)
    try execute(
      "INSERT INTO counters(namespace,key,value) VALUES(?,?,?) ON CONFLICT(namespace,key) DO UPDATE SET value=excluded.value",
      [.text(namespace), .text(key), .integer(next)])
    return next
  }
}

struct PowerLogStatementRow {
  fileprivate let statement: OpaquePointer
  var conservativeBytes: Int {
    var bytes = 2048
    for column in 0..<sqlite3_column_count(statement) {
      let type = sqlite3_column_type(statement, column)
      bytes += type == SQLITE_TEXT || type == SQLITE_BLOB ? 32 + 6 * Int(sqlite3_column_bytes(statement, column)) : 64
    }
    return bytes
  }
  func double(_ column: Int32) -> Double? {
    let type = sqlite3_column_type(statement, column)
    guard type == SQLITE_INTEGER || type == SQLITE_FLOAT else { return nil }
    return sqlite3_column_double(statement, column)
  }
  func integer(_ column: Int32) -> Int64? {
    sqlite3_column_type(statement, column) == SQLITE_INTEGER ? sqlite3_column_int64(statement, column) : nil
  }
  func text(_ column: Int32) -> String? {
    guard sqlite3_column_type(statement, column) == SQLITE_TEXT, let text = sqlite3_column_text(statement, column)
    else { return nil }
    return String(cString: text)
  }
  /// Unlike `text`, keeps the NUL characters a JSON string value can carry.
  func string(_ column: Int32) -> String? {
    guard sqlite3_column_type(statement, column) == SQLITE_TEXT, let text = sqlite3_column_text(statement, column)
    else { return nil }
    return String(
      decoding: UnsafeBufferPointer(start: text, count: Int(sqlite3_column_bytes(statement, column))), as: UTF8.self)
  }
  func data(_ column: Int32) -> Data? {
    guard sqlite3_column_type(statement, column) == SQLITE_BLOB else { return nil }
    let count = Int(sqlite3_column_bytes(statement, column))
    return count == 0 ? Data() : Data(bytes: sqlite3_column_blob(statement, column)!, count: count)
  }
}

struct PowerLogEventRecord {
  let event: WorkoutEvent
  let sequence: Int64
  let producer: String
  let revision: Int64
  let rowID: Int64
}

struct MonitorChannel {
  let table: String
  let column: String
  let kind: String
  let source: String
  var rawHeart = false
  var metric: String { column }
  var conditions: String {
    "m.kind=? AND m.source=?" + (column == "heartRateBpm" ? " AND m.raw_heart=\(rawHeart ? 1 : 0)" : "")
  }
  var key: String { "\(kind):\(source):\(metric):\(rawHeart)" }
  static func channels(_ event: WorkoutEvent) -> [MonitorChannel] {
    let columns: [String]
    let table: String
    switch event.kind {
    case "telemetry":
      columns = PowerLogStore.telemetryColumns
      table = "telemetry_frames"
    case "location":
      columns = PowerLogStore.locationColumns
      table = "locations"
    case "health":
      columns = PowerLogStore.healthColumns
      table = "health_samples"
    default: return []
    }
    let payload = event.payload
    let raw =
      payload["rawHealthSample"] == .bool(true) || payload["healthKitUUID"] != nil
      || payload["sampleUUID"] != nil
      || ["raw", "rawQuantity", "rawSeries"].contains(payload["representation"]?.string ?? "")
    return columns.compactMap { column in
      if [
        "latitude", "longitude", "speedAccuracyMps", "courseAccuracyDegrees", "originalAltitudeMeters",
        "originalVerticalAccuracyM", "sampleCount",
      ].contains(column) || column == "value" || event.kind == "health" && column == "speedMps" {
        return nil
      }
      if case .unsigned(let value)? = payload[column], value > UInt64(Int64.max) { return nil }
      guard let value = payload[column]?.number, value.isFinite else { return nil }
      if ["speedMps", "horizontalAccuracyM", "verticalAccuracyM", "courseDegrees"].contains(column), value < 0 {
        return nil
      }
      if column == "heartRateBpm", !(1...254).contains(value) { return nil }
      if event.kind == "location", column == "speedMps", (payload["speedAccuracyMps"]?.number ?? 0) < 0 { return nil }
      if event.kind == "location", column == "courseDegrees",
        value >= 360 || (payload["courseAccuracyDegrees"]?.number ?? 0) < 0
      {
        return nil
      }
      return MonitorChannel(
        table: table, column: column, kind: event.kind, source: event.source,
        rawHeart: column == "heartRateBpm" && raw)
    }
  }
  var values: [PowerLogSQLValue] { [.text(kind), .text(source)] }
  var latestFallback: MonitorChannel? {
    guard rawHeart else { return nil }
    var fallback = self
    fallback.rawHeart = false
    return fallback
  }
}
final class WorkoutLiveEvidence {
  struct Observation {
    let identity: String
    let acquiredAt: Double
  }
  private let lock = NSLock()
  private var readings: [String: [String: Observation]] = [:]
  private var timeline: (String, WorkoutTimelineAnchor)?
  func setTimeline(id: String?, anchor: WorkoutTimelineAnchor?) {
    lock.lock()
    defer { lock.unlock() }
    timeline = id.flatMap { id in anchor.map { (id, $0) } }
  }
  func timeline(id: String) -> WorkoutTimelineAnchor? {
    lock.lock()
    defer { lock.unlock() }
    return timeline?.0 == id ? timeline?.1 : nil
  }
  func sensor(_ event: WorkoutEvent, identity: String, acquiredAt: Double) {
    lock.lock()
    defer { lock.unlock() }
    for channel in MonitorChannel.channels(event) {
      guard acquiredAt > (readings[event.workoutId]?[channel.key]?.acquiredAt ?? -.infinity) else { continue }
      readings[event.workoutId, default: [:]][channel.key] = Observation(identity: identity, acquiredAt: acquiredAt)
    }
  }

  func clear(id: String) {
    lock.lock()
    defer { lock.unlock() }
    readings.removeValue(forKey: id)
  }
  func observation(id: String, channel: MonitorChannel) -> Observation? {
    lock.lock()
    defer { lock.unlock() }
    return readings[id]?[channel.key]
  }
  func acquisition(id: String, identity: String) -> Double? {
    lock.lock()
    defer { lock.unlock() }
    return readings[id]?.values.first(where: { $0.identity == identity })?.acquiredAt
  }
  func committed(_ event: WorkoutEvent, identity: String, acquiredAt: Double) {
    guard acquiredAt.isFinite, event.payload["supersedesEventId"] == nil,
      event.payload["deleted"] != .bool(true), event.payload["rawHealthSample"] != .bool(true),
      event.payload["healthKitUUID"] == nil, event.payload["sampleUUID"] == nil,
      event.kind != "health"
        || ["", "builderMostRecent", "cumulativeWorkoutTotal"].contains(event.payload["representation"]?.string ?? "")
    else { return }
    lock.lock()
    defer { lock.unlock() }
    for channel in MonitorChannel.channels(event) {
      guard acquiredAt > (readings[event.workoutId]?[channel.key]?.acquiredAt ?? -.infinity) else { continue }
      readings[event.workoutId, default: [:]][channel.key] = Observation(identity: identity, acquiredAt: acquiredAt)
    }
  }
}

final class PowerLogStore: @unchecked Sendable {
  static let telemetryColumns = StorageColumns.shared.telemetry
  static let locationColumns = StorageColumns.shared.appleLocation
  static let healthColumns = StorageColumns.shared.health
  private static let registryLock = NSLock()
  private static var registry: [String: WeakStore] = [:]
  private final class WeakStore {
    weak var value: PowerLogStore?
    init(_ value: PowerLogStore) { self.value = value }
  }
  let databaseURL: URL
  let runtimeVersion: String
  let runtimeSourceID: String
  private let queue = DispatchQueue(label: "app.powerlog.sqlite", qos: .userInitiated)
  private let key = DispatchSpecificKey<UInt8>()
  private let admission = NSLock()
  private var jobs: [(priority: PowerLogJobPriority, run: () -> Void, signal: DispatchSemaphore)] = []
  private var draining = false
  private let db: PowerLogDatabase
  private var recoveredKinds = Set<String>()
  private var recoveringKinds = Set<String>()
  private let recoveryCondition = NSCondition()
  private var transactionDepth = 0
  private var transactionRecords = 0
  private var transactionBytes = 0
  private var transactionFailure: Error?
  let liveEvidence = WorkoutLiveEvidence()
  private var liveAdmissions: [(WorkoutEvent, String, Double)] = []
  private var metrics: [String: Double] = [
    "jobs": 0, "queueMilliseconds": 0, "executionMilliseconds": 0, "receiptHits": 0,
  ]
  private var transactionCommitFences: [(() throws -> Void) throws -> Void] = []
  private var walFrames: Int32 = 0
  private var checkpointScheduled = false
  static let checkpointFrames: Int32 = 1000
  static let interactiveQueue = DispatchSpecificKey<Bool>()
  var beforeCommitForTesting: (() throws -> Void)?
  var jobObserverForTesting: ((_ priority: PowerLogJobPriority, _ steps: Int, _ reprepares: Int) -> Void)?
  var bookkeepingPerOriginalForTesting = false

  static func databaseURL(forRoot root: URL) -> URL {
    if root.lastPathComponent == "workouts" {
      return root.deletingLastPathComponent().appendingPathComponent("power-log.sqlite3")
    }
    return root.appendingPathComponent("power-log.sqlite3")
  }
  static let schemaVersion = 5
  static func evictSharedForTesting(databaseURL: URL) {
    registryLock.lock()
    defer { registryLock.unlock() }
    registry.removeValue(forKey: databaseURL.standardizedFileURL.path)
  }
  static func shared(databaseURL: URL) throws -> PowerLogStore {
    let url = databaseURL.standardizedFileURL
    registryLock.lock()
    defer { registryLock.unlock() }
    if let existing = registry[url.path]?.value { return existing }
    let store = try PowerLogStore(databaseURL: url)
    registry[url.path] = WeakStore(store)
    return store
  }
  static func memory() throws -> PowerLogStore {
    try PowerLogStore(databaseURL: URL(fileURLWithPath: "/tmp/power-log-preview-" + UUID().uuidString), memory: true)
  }
  private init(databaseURL: URL, memory: Bool = false) throws {
    self.databaseURL = databaseURL
    guard sqlite3_libversion_number() >= 3_037_000 else {
      throw PowerLogStorageError.invalid("Power Log requires SQLite 3.37 or newer")
    }
    runtimeVersion = String(cString: sqlite3_libversion())
    runtimeSourceID = String(cString: sqlite3_sourceid())
    let fm = FileManager.default
    let parent = databaseURL.deletingLastPathComponent()
    if !memory { try fm.createDirectory(at: parent, withIntermediateDirectories: true) }
    #if os(iOS) || os(watchOS)
      if !memory {
        try fm.setAttributes(
          [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: parent.path)
      }
    #endif
    var handle: OpaquePointer?
    let rc = sqlite3_open_v2(
      memory ? ":memory:" : databaseURL.path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX,
      nil)
    guard rc == SQLITE_OK, let handle else {
      let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "Unable to open canonical database"
      if let handle { sqlite3_close(handle) }
      throw PowerLogStorageError.sqlite(rc, message)
    }
    db = PowerLogDatabase(handle)
    queue.setSpecific(key: key, value: 1)
    db.assertExecutor = { [weak self] in
      precondition(
        self != nil && DispatchQueue.getSpecific(key: self!.key) != nil,
        "SQLite handle used outside its serialized executor")
    }
    try read { db in
      sqlite3_extended_result_codes(handle, 1)
      sqlite3_busy_timeout(handle, 1000)
      let version = try db.scalarInt("PRAGMA user_version") ?? 0
      guard version == 0 || version == Self.schemaVersion else {
        throw PowerLogStorageError.invalid(
          version < Self.schemaVersion
            ? "Power Log can't open rides saved by an earlier version. Reinstall the app to start over."
            : "Update Power Log to open your rides")
      }
      if version == 0,
        try db.scalarInt("SELECT count(*) FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%'") != 0
      {
        throw PowerLogStorageError.invalid(
          "Power Log can't open an unversioned ride store. Reinstall the app to start over.")
      }
      _ = try db.rows("PRAGMA journal_mode=WAL", limit: 1)
      try db.execute("PRAGMA synchronous=FULL")
      try db.execute("PRAGMA foreign_keys=ON")
      // SQLite's automatic checkpoint runs within the commit that crosses its threshold; a background job does it here.
      _ = try db.rows("PRAGMA wal_autocheckpoint=0", limit: 1)
      sqlite3_wal_hook(
        handle,
        { context, _, _, frames in
          Unmanaged<PowerLogStore>.fromOpaque(context!).takeUnretainedValue().committed(walFrames: frames)
          return SQLITE_OK
        }, Unmanaged.passUnretained(self).toOpaque())
      try db.execute("PRAGMA cache_size=-4096")
      try db.execute(memory ? "PRAGMA temp_store=MEMORY" : "PRAGMA temp_store=FILE")
      try db.execute("BEGIN IMMEDIATE")
      do {
        for sql in Self.schema { try db.execute(sql) }
        try db.execute("PRAGMA user_version=\(Self.schemaVersion)")
        try db.execute("COMMIT")
        metrics["commits", default: 0] += 1
      } catch {
        _ = try? db.execute("ROLLBACK")
        throw error
      }
    }
  }
  deinit {
    // Every admitted job retains the store; no jobs can still use the handle here.
    sqlite3_close_v2(db.handle)
  }

  private func perform<T>(priority: PowerLogJobPriority, _ body: () throws -> T) throws -> T {
    if DispatchQueue.getSpecific(key: key) != nil { return try body() }
    let enqueued = ProcessInfo.processInfo.systemUptime
    return try withoutActuallyEscaping(body) { escaped in
      let signal = DispatchSemaphore(value: 0)
      var result: Result<T, Error>?
      admission.lock()
      guard jobs.count < PowerLogStorageLimits.pendingJobs else {
        admission.unlock()
        throw PowerLogStorageError.busy
      }
      jobs.append(
        (
          priority,
          { [self] in
            let started = ProcessInfo.processInfo.systemUptime
            let observer = jobObserverForTesting
            let before = observer.map { _ in db.workForTesting }
            result = Result { try escaped() }
            if let observer, let before {
              let after = db.workForTesting
              observer(priority, after.steps - before.steps, after.reprepares - before.reprepares)
            }
            metrics["jobs", default: 0] += 1
            metrics["queueMilliseconds", default: 0] += (started - enqueued) * 1000
            metrics["executionMilliseconds", default: 0] += (ProcessInfo.processInfo.systemUptime - started) * 1000
          }, signal
        ))
      if !draining {
        draining = true
        queue.async { [self] in drain() }
      }
      admission.unlock()
      signal.wait()
      return try result!.get()
    }
  }
  private func drain() {
    while true {
      admission.lock()
      guard !jobs.isEmpty else {
        draining = false
        admission.unlock()
        return
      }
      let index = jobs.indices.min { jobs[$0].priority.rawValue < jobs[$1].priority.rawValue }!
      var job: (priority: PowerLogJobPriority, run: () -> Void, signal: DispatchSemaphore)? = jobs.remove(at: index)
      admission.unlock()
      let signal = job!.signal
      // A continuously busy drain may span many capture/import pages. Release Foundation
      // temporaries per admitted unit even when the dispatch work item never becomes idle.
      autoreleasepool { job!.run() }
      job = nil
      signal.signal()
    }
  }
  func read<T>(priority: PowerLogJobPriority? = nil, _ body: (PowerLogDatabase) throws -> T) throws -> T {
    try perform(
      priority: priority ?? (DispatchQueue.getSpecific(key: Self.interactiveQueue) == true ? .interactive : .normal)
    ) {
      try body(db)
    }
  }
  func transaction<T>(
    priority: PowerLogJobPriority = .normal, commitFence: ((() throws -> Void) throws -> Void)? = nil,
    _ body: (PowerLogDatabase) throws -> T
  ) throws -> T {
    try perform(priority: priority) {
      if transactionDepth > 0 {
        if let commitFence { transactionCommitFences.append(commitFence) }
        do { return try body(db) } catch {
          transactionFailure = error
          throw error
        }
      }
      try db.execute("BEGIN IMMEDIATE")
      transactionDepth = 1
      transactionCommitFences = commitFence.map { [$0] } ?? []
      transactionFailure = nil
      transactionRecords = 0
      transactionBytes = 0
      liveAdmissions.removeAll(keepingCapacity: true)
      defer {
        transactionDepth = 0
        transactionCommitFences.removeAll(keepingCapacity: true)
        transactionFailure = nil
        liveAdmissions.removeAll(keepingCapacity: true)
      }
      do {
        let result = try body(db)
        if let failure = transactionFailure { throw failure }
        try beforeCommitForTesting?()
        #if POWER_LOG_LAB
          try WorkoutLabCrash.transaction?("before-commit", db)
        #endif
        var commit: () throws -> Void = { _ = try self.db.execute("COMMIT") }
        for fence in transactionCommitFences.reversed() {
          let next = commit
          commit = { try fence(next) }
        }
        try commit()
        #if POWER_LOG_LAB
          try WorkoutLabCrash.transaction?("after-commit-before-receipt", db)
        #endif
        for (event, identity, acquiredAt) in liveAdmissions {
          liveEvidence.committed(event, identity: identity, acquiredAt: acquiredAt)
        }
        metrics["commits", default: 0] += 1
        return result
      } catch {
        _ = try? db.execute("ROLLBACK")
        throw error
      }
    }
  }
  func checkpoint() throws {
    try read(priority: .background) { db in
      guard transactionDepth == 0 else { throw PowerLogStorageError.invalid("Checkpoint inside a transaction") }
      try passiveCheckpoint()
    }
  }
  /// Queues a background checkpoint without waiting for the executor.
  func scheduleCheckpoint() {
    admission.lock()
    defer { admission.unlock() }
    guard !checkpointScheduled, jobs.count < PowerLogStorageLimits.pendingJobs else { return }
    checkpointScheduled = true
    jobs.append(
      (
        .background,
        { [self] in
          admission.lock()
          checkpointScheduled = false
          admission.unlock()
          if transactionDepth == 0 { try? passiveCheckpoint() }
        }, DispatchSemaphore(value: 0)
      ))
    if !draining {
      draining = true
      queue.async { [self] in drain() }
    }
  }
  private func passiveCheckpoint() throws {
    let started = ProcessInfo.processInfo.systemUptime
    let rc = sqlite3_wal_checkpoint_v2(db.handle, nil, SQLITE_CHECKPOINT_PASSIVE, nil, nil)
    guard rc == SQLITE_OK else { throw db.error() }
    metrics["checkpoints", default: 0] += 1
    metrics["checkpointMilliseconds", default: 0] += (ProcessInfo.processInfo.systemUptime - started) * 1000
  }
  private func committed(walFrames frames: Int32) {
    metrics["walFrames", default: 0] += Double(frames >= walFrames ? frames - walFrames : frames)
    walFrames = frames
    if frames >= Self.checkpointFrames { scheduleCheckpoint() }
  }
  func diagnostics() throws -> [String: Any] {
    try read { _ in
      var result: [String: Any] = metrics
      result["sqliteVersion"] = runtimeVersion
      result["sqliteSourceID"] = runtimeSourceID
      for (name, suffix) in [("databaseBytes", ""), ("walBytes", "-wal")] {
        result[name] =
          (try? FileManager.default.attributesOfItem(atPath: databaseURL.path + suffix)[.size] as? NSNumber)?.int64Value
          ?? 0
      }
      return result
    }
  }
  func recordReceiptHit() throws { try read { _ in metrics["receiptHits", default: 0] += 1 } }

  /// Partial-index predicates run on every write to their table, so malformed JSON must not reach json_extract.
  static let unreceivedSecondSQL =
    "CASE WHEN json_valid(CAST(record AS TEXT)) THEN received_power != json_extract(CAST(record AS TEXT),'$.power.contentVersion') OR received_cadence != json_extract(CAST(record AS TEXT),'$.cadence.contentVersion') END"
  static let forwardingCandidateSQL =
    "kind='workout' AND CASE WHEN json_valid(CAST(metadata AS TEXT)) THEN json_extract(CAST(metadata AS TEXT),'$.watchEnabled')=1 AND (json_extract(CAST(metadata AS TEXT),'$.watchSyncState')='pending' OR (json_extract(CAST(metadata AS TEXT),'$.watchSyncState')='received' AND json_extract(CAST(metadata AS TEXT),'$.finalizationState')='pending')) END"

  private static var schema: [String] {
    [
      "CREATE TABLE IF NOT EXISTS collections(id TEXT PRIMARY KEY,kind TEXT NOT NULL,started_at TEXT NOT NULL,ended_at TEXT,phase TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,event_count INTEGER NOT NULL DEFAULT 0,metadata BLOB,monotonic_origin REAL,min_query_us INTEGER,max_query_us INTEGER,min_elapsed REAL,max_elapsed REAL,channels BLOB NOT NULL DEFAULT X'5B5D') STRICT",
      "CREATE INDEX IF NOT EXISTS catalog_order ON collections(kind,started_at DESC,id DESC)",
      "CREATE TABLE IF NOT EXISTS controller_second_heads(ride TEXT NOT NULL,n INTEGER NOT NULL,record BLOB NOT NULL,received_power TEXT NOT NULL,received_cadence TEXT NOT NULL,PRIMARY KEY(ride,n)) STRICT",
      "CREATE TABLE IF NOT EXISTS observations(id INTEGER PRIMARY KEY,physical_id TEXT NOT NULL UNIQUE,kind TEXT NOT NULL,source TEXT NOT NULL,original_timestamp TEXT NOT NULL,query_us INTEGER NOT NULL,clock_epoch TEXT,monotonic_seconds REAL,representation TEXT NOT NULL DEFAULT '',content_hash BLOB NOT NULL,extra BLOB NOT NULL) STRICT",
      "CREATE TABLE IF NOT EXISTS telemetry_frames(observation_id INTEGER PRIMARY KEY REFERENCES observations(id),\(telemetryColumns.map { "\($0) ANY" }.joined(separator: ","))) STRICT",
      "CREATE TABLE IF NOT EXISTS locations(observation_id INTEGER PRIMARY KEY REFERENCES observations(id),\(locationColumns.map { "\($0) ANY" }.joined(separator: ",")),originalAltitudeDatum TEXT) STRICT",
      "CREATE TABLE IF NOT EXISTS health_samples(observation_id INTEGER PRIMARY KEY REFERENCES observations(id),\(healthColumns.map { "\($0) ANY" }.joined(separator: ",")),identifier TEXT,external_id TEXT,unit TEXT,start_timestamp TEXT,end_timestamp TEXT,provider TEXT,data_origin TEXT,provider_parent_id TEXT,member_index INTEGER,origin_revision TEXT,interval_start_us INTEGER,interval_end_us INTEGER) STRICT",
      "CREATE INDEX IF NOT EXISTS health_identifier ON health_samples(identifier,observation_id)",
      "CREATE INDEX IF NOT EXISTS health_external ON health_samples(external_id,observation_id)",
      "CREATE INDEX IF NOT EXISTS health_interval_order ON health_samples(interval_start_us,observation_id)",
      "CREATE INDEX IF NOT EXISTS health_parent_member ON health_samples(provider,data_origin,provider_parent_id,member_index)",
      "CREATE TABLE IF NOT EXISTS health_import_stage(ride TEXT NOT NULL,parent_key TEXT NOT NULL,position INTEGER NOT NULL,start_us INTEGER NOT NULL,start_sub_ns INTEGER NOT NULL,end_us INTEGER NOT NULL,end_sub_ns INTEGER NOT NULL,metric TEXT NOT NULL,unit TEXT NOT NULL,sort_bits TEXT NOT NULL,tuple BLOB NOT NULL,original_start TEXT NOT NULL,original_end TEXT NOT NULL,PRIMARY KEY(ride,parent_key,position)) STRICT",
      "CREATE TABLE IF NOT EXISTS health_parent_versions(ride TEXT NOT NULL,provider TEXT NOT NULL,data_origin TEXT NOT NULL,parent_id TEXT NOT NULL,digest TEXT NOT NULL,admitted_revision INTEGER NOT NULL,PRIMARY KEY(ride,provider,data_origin,parent_id,admitted_revision)) STRICT",
      "CREATE INDEX IF NOT EXISTS health_import_order ON health_import_stage(ride,parent_key,start_us,start_sub_ns,end_us,end_sub_ns,metric,unit,sort_bits,position)",
      "CREATE TABLE IF NOT EXISTS distance_generations(storage_id INTEGER PRIMARY KEY AUTOINCREMENT,collection_id TEXT NOT NULL,generation TEXT NOT NULL,revision INTEGER NOT NULL,state BLOB NOT NULL,UNIQUE(collection_id,generation)) STRICT",
      "CREATE INDEX IF NOT EXISTS distance_generation_revision ON distance_generations(collection_id,revision DESC)",
      "CREATE TABLE IF NOT EXISTS distance_snapshots(collection_id TEXT NOT NULL,revision INTEGER NOT NULL,generation TEXT NOT NULL,value BLOB NOT NULL,PRIMARY KEY(collection_id,revision)) STRICT",
      "CREATE TABLE IF NOT EXISTS distance_points(generation_id INTEGER NOT NULL,source TEXT NOT NULL,point_id INTEGER NOT NULL,elapsed_seconds REAL NOT NULL,timestamp TEXT NOT NULL,distance REAL NOT NULL,increment REAL NOT NULL,start_seconds REAL NOT NULL,end_seconds REAL NOT NULL,segment INTEGER NOT NULL,start_anchor TEXT NOT NULL,end_anchor TEXT NOT NULL,start_speed REAL,end_speed REAL,indivisible INTEGER NOT NULL,covered REAL NOT NULL,plot_segment INTEGER NOT NULL,PRIMARY KEY(generation_id,point_id)) STRICT",
      "CREATE INDEX IF NOT EXISTS distance_point_time ON distance_points(generation_id,source,elapsed_seconds,point_id)",
      "CREATE TABLE IF NOT EXISTS lifecycle_records(observation_id INTEGER PRIMARY KEY REFERENCES observations(id),action TEXT NOT NULL) STRICT",
      "CREATE TABLE IF NOT EXISTS collection_memberships(id INTEGER PRIMARY KEY,collection_id TEXT NOT NULL REFERENCES collections(id),observation_id INTEGER NOT NULL REFERENCES observations(id),event_id TEXT NOT NULL,producer TEXT NOT NULL,sequence INTEGER NOT NULL,ordinal INTEGER NOT NULL,kind TEXT NOT NULL,source TEXT NOT NULL,raw_heart INTEGER NOT NULL DEFAULT 0,elapsed_seconds REAL,original_elapsed_seconds REAL,deleted INTEGER NOT NULL DEFAULT 0,elapsed_us INTEGER,query_us INTEGER NOT NULL,revision INTEGER NOT NULL,mapping BLOB NOT NULL,UNIQUE(collection_id,event_id),UNIQUE(collection_id,producer,sequence),UNIQUE(collection_id,observation_id)) STRICT",
      "CREATE INDEX IF NOT EXISTS membership_observation ON collection_memberships(observation_id)",
      "CREATE INDEX IF NOT EXISTS membership_time ON collection_memberships(collection_id,elapsed_seconds,observation_id)",
      "CREATE INDEX IF NOT EXISTS membership_app_elapsed ON collection_memberships(collection_id,elapsed_seconds DESC) WHERE original_elapsed_seconds IS NOT NULL AND kind IN ('telemetry','location','lifecycle')",
      "DROP INDEX IF EXISTS membership_event",
      "CREATE INDEX IF NOT EXISTS membership_summary_time ON collection_memberships(collection_id,elapsed_us,CASE WHEN kind='lifecycle' THEN 0 ELSE 1 END,producer,sequence,id)",
      "CREATE INDEX IF NOT EXISTS membership_export_time ON collection_memberships(collection_id,elapsed_seconds,CASE WHEN kind='lifecycle' THEN 0 ELSE 1 END,producer,sequence,id)",
      "CREATE INDEX IF NOT EXISTS membership_lifecycle_elapsed ON collection_memberships(collection_id,elapsed_us DESC,ordinal DESC,revision) WHERE kind='lifecycle'",
      "CREATE INDEX IF NOT EXISTS membership_lifecycle_time ON collection_memberships(collection_id,elapsed_seconds,producer,sequence) WHERE kind='lifecycle'",
      "CREATE INDEX IF NOT EXISTS membership_utc ON collection_memberships(collection_id,query_us,observation_id)",
      "CREATE INDEX IF NOT EXISTS membership_source_time ON collection_memberships(collection_id,kind,source,raw_heart,elapsed_seconds,observation_id)",
      "CREATE INDEX IF NOT EXISTS membership_distance_time ON collection_memberships(collection_id,kind,source,elapsed_us,producer,sequence)",
      "CREATE INDEX IF NOT EXISTS membership_stream_time ON collection_memberships(collection_id,kind,source,elapsed_seconds,observation_id)",
      "CREATE INDEX IF NOT EXISTS membership_kind_time ON collection_memberships(collection_id,kind,elapsed_seconds,producer,sequence)",
      "CREATE TABLE IF NOT EXISTS collection_corrections(collection_id TEXT NOT NULL REFERENCES collections(id),target_event_id TEXT NOT NULL,replacement_event_id TEXT NOT NULL,revision INTEGER NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(collection_id,target_event_id,revision)) STRICT",
      "CREATE TABLE IF NOT EXISTS collection_versions(collection_id TEXT NOT NULL REFERENCES collections(id),revision INTEGER NOT NULL,metadata BLOB,PRIMARY KEY(collection_id,revision)) STRICT",
      "CREATE INDEX IF NOT EXISTS membership_snapshot ON collection_memberships(collection_id,revision,id)",
      "CREATE TABLE IF NOT EXISTS collection_sources(collection_id TEXT NOT NULL REFERENCES collections(id),producer TEXT NOT NULL,last_sequence INTEGER NOT NULL DEFAULT 0,record_count INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(collection_id,producer)) STRICT",
      "CREATE TABLE IF NOT EXISTS collection_channels(collection_id TEXT NOT NULL REFERENCES collections(id),metric TEXT NOT NULL,source TEXT NOT NULL,representation TEXT NOT NULL,count INTEGER NOT NULL,min_elapsed REAL,max_elapsed REAL,PRIMARY KEY(collection_id,metric,source,representation)) STRICT",
      "CREATE TABLE IF NOT EXISTS health_deletions(collection_id TEXT NOT NULL REFERENCES collections(id),source TEXT NOT NULL,external_id TEXT NOT NULL,revision INTEGER NOT NULL,tombstone_event_id TEXT NOT NULL,PRIMARY KEY(collection_id,source,external_id,revision)) STRICT",
      "CREATE TABLE IF NOT EXISTS collection_changes(collection_id TEXT NOT NULL REFERENCES collections(id),revision INTEGER NOT NULL,min_us INTEGER,max_us INTEGER,kind TEXT NOT NULL,metrics BLOB NOT NULL,PRIMARY KEY(collection_id,revision)) STRICT",
      "CREATE TABLE IF NOT EXISTS derived_cache(key TEXT PRIMARY KEY,collection_id TEXT NOT NULL REFERENCES collections(id),revision INTEGER NOT NULL,value BLOB NOT NULL) STRICT",
      "CREATE INDEX IF NOT EXISTS derived_cache_collection ON derived_cache(collection_id)",
      "CREATE TABLE IF NOT EXISTS health_route_points(ride TEXT NOT NULL,origin_key TEXT NOT NULL,time_us INTEGER NOT NULL,sequence INTEGER NOT NULL,point BLOB NOT NULL,PRIMARY KEY(ride,origin_key)) STRICT",
      "CREATE INDEX IF NOT EXISTS health_route_order ON health_route_points(ride,time_us,origin_key)",
      "CREATE TABLE IF NOT EXISTS health_route_frozen(ride TEXT NOT NULL,pin INTEGER NOT NULL,ordinal INTEGER NOT NULL,point BLOB NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(ride,pin,ordinal)) STRICT",
      "CREATE TABLE IF NOT EXISTS health_route_sources(sync_id TEXT NOT NULL,version INTEGER NOT NULL,ride TEXT NOT NULL,pin INTEGER NOT NULL,count INTEGER NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(sync_id,version)) STRICT",
      "CREATE TABLE IF NOT EXISTS durable_records(namespace TEXT NOT NULL,key TEXT NOT NULL,ride TEXT,value BLOB NOT NULL,component TEXT,second_index INTEGER,content_revision INTEGER,source_revision INTEGER,state TEXT,reason TEXT,wire_reason TEXT,source_pin BLOB,route_header BLOB,PRIMARY KEY(namespace,key)) STRICT",
      "CREATE INDEX IF NOT EXISTS durable_record_ride ON durable_records(ride,namespace,key)",
      "CREATE INDEX IF NOT EXISTS publication_state ON durable_records(ride,namespace,state,key)",
      "CREATE UNIQUE INDEX IF NOT EXISTS publication_second ON durable_records(ride,component,second_index) WHERE namespace='publication-records' AND second_index IS NOT NULL",
      "CREATE TABLE IF NOT EXISTS counters(namespace TEXT NOT NULL,key TEXT NOT NULL,value INTEGER NOT NULL,PRIMARY KEY(namespace,key)) STRICT",
    ] + forwardingIndexes
  }
  #if os(watchOS)
    private static let forwardingIndexes: [String] = []
  #else
    private static let forwardingIndexes = [
      "CREATE INDEX IF NOT EXISTS forwarding_candidate ON collections(id) WHERE \(forwardingCandidateSQL)",
      "CREATE INDEX IF NOT EXISTS controller_second_unreceived ON controller_second_heads(ride,n) WHERE \(unreceivedSecondSQL)",
    ]
  #endif

  func createCollection(
    id: String, kind: String, startedAt: String, monotonicOrigin: Double? = nil, metadata: Data? = nil
  ) throws {
    let id = try WorkoutCoding.id(id)
    _ = try WorkoutCoding.date(startedAt)
    guard ["workout", "live"].contains(kind), metadata?.count ?? 0 <= PowerLogStorageLimits.recordBytes,
      monotonicOrigin?.isFinite ?? true
    else { throw PowerLogStorageError.invalid("Invalid collection") }
    try transaction { db in
      try requireWorkoutAvailable(id: id)
      guard try db.scalarInt("SELECT count(*) FROM collections WHERE id=?", [.text(id)]) == 0 else {
        throw PowerLogStorageError.conflict("Collection already exists")
      }
      try db.execute(
        "INSERT INTO collections(id,kind,started_at,phase,metadata,monotonic_origin) VALUES(?,?,?,'running',?,?)",
        [
          .text(id), .text(kind), .text(startedAt), metadata.map(PowerLogSQLValue.blob) ?? .null,
          .optional(monotonicOrigin),
        ])
      try db.execute(
        "INSERT INTO collection_versions(collection_id,revision,metadata) VALUES(?,0,?)",
        [.text(id), metadata.map(PowerLogSQLValue.blob) ?? .null])
    }
  }
  func ensureLiveCollection(id: String, startedAt: String, monotonicOrigin: Double) throws {
    try transaction { db in
      if let row = try db.rows(
        "SELECT kind,started_at,monotonic_origin FROM collections WHERE id=?", [.text(id)], limit: 1
      ).first {
        guard row.string("kind") == "live", row.string("started_at") == startedAt,
          row.double("monotonic_origin") == monotonicOrigin
        else {
          throw PowerLogStorageError.conflict("Live capture identity changed")
        }
      } else {
        try createCollection(id: id, kind: "live", startedAt: startedAt, monotonicOrigin: monotonicOrigin)
      }
    }
  }
  func recoverOnce(kind: String, _ body: () throws -> Void) throws {
    recoveryCondition.lock()
    while recoveringKinds.contains(kind) { recoveryCondition.wait() }
    if recoveredKinds.contains(kind) {
      recoveryCondition.unlock()
      return
    }
    recoveringKinds.insert(kind)
    recoveryCondition.unlock()
    do {
      try body()
      recoveryCondition.lock()
      recoveringKinds.remove(kind)
      recoveredKinds.insert(kind)
      recoveryCondition.broadcast()
      recoveryCondition.unlock()
    } catch {
      recoveryCondition.lock()
      recoveringKinds.remove(kind)
      recoveryCondition.broadcast()
      recoveryCondition.unlock()
      throw error
    }
  }
  func collection(id: String) throws -> PowerLogRow {
    try read { db in
      try requireWorkoutAvailable(id: id)
      guard
        let row = try db.rows("SELECT * FROM collections WHERE id=?", [.text(try WorkoutCoding.id(id))], limit: 1).first
      else { throw PowerLogStorageError.missing("Collection does not exist") }
      return row
    }
  }
  func catalog(kind: String, beforeStartedAt: String? = nil, beforeID: String = "", limit: Int = 100) throws
    -> [PowerLogRow]
  {
    let pageLimit = max(1, min(100, limit))
    return try read { db in
      if let beforeStartedAt {
        return try db.rows(
          "SELECT * FROM collections WHERE kind=? AND NOT EXISTS(SELECT 1 FROM durable_records d WHERE d.namespace='deleted-workouts' AND d.key=collections.id) AND (started_at<? OR (started_at=? AND id<?)) ORDER BY started_at DESC,id DESC LIMIT ?",
          [.text(kind), .text(beforeStartedAt), .text(beforeStartedAt), .text(beforeID), .integer(Int64(pageLimit))],
          limit: pageLimit)
      }
      return try db.rows(
        "SELECT * FROM collections WHERE kind=? AND NOT EXISTS(SELECT 1 FROM durable_records d WHERE d.namespace='deleted-workouts' AND d.key=collections.id) ORDER BY started_at DESC,id DESC LIMIT ?",
        [.text(kind), .integer(Int64(pageLimit))], limit: pageLimit)
    }
  }
  static func microseconds(_ seconds: Double) throws -> Int64 {
    guard seconds.isFinite, seconds >= -9e12, seconds <= 9e12 else {
      throw PowerLogStorageError.invalid("Invalid source clock")
    }
    return Int64((seconds * 1_000_000).rounded(.down))
  }
  private static func numeric(_ value: WorkoutJSON?) -> PowerLogSQLValue {
    switch value {
    case .integer(let v): return .integer(v)
    case .unsigned(let v) where v <= UInt64(Int64.max): return .integer(Int64(v))
    case .number(let v): return .real(v)
    default: return .null
    }
  }
  private static func memberIndex(_ value: WorkoutJSON?) throws -> PowerLogSQLValue {
    guard let value else { return .null }
    let index: Int64?
    switch value {
    case .integer(let number): index = number
    case .string(let text): index = Int64(text).flatMap { String($0) == text ? $0 : nil }
    default: index = nil
    }
    guard let index, index >= 0 else { throw PowerLogStorageError.invalid("Invalid Health member index") }
    return .integer(index)
  }
  private static func json(_ value: PowerLogSQLValue) -> WorkoutJSON? {
    switch value {
    case .integer(let v): return .integer(v)
    case .real(let v): return .number(v)
    case .text(let v): return .string(v)
    default: return nil
    }
  }
  static let mappingKeys: Set<String> = ["elapsedSeconds", "sequence"]
  static func physicalIdentity(_ event: WorkoutEvent) throws -> String {
    if event.kind == "telemetry", let session = event.payload["captureSessionID"]?.string,
      let sequence = event.payload["observationSequence"]
    {
      let normalized = try WorkoutCoding.id(session)
      let seq: String
      switch sequence {
      case .integer(let v) where v >= 0: seq = String(v)
      case .unsigned(let v): seq = String(v)
      case .string(let v):
        guard let n = UInt64(v) else { throw PowerLogStorageError.invalid("Invalid observation sequence") }
        seq = String(n)
      default:
        throw PowerLogStorageError.invalid("Observation sequence must retain its original integer representation")
      }
      return "controller:\(normalized):\(seq)"
    }
    return "event:\(try WorkoutCoding.id(event.eventId))"
  }

  @discardableResult
  func appendTelemetry(
    _ sample: [String: Any], collectionID: String, elapsedSeconds: Double?, producer: String = "controller"
  )
    throws -> Int
  {
    guard let timestamp = sample["timestamp"] as? String else {
      throw PowerLogStorageError.invalid("Telemetry has no original timestamp")
    }
    let eventID = sample["observationId"] as? String ?? sample["eventId"] as? String ?? UUID().uuidString.lowercased()
    let event = try WorkoutEvent(dictionary: [
      "schemaVersion": 1, "eventId": eventID, "workoutId": collectionID,
      "kind": "telemetry", "source": "controller", "timestamp": timestamp,
      "elapsedSeconds": elapsedSeconds.map { $0 as Any } ?? NSNull(),
      "payload": WorkoutCoreCodec.telemetryPayload(sample),
    ])
    return try appendBatch(
      [event], producer: producer,
      liveAcquisitions: [event.eventId: sample["acquisitionMonotonic"] as? Double].compactMapValues { $0 })
  }

  @discardableResult
  func appendBatch(
    _ events: [WorkoutEvent], producer: String? = nil, firstSequence: Int64? = nil,
    liveAcquisitions: [String: Double] = [:]
  ) throws -> Int {
    try appendBatch(
      PowerLogPreparedBatch(events, producer: producer, firstSequence: firstSequence),
      liveAcquisitions: liveAcquisitions)
  }

  @discardableResult
  func appendBatch(_ batch: PowerLogPreparedBatch, liveAcquisitions: [String: Double] = [:]) throws -> Int {
    if batch.entries.isEmpty { return 0 }
    return try append(
      batch.entries, producer: batch.producer, firstSequence: batch.firstSequence, encodedBytes: batch.encodedBytes,
      liveAcquisitions: liveAcquisitions)
  }

  /// Appends capture-built originals whose identity was computed once for all of their memberships.
  @discardableResult
  func appendCaptured(
    _ entries: [(event: WorkoutEvent, identity: PowerLogObservationIdentity)], producer: String,
    liveAcquisitions: [String: Double] = [:]
  ) throws -> Int {
    guard entries.count <= PowerLogStorageLimits.transactionRecords else {
      throw PowerLogStorageError.invalid("Observation batch exceeds 512 records")
    }
    if entries.isEmpty { return 0 }
    guard !producer.isEmpty, producer.utf8.count <= 128 else {
      throw PowerLogStorageError.invalid("Invalid archival producer")
    }
    var encodedBytes = 0
    for entry in entries {
      guard entry.identity.eventBytes <= 32_768 else { throw WorkoutDataError.invalid("Workout event exceeds 32 KiB") }
      encodedBytes += entry.identity.eventBytes
    }
    return try append(
      entries.map { ($0.event, $0.identity) }, producer: producer, firstSequence: nil, encodedBytes: encodedBytes,
      liveAcquisitions: liveAcquisitions)
  }

  /// Collection state read when an append first names the collection; `flush` writes it after the last original.
  private final class AppendCollection {
    let kind: String?
    let startedAt: String
    let storedMetadata: Data?
    var metadata: RideRecord?
    var revision: Int64
    var eventCount: Int64
    var inserted: Int64 = 0
    var channels: Set<String>
    var minQueryUS: Int64?
    var maxQueryUS: Int64?
    var minElapsed: Double?
    var maxElapsed: Double?
    var sources: [String: (last: Int64, added: Int64)] = [:]
    var channelCounts: [String: [String: Int64]] = [:]
    init(_ row: PowerLogRow) {
      kind = row.string("kind")
      startedAt = row.string("started_at")!
      storedMetadata = row.data("metadata")
      revision = row.int("revision")!
      eventCount = row.int("event_count")!
      channels = Set((try? JSONDecoder().decode([String].self, from: row.data("channels") ?? Data())) ?? [])
    }
  }
  private struct AppendChannel: Hashable {
    let collection: String
    let metric: String
    let source: String
    let representation: String
  }
  private final class AppendBookkeeping {
    var collections: [String: AppendCollection] = [:]
    var order: [String] = []
    var channels: [AppendChannel: (count: Int64, min: Double, max: Double)] = [:]
    var channelOrder: [AppendChannel] = []
  }

  private func append(
    _ entries: [(event: WorkoutEvent, identity: PowerLogObservationIdentity)], producer: String?,
    firstSequence: Int64?, encodedBytes: Int, liveAcquisitions: [String: Double]
  ) throws -> Int {
    guard encodedBytes <= PowerLogStorageLimits.transactionBytes else {
      throw PowerLogStorageError.invalid("Observation batch exceeds byte limit")
    }
    return try transaction(priority: .capture) { db in
      let role = try db.get(namespace: "endpoint-binding", key: "role").map {
        try JSONDecoder().decode(String.self, from: $0)
      }
      guard role != "watch" || !entries.contains(where: { $0.event.source == "controller" }) else {
        throw PowerLogStorageError.invalid("Watch storage cannot admit Controller originals")
      }
      transactionRecords += entries.count
      transactionBytes += encodedBytes
      guard transactionRecords <= PowerLogStorageLimits.transactionRecords,
        transactionBytes <= PowerLogStorageLimits.transactionBytes
      else { throw PowerLogStorageError.invalid("Outer transaction exceeds capture batch bounds") }
      var bookkeeping = AppendBookkeeping()
      var inserted = 0
      for (offset, (event, identity)) in entries.enumerated() {
        let supplied = firstSequence.map { $0 + Int64(offset) }
        if try insert(
          event, producer: producer ?? event.source, suppliedSequence: supplied, identity: identity,
          bookkeeping: bookkeeping, db: db)
        {
          inserted += 1
          if let acquiredAt = liveAcquisitions[event.eventId] {
            liveAdmissions.append((event, try Self.physicalIdentity(event), acquiredAt))
          }
        }
        if bookkeepingPerOriginalForTesting {
          try flush(bookkeeping, db: db)
          bookkeeping = AppendBookkeeping()
        }
      }
      try flush(bookkeeping, db: db)
      return inserted
    }
  }
  private func flush(_ bookkeeping: AppendBookkeeping, db: PowerLogDatabase) throws {
    for id in bookkeeping.order {
      let collection = bookkeeping.collections[id]!
      guard collection.inserted > 0, let minQueryUS = collection.minQueryUS, let maxQueryUS = collection.maxQueryUS,
        let minElapsed = collection.minElapsed, let maxElapsed = collection.maxElapsed
      else { continue }
      for (producer, source) in collection.sources.sorted(by: { $0.key < $1.key }) {
        try db.execute(
          "UPDATE collection_sources SET last_sequence=max(last_sequence,?),record_count=record_count+? WHERE collection_id=? AND producer=?",
          [.integer(source.last), .integer(source.added), .text(id), .text(producer)])
      }
      try db.execute(
        "UPDATE collections SET revision=?,event_count=event_count+?,min_query_us=min(coalesce(min_query_us,?),?),max_query_us=max(coalesce(max_query_us,?),?),min_elapsed=min(coalesce(min_elapsed,?),?),max_elapsed=max(coalesce(max_elapsed,?),?),channels=? WHERE id=?",
        [
          .integer(collection.revision), .integer(collection.inserted), .integer(minQueryUS), .integer(minQueryUS),
          .integer(maxQueryUS), .integer(maxQueryUS), .real(minElapsed), .real(minElapsed), .real(maxElapsed),
          .real(maxElapsed), .blob(try WorkoutCoding.encoder().encode(collection.channels.sorted())), .text(id),
        ])
      try db.execute(
        "DELETE FROM collection_changes WHERE collection_id=? AND revision<=?",
        [.text(id), .integer(collection.revision - Int64(PowerLogStorageLimits.changeRows))])
    }
    for key in bookkeeping.channelOrder {
      let channel = bookkeeping.channels[key]!
      try db.execute(
        "INSERT INTO collection_channels(collection_id,metric,source,representation,count,min_elapsed,max_elapsed) VALUES(?,?,?,?,?,?,?) ON CONFLICT(collection_id,metric,source,representation) DO UPDATE SET count=count+excluded.count,min_elapsed=min(min_elapsed,excluded.min_elapsed),max_elapsed=max(max_elapsed,excluded.max_elapsed)",
        [
          .text(key.collection), .text(key.metric), .text(key.source), .text(key.representation),
          .integer(channel.count), .real(channel.min), .real(channel.max),
        ])
    }
  }
  static var appElapsedSQL: String {
    "SELECT m.elapsed_seconds FROM collection_memberships m INDEXED BY membership_app_elapsed WHERE m.collection_id=? AND m.revision<=? AND \(Self.selectedMembershipSQL) AND m.original_elapsed_seconds IS NOT NULL AND m.kind IN ('telemetry','location','lifecycle') ORDER BY m.elapsed_seconds DESC LIMIT 1"
  }
  func appElapsed(id: String, revision: Int64) throws -> Double {
    try read { db in
      try db.rows(
        Self.appElapsedSQL,
        [.text(id), .integer(revision), .integer(revision), .integer(revision)], limit: 1
      ).first?.double("elapsed_seconds") ?? 0
    }
  }

  private func insert(
    _ event: WorkoutEvent, producer: String, suppliedSequence: Int64?, identity: PowerLogObservationIdentity,
    bookkeeping: AppendBookkeeping, db: PowerLogDatabase
  ) throws -> Bool {
    let collectionID = try WorkoutCoding.id(event.workoutId)
    let eventID = try WorkoutCoding.id(event.eventId)
    let collection: AppendCollection
    if let pending = bookkeeping.collections[collectionID] {
      collection = pending
    } else {
      try requireWorkoutWritable(id: collectionID)
      guard let row = try db.rows("SELECT * FROM collections WHERE id=?", [.text(collectionID)], limit: 1).first
      else { throw PowerLogStorageError.missing("Collection does not exist") }
      collection = AppendCollection(row)
      bookkeeping.collections[collectionID] = collection
      bookkeeping.order.append(collectionID)
    }
    var payload = event.payload
    var mapping: [String: WorkoutJSON] = [:]
    for name in Self.mappingKeys { if let value = payload.removeValue(forKey: name) { mapping[name] = value } }
    if let value = payload.removeValue(forKey: "timestamp") { mapping["timestamp"] = value }
    let physical = try Self.physicalIdentity(event)
    let digest = identity.digest
    let queryUS = try WorkoutCoding.timestampMicroseconds(event.timestamp)
    guard event.kind == "health" || event.elapsedSeconds != nil else {
      throw PowerLogStorageError.invalid("App observation has no collection timing")
    }
    let elapsed: Double
    let elapsedUS: Int64
    if let original = event.elapsedSeconds {
      elapsed = original
      elapsedUS = try Self.microseconds(original)
    } else {
      elapsedUS = try queryUS - WorkoutCoding.timestampMicroseconds(collection.startedAt)
      elapsed = Double(elapsedUS) / 1_000_000
    }
    guard elapsed.isFinite, abs(elapsed) <= 2_678_400 else {
      throw PowerLogStorageError.invalid("Invalid collection time mapping")
    }
    let mappingData = try WorkoutCoding.encoder().encode(mapping)
    let observationID: Int64
    if let old = try db.rows(
      "SELECT id,content_hash FROM observations WHERE physical_id=?", [.text(physical)], limit: 1
    ).first {
      guard old.data("content_hash") == digest else {
        throw PowerLogStorageError.conflict("Conflicting replay of original observation identity")
      }
      observationID = old.int("id")!
    } else {
      let columns: [String]
      switch event.kind {
      case "telemetry": columns = Self.telemetryColumns
      case "location": columns = Self.locationColumns
      case "health": columns = Self.healthColumns
      default: columns = []
      }
      var extra = payload
      for name in columns { if Self.numeric(payload[name]) != .null { extra.removeValue(forKey: name) } }
      let representation =
        payload["representation"]?.string ?? (event.kind == "health" && payload["healthKitUUID"] != nil ? "raw" : "")
      let extraData = try WorkoutCoding.encoder().encode(extra)
      guard extraData.count <= PowerLogStorageLimits.extensionBytes else {
        throw PowerLogStorageError.invalid("Observation extension exceeds byte limit")
      }
      try db.execute(
        "INSERT INTO observations(physical_id,kind,source,original_timestamp,query_us,clock_epoch,monotonic_seconds,representation,content_hash,extra) VALUES(?,?,?,?,?,?,?,?,?,?)",
        [
          .text(physical), .text(event.kind), .text(event.source), .text(event.timestamp),
          .integer(queryUS), .optional(payload["clockEpoch"]?.string),
          .optional(payload["acquisitionMonotonic"]?.number),
          .text(representation), .blob(digest), .blob(extraData),
        ])
      observationID = db.lastInsertedID
      if !columns.isEmpty {
        let table =
          event.kind == "telemetry" ? "telemetry_frames" : event.kind == "location" ? "locations" : "health_samples"
        var names = columns
        var values = columns.map { Self.numeric(payload[$0]) }
        if event.kind == "location" {
          names.append("originalAltitudeDatum")
          values.append(.optional(payload["originalAltitudeDatum"]?.string))
        }
        if event.kind == "health" {
          let origin: [String: WorkoutJSON]
          if case .object(let value) = payload["origin"] { origin = value } else { origin = [:] }
          names += [
            "identifier", "external_id", "unit", "start_timestamp", "end_timestamp", "provider", "data_origin",
            "provider_parent_id", "member_index", "origin_revision", "interval_start_us", "interval_end_us",
          ]
          values += [
            .optional(payload["healthKitIdentifier"]?.string),
            .optional((payload["healthKitUUID"]?.string ?? payload["sampleUUID"]?.string)?.lowercased()),
            .optional(payload["unit"]?.string),
            .optional(payload["sampleStart"]?.string ?? payload["startDate"]?.string),
            .optional(payload["sampleEnd"]?.string ?? payload["endDate"]?.string),
            .optional(origin["provider"]?.string), .optional(origin["dataOrigin"]?.string),
            .optional(origin["providerObservationId"]?.string),
            try Self.memberIndex(payload["memberIndex"]), .optional(origin["originRevision"]?.string),
            try (payload["sampleStart"]?.string ?? payload["startDate"]?.string).map {
              .integer(try WorkoutCoding.timestampMicroseconds($0))
            } ?? .null,
            try (payload["sampleEnd"]?.string ?? payload["endDate"]?.string).map {
              .integer(try WorkoutCoding.timestampMicroseconds($0))
            } ?? .null,
          ]
        }
        let sql =
          "INSERT INTO \(table)(observation_id,\(names.joined(separator: ","))) VALUES(\(Array(repeating: "?", count: names.count + 1).joined(separator: ",")))"
        try db.execute(sql, [.integer(observationID)] + values)
      } else {
        try db.execute(
          "INSERT INTO lifecycle_records(observation_id,action) VALUES(?,?)",
          [.integer(observationID), .text(payload["action"]!.string!)])
      }
    }
    if let old = try db.rows(
      "SELECT * FROM collection_memberships WHERE collection_id=? AND (event_id=? OR observation_id=?)",
      [.text(collectionID), .text(eventID), .integer(observationID)], limit: 1
    ).first {
      // An absent original elapsed was mapped once on admission. A later metadata
      // confirmation must not reinterpret that same original when a transport retries it.
      guard old.int("observation_id") == observationID, old.string("event_id") == eventID,
        old.double("original_elapsed_seconds") == event.elapsedSeconds,
        event.elapsedSeconds == nil || old.double("elapsed_seconds") == elapsed,
        old.data("mapping") == mappingData, old.string("producer") == producer,
        suppliedSequence == nil || old.int("sequence") == suppliedSequence
      else { throw PowerLogStorageError.conflict("Conflicting collection membership replay") }
      return false
    }
    guard collection.eventCount < PowerLogStorageLimits.maximumCollectionRecords else {
      throw PowerLogStorageError.invalid("Collection record limit reached")
    }
    if collection.sources[producer] == nil {
      try db.execute(
        "INSERT OR IGNORE INTO collection_sources(collection_id,producer) VALUES(?,?)",
        [.text(collectionID), .text(producer)])
      let last = try db.scalarInt(
        "SELECT last_sequence FROM collection_sources WHERE collection_id=? AND producer=?",
        [.text(collectionID), .text(producer)])!
      collection.sources[producer] = (last, 0)
    }
    let source = collection.sources[producer]!
    guard suppliedSequence != nil || source.last < Int64.max else {
      throw PowerLogStorageError.invalid("Source archival sequence exhausted")
    }
    let sequence = suppliedSequence ?? (source.last + 1)
    guard sequence > 0 else { throw PowerLogStorageError.invalid("Archival sequence must be positive") }
    // Received pages may arrive out of order. Unique ranges are retained; consumers must verify holes before sealing.
    let revision = try PowerLogRevision.next(collection.revision)
    let rawHeart =
      event.kind == "health"
        && (payload["rawHealthSample"] == .bool(true) || payload["healthKitUUID"] != nil || payload["sampleUUID"] != nil
          || ["raw", "rawQuantity", "rawSeries"].contains(payload["representation"]?.string ?? ""))
      ? 1 : 0
    try db.execute(
      "INSERT INTO collection_memberships(collection_id,observation_id,event_id,producer,sequence,ordinal,kind,source,raw_heart,elapsed_seconds,original_elapsed_seconds,deleted,elapsed_us,query_us,revision,mapping) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
      [
        .text(collectionID), .integer(observationID), .text(eventID), .text(producer), .integer(sequence),
        .integer(collection.eventCount + 1), .text(event.kind), .text(event.source), .integer(Int64(rawHeart)),
        .real(elapsed), .optional(event.elapsedSeconds), .integer(payload["deleted"] == .bool(true) ? 1 : 0),
        .integer(elapsedUS), .integer(queryUS), .integer(revision), .blob(mappingData),
      ])
    if event.kind == "health", payload["deleted"] == .bool(true),
      let external = payload["sampleUUID"]?.string ?? payload["healthKitUUID"]?.string
        ?? payload["supersedesEventId"]?.string
    {
      try db.execute(
        "INSERT INTO health_deletions(collection_id,source,external_id,revision,tombstone_event_id) VALUES(?,?,?,?,?)",
        [
          .text(collectionID), .text(event.source), .text(try WorkoutCoding.id(external)), .integer(revision),
          .text(eventID),
        ])
    }
    if let target = payload["supersedesEventId"]?.string {
      try db.execute(
        "INSERT INTO collection_corrections(collection_id,target_event_id,replacement_event_id,revision,deleted) VALUES(?,?,?,?,?)",
        [
          .text(collectionID), .text(try WorkoutCoding.id(target)), .text(eventID), .integer(revision),
          .integer(payload["deleted"] == .bool(true) ? 1 : 0),
        ])
    }
    collection.sources[producer] = (max(source.last, sequence), source.added + 1)
    let chartMetrics = Set(Self.telemetryColumns + Self.locationColumns + Self.healthColumns).subtracting([
      "value", "sampleCount",
    ])
    let names = payload.compactMap { chartMetrics.contains($0.key) && Self.numeric($0.value) != .null ? $0.key : nil }
      .sorted()
    let representation = rawHeart == 1 ? "raw" : ""
    let group = event.source + "\u{0}" + representation
    if collection.channelCounts[group] == nil {
      let rows = try db.rows(
        "SELECT metric,count FROM collection_channels WHERE collection_id=? AND source=? AND representation=?",
        [.text(collectionID), .text(event.source), .text(representation)], limit: 64)
      collection.channelCounts[group] = Dictionary(
        uniqueKeysWithValues: rows.map { ($0.string("metric")!, $0.int("count")!) })
    }
    let priorChannels = collection.channelCounts[group]!
    let denseTelemetry =
      event.kind == "telemetry" && priorChannels.count == names.count && Set(priorChannels.values).count == 1
    let introducedChannel = !Set(names).isSubset(of: Set(priorChannels.keys))

    collection.channels.formUnion(names)
    collection.revision = revision
    collection.eventCount += 1
    collection.inserted += 1
    collection.minQueryUS = min(collection.minQueryUS ?? queryUS, queryUS)
    collection.maxQueryUS = max(collection.maxQueryUS ?? queryUS, queryUS)
    collection.minElapsed = min(collection.minElapsed ?? elapsed, elapsed)
    collection.maxElapsed = max(collection.maxElapsed ?? elapsed, elapsed)
    for metric in names {
      collection.channelCounts[group]![metric, default: 0] += 1
      let key = AppendChannel(
        collection: collectionID, metric: metric, source: event.source, representation: representation)
      if let channel = bookkeeping.channels[key] {
        bookkeeping.channels[key] = (channel.count + 1, min(channel.min, elapsed), max(channel.max, elapsed))
      } else {
        bookkeeping.channels[key] = (1, elapsed, elapsed)
        bookkeeping.channelOrder.append(key)
      }
    }
    let broadChange =
      introducedChannel || event.kind == "lifecycle" || payload["supersedesEventId"] != nil
      || payload["deleted"] == .bool(true)
      || ["finalWorkoutTotal", "finalTotal"].contains(payload["representation"]?.string ?? "")
      || names.contains(where: { ["activeEnergyKcal", "basalEnergyKcal", "distanceMeters"].contains($0) })
    if broadChange {
      try insertChange(
        db, id: collectionID, revision: revision, minUS: nil, maxUS: nil, kind: "semantics", metrics: names)
    } else {
      // Invalidate each selected metric's neighboring edges. Optional fields cannot use
      // an arbitrary stream neighbor because it may be null (or already superseded).
      var lower = elapsed
      var upper = elapsed
      let kind = event.kind == "telemetry" ? "telemetry" : (event.kind == "location" ? "location" : "health")
      let table = kind == "telemetry" ? "telemetry_frames" : (kind == "location" ? "locations" : "health_samples")
      let projections: [String?] = denseTelemetry ? [nil] : names.map { Optional($0) }
      for metric in projections {
        let join = metric == nil ? "" : " JOIN \(table) h ON h.observation_id=m.observation_id"
        let selected = metric == nil ? "" : " AND \(Self.selectedMembershipSQL) AND h.\(metric!) IS NOT NULL"
        var bindings: [PowerLogSQLValue] = [.text(collectionID), .text(event.source), .integer(Int64(rawHeart))]
        if metric != nil { bindings += [.integer(revision), .integer(revision)] }
        for before in [true, false] {
          // A literal kind lets SQLite settle the partial kind indexes once instead of re-planning per bound value.
          let sql =
            "SELECT m.elapsed_seconds FROM collection_memberships m\(join) WHERE m.collection_id=? AND m.kind='\(kind)' AND m.source=? AND m.raw_heart=?\(selected) AND m.elapsed_seconds\(before ? "<" : ">")? ORDER BY m.elapsed_seconds \(before ? "DESC" : "ASC"),m.observation_id \(before ? "DESC" : "ASC") LIMIT 1"
          if let time = try db.rows(sql, bindings + [.real(elapsed)], limit: 1).first?.double("elapsed_seconds") {
            if before { lower = min(lower, time) } else { upper = max(upper, time) }
          }
        }
      }
      try insertChange(
        db, id: collectionID, revision: revision, minUS: try Self.microseconds(lower),
        maxUS: Int64(ceil(upper * 1_000_000)), kind: "append", metrics: names)

    }
    return true
  }
  func recordChange(
    _ db: PowerLogDatabase, id: String, revision: Int64, minUS: Int64?, maxUS: Int64?, kind: String, metrics: [String]
  ) throws {
    try insertChange(db, id: id, revision: revision, minUS: minUS, maxUS: maxUS, kind: kind, metrics: metrics)
    try db.execute(
      "DELETE FROM collection_changes WHERE collection_id=? AND revision<=?",
      [.text(id), .integer(revision - Int64(PowerLogStorageLimits.changeRows))])
  }
  private func insertChange(
    _ db: PowerLogDatabase, id: String, revision: Int64, minUS: Int64?, maxUS: Int64?, kind: String, metrics: [String]
  ) throws {
    try db.execute(
      "INSERT INTO collection_changes(collection_id,revision,min_us,max_us,kind,metrics) VALUES(?,?,?,?,?,?)",
      [
        .text(id), .integer(revision), minUS.map(PowerLogSQLValue.integer) ?? .null,
        maxUS.map(PowerLogSQLValue.integer) ?? .null, .text(kind), .blob(try WorkoutCoding.encoder().encode(metrics)),
      ])
  }

  func telemetryOriginal(collectionID: String, observationID: String) throws -> [String: Any]? {
    try read { db in
      guard
        let row = try db.rows(
          "SELECT m.*,o.original_timestamp,o.extra FROM collection_memberships m JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND m.event_id=? AND m.kind='telemetry'",
          [.text(collectionID), .text(observationID)], limit: 1
        ).first
      else { return nil }
      let event = try decodeEvent(row, db: db).event
      var sample = event.payload.mapValues(\.any)
      sample["timestamp"] = event.timestamp
      return sample
    }
  }

  func pageEvents(
    id: String, afterSequence: Int64 = 0, limit: Int = 256, producer: String? = nil, throughRevision: Int64? = nil
  ) throws -> [PowerLogEventRecord] {
    try read(priority: .background) { db in
      let id = try WorkoutCoding.id(id)
      try requireWorkoutAvailable(id: id)
      let cursor = producer == nil ? "m.id" : "m.sequence"
      var sql =
        "SELECT m.*,o.original_timestamp,o.extra FROM collection_memberships m JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND \(cursor)>?"
      var values: [PowerLogSQLValue] = [.text(id), .integer(afterSequence)]
      if let producer {
        sql += " AND m.producer=?"
        values.append(.text(producer))
      }
      if let throughRevision {
        sql += " AND m.revision<=?"
        values.append(.integer(throughRevision))
      }
      sql += " ORDER BY \(cursor) LIMIT ?"
      values.append(.integer(Int64(limit)))
      return try db.rows(sql, values, limit: limit).map { try decodeEvent($0, db: db) }
    }
  }
  // Repeated named references share one bind slot; callers still supply two selected-revision binds.
  private static let healthTombstoneMembershipSQL =
    "(m.kind='health' AND m.deleted=1 AND EXISTS(SELECT 1 FROM observations original WHERE original.id=m.observation_id AND json_extract(CAST(original.extra AS TEXT),'$.representation')='healthTombstone'))"
  static let selectedMembershipSQL =
    "(m.deleted=0 OR \(healthTombstoneMembershipSQL)) AND NOT EXISTS(SELECT 1 FROM collection_corrections c WHERE c.collection_id=m.collection_id AND c.target_event_id=m.event_id AND c.revision<=?) AND (m.kind<>'health' OR \(healthTombstoneMembershipSQL) OR NOT EXISTS(SELECT 1 FROM health_samples hs WHERE hs.observation_id=m.observation_id AND (EXISTS(SELECT 1 FROM health_deletions hd WHERE hd.external_id=hs.external_id AND hd.collection_id=m.collection_id AND hd.source=m.source AND hd.revision<=$healthSelectionRevision) OR (hs.origin_revision IS NOT NULL AND hs.origin_revision IS NOT (SELECT pv.digest FROM health_parent_versions pv WHERE pv.ride=m.collection_id AND pv.provider=hs.provider AND pv.data_origin=hs.data_origin AND pv.parent_id=hs.provider_parent_id AND pv.admitted_revision<=$healthSelectionRevision ORDER BY pv.admitted_revision DESC LIMIT 1)))))"

  func decodeEvent(_ row: PowerLogRow, db: PowerLogDatabase) throws -> PowerLogEventRecord {
    let kind = row.string("kind")!
    let observationID = row.int("observation_id")!
    var payload = try JSONDecoder().decode([String: WorkoutJSON].self, from: row.data("extra")!)
    let table: String?
    let columns: [String]
    switch kind {
    case "telemetry":
      table = "telemetry_frames"
      columns = Self.telemetryColumns
    case "location":
      table = "locations"
      columns = Self.locationColumns
    case "health":
      table = "health_samples"
      columns = Self.healthColumns
    default:
      table = nil
      columns = []
    }
    if let table,
      let typed = try db.rows("SELECT * FROM \(table) WHERE observation_id=?", [.integer(observationID)], limit: 1)
        .first
    {
      for column in columns { if let value = Self.json(typed[column]) { payload[column] = value } }
    }
    let mapping = try JSONDecoder().decode([String: WorkoutJSON].self, from: row.data("mapping")!)
    payload.merge(mapping) { _, new in new }
    let event = try WorkoutEvent(
      storedEventID: row.string("event_id")!, workoutID: row.string("collection_id")!,
      kind: kind, source: row.string("source")!, originalTimestamp: row.string("original_timestamp")!,
      elapsedSeconds: row.double("original_elapsed_seconds"), payload: payload)
    return PowerLogEventRecord(
      event: event, sequence: row.int("sequence")!, producer: row.string("producer")!, revision: row.int("revision")!,
      rowID: row.int("id")!)
  }

  @discardableResult
  private static func cleanupDistancePage(_ db: PowerLogDatabase, id: String, limit: Int) throws -> Bool {
    for table in ["distance_points", "distance_snapshots", "distance_generations"] {
      let predicate =
        table == "distance_points"
        ? "generation_id IN (SELECT storage_id FROM distance_generations WHERE collection_id=?)" : "collection_id=?"
      let rows = try db.rows(
        "SELECT rowid AS cleanup_row FROM \(table) WHERE \(predicate) LIMIT ?", [.text(id), .integer(Int64(limit))],
        limit: limit)
      if !rows.isEmpty {
        for row in rows { try db.execute("DELETE FROM \(table) WHERE rowid=?", [row["cleanup_row"]]) }
        return true
      }
    }
    return false
  }
  /// Only used by the memory preview; retained ride originals never pass through this bound.
  func prunePreview(id: String, retaining maximum: Int = 28_800) throws {
    try transaction(priority: .capture) { db in
      let count =
        try db.scalarInt("SELECT count(*) FROM collection_memberships WHERE collection_id=?", [.text(id)]) ?? 0
      guard count > maximum else { return }
      let rows = try db.rows(
        "SELECT id,observation_id,elapsed_seconds FROM collection_memberships WHERE collection_id=? ORDER BY ordinal LIMIT ?",
        [.text(id), .integer(count - Int64(maximum))], limit: 512)
      for row in rows {
        try db.execute("DELETE FROM collection_memberships WHERE id=?", [row["id"]])
        try db.execute("DELETE FROM telemetry_frames WHERE observation_id=?", [row["observation_id"]])
        try db.execute("DELETE FROM observations WHERE id=?", [row["observation_id"]])
      }
      let revision = try collection(id: id).int("revision")! + 1
      try db.execute("UPDATE collections SET revision=? WHERE id=?", [.integer(revision), .text(id)])
      try recordChange(
        db, id: id, revision: revision, minUS: 0,
        maxUS: rows.last?.double("elapsed_seconds").map { Int64($0 * 1_000_000) }, kind: "telemetry",
        metrics: Self.telemetryColumns)
      try db.execute(
        "DELETE FROM collection_changes WHERE collection_id=? AND revision<?",
        [.text(id), .integer(max(0, revision - 512))])
    }
  }
  func pruneLivePage(keeping activeID: String, limit: Int = 128) throws -> Bool {
    let activeID = try WorkoutCoding.id(activeID)
    guard (1...128).contains(limit) else { throw PowerLogStorageError.invalid("Live cleanup page must be 1...128") }
    return try transaction(priority: .background) { db in
      guard
        let stale = try db.rows(
          "SELECT id FROM collections WHERE kind='live' AND id<>? ORDER BY started_at,id LIMIT 1", [.text(activeID)],
          limit: 1
        ).first?.string("id")
      else { return false }
      if try Self.cleanupDistancePage(db, id: stale, limit: limit) { return true }
      let page = try db.rows(
        "SELECT id,observation_id FROM collection_memberships WHERE collection_id=? ORDER BY id LIMIT ?",
        [.text(stale), .integer(Int64(limit))], limit: limit)
      for row in page {
        let observation = row.int("observation_id")!
        try db.execute("DELETE FROM collection_memberships WHERE id=?", [row["id"]])
        if try db.scalarInt(
          "SELECT 1 FROM collection_memberships WHERE observation_id=? LIMIT 1", [.integer(observation)]) == nil
        {
          for table in ["telemetry_frames", "locations", "health_samples", "lifecycle_records"] {
            try db.execute("DELETE FROM \(table) WHERE observation_id=?", [.integer(observation)])
          }
          try db.execute("DELETE FROM observations WHERE id=?", [.integer(observation)])
        }
      }
      if try db.scalarInt("SELECT 1 FROM collection_memberships WHERE collection_id=? LIMIT 1", [.text(stale)]) == nil {
        for table in [
          "collection_sources", "collection_channels", "collection_changes", "collection_versions",
          "collection_corrections", "health_deletions", "derived_cache",
        ] {
          try db.execute("DELETE FROM \(table) WHERE collection_id=?", [.text(stale)])
        }
        try db.execute("DELETE FROM collections WHERE id=?", [.text(stale)])
      }
      return true
    }
  }
  func hasEvent(id: String, eventID: String) throws -> Bool {
    try read { db in
      try requireWorkoutAvailable(id: id)
      return try db.scalarInt(
        "SELECT 1 FROM collection_memberships WHERE collection_id=? AND event_id=?",
        [.text(try WorkoutCoding.id(id)), .text(try WorkoutCoding.id(eventID))]) != nil
    }
  }
  func sourceProgress(id: String, producer: String) throws -> (count: Int64, lastSequence: Int64) {
    try read { db in
      try requireWorkoutAvailable(id: id)
      let row = try db.rows(
        "SELECT record_count,last_sequence FROM collection_sources WHERE collection_id=? AND producer=?",
        [.text(try WorkoutCoding.id(id)), .text(producer)], limit: 1
      ).first
      return (row?.int("record_count") ?? 0, row?.int("last_sequence") ?? 0)
    }
  }
}

struct WorkoutDeletionRecord: Codable {
  let id: String
  let messageID: String
  let requestedAt: String
  let watchRequired: Bool
  var originalWatchEndpointId: String? = nil
  var collectionKind: String? = nil
  var watchAcknowledged: Bool
  var retryAfter: Double = 0
  var transportCleanupComplete = false
  var cleanupPhase: Int = 0
  var cursorNamespace = ""
  var cursorKey = ""
  var files: [String] = []
  var directoryStack: [String]?
  var cleanupComplete: Bool { cleanupPhase >= 11 && files.isEmpty }

}

struct WorkoutHistoryDeletion: Codable, Equatable {
  let revision: String
  let deletedWorkoutID: String
}

extension PowerLogStore {
  func isWorkoutDeleted(id: String) throws -> Bool {
    let id = try WorkoutCoding.id(id)
    return try read { try $0.get(namespace: "deleted-workouts", key: id) != nil }
  }
  func requireWorkoutAvailable(id: String) throws {
    if try isWorkoutDeleted(id: id) { throw PowerLogStorageError.deleted(id) }
  }
  func workoutDeletion(id: String) throws -> WorkoutDeletionRecord? {
    try read {
      try $0.get(namespace: "deleted-workouts", key: WorkoutCoding.id(id)).map {
        try JSONDecoder().decode(WorkoutDeletionRecord.self, from: $0)
      }
    }
  }
  func historyDeletion() throws -> WorkoutHistoryDeletion? {
    try read { _ in
      let state = try catalogState()
      return state.lastDeletedWorkoutId.map {
        WorkoutHistoryDeletion(revision: String(state.revision), deletedWorkoutID: $0)
      }
    }
  }
  @discardableResult
  func markWorkoutDeleted(id: String, messageID: String = UUID().uuidString.lowercased(), watchRequired: Bool = false)
    throws -> WorkoutDeletionRecord
  {
    let id = try WorkoutCoding.id(id)
    let messageID = try WorkoutCoding.id(messageID)
    return try transaction(priority: .capture) { db in
      if let previous = try workoutDeletion(id: id) { return previous }
      var record = WorkoutDeletionRecord(
        id: id, messageID: messageID, requestedAt: WorkoutCoding.timestamp(Date()),
        watchRequired: watchRequired, watchAcknowledged: !watchRequired)
      record.collectionKind = try db.rows("SELECT kind FROM collections WHERE id=?", [.text(id)], limit: 1).first?
        .string("kind")
      try saveWorkoutDeletion(record, db: db)
      _ = try advanceCatalog(db, deletedID: id)
      if let bytes = try db.get(namespace: "phone-current", key: "workout"),
        let current = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any], current["id"] as? String == id
      {
        try db.remove(namespace: "phone-current", key: "workout")
      }
      return record
    }
  }
  private func saveWorkoutDeletion(_ record: WorkoutDeletionRecord, db: PowerLogDatabase) throws {
    try db.put(
      namespace: "deleted-workouts", key: record.id, ride: record.id, value: WorkoutCoding.encoder().encode(record))
  }
  func deletionPage(after: String = "", limit: Int = 8) throws -> [WorkoutDeletionRecord] {
    guard (1...128).contains(limit) else { throw PowerLogStorageError.invalid("Deletion discovery page exceeds bound") }
    return try read {
      try $0.page(namespace: "deleted-workouts", after: after, limit: limit).map {
        try JSONDecoder().decode(WorkoutDeletionRecord.self, from: $0.value)
      }
    }
  }
  @discardableResult
  func cleanupWorkoutPage(id: String, limit: Int = 128) throws -> WorkoutDeletionRecord {
    guard (1...128).contains(limit) else { throw PowerLogStorageError.invalid("Deletion cleanup page exceeds bound") }
    return try transaction(priority: .background) { db in
      guard var record = try workoutDeletion(id: id) else {
        throw PowerLogStorageError.invalid("Deletion requires its committed fence")
      }
      guard !record.cleanupComplete, record.files.isEmpty else { return record }
      if try Self.cleanupDistancePage(db, id: id, limit: limit) { return record }
      let seconds = try db.rows(
        "SELECT n FROM controller_second_heads WHERE ride=? LIMIT ?", [.text(id), .integer(Int64(limit))], limit: limit)
      for row in seconds {
        try db.execute("DELETE FROM controller_second_heads WHERE ride=? AND n=?", [.text(id), row["n"]])
      }
      if !seconds.isEmpty { return record }
      for table in [
        "health_import_stage", "health_parent_versions", "health_route_points", "health_route_frozen",
        "health_route_sources",
      ] {
        let rows = try db.rows(
          "SELECT rowid FROM \(table) WHERE ride=? LIMIT ?", [.text(id), .integer(Int64(limit))], limit: limit)
        for row in rows { try db.execute("DELETE FROM \(table) WHERE rowid=?", [row["rowid"]]) }
        if !rows.isEmpty { return record }
      }
      if record.cleanupPhase == 0 {
        let rows = try db.rows(
          "SELECT id,observation_id,event_id FROM collection_memberships WHERE collection_id=? LIMIT ?",
          [.text(id), .integer(Int64(limit))], limit: limit)
        for row in rows {
          let observation = row.int("observation_id")!
          try db.execute("DELETE FROM collection_memberships WHERE id=?", [row["id"]])
          if try db.scalarInt(
            "SELECT 1 FROM collection_memberships WHERE observation_id=? LIMIT 1", [.integer(observation)]) == nil
          {
            for table in ["telemetry_frames", "locations", "health_samples", "lifecycle_records"] {
              try db.execute("DELETE FROM \(table) WHERE observation_id=?", [.integer(observation)])
            }
            try db.execute("DELETE FROM observations WHERE id=?", [.integer(observation)])
          }
        }
        if rows.count < limit { record.cleanupPhase = 1 }
      } else if record.cleanupPhase <= 7 {
        let tables = [
          "collection_sources", "collection_channels", "collection_changes", "collection_versions",
          "collection_corrections", "health_deletions", "derived_cache",
        ]
        let table = tables[record.cleanupPhase - 1]
        let rows = try db.rows(
          "SELECT rowid AS cleanup_row FROM \(table) WHERE collection_id=? LIMIT ?",
          [.text(id), .integer(Int64(limit))], limit: limit)
        for row in rows { try db.execute("DELETE FROM \(table) WHERE rowid=?", [row["cleanup_row"]]) }
        if rows.count < limit { record.cleanupPhase += 1 }
      } else if record.cleanupPhase == 8 {
        let rows = try db.rows(
          "SELECT namespace,key FROM durable_records WHERE ride=? AND namespace NOT IN ('deleted-workouts','publication-fences') AND (namespace,key)>(?,?) ORDER BY namespace,key LIMIT ?",
          [.text(id), .text(record.cursorNamespace), .text(record.cursorKey), .integer(Int64(limit))], limit: limit)
        for row in rows {
          let namespace = row.string("namespace")!
          let key = row.string("key")!
          record.cursorNamespace = namespace
          record.cursorKey = key
          if namespace == "phone-outbox", let data = try db.get(namespace: namespace, key: key),
            let packet = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            ["deleteWorkout", "deleteWorkoutAck"].contains(packet["kind"] as? String ?? "")
          {
            continue
          }
          if ["sent-chunks", "incoming-chunks", "chunk-receipts"].contains(namespace),
            key.count == 64, key.allSatisfy({ $0.isHexDigit })
          {
            let file = (namespace == "incoming-chunks" ? "inbox/" : "") + key + ".plchunk"
            if !record.files.contains(file) { record.files.append(file) }
          }
          try db.remove(namespace: namespace, key: key)
        }
        if rows.count < limit {
          record.cleanupPhase = 9
          record.cursorNamespace = ""
          record.cursorKey = ""
        }
      } else if record.cleanupPhase == 9 {
        let rows = try db.rows(
          "SELECT namespace,key FROM counters WHERE (namespace,key)>(?,?) ORDER BY namespace,key LIMIT ?",
          [.text(record.cursorNamespace), .text(record.cursorKey), .integer(Int64(limit))], limit: limit)
        for row in rows {
          let namespace = row.string("namespace")!
          let key = row.string("key")!
          record.cursorNamespace = namespace
          record.cursorKey = key
          if key == id || key.hasPrefix(id + ":") {
            try db.execute("DELETE FROM counters WHERE namespace=? AND key=?", [.text(namespace), .text(key)])
          }
        }
        if rows.count < limit {
          record.cleanupPhase = 10
          record.cursorNamespace = ""
          record.cursorKey = ""
        }
      }
      if record.cleanupPhase == 10 {
        try db.execute("DELETE FROM collections WHERE id=?", [.text(id)])
      }
      try saveWorkoutDeletion(record, db: db)
      return record
    }
  }
  func deletionFilesRemoved(id: String, completed: Bool = false) throws {
    try transaction(priority: .background) { db in
      guard var record = try workoutDeletion(id: id) else {
        throw PowerLogStorageError.invalid("Deletion fence is unavailable")
      }
      record.files = []
      if completed, record.cleanupPhase == 10 { record.cleanupPhase = 11 }
      try saveWorkoutDeletion(record, db: db)
    }
  }
  func completeDeletionTransport(id: String) throws {
    try transaction { db in
      guard var record = try workoutDeletion(id: id) else {
        throw PowerLogStorageError.invalid("Deletion fence is unavailable")
      }
      guard !record.transportCleanupComplete else { return }
      record.transportCleanupComplete = true
      try saveWorkoutDeletion(record, db: db)
    }
  }
  func updateDeletionTraversal(id: String, stack: [String]) throws {
    guard stack.count <= 128 else { throw PowerLogStorageError.invalid("Deleted export nesting exceeds bound") }
    try transaction(priority: .background) { db in
      guard var record = try workoutDeletion(id: id) else {
        throw PowerLogStorageError.invalid("Deletion fence is unavailable")
      }
      record.directoryStack = stack
      try saveWorkoutDeletion(record, db: db)
    }
  }
}

extension PowerLogStore {
  func catalogState() throws -> CatalogState {
    try read { db in
      try db.get(namespace: "catalog-state", key: "deletion").map {
        try JSONDecoder().decode(CatalogState.self, from: $0)
      } ?? CatalogState(revision: 0)
    }
  }
  @discardableResult
  func advanceCatalog(_ db: PowerLogDatabase, deletedID: String? = nil) throws -> CatalogState {
    var state = try catalogState()
    state.revision = try PowerLogRevision.next(state.revision)
    if let deletedID { state.lastDeletedWorkoutId = try WorkoutCoding.id(deletedID) }
    try db.put(namespace: "catalog-state", key: "deletion", ride: nil, value: WorkoutCoding.encoder().encode(state))
    return state
  }

}

extension PowerLogStore {
  func requireWorkoutWritable(id: String) throws {
    try requireWorkoutAvailable(id: id)

  }
}

final class PowerLogReadLease {
  private static let lock = NSLock()
  private static var active: [String: Int] = [:]
  private static var deleting = Set<String>()
  private let key: String
  private var released = true
  init(store: PowerLogStore, id: String) throws {
    key = store.databaseURL.path + ":" + id
    Self.lock.lock()
    defer { Self.lock.unlock() }
    guard !Self.deleting.contains(key) else {
      throw PowerLogStorageError.invalid("Ride deletion is in progress. Try the read or export again.")
    }
    try store.requireWorkoutAvailable(id: id)
    Self.active[key, default: 0] += 1
    released = false
  }
  deinit { release() }
  func release() {
    Self.lock.lock()
    defer { Self.lock.unlock() }
    guard !released else { return }
    released = true
    let count = (Self.active[key] ?? 1) - 1
    Self.active[key] = count > 0 ? count : nil
  }
  static func withDeletion<T>(store: PowerLogStore, id: String, body: () throws -> T) throws -> T {
    let key = store.databaseURL.path + ":" + id
    lock.lock()
    guard active[key, default: 0] == 0, deleting.insert(key).inserted else {
      lock.unlock()
      throw PowerLogStorageError.invalid("The ride has an active read, export or deletion. Try deleting it again.")
    }
    lock.unlock()
    defer {
      lock.lock()
      deleting.remove(key)
      lock.unlock()
    }
    return try body()
  }
  static func isReading(store: PowerLogStore, id: String) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    return active[store.databaseURL.path + ":" + id, default: 0] > 0
  }
}
