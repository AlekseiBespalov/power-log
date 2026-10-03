import Compression
import Foundation
import zlib

final class ExportSinks: @unchecked Sendable {
  static let shared = ExportSinks(
    root: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("Exports", isDirectory: true))
  static let stagingPrefix = ".powerlog-export-"
  static let stagingSuffix = ".part"
  static let retention: TimeInterval = 24 * 3600

  let root: URL
  let queue = DispatchQueue(label: "app.powerlog.export.sink", qos: .userInitiated)
  private let lock = NSLock()
  private var sinks: [String: ExportFileSink] = [:]
  private var generations: [String: Int] = [:]
  private var generation = 0

  init(root: URL) { self.root = root }

  func open(now: Date = Date()) throws -> String {
    do {
      try prepare()
      cleanUp(now: now)
      let id = UUID().uuidString.lowercased()
      let sink = try ExportFileSink(id: id, url: staging(id))
      lock.lock()
      sinks[id] = sink
      generations[id] = generation
      lock.unlock()
      return id
    } catch { throw Self.failure(error) }
  }

  func write(_ id: String, _ bytes: Data) throws {
    try run(id) { try $0.write(bytes) }
  }

  func write(_ id: String, at offset: Double, _ bytes: Data) throws {
    guard offset.isFinite, offset >= 0, offset.rounded() == offset, offset <= 9_007_199_254_740_991 else {
      throw ExportFailure(code: "sink", message: "The export patch offset is invalid.")
    }
    try run(id) { try $0.write(bytes, at: Int64(offset)) }
  }

  func beginDeflate(_ id: String) throws {
    try run(id) { try $0.beginDeflate() }
  }

  func endDeflate(_ id: String) throws -> [String: Any] {
    try run(id) { sink in
      let result = try sink.endDeflate()
      return ["crc32": Int(result.crc32), "inputBytes": Int(result.input), "outputBytes": Int(result.output)]
    }
  }

  func commit(_ id: String, name: String) throws -> [String: Any] {
    try run(id) { sink in
      guard !name.isEmpty, name.utf8.count <= 255, !name.hasPrefix("."), !name.contains("/"), !name.contains("\0")
      else { throw ExportFailure(code: "sink", message: "The export file name is invalid.") }
      let directory = root.appendingPathComponent(id, isDirectory: true)
      lock.lock()
      defer { lock.unlock() }
      // A commit queued before teardown must not publish once `stop()` has run.
      guard generations[id] == generation else {
        sinks[id] = nil
        generations[id] = nil
        sink.abort()
        throw ExportFailure(code: "cancelled", message: "This export was cancelled.")
      }
      let url = try sink.commit(to: directory.appendingPathComponent(name, isDirectory: false))
      sinks[id] = nil
      generations[id] = nil
      return ["uri": url.absoluteString]
    }
  }

  func abort(_ id: String) {
    lock.lock()
    let sink = sinks.removeValue(forKey: id)
    generations[id] = nil
    lock.unlock()
    sink?.abort()
  }

  func stop() {
    lock.lock()
    generation += 1
    lock.unlock()
  }

  func abortAll() {
    stop()
    lock.lock()
    let open = Array(sinks.values)
    sinks.removeAll()
    generations.removeAll()
    lock.unlock()
    for sink in open { sink.abort() }
  }

  func cleanUp(now: Date = Date()) {
    let fm = FileManager.default
    let keys: Set<URLResourceKey> = [.isDirectoryKey, .isSymbolicLinkKey, .contentModificationDateKey]
    guard let entries = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: Array(keys)) else { return }
    lock.lock()
    let open = Set(sinks.keys)
    lock.unlock()
    for entry in entries {
      let name = entry.lastPathComponent
      guard let values = try? entry.resourceValues(forKeys: keys) else { continue }
      if name.hasPrefix(Self.stagingPrefix), name.hasSuffix(Self.stagingSuffix) {
        let id = String(name.dropFirst(Self.stagingPrefix.count).dropLast(Self.stagingSuffix.count))
        guard Self.identifier(id), !open.contains(id), values.isDirectory != true else { continue }
        try? fm.removeItem(at: entry)
      } else if Self.identifier(name), values.isDirectory == true, values.isSymbolicLink != true,
        let modified = values.contentModificationDate, now.timeIntervalSince(modified) > Self.retention
      {
        try? fm.removeItem(at: entry)
      }
    }
  }

  private static func identifier(_ value: String) -> Bool { (try? WorkoutCoding.id(value)) == value }

  private func staging(_ id: String) -> URL {
    root.appendingPathComponent(Self.stagingPrefix + id + Self.stagingSuffix, isDirectory: false)
  }

  private func prepare() throws {
    let fm = FileManager.default
    try fm.createDirectory(at: root, withIntermediateDirectories: true)
    var excluded = URLResourceValues()
    excluded.isExcludedFromBackup = true
    var directory = root
    try directory.setResourceValues(excluded)
    #if os(iOS)
      try fm.setAttributes(
        [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: root.path)
    #endif
  }

  private func run<T>(_ id: String, _ body: (ExportFileSink) throws -> T) throws -> T {
    lock.lock()
    let sink = sinks[id]
    lock.unlock()
    guard let sink else { throw ExportFailure(code: "sink", message: "This export file is no longer open.") }
    do { return try body(sink) } catch { throw Self.failure(error) }
  }

  static func failure(_ error: Error) -> ExportFailure {
    if let failure = error as? ExportFailure { return failure }
    return ExportFailure(code: "sink", message: "The export file could not be written: \(error.localizedDescription)")
  }
}

