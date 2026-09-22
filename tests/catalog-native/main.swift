import Foundation

let root = FileManager.default.temporaryDirectory.appendingPathComponent("power-log-catalog-test-\(UUID().uuidString)")
defer { try? FileManager.default.removeItem(at: root) }
let archive = try WorkoutArchive(rootURL: root)
let start = try WorkoutCoding.date("2026-01-01T00:00:00.000Z")
var expected: [String] = []
for index in 0..<137 {
  let id = String(format: "00000000-0000-4000-8000-%012x", index)
  expected.append(id)
  _ = try archive.create(id: id, startedAt: start, indoor: true, watchEnabled: false)
}
expected.sort(by: >)
let first = try archive.list(limit: 51)
precondition(first.map(\.id) == Array(expected.prefix(51)), "first page uses descending stable ID ties")
let newest = "ffffffff-ffff-4fff-8fff-ffffffffffff"
_ = try archive.create(id: newest, startedAt: start.addingTimeInterval(1), indoor: true, watchEnabled: false)
var seen = first.map(\.id), previous = first.last!
while true {
  let page = try archive.list(limit: 51, beforeStartedAt: previous.startedAt, beforeID: previous.id)
  precondition(page.count <= 51)
  guard let last = page.last else { break }
  seen.append(contentsOf: page.map(\.id)); previous = last
}
precondition(seen == expected, "new first-page entries cannot create pagination duplicates or omissions")
precondition(Set(seen).count == 137)
let refreshed = try archive.list(limit: 51)
precondition(refreshed.first?.id == newest, "refresh reveals the new first-page ride")
let maximum = try archive.list(limit: 100_000)
precondition(maximum.count == 100, "catalog admission remains bounded")
let minimum = try archive.list(limit: 0)
precondition(minimum.count == 1, "invalid low page limits remain bounded")
print("Native catalog tests passed: 138 actual writer records, timestamp ties, keyset pages, concurrent insert, bounded limits")
