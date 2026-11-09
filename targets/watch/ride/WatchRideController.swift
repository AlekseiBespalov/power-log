import Combine
import Foundation

@MainActor
final class WatchRideController: ObservableObject, WatchRideFacade {
  static let writeBudget: TimeInterval = 10
  static let maxWaitingWrites = 32
  static let startReplyCache = 64

  @Published private(set) var facts = WatchRideFacts()
  private(set) var options: WatchOptions

  private let phone: PhoneChannel
  private let workout: WatchWorkout
  private let clock: WatchClock
  private let store: WatchOptionsStore

  private final class Ride {
    let rideID: String
    let start: StartMessage?
    var phase: WatchPhase = .preparing
    var revision: Int64?
    var timer: RideTimer?
    var laps: Set<Int64> = []
    var lapCount = 0
    var display: WatchDisplay?
    var units: UnitSystem = .metric
    var stride = 1
    var points: [Int64: RoutePoint] = [:]
    var starting = false
    var startAbandoned = false
    var waiters: [(Data) -> Void] = []
    var sessionLive = false
    var workoutEnded = false
    var endedLocally = false
    var writesStopped = false
    var uncertain = false
    var outcome: HealthOutcome?

    init(rideID: String, start: StartMessage?) {
      self.rideID = rideID
      self.start = start
    }

    var orphan: Bool { start == nil }
    var saving: Bool { start?.options.saveToHealth ?? false }
  }

  private enum Write {
    case add([HealthPlanQuantity])
    case lap(Date)
    case finish(Date, HealthPlanRoute?)
    case discard(Date)
  }

  private var ride: Ride?
  private var request: (id: String, timer: WatchTimer)?
  private var command: (id: UUID, action: RideAction, timer: WatchTimer)?
  private var tombstones = StartTombstones()
  private var startReplies: [(eventID: String, reply: Data)] = []
  private var issue: WatchIssue?
  private var heartRate: LiveHeartRate?
  private var heartRateSent: Date?
  private var buffer = HealthSampleBuffer()
  private var bufferRide: String?
  private var acknowledged: Int64 = 0
  private var samplesInFlight = false
  private var cancellation: (message: CancelStartMessage, until: Date)?
  private var report: (message: HealthOutcomeMessage, until: Date)?
  private var writes: [(ride: Ride, write: Write)] = []
  private var writing: (token: Int, ride: Ride)?
  private var writeToken = 0
  private var activated = false

  init(phone: PhoneChannel, workout: WatchWorkout, clock: WatchClock, store: WatchOptionsStore) {
    self.phone = phone
    self.workout = workout
    self.clock = clock
    self.store = store
    options = store.load()
    phone.changed = { [weak self] in MainActor.assumeIsolated { self?.publish() } }
    phone.received = { [weak self] data, reply in MainActor.assumeIsolated { self?.receive(data, reply: reply) } }
    workout.onHeartRateReceived = { [weak self] heartRate in
      MainActor.assumeIsolated { self?.heartRateReceived(heartRate) }
    }
    workout.onSamplesCollected = { [weak self] samples in
      MainActor.assumeIsolated { self?.samplesCollected(samples) }
    }
    publish()
  }

  func activate() {
    guard !activated else { return }
    activated = true
    phone.activate()
    var settled = false
    let budget = clock.after(Self.writeBudget) { settled = true }
    workout.recoverOrphan { [weak self] rideID in
      MainActor.assumeIsolated {
        budget.cancel()
        guard let self, !settled, let rideID, self.ride == nil else { return }
        settled = true
        let orphan = Ride(rideID: rideID, start: nil)
        orphan.phase = .interrupted
        orphan.workoutEnded = true
        self.ride = orphan
        self.issue = .orphanRecovered
        self.publish()
      }
    }
    tick()
  }

  func setOptions(_ options: WatchOptions) {
    guard available(.options).enabled else { return }
    self.options = options
    store.save(options)
    publish()
  }

