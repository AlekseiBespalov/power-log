import Foundation

/// A first control slot stays responsive; the second rotates across every due packet.
/// Radio delivery never removes durable work. Retry state is bounded by the outbox.
struct WorkoutTransmissionSchedule {
  static func isPriority(_ packet: [String: Any]) -> Bool {
    ["command", "deleteWorkout"].contains(packet["kind"] as? String ?? "")
  }
  /// Only deletion has an offline userInfo transport. Undeliverable ordinary work
  /// must not occupy both fair scheduling slots while background delivery is possible.
  static func canDispatch(kind: String, mirrored: Bool, reachable: Bool, background: Bool) -> Bool {
    mirrored || reachable || (kind == "deleteWorkout" && background)
  }
  static func acceptsAcknowledgement(kind: String?, allowDeletion: Bool) -> Bool {
    kind != "deleteWorkout" || allowDeletion
  }
  private struct Attempt { var turn: Int64; var count: Int; var due: Double }
  private var attempts: [String: Attempt] = [:]
  private var turn: Int64 = 0
  private var consecutivePriorityAttempts = 0
  mutating func select(order: [String], priority: Set<String>, unavailable: Set<String> = [], now: Double) -> [String] {
    guard now.isFinite else { return [] }
    let retained = Set(order)
    attempts = attempts.filter { retained.contains($0.key) }
    let due = order.enumerated().filter { !unavailable.contains($0.element) && (attempts[$0.element]?.due ?? -.infinity) <= now }
      .sorted { a, b in
        let x = attempts[a.element]?.turn ?? -1, y = attempts[b.element]?.turn ?? -1
        return x == y ? a.offset < b.offset : x < y
      }.map(\.element)
    guard !due.isEmpty else { return [] }
    let first = consecutivePriorityAttempts < 2 ? (due.first(where: { priority.contains($0) }) ?? due[0]) : due[0]
    return [first] + due.filter { $0 != first }.prefix(1)
  }
  /// Selection does not spend a turn: native byte admission may reject it.
  /// Two actual priority attempts must yield to the oldest due packet.
  mutating func attempted(_ id: String, priority: Bool, now: Double) {
    guard now.isFinite else { return }
    turn += 1
    let count = min(5, (attempts[id]?.count ?? 0) + 1)
    attempts[id] = Attempt(turn: turn, count: count, due: now + min(30, 3 * pow(2, Double(count - 1))))
    consecutivePriorityAttempts = priority ? min(2, consecutivePriorityAttempts + 1) : 0
  }
  mutating func remove(_ id: String) { attempts.removeValue(forKey: id) }
}

/// Readiness can request replay of the existing durable start intent.
enum WorkoutLaunchReadiness {
  static func shouldResendStart(kind: String, remotePhase: String?, localPhase: String, watch: Bool) -> Bool {
    kind == "status" && remotePhase == "ready" && localPhase == "preparing" && watch
  }
}

