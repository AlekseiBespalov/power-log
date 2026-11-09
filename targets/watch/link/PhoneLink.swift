import Foundation

protocol PhoneChannel: AnyObject {
  var reachable: Bool { get }
  var changed: (() -> Void)? { get set }
  var received: ((Data, @escaping (Data) -> Void) -> Void)? { get set }
  func activate()
  func send(_ data: Data, reply: @escaping (Data) -> Void, failure: @escaping (Error) -> Void)
}

#if os(watchOS)
  import WatchConnectivity

  final class PhoneLink: NSObject, PhoneChannel, WCSessionDelegate {
    var changed: (() -> Void)?
    var received: ((Data, @escaping (Data) -> Void) -> Void)?
    private let session: WCSession? = WCSession.isSupported() ? .default : nil

    override init() {
      super.init()
      session?.delegate = self
    }

    var reachable: Bool { session?.activationState == .activated && session?.isReachable == true }

    func activate() { session?.activate() }

    func send(_ data: Data, reply: @escaping (Data) -> Void, failure: @escaping (Error) -> Void) {
      guard let session else { return failure(CocoaError(.featureUnsupported)) }
      session.sendMessageData(
        data, replyHandler: { answer in DispatchQueue.main.async { reply(answer) } },
        errorHandler: { error in DispatchQueue.main.async { failure(error) } })
    }

    func session(
      _ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?
    ) {
      DispatchQueue.main.async { self.changed?() }
    }

    func sessionReachabilityDidChange(_ session: WCSession) { DispatchQueue.main.async { self.changed?() } }

    func session(_ session: WCSession, didReceiveMessageData messageData: Data, replyHandler: @escaping (Data) -> Void)
    {
      DispatchQueue.main.async { self.received?(messageData, replyHandler) }
    }
  }
#endif
