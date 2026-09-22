import Foundation
import SQLite3
import CryptoKit

/// Limits bound each admitted unit, not the duration of a recording.
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
  static let derivedValueBytes = 4 * 1024 * 1024
}

enum PowerLogStorageError: Error, LocalizedError {
  case sqlite(Int32, String), invalid(String), conflict(String), missing(String), deleted(String), busy, revision(expected: Int64, actual: Int64)
  var bridgeCode: String? {
    if case .deleted = self { return "ERR_RIDE_DELETED" }
    return nil
  }
  var errorDescription: String? {
    switch self {
    case .sqlite(let code, let message): return "Storage error \(code): \(message)"
    case .invalid(let message), .conflict(let message), .missing(let message): return message
    case .deleted: return "This ride was deleted from Power Log."
    case .busy: return "Storage work queue is full. Retry the pending operation."
    case .revision(let expected, let actual): return "Source revision changed from \(expected) to \(actual). Retry the read."
    }
  }
}

enum PowerLogSQLValue: Equatable {
  case integer(Int64), real(Double), text(String), blob(Data), null
  var int: Int64? { if case .integer(let v) = self { return v }; return nil }
  var double: Double? { switch self { case .integer(let v): return Double(v); case .real(let v): return v; default: return nil } }
  var string: String? { if case .text(let v) = self { return v }; return nil }
  var data: Data? { if case .blob(let v) = self { return v }; return nil }
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

enum PowerLogJobPriority: Int { case capture = 0, normal = 1, background = 2 }

/// This handle is valid only inside the owning store's read/transaction closure.
/// Access is checked at runtime so accidentally retained handles cannot race SQLite.
final class PowerLogDatabase {
  fileprivate let handle: OpaquePointer
  fileprivate var assertExecutor: () -> Void = {}
  private var statements: [String: OpaquePointer] = [:]
  private var order: [String] = []
  fileprivate init(_ handle: OpaquePointer) { self.handle = handle }
  deinit { for statement in statements.values { sqlite3_finalize(statement) } }
  private func statement(_ sql: String, _ values: [PowerLogSQLValue]) throws -> OpaquePointer {
    assertExecutor()
    let s: OpaquePointer
    if let cached = statements[sql] { s = cached }
    else {
      var prepared: OpaquePointer?
      guard sqlite3_prepare_v2(handle, sql, -1, &prepared, nil) == SQLITE_OK, let prepared else { throw error() }
      s = prepared
      if order.count >= 64, let first = order.first { order.removeFirst(); if let old = statements.removeValue(forKey: first) { sqlite3_finalize(old) } }
      order.append(sql); statements[sql] = s
    }
    sqlite3_reset(s); sqlite3_clear_bindings(s)
    guard sqlite3_bind_parameter_count(s) == Int32(values.count) else { throw PowerLogStorageError.invalid("SQL parameter count mismatch") }
    let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    for (offset, value) in values.enumerated() {
      let i = Int32(offset + 1), rc: Int32
      switch value {
      case .integer(let v): rc = sqlite3_bind_int64(s, i, v)
      case .real(let v): guard v.isFinite else { throw PowerLogStorageError.invalid("Non-finite storage value") }; rc = sqlite3_bind_double(s, i, v)
      case .text(let v): rc = v.withCString { sqlite3_bind_text(s, i, $0, -1, transient) }
      case .blob(let v):
        if v.isEmpty { rc = sqlite3_bind_zeroblob(s, i, 0) }
        else { rc = v.withUnsafeBytes { sqlite3_bind_blob(s, i, $0.baseAddress, Int32(v.count), transient) } }
      case .null: rc = sqlite3_bind_null(s, i)
      }
      guard rc == SQLITE_OK else { throw error() }
    }
    return s
  }
  fileprivate func error() -> PowerLogStorageError { .sqlite(sqlite3_extended_errcode(handle), String(cString: sqlite3_errmsg(handle))) }
  @discardableResult
  func execute(_ sql: String, _ values: [PowerLogSQLValue] = []) throws -> Int {
    let s = try statement(sql, values); defer { sqlite3_reset(s) }
    guard sqlite3_step(s) == SQLITE_DONE else { throw error() }
    return Int(sqlite3_changes(handle))
  }
  func rows(_ sql: String, _ values: [PowerLogSQLValue] = [], limit: Int = PowerLogStorageLimits.pageRows) throws -> [PowerLogRow] {
    guard (1...PowerLogStorageLimits.pageRows).contains(limit) else { throw PowerLogStorageError.invalid("Storage page limit must be 1...512") }
    let s = try statement(sql, values); defer { sqlite3_reset(s) }
    var result: [PowerLogRow] = []
    var pageBytes = 0
    while true {
      let rc = sqlite3_step(s)
      if rc == SQLITE_DONE { return result }
      guard rc == SQLITE_ROW else { throw error() }
      guard result.count < limit else { throw PowerLogStorageError.invalid("Unbounded database read rejected; use keyset pages") }
      var row: [String: PowerLogSQLValue] = [:]
      for i in 0..<sqlite3_column_count(s) {
        pageBytes += max(8, Int(sqlite3_column_bytes(s, i)))
        guard pageBytes <= PowerLogStorageLimits.pageBytes else { throw PowerLogStorageError.invalid("Database page byte limit exceeded; request fewer rows") }
        let key = String(cString: sqlite3_column_name(s, i)), value: PowerLogSQLValue
        switch sqlite3_column_type(s, i) {
        case SQLITE_INTEGER: value = .integer(sqlite3_column_int64(s, i))
        case SQLITE_FLOAT: value = .real(sqlite3_column_double(s, i))
        case SQLITE_TEXT: value = .text(String(cString: sqlite3_column_text(s, i)))
        case SQLITE_BLOB:
          let count = Int(sqlite3_column_bytes(s, i))
          guard count <= PowerLogStorageLimits.derivedValueBytes else { throw PowerLogStorageError.invalid("Stored value exceeds read byte limit") }
          value = count == 0 ? .blob(Data()) : .blob(Data(bytes: sqlite3_column_blob(s, i)!, count: count))
        default: value = .null
        }
        row[key] = value
      }
      result.append(PowerLogRow(values: row))
    }
  }
  func scalarInt(_ sql: String, _ values: [PowerLogSQLValue] = []) throws -> Int64? {
    let s = try statement(sql, values); defer { sqlite3_reset(s) }
    let rc = sqlite3_step(s)
    if rc == SQLITE_DONE { return nil }
    guard rc == SQLITE_ROW else { throw error() }
    return sqlite3_column_type(s, 0) == SQLITE_NULL ? nil : sqlite3_column_int64(s, 0)
  }
  var lastInsertedID: Int64 { assertExecutor(); return sqlite3_last_insert_rowid(handle) }

