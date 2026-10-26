import Foundation
import PowerLogCore

struct WorkoutActivitySnapshot: @unchecked Sendable {
  let rideID: String?
  let phase: String
  let pendingAction: String?
  let timerSeconds: Double
  let clock: PresentationClock
  let readings: LivePresentationState
  let controlToken: String
  let pendingDeadline: Date?
  let confirmationPending: Bool

  init(
    rideID: String?, phase: String, pendingAction: String?, timerSeconds: Double,
    observedAt: Date, observedUptime: Double, bikeSampleAgeSeconds: Double?, heartSampleAgeSeconds: Double?,
    riderPowerW: Double?, heartRateBpm: Double?, controlToken: String, pendingSince: Date? = nil,
    confirmationPending: Bool = true
  ) {
    let clock = try! PresentationClock(
      epoch: TelemetryCaptureClock.processEpoch, monotonicUs: Int64(floor(observedUptime * 1_000_000)),
      utcUs: Int64(floor(observedAt.timeIntervalSince1970 * 1_000_000)))
    var state = try! LivePresentation.shared.adopt(state: LivePresentation.shared.empty(), rideId: rideID)
    if let rideID {
      for (source, metric, age, value) in [
        (LiveSource.controller, "humanPowerW", bikeSampleAgeSeconds, riderPowerW),
        (LiveSource.heartrate, "heartRateBpm", heartSampleAgeSeconds, heartRateBpm),
      ] {
        guard let age, age.isFinite, age >= 0, age <= observedUptime else { continue }
        let acquisitionUs = Int64(floor((observedUptime - age) * 1_000_000))
        let values = value.flatMap { $0.isFinite ? [metric: KotlinDouble(value: $0)] : nil } ?? [:]
        let reading = try! LiveReading(
          acquisition: LiveAcquisition(
            observationId: metric + ":" + String(acquisitionUs), sequence: nil,
            originalTimeUs: clock.utcUs - (clock.monotonicUs - acquisitionUs), epoch: clock.epoch,
            monotonicUs: acquisitionUs), values: values)
        state = try! LivePresentation.shared.observe(
          state: state, rideId: rideID, source: source, reading: reading, clock: clock, backfilled: false)
      }
    }
    self.init(
      rideID: rideID, phase: phase, pendingAction: pendingAction, timerSeconds: timerSeconds,
      clock: clock, readings: state, controlToken: controlToken, pendingSince: pendingSince,
      confirmationPending: confirmationPending)
  }

  init(
    rideID: String?, phase: String, pendingAction: String?, timerSeconds: Double,
    clock: PresentationClock, readings: LivePresentationState, controlToken: String, pendingSince: Date? = nil,
    confirmationPending: Bool = true
  ) {
    self.rideID = rideID
    self.phase = phase
    self.pendingAction = pendingAction
    self.timerSeconds = timerSeconds
    self.clock = clock
    self.readings = readings
    self.controlToken = controlToken
    self.confirmationPending = confirmationPending
    pendingDeadline =
      !confirmationPending || pendingAction == nil || ["idle", "completed", "failed"].contains(phase)
      ? nil
      : (pendingSince ?? Date(timeIntervalSince1970: Double(clock.utcUs) / 1_000_000)).addingTimeInterval(15)
  }

  func project(sequence: Int64, controlToken: String? = nil) throws -> ActivityPresentation? {
    guard let rideID else { return nil }
    let expired = pendingDeadline.map { $0.timeIntervalSince1970 * 1_000_000 <= Double(clock.utcUs) } == true
    return ActivityPresentations.shared.project(
      input: try ActivityInput(
        rideId: rideID, publicationSequence: sequence, controlToken: controlToken ?? self.controlToken,
        phase: expired ? "interrupted" : phase,
        pendingAction: expired || !confirmationPending && ["stop", "discard"].contains(pendingAction ?? "")
          ? nil : pendingAction,
        timerSeconds: KotlinDouble(value: timerSeconds)),
      state: readings, clock: clock)
  }
}

enum WorkoutActivityProjection {
  static func date(_ microseconds: KotlinLong?) -> Date? {
    microseconds.map { Date(timeIntervalSince1970: Double($0.int64Value) / 1_000_000) }
  }

  static func entry(_ value: FormattedEntry) -> PowerLogRideAttributes.Entry {
    .init(text: value.text, unit: value.unit, color: value.color, accessibility: value.accessibility)
  }

  static func entries(_ value: EntryAlternatives) -> PowerLogRideAttributes.Entries {
    .init(available: entry(value.available), expired: entry(value.expired))
  }

  static func status(_ value: ActivityStatus) -> PowerLogRideAttributes.Status {
    .init(text: value.text, symbol: value.symbol, accessibility: value.accessibility)
  }

  static func timer(_ value: TimerInstruction) -> PowerLogRideAttributes.Timer {
    .init(mode: value.mode, origin: date(value.originUs), text: value.text, accessibility: value.accessibility)
  }

  static func controls(_ values: [ActivityControl]) -> [PowerLogRideAttributes.Control] {
    values.map {
      .init(
        action: $0.action, label: $0.label, symbol: $0.symbol, rideID: $0.rideId, controlToken: $0.controlToken,
        expectedPhase: $0.expectedPhase)
    }
  }

  static func render(_ value: ActivityRender) -> PowerLogRideAttributes.Render {
    .init(
      bike: entry(value.bike), heartRate: entry(value.heartRate), status: status(value.status),
      timer: timer(value.timer), controls: controls(value.controls), showReadings: value.showReadings)
  }

