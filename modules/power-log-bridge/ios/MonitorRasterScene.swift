import Foundation
import CoreGraphics
import QuartzCore
import PowerLogCore

enum MonitorRasterError: Error, LocalizedError {
  case invalid(String)
  case cancelled
  var errorDescription: String? {
    switch self {
    case .invalid(let message): return message
    case .cancelled: return "Obsolete chart render"
    }
  }
}

struct MonitorRasterPoint: Decodable {
  let seconds: Double
  let value: Double
  let startsSegment: Bool
  init(from decoder: Decoder) throws {
    var values = try decoder.unkeyedContainer()
    seconds = try values.decode(Double.self)
    value = try values.decode(Double.self)
    startsSegment = try values.decode(Bool.self)
    guard values.isAtEnd else { throw MonitorRasterError.invalid("Invalid chart point") }
  }
}

struct MonitorRasterSeries: Decodable {
  let id: String
  let color: String
  let step: Bool
  let points: [MonitorRasterPoint]
}

struct MonitorRasterScene: Decodable {
  static let maximumJSONBytes = 2 * 1_024 * 1_024
  static let maximumPoints = 16_384
  static let maximumSeries = 32
  let key: String
  let sourceId: String
  let queryContext: MonitorRasterQueryContext
  let start: Double
  let end: Double
  let min: Double
  let max: Double
  let decimals: Int
  let laneCount: Int
  let series: [MonitorRasterSeries]

  static func parse(_ json: String, sourceID: String) throws -> MonitorRasterScene {
    guard json.utf8.count <= maximumJSONBytes, let data = json.data(using: .utf8) else {
      throw MonitorRasterError.invalid("Chart packet exceeds size limit")
    }
    let scene = try JSONDecoder().decode(Self.self, from: data)
    guard scene.sourceId == sourceID, !scene.sourceId.isEmpty, scene.sourceId.utf8.count <= 512,
      !scene.key.isEmpty, scene.key.utf8.count <= 512,
      scene.start.isFinite, scene.end.isFinite, abs(scene.start) <= 1e12, abs(scene.end) <= 1e12,
      scene.end - scene.start >= 1e-6, scene.min.isFinite, scene.max.isFinite,
      abs(scene.min) <= 1e15, abs(scene.max) <= 1e15, scene.max > scene.min,
      (0...8).contains(scene.decimals), (1...128).contains(scene.laneCount),
      scene.series.count <= maximumSeries
    else { throw MonitorRasterError.invalid("Invalid chart scene") }
    guard MonitorRasterQueryContext.valid(scene.queryContext) else {
      throw MonitorRasterError.invalid("Invalid chart context")
    }
    var count = 0
    var ids = Set<String>()
    for series in scene.series {
      count += series.points.count
      guard count <= maximumPoints, !series.id.isEmpty, series.id.utf8.count <= 128,
        ids.insert(series.id).inserted, color(series.color) != nil
      else {
        throw MonitorRasterError.invalid("Invalid chart series or point limit")
      }
      var previous = Int64.min
      for point in series.points {
        guard point.seconds.isFinite, point.value.isFinite,
          abs(point.seconds) <= 1e12, abs(point.value) <= 1e15, WorkoutDistancePolicy.timeUs(point.seconds) >= previous,
          abs((point.seconds - scene.start) / (scene.end - scene.start)) <= 1e9,
          abs((point.value - scene.min) / (scene.max - scene.min)) <= 1e9
        else {
          throw MonitorRasterError.invalid("Invalid or unsorted chart point")
        }
        previous = WorkoutDistancePolicy.timeUs(point.seconds)
      }
    }
    return scene
  }

  static func color(_ hex: String) -> CGColor? {
    guard hex.count == 7, hex.first == "#", let rgb = UInt32(hex.dropFirst(), radix: 16) else { return nil }
    return CGColor(
      colorSpace: CGColorSpaceCreateDeviceRGB(),
      components: [
        CGFloat((rgb >> 16) & 255) / 255, CGFloat((rgb >> 8) & 255) / 255, CGFloat(rgb & 255) / 255, 1,
      ])
  }
}

struct MonitorRasterDimensions: Equatable {
  static let collectionBytes = 32 * 1_024 * 1_024
  static let maximumBitmapBytes = 3 * 1_024 * 1_024
  let width: Double
  let height: Double
  let scale: Double
  static let leftInset: Double = 8
  static let rightInset: Double = 8
  var plotWidth: Double { width - Self.leftInset - Self.rightInset }
  var plotHeight: Double { height - 24 }