  func start() {
    guard available(.start).enabled else { return }
    guard phone.reachable else {
      issue = .phoneUnreachableAtStart
      return publish()
    }
    let id = UUID().uuidString.lowercased()
    issue = nil
    let timer = clock.after(Double(WatchProtocol.startWindowUs) / 1_000_000) { [weak self] in
      self?.startUnanswered(id)
    }
    request = (id, timer)
    send(.startRequest(StartRequestMessage(requestID: id, options: options.startOptions))) { [weak self] answer in
      guard let self else { return }
      switch answer {
      case .ride(let message)?:
        self.endRequest(id)
        _ = self.apply(message)
      case .ack(let ack)? where ack.error != nil:
        guard self.endRequest(id) else { return }
        if let code = ack.error { self.issue = .failure(code) }
      default:
        self.startUnanswered(id)
      }
      self.publish()
    }
    publish()
  }

  func cancelStart() {
    guard available(.cancelStart).enabled else { return }
    let current = ride
    let requestID = request?.id ?? current?.start?.requestID
    tombstones.insert([requestID, current?.start?.eventID], at: clock.now)
    request?.timer.cancel()
    request = nil
    if let current {
      if current.starting { answerStart(current, error: "startCancelled") }
      current.starting = false
      if current.sessionLive {
        current.sessionLive = false
        current.workoutEnded = true
        workout.discard(at: clock.now)
      }
      ride = nil
    }
    issue = nil
    cancellation = (
      CancelStartMessage(eventID: current?.start?.eventID, requestID: requestID, rideID: current?.rideID),
      clock.now.addingTimeInterval(Double(WatchProtocol.replayUs) / 1_000_000)
    )
    sendCancellation()
    publish()
  }

  func pause() { if available(.pause).enabled { sendCommand(.pause) } }

  func resume() { if available(.resume).enabled { sendCommand(.resume) } }

  func lap() { if available(.lap).enabled { sendCommand(.lap) } }

  func finish(rideID: String, discard: Bool) {
    guard available(discard ? .discard : .finish).enabled, let current = ride, current.rideID == rideID else {
      return
    }
    guard command == nil else { return }
    guard phone.reachable else { return endLocally(current, discard: discard, issue: .endedLocallyUnreachable) }
    sendCommand(discard ? .discard : .finish)
  }

  func receive(_ data: Data, reply: @escaping (Data) -> Void) {
    switch WatchMessages.decode(data) {
    case .versionMismatch:
      issue = .failure(Self.versionMismatch)
      reply(Self.ack(error: Self.versionMismatch))
    case .valid(.start(let message)): received(message, reply: reply)
    case .valid(.ride(let message)): reply(apply(message))
    default: reply(Self.ack(error: "watchMessageInvalid"))
    }
    publish()
  }

  func tick() {
    let now = clock.now
    buffer.trim(at: now)
    sendCancellation()
    sendReport()
    sendSamples()
    publish()
    clock.after(Double(WatchProtocol.resendUs) / 1_000_000) { [weak self] in self?.tick() }
  }

  private func received(_ message: StartMessage, reply: @escaping (Data) -> Void) {
    let now = clock.now
    if tombstones.contains(message.eventID, at: now) || tombstones.contains(message.requestID, at: now) {
      return reply(Self.ack(error: "startCancelled"))
    }
    if let cached = startReplies.last(where: { $0.eventID == message.eventID }) { return reply(cached.reply) }
    if let current = ride, current.starting, current.start?.eventID == message.eventID {
      return current.waiters.append(reply)
    }
    if HealthInstant.us(now) > message.expiresUtcUs { return reply(Self.ack(error: "startExpired")) }
    if busy { return reply(Self.ack(error: "watchBusy")) }
    if let request, request.id == message.requestID {
      request.timer.cancel()
      self.request = nil
    }
    let current = Ride(rideID: message.rideID, start: message)
    current.starting = true
    current.waiters = [reply]
    ride = current
    issue = nil
    heartRate = nil
    heartRateSent = nil
    buffer = HealthSampleBuffer()
    bufferRide = message.rideID
    acknowledged = 0
    workout.authorize(share: true) { [weak self] error in
      MainActor.assumeIsolated {
        guard let self, self.ride === current, current.starting else { return }
        if let error { return self.failStart(current, Self.startFailure(error)) }
        if current.workoutEnded { return self.endedWhileStarting(current, sessionBegan: false) }
        self.begin(current, message)
      }
    }
  }

