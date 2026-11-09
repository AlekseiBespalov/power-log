import Foundation

struct StartTombstones {
  static let lifetime: TimeInterval = 60
  static let capacity = 64

  private var entries: [(id: String, at: Date)] = []

  mutating func insert(_ ids: [String?], at now: Date) {
    for id in ids.compactMap({ $0 }) {
      entries.append((id, now))
      if entries.count > Self.capacity { entries.removeFirst(entries.count - Self.capacity) }
    }
  }

  func contains(_ id: String?, at now: Date) -> Bool {
    guard let id else { return false }
    return entries.contains { $0.id == id && now.timeIntervalSince($0.at) < Self.lifetime }
  }
}
