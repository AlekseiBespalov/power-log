import Foundation
import CoreGraphics

final class ChartRasterBitmapLease {
  private let release: () -> Void
  init(release: @escaping () -> Void) { self.release = release }
  deinit { release() }
}

struct ChartRasterResult {
  let scene: ChartRasterScene
  let dimensions: ChartRasterDimensions
  let image: CGImage
  let bytes: Int
  let vertices: ChartRasterVertexIndex
  // The reservation lives as long as either a queued completion or the accepted image.
  let lease: ChartRasterBitmapLease
}

final class ChartRasterWorker {
  typealias Renderer = (ChartRasterScene, ChartRasterDimensions, () -> Bool) throws -> CGImage
  static let shared = ChartRasterWorker()
  private struct Request {
    let owner: UUID
    let token: UUID
    let json: String
    let sourceID: String
    let requestKey: String?
    let dimensions: ChartRasterDimensions
    let completion: (Result<ChartRasterResult, Error>) -> Void
  }
  private let lock = NSLock()
  private let queue = DispatchQueue(label: "powerlog.monitor.graphics", qos: .userInitiated)
  private let completionQueue: DispatchQueue
  private let renderer: Renderer
  private var current: [UUID: UUID] = [:]
  private var pending: [UUID: Request] = [:]
  private var order: [UUID] = []
  private var running = false
  private var submitted = 0
  private var started = 0
  private var completed = 0
  private var discarded = 0
  private var failures = 0
  private var bitmapBytes = 0
  private var scratchBytes = 0
  private var peakBytes = 0

  init(
    completionQueue: DispatchQueue = .main,
    renderer: @escaping Renderer = { try ChartRasterRenderer.draw(scene: $0, dimensions: $1, cancelled: $2) }
  ) {
    self.completionQueue = completionQueue
    self.renderer = renderer
  }

  func submit(
    owner: UUID, json: String, sourceID: String, dimensions: ChartRasterDimensions,
    requestKey: String? = nil,
    completion: @escaping (Result<ChartRasterResult, Error>) -> Void
  ) {
    guard json.utf8.count <= ChartRasterScene.maximumJSONBytes else {
      cancel(owner: owner)
      completionQueue.async { completion(.failure(ChartRasterError.invalid("Chart packet exceeds size limit"))) }
      return
    }
    lock.lock()
    guard current[owner] != nil || current.count < 128 else {
      lock.unlock()
      completionQueue.async { completion(.failure(ChartRasterError.invalid("Chart lane limit exceeded"))) }
      return
    }
    let token = UUID()
    current[owner] = token
    submitted += 1
    if pending[owner] != nil { discarded += 1 } else { order.append(owner) }
    pending[owner] = Request(
      owner: owner, token: token, json: json, sourceID: sourceID,
      requestKey: requestKey, dimensions: dimensions,
      completion: completion)
    let shouldStart = !running
    running = true
    lock.unlock()
    if shouldStart { queue.async { self.drain() } }
  }

  func cancel(owner: UUID) {
    lock.lock()
    defer { lock.unlock() }
    current.removeValue(forKey: owner)
    if pending.removeValue(forKey: owner) != nil { discarded += 1 }
    order.removeAll { $0 == owner }
  }

  func diagnostics(reset: Bool = false) -> [String: Int] {
    lock.lock()
    defer { lock.unlock() }
    if reset {
      submitted = 0
      started = 0
      completed = 0
      discarded = 0
      failures = 0
      peakBytes = bitmapBytes + scratchBytes
    }
    return [
      "submitted": submitted, "started": started, "completed": completed, "discarded": discarded,
      "errors": failures, "pending": pending.count, "running": running ? 1 : 0,
      "bitmapBytes": bitmapBytes, "scratchBytes": scratchBytes, "peakBytes": peakBytes,
      "mountedLanes": current.count,
    ]
  }

  private func isCurrent(_ request: Request) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    return current[request.owner] == request.token
  }

  private func next() -> Request? {
    lock.lock()
    defer { lock.unlock() }
    guard !order.isEmpty else {
      running = false
      return nil
    }
    return pending.removeValue(forKey: order.removeFirst())
  }

  private func drain() {
    while let request = next() {
      autoreleasepool {
        let result: Result<ChartRasterResult, Error>
        do { result = .success(try render(request)) } catch {
          if case ChartRasterError.cancelled = error {
          } else {
            lock.lock()
            failures += 1
            lock.unlock()
          }
          result = .failure(error)
        }
        completionQueue.async {
          guard self.isCurrent(request) else {
            self.lock.lock()
            self.discarded += 1
            self.lock.unlock()
            return
          }
          request.completion(result)
        }
      }
    }
  }

  private func render(_ request: Request) throws -> ChartRasterResult {
    guard isCurrent(request) else { throw ChartRasterError.cancelled }
    let scene = try ChartRasterScene.parse(request.json, sourceID: request.sourceID)
    guard request.requestKey == nil || request.requestKey == scene.key else {
      throw ChartRasterError.invalid("Chart packet does not match its request key")
    }
    guard isCurrent(request) else { throw ChartRasterError.cancelled }
    let pixels = try request.dimensions.pixels(laneCount: scene.laneCount)
    let vertices = ChartRasterVertexIndex(scene: scene)
    guard isCurrent(request) else { throw ChartRasterError.cancelled }
    let lease = try reserve(bytes: pixels.bytes)
    defer {
      lock.lock()
      scratchBytes = 0
      lock.unlock()
    }
    lock.lock()
    started += 1
    lock.unlock()
    let image = try renderer(scene, request.dimensions, { !self.isCurrent(request) })
    lock.lock()
    completed += 1
    lock.unlock()
    return ChartRasterResult(
      scene: scene, dimensions: request.dimensions,
      image: image, bytes: pixels.bytes, vertices: vertices, lease: lease)
  }

  private func reserve(bytes: Int) throws -> ChartRasterBitmapLease {
    lock.lock()
    defer { lock.unlock() }
    // Two collection budgets cover accepted plus replacements; the one worker adds one scratch context.
    guard bitmapBytes + bytes <= 2 * ChartRasterDimensions.collectionBytes,
      bytes <= ChartRasterDimensions.maximumBitmapBytes
    else {
      throw ChartRasterError.invalid("Chart collection bitmap budget exceeded")
    }
    bitmapBytes += bytes
    scratchBytes = bytes
    peakBytes = max(peakBytes, bitmapBytes + scratchBytes)
    return ChartRasterBitmapLease { [weak self] in
      guard let self else { return }
      self.lock.lock()
      self.bitmapBytes -= bytes
      self.lock.unlock()
    }
  }
}