  private func begin(_ current: Ride, _ message: StartMessage) {
    let abandon = clock.after(Self.writeBudget) { [weak self] in
      guard let self, current.starting, !current.startAbandoned else { return }
      current.startAbandoned = true
      self.workout.discard(at: self.clock.now)
      self.failStart(current, "startUnconfirmed")
    }
    let plan = HealthPlanWorkout(
      rideID: message.rideID, syncID: message.workout.syncID, syncVersion: message.workout.syncVersion,
      start: HealthInstant.date(message.startedUtcUs))
    workout.begin(
      plan, indoor: message.options.indoor, route: message.options.saveToHealth && message.options.recordGps
    ) { [weak self] error in
      MainActor.assumeIsolated {
        abandon.cancel()
        guard let self else { return }
        guard !current.startAbandoned, self.ride === current, current.starting else {
          if error == nil { self.workout.discard(at: self.clock.now) }
          return
        }
        if let error { return self.failStart(current, Self.startFailure(error)) }
        if current.workoutEnded { return self.endedWhileStarting(current, sessionBegan: true) }
        if HealthInstant.us(self.clock.now) > message.expiresUtcUs {
          self.workout.discard(at: self.clock.now)
          return self.failStart(current, "startExpired")
        }
        current.sessionLive = true
        current.starting = false
        self.answerStart(current, error: nil)
        self.publish()
      }
    }
  }

  private func endedWhileStarting(_ current: Ride, sessionBegan: Bool) {
    if sessionBegan { workout.discard(at: clock.now) }
    current.starting = false
    answerStart(current, error: "startCancelled")
    publish()
  }

  private func failStart(_ current: Ride, _ code: String) {
    current.starting = false
    answerStart(current, error: code)
    if ride === current { ride = nil }
    issue = .failure(code)
    publish()
  }

  private func answerStart(_ current: Ride, error: String?) {
    let reply = Self.ack(error: error)
    if let eventID = current.start?.eventID {
      startReplies.append((eventID, reply))
      if startReplies.count > Self.startReplyCache { startReplies.removeFirst() }
    }
    let waiters = current.waiters
    current.waiters = []
    for waiter in waiters { waiter(reply) }
  }

  private func apply(_ message: RideMessage) -> Data {
    guard let current = ride, current.rideID == message.rideID else { return Self.ack() }
    current.display = message.display
    current.units = message.units
    let terminal = message.phase == .completed || message.phase == .discarded
    let collecting = !current.workoutEnded && current.sessionLive
    if collecting && current.saving && !message.quantities.isEmpty { enqueue(current, .add(message.quantities)) }
    if collecting {
      if message.routeStride > current.stride {
        current.stride = message.routeStride
        current.points = current.points.filter { $0.key % Int64(current.stride) == 0 }
      }
      for indexed in message.route where terminal || indexed.index % Int64(current.stride) == 0 {
        current.points[indexed.index] = indexed.point
      }
    }
    let newer = current.revision.map { message.stateRevision > $0 } ?? true
    guard newer, !current.workoutEnded || terminal || current.orphan else { return Self.ack() }
    let previous = current.phase
    current.revision = message.stateRevision
    current.phase = message.phase
    current.timer = message.timer
    current.lapCount = message.lapCount
    for lap in message.lapsUtcUs where !current.laps.contains(lap) {
      current.laps.insert(lap)
      if collecting && current.saving { enqueue(current, .lap(HealthInstant.date(lap))) }
    }
    guard collecting else {
      if terminal { current.workoutEnded = true }
      return Self.ack()
    }
    let stopped: Set<WatchPhase> = [.paused, .interrupted]
    switch message.phase {
    case .paused, .interrupted:
      if !stopped.contains(previous) { workout.pause() }
    case .running:
      if stopped.contains(previous) { workout.resume() }
    case .completed:
      end(current, finish: true, at: message.endedUtcUs.map(HealthInstant.date) ?? clock.now)
    case .discarded: end(current, finish: false, at: clock.now)
    default: break
    }
    return Self.ack()
  }

