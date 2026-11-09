import Foundation

#if os(iOS)
  import HealthKit
  import PowerLogCore
  import WatchConnectivity

  final class SystemWatchSession: NSObject, WatchSessionSeam, WCSessionDelegate {
    private let events: WatchSessionEvents
    private let session = WCSession.default

    init(events: WatchSessionEvents) {
      self.events = events
      super.init()
      session.delegate = self
    }

    static var supported: Bool { WCSession.isSupported() }

    var activated: Bool { session.activationState == .activated }
    var paired: Bool { session.isPaired }
    var appInstalled: Bool { session.isWatchAppInstalled }
    var reachable: Bool { session.isReachable }

    func activate() { session.activate() }

    func send(_ data: Data, reply: @escaping (Data) -> Void, failure: @escaping (Error) -> Void) {
      session.sendMessageData(data, replyHandler: reply, errorHandler: failure)
    }

    func session(
      _ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?
    ) {
      events.sessionChanged()
    }

    func sessionReachabilityDidChange(_ session: WCSession) { events.sessionChanged() }

    func sessionWatchStateDidChange(_ session: WCSession) { events.sessionChanged() }

    func sessionDidBecomeInactive(_ session: WCSession) { events.sessionChanged() }

    func sessionDidDeactivate(_ session: WCSession) { events.sessionDeactivated() }

    func session(_ session: WCSession, didReceiveMessageData messageData: Data, replyHandler: @escaping (Data) -> Void)
    {
      events.sessionReceived(messageData, reply: replyHandler)
    }
  }

  final class HealthWatchLauncher: WatchAppLauncher {
    private let store = HKHealthStore()

    func launch(indoor: Bool, completion: @escaping (Error?) -> Void) {
      let configuration = HKWorkoutConfiguration()
      configuration.activityType = .cycling
      configuration.locationType = indoor ? .indoor : .outdoor
      store.startWatchApp(with: configuration) { _, error in completion(error) }
    }
  }

  extension AppleWatchLink {
    static var supported: Bool { SystemWatchSession.supported }

    convenience init(clock: ApplicationClock, queue: DispatchQueue) {
      self.init(
        clock: clock, post: { queue.async(execute: $0) }, makeSession: { SystemWatchSession(events: $0) },
        launcher: HealthWatchLauncher())
    }
  }
#endif
