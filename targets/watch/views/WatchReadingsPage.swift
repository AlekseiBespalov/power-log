import SwiftUI

struct WatchReadingsPage: View {
  @ObservedObject var model: WatchRideModel
  @Environment(\.isLuminanceReduced) private var luminanceReduced

  private static let power = Color(red: 1, green: 0.7, blue: 0)
  private static let cadence = Color(red: 0.3, green: 0.8, blue: 1)
  private static let heart = Color(red: 1, green: 0.3, blue: 0.4)

  private var dimmed: Bool { luminanceReduced || model.state.paused }

  var body: some View {
    VStack(spacing: 9) {
      WatchTimerRow(model: model).padding(.horizontal, 4)
      ScrollView(showsIndicators: false) {
        VStack(spacing: 9) {
          TimelineView(.periodic(from: .now, by: luminanceReduced ? 60.0 : 1.0)) { context in
            let readings = model.readings(at: context.date)
            HStack(alignment: .firstTextBaseline) {
              metric(readings.power, label: "Rider power", unit: "W", color: Self.power)
              Spacer()
              metric(readings.cadence, label: "Cadence", unit: "rpm", color: Self.cadence)
            }
            Text(readings.controller).font(.caption2)
              .foregroundStyle(readings.controllerWarning ? Color.orange : Color.secondary)
            HStack {
              Label(readings.heart, systemImage: "heart.fill")
                .foregroundStyle(dimmed ? AnyShapeStyle(.secondary) : AnyShapeStyle(Self.heart))
                .accessibilityLabel("Heart rate, \(readings.heart) bpm")
              Spacer()
              VStack(alignment: .trailing, spacing: 2) {
                Text("\(readings.distance) \(readings.distanceSymbol)")
                  .accessibilityLabel("Distance, \(readings.distance) \(readings.distanceSymbol)")
                Text(readings.distanceSource).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                  .minimumScaleFactor(0.8)
              }
            }.font(.system(.body, design: .default).weight(.semibold)).monospacedDigit()
            HStack {
              Label(readings.gps, systemImage: "location.fill")
              Spacer()
              if let lap = readings.lap { Text(lap) }
            }.font(.caption2).foregroundStyle(.secondary)
          }
        }
        .padding(.horizontal, 4)
      }
    }
  }

  private func metric(_ value: String, label: String, unit: String, color: Color) -> some View {
    VStack(alignment: .leading, spacing: 0) {
      Text(value).font(.system(size: 36, weight: dimmed ? .semibold : .bold, design: .default))
        .monospacedDigit().minimumScaleFactor(0.6).lineLimit(1)
        .foregroundStyle(dimmed ? AnyShapeStyle(.secondary) : AnyShapeStyle(color))
      Text(unit).font(.caption2).foregroundStyle(.secondary)
    }.frame(maxWidth: .infinity, alignment: .leading)
      .accessibilityElement(children: .ignore)
      .accessibilityLabel("\(label), \(value) \(unit)")
  }
}
