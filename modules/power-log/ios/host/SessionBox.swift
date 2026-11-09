import Foundation
import PowerLogCore

final class SessionBox: @unchecked Sendable {
  private let lock = NSLock()
  private var session: BridgeSession?

  var current: BridgeSession? { lock.withLock { session } }

  func open(_ opened: BridgeSession?) { lock.withLock { session = opened } }

  func close() {
    lock.withLock { () -> BridgeSession? in
      defer { session = nil }
      return session
    }?.close()
  }
}
