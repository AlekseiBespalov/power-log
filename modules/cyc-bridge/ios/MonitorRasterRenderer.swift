import Foundation
import CoreGraphics

enum MonitorRasterRenderer {
  static func draw(scene: MonitorRasterScene, dimensions: MonitorRasterDimensions,
                   cancelled: () -> Bool) throws -> CGImage {
    let pixels = try dimensions.pixels(laneCount: scene.laneCount)
    guard !cancelled() else { throw MonitorRasterError.cancelled }
    guard let context = CGContext(data: nil, width: pixels.width, height: pixels.height,
      bitsPerComponent: 8, bytesPerRow: pixels.width * 4, space: CGColorSpaceCreateDeviceRGB(),
      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
      throw MonitorRasterError.invalid("Chart bitmap allocation failed")
    }
    // Match the UIKit top-left coordinates while storing only the clipped plot area.
    context.translateBy(x: 0, y: CGFloat(pixels.height))
    context.scaleBy(x: CGFloat(pixels.width) / dimensions.plotWidth,
                    y: -CGFloat(pixels.height) / dimensions.plotHeight)
    context.clip(to: CGRect(x: 0, y: 0, width: dimensions.plotWidth, height: dimensions.plotHeight))
    context.setLineWidth(1.8)
    context.setLineJoin(.round)
    context.setLineCap(.round)
    let clip = CGRect(x: -2, y: -2, width: dimensions.plotWidth + 4, height: dimensions.plotHeight + 4)
    for series in scene.series {
      guard !cancelled() else { throw MonitorRasterError.cancelled }
      let color = MonitorRasterScene.color(series.color)!
      context.setStrokeColor(color); context.setFillColor(color)
      context.beginPath()
      var previous: CGPoint?
      var lastDrawn: CGPoint?
      var runCount = 0
      var singletons: [CGPoint] = []
      for (index, point) in series.points.enumerated() {
        if index % 256 == 0 && cancelled() { throw MonitorRasterError.cancelled }
        let position = CGPoint(
          x: (point.seconds - scene.start) / (scene.end - scene.start) * dimensions.plotWidth,
          y: y(point.value, scene: scene, height: dimensions.height))
        if previous == nil || point.startsSegment {
          if runCount == 1, let previous { singletons.append(previous) }
          lastDrawn = nil; runCount = 1
        } else if let previous {
          if series.step {
            let corner = CGPoint(x: position.x, y: previous.y)
            appendLine(from: previous, to: corner, context: context, clip: clip, lastDrawn: &lastDrawn)
            appendLine(from: corner, to: position, context: context, clip: clip, lastDrawn: &lastDrawn)
          } else { appendLine(from: previous, to: position, context: context, clip: clip, lastDrawn: &lastDrawn) }
          runCount += 1
        }
        previous = position
      }
      if runCount == 1, let previous { singletons.append(previous) }
      // Direct stroke on a bounded bitmap; no stroked-outline copy or main-thread path rasterization.
      context.strokePath()
      for point in singletons where clip.contains(point) {
        context.fillEllipse(in: CGRect(x: point.x - 1.8, y: point.y - 1.8, width: 3.6, height: 3.6))
      }
    }
    guard !cancelled() else { throw MonitorRasterError.cancelled }
    guard let image = context.makeImage() else { throw MonitorRasterError.invalid("Chart bitmap creation failed") }
    return image
  }

  static func y(_ value: Double, scene: MonitorRasterScene, height: Double) -> Double {
    8 + (scene.max - value) / (scene.max - scene.min) * (height - 34)
  }

  /// Keep huge predecessor/successor coordinates out of CoreGraphics, preserving exact edge crossings.
  /// The two-point margin puts clipping caps beyond the actual bitmap clip.
  private static func appendLine(from a: CGPoint, to b: CGPoint, context: CGContext,
                                 clip: CGRect, lastDrawn: inout CGPoint?) {
    let dx = b.x - a.x, dy = b.y - a.y
    var lower: CGFloat = 0, upper: CGFloat = 1
    let edges = [(-dx, a.x - clip.minX), (dx, clip.maxX - a.x),
                 (-dy, a.y - clip.minY), (dy, clip.maxY - a.y)]
    for (direction, distance) in edges {
      if direction == 0 {
        if distance < 0 { lastDrawn = nil; return }
      } else {
        let fraction = distance / direction
        if direction < 0 { lower = max(lower, fraction) } else { upper = min(upper, fraction) }
        if lower > upper { lastDrawn = nil; return }
      }
    }
    let start = CGPoint(x: a.x + lower * dx, y: a.y + lower * dy)
    let end = CGPoint(x: a.x + upper * dx, y: a.y + upper * dy)
    if lastDrawn != start { context.move(to: start) }
    context.addLine(to: end); lastDrawn = end
  }
}