  func get(namespace: String, key: String) throws -> Data? {
    try rows("SELECT value FROM durable_records WHERE namespace=? AND key=?", [.text(namespace), .text(key)], limit: 1).first?.data("value")
  }
  func put(namespace: String, key: String, value: Data, immutable: Bool = false) throws {
    guard !namespace.isEmpty, namespace.utf8.count <= 128, !key.isEmpty, key.utf8.count <= 512,
          value.count <= PowerLogStorageLimits.derivedValueBytes else { throw PowerLogStorageError.invalid("Durable record exceeds bounds") }
    if immutable, let old = try get(namespace: namespace, key: key) {
      guard old == value else { throw PowerLogStorageError.conflict("Conflicting immutable record: \(namespace)") }; return
    }
    try execute("INSERT INTO durable_records(namespace,key,value) VALUES(?,?,?) ON CONFLICT(namespace,key) DO UPDATE SET value=excluded.value", [.text(namespace), .text(key), .blob(value)])
  }
  func page(namespace: String, after: String = "", limit: Int = 256) throws -> [(key: String, value: Data)] {
    try rows("SELECT key,value FROM durable_records WHERE namespace=? AND key>? ORDER BY key LIMIT ?", [.text(namespace), .text(after), .integer(Int64(limit))], limit: limit).map { ($0.string("key")!, $0.data("value")!) }
  }
  func remove(namespace: String, key: String) throws { try execute("DELETE FROM durable_records WHERE namespace=? AND key=?", [.text(namespace), .text(key)]) }
  func nextSequence(namespace: String, key: String) throws -> Int64 {
    try execute("INSERT INTO counters(namespace,key,value) VALUES(?,?,1) ON CONFLICT(namespace,key) DO UPDATE SET value=value+1", [.text(namespace), .text(key)])
    return try scalarInt("SELECT value FROM counters WHERE namespace=? AND key=?", [.text(namespace), .text(key)])!
  }
}

struct PowerLogEventRecord {
  let event: WorkoutEvent
  let sequence: Int64
  let producer: String
  let revision: Int64
  let rowID: Int64
}

/// One connection and one executor per canonical database, shared by all native facades.
final class PowerLogStore: @unchecked Sendable {
  static let telemetryColumns = ["humanPowerW", "cadenceRpm", "motorInputPowerW", "batteryVoltageV", "batteryCurrentA", "motorCurrentA", "motorRpm", "pedalTorqueNm", "controllerTempC", "motorTempC", "consumedAh", "consumedWh", "throttleVoltageV", "faultCode", "assistLevel", "raceMode", "speedRaw", "controllerSpeedMps"]
  static let locationColumns = ["latitude", "longitude", "altitudeMeters", "horizontalAccuracyM", "verticalAccuracyM", "speedMps", "courseDegrees", "speedAccuracyMps", "courseAccuracyDegrees"]
  static let healthColumns = ["heartRateBpm", "activeEnergyKcal", "basalEnergyKcal", "distanceMeters", "riderPowerW", "cadenceRpm", "speedMps", "value", "sampleCount"]
  private static let registryLock = NSLock()
  private static var registry: [String: WeakStore] = [:]
  private final class WeakStore { weak var value: PowerLogStore?; init(_ value: PowerLogStore) { self.value = value } }
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
  private var commitActions: [() -> Void] = []
  private var transactionRecords = 0
  private var transactionBytes = 0
  private var transactionFailure: Error?
  private var metrics: [String: Double] = ["jobs": 0, "queueMilliseconds": 0, "executionMilliseconds": 0, "receiptHits": 0]
  /// Fault injection is configured only by software tests; runs before outer COMMIT.
  var beforeCommitForTesting: (() throws -> Void)?

  static func databaseURL(forRoot root: URL) -> URL {
    if root.lastPathComponent == "workouts" {
      return root.deletingLastPathComponent().appendingPathComponent("power-log.sqlite3")
    }
    return root.appendingPathComponent("power-log.sqlite3")
  }
  static let schemaVersion = 2
  static func shared(databaseURL: URL) throws -> PowerLogStore {
    let url = databaseURL.standardizedFileURL
    registryLock.lock(); defer { registryLock.unlock() }
    if let existing = registry[url.path]?.value { return existing }
    let store = try PowerLogStore(databaseURL: url)
    registry[url.path] = WeakStore(store)
    return store
  }
  private init(databaseURL: URL) throws {
    self.databaseURL = databaseURL
    guard sqlite3_libversion_number() >= 3_037_000 else { throw PowerLogStorageError.invalid("Power Log requires SQLite 3.37 or newer") }
    runtimeVersion = String(cString: sqlite3_libversion()); runtimeSourceID = String(cString: sqlite3_sourceid())
    let fm = FileManager.default, parent = databaseURL.deletingLastPathComponent()
    try fm.createDirectory(at: parent, withIntermediateDirectories: true)
    #if os(iOS) || os(watchOS)
    try fm.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: parent.path)
    #endif
    var handle: OpaquePointer?
    let rc = sqlite3_open_v2(databaseURL.path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX, nil)
    guard rc == SQLITE_OK, let handle else {
      let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "Unable to open canonical database"
      if let handle { sqlite3_close(handle) }
      throw PowerLogStorageError.sqlite(rc, message)
    }
    db = PowerLogDatabase(handle)
    queue.setSpecific(key: key, value: 1)
    db.assertExecutor = { [weak self] in precondition(self != nil && DispatchQueue.getSpecific(key: self!.key) != nil, "SQLite handle used outside its serialized executor") }
    try read { db in
      sqlite3_extended_result_codes(handle, 1)
      sqlite3_busy_timeout(handle, 1000)
      _ = try db.rows("PRAGMA journal_mode=WAL", limit: 1)
      try db.execute("PRAGMA synchronous=FULL")
      try db.execute("PRAGMA foreign_keys=ON")
      _ = try db.rows("PRAGMA wal_autocheckpoint=1000", limit: 1)
      try db.execute("PRAGMA cache_size=-4096")
      try db.execute("PRAGMA temp_store=FILE")
      let version = try db.scalarInt("PRAGMA user_version") ?? 0
      guard version == 0 || version == Self.schemaVersion else {
        throw PowerLogStorageError.invalid("This database was created by another Power Log version. Delete the app and reinstall it to start fresh.")
      }
      try db.execute("BEGIN IMMEDIATE")
      do {
        for sql in Self.schema { try db.execute(sql) }
        try db.execute("PRAGMA user_version=\(Self.schemaVersion)")
        try db.execute("COMMIT")
        metrics["commits", default: 0] += 1
      }
      catch { _ = try? db.execute("ROLLBACK"); throw error }
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
      guard jobs.count < PowerLogStorageLimits.pendingJobs else { admission.unlock(); throw PowerLogStorageError.busy }
      jobs.append((priority, { [self] in
        let started = ProcessInfo.processInfo.systemUptime
        result = Result { try escaped() }
        metrics["jobs", default: 0] += 1
        metrics["queueMilliseconds", default: 0] += (started - enqueued) * 1000
        metrics["executionMilliseconds", default: 0] += (ProcessInfo.processInfo.systemUptime - started) * 1000
      }, signal))
      if !draining { draining = true; queue.async { [self] in drain() } }
      admission.unlock()
      signal.wait()
      return try result!.get()
    }
  }
  private func drain() {
    while true {
      admission.lock()
      guard !jobs.isEmpty else { draining = false; admission.unlock(); return }
      let index = jobs.indices.min { jobs[$0].priority.rawValue < jobs[$1].priority.rawValue }!
      var job: (priority: PowerLogJobPriority, run: () -> Void, signal: DispatchSemaphore)? = jobs.remove(at: index)
      admission.unlock()
      let signal = job!.signal
      // A continuously busy drain may span many capture/import pages. Release Foundation
      // temporaries per admitted unit even when the dispatch work item never becomes idle.
      autoreleasepool { job!.run() }; job = nil
      signal.signal()
    }
  }
  func read<T>(priority: PowerLogJobPriority = .normal, _ body: (PowerLogDatabase) throws -> T) throws -> T {
    try perform(priority: priority) { try body(db) }
  }
  func transaction<T>(priority: PowerLogJobPriority = .capture, _ body: (PowerLogDatabase) throws -> T) throws -> T {
    try perform(priority: priority) {
      if transactionDepth > 0 {
        do { return try body(db) } catch { transactionFailure = error; throw error }
      }
      try db.execute("BEGIN IMMEDIATE"); transactionDepth = 1; transactionFailure = nil; transactionRecords = 0; transactionBytes = 0
      defer { transactionDepth = 0; transactionFailure = nil; commitActions.removeAll() }
      do {
        let result = try body(db)
        if let failure = transactionFailure { throw failure }
        try beforeCommitForTesting?()
        try db.execute("COMMIT")
        metrics["commits", default: 0] += 1
        let actions = commitActions; commitActions.removeAll()
        for action in actions { action() }
        return result
      } catch { _ = try? db.execute("ROLLBACK"); throw error }
    }
  }
  /// Updates caller-owned memory only after an enclosing transaction commits. Actions must not access SQLite.
  func afterCommit(_ action: @escaping () -> Void) throws {
    try read { _ in
      if transactionDepth > 0 { commitActions.append(action) } else { action() }
    }
  }
  func checkpoint() throws {
    try read(priority: .background) { db in
      guard transactionDepth == 0 else { throw PowerLogStorageError.invalid("Checkpoint inside a transaction") }
      let rc = sqlite3_wal_checkpoint_v2(db.handle, nil, SQLITE_CHECKPOINT_PASSIVE, nil, nil)
      guard rc == SQLITE_OK else { throw db.error() }
    }
  }
  func diagnostics() throws -> [String: Any] {
    try read { _ in
      var result: [String: Any] = metrics
      result["sqliteVersion"] = runtimeVersion; result["sqliteSourceID"] = runtimeSourceID
      for (name, suffix) in [("databaseBytes", ""), ("walBytes", "-wal")] {
        result[name] = (try? FileManager.default.attributesOfItem(atPath: databaseURL.path + suffix)[.size] as? NSNumber)?.int64Value ?? 0
      }
      return result
    }
  }
  func recordReceiptHit() throws { try read { _ in metrics["receiptHits", default: 0] += 1 } }