  func pixels(laneCount: Int) throws -> (width: Int, height: Int, bytes: Int) {
    guard width.isFinite, height.isFinite, scale.isFinite, plotWidth > 0, height > 34,
      width <= 16_384, height <= 16_384, scale > 0, scale <= 8,
      (1...128).contains(laneCount)
    else { throw MonitorRasterError.invalid("Invalid chart dimensions") }
    let budget = Swift.min(Self.maximumBitmapBytes, Self.collectionBytes / laneCount)
    let density = Swift.min(scale, sqrt(Double(budget / 4) / (plotWidth * plotHeight)))
    let pixelWidth = Swift.max(1, Int(floor(plotWidth * density)))
    let pixelHeight = Swift.max(1, Int(floor(plotHeight * density)))
    let bytes = pixelWidth * pixelHeight * 4
    guard bytes <= budget else { throw MonitorRasterError.invalid("Chart aspect ratio exceeds bitmap budget") }
    return (pixelWidth, pixelHeight, bytes)
  }
}

struct MonitorRasterAxisLabel {
  let frame: CGRect
  let textFrame: CGRect
}

struct MonitorRasterAxisLayer {
  let backing: CALayer
  let text: CATextLayer
}

enum MonitorRasterAxes {
  static let fontSize: Double = 10
  static let textHeight: Double = 14
  static let inset: Double = 4
  static let horizontalPadding: Double = 4
  static let verticalPadding: Double = 2
  static let gridOffset: Double = 2
  static let background = "#0b0d10"

  static func layer() -> MonitorRasterAxisLayer {
    let backing = CALayer()
    backing.backgroundColor = MonitorRasterScene.color(background)
    let text = CATextLayer()
    text.font = "Arial" as CFTypeRef
    text.fontSize = CGFloat(fontSize)
    text.foregroundColor = MonitorRasterScene.color("#9ba6b6")
    text.alignmentMode = .left
    text.truncationMode = .end
    backing.addSublayer(text)
    return MonitorRasterAxisLayer(backing: backing, text: text)
  }

  static func yLabel(ordinate: Double, textWidth: Double, dimensions: MonitorRasterDimensions) -> MonitorRasterAxisLabel
  {
    let size = labelSize(textWidth: textWidth, dimensions: dimensions)
    let top = 8 + inset
    let bottom = Swift.max(top, dimensions.plotHeight - inset - size.height)
    return label(
      x: MonitorRasterDimensions.leftInset + inset,
      y: Swift.min(bottom, Swift.max(top, ordinate + gridOffset)), size: size)
  }

  static func xLabel(fraction: Double, textWidth: Double, dimensions: MonitorRasterDimensions) -> MonitorRasterAxisLabel
  {
    let size = labelSize(textWidth: textWidth, dimensions: dimensions)
    let left = MonitorRasterDimensions.leftInset + inset
    let right = dimensions.width - MonitorRasterDimensions.rightInset - inset - size.width
    let x = MonitorRasterDimensions.leftInset + dimensions.plotWidth * fraction - size.width / 2
    return label(x: Swift.max(left, Swift.min(right, x)), y: dimensions.height - inset - size.height, size: size)
  }

  static func hasLabels(_ scene: MonitorRasterScene) -> Bool { scene.series.contains { !$0.points.isEmpty } }

  private static func labelSize(textWidth: Double, dimensions: MonitorRasterDimensions) -> CGSize {
    CGSize(
      width: Swift.min(ceil(textWidth) + horizontalPadding * 2, Swift.max(0, dimensions.plotWidth - inset * 2)),
      height: textHeight + verticalPadding * 2)
  }

  private static func label(x: Double, y: Double, size: CGSize) -> MonitorRasterAxisLabel {
    MonitorRasterAxisLabel(
      frame: CGRect(x: x, y: y, width: size.width, height: size.height),
      textFrame: CGRect(
        x: horizontalPadding, y: verticalPadding, width: Swift.max(0, size.width - horizontalPadding * 2),
        height: textHeight))
  }
}

struct MonitorRasterPresentation: Equatable {
  static let maximumTag: Int64 = 9_007_199_254_740_991
  let start: Double
  let end: Double
  let cursor: Double?
  let reference: Double?
  let epoch: Int64
  let sequence: Int64

