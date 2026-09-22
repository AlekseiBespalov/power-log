#if canImport(UIKit)
import UIKit
#if canImport(ExpoModulesCore)
import ExpoModulesCore
typealias MonitorRasterBaseView = ExpoView
#else
// The standalone SDK typecheck exercises the same UIKit implementation without Expo's build products.
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
  private let owner = UUID()
  private let plotClip = CALayer()
  private let bitmap = CALayer()
  private let grid = CAShapeLayer()
  private let cursorLine = CAShapeLayer()
  private let referenceLine = CAShapeLayer()
  private var yLabels: [CATextLayer] = []
  private var xLabels: [CATextLayer] = []
  private var pointLayers: [String: CAShapeLayer] = [:]
  private var referenceLayers: [String: CAShapeLayer] = [:]
  private var tailLayers: [String: CAShapeLayer] = [:]
  private var props = MonitorRasterProps()
  private var sourceID: String { props.sourceID }
  private var sceneKey: String { props.sceneKey }
  private var sceneJSON: String { props.sceneJSON }
  private var selection: MonitorRasterSelection? { props.selection }
  private var presentation: MonitorRasterPresentation? { props.presentation }
  private var accepted: MonitorRasterResult?
  private var acceptanceID = ""
  private var markers = MonitorRasterMarkerState()
  private var generation = 0
  private var needsRender = false
  private var submittedDimensions: MonitorRasterDimensions?
  private var axisIdentity: AxisIdentity?

  #if canImport(ExpoModulesCore)
  required init(appContext: AppContext? = nil) { super.init(appContext: appContext); configure() }
  #else
  override init(frame: CGRect) { super.init(frame: frame); configure() }
  required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
  #endif

  deinit { MonitorRasterWorker.shared.cancel(owner: owner) }

  func setSourceID(_ value: String) {
    guard props.setSourceID(value) else { return }
    invalidate(releaseImage: true)
    needsRender = true; setNeedsLayout()
  }

  func setScene(_ value: String) {
    guard props.setScene(value) else { return }
    // Only a bounded string crosses main; packet decoding and all path work happen on the worker.
    invalidate(releaseImage: false)
    needsRender = true; setNeedsLayout()
  }

  func setSceneKey(_ value: String) {
    guard props.setSceneKey(value) else { return }
    invalidate(releaseImage: false)
    needsRender = true; setNeedsLayout()
  }

  func setPresentation(_ values: [Double]) {
    guard props.setPresentation(values) else { return }
    MonitorRasterFrameDiagnostics.shared.presentationChanged()
    // Deliberately no setNeedsLayout, packet parse or graphics submission on this UI-thread path.
    present()
  }

  func setSelection(_ value: String) {
    props.setSelection(value)
    present()
  }

  func setSelectionTarget(_ value: String) {
    props.setSelectionTarget(value)
    present()
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window == nil { invalidate(releaseImage: true); submittedDimensions = nil }
    else { needsRender = true; setNeedsLayout() }
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    let dimensions = MonitorRasterDimensions(width: Double(bounds.width), height: Double(bounds.height),
      scale: Double(window?.screen.scale ?? traitCollection.displayScale))
    if dimensions != submittedDimensions { needsRender = true }
    present()
    guard window != nil, needsRender, props.hasScene, !sceneKey.isEmpty, !sourceID.isEmpty,
          dimensions.plotWidth > 0, dimensions.height > 34 else { return }
    needsRender = false; submittedDimensions = dimensions
    generation += 1
    let token = generation
    let attempt = MonitorRasterRenderAttempt(key: sceneKey, sourceID: sourceID, generation: token, dimensions: dimensions)
    guard !sceneJSON.isEmpty else {
      status(attempt: attempt, error: MonitorRasterError.invalid("Empty or oversized chart packet"))
      return
    }
    MonitorRasterWorker.shared.submit(owner: owner, json: sceneJSON, sourceID: sourceID, dimensions: dimensions,
      requestKey: attempt.key) { [weak self] result in
      guard let self, self.window != nil, self.generation == token else { return }
      switch result {
      case .success(let next):
        guard next.scene.sourceId == self.sourceID else { return }
        // Image, geometry viewport, dimensions and scale change in the same disabled-animation transaction.
        CATransaction.begin(); CATransaction.setDisableActions(true)
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

  private func invalidate(releaseImage: Bool) {
    generation += 1
    MonitorRasterWorker.shared.cancel(owner: owner)
    if releaseImage {
      CATransaction.begin(); CATransaction.setDisableActions(true)
      bitmap.contents = nil; accepted = nil; acceptanceID = ""
      clearSeriesLayers()
      present()
      CATransaction.commit()
    }
  }

  private func configure() {
    isUserInteractionEnabled = false
    backgroundColor = .clear
    layer.addSublayer(grid)
    grid.fillColor = nil; grid.strokeColor = color("#303844"); grid.lineWidth = 1; grid.lineDashPattern = [2, 5]
    layer.addSublayer(plotClip); plotClip.masksToBounds = true
    bitmap.anchorPoint = .zero; bitmap.contentsGravity = .resize; bitmap.minificationFilter = .linear; bitmap.magnificationFilter = .linear
    plotClip.addSublayer(bitmap)
    for shape in [referenceLine, cursorLine] { shape.fillColor = nil; shape.lineWidth = 1; plotClip.addSublayer(shape) }
    cursorLine.strokeColor = color("#f4f6fa")
    referenceLine.strokeColor = color("#9ba6b6"); referenceLine.lineDashPattern = [3, 4]
    for index in 0..<3 {
      let y = textLayer(); y.alignmentMode = .left; yLabels.append(y); layer.addSublayer(y)
      let x = textLayer(); x.alignmentMode = index == 0 ? .left : index == 2 ? .right : .center
      xLabels.append(x); layer.addSublayer(x)
    }
  }

  private func textLayer() -> CATextLayer {
    let text = CATextLayer(); text.font = "Arial" as CFTypeRef; text.fontSize = 10
    text.foregroundColor = color("#9ba6b6")
    return text
  }

  private func clearSeriesLayers() {
    for shape in Array(pointLayers.values) + Array(referenceLayers.values) + Array(tailLayers.values) { shape.removeFromSuperlayer() }
    pointLayers.removeAll(); referenceLayers.removeAll(); tailLayers.removeAll()
  }

  private func makeSeriesLayers(_ scene: MonitorRasterScene) {
    clearSeriesLayers()
    for series in scene.series {
      let point = CAShapeLayer(); point.fillColor = color(series.color); point.strokeColor = color("#15191f"); point.lineWidth = 2
      let reference = CAShapeLayer(); reference.fillColor = color("#15191f"); reference.strokeColor = color(series.color); reference.lineWidth = 1.5
      let tail = CAShapeLayer(); tail.strokeColor = color(series.color); tail.fillColor = nil; tail.lineWidth = 1.8
      plotClip.insertSublayer(tail, below: referenceLine)
      plotClip.addSublayer(point); plotClip.addSublayer(reference)
      pointLayers[series.id] = point; referenceLayers[series.id] = reference; tailLayers[series.id] = tail
    }
  }

  private func present() {
    CATransaction.begin(); CATransaction.setDisableActions(true); defer { CATransaction.commit() }
    markers.synchronize(sourceID: sourceID, presentation: presentation)
    let left = MonitorRasterDimensions.leftInset
    let width = max(0, Double(bounds.width) - left - MonitorRasterDimensions.rightInset), height = Double(bounds.height)
    plotClip.frame = CGRect(x: left, y: 0, width: width, height: max(0, height - 24))
    guard let accepted, let view = presentation, width > 0, height > 34 else {
      axisIdentity = nil
      grid.path = nil; cursorLine.path = nil; referenceLine.path = nil
      for text in xLabels + yLabels { text.isHidden = true }
      return
    }
    MonitorRasterFrameDiagnostics.shared.observePresentation(hasAcceptedBitmap: true, hasCursor: view.cursor != nil)
    let scene = accepted.scene
    let imageWidth = (scene.end - scene.start) / (view.end - view.start) * width
    let imageX = view.x(scene.start, width: width)
    // Huge out-of-coverage transforms are irrelevant and must never overflow layer geometry.
    let covered = scene.end >= view.start && scene.start <= view.end && imageWidth.isFinite && abs(imageX) < 1e9 && imageWidth < 1e9
    bitmap.isHidden = !covered
    if covered {
      bitmap.bounds = CGRect(x: 0, y: 0, width: imageWidth, height: height - 24)
      bitmap.position = CGPoint(x: imageX, y: 0)
    }
    presentAxes(scene: scene, view: view, width: width, height: height)
    cursorLine.path = line(view.cursor, view: view, width: width, height: height)
    referenceLine.path = line(view.reference, view: view, width: width, height: height)
    for shape in Array(pointLayers.values) + Array(referenceLayers.values) + Array(tailLayers.values) { shape.path = nil }
    markers.receive(selection: selection, metrics: Set(scene.series.map(\.id)))
    markers.receive(target: props.selectionTarget)
    presentPoints(markers.points, layers: pointLayers, view: view, width: width)
    if let primary = markers.primary(acceptanceID: acceptanceID, vertices: accepted.vertices) {
      presentPoints([primary], layers: pointLayers, view: view, width: width)
    }
    guard let selection, selection.sourceId == scene.sourceId, selection.interactionEpoch == view.epoch else { return }
    if selection.referenceSeconds == view.reference, view.reference != nil { presentPoints(selection.references, layers: referenceLayers, view: view, width: width) }
    for tail in selection.tails {
      guard tail.end >= view.start, tail.start <= view.end, let layer = tailLayers[tail.id], let y = y(tail.value) else { continue }
      let path = CGMutablePath()
      path.move(to: CGPoint(x: view.x(max(tail.start, view.start), width: width), y: y))
      path.addLine(to: CGPoint(x: view.x(min(tail.end, view.end), width: width), y: y))
      layer.path = path
    }
  }

  private func y(_ value: Double) -> Double? {
    guard let accepted else { return nil }
    let result = MonitorRasterRenderer.y(value, scene: accepted.scene, height: accepted.dimensions.height)
      * (Double(bounds.height) - 24) / accepted.dimensions.plotHeight
    return result.isFinite && abs(result) < 1e9 ? result : nil
  }

  private func presentAxes(scene: MonitorRasterScene, view: MonitorRasterPresentation, width: Double, height: Double) {
    let scale = window?.screen.scale ?? traitCollection.displayScale
    let identity = AxisIdentity(key: acceptanceID, bounds: bounds, start: view.start, end: view.end, displayScale: scale)
    guard identity != axisIdentity else { return }
    axisIdentity = identity
    let left = MonitorRasterDimensions.leftInset
    let empty = scene.series.allSatisfy { $0.points.isEmpty }
    let path = CGMutablePath()
    for index in 0..<3 {
      let fraction = Double(index) / 2
      let value = scene.min + fraction * (scene.max - scene.min)
      let ordinate = y(value) ?? 8
      path.move(to: CGPoint(x: left, y: ordinate)); path.addLine(to: CGPoint(x: width + left, y: ordinate))
      let label = yLabels[index]
      label.isHidden = empty; label.contentsScale = scale
      label.frame = CGRect(x: left + 4, y: ordinate + 2, width: 80, height: 14)
      let valueText = String(format: "%.*f", locale: Locale(identifier: "en_US_POSIX"), scene.decimals, value)
      if label.string as? String != valueText { label.string = valueText }
      let xLabel = xLabels[index]
      xLabel.isHidden = empty; xLabel.contentsScale = scale
      xLabel.frame = CGRect(x: left + width * fraction - (index == 0 ? 0 : index == 2 ? 100 : 50), y: height - 14, width: 100, height: 14)
      let time = duration(view.start + (view.end - view.start) * fraction)
      if xLabel.string as? String != time { xLabel.string = time }
    }
    grid.path = path
  }

  private func line(_ seconds: Double?, view: MonitorRasterPresentation, width: Double, height: Double) -> CGPath? {
    guard let seconds, view.contains(seconds) else { return nil }
    let path = CGMutablePath(); let x = view.x(seconds, width: width)
    path.move(to: CGPoint(x: x, y: 4)); path.addLine(to: CGPoint(x: x, y: height - 26))
    return path
  }

  private func presentPoints(_ points: [MonitorRasterSelection.Point], layers: [String: CAShapeLayer],
                             view: MonitorRasterPresentation, width: Double) {
    for point in points {
      guard view.contains(point.seconds), let layer = layers[point.id], let y = y(point.value) else { continue }
      layer.path = CGPath(ellipseIn: CGRect(x: view.x(point.seconds, width: width) - 4, y: y - 4, width: 8, height: 8), transform: nil)
    }
  }

  private func duration(_ value: Double) -> String {
    let seconds = Int(max(0, floor(value))), hours = seconds / 3_600
    return (hours > 0 ? "\(hours):" : "") + String(format: "%02d:%02d", seconds / 60 % 60, seconds % 60)
  }

  private func color(_ value: String) -> CGColor? { MonitorRasterScene.color(value) }

  private func status(attempt: MonitorRasterRenderAttempt, error: Error? = nil) {
    #if canImport(ExpoModulesCore)
    guard let metadata = attempt.status(viewID: owner.uuidString, currentKey: sceneKey,
      currentSourceID: sourceID, currentGeneration: generation, error: error) else { return }
    var body: [String: Any] = MonitorRasterWorker.shared.diagnostics()
    body.merge(metadata) { _, current in current }
    onRenderStatus(body)
    #endif
  }
}
#endif