  private static var schema: [String] {
    [
      "CREATE TABLE IF NOT EXISTS collections(id TEXT PRIMARY KEY,kind TEXT NOT NULL,started_at TEXT NOT NULL,ended_at TEXT,phase TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,event_count INTEGER NOT NULL DEFAULT 0,metadata BLOB,monotonic_origin REAL,min_query_us INTEGER,max_query_us INTEGER,min_elapsed REAL,max_elapsed REAL,channels BLOB NOT NULL DEFAULT X'5B5D') STRICT",
      "CREATE INDEX IF NOT EXISTS catalog_order ON collections(kind,started_at DESC,id DESC)",
      "CREATE TABLE IF NOT EXISTS observations(id INTEGER PRIMARY KEY,physical_id TEXT NOT NULL UNIQUE,kind TEXT NOT NULL,source TEXT NOT NULL,original_timestamp TEXT NOT NULL,utc_seconds REAL NOT NULL,query_us INTEGER NOT NULL,clock_epoch TEXT,monotonic_seconds REAL,source_elapsed REAL,representation TEXT NOT NULL DEFAULT '',content_hash BLOB NOT NULL,extra BLOB NOT NULL) STRICT",
      "CREATE TABLE IF NOT EXISTS telemetry_frames(observation_id INTEGER PRIMARY KEY REFERENCES observations(id),\(telemetryColumns.map { "\($0) ANY" }.joined(separator: ","))) STRICT",
      "CREATE TABLE IF NOT EXISTS locations(observation_id INTEGER PRIMARY KEY REFERENCES observations(id),\(locationColumns.map { "\($0) ANY" }.joined(separator: ","))) STRICT",
      "CREATE TABLE IF NOT EXISTS health_samples(observation_id INTEGER PRIMARY KEY REFERENCES observations(id),\(healthColumns.map { "\($0) ANY" }.joined(separator: ",")),identifier TEXT,external_id TEXT,unit TEXT,start_timestamp TEXT,end_timestamp TEXT) STRICT",
      "CREATE INDEX IF NOT EXISTS health_identifier ON health_samples(identifier,observation_id)",
      "CREATE INDEX IF NOT EXISTS health_external ON health_samples(external_id,observation_id)",
      "CREATE TABLE IF NOT EXISTS distance_generations(storage_id INTEGER PRIMARY KEY AUTOINCREMENT,collection_id TEXT NOT NULL,generation TEXT NOT NULL,revision INTEGER NOT NULL,policy INTEGER NOT NULL,state BLOB NOT NULL,UNIQUE(collection_id,generation)) STRICT",
      "CREATE INDEX IF NOT EXISTS distance_generation_revision ON distance_generations(collection_id,revision DESC)",
      "CREATE TABLE IF NOT EXISTS distance_snapshots(collection_id TEXT NOT NULL,revision INTEGER NOT NULL,policy INTEGER NOT NULL,generation TEXT NOT NULL,value BLOB NOT NULL,PRIMARY KEY(collection_id,revision,policy)) STRICT",
      "CREATE TABLE IF NOT EXISTS distance_points(generation_id INTEGER NOT NULL,source TEXT NOT NULL,point_id INTEGER NOT NULL,elapsed_seconds REAL NOT NULL,timestamp TEXT NOT NULL,distance REAL NOT NULL,increment REAL NOT NULL,start_seconds REAL NOT NULL,end_seconds REAL NOT NULL,segment INTEGER NOT NULL,start_anchor TEXT NOT NULL,end_anchor TEXT NOT NULL,start_speed REAL,end_speed REAL,indivisible INTEGER NOT NULL,covered REAL NOT NULL,PRIMARY KEY(generation_id,point_id)) STRICT",
      "CREATE INDEX IF NOT EXISTS distance_point_time ON distance_points(generation_id,source,elapsed_seconds,point_id)",
      "CREATE TABLE IF NOT EXISTS distance_health_inputs(collection_id TEXT NOT NULL,generation TEXT NOT NULL,source TEXT NOT NULL,input_id INTEGER NOT NULL,start_seconds REAL NOT NULL,end_seconds REAL NOT NULL,value BLOB NOT NULL,PRIMARY KEY(collection_id,generation,source,input_id)) STRICT",
      "CREATE INDEX IF NOT EXISTS distance_health_time ON distance_health_inputs(collection_id,generation,source,start_seconds,input_id)",
      "CREATE TABLE IF NOT EXISTS lifecycle_records(observation_id INTEGER PRIMARY KEY REFERENCES observations(id),action TEXT NOT NULL) STRICT",
      "CREATE TABLE IF NOT EXISTS collection_memberships(id INTEGER PRIMARY KEY,collection_id TEXT NOT NULL REFERENCES collections(id),observation_id INTEGER NOT NULL REFERENCES observations(id),event_id TEXT NOT NULL,producer TEXT NOT NULL,sequence INTEGER NOT NULL,ordinal INTEGER NOT NULL,kind TEXT NOT NULL,source TEXT NOT NULL,raw_heart INTEGER NOT NULL DEFAULT 0,elapsed_seconds REAL,original_elapsed_seconds REAL,deleted INTEGER NOT NULL DEFAULT 0,elapsed_us INTEGER,query_us INTEGER NOT NULL,revision INTEGER NOT NULL,mapping BLOB NOT NULL,UNIQUE(collection_id,event_id),UNIQUE(collection_id,producer,sequence),UNIQUE(collection_id,observation_id)) STRICT",
      "CREATE INDEX IF NOT EXISTS membership_observation ON collection_memberships(observation_id)",
      "CREATE INDEX IF NOT EXISTS membership_time ON collection_memberships(collection_id,elapsed_seconds,observation_id)",
      "CREATE INDEX IF NOT EXISTS membership_event ON collection_memberships(event_id)",
      "CREATE INDEX IF NOT EXISTS membership_export_time ON collection_memberships(collection_id,elapsed_seconds,CASE WHEN kind='lifecycle' THEN 0 ELSE 1 END,event_id,id)",
      "CREATE INDEX IF NOT EXISTS membership_utc ON collection_memberships(collection_id,query_us,observation_id)",
      "CREATE INDEX IF NOT EXISTS membership_source_time ON collection_memberships(collection_id,kind,source,raw_heart,elapsed_seconds,observation_id)",
      "CREATE INDEX IF NOT EXISTS membership_stream_time ON collection_memberships(collection_id,kind,source,elapsed_seconds,observation_id)",
      "CREATE TABLE IF NOT EXISTS collection_corrections(collection_id TEXT NOT NULL REFERENCES collections(id),target_event_id TEXT NOT NULL,replacement_event_id TEXT NOT NULL,revision INTEGER NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(collection_id,target_event_id,revision)) STRICT",
      "CREATE TABLE IF NOT EXISTS collection_versions(collection_id TEXT NOT NULL REFERENCES collections(id),revision INTEGER NOT NULL,metadata BLOB,PRIMARY KEY(collection_id,revision)) STRICT",
      "CREATE INDEX IF NOT EXISTS membership_snapshot ON collection_memberships(collection_id,revision,id)",
      "CREATE TABLE IF NOT EXISTS collection_sources(collection_id TEXT NOT NULL REFERENCES collections(id),producer TEXT NOT NULL,last_sequence INTEGER NOT NULL DEFAULT 0,record_count INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(collection_id,producer)) STRICT",
      "CREATE TABLE IF NOT EXISTS collection_channels(collection_id TEXT NOT NULL REFERENCES collections(id),metric TEXT NOT NULL,source TEXT NOT NULL,representation TEXT NOT NULL,count INTEGER NOT NULL,min_elapsed REAL,max_elapsed REAL,PRIMARY KEY(collection_id,metric,source,representation)) STRICT",
      "CREATE TABLE IF NOT EXISTS health_deletions(collection_id TEXT NOT NULL REFERENCES collections(id),source TEXT NOT NULL,external_id TEXT NOT NULL,revision INTEGER NOT NULL,tombstone_event_id TEXT NOT NULL,PRIMARY KEY(collection_id,source,external_id,revision)) STRICT",
      "CREATE TABLE IF NOT EXISTS collection_changes(collection_id TEXT NOT NULL REFERENCES collections(id),revision INTEGER NOT NULL,min_us INTEGER,max_us INTEGER,kind TEXT NOT NULL,metrics BLOB NOT NULL,PRIMARY KEY(collection_id,revision)) STRICT",
      "CREATE TABLE IF NOT EXISTS derived_cache(key TEXT PRIMARY KEY,collection_id TEXT NOT NULL REFERENCES collections(id),revision INTEGER NOT NULL,interpretation TEXT NOT NULL,value BLOB NOT NULL) STRICT",
      "CREATE INDEX IF NOT EXISTS derived_cache_collection ON derived_cache(collection_id)",
      "CREATE TABLE IF NOT EXISTS durable_records(namespace TEXT NOT NULL,key TEXT NOT NULL,value BLOB NOT NULL,PRIMARY KEY(namespace,key)) STRICT",
      "CREATE TABLE IF NOT EXISTS counters(namespace TEXT NOT NULL,key TEXT NOT NULL,value INTEGER NOT NULL,PRIMARY KEY(namespace,key)) STRICT"
    ]
  }