/// Not thread-safe: ExportSinks calls it only from its serial queue.
final class ExportFileSink {
  private static let capacity = 65_536
  let id: String
  private let url: URL
  private var descriptor: Int32
  private var size: Int64 = 0
  private var deflate: Deflate?
  private var committed = false

  private final class Deflate {
    let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
    let output = UnsafeMutablePointer<UInt8>.allocate(capacity: ExportFileSink.capacity)
    let start: Int64
    var crc: uLong = 0
    var input: Int64 = 0
    var produced: Int64 = 0
    var initialized = false
    init(start: Int64) throws {
      self.start = start
      guard compression_stream_init(stream, COMPRESSION_STREAM_ENCODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
        throw ExportFailure(code: "sink", message: "The export compressor could not start.")
      }
      initialized = true
    }
    deinit {
      if initialized { compression_stream_destroy(stream) }
      stream.deallocate()
      output.deallocate()
    }
  }

  init(id: String, url: URL) throws {
    self.id = id
    self.url = url
    descriptor = Darwin.open(url.path, O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0o600)
    guard descriptor >= 0 else { throw Self.posix("create") }
  }
  deinit { if descriptor >= 0 { Darwin.close(descriptor) } }

  func write(_ bytes: Data) throws {
    guard !bytes.isEmpty else { return }
    try bytes.withUnsafeBytes { buffer in
      guard let deflate else {
        try append(buffer.baseAddress!, buffer.count)
        return
      }
      deflate.crc = crc32(deflate.crc, buffer.bindMemory(to: Bytef.self).baseAddress, uInt(buffer.count))
      deflate.stream.pointee.src_ptr = buffer.bindMemory(to: UInt8.self).baseAddress!
      deflate.stream.pointee.src_size = buffer.count
      defer { deflate.stream.pointee.src_size = 0 }
      try pump(deflate, final: false)
      deflate.input += Int64(buffer.count)
    }
  }

  func write(_ bytes: Data, at offset: Int64) throws {
    let end = deflate?.start ?? size
    guard offset <= end, Int64(bytes.count) <= end - offset else {
      throw ExportFailure(code: "sink", message: "An export patch must replace bytes already written.")
    }
    try bytes.withUnsafeBytes { buffer in
      var written = 0
      while written < buffer.count {
        let count = pwrite(
          descriptor, buffer.baseAddress! + written, buffer.count - written, off_t(offset) + off_t(written))
        if count < 0, errno == EINTR { continue }
        guard count > 0 else { throw Self.posix("patch") }
        written += count
      }
    }
  }

  func beginDeflate() throws {
    guard deflate == nil else { throw ExportFailure(code: "sink", message: "A compressed entry is already open.") }
    deflate = try Deflate(start: size)
  }

  func endDeflate() throws -> (crc32: UInt32, input: Int64, output: Int64) {
    guard let deflate else { throw ExportFailure(code: "sink", message: "No compressed entry is open.") }
    deflate.stream.pointee.src_ptr = UnsafePointer(deflate.output)
    deflate.stream.pointee.src_size = 0
    try pump(deflate, final: true)
    self.deflate = nil
    return (UInt32(truncatingIfNeeded: deflate.crc), deflate.input, deflate.produced)
  }

  func commit(to destination: URL) throws -> URL {
    guard deflate == nil else { throw ExportFailure(code: "sink", message: "Finish the compressed entry first.") }
    guard fsync(descriptor) == 0 else { throw Self.posix("flush") }
    Darwin.close(descriptor)
    descriptor = -1
    let directory = destination.deletingLastPathComponent()
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
    guard rename(url.path, destination.path) == 0 else {
      let failure = Self.posix("publish")
      try? FileManager.default.removeItem(at: directory)
      throw failure
    }
    committed = true
    return destination
  }

  func abort() {
    guard !committed else { return }
    deflate = nil
    if descriptor >= 0 {
      Darwin.close(descriptor)
      descriptor = -1
    }
    unlink(url.path)
  }

  private func append(_ pointer: UnsafeRawPointer, _ count: Int) throws {
    var written = 0
    while written < count {
      let result = pwrite(descriptor, pointer + written, count - written, off_t(size))
      if result < 0, errno == EINTR { continue }
      guard result > 0 else { throw Self.posix("write") }
      written += result
      size += Int64(result)
    }
  }

  private func pump(_ deflate: Deflate, final: Bool) throws {
    let flags = final ? Int32(COMPRESSION_STREAM_FINALIZE.rawValue) : 0
    while true {
      deflate.stream.pointee.dst_ptr = deflate.output
      deflate.stream.pointee.dst_size = Self.capacity
      let status = compression_stream_process(deflate.stream, flags)
      guard status != COMPRESSION_STATUS_ERROR else {
        throw ExportFailure(code: "sink", message: "The export could not be compressed.")
      }
      let count = Self.capacity - deflate.stream.pointee.dst_size
      if count > 0 {
        try append(deflate.output, count)
        deflate.produced += Int64(count)
      }
      if status == COMPRESSION_STATUS_END { return }
      if !final && deflate.stream.pointee.src_size == 0 && deflate.stream.pointee.dst_size > 0 { return }
    }
  }

  private static func posix(_ operation: String) -> ExportFailure {
    ExportFailure(
      code: "sink", message: "The export file could not \(operation): \(String(cString: strerror(errno))).")
  }
}