  static func content(_ value: ActivityPresentation, at utcUs: Int64, pendingDeadline: Date? = nil)
    -> PowerLogRideAttributes.ContentState
  {
    .init(
      rideID: value.rideId, publicationSequence: value.publicationSequence, controlToken: value.controlToken,
      expectedPhase: value.expectedPhase, bike: entries(value.bike), heartRate: entries(value.heartRate),
      bikeExpiresAt: date(value.bikeExpiresAtUs), heartRateExpiresAt: date(value.heartRateExpiresAtUs),
      bikeAvailableStatus: status(value.bikeAvailableStatus), bikeExpiredStatus: status(value.bikeExpiredStatus),
      controls: controls(value.controls), timer: timer(value.timer),
      systemStale: render(value.render(nowUtcUs: utcUs, systemStale: true)),
      showReadings: value.render(nowUtcUs: utcUs, systemStale: false).showReadings,
      staleDate: [date(value.staleDateUs), pendingDeadline].compactMap { $0 }
        .filter { $0.timeIntervalSince1970 * 1_000_000 > Double(utcUs) }.min())
  }
}

struct WorkoutActivityDeadline: Equatable {
  let rideID: String
  let sequence: Int64
  let utcUs: Int64
}

final class WorkoutActivityExternalActions {
  private(set) var controlToken = UUID().uuidString.lowercased()
  private(set) var finish: ExternalControlRequest?
  private var rideID: String?
  private var phase = "idle"
  private var pendingAction: String?
  private var engineToken: String?

  func adopt(_ snapshot: WorkoutActivitySnapshot) {
    if rideID != snapshot.rideID || phase != snapshot.phase || pendingAction != snapshot.pendingAction
      || engineToken != snapshot.controlToken
    {
      controlToken = snapshot.controlToken
      finish = nil
    }
    rideID = snapshot.rideID
    phase = snapshot.phase
    pendingAction = snapshot.pendingAction
    engineToken = snapshot.controlToken
  }

  func state(_ body: [String: Any]) -> [String: Any] {
    var body = body
    body["controlToken"] = controlToken
    body["externalFinish"] = finish.map(Self.wire) ?? NSNull()
    return body
  }

  static func wire(_ request: ExternalControlRequest) -> [String: String] {
    [
      "rideId": request.rideId, "controlToken": request.controlToken, "expectedPhase": request.expectedPhase,
      "action": request.action,
    ]
  }

  func decision(_ request: ExternalControlRequest, confirmed: Bool) throws -> ExternalControlDecision {
    RideActions.shared.external(
      current: try ExternalControlContext(
        rideId: rideID, controlToken: controlToken, phase: phase, pendingAction: pendingAction),
      request: request, confirmed: confirmed)
  }

  func perform(
    _ request: ExternalControlRequest, confirmed: Bool, discard: Bool = false,
    execute: (String, String) throws -> Void
  ) throws {
    let result = try decision(request, confirmed: confirmed)
    guard result.outcome != "rejected" else {
      throw WorkoutDataError.invalid(result.error ?? "Ride controls are unavailable.")
    }
    if result.outcome == "confirmationRequired" {
      if let finish {
        guard Self.wire(finish) == Self.wire(request) else {
          throw WorkoutDataError.invalid("A Finish confirmation is already open.")
        }
        return
      }
      finish = request
      return
    }
    if request.action == "finish" {
      guard let finish, Self.wire(finish) == Self.wire(request) else {
        throw WorkoutDataError.invalid("Confirm finishing the selected ride.")
      }
    }
    guard let command = result.command, let engineToken else {
      throw WorkoutDataError.invalid("Ride controls are unavailable.")
    }
    try execute(discard && request.action == "finish" ? "discard" : command, engineToken)
    finish = nil
    controlToken = UUID().uuidString.lowercased()
  }

  func cancel(_ request: ExternalControlRequest) {
    guard let finish, Self.wire(finish) == Self.wire(request) else { return }
    self.finish = nil
    controlToken = UUID().uuidString.lowercased()
  }
}

struct WorkoutActivitySchedule {
  private(set) var deadline: WorkoutActivityDeadline?

  mutating func replace(
    _ content: PowerLogRideAttributes.ContentState?, delivered: PowerLogRideAttributes.ContentState? = nil,
    nowUtcUs: Int64? = nil
  ) {
    guard let content,
      ["preparing", "running", "paused", "interrupted", "finishing"].contains(content.expectedPhase)
    else {
      deadline = nil
      return
    }
    let dates = [content.staleDate, delivered?.rideID == content.rideID ? delivered?.staleDate : nil]
      .compactMap { $0 }.filter { date in
        nowUtcUs.map { date.timeIntervalSince1970 > Double($0) / 1_000_000 } ?? true
      }
    guard let staleDate = dates.min() else {
      deadline = nil
      return
    }
    deadline = WorkoutActivityDeadline(
      rideID: content.rideID, sequence: content.publicationSequence,
      utcUs: Int64((staleDate.timeIntervalSince1970 * 1_000_000).rounded()))
  }

  mutating func take(_ candidate: WorkoutActivityDeadline) -> Bool {
    guard candidate == deadline else { return false }
    deadline = nil
    return true
  }
}

enum WorkoutActivityUpdateGate {
  static func deadlineReached(_ expiry: Date?, at date: Date) -> Bool { expiry.map { $0 <= date } ?? false }

  static func allows(deadline: Bool, controlChanged: Bool, lastUpdate: Double, now: Double) -> Bool {
    deadline || controlChanged || now - lastUpdate >= ActivityPresentations.shared.refreshSeconds
  }
}