  func createCollection(id: String, kind: String, startedAt: String, monotonicOrigin: Double? = nil, metadata: Data? = nil) throws {
    let id = try WorkoutCoding.id(id)
    _ = try WorkoutCoding.date(startedAt)
    guard ["workout", "live"].contains(kind), metadata?.count ?? 0 <= PowerLogStorageLimits.recordBytes,
          monotonicOrigin?.isFinite ?? true else { throw PowerLogStorageError.invalid("Invalid collection") }
    try transaction { db in
      try requireWorkoutAvailable(id: id)
      guard try db.scalarInt("SELECT count(*) FROM collections WHERE id=?", [.text(id)]) == 0 else { throw PowerLogStorageError.conflict("Collection already exists") }
      try db.execute("INSERT INTO collections(id,kind,started_at,phase,metadata,monotonic_origin) VALUES(?,?,?,'running',?,?)", [.text(id), .text(kind), .text(startedAt), metadata.map(PowerLogSQLValue.blob) ?? .null, .optional(monotonicOrigin)])
      try db.execute("INSERT INTO collection_versions(collection_id,revision,metadata) VALUES(?,0,?)", [.text(id), metadata.map(PowerLogSQLValue.blob) ?? .null])
    }
  }
  func ensureLiveCollection(id: String, startedAt: String, monotonicOrigin: Double) throws {
    try transaction { db in
      if let row = try db.rows("SELECT kind,started_at,monotonic_origin FROM collections WHERE id=?", [.text(id)], limit: 1).first {
        guard row.string("kind") == "live", row.string("started_at") == startedAt,
          row.double("monotonic_origin") == monotonicOrigin else {
          throw PowerLogStorageError.conflict("Live capture identity changed")
        }
      } else { try createCollection(id: id, kind: "live", startedAt: startedAt, monotonicOrigin: monotonicOrigin) }
    }
  }
  func recoverOnce(kind: String, _ body: () throws -> Void) throws {
    recoveryCondition.lock()
    while recoveringKinds.contains(kind) { recoveryCondition.wait() }
    if recoveredKinds.contains(kind) { recoveryCondition.unlock(); return }
    recoveringKinds.insert(kind); recoveryCondition.unlock()
    do {
      // Each recovery page obtains/releases the executor; no enclosing read pins it.
      try body()
      recoveryCondition.lock(); recoveringKinds.remove(kind); recoveredKinds.insert(kind)
      recoveryCondition.broadcast(); recoveryCondition.unlock()
    } catch {
      recoveryCondition.lock(); recoveringKinds.remove(kind); recoveryCondition.broadcast(); recoveryCondition.unlock()
      throw error
    }
  }
  func collection(id: String) throws -> PowerLogRow {
    try read { db in
      try requireWorkoutAvailable(id: id)
      guard let row = try db.rows("SELECT * FROM collections WHERE id=?", [.text(try WorkoutCoding.id(id))], limit: 1).first else { throw PowerLogStorageError.missing("Collection does not exist") }
      return row
    }
  }
  func catalog(kind: String, beforeStartedAt: String? = nil, beforeID: String = "", limit: Int = 100) throws -> [PowerLogRow] {
    let pageLimit = max(1, min(100, limit))
    return try read { db in
      if let beforeStartedAt {
        return try db.rows("SELECT * FROM collections WHERE kind=? AND NOT EXISTS(SELECT 1 FROM durable_records d WHERE d.namespace='deleted-workouts' AND d.key=collections.id) AND (started_at<? OR (started_at=? AND id<?)) ORDER BY started_at DESC,id DESC LIMIT ?", [.text(kind), .text(beforeStartedAt), .text(beforeStartedAt), .text(beforeID), .integer(Int64(pageLimit))], limit: pageLimit)
      }
      return try db.rows("SELECT * FROM collections WHERE kind=? AND NOT EXISTS(SELECT 1 FROM durable_records d WHERE d.namespace='deleted-workouts' AND d.key=collections.id) ORDER BY started_at DESC,id DESC LIMIT ?", [.text(kind), .integer(Int64(pageLimit))], limit: pageLimit)
    }
  }
  static func microseconds(_ seconds: Double) throws -> Int64 {
    guard seconds.isFinite, seconds >= -9e12, seconds <= 9e12 else { throw PowerLogStorageError.invalid("Invalid source clock") }
    return Int64((seconds * 1_000_000).rounded(.down))
  }
  private static func numeric(_ value: WorkoutJSON?) -> PowerLogSQLValue {
    switch value { case .integer(let v): return .integer(v); case .unsigned(let v) where v <= UInt64(Int64.max): return .integer(Int64(v)); case .number(let v): return .real(v); default: return .null }
  }
  private static func json(_ value: PowerLogSQLValue) -> WorkoutJSON? {
    switch value { case .integer(let v): return .integer(v); case .real(let v): return .number(v); case .text(let v): return .string(v); default: return nil }
  }
  private static let mappingKeys: Set<String> = ["elapsedSeconds", "sequence", "timelineMappingUncertainty"]
  static func physicalIdentity(_ event: WorkoutEvent) throws -> String {
    if event.kind == "telemetry", let session = event.payload["captureSessionID"]?.string,
       let sequence = event.payload["observationSequence"] {
      let normalized = try WorkoutCoding.id(session)
      let seq: String
      switch sequence {
      case .integer(let v) where v >= 0: seq = String(v)
      case .unsigned(let v): seq = String(v)
      case .string(let v): guard let n = UInt64(v) else { throw PowerLogStorageError.invalid("Invalid observation sequence") }; seq = String(n)
      default: throw PowerLogStorageError.invalid("Observation sequence must retain its original integer representation")
      }
      return "cyc:\(normalized):\(seq)"
    }
    return "event:\(try WorkoutCoding.id(event.eventId))"
  }

  @discardableResult
  func appendTelemetry(_ sample: [String: Any], collectionID: String, elapsedSeconds: Double?, producer: String = "cyc") throws -> Int {
    guard let timestamp = sample["timestamp"] as? String else { throw PowerLogStorageError.invalid("Telemetry has no original timestamp") }
    let eventID = sample["observationId"] as? String ?? sample["eventId"] as? String ?? UUID().uuidString.lowercased()
    let event = try WorkoutEvent(dictionary: ["schemaVersion": 1, "eventId": eventID, "workoutId": collectionID,
      "kind": "telemetry", "source": "cyc", "timestamp": timestamp, "elapsedSeconds": elapsedSeconds.map { $0 as Any } ?? NSNull(), "payload": sample])
    return try appendBatch([event], producer: producer)
  }