/// Private bounded transport history. Never stores packet bodies, IDs, userInfo or localized errors.
final class WorkoutConnectivityDiagnostics {
  enum Event: String, Codable {
    case initialized, activationRequested, activationCompleted, becameInactive, deactivated, reachabilityChanged, watchStateChanged
    case enqueued, discarded, acknowledged, acknowledgementUnknown, contextSubmitted, contextCleared
    case sendAttempt, transportDelivered, sendFailed, sendUnconfirmed, mirrorBudgetLimited
    case received, archiveCopied, archiveCopyFailed, storageFailed
    case startRequested, healthPermissionRequested, healthPermissionCompleted, watchLaunchRequested, watchLaunchCompleted
    case phoneCollectionRequested, collectionStarted, startFailed, readinessExpired, mirrorStarted, mirrorDisconnected, healthError, sessionFailed, statusReceived
  }
  struct Connection: Codable {
    var supported = false, activated = false, paired = false, installed = false, reachable = false
    var activationState = 0, pendingMessages = 0
  }
  struct Entry: Codable {
    var event: Event
    var timestamp: String
    var uptimeSeconds: Double
    var kind: String?
    var transport: String?
    var success: Bool?
    var errorDomain: String?
    var errorCode: Int?
    var phase: String?
    var elapsedSeconds: Double?
  }
  struct Report: Codable {
    var schemaVersion = 1
    var connection = Connection()
    var history: [Entry] = []
    var counts: [String: Int] = [:]
    var lastSent: Entry?, lastDelivered: Entry?, lastReceived: Entry?, lastAcknowledged: Entry?, lastNativeError: Entry?
    var lastStartRequested: Entry?, lastWatchLaunchResult: Entry?, lastStartOutcome: Entry?
  }
  let url: URL
  private(set) var report = Report()
  private(set) var persistenceCount = 0
  private var lastPersisted = -Double.infinity
  init(rootURL: URL) {
    url = rootURL.appendingPathComponent("connectivity-diagnostics.json")
    if let data = try? Data(contentsOf: url), data.count <= 131_072, let previous = try? JSONDecoder().decode(Report.self, from: data) {
      report = previous; report.history = Array(report.history.suffix(64))
    }
  }
  static func kind(_ packet: [String: Any]) -> String {
    let kind = packet["kind"] as? String ?? "unknown"
    if kind == "command" {
      let action = packet["action"] as? String ?? "unknown"
      return ["start", "pause", "resume", "lap", "stop", "discard", "status"].contains(action) ? "command." + action : "command.unknown"
    }
    return ["events", "status", "ownerQuery", "ownerReply", "sourceSeal", "seal", "chunkAck", "sealAck", "ack", "archiveAck", "workoutArchive", "workoutChunk", "deleteWorkout", "deleteWorkoutAck"].contains(kind) ? kind : "unknown"
  }
  func record(_ event: Event, connection: Connection, kind: String? = nil, transport: String? = nil,
    success: Bool? = nil, error: Error? = nil, phase: String? = nil, elapsedSeconds: Double? = nil,
    now: Date = Date(), uptime: TimeInterval = ProcessInfo.processInfo.systemUptime) {
    let allowedKinds = ["events", "status", "ownerQuery", "ownerReply", "sourceSeal", "seal", "chunkAck", "sealAck", "ack", "archiveAck", "workoutArchive", "workoutChunk", "deleteWorkout", "deleteWorkoutAck", "unknown", "command.start", "command.pause", "command.resume", "command.lap", "command.stop", "command.discard", "command.status", "command.unknown"]
    let safeKind = kind.flatMap { allowedKinds.contains($0) ? $0 : "unknown" }
    let safeTransport = transport.flatMap { ["watchConnectivity", "healthKitMirror", "applicationContext", "userInfo", "file"].contains($0) ? $0 : "unknown" }
    let safePhase = phase.flatMap { ["idle", "preparing", "ready", "running", "paused", "recoverable", "finishing", "completed", "failed"].contains($0) ? $0 : "unknown" }
    let nsError = error as NSError?
    let entry = Entry(event: event, timestamp: WorkoutCoding.timestamp(now), uptimeSeconds: uptime,
      kind: safeKind, transport: safeTransport, success: success,
      errorDomain: nsError.map { $0.domain.range(of: "^[A-Za-z0-9._-]{1,120}$", options: .regularExpression) == nil ? "unrecognized_error_domain" : $0.domain },
      errorCode: nsError?.code, phase: safePhase, elapsedSeconds: elapsedSeconds.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil })
    report.connection = connection
    report.counts[event.rawValue] = min(Int.max - 1, report.counts[event.rawValue, default: 0]) + 1
    if [.sendAttempt, .contextSubmitted].contains(event) { report.lastSent = entry }
    if event == .transportDelivered { report.lastDelivered = entry }
    if event == .received || event == .archiveCopied { report.lastReceived = entry }
    if event == .acknowledged { report.lastAcknowledged = entry }
    if error != nil { report.lastNativeError = entry }
    if event == .startRequested { report.lastStartRequested = entry }
    if event == .watchLaunchCompleted { report.lastWatchLaunchResult = entry }
    if [.collectionStarted, .startFailed, .readinessExpired].contains(event) { report.lastStartOutcome = entry }
    let routine = error == nil && ["events", "ack", "workoutChunk", "chunkAck", "sourceSeal", "seal", "sealAck", "archiveAck"].contains(safeKind ?? "") &&
      [.enqueued, .sendAttempt, .transportDelivered, .received, .acknowledged, .sendUnconfirmed, .mirrorBudgetLimited].contains(event)
    if !routine { report.history.append(entry); if report.history.count > 64 { report.history.removeFirst(report.history.count - 64) } }
    guard !routine || uptime - lastPersisted >= 5 else { return }
    do {
      let data = try JSONEncoder().encode(report)
      #if os(iOS)
      try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      #else
      try data.write(to: url, options: .atomic)
      #endif
      lastPersisted = uptime
      persistenceCount += 1
    } catch { /* Diagnostics must not interrupt capture or replace the original native failure. */ }
  }
}

