import Foundation
import CoreGraphics
import PowerLogCore

var assertions = 0
func check(_ condition: @autoclosure () -> Bool, _ message: String) {
  assertions += 1
  guard condition() else { fatalError(message) }
}
func rejects(_ message: String, _ action: () throws -> Void) {
  do {
    try action()
    fatalError(message)
  } catch { assertions += 1 }
}
func packet(
  key: String = "test", source: String = "source", step: Bool = false,
  points: [[Any]] = [[0, 50, true], [10, 50, false]], lanes: Int = 1, revision: String = "1",
  distanceSelection: String = "auto"
) -> String {
  let body: [String: Any] = [
    "key": key, "sourceId": source,
    "queryContext": ["revision": revision, "distanceSelection": distanceSelection, "generation": 0],
    "start": 0, "end": 10,
    "min": 0, "max": 100, "decimals": 1, "laneCount": lanes,
    "series": [["id": "metric", "color": "#ff0000", "step": step, "points": points]],
  ]
  return String(data: try! JSONSerialization.data(withJSONObject: body, options: [.sortedKeys]), encoding: .utf8)!
}
let submicrosecondScene = try MonitorRasterScene.parse(
  packet(points: [[1.0000008, 90, true], [1.0000002, 20, false]]), sourceID: "source")
check(
  submicrosecondScene.series[0].points.map(\.seconds) == [1.0000008, 1.0000002],
  "Renderer admits canonical microsecond order without changing original point times")
let dimensions = MonitorRasterDimensions(width: 116, height: 124, scale: 1)
func draw(_ points: [[Any]], step: Bool = false) throws -> CGImage {
  let scene = try MonitorRasterScene.parse(packet(step: step, points: points), sourceID: "source")
  return try MonitorRasterRenderer.draw(scene: scene, dimensions: dimensions, cancelled: { false })
}
func ink(_ image: CGImage, _ x: Int, _ y: Int, radius: Int = 1) -> Bool {
  let bytes = image.dataProvider!.data!
  let data = CFDataGetBytePtr(bytes)!
  for py in max(0, y - radius)...min(image.height - 1, y + radius) {
    for px in max(0, x - radius)...min(image.width - 1, x + radius) {
      if data[py * image.bytesPerRow + px * 4 + 3] > 30 { return true }
    }
  }
  return false
}
func wait(_ semaphore: DispatchSemaphore, _ message: String) {
  check(semaphore.wait(timeout: .now() + 5) == .success, message)
}
func eventually(_ message: String, _ condition: () -> Bool) {
  let deadline = Date().addingTimeInterval(5)
  while Date() < deadline {
    if condition() {
      assertions += 1
      return
    }
    Thread.sleep(forTimeInterval: 0.002)
  }
  fatalError(message)
}

let horizontal = try draw([[0, 50, true], [10, 50, false]])
check(horizontal.width == 100 && horizontal.height == 100, "Only plot pixels are allocated")
check(ink(horizontal, 50, 53), "Direct production stroke draws an explicitly continuous line")
let gap = try draw([[1, 50, true], [2, 50, false], [8, 50, true], [9, 50, false]])
check(ink(gap, 15, 53) && ink(gap, 85, 53), "Both real runs are present")
check(!ink(gap, 50, 53), "Explicit startsSegment creates a visible gap")
let singleton = try draw([[5, 50, true]])
check(ink(singleton, 50, 53), "Singletons survive as visible dots")
check(!ink(singleton, 20, 53), "Singletons never synthesize a line")
let step = try draw([[2, 80, true], [8, 20, false]], step: true)
check(ink(step, 50, 26) && ink(step, 80, 53), "Step draws horizontal then vertical")
check(!ink(step, 50, 53), "Step does not become a diagonal")
let linear = try draw([[2, 80, true], [8, 20, false]])
check(ink(linear, 50, 53) && !ink(linear, 50, 26), "Linear series remains linear")
let edges = try draw([[-10, 50, true], [20, 50, false]])
check(ink(edges, 0, 53) && ink(edges, 99, 53), "Predecessor and successor clip through viewport edges")
let distantEdges = try draw([[-1e9, 50, true], [1e9, 50, false]])
check(
  ink(distantEdges, 0, 53) && ink(distantEdges, 99, 53),
  "Very distant edge samples clip numerically before CoreGraphics")
let markerFalse = try draw([[0, 50, true], [10, 50, false]])
check(ink(markerFalse, 50, 53), "Explicit false is not replaced by a coarse timestamp gap heuristic")
let noSamples = try draw([])
check(!ink(noSamples, 50, 53), "Empty scene remains transparent")
let scene = try MonitorRasterScene.parse(packet(), sourceID: "source")
let axisLayer = MonitorRasterAxes.layer()
check(
  axisLayer.backing.backgroundColor == MonitorRasterScene.color("#0b0d10")
    && axisLayer.backing.backgroundColor?.alpha == 1,
  "Production axis layers have an opaque backing in the chart card colour")