  init?(_ values: [Double]) {
    guard (values.count == 6 || values.count == 8), values.allSatisfy({ $0.isFinite }),
      abs(values[0]) <= 1e12, abs(values[1]) <= 1e12, values[1] - values[0] >= 1e-6,
      (values[2] == 0 || values[2] == 1), (values[4] == 0 || values[4] == 1)
    else { return nil }
    let tags = values.count == 8 ? Array(values[6...7]) : [0, 0]
    guard tags.allSatisfy({ $0 >= 0 && $0 <= Double(Self.maximumTag) && $0.rounded(.towardZero) == $0 }) else {
      return nil
    }
    start = values[0]
    end = values[1]
    cursor = values[2] == 1 ? values[3] : nil
    reference = values[4] == 1 ? values[5] : nil
    epoch = Int64(tags[0])
    sequence = Int64(tags[1])
  }
  func contains(_ seconds: Double) -> Bool { seconds >= start && seconds <= end }
  func x(_ seconds: Double, width: Double) -> Double { (seconds - start) / (end - start) * width }
}

struct MonitorRasterSelection: Decodable {
  struct Point: Decodable {
    let id: String
    let seconds: Double
    let value: Double
  }
  struct TailCandidate: Decodable {
    let id: String
    let observationId: String
    let evidence: String
    let start: Double
    let value: Double
    let acquired: Double?
    let now: Double
    let elapsed: Double
  }
  struct Tail: Decodable {
    let id: String
    let start: Double
    let end: Double
    let value: Double
  }
  let sourceId: String
  let cursorSeconds: Double?
  let referenceSeconds: Double?
  let points: [Point]
  let references: [Point]
  let tailCandidates: [TailCandidate]
  let revision: String
  let distanceSelection: String
  let generation: Int64
  var tails: [Tail] { tailCandidates.compactMap { try? $0.render() } }
  let cursorPending: Bool?
  let epoch: Int64?
  let sequence: Int64?
  var isPending: Bool { cursorPending ?? false }
  var interactionEpoch: Int64 { epoch ?? 0 }
  var interactionSequence: Int64 { sequence ?? 0 }

  static func parse(_ json: String) -> Self? {
    guard json.utf8.count <= 32_768, let data = json.data(using: .utf8),
      let result = try? JSONDecoder().decode(Self.self, from: data),
      result.sourceId.utf8.count <= 512, result.points.count <= 32,
      result.references.count <= 32, result.tailCandidates.count <= 32,
      (0...MonitorRasterPresentation.maximumTag).contains(result.interactionEpoch),
      (0...MonitorRasterPresentation.maximumTag).contains(result.interactionSequence),
      (0...MonitorRasterPresentation.maximumTag).contains(result.generation),
      result.cursorSeconds?.isFinite != false, result.referenceSeconds?.isFinite != false,
      (result.points + result.references).allSatisfy({
        $0.id.utf8.count <= 128 && $0.seconds.isFinite && $0.value.isFinite
      }),
      (try? InspectionContext(
        sourceId: result.sourceId, revision: result.revision, distanceSelection: result.distanceSelection,
        interactionEpoch: result.interactionEpoch, interactionSequence: result.interactionSequence)) != nil,
      result.tailCandidates.allSatisfy({
        !$0.observationId.isEmpty && $0.observationId.utf8.count <= 512 && $0.evidence.utf8.count <= 512
          && $0.start.isFinite && $0.value.isFinite && $0.now.isFinite && $0.elapsed.isFinite
          && $0.acquired?.isFinite != false
      }),
      Set(result.points.map(\.id)).count == result.points.count
    else { return nil }
    return result
  }
}

struct MonitorRasterQueryContext: Decodable, Equatable {
  let revision: String
  let distanceSelection: String
  let generation: Int64
  func ticket(sourceID: String, epoch: Int64, sequence: Int64) throws -> InspectionTicket {
    InspectionTicket(
      context: try InspectionContext(
        sourceId: sourceID, revision: revision,
        distanceSelection: distanceSelection, interactionEpoch: epoch, interactionSequence: sequence),
      generation: generation)
  }
  static func valid(_ value: Self) -> Bool {
    !value.revision.isEmpty && value.revision.utf8.count <= 512
      && (0...MonitorRasterPresentation.maximumTag).contains(value.generation)
      && DistancePolicy.shared.validSelection(selection: value.distanceSelection)
  }
  static func fromSceneKey(_ key: String) -> Self? {
    guard key.utf8.count <= 512, let data = key.data(using: .utf8),
      let values = try? JSONSerialization.jsonObject(with: data) as? [Any], values.count == 5,
      let context = values[4] as? [String: Any],
      let encoded = try? JSONSerialization.data(withJSONObject: context),
      let value = try? JSONDecoder().decode(Self.self, from: encoded), valid(value)
    else { return nil }
    return value
  }

}

