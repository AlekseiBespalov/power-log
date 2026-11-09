import Foundation

struct HealthSampleBuffer {
  static let maxAge: TimeInterval = 60
  static let maxEntries = 2_048
  static let maxBytes = 512 * 1_024

  private struct Entry {
    let sequence: Int64
    let sample: HealthOriginal
    let added: Date
    let bytes: Int
  }

  private var entries: [Entry] = []
  private var nextSequence: Int64 = 1
  private(set) var gaps = 0

  var isEmpty: Bool { entries.isEmpty }

  mutating func append(_ samples: [HealthOriginal], at now: Date) {
    let encoder = JSONEncoder()
    for sample in samples where !(sample.syncID?.hasPrefix(WatchProtocol.ownSyncIDPrefix) ?? false) {
      let bytes = (try? encoder.encode(sample).count) ?? Self.maxBytes + 1
      guard bytes <= Self.maxBytes else {
        gaps += 1
        continue
      }
      entries.append(Entry(sequence: nextSequence, sample: sample, added: now, bytes: bytes))
      nextSequence += 1
    }
    trim(at: now)
  }

  mutating func trim(at now: Date) {
    let fresh = entries.firstIndex { now.timeIntervalSince($0.added) <= Self.maxAge } ?? entries.count
    drop(fresh)
    if entries.count > Self.maxEntries { drop(entries.count - Self.maxEntries) }
    var total = entries.reduce(0) { $0 + $1.bytes }
    var over = 0
    while total > Self.maxBytes {
      total -= entries[over].bytes
      over += 1
    }
    drop(over)
  }

  func page() -> (samples: [HealthOriginal], through: Int64)? {
    let page = entries.prefix(WatchProtocol.maxHealthSamples)
    guard let last = page.last else { return nil }
    return (page.map(\.sample), last.sequence)
  }

  mutating func acknowledge(through sequence: Int64) {
    entries.removeAll { $0.sequence <= sequence }
  }

  private mutating func drop(_ count: Int) {
    guard count > 0 else { return }
    gaps += count
    entries.removeFirst(count)
  }
}
