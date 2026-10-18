import Foundation
import PowerLogCore

struct MonitorRasterSelectionTarget: Decodable {
  struct Point: Decodable {
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
      (0...MonitorRasterPresentation.maximumTag).contains(target.epoch),
      (0...MonitorRasterPresentation.maximumTag).contains(target.sequence),
      target.points.count <= MonitorRasterScene.maximumSeries,
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
struct MonitorRasterMarkerState {
  private var sourceID = ""
  private var revision = ""
  private var distanceSelection = "auto"
  private var generation: Int64 = 0
  private var ticket: InspectionTicket?
  private var epoch: Int64 = -1
  private var presentationSequence: Int64 = -1
  private var clearSequence: Int64 = -1
  private var active = false
  private var selectionSequence: Int64 = -1
  private var selectionPending = true
  private var originals: [String: MonitorRasterSelection.Point] = [:]
  private var targetSequence: Int64 = -1
  private var targets: [MonitorRasterSelectionTarget.Point] = []

  mutating func synchronize(
    sourceID: String, revision: String, distanceSelection: String, generation: Int64,
    presentation: MonitorRasterPresentation?
  ) {
    guard let presentation else { return }
    if sourceID == self.sourceID && presentation.epoch < epoch { return }
    if sourceID != self.sourceID || revision != self.revision || distanceSelection != self.distanceSelection
      || generation != self.generation || presentation.epoch != epoch
    {
      self = MonitorRasterMarkerState()
      self.sourceID = sourceID
      self.revision = revision
      self.distanceSelection = distanceSelection
      self.generation = generation
      epoch = presentation.epoch
    }
    guard presentation.sequence >= presentationSequence else { return }
    presentationSequence = presentation.sequence
    ticket =
      (try? InspectionContext(
        sourceId: sourceID, revision: revision, distanceSelection: distanceSelection,
        interactionEpoch: presentation.epoch, interactionSequence: presentation.sequence)).map {
        InspectionTicket(context: $0, generation: generation)
      }
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
    guard active, sequence > clearSequence,
      let context = try? InspectionContext(
        sourceId: sourceID, revision: revision,
        distanceSelection: distanceSelection, interactionEpoch: epoch, interactionSequence: sequence)
    else { return false }
    return InspectionAcceptance.shared.accepts(
      current: ticket, result: InspectionTicket(context: context, generation: generation))
  }

  func accepts(selection: MonitorRasterSelection) -> Bool {
    guard
      let context = try? InspectionContext(
        sourceId: selection.sourceId, revision: selection.revision,
        distanceSelection: selection.distanceSelection, interactionEpoch: selection.interactionEpoch,
        interactionSequence: selection.interactionSequence)
    else { return false }
    return InspectionAcceptance.shared.accepts(
      current: ticket, result: InspectionTicket(context: context, generation: selection.generation))
  }

  mutating func receive(selection: MonitorRasterSelection?, metrics: Set<String>) {
    guard let selection, accepts(selection: selection),
      eligible(epoch: selection.interactionEpoch, sequence: selection.interactionSequence),
      selection.interactionSequence >= selectionSequence
    else { return }
    if selection.interactionSequence == selectionSequence && !selectionPending && selection.isPending { return }
    selectionSequence = selection.interactionSequence
    selectionPending = selection.isPending
    let points = selection.points.filter { metrics.contains($0.id) }
    originals.removeAll()
    for point in points { originals[point.id] = point }
  }

  mutating func receive(target: MonitorRasterSelectionTarget?) {
    guard let target, eligible(epoch: target.epoch, sequence: target.sequence), target.sequence >= targetSequence else {
      return
    }
    targetSequence = target.sequence
    targets = target.points
  }

  var points: [MonitorRasterSelection.Point] {
    active && selectionSequence == presentationSequence ? Array(originals.values) : []
  }

  func previews(acceptanceID: String, vertices: MonitorRasterVertexIndex) -> [MonitorRasterSelection.Point] {
    guard active, targetSequence == presentationSequence,
      selectionSequence != presentationSequence || selectionPending
    else { return [] }
    return targets.compactMap { target in
      guard target.key == acceptanceID,
        vertices.contains(metric: target.id, seconds: target.seconds, value: target.value)
      else { return nil }
      return MonitorRasterSelection.Point(id: target.id, seconds: target.seconds, value: target.value)
    }
  }
}
