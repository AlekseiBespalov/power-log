import Foundation
import PowerLogCore

#if canImport(UIKit)
  import UIKit
#endif
#if canImport(ActivityKit)
  import ActivityKit
#endif

struct RideActivity: Equatable {
  let id: String
  let rideID: String
}

protocol RideActivityKit: AnyObject {
  var enabled: Bool { get }
  var current: [RideActivity] { get }
  func request(_ content: PowerLogRideAttributes.ContentState) throws -> RideActivity
  func update(_ activity: RideActivity, content: PowerLogRideAttributes.ContentState, done: @escaping () -> Void)
  func end(_ activity: RideActivity)
  func observe(_ activity: RideActivity, ended: @escaping () -> Void)
}

protocol RideCardTimer: AnyObject {
  func replace(after seconds: Double, handler: @escaping () -> Void)
  func cancel()
}

final class AppleRideCard: NSObject, ApplicationRideCard {
  static let startedRideKey = "PowerLog.liveActivity.startedRide"
  private let kit: RideActivityKit
  private let timer: RideCardTimer
  private let defaults: UserDefaults
  private let main: (@escaping () -> Void) -> Void
  private let queue: DispatchQueue
  private let now: () -> Int64
  private var events: ApplicationRideCardEvents?
  private var observers: [NSObjectProtocol] = []
  private var active = false
  private var newest: DomainRideCardFacts?
  private var activity: RideActivity?
  private var numbered: String?
  private var sequence: Int64 = 0
  private var observed: Set<String> = []
  private var updating = false
  private var waiting = false
  private var failedRequest: String?
  private var gone: Set<String> = []

  init(
    kit: RideActivityKit, timer: RideCardTimer, defaults: UserDefaults, center: NotificationCenter,
    active: Notification.Name, inactive: Notification.Name, queue: DispatchQueue,
    main: @escaping (@escaping () -> Void) -> Void, now: @escaping () -> Int64
  ) {
    self.kit = kit
    self.timer = timer
    self.defaults = defaults
    self.queue = queue
    self.main = main
    self.now = now
    super.init()
    observers = [
      center.addObserver(forName: active, object: nil, queue: nil) { [weak self] _ in
        self?.main { self?.becameActive() }
      },
      center.addObserver(forName: inactive, object: nil, queue: nil) { [weak self] _ in
        self?.main { self?.active = false }
      },
    ]
  }

  func attach(rideCardEvents: ApplicationRideCardEvents) {
    events = rideCardEvents
  }

  func publish(facts: DomainRideCardFacts) {
    main { [self] in
      newest = facts
      reconcile()
    }
  }

  func end() {
    main { [self] in
      newest = nil
      activity = nil
      waiting = false
      timer.cancel()
      for card in kit.current { kit.end(card) }
      defaults.removeObject(forKey: Self.startedRideKey)
    }
  }

  private func becameActive() {
    active = true
    failedRequest = nil
    reconcile()
  }

  private func reconcile() {
    guard let facts = newest else { return }
    let ride = facts.rideId
    for card in kit.current where card.rideID != ride { kit.end(card) }
    if activity?.rideID != ride {
      activity = gone.contains(ride) ? nil : kit.current.first { $0.rideID == ride }
      if let adopted = activity { observe(adopted) }
    }
    if numbered != ride {
      numbered = ride
      sequence = 0
    }
    if let activity {
      if updating { waiting = true } else { send(to: activity, facts) }
      return
    }
    guard !gone.contains(ride) else { return }
    if defaults.string(forKey: Self.startedRideKey) == ride {
      gone.insert(ride)
      return
    }
    guard active, kit.enabled, failedRequest != ride, facts.state == .running || facts.state == .paused else {
      return
    }
    let content = render(facts)
    do {
      let started = try kit.request(content)
      activity = started
      defaults.set(ride, forKey: Self.startedRideKey)
      observe(started)
      rearm(content)
    } catch {
      failedRequest = ride
      let failure = error as NSError
      let code = DomainPlatformCode(domain: failure.domain, code: String(failure.code))
      queue.async { [events] in events?.failed(error: ApplicationPortError(kind: .failed, platform: code)) }
    }
  }

  private func send(to activity: RideActivity, _ facts: DomainRideCardFacts) {
    let content = render(facts)
    updating = true
    kit.update(activity, content: content) { [weak self] in
      self?.main {
        guard let self else { return }
        self.updating = false
        guard self.waiting else { return }
        self.waiting = false
        self.reconcile()
      }
    }
    rearm(content)
  }

  private func render(_ facts: DomainRideCardFacts) -> PowerLogRideAttributes.ContentState {
    sequence += 1
    let at = now()
    return RideCardContentState.content(
      RideCardProjection.shared.project(facts: facts, nowUtcUs: at), sequence: sequence, nowUtcUs: at)
  }

  private func rearm(_ content: PowerLogRideAttributes.ContentState) {
    timer.cancel()
    guard let stale = content.staleDate else { return }
    timer.replace(after: max(0, stale.timeIntervalSince1970 - Double(now()) / 1_000_000)) { [weak self] in
      self?.main { self?.reconcile() }
    }
  }

  private func observe(_ card: RideActivity) {
    guard observed.insert(card.id).inserted else { return }
    kit.observe(card) { [weak self] in self?.main { self?.dismissed(card) } }
  }

  private func dismissed(_ card: RideActivity) {
    gone.insert(card.rideID)
    if activity == card { activity = nil }
  }
}

enum RideCardContentState {
  static func date(_ utcUs: KotlinLong?) -> Date? {
    utcUs.map { Date(timeIntervalSince1970: Double($0.int64Value) / 1_000_000) }
  }