  private func end(_ current: Ride, finish: Bool, at date: Date) {
    guard !current.workoutEnded else { return }
    current.workoutEnded = true
    guard current.sessionLive else { return }
    current.sessionLive = false
    if finish && current.saving {
      current.outcome = .pending
      let route =
        current.start?.options.recordGps == true
        ? HealthPlanRoute(
          syncID: Self.routeSyncID(current.rideID),
          points: current.points.sorted { $0.key < $1.key }.map(\.value)) : nil
      enqueue(current, .finish(date, route))
    } else {
      enqueue(current, .discard(date))
      if finish { reportOutcome(current, .notRequested, workoutID: nil, reason: nil) }
    }
  }

  private func endLocally(_ current: Ride, discard: Bool, issue: WatchIssue) {
    current.endedLocally = true
    current.phase = .completed
    self.issue = issue
    if !current.orphan { end(current, finish: !discard, at: clock.now) }
    current.workoutEnded = true
    publish()
  }

  private func sendCommand(_ action: RideAction) {
    guard let current = ride, command == nil else { return }
    let id = UUID()
    let expiry = Double(WatchProtocol.commandExpiryUs) / 1_000_000
    let timer = clock.after(expiry) { [weak self] in
      guard let self, self.command?.id == id else { return }
      self.command = nil
      self.commandFailed(current, action)
    }
    command = (id, action, timer)
    let message = CommandMessage(
      rideID: current.rideID, action: action,
      expiresUtcUs: HealthInstant.us(clock.now) + WatchProtocol.commandExpiryUs)
    send(.command(message)) { [weak self] answer in
      guard let self, self.command?.id == id else { return }
      timer.cancel()
      self.command = nil
      switch answer {
      case .ride(let ride)?: _ = self.apply(ride)
      case .ack(let ack)?:
        if action == .finish || action == .discard {
          let issue: WatchIssue =
            ack.error == Self.versionMismatch ? .failure(Self.versionMismatch) : .endedLocallyUnconfirmed
          self.endLocally(current, discard: action == .discard, issue: issue)
        } else {
          self.issue = .failure(ack.error ?? "unknown")
        }
      default: self.commandFailed(current, action)
      }
      self.publish()
    }
    publish()
  }

  private func commandFailed(_ current: Ride, _ action: RideAction) {
    if action == .finish || action == .discard {
      endLocally(current, discard: action == .discard, issue: .endedLocallyUnconfirmed)
    } else {
      issue = .commandUnconfirmed
    }
    publish()
  }

  private func startUnanswered(_ id: String) {
    guard endRequest(id) else { return }
    issue = .failure("startUnconfirmed")
    publish()
  }

  @discardableResult private func endRequest(_ id: String) -> Bool {
    guard let request, request.id == id else { return false }
    request.timer.cancel()
    self.request = nil
    return true
  }

  private func enqueue(_ current: Ride, _ write: Write) {
    switch write {
    case .add, .lap:
      guard !current.writesStopped else { return }
      if writes.count >= Self.maxWaitingWrites {
        current.uncertain = true
        current.writesStopped = true
        return
      }
    case .finish, .discard: break
    }
    writes.append((current, write))
    pump()
  }

