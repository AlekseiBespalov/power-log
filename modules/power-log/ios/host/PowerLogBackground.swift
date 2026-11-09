import Foundation
import PowerLogCore

enum PowerLogBackground {
  static let terminateBudgetUs: Int64 = 2_000_000

  static func forward(
    to host: HostFacade, from center: NotificationCenter, background: Notification.Name,
    terminate: Notification.Name, tasks: BackgroundTasks
  ) {
    _ = center.addObserver(forName: background, object: nil, queue: nil) { _ in
      let lock = NSLock()
      var task: Int?
      var ended = false
      let end = {
        let ending: Int? = lock.withLock {
          guard !ended, let task else { return nil }
          ended = true
          return task
        }
        if let ending { tasks.end(ending) }
      }
      let begun = tasks.begin(name: "Power Log save", expired: end)
      lock.withLock { task = begun }
      host.didEnterBackground(done: end)
    }
    _ = center.addObserver(forName: terminate, object: nil, queue: nil) { _ in
      _ = host.willTerminate(timeoutUs: terminateBudgetUs)
    }
  }
}
