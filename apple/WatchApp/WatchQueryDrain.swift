import Foundation

/// Serializes finite query pages while retaining notifications that arrive during a page read.
/// The caller owns the query, anchor and observer completions; all calls occur on one executor.
struct WatchQueryDrain {
  private(set) var isReading = false
  private(set) var isStopped = false
  private var invalidated = false

  /// Returns true only when the caller must launch the first page of a new drain.
  mutating func invalidate() -> Bool {
    guard !isStopped else { return false }
    if isReading { invalidated = true; return false }
    isReading = true
    return true
  }

  /// A notification received during even a short final page requires another anchored read.
  mutating func finishPage(hasMore: Bool) -> Bool {
    guard isReading, !isStopped else { return false }
    if hasMore || invalidated { invalidated = false; return true }
    isReading = false
    return false
  }

  mutating func fail() {
    isReading = false
    invalidated = false
  }

  mutating func stop() {
    fail()
    isStopped = true
  }
}
