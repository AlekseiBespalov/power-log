import Foundation
import PowerLogCore

final class MonitorRasterViewScheduling {
  private(set) var props = MonitorRasterProps()
  private let requests = MonitorInspectionGate()
  private let worker: MonitorRasterWorker
  let owner = UUID()
  private var attached = false
  private var needsRender = false
  private var submittedDimensions: MonitorRasterDimensions?
  private var generation = 0

  init(worker: MonitorRasterWorker = .shared) { self.worker = worker }
  deinit { worker.cancel(owner: owner) }
  private func invalidate() {
    _ = try? requests.cancel()
    worker.cancel(owner: owner)
    needsRender = true
  }
  func setSourceID(_ value: String) -> Bool {
    guard props.setSourceID(value) else { return false }
    invalidate()
    return true
  }
  func setScene(_ value: String) -> Bool {
    guard props.setScene(value) else { return false }
    invalidate()
    return true
  }
  func setSceneKey(_ value: String) -> Bool {
    guard props.setSceneKey(value) else { return false }
    invalidate()
    return true
  }
  func setAttached(_ value: Bool) {
    attached = value
    invalidate()
    if !value { submittedDimensions = nil }
  }
  func setPresentation(_ values: [Double]) -> Bool { props.setPresentation(values) }
  func setSelection(_ value: String) { props.setSelection(value) }
  func setSelectionTarget(_ value: String) { props.setSelectionTarget(value) }

  @discardableResult
  func render(
    dimensions: MonitorRasterDimensions,
    completion: @escaping (MonitorRasterRenderAttempt, Result<MonitorRasterResult, Error>) -> Void
  ) -> Bool {
    if dimensions != submittedDimensions { needsRender = true }
    guard attached, needsRender, props.hasScene, !props.sceneKey.isEmpty, !props.sourceID.isEmpty,
      dimensions.plotWidth > 0, dimensions.height > 34, let context = props.queryContext
    else { return false }
    needsRender = false
    submittedDimensions = dimensions
    generation += 1
    guard
      let ticket = try? requests.begin(
        source: props.sourceID, revision: context.revision, selection: context.distanceSelection)
    else { return false }
    let attempt = MonitorRasterRenderAttempt(
      key: props.sceneKey, sourceID: props.sourceID, generation: generation, dimensions: dimensions)
    worker.submit(
      owner: owner, json: props.sceneJSON, sourceID: props.sourceID, dimensions: dimensions, requestKey: props.sceneKey,
      requestTicket: ticket, queryContext: context
    ) { [weak self] result in
      guard let self, self.attached, self.requests.complete(ticket) else { return }
      completion(attempt, result)
    }
    return true
  }
}