  @discardableResult
  func appendBatch(_ events: [WorkoutEvent], producer: String? = nil, firstSequence: Int64? = nil) throws -> Int {
    guard events.count <= PowerLogStorageLimits.transactionRecords else { throw PowerLogStorageError.invalid("Observation batch exceeds 512 records") }
    if events.isEmpty { return 0 }
    guard producer == nil || !(producer!.isEmpty) && producer!.utf8.count <= 128 else { throw PowerLogStorageError.invalid("Invalid archival producer") }
    if let firstSequence { guard firstSequence > 0, firstSequence <= Int64.max - Int64(events.count - 1) else { throw PowerLogStorageError.invalid("Invalid archival sequence range") } }
    if firstSequence != nil && producer == nil { throw PowerLogStorageError.invalid("Explicit sequence requires a producer") }
    var encodedBytes = 0
    for event in events { encodedBytes += try event.validate() }
    guard encodedBytes <= PowerLogStorageLimits.transactionBytes else { throw PowerLogStorageError.invalid("Observation batch exceeds byte limit") }
    return try transaction { db in
      transactionRecords += events.count; transactionBytes += encodedBytes
      guard transactionRecords <= PowerLogStorageLimits.transactionRecords, transactionBytes <= PowerLogStorageLimits.transactionBytes else { throw PowerLogStorageError.invalid("Outer transaction exceeds capture batch bounds") }
      var inserted = 0
      for (offset, event) in events.enumerated() {
        let supplied = firstSequence.map { $0 + Int64(offset) }
        inserted += try insert(event, producer: producer ?? event.source, suppliedSequence: supplied, db: db) ? 1 : 0
      }
      return inserted
    }
  }
  private func insert(_ event: WorkoutEvent, producer: String, suppliedSequence: Int64?, db: PowerLogDatabase) throws -> Bool {
    let collectionID = try WorkoutCoding.id(event.workoutId), eventID = try WorkoutCoding.id(event.eventId)
    try requireWorkoutAvailable(id: collectionID)
    guard let collection = try db.rows("SELECT * FROM collections WHERE id=?", [.text(collectionID)], limit: 1).first else { throw PowerLogStorageError.missing("Collection does not exist") }
    var payload = event.payload
    var mapping: [String: WorkoutJSON] = [:]
    for name in Self.mappingKeys { if let value = payload.removeValue(forKey: name) { mapping[name] = value } }
    // Timestamp is already retained as an original string in observations. Preserve its presence in the mapping.
    if let value = payload.removeValue(forKey: "timestamp") { mapping["timestamp"] = value }
    let physical = try Self.physicalIdentity(event)
    var identityPayload = payload
    identityPayload["_timestamp"] = .string(event.timestamp)
    identityPayload["_kind"] = .string(event.kind); identityPayload["_source"] = .string(event.source)
    let digest = Data(SHA256.hash(data: try WorkoutCoding.encoder().encode(identityPayload)))
    let original = try WorkoutCoding.date(event.timestamp).timeIntervalSince1970
    let queryUS = try Self.microseconds(original)
    let elapsed = try event.elapsedSeconds ?? (original - WorkoutCoding.date(collection.string("started_at")!).timeIntervalSince1970)
    guard elapsed.isFinite, abs(elapsed) <= 2_678_400 else { throw PowerLogStorageError.invalid("Invalid collection time mapping") }
    let elapsedUS = try Self.microseconds(elapsed)
    let mappingData = try WorkoutCoding.encoder().encode(mapping)
    let observationID: Int64
    if let old = try db.rows("SELECT id,content_hash FROM observations WHERE physical_id=?", [.text(physical)], limit: 1).first {
      guard old.data("content_hash") == digest else { throw PowerLogStorageError.conflict("Conflicting replay of original observation identity") }
      observationID = old.int("id")!
    } else {
      let columns: [String]
      switch event.kind { case "telemetry": columns = Self.telemetryColumns; case "location": columns = Self.locationColumns; case "health": columns = Self.healthColumns; default: columns = [] }
      var extra = payload
      for name in columns { if Self.numeric(payload[name]) != .null { extra.removeValue(forKey: name) } }
      let representation = payload["representation"]?.string ?? (event.kind == "health" && payload["healthKitUUID"] != nil ? "raw" : "")
      let extraData = try WorkoutCoding.encoder().encode(extra)
      guard extraData.count <= PowerLogStorageLimits.extensionBytes else { throw PowerLogStorageError.invalid("Observation extension exceeds byte limit") }
      try db.execute("INSERT INTO observations(physical_id,kind,source,original_timestamp,utc_seconds,query_us,clock_epoch,monotonic_seconds,source_elapsed,representation,content_hash,extra) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", [.text(physical), .text(event.kind), .text(event.source), .text(event.timestamp), .real(original), .integer(queryUS), .optional(payload["clockEpoch"]?.string), .optional(payload["acquisitionMonotonic"]?.number), .optional(payload["sourceElapsedSeconds"]?.number), .text(representation), .blob(digest), .blob(extraData)])
      observationID = db.lastInsertedID
      if !columns.isEmpty {
        let table = event.kind == "telemetry" ? "telemetry_frames" : event.kind == "location" ? "locations" : "health_samples"
        let sql = "INSERT INTO \(table)(observation_id,\(columns.joined(separator: ","))) VALUES(\(Array(repeating: "?", count: columns.count + 1).joined(separator: ",")))"
        try db.execute(sql, [.integer(observationID)] + columns.map { Self.numeric(payload[$0]) })
        if event.kind == "health" {
          try db.execute("UPDATE health_samples SET identifier=?,external_id=?,unit=?,start_timestamp=?,end_timestamp=? WHERE observation_id=?", [.optional(payload["healthKitIdentifier"]?.string), .optional((payload["healthKitUUID"]?.string ?? payload["sampleUUID"]?.string)?.lowercased()), .optional(payload["unit"]?.string), .optional(payload["sampleStart"]?.string ?? payload["startDate"]?.string), .optional(payload["sampleEnd"]?.string ?? payload["endDate"]?.string), .integer(observationID)])
        }
      } else {
        try db.execute("INSERT INTO lifecycle_records(observation_id,action) VALUES(?,?)", [.integer(observationID), .text(payload["action"]!.string!)])
      }
    }
    if let old = try db.rows("SELECT * FROM collection_memberships WHERE collection_id=? AND (event_id=? OR observation_id=?)", [.text(collectionID), .text(eventID), .integer(observationID)], limit: 1).first {
      // An absent original elapsed was mapped once on admission. A later metadata
      // confirmation must not reinterpret that same original when a transport retries it.
      guard old.int("observation_id") == observationID, old.string("event_id") == eventID,
            old.double("original_elapsed_seconds") == event.elapsedSeconds,
            event.elapsedSeconds == nil || old.double("elapsed_seconds") == elapsed,
            old.data("mapping") == mappingData, old.string("producer") == producer,
            suppliedSequence == nil || old.int("sequence") == suppliedSequence else { throw PowerLogStorageError.conflict("Conflicting collection membership replay") }
      return false
    }
    guard collection.int("event_count")! < PowerLogStorageLimits.maximumCollectionRecords else { throw PowerLogStorageError.invalid("Collection record limit reached") }
    try db.execute("INSERT OR IGNORE INTO collection_sources(collection_id,producer) VALUES(?,?)", [.text(collectionID), .text(producer)])
    let last = try db.scalarInt("SELECT last_sequence FROM collection_sources WHERE collection_id=? AND producer=?", [.text(collectionID), .text(producer)])!
    guard suppliedSequence != nil || last < Int64.max else { throw PowerLogStorageError.invalid("Source archival sequence exhausted") }
    let sequence = suppliedSequence ?? (last + 1)
    guard sequence > 0 else { throw PowerLogStorageError.invalid("Archival sequence must be positive") }
    // Received pages may arrive out of order. Unique ranges are retained; consumers must verify holes before sealing.
    let revision = collection.int("revision")! + 1
    let rawHeart = event.kind == "health" && (payload["rawHealthSample"] == .bool(true) || payload["healthKitUUID"] != nil || payload["sampleUUID"] != nil || ["raw", "rawQuantity", "rawSeries"].contains(payload["representation"]?.string ?? "")) ? 1 : 0
    try db.execute("INSERT INTO collection_memberships(collection_id,observation_id,event_id,producer,sequence,ordinal,kind,source,raw_heart,elapsed_seconds,original_elapsed_seconds,deleted,elapsed_us,query_us,revision,mapping) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", [.text(collectionID), .integer(observationID), .text(eventID), .text(producer), .integer(sequence), .integer(collection.int("event_count")! + 1), .text(event.kind), .text(event.source), .integer(Int64(rawHeart)), .real(elapsed), .optional(event.elapsedSeconds), .integer(payload["deleted"] == .bool(true) ? 1 : 0), .integer(elapsedUS), .integer(queryUS), .integer(revision), .blob(mappingData)])
    if event.kind == "health", payload["deleted"] == .bool(true),
       let external = payload["sampleUUID"]?.string ?? payload["healthKitUUID"]?.string ?? payload["supersedesEventId"]?.string {
      try db.execute("INSERT INTO health_deletions(collection_id,source,external_id,revision,tombstone_event_id) VALUES(?,?,?,?,?)",
        [.text(collectionID), .text(event.source), .text(try WorkoutCoding.id(external)), .integer(revision), .text(eventID)])
    }
    if let target = payload["supersedesEventId"]?.string {
      try db.execute("INSERT INTO collection_corrections(collection_id,target_event_id,replacement_event_id,revision,deleted) VALUES(?,?,?,?,?)", [.text(collectionID), .text(try WorkoutCoding.id(target)), .text(eventID), .integer(revision), .integer(payload["deleted"] == .bool(true) ? 1 : 0)])
    }
    try db.execute("UPDATE collection_sources SET last_sequence=max(last_sequence,?),record_count=record_count+1 WHERE collection_id=? AND producer=?", [.integer(sequence), .text(collectionID), .text(producer)])
    // Accepted late originals make the previous seal historical until the producer seals again.
    if collection.string("kind") == "workout", let metadata = collection.data("metadata") {
      var m = try JSONDecoder().decode(WorkoutMetadata.self, from: metadata)
      if ["complete", "partial"].contains(m.finalizationState ?? "") {
        m.finalizationState = "pending"; m.collectionRevision = revision
        let updated = try WorkoutCoding.encoder().encode(m)
        try db.execute("UPDATE collections SET metadata=? WHERE id=?", [.blob(updated), .text(collectionID)])
        try db.execute("INSERT INTO collection_versions(collection_id,revision,metadata) VALUES(?,?,?)", [.text(collectionID), .integer(revision), .blob(updated)])
      }
    }
    let chartMetrics = Set(Self.telemetryColumns + Self.locationColumns + Self.healthColumns).subtracting(["value", "sampleCount"])
    let names = payload.compactMap { chartMetrics.contains($0.key) && Self.numeric($0.value) != .null ? $0.key : nil }.sorted()
    let priorChannelRows = try db.rows("SELECT metric,count FROM collection_channels WHERE collection_id=? AND source=? AND representation=?", [.text(collectionID), .text(event.source), .text(rawHeart == 1 ? "raw" : "")], limit: 64)
    let priorChannels = priorChannelRows.compactMap { $0.string("metric") }
    let denseTelemetry = event.kind == "telemetry" && priorChannels.count == names.count && Set(priorChannelRows.compactMap { $0.int("count") }).count == 1
    let introducedChannel = !Set(names).isSubset(of: Set(priorChannels))

    var channels = (try? JSONDecoder().decode([String].self, from: collection.data("channels") ?? Data())) ?? []
    channels = Array(Set(channels + names)).sorted()
    try db.execute("UPDATE collections SET revision=?,event_count=event_count+1,min_query_us=min(coalesce(min_query_us,?),?),max_query_us=max(coalesce(max_query_us,?),?),min_elapsed=min(coalesce(min_elapsed,?),?),max_elapsed=max(coalesce(max_elapsed,?),?),channels=? WHERE id=?", [.integer(revision), .integer(queryUS), .integer(queryUS), .integer(queryUS), .integer(queryUS), .real(elapsed), .real(elapsed), .real(elapsed), .real(elapsed), .blob(try WorkoutCoding.encoder().encode(channels)), .text(collectionID)])
    for metric in names {
      try db.execute("INSERT INTO collection_channels(collection_id,metric,source,representation,count,min_elapsed,max_elapsed) VALUES(?,?,?,?,1,?,?) ON CONFLICT(collection_id,metric,source,representation) DO UPDATE SET count=count+1,min_elapsed=min(min_elapsed,excluded.min_elapsed),max_elapsed=max(max_elapsed,excluded.max_elapsed)", [.text(collectionID), .text(metric), .text(event.source), .text(rawHeart == 1 ? "raw" : ""), .real(elapsed), .real(elapsed)])
    }
    let broadChange = introducedChannel || event.kind == "lifecycle" || payload["supersedesEventId"] != nil || payload["deleted"] == .bool(true)
      || ["finalWorkoutTotal", "finalTotal"].contains(payload["representation"]?.string ?? "")
      || names.contains(where: { ["activeEnergyKcal", "basalEnergyKcal", "distanceMeters"].contains($0) })
    if broadChange {
      try recordChange(db, id: collectionID, revision: revision, minUS: nil, maxUS: nil, kind: "semantics", metrics: names)
    } else {
      // Invalidate each selected metric's neighboring edges. Optional fields cannot use
      // an arbitrary stream neighbor because it may be null (or already superseded).
      var lower = elapsed, upper = elapsed
      let table = event.kind == "telemetry" ? "telemetry_frames" : (event.kind == "location" ? "locations" : "health_samples")
      let projections: [String?] = denseTelemetry ? [nil] : names.map { Optional($0) }
      for metric in projections {
        let join = metric == nil ? "" : " JOIN \(table) h ON h.observation_id=m.observation_id"
        let selected = metric == nil ? "" : " AND \(Self.selectedMembershipSQL) AND h.\(metric!) IS NOT NULL"
        var bindings: [PowerLogSQLValue] = [.text(collectionID), .text(event.kind), .text(event.source), .integer(Int64(rawHeart))]
        if metric != nil { bindings += [.integer(revision), .integer(revision)] }
        for before in [true, false] {
          let sql = "SELECT m.elapsed_seconds FROM collection_memberships m\(join) WHERE m.collection_id=? AND m.kind=? AND m.source=? AND m.raw_heart=?\(selected) AND m.elapsed_seconds\(before ? "<" : ">")? ORDER BY m.elapsed_seconds \(before ? "DESC" : "ASC"),m.observation_id \(before ? "DESC" : "ASC") LIMIT 1"
          if let time = try db.rows(sql, bindings + [.real(elapsed)], limit: 1).first?.double("elapsed_seconds") {
            if before { lower = min(lower, time) } else { upper = max(upper, time) }
          }
        }
      }
      try recordChange(db, id: collectionID, revision: revision, minUS: try Self.microseconds(lower), maxUS: Int64(ceil(upper * 1_000_000)), kind: "append", metrics: names)

    }
    return true
  }
  func recordChange(_ db: PowerLogDatabase, id: String, revision: Int64, minUS: Int64?, maxUS: Int64?, kind: String, metrics: [String]) throws {
    try db.execute("INSERT INTO collection_changes(collection_id,revision,min_us,max_us,kind,metrics) VALUES(?,?,?,?,?,?)", [.text(id), .integer(revision), minUS.map(PowerLogSQLValue.integer) ?? .null, maxUS.map(PowerLogSQLValue.integer) ?? .null, .text(kind), .blob(try WorkoutCoding.encoder().encode(metrics))])
    try db.execute("DELETE FROM collection_changes WHERE collection_id=? AND revision<=?", [.text(id), .integer(revision - Int64(PowerLogStorageLimits.changeRows))])
  }

