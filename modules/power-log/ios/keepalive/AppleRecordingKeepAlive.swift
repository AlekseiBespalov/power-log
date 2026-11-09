import Foundation
import PowerLogCore

#if canImport(UIKit)
  import UIKit
#endif

protocol BackgroundTasks: AnyObject {
  func begin(name: String, expired: @escaping () -> Void) -> Int?
  func end(_ task: Int)
}

final class AppleRecordingKeepAlive: NSObject, ApplicationRecordingKeepAlive {
  private let tasks: BackgroundTasks
  private let queue: DispatchQueue
  private let main: (@escaping () -> Void) -> Void
  private var events: ApplicationKeepAliveEvents?
  private var task: Int?

  init(tasks: BackgroundTasks, queue: DispatchQueue, main: @escaping (@escaping () -> Void) -> Void) {
    self.tasks = tasks
    self.queue = queue
    self.main = main
  }

  func attach(keepAliveEvents: ApplicationKeepAliveEvents) {
    events = keepAliveEvents
  }

  func hold(kind: ApplicationHoldKind, location: Bool, reply: ApplicationReply<KotlinUnit>) {
    main { [self] in
      guard kind == .reconnect, task == nil else { return reply.ok(value: KotlinUnit()) }
      var begun: Int?
      begun = tasks.begin(name: "Power Log bike reconnect") { [weak self] in
        guard let self, let ended = begun, self.task == ended else { return }
        self.task = nil
        self.tasks.end(ended)
        self.queue.async { [events = self.events] in events?.expiring(kind: .reconnect) }
      }
      guard let begun else {
        return reply.failed(error: ApplicationPortError(kind: .notallowedinbackground, platform: nil))
      }
      task = begun
      reply.ok(value: KotlinUnit())
    }
  }

  func release(kind: ApplicationHoldKind) {
    guard kind == .reconnect else { return }
    main { [self] in
      guard let ended = task else { return }
      task = nil
      tasks.end(ended)
    }
  }
}

#if canImport(UIKit)
  final class ApplicationBackgroundTasks: BackgroundTasks {
    func begin(name: String, expired: @escaping () -> Void) -> Int? {
      let task = UIApplication.shared.beginBackgroundTask(withName: name, expirationHandler: expired)
      return task == .invalid ? nil : task.rawValue
    }

    func end(_ task: Int) {
      UIApplication.shared.endBackgroundTask(UIBackgroundTaskIdentifier(rawValue: task))
    }
  }

  extension AppleRecordingKeepAlive {
    convenience init(queue: DispatchQueue) {
      self.init(tasks: ApplicationBackgroundTasks(), queue: queue, main: { DispatchQueue.main.async(execute: $0) })
    }
  }
#endif
