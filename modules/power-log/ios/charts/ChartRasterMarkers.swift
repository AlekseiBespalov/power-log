import Foundation

struct ChartRasterSelectionTarget: Decodable {
  struct Point: Decodable {
    enum CodingKeys: String, CodingKey {
      case key, id, value
      case seconds = "atS"
    }
    let key: String
    let id: String
    let seconds: Double
    let value: Double
  }
  let epoch: Int64
  let sequence: Int64
  let points: [Point]

  static func parse(_ json: String) -> Self? {
    guard json.utf8.count <= 32_768, let data = json.data(using: .utf8),
      let target = try? JSONDecoder().decode(Self.self, from: data),
      (0...ChartRasterPresentation.maximumTag).contains(target.epoch),
      (0...ChartRasterPresentation.maximumTag).contains(target.sequence),
      target.points.count <= ChartRasterScene.maximumSeries,
      Set(target.points.map(\.id)).count == target.points.count
    else { return nil }
    for point in target.points {
      guard !point.key.isEmpty, point.key.utf8.count <= 512, !point.id.isEmpty, point.id.utf8.count <= 128,
        point.seconds.isFinite, abs(point.seconds) <= 1e12, point.value.isFinite, abs(point.value) <= 1e15
      else { return nil }
    }
    return target
  }
}

/// Markers belong to one cursor position. UI originals move every lane immediately;
/// a matching completed lookup replaces them, including explicit gaps.
struct ChartRasterMarkerState {
  private var sourceID = ""
  private var revision = ""
  private var epoch: Int64 = -1
  private var presentationSequence: Int64 = -1
  private var clearSequence: Int64 = -1
  private var active = false
  private var selectionSequence: Int64 = -1
  private var selectionPending = true
  private var originals: [String: ChartRasterSelection.Point] = [:]
  private var targetSequence: Int64 = -1
  private var targets: [ChartRasterSelectionTarget.Point] = []

  mutating func synchronize(sourceID: String, revision: String, presentation: ChartRasterPresentation?) {
    guard let presentation else { return }
    if sourceID == self.sourceID && presentation.epoch < epoch { return }
    if sourceID != self.sourceID || revision != self.revision || presentation.epoch != epoch {
      self = ChartRasterMarkerState()
      self.sourceID = sourceID
      self.revision = revision
      epoch = presentation.epoch
    }
    guard presentation.sequence >= presentationSequence else { return }
    presentationSequence = presentation.sequence
    active = presentation.cursor != nil
    if !active {
      clearSequence = max(clearSequence, presentation.sequence)
      originals.removeAll()
      targets.removeAll()
      selectionSequence = max(selectionSequence, clearSequence)
      targetSequence = max(targetSequence, clearSequence)
    }
  }

  private func eligible(epoch: Int64, sequence: Int64) -> Bool {
    active && sequence > clearSequence && epoch == self.epoch && sequence == presentationSequence
  }

  func accepts(selection: ChartRasterSelection) -> Bool {
    selection.sourceId == sourceID && selection.revision == revision && selection.epoch == epoch
      && selection.sequence == presentationSequence
  }

  mutating func receive(selection: ChartRasterSelection?, metrics: Set<String>) {
    guard let selection, accepts(selection: selection),
      eligible(epoch: selection.epoch, sequence: selection.sequence),
      selection.sequence >= selectionSequence
    else { return }
    if selection.sequence == selectionSequence && !selectionPending && selection.cursorPending { return }
    selectionSequence = selection.sequence
    selectionPending = selection.cursorPending
    let points = selection.points.filter { metrics.contains($0.id) }
    originals.removeAll()
    for point in points { originals[point.id] = point }
  }

  mutating func receive(target: ChartRasterSelectionTarget?) {
    guard let target, eligible(epoch: target.epoch, sequence: target.sequence), target.sequence >= targetSequence else {
      return
    }
    targetSequence = target.sequence
    targets = target.points
  }

  var points: [ChartRasterSelection.Point] {
    active && selectionSequence == presentationSequence ? Array(originals.values) : []
  }

  func previews(acceptanceID: String, vertices: ChartRasterVertexIndex) -> [ChartRasterSelection.Point] {
    guard active, targetSequence == presentationSequence,
      selectionSequence != presentationSequence || selectionPending
    else { return [] }
    return targets.compactMap { target in
      guard target.key == acceptanceID,
        vertices.contains(metric: target.id, seconds: target.seconds, value: target.value)
      else { return nil }
      return ChartRasterSelection.Point(id: target.id, seconds: target.seconds, value: target.value)
    }
  }
}