  func pageEvents(id: String, afterSequence: Int64 = 0, limit: Int = 256, producer: String? = nil, throughRevision: Int64? = nil) throws -> [PowerLogEventRecord] {
    try read(priority: .background) { db in
      let id = try WorkoutCoding.id(id)
      try requireWorkoutAvailable(id: id)
      let cursor = producer == nil ? "m.id" : "m.sequence"
      var sql = "SELECT m.*,o.original_timestamp,o.extra FROM collection_memberships m JOIN observations o ON o.id=m.observation_id WHERE m.collection_id=? AND \(cursor)>?"
      var values: [PowerLogSQLValue] = [.text(id), .integer(afterSequence)]
      if let producer { sql += " AND m.producer=?"; values.append(.text(producer)) }
      if let throughRevision { sql += " AND m.revision<=?"; values.append(.integer(throughRevision)) }
      sql += " ORDER BY \(cursor) LIMIT ?"; values.append(.integer(Int64(limit)))
      return try db.rows(sql, values, limit: limit).map { try decodeEvent($0, db: db) }
    }
  }
  /// Bind the chosen snapshot revision twice. A Health sample owns all of its raw series members.
  static let selectedMembershipSQL = "m.deleted=0 AND NOT EXISTS(SELECT 1 FROM collection_corrections c WHERE c.collection_id=m.collection_id AND c.target_event_id=m.event_id AND c.revision<=?) AND (m.kind<>'health' OR NOT EXISTS(SELECT 1 FROM health_samples hs JOIN health_deletions hd ON hd.external_id=hs.external_id WHERE hs.observation_id=m.observation_id AND hd.collection_id=m.collection_id AND hd.source=m.source AND hd.revision<=?))"