  static func entry(_ value: RideCardContent.Entry) -> PowerLogRideAttributes.Entry {
    .init(text: value.text, unit: value.unit, color: value.color, accessibility: value.accessibility)
  }

  static func entries(_ value: RideCardContent.Entries) -> PowerLogRideAttributes.Entries {
    .init(available: entry(value.available), expired: entry(value.expired))
  }

  static func status(_ value: RideCardContent.Status) -> PowerLogRideAttributes.Status {
    .init(text: value.text, symbol: value.symbol, accessibility: value.accessibility)
  }

  static func timer(_ value: RideCardContent.Timer) -> PowerLogRideAttributes.Timer {
    .init(mode: value.mode.name, origin: date(value.originUtcUs), text: value.text, accessibility: value.accessibility)
  }

  static func controls(_ values: [RideCardContent.Control]) -> [PowerLogRideAttributes.Control] {
    values.map {
      .init(
        action: $0.action.name, label: $0.label, symbol: $0.symbol, rideID: $0.rideId, controlToken: $0.controlToken,
        expectedPhase: $0.expectedPhase.name)
    }
  }

  static func render(_ value: RideCardContent.Render) -> PowerLogRideAttributes.Render {
    .init(
      bike: entry(value.bike), heartRate: entry(value.heartRate), status: status(value.status),
      timer: timer(value.timer), controls: controls(value.controls), showReadings: value.showReadings)
  }

  static func content(_ value: RideCardContent, sequence: Int64, nowUtcUs: Int64)
    -> PowerLogRideAttributes.ContentState
  {
    .init(
      rideID: value.rideId, publicationSequence: sequence, controlToken: value.controlToken,
      expectedPhase: value.expectedPhase.name, bike: entries(value.bike), heartRate: entries(value.heartRate),
      bikeExpiresAt: date(value.bikeExpiresAtUtcUs), heartRateExpiresAt: date(value.heartRateExpiresAtUtcUs),
      bikeAvailableStatus: status(value.bikeAvailableStatus), bikeExpiredStatus: status(value.bikeExpiredStatus),
      controls: controls(value.controls), timer: timer(value.timer),
      systemStale: render(value.render(nowUtcUs: nowUtcUs, systemStale: true)),
      showReadings: value.render(nowUtcUs: nowUtcUs, systemStale: false).showReadings,
      staleDate: date(value.staleDateUtcUs))
  }
}

final class MainRideCardTimer: RideCardTimer {
  private var timer: DispatchSourceTimer?

  func replace(after seconds: Double, handler: @escaping () -> Void) {
    cancel()
    let timer = DispatchSource.makeTimerSource(queue: .main)
    timer.schedule(deadline: .now() + seconds, leeway: .milliseconds(50))
    timer.setEventHandler(handler: handler)
    self.timer = timer
    timer.resume()
  }

  func cancel() {
    timer?.cancel()
    timer = nil
  }
}

final class SystemRideActivities: RideActivityKit {
  #if canImport(ActivityKit) && os(iOS)
    var enabled: Bool { ActivityAuthorizationInfo().areActivitiesEnabled }

    var current: [RideActivity] {
      Activity<PowerLogRideAttributes>.activities
        .filter { $0.activityState == .active || $0.activityState == .stale }
        .map { RideActivity(id: $0.id, rideID: $0.attributes.rideID) }
    }

    private func find(_ activity: RideActivity) -> Activity<PowerLogRideAttributes>? {
      Activity<PowerLogRideAttributes>.activities.first { $0.id == activity.id }
    }

    func request(_ content: PowerLogRideAttributes.ContentState) throws -> RideActivity {
      let started = try Activity.request(
        attributes: PowerLogRideAttributes(rideID: content.rideID),
        content: ActivityContent(state: content, staleDate: content.staleDate), pushType: nil)
      return RideActivity(id: started.id, rideID: started.attributes.rideID)
    }

    func update(_ activity: RideActivity, content: PowerLogRideAttributes.ContentState, done: @escaping () -> Void) {
      let found = find(activity)
      Task {
        await found?.update(ActivityContent(state: content, staleDate: content.staleDate))
        done()
      }
    }

    func end(_ activity: RideActivity) {
      let found = find(activity)
      Task { await found?.end(nil, dismissalPolicy: .immediate) }
    }

    func observe(_ activity: RideActivity, ended: @escaping () -> Void) {
      guard let found = find(activity) else { return }
      Task {
        for await state in found.activityStateUpdates where state == .dismissed || state == .ended {
          ended()
          return
        }
      }
    }
  #else
    var enabled: Bool { false }
    var current: [RideActivity] { [] }
    func request(_ content: PowerLogRideAttributes.ContentState) throws -> RideActivity {
      throw CocoaError(.featureUnsupported)
    }
    func update(_ activity: RideActivity, content: PowerLogRideAttributes.ContentState, done: @escaping () -> Void) {
      done()
    }
    func end(_ activity: RideActivity) {}
    func observe(_ activity: RideActivity, ended: @escaping () -> Void) {}
  #endif
}

#if canImport(UIKit)
  extension AppleRideCard {
    convenience init(queue: DispatchQueue) {
      self.init(
        kit: SystemRideActivities(), timer: MainRideCardTimer(), defaults: .standard, center: .default,
        active: UIApplication.didBecomeActiveNotification, inactive: UIApplication.willResignActiveNotification,
        queue: queue, main: { DispatchQueue.main.async(execute: $0) },
        now: { Int64((Date().timeIntervalSince1970 * 1_000_000).rounded(.down)) })
    }
  }
#endif