#if os(iOS)
import WatchConnectivity

/// Phone-to-Watch traffic is acknowledged after the receiver persists it, not after radio delivery.
final class WorkoutPhoneConnectivity: NSObject, WCSessionDelegate {
  private let store: PowerLogStore
  private let queue: DispatchQueue
  private let outbox: WorkoutBoundedOutbox
  private let inbox: WorkoutChunkInbox
  private let diagnostics: WorkoutConnectivityDiagnostics
  private var timer: DispatchSourceTimer?
  private var transmitting = Set<String>()
  private var budget = WorkoutTransmissionBudget()
  private var schedule = WorkoutTransmissionSchedule()
  private var pending: [String: Data] = [:]
  private var order: [String] = []
  private var priority = Set<String>()
  var mirrorSend: ((Data, @escaping (Bool) -> Void) -> Void)?
  var mirrorAvailable: (() -> Bool)?
  var onPacket: ((Data) -> Void)?
  var onArchive: ((URL, [String: Any], @escaping (Bool) -> Void) -> Void)?
  var onChanged: (() -> Void)?
  private(set) var lastError: String?

  init(rootURL: URL, queue: DispatchQueue, store: PowerLogStore) throws {
    self.store = store
    self.queue = queue
    diagnostics = WorkoutConnectivityDiagnostics(rootURL: rootURL)
    outbox = WorkoutBoundedOutbox(store: store)
    inbox = try WorkoutChunkInbox(root: rootURL.appendingPathComponent("inbox", isDirectory: true), store: store)
    super.init()
    let archive = try WorkoutArchive(rootURL: rootURL, store: store)
    _ = try WorkoutTelemetryForwarder(archive: archive).pruneVerifiedPackets()
    for item in try outbox.packets() {
      pending[item.key] = item.value; order.append(item.key)
      if let value = try? JSONSerialization.jsonObject(with: item.value) as? [String: Any], WorkoutTransmissionSchedule.isPriority(value) { priority.insert(item.key) }
    }
    recordDiagnostic(.initialized)
    if WCSession.isSupported() {
      WCSession.default.delegate = self; recordDiagnostic(.activationRequested); WCSession.default.activate()
    }
    let timer = DispatchSource.makeTimerSource(queue: queue)
    timer.schedule(deadline: .now() + 1, repeating: 1)
    timer.setEventHandler { [weak self] in self?.pump(); self?.retryInbox() }
    timer.resume(); self.timer = timer
  }

  var state: [String: Any] {
    guard WCSession.isSupported() else { return ["supported": false, "paired": false, "installed": false, "reachable": false, "pendingMessages": pendingCount] }
    let session = WCSession.default
    return ["supported": true, "paired": session.isPaired, "installed": session.isWatchAppInstalled,
      "reachable": session.isReachable, "activated": session.activationState == .activated,
      "pendingMessages": pendingCount, "error": lastError as Any? ?? NSNull()]
  }

  func recordDiagnostic(_ event: WorkoutConnectivityDiagnostics.Event, kind: String? = nil, transport: String? = nil,
    success: Bool? = nil, error: Error? = nil, phase: String? = nil, elapsedSeconds: Double? = nil) {
    var connection = WorkoutConnectivityDiagnostics.Connection()
    connection.pendingMessages = pendingCount
    if WCSession.isSupported() {
      let session = WCSession.default
      connection.supported = true; connection.activated = session.activationState == .activated
      connection.activationState = session.activationState.rawValue
      connection.paired = session.isPaired; connection.installed = session.isWatchAppInstalled; connection.reachable = session.isReachable
    }
    diagnostics.record(event, connection: connection, kind: kind, transport: transport, success: success, error: error, phase: phase, elapsedSeconds: elapsedSeconds)
  }

