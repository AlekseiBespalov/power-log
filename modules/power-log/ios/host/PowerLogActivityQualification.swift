#if POWER_LOG_DEV_TOOLS && os(iOS)
  import Foundation
  import OSLog
  import PowerLogCore
  import UIKit

  enum PowerLogActivityQualification {
    static let rideID = "11111111-1111-4111-8111-111111111111"
    static let token = "66666666-6666-4666-8666-666666666666"

    static func acquiredAt(_ arguments: [String] = ProcessInfo.processInfo.arguments) -> Date? {
      guard arguments.contains("--activity-qualification") else { return nil }
      return arguments.firstIndex(of: "--activity-qualification-acquiredAt")
        .flatMap { $0 + 1 < arguments.count ? Double(arguments[$0 + 1]) : nil }
        .map { Date(timeIntervalSince1970: $0) } ?? Date().addingTimeInterval(-5)
    }

    static func start(acquired: Date, queue: DispatchQueue, running: Bool) -> AppleRideCard {
      let card = AppleRideCard(
        kit: LoggedRideActivities(SystemRideActivities()), timer: MainRideCardTimer(), defaults: .standard,
        center: .default, active: UIApplication.didBecomeActiveNotification,
        inactive: UIApplication.willResignActiveNotification, queue: queue,
        main: { DispatchQueue.main.async(execute: $0) }, now: utcUs)
      card.publish(facts: facts(acquired: acquired, readings: false))
      DispatchQueue.main.asyncAfter(deadline: .now() + max(0, acquired.timeIntervalSinceNow)) {
        if running {
          let app = UIApplication.shared
          let task = app.beginBackgroundTask(withName: "Activity qualification")
          DispatchQueue.main.asyncAfter(deadline: .now() + 40) { app.endBackgroundTask(task) }
        }
        card.publish(facts: facts(acquired: acquired, readings: true))
      }
      return card
    }

    static func facts(acquired: Date, readings: Bool) -> DomainRideCardFacts {
      let at = Int64((acquired.timeIntervalSince1970 * 1_000_000).rounded(.down))
      return DomainRideCardFacts(
        rideId: rideID, token: token, state: .running,
        timer: DomainRideTimer(state: .running, baseUs: 5_000_000, originUtcUs: KotlinLong(value: at - 5_000_000)),
        powerW: readings ? KotlinDouble(value: 200) : nil, heartRateBpm: readings ? KotlinDouble(value: 150) : nil,
        bikeFreshUntilUtcUs: readings ? KotlinLong(value: at + 21_000_000) : nil,
        heartRateFreshUntilUtcUs: readings ? KotlinLong(value: at + 30_000_000) : nil,
        controls: [.pause, .finish])
    }

    static func utcUs() -> Int64 { Int64((Date().timeIntervalSince1970 * 1_000_000).rounded(.down)) }
  }

  final class LoggedRideActivities: RideActivityKit {
    private let kit: RideActivityKit
    private var shown: PowerLogRideAttributes.ContentState?
    private let log = Logger(subsystem: "app.powerlog.activity.qualification", category: "Publication")

    init(_ kit: RideActivityKit) { self.kit = kit }

    var enabled: Bool { kit.enabled }
    var current: [RideActivity] { kit.current }

    func request(_ content: PowerLogRideAttributes.ContentState) throws -> RideActivity {
      let activity = try kit.request(content)
      logged(content)
      return activity
    }

    func update(_ activity: RideActivity, content: PowerLogRideAttributes.ContentState, done: @escaping () -> Void) {
      kit.update(activity, content: content) { [self] in
        DispatchQueue.main.async { logged(content) }
        done()
      }
    }

    func end(_ activity: RideActivity) { kit.end(activity) }

    func observe(_ activity: RideActivity, ended: @escaping () -> Void) { kit.observe(activity, ended: ended) }

    private func logged(_ content: PowerLogRideAttributes.ContentState) {
      let now = Date()
      let deadline = shown?.staleDate.map { $0 <= now } ?? false
      shown = content
      let render = content.render(at: now, isStale: false)
      let staleDate = content.staleDate?.timeIntervalSince1970 ?? -1
      log.notice(
        "PUBLISH sequence=\(content.publicationSequence) now=\(now.timeIntervalSince1970) staleDate=\(staleDate) deadline=\(deadline) bike=\(render.bike.text, privacy: .public) heart=\(render.heartRate.text, privacy: .public)"
      )
    }
  }
#endif