  func decodeEvent(_ row: PowerLogRow, db: PowerLogDatabase) throws -> PowerLogEventRecord {
        let kind = row.string("kind")!, observationID = row.int("observation_id")!
        var payload = try JSONDecoder().decode([String: WorkoutJSON].self, from: row.data("extra")!)
        let table: String?, columns: [String]
        switch kind { case "telemetry": table = "telemetry_frames"; columns = Self.telemetryColumns; case "location": table = "locations"; columns = Self.locationColumns; case "health": table = "health_samples"; columns = Self.healthColumns; default: table = nil; columns = [] }
        if let table, let typed = try db.rows("SELECT * FROM \(table) WHERE observation_id=?", [.integer(observationID)], limit: 1).first {
          for column in columns { if let value = Self.json(typed[column]) { payload[column] = value } }
        }
        let mapping = try JSONDecoder().decode([String: WorkoutJSON].self, from: row.data("mapping")!)
        payload.merge(mapping) { _, new in new }
        let event = WorkoutEvent(storedEventID: row.string("event_id")!, workoutID: row.string("collection_id")!,
          kind: kind, source: row.string("source")!, originalTimestamp: row.string("original_timestamp")!,
          elapsedSeconds: row.double("original_elapsed_seconds"), payload: payload)
        return PowerLogEventRecord(event: event, sequence: row.int("sequence")!, producer: row.string("producer")!, revision: row.int("revision")!, rowID: row.int("id")!)
  }

