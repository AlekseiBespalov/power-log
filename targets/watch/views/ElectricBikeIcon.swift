import SwiftUI

struct ElectricBikeIcon: View {
  var body: some View {
    Canvas { context, size in
      context.scaleBy(x: size.width / 24, y: size.height / 24)
      var bike = Path()
      bike.addEllipse(in: CGRect(x: 1, y: 13.4, width: 7.6, height: 7.6))
      bike.addEllipse(in: CGRect(x: 15.4, y: 13.4, width: 7.6, height: 7.6))
      bike.move(to: CGPoint(x: 4.8, y: 17.2))
      for point in [CGPoint(x: 8.8, y: 11.7), CGPoint(x: 16.4, y: 9.9), CGPoint(x: 11.5, y: 17.2)] {
        bike.addLine(to: point)
      }
      bike.closeSubpath()
      bike.move(to: CGPoint(x: 11.5, y: 17.2))
      bike.addLine(to: CGPoint(x: 8, y: 9.5))
      bike.move(to: CGPoint(x: 6.5, y: 9.5))
      bike.addLine(to: CGPoint(x: 9.5, y: 9.5))
      bike.move(to: CGPoint(x: 19.2, y: 17.2))
      bike.addLine(to: CGPoint(x: 15.6, y: 7))
      bike.addLine(to: CGPoint(x: 18.4, y: 7))
      context.stroke(bike, with: .foreground, style: StrokeStyle(lineWidth: 1.7, lineCap: .round, lineJoin: .round))
      var battery = Path()
      battery.move(to: CGPoint(x: 12.5, y: 15.7))
      battery.addLine(to: CGPoint(x: 15.2, y: 11.7))
      context.stroke(battery, with: .foreground, style: StrokeStyle(lineWidth: 2.8, lineCap: .round))
      var bolt = Path()
      bolt.move(to: CGPoint(x: 11.3, y: 1.5))
      for point in [
        CGPoint(x: 8.3, y: 5.5), CGPoint(x: 10.5, y: 5.5), CGPoint(x: 9.4, y: 8.2),
        CGPoint(x: 13.7, y: 3.8), CGPoint(x: 11.3, y: 3.8), CGPoint(x: 12.4, y: 1.5),
      ] {
        bolt.addLine(to: point)
      }
      bolt.closeSubpath()
      context.fill(bolt, with: .foreground)
    }
  }
}