check(axisLayer.backing.sublayers?.first === axisLayer.text, "Axis text is above its backing")
axisLayer.backing.frame = CGRect(x: 0, y: 0, width: 36, height: 18)
axisLayer.text.frame = CGRect(x: 4, y: 2, width: 28, height: 14)
axisLayer.text.string = "100.0"
let labelContext = CGContext(
  data: nil, width: 36, height: 18, bitsPerComponent: 8, bytesPerRow: 36 * 4,
  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
labelContext.setFillColor(MonitorRasterScene.color("#ff0000")!)
labelContext.fill(CGRect(x: 0, y: 0, width: 36, height: 18))
axisLayer.backing.render(in: labelContext)
let labelImage = labelContext.makeImage()!
let labelBytes = CFDataGetBytePtr(labelImage.dataProvider!.data!)!
check(
  Array(UnsafeBufferPointer(start: labelBytes + labelImage.bytesPerRow + 4, count: 4)) == [11, 13, 16, 255],
  "Production label backing masks the stroke pixels beneath it")
let labelDimensions = MonitorRasterDimensions(width: 360, height: 140, scale: 3)
for (ordinate, expectedY) in [(8.0, 12.0), (61.0, 63.0), (114.0, 94.0)] {
  let label = MonitorRasterAxes.yLabel(ordinate: ordinate, textWidth: 28, dimensions: labelDimensions)
  check(
    label.frame == CGRect(x: 12, y: expectedY, width: 36, height: 18),
    "Y labels have a padded backing inset from the unchanged plot at ordinate \(ordinate): \(label.frame)")
  check(
    label.textFrame == CGRect(x: 4, y: 2, width: 28, height: 14),
    "Axis text sits inside its backing with four horizontal and two vertical points of padding")
  check(label.frame.maxY <= labelDimensions.plotHeight - 4, "Y labels stay clear of the time labels")
}
for (fraction, expectedX) in [(0.0, 12.0), (0.5, 162.0), (1.0, 312.0)] {
  let label = MonitorRasterAxes.xLabel(fraction: fraction, textWidth: 28, dimensions: labelDimensions)
  check(
    label.frame == CGRect(x: expectedX, y: 118, width: 36, height: 18),
    "Time labels are inset from plot ends and the card bottom")
}
let longLabel = MonitorRasterAxes.yLabel(ordinate: 61, textWidth: 800, dimensions: labelDimensions)
check(longLabel.frame.maxX == 348, "Long axis labels stay inside the opposite plot inset")
check(labelDimensions.plotWidth == 344 && labelDimensions.plotHeight == 116, "Label layout never resizes the plot")
check(
  MonitorRasterRenderer.y(50, scene: scene, height: 140) == 61,
  "Axis label layout preserves the ordinate used by hit testing")
let emptyScene = try MonitorRasterScene.parse(packet(points: []), sourceID: "source")
check(!MonitorRasterAxes.hasLabels(emptyScene), "Empty charts show neither value nor time numbers")
check(MonitorRasterAxes.hasLabels(scene), "An original sample enables the axis labels")
check(
  MonitorRasterAxes.background == "#0b0d10",
  "Opaque label backings use the chart card colour")
rejects("Cancelled raster should not allocate/draw") {
  _ = try MonitorRasterRenderer.draw(scene: scene, dimensions: dimensions, cancelled: { true })
}
rejects("Wrong source rejected") { _ = try MonitorRasterScene.parse(packet(), sourceID: "other") }
rejects("Malformed JSON rejected") { _ = try MonitorRasterScene.parse("{", sourceID: "source") }
rejects("Nonfinite JSON rejected") {
  _ = try MonitorRasterScene.parse(packet().replacingOccurrences(of: "50", with: "1e999"), sourceID: "source")
}
rejects("Missing boolean rejected") { _ = try MonitorRasterScene.parse(packet(points: [[1, 1]]), sourceID: "source") }
rejects("Numeric boolean rejected") {
  _ = try MonitorRasterScene.parse(packet(points: [[1, 1, 0]]), sourceID: "source")
}
rejects("Unsorted data rejected") {
  _ = try MonitorRasterScene.parse(packet(points: [[2, 1, true], [1, 1, false]]), sourceID: "source")
}
rejects("Point limit rejected") {
  _ = try MonitorRasterScene.parse(
    packet(points: (0...16_384).map { [Double($0) / 2_000, 50, false] }), sourceID: "source")
}
rejects("Lane count rejected") { _ = try MonitorRasterScene.parse(packet(lanes: 129), sourceID: "source") }
for lanes in [1, 2, 16, 64, 128] {
  for size in [
    dimensions, MonitorRasterDimensions(width: 430, height: 220, scale: 3),
    MonitorRasterDimensions(width: 16_384, height: 16_384, scale: 8),
  ] {
    let pixels = try size.pixels(laneCount: lanes)
    check(pixels.bytes <= MonitorRasterDimensions.maximumBitmapBytes, "Every bitmap <= 2 MiB")
    check(pixels.bytes * lanes <= MonitorRasterDimensions.collectionBytes, "Collection <= 32 MiB")
    check(
      pixels.bytes * lanes * 2 + pixels.bytes <= 2 * MonitorRasterDimensions.collectionBytes
        + MonitorRasterDimensions.maximumBitmapBytes, "Accepted, replacements and scratch obey peak bound")
  }
}
let phonePixels = try MonitorRasterDimensions(width: 390, height: 180, scale: 3).pixels(laneCount: 2)
check(phonePixels.width == 1_122, "Phone plots use native 3x density when within individual bitmap budget")
for invalid in [
  MonitorRasterDimensions(width: .nan, height: 200, scale: 2),
  MonitorRasterDimensions(width: 16, height: 200, scale: 2),
  MonitorRasterDimensions(width: 200, height: .infinity, scale: 2),
  MonitorRasterDimensions(width: 200, height: 200, scale: 0),
] {
  rejects("Invalid dimensions rejected") { _ = try invalid.pixels(laneCount: 1) }
}

let firstEntered = DispatchSemaphore(value: 0)
let firstReleased = DispatchSemaphore(value: 0)
let delivered = DispatchSemaphore(value: 0)
let callbackQueue = DispatchQueue(label: "raster.tests.callback")
let worker = MonitorRasterWorker(completionQueue: callbackQueue) { scene, dimensions, cancelled in
  if scene.key == "first" {
    firstEntered.signal()
    _ = firstReleased.wait(timeout: .now() + 5)
  }
  return try MonitorRasterRenderer.draw(scene: scene, dimensions: dimensions, cancelled: cancelled)
}
let a = UUID()
let b = UUID()
var received: [String] = []
var held: [MonitorRasterResult] = []
let resultsLock = NSLock()
let receive: (Result<MonitorRasterResult, Error>) -> Void = { result in
  resultsLock.lock()
  defer { resultsLock.unlock() }
  switch result {
  case .success(let result):
    received.append(result.scene.key)
    held.append(result)
  case .failure(let error): fatalError("Unexpected scheduler error: \(error)")
  }
  delivered.signal()
}
worker.submit(owner: a, json: packet(key: "first"), sourceID: "source", dimensions: dimensions, completion: receive)
wait(firstEntered, "First raster starts")
for index in 2...200 {
  worker.submit(
    owner: a, json: packet(key: "a-\(index)"), sourceID: "source", dimensions: dimensions, completion: receive)
}
let replacementDimensions = MonitorRasterDimensions(width: 254, height: 224, scale: 2)
worker.submit(
  owner: b, json: packet(key: "other-source", source: "new-source"), sourceID: "new-source",
  dimensions: replacementDimensions, completion: receive)
check(worker.diagnostics()["pending"] == 2, "One pending request per lane during 200-event burst")
check(worker.diagnostics()["started"] == 1, "Only one global worker raster starts while blocked")
firstReleased.signal()
wait(delivered, "Latest first lane completes")
wait(delivered, "Other lane completes")
callbackQueue.sync {}
check(Set(received) == Set(["a-200", "other-source"]), "Obsolete in-flight and replaced pending jobs cannot publish")
check(
  held.first { $0.scene.key == "other-source" }?.dimensions == replacementDimensions,
  "Dimensions and DPR travel atomically with accepted image and source")
check(worker.diagnostics()["started"] == 3, "Burst rasterizes at most current plus final requests")
check(worker.diagnostics()["completed"] == 2, "Cancelled worker does not complete a bitmap")
check(worker.diagnostics()["discarded"] == 199, "All obsolete requests are counted")
check(worker.diagnostics()["scratchBytes"] == 0, "Scratch released after render")
check(
  worker.diagnostics()["bitmapBytes"] == held.reduce(0) { $0 + $1.bytes }, "Accepted bitmap reservations are measured")
worker.cancel(owner: a)
worker.cancel(owner: b)
held.removeAll()
eventually("Detaching and releasing accepted scenes releases all image reservations") {
  worker.diagnostics()["bitmapBytes"] == 0
}
check(worker.diagnostics()["mountedLanes"] == 0, "Detached views leave no worker registrations")

let pausedCallbacks = DispatchQueue(label: "raster.tests.paused")
pausedCallbacks.suspend()
let staleWorker = MonitorRasterWorker(completionQueue: pausedCallbacks)
let staleOwner = UUID()
var stalePublished = false
staleWorker.submit(owner: staleOwner, json: packet(key: "stale"), sourceID: "source", dimensions: dimensions) { _ in
  stalePublished = true
}
eventually("First completion queued") { staleWorker.diagnostics()["completed"] == 1 }
staleWorker.cancel(owner: staleOwner)
pausedCallbacks.resume()
pausedCallbacks.sync {}
check(
  !stalePublished && staleWorker.diagnostics()["discarded"] == 1, "Detach rejects a completed but unpublished image")
eventually("Discarded queued bitmap released") { staleWorker.diagnostics()["bitmapBytes"] == 0 }

let budgetWorker = MonitorRasterWorker(completionQueue: callbackQueue)
let budgetDimensions = MonitorRasterDimensions(width: 1_040, height: 536, scale: 1)
let budgetDelivered = DispatchSemaphore(value: 0)
var budgetImages: [MonitorRasterResult] = []
var budgetErrors = 0
var budgetOwners: [UUID] = []
for index in 0..<33 {
  let owner = UUID()
  budgetOwners.append(owner)
  budgetWorker.submit(
    owner: owner, json: packet(key: "budget-\(index)"), sourceID: "source", dimensions: budgetDimensions
  ) { result in
    switch result {
    case .success(let image): budgetImages.append(image)
    case .failure: budgetErrors += 1
    }
    budgetDelivered.signal()
  }
  wait(budgetDelivered, "Budget job finishes")
}
callbackQueue.sync {}
check(
  budgetImages.count == 32 && budgetErrors == 1,
  "Global reservation rejects any image beyond two 32 MiB collection budgets")
check(
  budgetWorker.diagnostics()["bitmapBytes"] == 64 * 1_024 * 1_024, "Measured retained bitmap allocation remains 64 MiB")
check(
  budgetWorker.diagnostics()["peakBytes"] == 66 * 1_024 * 1_024,
  "Measured overlap including one scratch context remains 66 MiB")
check(budgetWorker.diagnostics()["errors"] == 1, "Budget refusal is visible in diagnostics")
for owner in budgetOwners { budgetWorker.cancel(owner: owner) }
budgetImages.removeAll()
eventually("Stress-test images all release") { budgetWorker.diagnostics()["bitmapBytes"] == 0 }

let presentation = MonitorRasterPresentation([2, 8, 1, 5, 1, 3])!
check(
  presentation.x(5, width: 100) == 50 && presentation.cursor == 5 && presentation.reference == 3,
  "Presentation positions cursor and reference from current viewport")
check(MonitorRasterPresentation([0, 0, 0, 0, 0, 0]) == nil, "Zero presentation duration rejected")
check(MonitorRasterPresentation([0, 1, 2, 0, 0, 0]) == nil, "Malformed cursor flag rejected")
let before = worker.diagnostics()["submitted"]!
for index in 0..<240 {
  let value = MonitorRasterPresentation([0, 10, 1, Double(index) / 24, 0, 0])!
  _ = value.x(value.cursor!, width: 100)
}
check(worker.diagnostics()["submitted"] == before, "Presentation math never submits raster work")
let selectionJSON =
  "{\"sourceId\":\"source\",\"cursorSeconds\":5,\"referenceSeconds\":null,\"points\":[{\"id\":\"metric\",\"seconds\":4.9,\"value\":50}],\"references\":[],\"revision\":\"1\",\"distanceSelection\":\"auto\",\"generation\":0,\"tailCandidates\":[]}"
let selection = MonitorRasterSelection.parse(selectionJSON)!
check(
  selection.cursorSeconds == 5 && selection.points[0].seconds == 4.9,
  "Exact point preserves original observation time separately from cursor tag")
check(MonitorRasterSelection.parse("{}") == nil, "Malformed overlays rejected safely")

func propOrders(_ remaining: [String]) -> [[String]] {
  remaining.isEmpty
    ? [[]] : remaining.flatMap { next in propOrders(remaining.filter { $0 != next }).map { [next] + $0 } }
}
let overlayPacket =
  "{\"sourceId\":\"source\",\"cursorSeconds\":5,\"referenceSeconds\":3,\"points\":[{\"id\":\"metric\",\"seconds\":4.9,\"value\":50}],\"references\":[{\"id\":\"metric\",\"seconds\":3.1,\"value\":40}],\"revision\":\"1\",\"distanceSelection\":\"auto\",\"generation\":0,\"tailCandidates\":[{\"id\":\"humanPowerW\",\"observationId\":\"a\",\"evidence\":\"a\",\"start\":9,\"elapsed\":10,\"now\":100,\"acquired\":99,\"value\":50}]}"
for scenario in ["initial mount", "same-source remount", "source swap"] {
  for order in propOrders(["source", "scene", "presentation", "selection"]) {
    var props = MonitorRasterProps()
    if scenario != "initial mount" {
      let previous = scenario == "source swap" ? "previous-source" : "source"
      _ = props.setSourceID(previous)
      _ = props.setScene(packet(key: "old-scene", source: previous))
      _ = props.setPresentation([0, 10, 0, 0, 0, 0])
      props.setSelection(overlayPacket.replacingOccurrences(of: "\"source\"", with: "\"\(previous)\""))
    }
    for name in order {
      switch name {
      case "source": _ = props.setSourceID("source")
      case "scene": _ = props.setScene(packet(key: "current-scene"))
      case "presentation": _ = props.setPresentation([2, 8, 1, 5, 1, 3])
      default: props.setSelection(overlayPacket)
      }
    }
    let currentScene = try MonitorRasterScene.parse(props.sceneJSON, sourceID: props.sourceID)
    check(
      currentScene.key == "current-scene" && props.presentation?.cursor == 5 && props.presentation?.reference == 3,
      "\(scenario): source, scene and presentation survive prop order \(order)")
    check(
      props.selection?.sourceId == "source" && props.selection?.points.first?.seconds == 4.9
        && props.selection?.references.first?.seconds == 3.1 && props.selection?.tails.first?.end == 10,
      "\(scenario): exact dots, references and tails survive prop order \(order)")
  }
}
var changingSource = MonitorRasterProps()
_ = changingSource.setSourceID("source")
changingSource.setSelection(overlayPacket)
_ = changingSource.setSourceID("different-source")
check(changingSource.selection == nil, "A source change still clears old-source exact overlays immediately")
var fullscreenMount = MonitorRasterProps()
fullscreenMount.setSelection(overlayPacket)
_ = fullscreenMount.setPresentation([2, 8, 1, 5, 1, 3])
_ = fullscreenMount.setScene(packet())
_ = fullscreenMount.setSourceID("source")
check(
  fullscreenMount.selection?.cursorSeconds == 5 && fullscreenMount.presentation?.cursor == 5,
  "Fresh fullscreen view preserves a selection prop delivered before its first source prop")
var frames = MonitorRasterFrameCounters()
frames.record(timestamp: 1)
frames.record(timestamp: 1 + 1 / 120)
frames.record(timestamp: 1 + 1 / 120 + 1 / 60)
frames.record(timestamp: 1 + 1 / 120 + 1 / 60 + 0.03)
frames.record(timestamp: 1 + 1 / 120 + 1 / 60 + 0.03 + 0.12)
let frameSnapshot = frames.snapshot(active: false)
check(
  frameSnapshot["frames"] == 4 && frameSnapshot["within16_67Milliseconds"] == 2,
  "60/120 Hz intervals meet frame baseline")
check(
  frameSnapshot["within33_3Milliseconds"] == 3 && frameSnapshot["over100Milliseconds"] == 1,
  "Frame counters expose delayed callbacks and 100 ms stalls")
check(abs(frameSnapshot["maxMilliseconds"]! - 120) < 0.001, "Frame maximum retains a real hitch")
var disabledProfile = MonitorRasterLaunchProfile(enabled: false)
check(
  !disabledProfile.shouldStart(hasAcceptedBitmap: true, hasCursor: true) && !disabledProfile.started,
  "Normal launches cannot arm profiling even when inspecting a ready chart")
var launchProfile = MonitorRasterLaunchProfile(enabled: true)
check(
  !launchProfile.shouldStart(hasAcceptedBitmap: false, hasCursor: true),
  "A cursor cannot start profiling before the bitmap is accepted")
check(
  !launchProfile.shouldStart(hasAcceptedBitmap: true, hasCursor: false),
  "Initial chart loading without a real cursor cannot start profiling")
check(
  launchProfile.shouldStart(hasAcceptedBitmap: true, hasCursor: true),
  "First real cursor on an accepted bitmap starts the launch capture")
for _ in 0..<20 {
  check(
    !launchProfile.shouldStart(hasAcceptedBitmap: true, hasCursor: true),
    "Other lanes and repeated cursor updates cannot restart the launch capture")
}
let profileReport = MonitorRasterLaunchProfile.report(
  frames: frameSnapshot,
  raster: ["submitted": 0, "started": 0, "completed": 0, "bitmapBytes": 1_024], stopReason: 1)
check(
  profileReport["requestedMilliseconds"] == 30_000 && profileReport["stopReason"] == 1,
  "Launch report records the bounded duration and stop reason numerically")
check(
  profileReport["raster_started"] == 0 && profileReport["raster_bitmapBytes"] == 1_024,
  "Launch report includes raster counters alongside measured frame cadence")
let reportDirectory = FileManager.default.temporaryDirectory.appendingPathComponent(
  "powerlog-raster-report-\(UUID().uuidString)")
try MonitorRasterLaunchProfile.write(profileReport, to: reportDirectory)
let writtenReport = try Data(contentsOf: reportDirectory.appendingPathComponent(MonitorRasterLaunchProfile.filename))
let reportJSON = try JSONSerialization.jsonObject(with: writtenReport) as! [String: Any]
check(
  reportJSON.count == profileReport.count
    && reportJSON.values.allSatisfy { value in
      guard let number = value as? NSNumber else { return false }
      return CFGetTypeID(number) != CFBooleanGetTypeID() && number.doubleValue.isFinite
    }, "Saved launch report contains only aggregate numeric values, no identities, samples or nested payloads")
try FileManager.default.removeItem(at: reportDirectory)

func markerPacket(
  sequence: Int, epoch: Int = 1, source: String = "source", pending: Bool = false,
  values: [Double] = [20]
) -> String {
  let points: [[String: Any]] = values.enumerated().map { index, value in
    ["id": index == 0 ? "metric" : "other", "seconds": 5, "value": value]
  }
  let body: [String: Any] = [
    "sourceId": source, "cursorSeconds": 5, "referenceSeconds": NSNull(),
    "points": points, "references": [], "tailCandidates": [], "revision": "1", "distanceSelection": "auto",
    "generation": 0, "cursorPending": pending, "epoch": epoch, "sequence": sequence,
  ]
  return String(data: try! JSONSerialization.data(withJSONObject: body), encoding: .utf8)!
}
func targetPacket(
  sequence: Int, epoch: Int = 1, key: String = "accept-1", id: String = "metric",
  seconds: Double = 5, value: Double = 90, empty: Bool = false
) -> String {
  let points: [[String: Any]] = empty ? [] : [["key": key, "id": id, "seconds": seconds, "value": value]]
  return String(
    data: try! JSONSerialization.data(withJSONObject: ["epoch": epoch, "sequence": sequence, "points": points]),
    encoding: .utf8)!
}
func markerPresentation(sequence: Int, epoch: Int = 1, cursor: Double? = 5) -> MonitorRasterPresentation {
  MonitorRasterPresentation([0, 10, cursor == nil ? 0 : 1, cursor ?? 0, 0, 0, Double(epoch), Double(sequence)])!
}
let markerScene = try MonitorRasterScene.parse(
  packet(points: [[5, 20, true], [5, 90, false], [6, 30, false]]), sourceID: "source")
let markerVertices = MonitorRasterVertexIndex(scene: markerScene)
var markerState = MonitorRasterMarkerState()
markerState.synchronize(
  sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
  presentation: markerPresentation(sequence: 0, cursor: nil))
markerState.synchronize(
  sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
  presentation: markerPresentation(sequence: 1))
markerState.receive(selection: MonitorRasterSelection.parse(markerPacket(sequence: 1)), metrics: ["metric", "other"])
for sequence in 2...120 {
  markerState.synchronize(
    sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
    presentation: markerPresentation(sequence: sequence, cursor: Double(sequence) / 20))
  check(
    markerState.points.isEmpty,
    "Cursor movement never retains dots from an earlier position while JavaScript is delayed")
}
markerState.receive(
  selection: MonitorRasterSelection.parse(markerPacket(sequence: 120, pending: true, values: [])),
  metrics: ["metric", "other"])
check(markerState.points.isEmpty, "A pending empty packet cannot retain an old original")
markerState.receive(target: MonitorRasterSelectionTarget.parse(targetPacket(sequence: 120)))
check(
  markerState.previews(acceptanceID: "accept-1", vertices: markerVertices).first?.value == 90,
  "Immediate marker selects the accepted original peak among equal-time vertices")
check(
  markerState.previews(acceptanceID: "accept-2", vertices: markerVertices).isEmpty,
  "A new native acceptance rejects a target from an earlier bitmap even if logical scene data is identical")
markerState.receive(
  selection: MonitorRasterSelection.parse(markerPacket(sequence: 120, values: [])), metrics: ["metric", "other"])
check(
  markerState.points.isEmpty && markerState.previews(acceptanceID: "accept-1", vertices: markerVertices).isEmpty,
  "Ready-null clears retained dots and the same-sequence immediate target")
markerState.receive(
  selection: MonitorRasterSelection.parse(markerPacket(sequence: 120, pending: true)), metrics: ["metric", "other"])
check(markerState.points.isEmpty, "A late pending packet cannot undo a ready-null at the same sequence")
markerState.synchronize(
  sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
  presentation: markerPresentation(sequence: 121))
markerState.receive(target: MonitorRasterSelectionTarget.parse(targetPacket(sequence: 121)))
markerState.receive(
  selection: MonitorRasterSelection.parse(markerPacket(sequence: 120, values: [])), metrics: ["metric", "other"])
check(
  markerState.previews(acceptanceID: "accept-1", vertices: markerVertices).first?.value == 90,
  "An older ready-null cannot suppress a newer immediate target")
markerState.receive(target: MonitorRasterSelectionTarget.parse(targetPacket(sequence: 120, empty: true)))
check(
  markerState.previews(acceptanceID: "accept-1", vertices: markerVertices).first?.value == 90,
  "A stale empty-target envelope cannot clear a newer primary dot")
markerState.synchronize(
  sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
  presentation: markerPresentation(sequence: 122))
markerState.receive(target: MonitorRasterSelectionTarget.parse(targetPacket(sequence: 122, empty: true)))
check(
  markerState.previews(acceptanceID: "accept-1", vertices: markerVertices).isEmpty,
  "A current empty-target envelope clears its primary override")
markerState.synchronize(
  sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
  presentation: markerPresentation(sequence: 125, cursor: nil))
markerState.synchronize(
  sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
  presentation: markerPresentation(sequence: 123, cursor: nil))
markerState.synchronize(
  sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
  presentation: markerPresentation(sequence: 126))
markerState.receive(selection: MonitorRasterSelection.parse(markerPacket(sequence: 124)), metrics: ["metric"])
markerState.receive(target: MonitorRasterSelectionTarget.parse(targetPacket(sequence: 125)))
check(
  markerState.points.isEmpty && markerState.previews(acceptanceID: "accept-1", vertices: markerVertices).isEmpty,
  "Clear watermark never decreases; delayed pre-clear packets cannot resurrect dots after retap")
markerState.receive(selection: MonitorRasterSelection.parse(markerPacket(sequence: 126)), metrics: ["metric"])
check(markerState.points.first?.value == 20, "A post-clear exact result can populate the new selection")
markerState.synchronize(
  sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
  presentation: markerPresentation(sequence: 1, epoch: 2))
markerState.receive(selection: MonitorRasterSelection.parse(markerPacket(sequence: 999)), metrics: ["metric"])
markerState.receive(target: MonitorRasterSelectionTarget.parse(targetPacket(sequence: 999)))
check(
  markerState.points.isEmpty && markerState.previews(acceptanceID: "accept-1", vertices: markerVertices).isEmpty,
  "A new interaction epoch rejects older-epoch payloads regardless of their sequence")
markerState.synchronize(
  sourceID: "another-source", revision: "1", distanceSelection: "auto", generation: 0,
  presentation: markerPresentation(sequence: 2, epoch: 2))
markerState.receive(selection: MonitorRasterSelection.parse(markerPacket(sequence: 2, epoch: 2)), metrics: ["metric"])
check(markerState.points.isEmpty, "Source change clears cached originals and rejects another source's selection")

var pairedBody = try JSONSerialization.jsonObject(with: packet().data(using: .utf8)!) as! [String: Any]
pairedBody["series"] = [
  ["id": "metric", "color": "#ff0000", "step": false, "points": [[5, 20, true], [6, 30, false]]],
  ["id": "other", "color": "#0000ff", "step": false, "points": [[5, 40, true], [6, 50, false]]],
]
let pairedScene = try MonitorRasterScene.parse(
  String(data: try JSONSerialization.data(withJSONObject: pairedBody), encoding: .utf8)!, sourceID: "source")
let pairedVertices = MonitorRasterVertexIndex(scene: pairedScene)
var pairedMarkers = MonitorRasterMarkerState()
pairedMarkers.synchronize(
  sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
  presentation: markerPresentation(sequence: 1))
pairedMarkers.receive(
  selection: MonitorRasterSelection.parse(markerPacket(sequence: 1, values: [20, 40])), metrics: ["metric", "other"])
pairedMarkers.synchronize(
  sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
  presentation: markerPresentation(sequence: 2, cursor: 6))
let pairedTarget = """
  {"epoch":1,"sequence":2,"points":[
    {"key":"accept-1","id":"metric","seconds":6,"value":30},
    {"key":"accept-1","id":"other","seconds":6,"value":50}]}
  """
pairedMarkers.receive(target: MonitorRasterSelectionTarget.parse(pairedTarget))
check(pairedMarkers.points.isEmpty, "Neither metric keeps its previous-position dot during a horizontal scrub")
let movingPair = pairedMarkers.previews(acceptanceID: "accept-1", vertices: pairedVertices)
check(
  movingPair.count == 2 && movingPair.allSatisfy { $0.seconds == 6 } && movingPair.map(\.value) == [30, 50],
  "Both metrics move immediately, without waiting for a JavaScript lookup")
pairedMarkers.receive(
  selection: MonitorRasterSelection.parse(markerPacket(sequence: 1, values: [20, 40])), metrics: ["metric", "other"])
check(
  pairedMarkers.points.isEmpty
    && pairedMarkers.previews(acceptanceID: "accept-1", vertices: pairedVertices).count == 2,
  "A delayed old result cannot pin either metric behind the cursor")
pairedMarkers.receive(
  selection: MonitorRasterSelection.parse(markerPacket(sequence: 2, values: [])), metrics: ["metric", "other"])
check(
  pairedMarkers.points.isEmpty && pairedMarkers.previews(acceptanceID: "accept-1", vertices: pairedVertices).isEmpty,
  "A confirmed empty lookup clears both previews at the current position")

for bad in [
  targetPacket(sequence: 1, value: 80), targetPacket(sequence: 1, seconds: 5.1),
  targetPacket(sequence: 1, id: "unknown"),
] {
  var state = MonitorRasterMarkerState()
  state.synchronize(
    sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
    presentation: markerPresentation(sequence: 1))
  state.receive(target: MonitorRasterSelectionTarget.parse(bad))
  check(
    state.previews(acceptanceID: "accept-1", vertices: markerVertices).isEmpty,
    "Only actual accepted metric/time/value vertices may become an immediate marker")
}
check(
  MonitorRasterSelectionTarget.parse("{\"epoch\":1,\"sequence\":1}") == nil,
  "Missing points envelope is malformed, not an implicit clear")
check(MonitorRasterSelectionTarget.parse(targetPacket(sequence: -1)) == nil, "Negative target sequence rejected")
check(
  MonitorRasterSelectionTarget.parse(String(repeating: " ", count: 32_769)) == nil,
  "Target payload bound rejects oversized strings")
check(MonitorRasterPresentation([0, 10, 1, 5, 0, 0, 1, 1.5]) == nil, "Fractional sequence rejected")

func applyMarkerProps(_ props: MonitorRasterProps, state: inout MonitorRasterMarkerState) {
  state.synchronize(
    sourceID: props.sourceID, revision: "1", distanceSelection: "auto", generation: 0, presentation: props.presentation)
  state.receive(selection: props.selection, metrics: ["metric", "other"])
  state.receive(target: props.selectionTarget)
}
for order in propOrders(["presentation", "selection", "target"]) {
  var props = MonitorRasterProps()
  var state = MonitorRasterMarkerState()
  _ = props.setSourceID("source")
  _ = props.setPresentation([0, 10, 0, 0, 0, 0, 1, 0])
  applyMarkerProps(props, state: &state)
  for name in order {
    if name == "presentation" {
      _ = props.setPresentation([0, 10, 1, 5, 0, 0, 1, 1])
    } else if name == "selection" {
      props.setSelection(markerPacket(sequence: 1, values: [20, 40]))
    } else {
      props.setSelectionTarget(targetPacket(sequence: 1))
    }
    applyMarkerProps(props, state: &state)
  }
  check(
    state.points.count == 2 && state.previews(acceptanceID: "accept-1", vertices: markerVertices).isEmpty,
    "Presentation, exact selection and immediate target survive setter order \(order)")
  _ = props.setPresentation([0, 10, 0, 0, 0, 0, 1, 2])
  applyMarkerProps(props, state: &state)
  for name in order {
    if name == "presentation" {
      _ = props.setPresentation([0, 10, 1, 6, 0, 0, 1, 3])
    } else if name == "selection" {
      props.setSelection(markerPacket(sequence: 1))
    } else {
      props.setSelectionTarget(targetPacket(sequence: 1))
    }
    applyMarkerProps(props, state: &state)
  }
  check(
    state.points.isEmpty && state.previews(acceptanceID: "accept-1", vertices: markerVertices).isEmpty,
    "Clear/retap rejects delayed selection and target for setter order \(order)")
}
for order in propOrders(["source", "presentation", "selection", "target"]) {
  var props = MonitorRasterProps()
  var state = MonitorRasterMarkerState()
  _ = props.setSourceID("old-source")
  _ = props.setPresentation([0, 10, 1, 5, 0, 0, 1, 5])
  props.setSelection(markerPacket(sequence: 5, source: "old-source"))
  props.setSelectionTarget(targetPacket(sequence: 5, key: "old-acceptance"))
  applyMarkerProps(props, state: &state)
  for name in order {
    switch name {
    case "source": _ = props.setSourceID("source")
    case "presentation": _ = props.setPresentation([0, 10, 1, 5, 0, 0, 2, 1])
    case "selection": props.setSelection(markerPacket(sequence: 1, epoch: 2))
    default: props.setSelectionTarget(targetPacket(sequence: 1, epoch: 2))
    }
    applyMarkerProps(props, state: &state)
  }
  check(
    state.points.first?.value == 20 && state.previews(acceptanceID: "accept-1", vertices: markerVertices).isEmpty,
    "Source/epoch transition retains new matching payloads for arbitrary prop order \(order)")
  props.setSelection(markerPacket(sequence: 999, source: "old-source"))
  props.setSelectionTarget(targetPacket(sequence: 999, empty: true))
  _ = props.setPresentation([0, 10, 0, 0, 0, 0, 1, 999])
  applyMarkerProps(props, state: &state)
  check(
    state.points.first?.value == 20 && state.previews(acceptanceID: "accept-1", vertices: markerVertices).isEmpty,
    "Late old-epoch clears and selection updates cannot overwrite the new source for prop order \(order)")
}

var resetWatermark = MonitorRasterMarkerState()
resetWatermark.synchronize(
  sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
  presentation: markerPresentation(sequence: 500, epoch: 7, cursor: nil))
resetWatermark.synchronize(
  sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
  presentation: markerPresentation(sequence: 0, epoch: 8))
resetWatermark.receive(
  selection: MonitorRasterSelection.parse(markerPacket(sequence: 0, epoch: 8)), metrics: ["metric"])
resetWatermark.receive(target: MonitorRasterSelectionTarget.parse(targetPacket(sequence: 0, epoch: 8)))
check(
  resetWatermark.points.first?.value == 20
    && resetWatermark.previews(acceptanceID: "accept-1", vertices: markerVertices).isEmpty,
  "New epoch accepts FIRST active presentation and original markers at sequence zero despite an old clear watermark")
resetWatermark.receive(target: MonitorRasterSelectionTarget.parse(targetPacket(sequence: 999, epoch: 7, empty: true)))
check(
  resetWatermark.previews(acceptanceID: "accept-1", vertices: markerVertices).isEmpty,
  "Old-epoch late empty target cannot clear a programmatic sequence-zero selection")

var coverageBody =
  try JSONSerialization.jsonObject(
    with: packet(points: [[2, 20, true], [3, 30, false], [7, 70, false], [8, 80, false]]).data(using: .utf8)!)
  as! [String: Any]
coverageBody["start"] = 3
coverageBody["end"] = 7
let coverageJSON = String(data: try JSONSerialization.data(withJSONObject: coverageBody), encoding: .utf8)!
let coverageScene = try MonitorRasterScene.parse(coverageJSON, sourceID: "source")
let coverageVertices = MonitorRasterVertexIndex(scene: coverageScene)
check(
  !coverageVertices.contains(metric: "metric", seconds: 2, value: 20)
    && !coverageVertices.contains(metric: "metric", seconds: 8, value: 80),
  "Predecessor and successor used only for clipped line crossings cannot authorize immediate dots")
check(
  coverageVertices.contains(metric: "metric", seconds: 3, value: 30)
    && coverageVertices.contains(metric: "metric", seconds: 7, value: 70),
  "Original vertices on both accepted coverage boundaries remain selectable")
var outsideCoverage = MonitorRasterMarkerState()
outsideCoverage.synchronize(
  sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
  presentation: markerPresentation(sequence: 1, cursor: 8))
outsideCoverage.receive(target: MonitorRasterSelectionTarget.parse(targetPacket(sequence: 1, seconds: 8, value: 80)))
check(
  outsideCoverage.previews(acceptanceID: "accept-1", vertices: coverageVertices).isEmpty,
  "Panning into an uncovered strip cannot snap to an off-bitmap successor even when it is inside the current viewport")

for order in propOrders(["scene", "sceneKey"]) {
  var props = MonitorRasterProps()
  for name in order {
    if name == "scene" { _ = props.setScene(packet(key: "attempt-key")) } else { _ = props.setSceneKey("attempt-key") }
  }
  check(
    props.hasScene && props.sceneKey == "attempt-key"
      && (try? MonitorRasterScene.parse(props.sceneJSON, sourceID: "source").key) == props.sceneKey,
    "Scalar scene key and packet survive independent prop order \(order)")
}
let attempted = MonitorRasterRenderAttempt(
  key: "bad-attempt", sourceID: "source", generation: 7, dimensions: dimensions)
let failedWorker = MonitorRasterWorker(completionQueue: callbackQueue)
let failedDelivered = DispatchSemaphore(value: 0)
var failureStatus: [String: Any]?
failedWorker.submit(owner: UUID(), json: "{", sourceID: "source", dimensions: dimensions, requestKey: attempted.key) {
  result in
  if case .failure(let error) = result {
    failureStatus = attempted.status(
      viewID: "view-test", error: error)
  }
  failedDelivered.signal()
}
wait(failedDelivered, "Malformed production worker packet reports failure")
check(
  failureStatus?["status"] as? String == "error" && failureStatus?["key"] as? String == "bad-attempt"
    && failureStatus?["requestGeneration"] as? Int == 7 && failureStatus?["acceptanceId"] == nil,
  "Current malformed-render error carries the attempted logical key/generation and cannot pretend to be an accepted bitmap"
)
let renderAdmission = MonitorInspectionGate()
let failedTicket = try renderAdmission.begin(source: "source", revision: "1")
check(renderAdmission.accepts(failedTicket), "Current failed worker response is admitted by the core")
_ = try renderAdmission.begin(source: "source", revision: "1")
check(!renderAdmission.accepts(failedTicket), "An older failed attempt is rejected when the same key is resubmitted")
let resetTicket = try renderAdmission.begin(source: "source", revision: "1")
_ = try renderAdmission.begin(source: "new-source", revision: "1")
check(!renderAdmission.accepts(resetTicket), "Late errors cannot cross a source reset")
let cancelledPendingScene = try renderAdmission.cancel()
check(cancelledPendingScene, "Cancelling a pending scene requests a replacement even with a visible bitmap")
let cancelledIdleScene = try renderAdmission.cancel()
check(!cancelledIdleScene, "An idle presentation change does not schedule another bitmap")
let replacementTicket = try renderAdmission.begin(source: "source", revision: "1", epoch: 1)
check(!renderAdmission.complete(resetTicket), "A cancelled completion cannot settle the replacement job")
check(renderAdmission.complete(replacementTicket), "The current scene settles after core acceptance")
let cancelledCompletedScene = try renderAdmission.cancel()
check(!cancelledCompletedScene, "A completed scene needs no replacement on presentation movement")
let readyMetadata = attempted.status(viewID: "view-test")
check(
  readyMetadata["viewId"] as? String == "view-test" && readyMetadata["acceptanceGeneration"] as? Int == 7
    && readyMetadata["acceptanceId"] as? String == "view-test:7",
  "Ready metadata exposes stable owner plus numeric monotonic acceptance generation")
let laterAttempt = MonitorRasterRenderAttempt(
  key: "bad-attempt", sourceID: "source", generation: 8, dimensions: dimensions)
check(
  laterAttempt.acceptanceID(viewID: "view-test") != attempted.acceptanceID(viewID: "view-test")
    && laterAttempt.acceptanceID(viewID: "other-view") != laterAttempt.acceptanceID(viewID: "view-test"),
  "Repeated logical scene keys and remounted native owners still produce distinct acceptance identities")
for (field, value) in [
  ("revision", "old" as Any), ("distanceSelection", "controller" as Any), ("generation", 1 as Any),
] {
  var state = MonitorRasterMarkerState()
  state.synchronize(
    sourceID: "source", revision: "1", distanceSelection: "auto", generation: 0,
    presentation: markerPresentation(sequence: 1))
  var payload = try JSONSerialization.jsonObject(with: markerPacket(sequence: 1).data(using: .utf8)!) as! [String: Any]
  payload[field] = value
  let packet = String(data: try JSONSerialization.data(withJSONObject: payload), encoding: .utf8)!
  state.receive(selection: MonitorRasterSelection.parse(packet), metrics: ["metric"])
  check(state.points.isEmpty, "Core marker acceptance rejects a stale \(field) independently")
}
let contextHeader = String(
  data: try JSONSerialization.data(
    withJSONObject: [
      "logical-key", 116, 124, 1,
      ["revision": "1", "distanceSelection": "auto", "generation": 1],
    ] as [Any]), encoding: .utf8)!
var headerProps = MonitorRasterProps()
_ = headerProps.setSceneKey(contextHeader)
check(
  headerProps.queryContext?.generation == 1 && headerProps.queryContext?.revision == "1",
  "Scalar scene header supplies the core query context without another view property")
check(MonitorRasterQueryContext.fromSceneKey("{invalid") == nil, "Malformed scalar context header is refused")
let packetContext = MonitorRasterQueryContext(revision: "1", distanceSelection: "auto", generation: 1)
let currentPacket = try packetContext.ticket(sourceID: "source", epoch: 2, sequence: 0)
let oldEpochPacket = try packetContext.ticket(sourceID: "source", epoch: 1, sequence: 0)
check(
  !InspectionAcceptance.shared.accepts(current: currentPacket, result: oldEpochPacket),
  "Scene packet tickets reject an epoch-only reset at unchanged generation")
let mismatchDelivered = DispatchSemaphore(value: 0)
var mismatchedAccepted = false
failedWorker.submit(
  owner: UUID(), json: packet(key: "old-key"), sourceID: "source", dimensions: dimensions, requestKey: "new-key"
) { result in
  if case .success = result { mismatchedAccepted = true }
  mismatchDelivered.signal()
}
wait(mismatchDelivered, "Worker validates scalar key against decoded packet")
check(
  !mismatchedAccepted && failedWorker.diagnostics()["started"] == 0,
  "A packet from a different prop generation is rejected before allocating or rasterizing")
print(
  "Monitor raster checks passed: \(assertions) assertions; pixel rendering, bounds, coalescing and stale completions.")

let schedulingAssertionsStart = assertions
let schedulingWorker = MonitorRasterWorker(
  completionQueue: DispatchQueue(label: "raster.scheduling.test"),
  renderer: { scene, size, cancelled in
    Thread.sleep(forTimeInterval: 0.030)
    return try MonitorRasterRenderer.draw(scene: scene, dimensions: size, cancelled: cancelled)
  })
let scheduling = MonitorRasterViewScheduling(worker: schedulingWorker)
let schedulingKey = String(
  data: try JSONSerialization.data(withJSONObject: [
    "test", 116, 124, 1, ["revision": "1", "distanceSelection": "auto", "generation": 0],
  ]), encoding: .utf8)!
_ = scheduling.setSourceID("source")
_ = scheduling.setSceneKey(schedulingKey)
_ = scheduling.setScene(packet(key: schedulingKey))
scheduling.setAttached(true)
let installed = DispatchSemaphore(value: 0)
scheduling.render(dimensions: dimensions) { _, result in if case .success = result { installed.signal() } }
var installedDuringMovement = false
for sequence in 1...12 {
  Thread.sleep(forTimeInterval: 0.016)
  _ = scheduling.setPresentation([
    Double(sequence) / 100, 10 + Double(sequence) / 100, 1, 5 + Double(sequence) / 100, 0, 0, Double(sequence / 4),
    Double(sequence),
  ])
  scheduling.render(dimensions: dimensions) { _, result in if case .success = result { installed.signal() } }
  if installed.wait(timeout: .now()) == .success { installedDuringMovement = true }
}
check(
  installedDuringMovement,
  "MonitorRasterView installs 30 ms geometry during continuous 16 ms presentation updates")
check(schedulingWorker.diagnostics()["submitted"] == 1, "MonitorRasterView presentation changes do not submit geometry")

var staleVertices = 0
MonitorRasterVertexIndex.buildObserverForTesting = { staleVertices += 1 }
let contextWorker = MonitorRasterWorker(completionQueue: DispatchQueue(label: "raster.stale.test"))
let staleDone = DispatchSemaphore(value: 0)
let expectedContext = MonitorRasterQueryContext(revision: "2", distanceSelection: "auto", generation: 0)
let expectedTicket = try MonitorInspectionGate().begin(source: "source", revision: "2")
contextWorker.submit(
  owner: UUID(), json: packet(key: "expected"), sourceID: "source", dimensions: dimensions, requestKey: "expected",
  requestTicket: expectedTicket, queryContext: expectedContext
) { result in
  if case .failure = result { staleDone.signal() }
}
check(
  staleDone.wait(timeout: .now() + 2) == .success, "Expected key with stale body queryContext is rejected by the worker"
)
check(
  contextWorker.diagnostics()["started"] == 0 && contextWorker.diagnostics()["peakBytes"] == 0,
  "Core acceptance precedes vertices, bitmap reservation and drawing")

MonitorRasterVertexIndex.buildObserverForTesting = nil
check(staleVertices == 0, "Stale packet context is rejected before vertex construction")

for change in ["source", "revision", "selection"] {
  let callbackQueue = DispatchQueue(label: "raster.context." + change)
  let entered = DispatchSemaphore(value: 0)
  let release = DispatchSemaphore(value: 0)
  var ordinal = 0
  let worker = MonitorRasterWorker(
    completionQueue: callbackQueue,
    renderer: { scene, dimensions, cancelled in
      ordinal += 1
      if ordinal == 1 {
        entered.signal()
        _ = release.wait(timeout: .now() + 5)
      }
      return try MonitorRasterRenderer.draw(scene: scene, dimensions: dimensions, cancelled: cancelled)
    })
  let view = MonitorRasterViewScheduling(worker: worker)
  _ = view.setSourceID("source")
  _ = view.setSceneKey(schedulingKey)
  _ = view.setScene(packet(key: schedulingKey))
  view.setAttached(true)
  var acceptedKeys: [String] = []
  let ready = DispatchSemaphore(value: 0)
  let completion: (MonitorRasterRenderAttempt, Result<MonitorRasterResult, Error>) -> Void = { attempt, result in
    if case .success = result {
      acceptedKeys.append(attempt.key)
      ready.signal()
    }
  }
  view.render(dimensions: dimensions, completion: completion)
  wait(entered, "View geometry starts before " + change + " changes")
  var nextKey = schedulingKey
  if change == "source" {
    _ = view.setSourceID("other")
    _ = view.setSourceID("source")
  } else {
    let revision = change == "revision" ? "2" : "1"
    let selection = change == "selection" ? "controller" : "auto"
    nextKey = String(
      data: try JSONSerialization.data(withJSONObject: [
        "test", 116, 124, 1, ["revision": revision, "distanceSelection": selection, "generation": 0],
      ]), encoding: .utf8)!
    _ = view.setSceneKey(nextKey)
    _ = view.setScene(packet(key: nextKey, revision: revision, distanceSelection: selection))
  }
  release.signal()
  eventually("Cancelled view request drains", { worker.diagnostics()["running"] == 0 })
  callbackQueue.sync {}
  check(acceptedKeys.isEmpty, "Relevant view context changes reject earlier geometry before another layout: " + change)
  check(view.render(dimensions: dimensions, completion: completion), "Changed view context submits fresh geometry")
  wait(ready, "Fresh view geometry installs after " + change)
  check(acceptedKeys == [nextKey], "Only the new view geometry is accepted after " + change)
}
print(
  "MonitorRasterView scheduling and pre-draw acceptance: \(assertions - schedulingAssertionsStart) assertions passed")
