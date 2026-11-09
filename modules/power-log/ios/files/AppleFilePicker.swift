import Foundation
import PowerLogCore

protocol DocumentPicking: AnyObject {
  func pickCsv(_ picked: @escaping (URL?) -> Void) -> Bool
}

final class AttachedDocuments: DocumentPicking {
  static let shared = AttachedDocuments()
  private let lock = NSLock()
  private var documents: DocumentPicking?

  func attach(_ documents: DocumentPicking) {
    lock.lock()
    self.documents = documents
    lock.unlock()
  }

  func detach(_ documents: DocumentPicking) {
    lock.lock()
    if self.documents === documents { self.documents = nil }
    lock.unlock()
  }

  func pickCsv(_ picked: @escaping (URL?) -> Void) -> Bool {
    lock.lock()
    let documents = self.documents
    lock.unlock()
    return documents?.pickCsv(picked) ?? false
  }
}

final class AppleFilePicker: NSObject, ApplicationFilePicker {
  private let documents: DocumentPicking
  private let queue: DispatchQueue
  private let main: (@escaping () -> Void) -> Void

  init(
    documents: DocumentPicking, queue: DispatchQueue,
    main: @escaping (@escaping () -> Void) -> Void = { DispatchQueue.main.async(execute: $0) }
  ) {
    self.documents = documents
    self.queue = queue
    self.main = main
  }

  func pickCsv(reply: ApplicationReply<ApplicationPickedFile>) {
    main { [self] in
      let shown = documents.pickCsv { [self] url in
        guard let url else { return failed(reply, .cancelled) }
        queue.async { [self] in open(url, reply) }
      }
      if !shown { failed(reply, .uiunavailable) }
    }
  }

  private func open(_ url: URL, _ reply: ApplicationReply<ApplicationPickedFile>) {
    guard let handle = try? FileHandle(forReadingFrom: url) else {
      try? FileManager.default.removeItem(at: url)
      return reply.failed(error: ApplicationPortError(kind: .unreadable, platform: nil))
    }
    let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? -1
    reply.ok(value: PickedCsv(url: url, size: Int64(size), handle: handle, queue: queue))
  }

  private func failed(_ reply: ApplicationReply<ApplicationPickedFile>, _ kind: ApplicationPortErrorKind) {
    queue.async { reply.failed(error: ApplicationPortError(kind: kind, platform: nil)) }
  }
}

private final class PickedCsv: NSObject, ApplicationPickedFile {
  let name: String
  let sizeBytes: Int64
  private let url: URL
  private let handle: FileHandle
  private let queue: DispatchQueue
  private var closed = false

  init(url: URL, size: Int64, handle: FileHandle, queue: DispatchQueue) {
    name = url.lastPathComponent
    sizeBytes = size
    self.url = url
    self.handle = handle
    self.queue = queue
  }

  func read(maxBytes: Int32, reply: ApplicationReply<KotlinByteArray>) {
    queue.async { [self] in
      do {
        guard !closed else { throw CocoaError(.fileReadUnknown) }
        let data = try handle.read(upToCount: Int(maxBytes)) ?? Data()
        reply.ok(value: data.isEmpty ? nil : AppleBytes.shared.bytes(data: data))
      } catch {
        reply.failed(error: ApplicationPortError(kind: .unreadable, platform: nil))
      }
    }
  }

  func close() {
    queue.async { [self] in
      guard !closed else { return }
      closed = true
      try? handle.close()
      try? FileManager.default.removeItem(at: url)
    }
  }
}
