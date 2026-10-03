import Foundation
import zlib

var assertions = 0
func check(_ condition: Bool, _ message: String) {
  assertions += 1
  if !condition { fatalError(message) }
}
func rejects(_ message: String, _ body: () throws -> Void) {
  do {
    try body()
    check(false, message)
  } catch let failure as ExportFailure {
    check(failure.code == "sink", message + " (\(failure.code): \(failure.message))")
  } catch { check(false, message + " threw \(error)") }
}
func crc(_ data: Data) -> Int {
  Int(data.withUnsafeBytes { crc32(0, $0.bindMemory(to: Bytef.self).baseAddress, uInt($0.count)) })
}
func inflateRaw(_ data: Data, capacity: Int) -> Data? {
  var stream = z_stream()
  guard inflateInit2_(&stream, -MAX_WBITS, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else {
    return nil
  }
  defer { inflateEnd(&stream) }
  var output = Data(count: capacity + 1)
  let status = data.withUnsafeBytes { input in
    output.withUnsafeMutableBytes { out -> Int32 in
      stream.next_in = UnsafeMutablePointer(mutating: input.bindMemory(to: Bytef.self).baseAddress)
      stream.avail_in = uInt(input.count)
      stream.next_out = out.bindMemory(to: Bytef.self).baseAddress
      stream.avail_out = uInt(out.count)
      return inflate(&stream, Z_FINISH)
    }
  }
  guard status == Z_STREAM_END, stream.avail_in == 0 else { return nil }
  output.count = Int(stream.total_out)
  return output
}
func staging(_ id: String) -> URL { root.appendingPathComponent(".powerlog-export-\(id).part") }

let base = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-export-sink-" + UUID().uuidString)
defer { try? FileManager.default.removeItem(at: base) }
let root = base.appendingPathComponent("Exports", isDirectory: true)
let sinks = ExportSinks(root: root)
let fm = FileManager.default

// One file: a stored header, two compressed entries and a trailer, patched afterwards.
let id = try sinks.open()
check(fm.fileExists(atPath: staging(id).path), "open stages a hidden part file")
check(
  (try root.resourceValues(forKeys: [.isExcludedFromBackupKey])).isExcludedFromBackup == true, "exports skip backup")
try sinks.write(id, Data("HEAD0000".utf8))
try sinks.beginDeflate(id)
rejects("a second stage cannot open inside the first") { try sinks.beginDeflate(id) }
var first = Data()
var random = SystemRandomNumberGenerator()
for size in [0, 1, 17, 65_536, 300_000, 3] {
  var chunk = Data(count: size)
  for index in 0..<size {
    chunk[index] = index % 7 == 0 ? UInt8.random(in: 0...255, using: &random) : UInt8(ascii: "a") + UInt8(index % 26)
  }
  try sinks.write(id, chunk)
  first.append(chunk)
}
rejects("a patch cannot reach into the open stage") { try sinks.write(id, at: 8, Data([1])) }
try sinks.write(id, at: 4, Data("1234".utf8))
let stage = try sinks.endDeflate(id)
check(stage["inputBytes"] as? Int == first.count, "stage counts its uncompressed bytes")
check(stage["crc32"] as? Int == crc(first), "stage CRC-32 covers the uncompressed bytes")
let firstSize = stage["outputBytes"] as! Int
check(firstSize > 0 && firstSize < first.count, "stage reports its compressed size")
rejects("a stage cannot end twice") { _ = try sinks.endDeflate(id) }
try sinks.beginDeflate(id)
let empty = try sinks.endDeflate(id)
check(empty["inputBytes"] as? Int == 0 && empty["crc32"] as? Int == 0, "an empty stage is valid")
let emptySize = empty["outputBytes"] as! Int
try sinks.write(id, Data("TAIL".utf8))
let total = 8 + firstSize + emptySize + 4
try sinks.write(id, at: 0, Data("head".utf8))
try sinks.write(id, at: Double(total - 4), Data("tail".utf8))
rejects("a patch cannot extend the file") { try sinks.write(id, at: Double(total - 1), Data([1, 2])) }
rejects("a patch needs an exact offset") { try sinks.write(id, at: 1.5, Data([1])) }
rejects("a patch needs a non-negative offset") { try sinks.write(id, at: -1, Data([1])) }
try sinks.beginDeflate(id)
rejects("publication waits for the open stage") { _ = try sinks.commit(id, name: "PowerLog.zip") }
_ = try sinks.endDeflate(id)
let committed = try sinks.commit(id, name: "PowerLog ride.zip")
let url = URL(string: committed["uri"] as! String)!
check(url.isFileURL, "commit returns a file URI")
check(
  url.standardizedFileURL.path == root.appendingPathComponent(id).appendingPathComponent("PowerLog ride.zip").path,
  "commit publishes into the export's own directory")
check(!fm.fileExists(atPath: staging(id).path), "commit moves the part file")
let bytes = try Data(contentsOf: url)
check(bytes.prefix(8) == Data("head1234".utf8), "patches replace stored bytes")
check(bytes.count == total + 2, "every written byte is in the file")
check(
  inflateRaw(bytes.subdata(in: 8..<(8 + firstSize)), capacity: first.count) == first,
  "the stage is raw DEFLATE of the written bytes")
check(
  inflateRaw(bytes.subdata(in: (8 + firstSize)..<(8 + firstSize + emptySize)), capacity: 0) == Data(),
  "the empty stage is raw DEFLATE")
check(bytes.subdata(in: (total - 4)..<total) == Data("tail".utf8), "trailer and its patch survive")
rejects("a published file accepts no writes") { try sinks.write(id, Data([1])) }
sinks.abort(id)
check(fm.fileExists(atPath: url.path), "abort never removes a published file")

// Each export publishes into a directory of its own, also under the same name.
var uris: [URL] = []
for content in ["one", "two"] {
  let sink = try sinks.open()
  try sinks.write(sink, Data(content.utf8))
  uris.append(URL(string: try sinks.commit(sink, name: "PowerLog.fit")["uri"] as! String)!)
}
check(uris[0] != uris[1], "two exports of one name get two URIs")
check(
  try Data(contentsOf: uris[0]) == Data("one".utf8) && Data(contentsOf: uris[1]) == Data("two".utf8),
  "neither export overwrites the other")
for name in ["", ".hidden", "a/b", "..", String(repeating: "x", count: 256)] {
  let sink = try sinks.open()
  rejects("commit rejects the name \(name)") { _ = try sinks.commit(sink, name: name) }
  sinks.abort(sink)
}

// Abort closes and deletes the part file and is idempotent.
let aborted = try sinks.open()
try sinks.write(aborted, Data(repeating: 7, count: 1000))
try sinks.beginDeflate(aborted)
try sinks.write(aborted, Data(repeating: 8, count: 1000))
sinks.abort(aborted)
check(!fm.fileExists(atPath: staging(aborted).path), "abort deletes the part file")
sinks.abort(aborted)
rejects("an aborted sink accepts no writes") { try sinks.write(aborted, Data([1])) }

// Cleanup: part files of no open sink and published exports older than 24 hours.
let now = Date()
let open = try sinks.open()
let orphan = UUID().uuidString.lowercased()
try Data([1]).write(to: staging(orphan))
let old = UUID().uuidString.lowercased()
let fresh = UUID().uuidString.lowercased()
for (name, age) in [(old, 24.5 * 3600), (fresh, 23.5 * 3600)] {
  let directory = root.appendingPathComponent(name, isDirectory: true)
  try fm.createDirectory(at: directory, withIntermediateDirectories: true)
  try Data([2]).write(to: directory.appendingPathComponent("PowerLog.fit"))
  try fm.setAttributes([.modificationDate: now.addingTimeInterval(-age)], ofItemAtPath: directory.path)
}
let foreign = root.appendingPathComponent("notes.txt")
try Data([3]).write(to: foreign)
let other = root.appendingPathComponent("keep", isDirectory: true)
try fm.createDirectory(at: other, withIntermediateDirectories: true)
try fm.setAttributes([.modificationDate: now.addingTimeInterval(-90 * 24 * 3600)], ofItemAtPath: other.path)
let opened = try sinks.open(now: now)
check(!fm.fileExists(atPath: staging(orphan).path), "a new export deletes orphaned part files")
check(fm.fileExists(atPath: staging(open).path), "cleanup keeps the part file of an open sink")
check(!fm.fileExists(atPath: root.appendingPathComponent(old).path), "exports older than 24 hours are deleted")
check(fm.fileExists(atPath: root.appendingPathComponent(fresh).path), "newer exports are kept")
check(fm.fileExists(atPath: foreign.path) && fm.fileExists(atPath: other.path), "cleanup touches only export entries")
check(fm.fileExists(atPath: uris[0].path), "a just-published export survives cleanup")
sinks.abortAll()
check(!fm.fileExists(atPath: staging(open).path) && !fm.fileExists(atPath: staging(opened).path), "teardown aborts")
let restarted = ExportSinks(root: root)
try Data([4]).write(to: staging(orphan))
restarted.cleanUp(now: now.addingTimeInterval(25 * 3600))
check(!fm.fileExists(atPath: staging(orphan).path), "module start deletes part files left by process death")
check(!fm.fileExists(atPath: uris[0].path), "module start deletes published exports once they are 24 hours old")

let fenced = ExportSinks(root: base.appendingPathComponent("Fenced", isDirectory: true))
let queued = try fenced.open()
try fenced.write(queued, Data([1, 2, 3]))
fenced.stop()
do {
  _ = try fenced.commit(queued, name: "PowerLog.fit")
  check(false, "a commit queued before teardown publishes nothing")
} catch let failure as ExportFailure {
  check(failure.code == "cancelled", "a commit queued before teardown is cancelled")
}
check(
  !fm.fileExists(atPath: fenced.root.appendingPathComponent(".powerlog-export-\(queued).part").path)
    && !fm.fileExists(atPath: fenced.root.appendingPathComponent(queued).path),
  "the cancelled commit leaves neither its part file nor a published file")
let after = try fenced.open()
try fenced.write(after, Data([4]))
check(
  fm.fileExists(atPath: URL(string: try fenced.commit(after, name: "PowerLog.fit")["uri"] as! String)!.path),
  "a sink opened after teardown publishes normally")

print("Export sink: \(assertions) assertions passed")
