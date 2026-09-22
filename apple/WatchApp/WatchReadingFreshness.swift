import Foundation

/// Display-only policy. Holding a reading never changes its recorded time or creates samples.
enum WatchReadingFreshness {
  case unavailable, live, held

  static func bikeSample(age: TimeInterval?, running: Bool) -> WatchReadingFreshness {
    guard running, let age, age.isFinite, age >= -2, age < 6 else { return .unavailable }
    return age <= 2.5 ? .live : .held
  }
}