#if canImport(UIKit)
  import UIKit
  #if canImport(ExpoModulesCore)
    import ExpoModulesCore
    typealias MonitorRasterBaseView = ExpoView
  #else
    typealias MonitorRasterBaseView = UIView
  #endif

  final class MonitorRasterView: MonitorRasterBaseView {
    private struct AxisIdentity: Equatable {
      let key: String
      let bounds: CGRect
      let start: Double
      let end: Double
      let displayScale: CGFloat
    }
    #if canImport(ExpoModulesCore)
      let onRenderStatus = EventDispatcher()
    #endif
    private var owner: UUID { scheduling.owner }
    private let plotClip = CALayer()
    private let bitmap = CALayer()
    private let grid = CAShapeLayer()
    private let cursorLine = CAShapeLayer()
    private let referenceLine = CAShapeLayer()
    private var yLabels: [MonitorRasterAxisLayer] = []
    private var xLabels: [MonitorRasterAxisLayer] = []
    private var pointLayers: [String: CAShapeLayer] = [:]
    private var referenceLayers: [String: CAShapeLayer] = [:]
    private var tailLayers: [String: CAShapeLayer] = [:]
    private let scheduling = MonitorRasterViewScheduling()
    private var sourceID: String { scheduling.props.sourceID }
    private var selection: MonitorRasterSelection? { scheduling.props.selection }
    private var presentation: MonitorRasterPresentation? { scheduling.props.presentation }
    private var accepted: MonitorRasterResult?
    private var acceptanceID = ""
    private var markers = MonitorRasterMarkerState()
    private var axisIdentity: AxisIdentity?

    #if canImport(ExpoModulesCore)
      required init(appContext: AppContext? = nil) {
        super.init(appContext: appContext)
        configure()
      }
    #else
      override init(frame: CGRect) {
        super.init(frame: frame)
        configure()
      }
      required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
    #endif

    func setSourceID(_ value: String) {
      guard scheduling.setSourceID(value) else { return }
      clearAccepted()
      setNeedsLayout()
    }

    func setScene(_ value: String) {
      guard scheduling.setScene(value) else { return }
      setNeedsLayout()
    }

    func setSceneKey(_ value: String) {
      guard scheduling.setSceneKey(value) else { return }
      setNeedsLayout()
    }

    func setPresentation(_ values: [Double]) {
      guard scheduling.setPresentation(values) else { return }
      MonitorRasterFrameDiagnostics.shared.presentationChanged()
      present()
    }

    func setSelection(_ value: String) {
      scheduling.setSelection(value)
      present()
    }

    func setSelectionTarget(_ value: String) {
      scheduling.setSelectionTarget(value)
      present()
    }

    override func didMoveToWindow() {
      super.didMoveToWindow()
      scheduling.setAttached(window != nil)
      if window == nil {
        clearAccepted()
      } else {
        setNeedsLayout()
      }
    }

    override func layoutSubviews() {
      super.layoutSubviews()
      let dimensions = MonitorRasterDimensions(
        width: Double(bounds.width), height: Double(bounds.height),
        scale: Double(window?.screen.scale ?? traitCollection.displayScale))
      present()
      scheduling.render(dimensions: dimensions) { [weak self] attempt, result in
        guard let self, self.window != nil else { return }
        switch result {
        case .success(let next):
          CATransaction.begin()
          CATransaction.setDisableActions(true)
          self.bitmap.contents = next.image
          self.accepted = next
          self.acceptanceID = attempt.acceptanceID(viewID: self.owner.uuidString)
          self.makeSeriesLayers(next.scene)
          self.present()
          CATransaction.commit()
          self.status(attempt: attempt)
        case .failure(let error): self.status(attempt: attempt, error: error)
        }
      }
    }

    private func clearAccepted() {
      CATransaction.begin()
      CATransaction.setDisableActions(true)
      bitmap.contents = nil
      accepted = nil
      acceptanceID = ""
      clearSeriesLayers()
      present()
      CATransaction.commit()
    }

    private func configure() {
      isUserInteractionEnabled = false
      backgroundColor = .clear
      layer.addSublayer(grid)
      grid.fillColor = nil
      grid.strokeColor = color("#303844")
      grid.lineWidth = 1
      grid.lineDashPattern = [2, 5]
      layer.addSublayer(plotClip)
      plotClip.masksToBounds = true
      bitmap.anchorPoint = .zero
      bitmap.contentsGravity = .resize
      bitmap.minificationFilter = .linear
      bitmap.magnificationFilter = .linear
      plotClip.addSublayer(bitmap)
      for shape in [referenceLine, cursorLine] {
        shape.fillColor = nil
        shape.lineWidth = 1
        plotClip.addSublayer(shape)
      }
      cursorLine.strokeColor = color("#f4f6fa")
      referenceLine.strokeColor = color("#9ba6b6")
      referenceLine.lineDashPattern = [3, 4]
      for _ in 0..<3 {
        let y = MonitorRasterAxes.layer()
        yLabels.append(y)
        layer.addSublayer(y.backing)
        let x = MonitorRasterAxes.layer()
        xLabels.append(x)
        layer.addSublayer(x.backing)
      }
    }

    private func clearSeriesLayers() {
      for shape in Array(pointLayers.values) + Array(referenceLayers.values) + Array(tailLayers.values) {
        shape.removeFromSuperlayer()
      }
      pointLayers.removeAll()
      referenceLayers.removeAll()
      tailLayers.removeAll()
    }

    private func makeSeriesLayers(_ scene: MonitorRasterScene) {
      clearSeriesLayers()
      for series in scene.series {
        let point = CAShapeLayer()
        point.fillColor = color(series.color)
        point.strokeColor = color("#15191f")
        point.lineWidth = 2
        let reference = CAShapeLayer()
        reference.fillColor = color("#15191f")
        reference.strokeColor = color(series.color)
        reference.lineWidth = 1.5
        let tail = CAShapeLayer()
        tail.strokeColor = color(series.color)
        tail.fillColor = nil
        tail.lineWidth = 1.8
        plotClip.insertSublayer(tail, below: referenceLine)
        plotClip.addSublayer(point)
        plotClip.addSublayer(reference)
        pointLayers[series.id] = point
        referenceLayers[series.id] = reference
        tailLayers[series.id] = tail
      }
    }

    private func present() {
      CATransaction.begin()
      CATransaction.setDisableActions(true)
      defer { CATransaction.commit() }
      guard let context = scheduling.props.queryContext else { return }
      markers.synchronize(
        sourceID: sourceID, revision: context.revision, distanceSelection: context.distanceSelection,
        generation: context.generation,
        presentation: presentation)
      let left = MonitorRasterDimensions.leftInset
      let width = max(0, Double(bounds.width) - left - MonitorRasterDimensions.rightInset)
      let height = Double(bounds.height)
      plotClip.frame = CGRect(x: left, y: 0, width: width, height: max(0, height - 24))
      guard let accepted, let view = presentation, width > 0, height > 34 else {
        axisIdentity = nil
        grid.path = nil
        cursorLine.path = nil
        referenceLine.path = nil
        for label in xLabels + yLabels { label.backing.isHidden = true }
        return
      }
      MonitorRasterFrameDiagnostics.shared.observePresentation(hasAcceptedBitmap: true, hasCursor: view.cursor != nil)
      let scene = accepted.scene
      let imageWidth = (scene.end - scene.start) / (view.end - view.start) * width
      let imageX = view.x(scene.start, width: width)
      // Huge out-of-coverage transforms are irrelevant and must never overflow layer geometry.
      let covered =
        scene.end >= view.start && scene.start <= view.end && imageWidth.isFinite && abs(imageX) < 1e9
        && imageWidth < 1e9
      bitmap.isHidden = !covered
      if covered {
        bitmap.bounds = CGRect(x: 0, y: 0, width: imageWidth, height: height - 24)
        bitmap.position = CGPoint(x: imageX, y: 0)
      }
      presentAxes(scene: scene, view: view, width: width, height: height)
      cursorLine.path = line(view.cursor, view: view, width: width, height: height)
      referenceLine.path = line(view.reference, view: view, width: width, height: height)
      for shape in Array(pointLayers.values) + Array(referenceLayers.values) + Array(tailLayers.values) {
        shape.path = nil
      }
      markers.receive(selection: selection, metrics: Set(scene.series.map(\.id)))
      markers.receive(target: scheduling.props.selectionTarget)
      presentPoints(markers.points, layers: pointLayers, view: view, width: width)
      presentPoints(
        markers.previews(acceptanceID: acceptanceID, vertices: accepted.vertices),
        layers: pointLayers, view: view, width: width)
      guard let selection, markers.accepts(selection: selection) else {
        return
      }
      if selection.referenceSeconds == view.reference, view.reference != nil {
        presentPoints(selection.references, layers: referenceLayers, view: view, width: width)
      }
      for tail in selection.tails {
        guard tail.end >= view.start, tail.start <= view.end, let layer = tailLayers[tail.id], let y = y(tail.value)
        else { continue }
        let path = CGMutablePath()
        path.move(to: CGPoint(x: view.x(max(tail.start, view.start), width: width), y: y))
        path.addLine(to: CGPoint(x: view.x(min(tail.end, view.end), width: width), y: y))
        layer.path = path
      }
    }

    private func y(_ value: Double) -> Double? {
      guard let accepted else { return nil }
      let result =
        MonitorRasterRenderer.y(value, scene: accepted.scene, height: accepted.dimensions.height)
        * (Double(bounds.height) - 24) / accepted.dimensions.plotHeight
      return result.isFinite && abs(result) < 1e9 ? result : nil
    }

    private func presentAxes(scene: MonitorRasterScene, view: MonitorRasterPresentation, width: Double, height: Double)
    {
      let scale = window?.screen.scale ?? traitCollection.displayScale
      let identity = AxisIdentity(
        key: acceptanceID, bounds: bounds, start: view.start, end: view.end, displayScale: scale)
      guard identity != axisIdentity else { return }
      axisIdentity = identity
      let left = MonitorRasterDimensions.leftInset
      let empty = !MonitorRasterAxes.hasLabels(scene)
      let dimensions = MonitorRasterDimensions(width: Double(bounds.width), height: height, scale: Double(scale))
      let path = CGMutablePath()
      for index in 0..<3 {
        let fraction = Double(index) / 2
        let value = scene.min + fraction * (scene.max - scene.min)
        let ordinate = y(value) ?? 8
        path.move(to: CGPoint(x: left, y: ordinate))
        path.addLine(to: CGPoint(x: width + left, y: ordinate))
        let label = yLabels[index]
        label.backing.isHidden = empty
        label.text.contentsScale = scale
        let valueText = String(format: "%.*f", locale: Locale(identifier: "en_US_POSIX"), scene.decimals, value)
        if label.text.string as? String != valueText { label.text.string = valueText }
        let placement = MonitorRasterAxes.yLabel(
          ordinate: ordinate, textWidth: Double(label.text.preferredFrameSize().width), dimensions: dimensions)
        label.backing.frame = placement.frame
        label.text.frame = placement.textFrame
        let xLabel = xLabels[index]
        xLabel.backing.isHidden = empty
        xLabel.text.contentsScale = scale
        let time = duration(view.start + (view.end - view.start) * fraction)
        if xLabel.text.string as? String != time { xLabel.text.string = time }
        let xPlacement = MonitorRasterAxes.xLabel(
          fraction: fraction, textWidth: Double(xLabel.text.preferredFrameSize().width), dimensions: dimensions)
        xLabel.backing.frame = xPlacement.frame
        xLabel.text.frame = xPlacement.textFrame
      }
      grid.path = path
    }

    private func line(_ seconds: Double?, view: MonitorRasterPresentation, width: Double, height: Double) -> CGPath? {
      guard let seconds, view.contains(seconds) else { return nil }
      let path = CGMutablePath()
      let x = view.x(seconds, width: width)
      path.move(to: CGPoint(x: x, y: 4))
      path.addLine(to: CGPoint(x: x, y: height - 26))
      return path
    }

    private func presentPoints(
      _ points: [MonitorRasterSelection.Point], layers: [String: CAShapeLayer],
      view: MonitorRasterPresentation, width: Double
    ) {
      for point in points {
        guard view.contains(point.seconds), let layer = layers[point.id], let y = y(point.value) else { continue }
        layer.path = CGPath(
          ellipseIn: CGRect(x: view.x(point.seconds, width: width) - 4, y: y - 4, width: 8, height: 8), transform: nil)
      }
    }

    private func duration(_ value: Double) -> String {
      let seconds = Int(max(0, floor(value)))
      let hours = seconds / 3_600
      return (hours > 0 ? "\(hours):" : "") + String(format: "%02d:%02d", seconds / 60 % 60, seconds % 60)
    }

    private func color(_ value: String) -> CGColor? { MonitorRasterScene.color(value) }

    private func status(attempt: MonitorRasterRenderAttempt, error: Error? = nil) {
      #if canImport(ExpoModulesCore)
        let metadata = attempt.status(viewID: owner.uuidString, error: error)
        var body: [String: Any] = MonitorRasterWorker.shared.diagnostics()
        body.merge(metadata) { _, current in current }
        onRenderStatus(body)
      #endif
    }
  }
#endif
