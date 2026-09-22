import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

// File-format normalization only: remove the Simulator's unused alpha channel.
// Refuse transparency and verify decoded pixels before replacing each PNG.
guard CommandLine.arguments.count > 1 else { fatalError("Pass screenshot PNG paths") }
func pixels(_ image: CGImage) -> Data {
  var result = Data(count: image.width * image.height * 4)
  result.withUnsafeMutableBytes { bytes in
    let context = CGContext(data: bytes.baseAddress, width: image.width, height: image.height,
      bitsPerComponent: 8, bytesPerRow: image.width * 4, space: image.colorSpace!,
      bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue)!
    context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
  }
  return result
}
for path in CommandLine.arguments.dropFirst() {
  let url = URL(fileURLWithPath: path)
  let source = CGImageSourceCreateWithURL(url as CFURL, nil)!
  let image = CGImageSourceCreateImageAtIndex(source, 0, nil)!
  precondition(image.width == 1320 && image.height == 2868)
  let original = pixels(image)
  precondition(stride(from: 3, to: original.count, by: 4).allSatisfy { original[$0] == 255 }, "Unexpected transparency")
  let rgb = CGImage(width: image.width, height: image.height, bitsPerComponent: 8, bitsPerPixel: 32,
    bytesPerRow: image.width * 4, space: image.colorSpace!,
    bitmapInfo: CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.noneSkipLast.rawValue),
    provider: CGDataProvider(data: original as CFData)!, decode: nil, shouldInterpolate: false,
    intent: .defaultIntent)!
  let encoded = NSMutableData()
  let destination = CGImageDestinationCreateWithData(encoded, UTType.png.identifier as CFString, 1, nil)!
  CGImageDestinationAddImage(destination, rgb, nil)
  precondition(CGImageDestinationFinalize(destination))
  let check = CGImageSourceCreateImageAtIndex(CGImageSourceCreateWithData(encoded, nil)!, 0, nil)!
  precondition(pixels(check) == original, "Export changed rendered pixels")
  try (encoded as Data).write(to: url, options: .atomic)
  print("RGB export verified: \(url.lastPathComponent)")
}
