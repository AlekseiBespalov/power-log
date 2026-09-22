import AppKit
import CoreText
import ImageIO
import UniformTypeIdentifiers

// Run from the project root: swift scripts/generate-app-icon.swift
// Reuse AppShell's ϟ glyph, system weight 800, and Power Log's orange/dark colors.
// Keep the source square and opaque; iOS and watchOS apply their own icon masks.
let size = 1024
let font = NSFont.systemFont(ofSize: 800, weight: .heavy)
let line = CTLineCreateWithAttributedString(NSAttributedString(string: "ϟ", attributes: [.font: font]))
let mark = CGMutablePath()
for run in CTLineGetGlyphRuns(line) as! [CTRun] {
    let attributes = CTRunGetAttributes(run) as NSDictionary
    let runFont = attributes[kCTFontAttributeName] as! CTFont
    let count = CTRunGetGlyphCount(run)
    var glyphs = [CGGlyph](repeating: 0, count: count)
    var positions = [CGPoint](repeating: .zero, count: count)
    CTRunGetGlyphs(run, CFRange(location: 0, length: count), &glyphs)
    CTRunGetPositions(run, CFRange(location: 0, length: count), &positions)
    for index in 0..<count {
        if let path = CTFontCreatePathForGlyph(runFont, glyphs[index], nil) {
            mark.addPath(path, transform: CGAffineTransform(translationX: positions[index].x, y: positions[index].y))
        }
    }
}
precondition(!mark.isEmpty, "The Power Log glyph must be available")
let bounds = mark.boundingBoxOfPath
let scale = 640 / bounds.height
let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4,
                        space: colorSpace, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
context.setFillColor(CGColor(colorSpace: colorSpace, components: [1, 135.0 / 255, 60.0 / 255, 1])!)
context.fill(CGRect(x: 0, y: 0, width: size, height: size))
context.translateBy(x: 512 - bounds.midX * scale, y: 512 - bounds.midY * scale)
context.scaleBy(x: scale, y: scale)
context.addPath(mark)
context.setFillColor(CGColor(colorSpace: colorSpace, components: [11.0 / 255, 13.0 / 255, 16.0 / 255, 1])!)
context.fillPath()
let output = URL(fileURLWithPath: "assets/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
let destination = CGImageDestinationCreateWithURL(output as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(destination, context.makeImage()!, nil)
precondition(CGImageDestinationFinalize(destination), "Could not write app icon")
print("Wrote \(output.path)")