/// Expo may deliver these independently in any order. Keep their values until layout can render
/// a coherent scene; a source setter only clears overlays that belong to a different source.
struct MonitorRasterProps {
  private(set) var sourceID = ""
  private(set) var sceneKey = ""
  private(set) var queryContext: MonitorRasterQueryContext?
  private(set) var sceneJSON = ""
  private(set) var hasScene = false
  private(set) var presentation: MonitorRasterPresentation?
  private(set) var selection: MonitorRasterSelection?
  private(set) var selectionTarget: MonitorRasterSelectionTarget?

  mutating func setSourceID(_ value: String) -> Bool {
    guard value != sourceID else { return false }
    sourceID = value
    if selection?.sourceId != value { selection = nil }
    return true
  }

  mutating func setScene(_ value: String) -> Bool {
    guard !hasScene || value != sceneJSON else { return false }
    hasScene = true
    sceneJSON = value.utf8.count <= MonitorRasterScene.maximumJSONBytes ? value : ""
    return true
  }

  mutating func setSceneKey(_ value: String) -> Bool {
    guard value != sceneKey else { return false }
    sceneKey = value.utf8.count <= 512 ? value : ""
    queryContext = MonitorRasterQueryContext.fromSceneKey(sceneKey)
    return true
  }

  mutating func setPresentation(_ values: [Double]) -> Bool {
    guard let next = MonitorRasterPresentation(values), next != presentation else { return false }
    if let old = presentation, next.epoch < old.epoch || (next.epoch == old.epoch && next.sequence < old.sequence) {
      return false
    }
    presentation = next
    return true
  }

  mutating func setSelection(_ value: String) {
    guard let next = MonitorRasterSelection.parse(value) else { return }
    if let old = selection,
      next.interactionEpoch < old.interactionEpoch
        || (next.interactionEpoch == old.interactionEpoch && next.interactionSequence < old.interactionSequence)
    {
      return
    }
    selection = next
  }

  mutating func setSelectionTarget(_ value: String) {
    guard let next = MonitorRasterSelectionTarget.parse(value) else { return }
    if let old = selectionTarget, next.epoch < old.epoch || (next.epoch == old.epoch && next.sequence < old.sequence) {
      return
    }
    selectionTarget = next
  }
}

struct MonitorRasterVertex: Hashable {
  let seconds: Double
  let value: Double
}

struct MonitorRasterVertexIndex {
  static var buildObserverForTesting: (() -> Void)?
  private let metrics: [String: Set<MonitorRasterVertex>]
  init(scene: MonitorRasterScene) {
    Self.buildObserverForTesting?()
    metrics = Dictionary(
      uniqueKeysWithValues: scene.series.map { series in
        let covered = series.points.filter { $0.seconds >= scene.start && $0.seconds <= scene.end }
        return (series.id, Set(covered.map { MonitorRasterVertex(seconds: $0.seconds, value: $0.value) }))
      })
  }
  func contains(metric: String, seconds: Double, value: Double) -> Bool {
    metrics[metric]?.contains(MonitorRasterVertex(seconds: seconds, value: value)) == true
  }
}

/// Bind both success and failure to the immutable request that produced them. The separate scalar
/// key remains available even when scene JSON is malformed and cannot be parsed on the worker.
struct MonitorRasterRenderAttempt {
  let key: String
  let sourceID: String
  let generation: Int
  let dimensions: MonitorRasterDimensions

  func acceptanceID(viewID: String) -> String { "\(viewID):\(generation)" }

  func status(
    viewID: String, error: Error? = nil
  ) -> [String: Any] {
    var result: [String: Any] = [
      "key": key, "sourceId": sourceID, "viewId": viewID,
      "requestGeneration": generation, "status": error == nil ? "ready" : "error",
      "width": dimensions.width, "height": dimensions.height, "displayScale": dimensions.scale,
    ]
    if let error {
      result["message"] = error.localizedDescription
    } else {
      result["acceptanceId"] = acceptanceID(viewID: viewID)
      result["acceptanceGeneration"] = generation
    }
    return result
  }
}
