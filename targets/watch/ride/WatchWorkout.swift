import Foundation

protocol WatchWorkout: AnyObject {
  var onHeartRateReceived: ((LiveHeartRate) -> Void)? { get set }
  var onSamplesCollected: (([HealthOriginal]) -> Void)? { get set }
  func authorize(share: Bool, completion: @escaping (Error?) -> Void)
  func begin(_ workout: HealthPlanWorkout, indoor: Bool, route: Bool, completion: @escaping (Error?) -> Void)
  func pause()
  func resume()
  func add(_ quantities: [HealthPlanQuantity], completion: @escaping (Error?) -> Void)
  func addLap(at date: Date, completion: @escaping (Error?) -> Void)
  func finish(at date: Date, route: HealthPlanRoute?, completion: @escaping (Result<(String, Bool), Error>) -> Void)
  func discard(at date: Date)
  func recoverOrphan(completion: @escaping (String?) -> Void)
}

protocol WatchClock: AnyObject {
  var now: Date { get }
  @discardableResult func after(_ seconds: TimeInterval, _ action: @escaping @MainActor () -> Void) -> WatchTimer
}

protocol WatchTimer: AnyObject {
  func cancel()
}

final class SystemWatchClock: WatchClock {
  var now: Date { Date() }

  func after(_ seconds: TimeInterval, _ action: @escaping @MainActor () -> Void) -> WatchTimer {
    let timer = DispatchTimer()
    let item = DispatchWorkItem { MainActor.assumeIsolated { action() } }
    timer.item = item
    DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: item)
    return timer
  }

  private final class DispatchTimer: WatchTimer {
    var item: DispatchWorkItem?
    func cancel() { item?.cancel() }
  }
}

extension WorkoutSession: WatchWorkout {}
