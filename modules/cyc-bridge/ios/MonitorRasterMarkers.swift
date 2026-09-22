import Foundation

struct MonitorRasterSelectionTarget: Decodable {
  struct Point: Decodable { let key: String; let id: String; let seconds: Double; let value: Double }
  let epoch: Int64
  let sequence: Int64
  let point: Point?

  private enum CodingKeys: String, CodingKey { case epoch, sequence, point }
  init(from decoder: Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    guard container.contains(.point) else { throw MonitorRasterError.invalid("Missing marker target") }
    epoch = try container.decode(Int64.self, forKey: .epoch)
    sequence = try container.decode(Int64.self, forKey: .sequence)
    point = try container.decodeIfPresent(Point.self, forKey: .point)
  }

  static func parse(_ json: String) -> Self? {
    guard json.utf8.count <= 2_048, let data = json.data(using: .utf8),
          let target = try? JSONDecoder().decode(Self.self, from: data),
          (0...MonitorRasterPresentation.maximumTag).contains(target.epoch),
          (0...MonitorRasterPresentation.maximumTag).contains(target.sequence) else { return nil }
    if let point = target.point {
      guard !point.key.isEmpty, point.key.utf8.count <= 512, !point.id.isEmpty, point.id.utf8.count <= 128,
            point.seconds.isFinite, abs(point.seconds) <= 1e12, point.value.isFinite, abs(point.value) <= 1e15 else { return nil }
    }
    return target
  }
}

/// Real original points remain visible while a newer exact read is pending. Presentation movement
/// alone never erases them. Epoch and clear watermarks reject delayed packets after clear/retap.
struct MonitorRasterMarkerState {
  private var sourceID = ""
  private var epoch: Int64 = -1
  private var presentationSequence: Int64 = -1
  private var clearSequence: Int64 = -1
  private var active = false
  private var selectionSequence: Int64 = -1
  private var selectionPending = true
  private var originals: [String: MonitorRasterSelection.Point] = [:]
  private var unavailable: [String: Int64] = [:]
  private var targetSequence: Int64 = -1
  private var target: MonitorRasterSelectionTarget.Point?

  mutating func synchronize(sourceID: String, presentation: MonitorRasterPresentation?) {
    guard let presentation else { return }
    if sourceID == self.sourceID && presentation.epoch < epoch { return }
    if sourceID != self.sourceID || presentation.epoch != epoch {
      self = MonitorRasterMarkerState()
      self.sourceID = sourceID; epoch = presentation.epoch
    }
    guard presentation.sequence >= presentationSequence else { return }
    presentationSequence = presentation.sequence
    active = presentation.cursor != nil
    if !active {
      clearSequence = max(clearSequence, presentation.sequence)
      originals.removeAll(); unavailable.removeAll(); target = nil
      selectionSequence = max(selectionSequence, clearSequence)
      targetSequence = max(targetSequence, clearSequence)
    }
  }

  private func eligible(epoch: Int64, sequence: Int64) -> Bool {
    active && epoch == self.epoch && sequence > clearSequence && sequence <= presentationSequence
  }

  mutating func receive(selection: MonitorRasterSelection?, metrics: Set<String>) {
    guard let selection, selection.sourceId == sourceID,
          eligible(epoch: selection.interactionEpoch, sequence: selection.interactionSequence),
          selection.interactionSequence >= selectionSequence else { return }
    if selection.interactionSequence == selectionSequence && !selectionPending && selection.isPending { return }
    selectionSequence = selection.interactionSequence; selectionPending = selection.isPending
    originals = originals.filter { metrics.contains($0.key) }
    unavailable = unavailable.filter { metrics.contains($0.key) }
    let points = selection.points.filter { metrics.contains($0.id) }
    if !selection.isPending {
      originals.removeAll()
      let present = Set(points.map(\.id))
      for metric in metrics where !present.contains(metric) { unavailable[metric] = max(unavailable[metric] ?? -1, selectionSequence) }
    }
    for point in points { originals[point.id] = point }
  }

  mutating func receive(target: MonitorRasterSelectionTarget?) {
    guard let target, eligible(epoch: target.epoch, sequence: target.sequence), target.sequence >= targetSequence else { return }
    targetSequence = target.sequence; self.target = target.point
  }

  var points: [MonitorRasterSelection.Point] { active ? Array(originals.values) : [] }

  func primary(acceptanceID: String, vertices: MonitorRasterVertexIndex) -> MonitorRasterSelection.Point? {
    guard active, let target, target.key == acceptanceID,
          targetSequence > (unavailable[target.id] ?? -1),
          vertices.contains(metric: target.id, seconds: target.seconds, value: target.value) else { return nil }
    return MonitorRasterSelection.Point(id: target.id, seconds: target.seconds, value: target.value)
  }
}