  /// Reclaims one bounded page from an obsolete disposable live collection.
  /// Call again on a background scheduler while true; saved collection references retain originals.
  @discardableResult
  /// Disposable distance rows use the same bounded cleanup budget as originals.
  private static func cleanupDistancePage(_ db: PowerLogDatabase, id: String, limit: Int) throws -> Bool {
    for table in ["distance_points", "distance_health_inputs", "distance_snapshots", "distance_generations"] {
      let predicate = table == "distance_points" ? "generation_id IN (SELECT storage_id FROM distance_generations WHERE collection_id=?)" : "collection_id=?"
      let rows = try db.rows("SELECT rowid AS cleanup_row FROM \(table) WHERE \(predicate) LIMIT ?", [.text(id), .integer(Int64(limit))], limit: limit)
      if !rows.isEmpty {
        for row in rows { try db.execute("DELETE FROM \(table) WHERE rowid=?", [row["cleanup_row"]]) }
        return true
      }
    }
    return false
  }
  func pruneLivePage(keeping activeID: String, limit: Int = 128) throws -> Bool {
    let activeID = try WorkoutCoding.id(activeID)
    guard (1...128).contains(limit) else { throw PowerLogStorageError.invalid("Live cleanup page must be 1...128") }
    return try transaction(priority: .background) { db in
      guard let stale = try db.rows("SELECT id FROM collections WHERE kind='live' AND id<>? ORDER BY started_at,id LIMIT 1", [.text(activeID)], limit: 1).first?.string("id") else { return false }
      if try Self.cleanupDistancePage(db, id: stale, limit: limit) { return true }
      let page = try db.rows("SELECT id,observation_id FROM collection_memberships WHERE collection_id=? ORDER BY id LIMIT ?", [.text(stale), .integer(Int64(limit))], limit: limit)
      for row in page {
        let observation = row.int("observation_id")!
        try db.execute("DELETE FROM collection_memberships WHERE id=?", [row["id"]])
        if try db.scalarInt("SELECT 1 FROM collection_memberships WHERE observation_id=? LIMIT 1", [.integer(observation)]) == nil {
          for table in ["telemetry_frames", "locations", "health_samples", "lifecycle_records"] {
            try db.execute("DELETE FROM \(table) WHERE observation_id=?", [.integer(observation)])
          }
          try db.execute("DELETE FROM observations WHERE id=?", [.integer(observation)])
        }
      }
      if try db.scalarInt("SELECT 1 FROM collection_memberships WHERE collection_id=? LIMIT 1", [.text(stale)]) == nil {
        for table in ["collection_sources", "collection_channels", "collection_changes", "collection_versions", "collection_corrections", "health_deletions", "derived_cache"] {
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
      return try db.scalarInt("SELECT 1 FROM collection_memberships WHERE collection_id=? AND event_id=?", [.text(try WorkoutCoding.id(id)), .text(try WorkoutCoding.id(eventID))]) != nil
    }
  }
  func sourceProgress(id: String, producer: String) throws -> (count: Int64, lastSequence: Int64) {
    try read { db in
      try requireWorkoutAvailable(id: id)
      let row = try db.rows("SELECT record_count,last_sequence FROM collection_sources WHERE collection_id=? AND producer=?", [.text(try WorkoutCoding.id(id)), .text(producer)], limit: 1).first
      return (row?.int("record_count") ?? 0, row?.int("last_sequence") ?? 0)
    }
  }
}

/// A small permanent fence remains after the explicitly selected recording has been reclaimed.
struct WorkoutDeletionRecord: Codable {
  let id: String
  let messageID: String
  let requestedAt: String
  let watchRequired: Bool
  var collectionKind: String? = nil
  var watchAcknowledged: Bool
  var retryAfter: Double = 0
  var cleanupPhase: Int = 0
  var cursorNamespace = ""
  var cursorKey = ""
  var files: [String] = []
  var directoryStack: [String]?
  var cleanupComplete: Bool { cleanupPhase >= 11 && files.isEmpty }
  var packet: [String: Any] {
    ["schemaVersion": 1, "kind": "deleteWorkout", "workoutId": id, "messageId": messageID, "requestedAt": requestedAt]
  }
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
    try read { try $0.get(namespace: "deleted-workouts", key: WorkoutCoding.id(id)).map { try JSONDecoder().decode(WorkoutDeletionRecord.self, from: $0) } }
  }
  /// One fixed-size catalog signal also covers deletion of a nonselected ride.
  func historyDeletion() throws -> WorkoutHistoryDeletion? {
    try read { try $0.get(namespace: "catalog-state", key: "deletion").map { try JSONDecoder().decode(WorkoutHistoryDeletion.self, from: $0) } }
  }
  @discardableResult
  func markWorkoutDeleted(id: String, messageID: String = UUID().uuidString.lowercased(), watchRequired: Bool = false) throws -> WorkoutDeletionRecord {
    let id = try WorkoutCoding.id(id), messageID = try WorkoutCoding.id(messageID)
    return try transaction(priority: .capture) { db in
      if let previous = try workoutDeletion(id: id) { return previous }
      var record = WorkoutDeletionRecord(id: id, messageID: messageID, requestedAt: WorkoutCoding.timestamp(Date()),
        watchRequired: watchRequired, watchAcknowledged: !watchRequired)
      record.collectionKind = try db.rows("SELECT kind FROM collections WHERE id=?", [.text(id)], limit: 1).first?.string("kind")
      try saveWorkoutDeletion(record, db: db)
      try db.put(namespace: "catalog-state", key: "deletion", value: WorkoutCoding.encoder().encode(
        WorkoutHistoryDeletion(revision: record.messageID, deletedWorkoutID: id)))
      if let bytes = try db.get(namespace: "phone-current", key: "workout"),
        let current = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any], current["id"] as? String == id {
        try db.remove(namespace: "phone-current", key: "workout")
      }
      return record
    }
  }
  private func saveWorkoutDeletion(_ record: WorkoutDeletionRecord, db: PowerLogDatabase) throws {
    try db.put(namespace: "deleted-workouts", key: record.id, value: WorkoutCoding.encoder().encode(record))
  }
  func deletionPage(after: String = "", limit: Int = 8) throws -> [WorkoutDeletionRecord] {
    guard (1...128).contains(limit) else { throw PowerLogStorageError.invalid("Deletion discovery page exceeds bound") }
    return try read { try $0.page(namespace: "deleted-workouts", after: after, limit: limit).map { try JSONDecoder().decode(WorkoutDeletionRecord.self, from: $0.value) } }
  }
  @discardableResult
  func acknowledgeWorkoutDeletion(id: String, messageID: String, deleted: Bool, now: Double = Date().timeIntervalSince1970) throws -> Bool {
    try transaction(priority: .capture) { db in
      guard var record = try workoutDeletion(id: id), record.messageID == messageID else { return false }
      if !deleted, record.watchAcknowledged { return false }
      if deleted { record.watchAcknowledged = true }
      else if !record.watchAcknowledged { record.retryAfter = now + 30 }
      try saveWorkoutDeletion(record, db: db); return true
    }
  }
  /// Cleanup is paged independently of UI, capture, and transport. No full-database VACUUM is used.
  @discardableResult
  func cleanupWorkoutPage(id: String, limit: Int = 128) throws -> WorkoutDeletionRecord {
    guard (1...128).contains(limit) else { throw PowerLogStorageError.invalid("Deletion cleanup page exceeds bound") }
    return try transaction(priority: .background) { db in
      guard var record = try workoutDeletion(id: id) else { throw PowerLogStorageError.invalid("Deletion requires its committed fence") }
      guard !record.cleanupComplete, record.files.isEmpty else { return record }
      if try Self.cleanupDistancePage(db, id: id, limit: limit) { return record }
      if record.cleanupPhase == 0 {
        let rows = try db.rows("SELECT id,observation_id,event_id FROM collection_memberships WHERE collection_id=? LIMIT ?", [.text(id), .integer(Int64(limit))], limit: limit)
        for row in rows {
          let observation = row.int("observation_id")!, eventID = row.string("event_id")!
          try db.execute("DELETE FROM collection_memberships WHERE id=?", [row["id"]])
          let intent = try db.get(namespace: "health-insertion-intents", key: eventID).flatMap { try? JSONDecoder().decode(WorkoutEvent.self, from: $0) }
          let anotherEvent = try db.scalarInt("SELECT 1 FROM collection_memberships WHERE event_id=? LIMIT 1", [.text(eventID)]) != nil
          if intent?.workoutId == id || intent == nil && !anotherEvent {
            for namespace in ["health-insertion-intents", "health-insertion-results", "health-insertion-metrics"] { try db.remove(namespace: namespace, key: eventID) }
            for metric in ["humanPowerW", "cadenceRpm"] { try db.remove(namespace: "health-insertion-versions", key: eventID + "." + metric) }
          }
          if try db.scalarInt("SELECT 1 FROM collection_memberships WHERE observation_id=? LIMIT 1", [.integer(observation)]) == nil {
            for table in ["telemetry_frames", "locations", "health_samples", "lifecycle_records"] { try db.execute("DELETE FROM \(table) WHERE observation_id=?", [.integer(observation)]) }
            try db.execute("DELETE FROM observations WHERE id=?", [.integer(observation)])
          }
        }
        if rows.count < limit { record.cleanupPhase = 1 }
      } else if record.cleanupPhase <= 7 {
        let tables = ["collection_sources", "collection_channels", "collection_changes", "collection_versions", "collection_corrections", "health_deletions", "derived_cache"]
        let table = tables[record.cleanupPhase - 1]
        let rows = try db.rows("SELECT rowid AS cleanup_row FROM \(table) WHERE collection_id=? LIMIT ?", [.text(id), .integer(Int64(limit))], limit: limit)
        for row in rows { try db.execute("DELETE FROM \(table) WHERE rowid=?", [row["cleanup_row"]]) }
        if rows.count < limit { record.cleanupPhase += 1 }
      } else if record.cleanupPhase == 8 {
        let rows = try db.rows("SELECT namespace,key FROM durable_records WHERE (namespace,key)>(?,?) ORDER BY namespace,key LIMIT ?", [.text(record.cursorNamespace), .text(record.cursorKey), .integer(Int64(limit))], limit: limit)
        for row in rows {
          let namespace = row.string("namespace")!, key = row.string("key")!
          record.cursorNamespace = namespace; record.cursorKey = key
          guard namespace != "deleted-workouts" else { continue }
          guard let data = try db.get(namespace: namespace, key: key) else { continue }
          var dictionary = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
          if namespace == "incoming-chunks", let encoded = dictionary?["metadata"] as? String, let inner = Data(base64Encoded: encoded) {
            dictionary = (try? JSONSerialization.jsonObject(with: inner)) as? [String: Any]
          }
          if let kind = dictionary?["kind"] as? String, ["deleteWorkout", "deleteWorkoutAck"].contains(kind) { continue }
          let owner = dictionary?["workoutID"] as? String ?? dictionary?["workoutId"] as? String
          // An encoded association to another ride outranks a coincidentally matching key.
          if let owner, owner != id { continue }
          let workoutKeys: Set<String> = ["active-owner-command", "applied-origins", "command-sequences", "cancelled-owner-start",
            "pending-remote-action", "terminal-remote-action", "confirmed-remote-stop", "activity-commands", "owner-snapshots", "source-roster", "source-seals", "source-digest-cache",
            "immutable-seals", "current-seal", "health-correction-heads", "health-insertion-progress", "health-insertion-repair",
            "health-query-progress", "health-query-completed", "telemetry-forward-blocked", "telemetry-forward-cursor",
            "telemetry-forward-turn", "telemetry-forward-verified", "watch-metadata", "sent-progress", "submitted-source-seal"]
          let matchesKey = workoutKeys.contains(namespace) && (key == id || key.hasPrefix(id + ":"))
          guard owner == id || matchesKey else { continue }
          if namespace == "health-insertion-intents" {
            for child in ["health-insertion-results", "health-insertion-metrics"] { try db.remove(namespace: child, key: key) }
            for metric in ["humanPowerW", "cadenceRpm"] { try db.remove(namespace: "health-insertion-versions", key: key + "." + metric) }
          }
          if ["commands", "outgoing-commands"].contains(namespace) {
            for child in ["command-results", "remote-command-results"] { try db.remove(namespace: child, key: key) }
          }
          if ["sent-chunks", "incoming-chunks", "chunk-receipts", "acknowledged-chunks"].contains(namespace),
            key.count == 64, key.allSatisfy({ $0.isHexDigit }) {
            let file = (namespace == "incoming-chunks" ? "inbox/" : "") + key + ".plchunk"
            if !record.files.contains(file) { record.files.append(file) }
          }
          try db.remove(namespace: namespace, key: key)
        }
        if rows.count < limit { record.cleanupPhase = 9; record.cursorNamespace = ""; record.cursorKey = "" }
      } else if record.cleanupPhase == 9 {
        let rows = try db.rows("SELECT namespace,key FROM counters WHERE (namespace,key)>(?,?) ORDER BY namespace,key LIMIT ?", [.text(record.cursorNamespace), .text(record.cursorKey), .integer(Int64(limit))], limit: limit)
        for row in rows {
          let namespace = row.string("namespace")!, key = row.string("key")!
          record.cursorNamespace = namespace; record.cursorKey = key
          if key == id || key.hasPrefix(id + ":") { try db.execute("DELETE FROM counters WHERE namespace=? AND key=?", [.text(namespace), .text(key)]) }
        }
        if rows.count < limit { record.cleanupPhase = 10; record.cursorNamespace = ""; record.cursorKey = "" }
      } else {
        try db.execute("DELETE FROM collections WHERE id=?", [.text(id)])
        // Phase 10 waits for the facade's bounded file/cache cleanup before completion.
      }
      try saveWorkoutDeletion(record, db: db); return record
    }
  }
  func deletionFilesRemoved(id: String, completed: Bool = false) throws {
    try transaction(priority: .background) { db in
      guard var record = try workoutDeletion(id: id) else { throw PowerLogStorageError.invalid("Deletion fence is unavailable") }
      record.files = []
      if completed, record.cleanupPhase == 10 { record.cleanupPhase = 11 }
      try saveWorkoutDeletion(record, db: db)
    }
  }
  func updateDeletionTraversal(id: String, stack: [String]) throws {
    guard stack.count <= 128 else { throw PowerLogStorageError.invalid("Deleted export nesting exceeds bound") }
    try transaction(priority: .background) { db in
      guard var record = try workoutDeletion(id: id) else { throw PowerLogStorageError.invalid("Deletion fence is unavailable") }
      record.directoryStack = stack
      try saveWorkoutDeletion(record, db: db)
    }
  }
}
