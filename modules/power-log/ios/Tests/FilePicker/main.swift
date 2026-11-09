import Foundation
import PowerLogCore

var assertions = 0
func check(_ condition: Bool, _ message: String) {
  assertions += 1
  if !condition { fatalError(message) }
}

final class FakeDocuments: DocumentPicking {
  var answer: URL?
  var shown = true

  func pickCsv(_ picked: @escaping (URL?) -> Void) -> Bool {
    guard shown else { return false }
    picked(answer)
    return true
  }
}

let queue = DispatchQueue(label: "file-picker-test")
let documents = FakeDocuments()
let picker = AppleFilePicker(documents: documents, queue: queue, main: { $0() })

func pick() -> (file: ApplicationPickedFile?, failure: ApplicationPortErrorKind?) {
  var file: ApplicationPickedFile?
  var failure: ApplicationPortErrorKind?
  picker.pickCsv(reply: ApplicationReply(onOk: { file = $0 }, onFailed: { failure = $0.kind }))
  queue.sync {}
  return (file, failure)
}

func read(_ file: ApplicationPickedFile, _ maxBytes: Int32) -> (chunk: Data?, failure: ApplicationPortErrorKind?) {
  var chunk: Data?
  var failure: ApplicationPortErrorKind?
  file.read(
    maxBytes: maxBytes,
    reply: ApplicationReply(
      onOk: { bytes in chunk = bytes.map { AppleBytes.shared.data(bytes: $0) } }, onFailed: { failure = $0.kind }))
  queue.sync {}
  return (chunk, failure)
}

let base = FileManager.default.temporaryDirectory.appendingPathComponent("powerlog-file-picker-" + UUID().uuidString)
try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: base) }
let csv = "elapsedSeconds,humanPowerW\n" + (1...500).map { "\($0),\($0 % 300)\n" }.joined()
let copy = base.appendingPathComponent("ride.csv")
try Data(csv.utf8).write(to: copy)

documents.answer = copy
let picked = pick()
check(picked.failure == nil, "a picked CSV opens")
let file = picked.file!
check(file.name == "ride.csv", "the picked file keeps its name")
check(file.sizeBytes == Int64(csv.utf8.count), "the picked file reports its size")
var bytes = Data()
while true {
  let next = read(file, 1000)
  check(next.failure == nil, "a chunk reads")
  guard let chunk = next.chunk else { break }
  check(chunk.count <= 1000, "a chunk holds at most maxBytes")
  bytes.append(chunk)
}
check(bytes == Data(csv.utf8), "the chunks hold the whole file")
file.close()
queue.sync {}
check(!FileManager.default.fileExists(atPath: copy.path), "close deletes the picker's copy")
check(read(file, 10).failure == .unreadable, "a closed file reads nothing")

documents.answer = nil
check(pick().failure == .cancelled, "a cancelled pick is cancelled")
documents.answer = base.appendingPathComponent("missing.csv")
check(pick().failure == .unreadable, "a file that cannot be opened is unreadable")
documents.shown = false
check(pick().failure == .uiunavailable, "without a screen the pick is uiUnavailable")
let detached = AppleFilePicker(documents: AttachedDocuments.shared, queue: queue, main: { $0() })
var failure: ApplicationPortErrorKind?
detached.pickCsv(reply: ApplicationReply(onOk: { _ in }, onFailed: { failure = $0.kind }))
queue.sync {}
check(failure == .uiunavailable, "with no module attached the pick is uiUnavailable")

print("File picker: \(assertions) assertions passed")