  private func pump() {
    guard writing == nil, !writes.isEmpty else { return }
    let (current, write) = writes.removeFirst()
    writeToken += 1
    let token = writeToken
    switch write {
    case .discard(let date):
      workout.discard(at: date)
      return pump()
    case .add, .lap:
      if current.writesStopped { return pump() }
    case .finish: break
    }
    writing = (token, current)
    let abandon = clock.after(Self.writeBudget) { [weak self] in
      guard let self, self.writing?.token == token else { return }
      self.writing = nil
      current.uncertain = true
      if case .finish = write {
        self.reportOutcome(current, .unknown, workoutID: nil, reason: .ambiguous)
      } else {
        current.writesStopped = true
      }
      self.pump()
    }
    let settle: @MainActor () -> Bool = { [weak self] in
      guard let self, self.writing?.token == token else { return false }
      abandon.cancel()
      self.writing = nil
      return true
    }
    switch write {
    case .add(let quantities):
      workout.add(quantities) { [weak self] error in
        MainActor.assumeIsolated {
          guard settle() else { return }
          if error != nil {
            current.uncertain = true
            current.writesStopped = true
          }
          self?.pump()
        }
      }
    case .lap(let date):
      workout.addLap(at: date) { [weak self] error in
        MainActor.assumeIsolated {
          guard settle() else { return }
          if error != nil {
            current.uncertain = true
            current.writesStopped = true
          }
          self?.pump()
        }
      }
    case .finish(let date, let route):
      workout.finish(at: date, route: route) { [weak self] result in
        MainActor.assumeIsolated {
          guard settle(), let self else { return }
          switch result {
          case .success(let (workoutID, _)):
            if current.uncertain {
              self.reportOutcome(current, .unknown, workoutID: workoutID, reason: .ambiguous)
            } else {
              self.reportOutcome(current, .saved, workoutID: workoutID, reason: nil)
            }
          case .failure:
            self.reportOutcome(current, .unknown, workoutID: nil, reason: .ambiguous)
          }
          self.pump()
        }
      }
    case .discard: break
    }
  }

  private func reportOutcome(_ current: Ride, _ outcome: HealthOutcome, workoutID: String?, reason: HealthReason?) {
    current.outcome = outcome
    report = (
      HealthOutcomeMessage(rideID: current.rideID, outcome: outcome, workoutID: workoutID, reason: reason),
      clock.now.addingTimeInterval(Double(WatchProtocol.replayUs) / 1_000_000)
    )
    sendReport()
    publish()
  }

  private func sendReport() {
    guard let pending = report else { return }
    guard clock.now <= pending.until else {
      report = nil
      return
    }
    guard phone.reachable else { return }
    send(.healthOutcome(pending.message)) { [weak self] answer in
      guard let self, case .ack(let ack)? = answer, ack.error == nil, self.report?.message == pending.message else {
        return
      }
      self.report = nil
    }
  }

  private func sendCancellation() {
    guard let pending = cancellation else { return }
    guard clock.now <= pending.until else {
      cancellation = nil
      return
    }
    guard phone.reachable else { return }
    send(.cancelStart(pending.message)) { [weak self] answer in
      guard let self, case .ack(let ack)? = answer, ack.error == nil, self.cancellation?.message == pending.message
      else { return }
      self.cancellation = nil
    }
  }

  private func sendSamples() {
    guard let rideID = bufferRide, phone.reachable, !samplesInFlight else { return }
    let now = clock.now
    let page = buffer.page()
    let hold = Double(WatchProtocol.heartRateDisplayHoldUs) / 1_000_000
    let fresh = heartRate.flatMap { rate in
      now.timeIntervalSince(rate.at) <= hold && heartRateSent.map { rate.at > $0 } != false ? rate : nil
    }
    guard page != nil || fresh != nil else { return }
    let through = page?.through ?? acknowledged
    samplesInFlight = true
    let message = HealthSamplesMessage(
      rideID: rideID, throughSequence: through, samples: page?.samples ?? [], heartRate: fresh)
    send(.healthSamples(message)) { [weak self] answer in
      guard let self else { return }
      self.samplesInFlight = false
      guard self.bufferRide == rideID, case .ack(let ack)? = answer, ack.error == nil else { return }
      if let sequence = ack.acknowledgedSequence {
        self.buffer.acknowledge(through: sequence)
        self.acknowledged = max(self.acknowledged, sequence)
      }
      if let fresh { self.heartRateSent = fresh.at }
    }
  }

  private func heartRateReceived(_ heartRate: LiveHeartRate) {
    guard let current = ride, current.sessionLive else { return }
    if self.heartRate.map({ heartRate.at >= $0.at }) ?? true { self.heartRate = heartRate }
    publish()
  }

  private func samplesCollected(_ samples: [HealthOriginal]) {
    guard ride?.sessionLive == true else { return }
    buffer.append(samples, at: clock.now)
  }