  private func packetKind(_ data: Data) -> String {
    guard let packet = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "unknown" }
    return WorkoutConnectivityDiagnostics.kind(packet)
  }

  func receiveMirrored(_ data: Data) {
    guard data.count <= 100_000 else { return }
    receivePacket(data, transport: "healthKitMirror")
  }

  private func receivePacket(_ data: Data, transport: String) {
    let kind = packetKind(data)
    recordDiagnostic(.received, kind: kind, transport: transport)
    if kind == "workoutChunk" {
      do {
        let received = try WorkoutChunkWire.decode(data)
        if try inbox.stage(data: received.chunk.data, metadata: received.metadata) { retryInbox() }
      } catch { recordDiagnostic(.archiveCopyFailed, kind: kind, transport: transport, error: error) }
      return
    }
    onPacket?(data)
  }

  func setActiveWorkoutID(_ id: String?) { inbox.setPreferredWorkoutID(id) }

  var pendingCount: Int { pending.count }
  @discardableResult
  func enqueue(_ envelope: [String: Any], notify: Bool = true) throws -> Bool {
    guard let id = envelope["messageId"] as? String, UUID(uuidString: id) != nil else { throw CycError.invalid("Invalid Watch message ID.") }
    if let workoutID = envelope["workoutId"] as? String,
      !["deleteWorkout", "deleteWorkoutAck"].contains(envelope["kind"] as? String ?? ""),
      try store.isWorkoutDeleted(id: workoutID) { throw PowerLogStorageError.deleted(workoutID) }
    guard try outbox.enqueue(envelope) else { return false }
    let data = try JSONSerialization.data(withJSONObject: envelope, options: [.sortedKeys])
    if pending[id] == nil { order.append(id) }
    pending[id] = data
    if WorkoutTransmissionSchedule.isPriority(envelope) { priority.insert(id) }
    recordDiagnostic(.enqueued, kind: WorkoutConnectivityDiagnostics.kind(envelope))
    if notify { onChanged?(); pump() }
    return true
  }

  /// Publish packets only after the canonical forwarding transaction committed.
  func refreshOutbox() throws {
    let retained = try outbox.packets()
    let ids = Set(retained.map(\.key))
    for id in order where !ids.contains(id) {
      pending.removeValue(forKey: id); transmitting.remove(id); priority.remove(id); schedule.remove(id)
    }
    order.removeAll { !ids.contains($0) }
    for item in retained where pending[item.key] == nil {
      pending[item.key] = item.value; order.append(item.key)
      if let packet = try? JSONSerialization.jsonObject(with: item.value) as? [String: Any], WorkoutTransmissionSchedule.isPriority(packet) { priority.insert(item.key) }
    }
    onChanged?(); pump()
  }

  func retryInbox() {
    guard let onArchive else { return }
    do {
      guard let item = try inbox.claim() else { return }
      guard let metadata = try JSONSerialization.jsonObject(with: item.metadata) as? [String: Any] else {
        try inbox.finish(item, success: false); return
      }
      onArchive(inbox.url(item), metadata) { [weak self] success in
        self?.queue.async {
          guard let self else { return }
          do { try self.inbox.finish(item, success: success) }
          catch { self.lastError = "Incoming archive progress could not be committed."; self.recordDiagnostic(.storageFailed, error: error) }
          self.retryInbox()
        }
      }
    } catch { lastError = "Incoming archive is pending."; recordDiagnostic(.storageFailed, error: error) }
  }

  /// Called after the deletion marker commits. Delayed deletion delivery remains durable.
  func discardWorkoutPackets(workoutId: String) {
    for (id, data) in pending {
      guard let packet = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
        packet["workoutId"] as? String == workoutId,
        !["deleteWorkout", "deleteWorkoutAck"].contains(packet["kind"] as? String ?? "") else { continue }
      acknowledge(id, discarded: true)
    }
    do { try discardDeletedTransfers() }
    catch { lastError = "Watch delivery is waiting for local storage."; recordDiagnostic(.storageFailed, error: error) }
  }

  func setPendingStart(_ envelope: [String: Any]) throws {
    guard let workoutID = envelope["workoutId"] as? String else { throw CycError.invalid("Missing workout identity.") }
    try store.requireWorkoutAvailable(id: workoutID)
    guard WCSession.isSupported(), WCSession.default.activationState == .activated else { throw CycError.invalid("Watch connectivity is still starting. Try again when ready.") }
    do {
      try WCSession.default.updateApplicationContext(["pendingStart": envelope])
      recordDiagnostic(.contextSubmitted, kind: "command.start", transport: "applicationContext")
    } catch { recordDiagnostic(.sendFailed, kind: "command.start", transport: "applicationContext", error: error); throw error }
  }

  func clearPendingStart() {
    if WCSession.isSupported(), WCSession.default.activationState == .activated {
      do { try WCSession.default.updateApplicationContext([:]); recordDiagnostic(.contextCleared, transport: "applicationContext") }
      catch { recordDiagnostic(.sendFailed, transport: "applicationContext", error: error) }
    }
  }

  func acknowledge(_ id: String, discarded: Bool = false, allowDeletion: Bool = false) {
    guard UUID(uuidString: id) != nil else { return }
    let known = pending[id.lowercased()]
    let kind = known.map(packetKind)
    guard WorkoutTransmissionSchedule.acceptsAcknowledgement(kind: kind, allowDeletion: allowDeletion) else { return }
    do { try outbox.acknowledge(id.lowercased()) }
    catch { lastError = "Could not commit a Watch acknowledgement."; recordDiagnostic(.storageFailed, error: error); return }
    recordDiagnostic(discarded ? .discarded : known == nil ? .acknowledgementUnknown : .acknowledged, kind: kind)
    pending.removeValue(forKey: id.lowercased()); order.removeAll { $0 == id.lowercased() }
    transmitting.remove(id.lowercased()); priority.remove(id.lowercased()); onChanged?()
    schedule.remove(id.lowercased())
    if kind == "deleteWorkout", WCSession.isSupported() {
      for transfer in WCSession.default.outstandingUserInfoTransfers {
        guard let bytes = transfer.userInfo["data"] as? Data,
          let packet = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any],
          packet["kind"] as? String == "deleteWorkout", packet["messageId"] as? String == id.lowercased() else { continue }
        transfer.cancel()
      }
    }
  }

  func sendEphemeral(_ envelope: [String: Any]) {
    guard let data = try? JSONSerialization.data(withJSONObject: envelope) else { return }
    sendMirror(data)
    if WCSession.isSupported(), WCSession.default.isReachable { sendConnectivity(data, wantsReply: false) }
    if ["chunkAck", "sealAck"].contains(envelope["kind"] as? String ?? ""), WCSession.isSupported(), WCSession.default.activationState == .activated {
      recordDiagnostic(.sendAttempt, kind: packetKind(data), transport: "userInfo")
      let session = WCSession.default
      let duplicates = session.outstandingUserInfoTransfers.contains { transfer in
        guard let bytes = transfer.userInfo["data"] as? Data,
          let old = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { return false }
        return old["kind"] as? String == envelope["kind"] as? String && old["workoutId"] as? String == envelope["workoutId"] as? String &&
          (old["sealRevision"] as? String == envelope["sealRevision"] as? String) && (old["chunkIdentity"] as? String == envelope["chunkIdentity"] as? String)
      }
      if !duplicates, session.outstandingUserInfoTransfers.count < 8 { session.transferUserInfo(["data": data]) }
    }
  }

  @discardableResult
  private func sendMirror(_ data: Data) -> Bool {
    guard let mirrorSend, mirrorAvailable?() == true else { return false }
    let kind = packetKind(data)
    guard budget.reserve(bytes: data.count, now: ProcessInfo.processInfo.systemUptime) else {
      recordDiagnostic(.mirrorBudgetLimited, kind: kind, transport: "healthKitMirror"); return false
    }
    recordDiagnostic(.sendAttempt, kind: kind, transport: "healthKitMirror")
    mirrorSend(data) { [weak self] success in
      guard let self else { return }; self.queue.async {
        self.recordDiagnostic(success ? .transportDelivered : .sendUnconfirmed, kind: kind, transport: "healthKitMirror", success: success)
      }
    }
    return true
  }

  private func pump() {
    // Application context and OS-queued userInfo survive our process. Reconcile them
    // on every activation/retry, including a crash immediately after deletion commits.
    do { try discardDeletedTransfers() }
    catch {
      lastError = "Watch delivery is waiting for local storage."
      recordDiagnostic(.storageFailed, error: error); return
    }
    let session = WCSession.isSupported() ? WCSession.default : nil
    let reachable = session?.isReachable == true
    let background = session?.activationState == .activated && session?.isPaired == true && session?.isWatchAppInstalled == true
    let mirrored = mirrorSend != nil && mirrorAvailable?() == true
    var unavailable = transmitting
    for (id, data) in pending where !WorkoutTransmissionSchedule.canDispatch(kind: packetKind(data), mirrored: mirrored, reachable: reachable, background: background) {
      unavailable.insert(id)
    }
    let selected = schedule.select(order: order, priority: priority, unavailable: unavailable, now: ProcessInfo.processInfo.systemUptime)
    for id in selected {
      guard !transmitting.contains(id), let data = pending[id], data.count <= 60_000 else { continue }
      // A crash can leave old outbox copies after the deletion marker. Check before any radio effect.
      do {
        if let packet = try JSONSerialization.jsonObject(with: data) as? [String: Any],
          let workoutID = packet["workoutId"] as? String,
          !["deleteWorkout", "deleteWorkoutAck"].contains(packet["kind"] as? String ?? ""),
          try store.isWorkoutDeleted(id: workoutID) { acknowledge(id, discarded: true); continue }
      } catch {
        lastError = "Watch delivery is waiting for local storage."
        recordDiagnostic(.storageFailed, error: error); continue
      }
      // Byte-deferred work retains its age and does not incur radio retry backoff.
      var attempted = sendMirror(data)
      if WCSession.isSupported(), WCSession.default.isReachable {
        sendConnectivity(data, wantsReply: true); attempted = true
      } else if packetKind(data) == "deleteWorkout" {
        attempted = sendBackgroundDeletion(data) || attempted
      }
      guard attempted else { continue }
      schedule.attempted(id, priority: priority.contains(id), now: ProcessInfo.processInfo.systemUptime)
      transmitting.insert(id)
      // A successful send is not an application acknowledgement. Keep durable work until explicit ACK.
      queue.asyncAfter(deadline: .now() + 3) { [weak self] in self?.transmitting.remove(id) }
    }
  }

  private func discardDeletedTransfers() throws {
    guard WCSession.isSupported() else { return }
    let session = WCSession.default
    guard session.activationState == .activated else { return }
    var context = session.applicationContext
    if let pendingStart = context["pendingStart"] as? [String: Any],
      let workoutID = pendingStart["workoutId"] as? String,
      try store.isWorkoutDeleted(id: workoutID) {
      context.removeValue(forKey: "pendingStart")
      try session.updateApplicationContext(context)
      recordDiagnostic(.contextCleared, transport: "applicationContext")
    }
    for transfer in session.outstandingUserInfoTransfers {
      guard let data = transfer.userInfo["data"] as? Data,
        let packet = try JSONSerialization.jsonObject(with: data) as? [String: Any],
        let workoutID = packet["workoutId"] as? String,
        !["deleteWorkout", "deleteWorkoutAck"].contains(packet["kind"] as? String ?? "") else { continue }
      if try store.isWorkoutDeleted(id: workoutID) { transfer.cancel() }
    }
  }

  /// WatchConnectivity may deliver this while the Watch app is not in the foreground.
  /// The durable journal, rather than OS transfer completion, still owns the deletion receipt.
  private func sendBackgroundDeletion(_ data: Data) -> Bool {
    guard WCSession.isSupported(), let packet = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
      packet["kind"] as? String == "deleteWorkout", let id = packet["messageId"] as? String else { return false }
    let session = WCSession.default
    guard session.activationState == .activated, session.isPaired, session.isWatchAppInstalled else { return false }
    let outstanding = session.outstandingUserInfoTransfers
    if outstanding.contains(where: { transfer in
      guard let bytes = transfer.userInfo["data"] as? Data,
        let old = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] else { return false }
      return old["kind"] as? String == "deleteWorkout" && old["messageId"] as? String == id
    }) { return true }
    guard outstanding.count < 8 else { return false }
    session.transferUserInfo(["data": data])
    recordDiagnostic(.sendAttempt, kind: "deleteWorkout", transport: "userInfo")
    return true
  }

  private func sendConnectivity(_ data: Data, wantsReply: Bool) {
    let kind = packetKind(data)
    recordDiagnostic(.sendAttempt, kind: kind, transport: "watchConnectivity")
    let reply: ((Data) -> Void)? = wantsReply ? { [weak self] value in
      guard let self else { return }; self.queue.async {
        self.recordDiagnostic(.transportDelivered, kind: kind, transport: "watchConnectivity", success: true)
        if let packet = try? JSONSerialization.jsonObject(with: value) as? [String: Any], packet["schemaVersion"] as? Int == 1 {
          self.receivePacket(value, transport: "watchConnectivity")
        }
      }
    } : nil
    WCSession.default.sendMessageData(data, replyHandler: reply, errorHandler: { [weak self] error in
      guard let self else { return }; self.queue.async {
        self.lastError = "Watch message delivery failed."
        self.recordDiagnostic(.sendFailed, kind: kind, transport: "watchConnectivity", error: error); self.onChanged?()
      }
    })
  }

  func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
    queue.async { [weak self] in self?.lastError = error == nil ? nil : "Watch connectivity could not activate."; self?.recordDiagnostic(.activationCompleted, success: error == nil && activationState == .activated, error: error); self?.pump(); self?.onChanged?() }
  }
  func sessionDidBecomeInactive(_ session: WCSession) { queue.async { [weak self] in self?.recordDiagnostic(.becameInactive); self?.onChanged?() } }
  func sessionDidDeactivate(_ session: WCSession) { queue.async { [weak self] in self?.recordDiagnostic(.deactivated); session.activate() } }
  func sessionReachabilityDidChange(_ session: WCSession) { queue.async { [weak self] in self?.recordDiagnostic(.reachabilityChanged); self?.onChanged?(); self?.pump() } }
  func sessionWatchStateDidChange(_ session: WCSession) { queue.async { [weak self] in self?.recordDiagnostic(.watchStateChanged); self?.onChanged?() } }
  func session(_ session: WCSession, didReceiveMessageData messageData: Data) {
    guard messageData.count <= 100_000 else { return }
    queue.async { [weak self] in self?.receivePacket(messageData, transport: "watchConnectivity") }
  }
  func session(_ session: WCSession, didReceiveMessageData messageData: Data, replyHandler: @escaping (Data) -> Void) {
    // ACK is sent by WorkoutEngine after archival, using the same mirrored/WC channels.
    self.session(session, didReceiveMessageData: messageData)
    replyHandler(Data("{}".utf8))
  }
  func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
    if let data = userInfo["data"] as? Data, data.count <= 100_000 { queue.async { [weak self] in self?.receivePacket(data, transport: "userInfo") } }
    else if let data = try? JSONSerialization.data(withJSONObject: userInfo), data.count <= 100_000 { queue.async { [weak self] in self?.receivePacket(data, transport: "userInfo") } }
  }
  func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
    guard let packet = applicationContext["workoutStatus"] as? [String: Any],
      let data = try? JSONSerialization.data(withJSONObject: packet), data.count <= 100_000 else { return }
    queue.async { [weak self] in self?.receivePacket(data, transport: "applicationContext") }
  }
  func session(_ session: WCSession, didFinish userInfoTransfer: WCSessionUserInfoTransfer, error: Error?) {
    let kind = (userInfoTransfer.userInfo["data"] as? Data).map(packetKind) ?? "unknown"
    queue.async { [weak self] in self?.recordDiagnostic(error == nil ? .transportDelivered : .sendFailed, kind: kind, transport: "userInfo", success: error == nil, error: error) }
  }
  func session(_ session: WCSession, didReceive file: WCSessionFile) {
    guard let metadata = file.metadata else { return }
    do {
      if try inbox.stage(file: file.fileURL, metadata: metadata) {
        queue.async { [weak self] in self?.retryInbox() }
      }
    } catch {
      queue.async { [weak self] in self?.recordDiagnostic(.archiveCopyFailed, kind: "workoutChunk", transport: "file", error: error) }
    }
  }
}
#endif
