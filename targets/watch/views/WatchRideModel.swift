import Combine
import Foundation

@MainActor
final class WatchRideModel: ObservableObject {
  enum Mode { case idle, active }

  struct Control: Equatable {
    let label: String
    let enabled: Bool
    let accessibilityLabel: String
    let hint: String?
  }

  struct State: Equatable {
    let mode: Mode
    let title: String
    let showsProgress: Bool
    let showsCheckmark: Bool
    let canStart: Bool
    let canEndInterrupted: Bool
    let paused: Bool
    let status: String
    let issue: String?
    let health: String?
    let lap: String?
    let optionsLabel: String
    let controls: [WatchControl: Control]
  }

  struct Readings: Equatable {
    let timer: String
    let power: String
    let cadence: String
    let controller: String
    let controllerWarning: Bool
    let heart: String
    let distance: String
    let distanceSymbol: String
    let distanceSource: String
    let gps: String
    let lap: String?
  }

  @Published var confirmingRideID: String?
  @Published var showsOptions = false
  @Published var page = 0
  @Published private(set) var state: State

  private let facade: WatchRideFacade
  private var observation: AnyCancellable?

  init<Facade: WatchRideFacade & ObservableObject>(_ facade: Facade) {
    self.facade = facade
    state = Self.state(facade.facts, options: facade.options)
    observation = facade.objectWillChange.sink { [weak self] _ in
      DispatchQueue.main.async { MainActor.assumeIsolated { self?.refresh() } }
    }
  }

  var options: WatchOptions { facade.options }

  func refresh() {
    let next = Self.state(facade.facts, options: facade.options)
    if next.mode == .idle { page = 0 }
    if next.showsProgress || next.mode == .active { showsOptions = false }
    if next != state { state = next }
  }

  func start() { facade.start() }
  func cancelStart() { facade.cancelStart() }
  func pauseOrResume() { state.paused ? facade.resume() : facade.pause() }
  func lap() { facade.lap() }
  func setOptions(_ options: WatchOptions) { facade.setOptions(options) }

  func askToFinish() { confirmingRideID = facade.facts.rideID }

  func confirmFinish(discard: Bool) {
    guard let rideID = confirmingRideID else { return }
    confirmingRideID = nil
    facade.finish(rideID: rideID, discard: discard)
  }

  func discardInterrupted() {
    guard let rideID = facade.facts.rideID else { return }
    facade.finish(rideID: rideID, discard: true)
  }

  func readings(at now: Date) -> Readings { Self.readings(facade.facts, at: now) }

  static func state(_ facts: WatchRideFacts, options: WatchOptions) -> State {
    let active = facts.phase == .running || facts.phase == .paused
    let paused = facts.phase == .paused
    let title: String =
      switch facts.phase {
      case .preparing: "Starting with phone…"
      case .completed: "Ride finished"
      default: "Ready to ride"
      }
    func control(_ which: WatchControl, _ label: String, _ accessibility: String? = nil) -> Control {
      let availability = facts.available[which] ?? .disabled(.noActiveRide)
      return Control(
        label: label, enabled: availability.enabled, accessibilityLabel: accessibility ?? label,
        hint: availability.reason.map(WatchText.reason))
    }
    let pauseOrResume =
      paused ? control(.resume, "Resume", "Resume ride") : control(.pause, "Pause", "Pause ride")
    return State(
      mode: active ? .active : .idle, title: title, showsProgress: facts.phase == .preparing,
      showsCheckmark: facts.phase == .completed, canStart: facts.phase == .idle || facts.phase == .completed,
      canEndInterrupted: facts.phase == .interrupted, paused: paused, status: paused ? "PAUSED" : "RIDING",
      issue: facts.issue.map(WatchText.issue),
      health: facts.phase == .completed ? WatchText.health(facts.healthOutcome) : nil,
      lap: WatchText.lap(facts.lapCount), optionsLabel: WatchText.rideType(indoor: options.indoor),
      controls: [
        .start: control(.start, "Start ride"),
        .options: control(
          .options, WatchText.rideType(indoor: options.indoor),
          "Ride options, \(options.indoor ? "indoor" : "outdoor")"),
        .cancelStart: control(.cancelStart, "Cancel Start"),
        .pause: pauseOrResume, .resume: pauseOrResume,
        .lap: control(.lap, "Lap", "Mark lap"),
        .finish: control(.finish, "Finish ride"),
        .discard: control(.discard, "Discard ride"),
      ])
  }

  static func readings(_ facts: WatchRideFacts, at now: Date) -> Readings {
    let nowUs = HealthInstant.us(now)
    let display = facts.display.flatMap { nowUs < $0.freshUntilUtcUs ? $0 : nil }
    let hold = Double(WatchProtocol.heartRateDisplayHoldUs) / 1_000_000
    let heart = facts.heartRate.flatMap { now.timeIntervalSince($0.at) < hold ? $0.bpm : nil }
    let distance = WatchText.distance(facts.display?.distanceM, units: facts.units)
    return Readings(
      timer: WatchText.timer(facts.timer, at: now), power: WatchText.whole(display?.powerW),
      cadence: WatchText.whole(display?.cadenceRpm), controller: WatchText.controller(fresh: display != nil),
      controllerWarning: display == nil, heart: WatchText.whole(heart), distance: distance.text,
      distanceSymbol: distance.symbol, distanceSource: WatchText.distanceSource(facts.distanceSource),
      gps: WatchText.gps(facts.recordGps), lap: WatchText.lap(facts.lapCount))
  }
}