  private func send(_ message: WatchMessage, answer: @escaping @MainActor (WatchMessage?) -> Void) {
    guard let data = WatchMessages.encode(message) else { return answer(nil) }
    phone.send(
      data,
      reply: { [weak self] reply in
        guard let self else { return }
        MainActor.assumeIsolated {
          switch WatchMessages.decode(reply) {
          case .valid(let decoded): answer(decoded)
          case .versionMismatch:
            self.issue = .failure(Self.versionMismatch)
            answer(.ack(AckMessage(error: Self.versionMismatch)))
          case .invalid: answer(nil)
          }
        }
      },
      failure: { _ in MainActor.assumeIsolated { answer(nil) } })
  }

  private var busy: Bool {
    guard let current = ride else { return writing != nil || !writes.isEmpty }
    return current.starting || current.sessionLive || writing != nil || !writes.isEmpty
      || (current.phase != .completed && current.phase != .discarded && !current.endedLocally)
  }

  private var phase: WatchRideFacts.Phase {
    if request != nil { return .preparing }
    guard let current = ride else { return .idle }
    if current.endedLocally { return .completed }
    if current.starting || (current.revision == nil && !current.orphan) { return .preparing }
    switch current.phase {
    case .preparing: return .preparing
    case .running: return .running
    case .paused: return .paused
    case .interrupted: return .interrupted
    case .completed: return .completed
    case .discarded: return .idle
    }
  }

  private func available(_ control: WatchControl) -> WatchAvailability {
    let phase = phase
    switch control {
    case .start, .options:
      if busy || phase == .preparing { return .disabled(.sessionBusy) }
      return command == nil ? .enabled : .disabled(.commandPending)
    case .cancelStart:
      return phase == .preparing ? .enabled : .disabled(.noActiveRide)
    case .pause, .resume, .lap:
      let active: Bool =
        switch control {
        case .pause: phase == .running
        case .resume: phase == .paused
        default: phase == .running || phase == .paused
        }
      if !active { return .disabled(.noActiveRide) }
      if !phone.reachable { return .disabled(.phoneUnreachable) }
      return command == nil ? .enabled : .disabled(.commandPending)
    case .finish, .discard:
      return [.running, .paused, .interrupted].contains(phase) ? .enabled : .disabled(.noActiveRide)
    }
  }

  private func publish() {
    let phase = phase
    let current = phase == .idle ? nil : ride
    var next = WatchRideFacts()
    next.phase = phase
    next.rideID = current?.rideID
    next.timer = current?.timer
    next.display = current?.display
    next.heartRate = current == nil ? nil : heartRate
    next.units = current?.units ?? .metric
    next.recordGps = current?.start?.options.recordGps ?? false
    next.distanceSource = current?.start?.options.distanceSource
    next.lapCount = current?.lapCount ?? 0
    next.healthOutcome = phase == .completed ? current?.outcome : nil
    next.issue = issue
    next.available = Dictionary(uniqueKeysWithValues: WatchControl.allCases.map { ($0, available($0)) })
    if next != facts { facts = next }
  }

  static let versionMismatch = "watchVersionMismatch"

  static func routeSyncID(_ rideID: String) -> String { "\(WatchProtocol.ownSyncIDPrefix)\(rideID.lowercased()):route" }

  static func ack(error: String? = nil, acknowledgedSequence: Int64? = nil) -> Data {
    WatchMessages.encode(.ack(AckMessage(error: error, acknowledgedSequence: acknowledgedSequence)))!
  }

  // HKError codes: 1 errorHealthDataUnavailable, 4 errorAuthorizationDenied, 5 errorAuthorizationNotDetermined,
  // 6 errorDatabaseInaccessible (locked).
  static func startFailure(_ error: Error) -> String {
    if (error as? WorkoutSessionError) == .unavailable { return "healthUnavailable" }
    let system = error as NSError
    guard system.domain == "com.apple.healthkit" else { return "startInterrupted" }
    switch system.code {
    case 1: return "healthUnavailable"
    case 4, 5: return "healthDenied"
    case 6: return "healthLocked"
    default: return "startInterrupted"
    }
  }
}
