import Foundation
import PowerLogCore

protocol WatchSessionSeam: AnyObject {
  var activated: Bool { get }
  var paired: Bool { get }
  var appInstalled: Bool { get }
  var reachable: Bool { get }
  func activate()
  func send(_ data: Data, reply: @escaping (Data) -> Void, failure: @escaping (Error) -> Void)
}

protocol WatchSessionEvents: AnyObject {
  func sessionChanged()
  func sessionDeactivated()
  func sessionReceived(_ data: Data, reply: @escaping (Data) -> Void)
}

protocol WatchAppLauncher: AnyObject {
  func launch(indoor: Bool, completion: @escaping (Error?) -> Void)
}

final class AppleWatchLink: NSObject, ApplicationWatchLink, WatchSessionEvents {
  private let clock: ApplicationClock
  private let post: (@escaping () -> Void) -> Void
  private let makeSession: (WatchSessionEvents) -> WatchSessionSeam
  private let launcher: WatchAppLauncher
  private let lock = NSLock()
  private var session: WatchSessionSeam?
  private var events: ApplicationWatchLinkEvents?

  init(
    clock: ApplicationClock, post: @escaping (@escaping () -> Void) -> Void,
    makeSession: @escaping (WatchSessionEvents) -> WatchSessionSeam, launcher: WatchAppLauncher
  ) {
    self.clock = clock
    self.post = post
    self.makeSession = makeSession
    self.launcher = launcher
  }

  func attach(watchLinkEvents: ApplicationWatchLinkEvents) { events = watchLinkEvents }

  func activate() {
    let current: WatchSessionSeam = lock.withLock {
      if let session { return session }
      let created = makeSession(self)
      session = created
      return created
    }
    current.activate()
  }

  func availability() -> ApplicationWatchLinkAvailability {
    guard let session = lock.withLock({ session }), session.activated else {
      return ApplicationWatchLinkAvailability(installed: false, reachable: false)
    }
    return ApplicationWatchLinkAvailability(
      installed: session.paired && session.appInstalled, reachable: session.reachable)
  }

  func send(message: KotlinByteArray, reply: ApplicationReply<KotlinByteArray>) {
    guard let session = lock.withLock({ session }), session.activated, session.reachable else {
      post { reply.failed(error: ApplicationPortError(kind: .notreachable, platform: nil)) }
      return
    }
    session.send(
      AppleBytes.shared.data(bytes: message),
      reply: { data in reply.ok(value: AppleBytes.shared.bytes(data: data)) },
      failure: { error in reply.failed(error: Self.failure(error)) })
  }

  func launchWatchApp(indoor: Bool, reply: ApplicationReply<KotlinUnit>) {
    launcher.launch(indoor: indoor) { error in
      if let error {
        let system = error as NSError
        reply.failed(
          error: ApplicationPortError(
            kind: .launchfailed, platform: DomainPlatformCode(domain: system.domain, code: String(system.code))))
      } else {
        reply.ok(value: KotlinUnit())
      }
    }
  }

  func sessionChanged() {
    let current = availability()
    post { [weak self] in self?.events?.availabilityChanged(availability: current) }
  }

  func sessionDeactivated() {
    sessionChanged()
    lock.withLock { session }?.activate()
  }

  func sessionReceived(_ data: Data, reply: @escaping (Data) -> Void) {
    let at = clock.read()
    let bytes = AppleBytes.shared.bytes(data: data)
    let responder = WatchResponder(reply)
    post { [weak self] in
      guard let events = self?.events else { return responder.reply(message: AppleBytes.shared.bytes(data: Data())) }
      events.received(message: bytes, respond: responder, at: at)
    }
  }

  // WCErrorDomain 7007 is WCError.notReachable; every other code is a send that may or may not have arrived.
  static func failure(_ error: Error) -> ApplicationPortError {
    let system = error as NSError
    let kind: ApplicationPortErrorKind =
      system.domain == "WCErrorDomain" && system.code == 7007 ? .notreachable : .failed
    return ApplicationPortError(
      kind: kind, platform: DomainPlatformCode(domain: system.domain, code: String(system.code)))
  }
}

final class WatchResponder: NSObject, ApplicationResponder {
  private let lock = NSLock()
  private var handler: ((Data) -> Void)?

  init(_ handler: @escaping (Data) -> Void) { self.handler = handler }

  func reply(message: KotlinByteArray) {
    let pending: ((Data) -> Void)? = lock.withLock {
      defer { handler = nil }
      return handler
    }
    pending?(AppleBytes.shared.data(bytes: message))
  }
}
