#if os(iOS) || os(watchOS)
  import Foundation
  import WatchConnectivity

  /// Only live, bounded messages cross this transport; there is no file transfer or durable outbox.
  final class WorkoutCompanionTransport: NSObject, WCSessionDelegate {
    let queue: DispatchQueue
    var received: ((WorkoutCompanionMessage, @escaping (WorkoutCompanionMessage) -> Void) -> Void)?
    var changed: (() -> Void)?
    private let session: WCSession?
    var reachable: Bool { session?.activationState == .activated && session?.isReachable == true }
    var installed: Bool {
      // Installation metadata is valid only after activation.
      // https://developer.apple.com/documentation/watchconnectivity/wcsession/iswatchappinstalled
      guard let session, session.activationState == .activated else { return false }
      #if os(iOS)
        return session.isPaired && session.isWatchAppInstalled
      #else
        return session.isCompanionAppInstalled
      #endif
    }
    init(queue: DispatchQueue) {
      self.queue = queue
      session = WCSession.isSupported() ? .default : nil
      super.init()
      session?.delegate = self
      session?.activate()
    }
    func send(_ message: WorkoutCompanionMessage, completion: ((Result<WorkoutCompanionMessage, Error>) -> Void)? = nil)
    {
      guard let session, session.activationState == .activated, session.isReachable else {
        completion?(.failure(WorkoutCompanionError.invalid("The companion is not reachable.")))
        return
      }
      do {
        let bytes = try message.encoded()
        session.sendMessageData(
          bytes,
          replyHandler: { [weak self] data in
            self?.queue.async { completion?(Result { try WorkoutCompanionMessage.decode(data) }) }
          }, errorHandler: { [weak self] error in self?.queue.async { completion?(.failure(error)) } })
      } catch { completion?(.failure(error)) }
    }
    func session(_ session: WCSession, didReceiveMessageData data: Data, replyHandler: @escaping (Data) -> Void) {
      queue.async {
        do {
          let message = try WorkoutCompanionMessage.decode(data)
          guard let received = self.received else { throw WorkoutCompanionError.invalid("Companion is starting.") }
          received(message) { response in
            if let data = try? response.encoded() { replyHandler(data) }
          }
        } catch {
          if let data = try? WorkoutCompanionMessage(kind: .acknowledgement, error: error.localizedDescription)
            .encoded()
          {
            replyHandler(data)
          }
        }
      }
    }
    func session(
      _ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?
    ) {
      queue.async { self.changed?() }
    }
    func sessionReachabilityDidChange(_ session: WCSession) { queue.async { self.changed?() } }
    #if os(iOS)
      func sessionWatchStateDidChange(_ session: WCSession) { queue.async { self.changed?() } }
      func sessionDidBecomeInactive(_ session: WCSession) { queue.async { self.changed?() } }
      func sessionDidDeactivate(_ session: WCSession) {
        queue.async { self.changed?() }
        session.activate()
      }
    #endif
  }
#endif
