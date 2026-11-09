import ActivityKit
import SwiftUI
import WidgetKit
#if DEBUG
  import OSLog
  private let activityRenderLogger = Logger(subsystem: "app.powerlog.activity.qualification", category: "Render")
#endif

@main
struct PowerLogRideWidgetBundle: WidgetBundle {
  var body: some Widget { PowerLogRideWidget() }
}

private let rideURL = URL(string: (Bundle.main.object(forInfoDictionaryKey: "PowerLogURLScheme") as! String) + "://")!

struct PowerLogRideWidget: Widget {
  var body: some WidgetConfiguration {
    ActivityConfiguration(for: PowerLogRideAttributes.self) { context in
      RideActivityView(render: activityRender(context))
        .padding(16)
        .activityBackgroundTint(Color(red: 0.05, green: 0.06, blue: 0.07))
        .activitySystemActionForegroundColor(.white)
        .widgetURL(rideURL)
    } dynamicIsland: { context in
      let render = activityRender(context)
      return DynamicIsland {
        DynamicIslandExpandedRegion(.leading) {
          Label("Power Log", systemImage: "bolt.fill").foregroundStyle(.orange).font(.headline)
        }
        DynamicIslandExpandedRegion(.trailing) { RideElapsed(timer: render.timer) }
        DynamicIslandExpandedRegion(.bottom) { RideActivityView(render: render, expanded: true) }
      } compactLeading: {
        Image(systemName: render.status.symbol)
          .foregroundStyle(.orange).accessibilityLabel(render.status.accessibility)
      } compactTrailing: {
        RideElapsed(timer: render.timer).frame(maxWidth: 76)
      } minimal: {
        Image(systemName: render.status.symbol)
          .foregroundStyle(.orange).accessibilityLabel(render.status.accessibility)
      }
      .widgetURL(rideURL)
      .keylineTint(.orange)
    }
  }
}

private func activityRender(_ context: ActivityViewContext<PowerLogRideAttributes>) -> PowerLogRideAttributes.Render {
  let now = Date()
  let render = context.state.render(at: now, isStale: context.isStale)
  #if DEBUG
    let staleDate = context.state.staleDate?.timeIntervalSince1970 ?? -1
    activityRenderLogger.notice(
      "RENDER sequence=\(context.state.publicationSequence) now=\(now.timeIntervalSince1970) staleDate=\(staleDate) isStale=\(context.isStale) bike=\(render.bike.text, privacy: .public) heart=\(render.heartRate.text, privacy: .public) status=\(render.status.text, privacy: .public)"
    )
  #endif
  return render
}

private struct RideElapsed: View {
  let timer: PowerLogRideAttributes.Timer
  var body: some View {
    Group {
      if let origin = timer.origin {
        Text(timerInterval: origin...Date.distantFuture, countsDown: false)
      } else {
        Text(timer.text ?? "")
      }
    }
    .monospacedDigit().font(.system(.body, design: .rounded).weight(.semibold))
    .foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.8)
    .accessibilityLabel(timer.accessibility)
  }
}

private struct RideActivityView: View {
  let render: PowerLogRideAttributes.Render
  var expanded = false
  var body: some View {
    VStack(alignment: .leading, spacing: 10) {
      HStack {
        Label(render.status.text, systemImage: render.status.symbol)
          .font(.headline).foregroundStyle(.white).lineLimit(1).accessibilityLabel(render.status.accessibility)
        Spacer(minLength: 8)
        if !expanded { RideElapsed(timer: render.timer) }
      }
      if render.showReadings {
        HStack(spacing: 20) {
          metric(render.bike)
          metric(render.heartRate)
          Spacer(minLength: 0)
        }
      }
      if render.controls.isEmpty {
        Link(destination: rideURL) {
          Label("Open Power Log", systemImage: "arrow.up.forward.app")
        }
        .font(.subheadline.weight(.semibold)).foregroundStyle(.orange)
      }
      if !render.controls.isEmpty {
        HStack {
          ForEach(render.controls, id: \.action) { control in
            Button(
              intent: PowerLogRideIntent(
                rideID: control.rideID, controlToken: control.controlToken,
                action: control.action, expectedPhase: control.expectedPhase)
            ) { Label(control.label, systemImage: control.symbol) }
            .buttonStyle(.bordered).tint(.orange).font(.subheadline.weight(.semibold))
            if control.action != render.controls.last?.action { Spacer(minLength: 8) }
          }
        }
      }
    }
    .accessibilityElement(children: .contain).accessibilityIdentifier("PowerLog.Activity")
  }

  private func metric(_ entry: PowerLogRideAttributes.Entry) -> some View {
    HStack(alignment: .firstTextBaseline, spacing: 4) {
      Text(entry.text).font(.title3.bold()).monospacedDigit().foregroundStyle(color(entry.color))
      Text(entry.unit).font(.caption).foregroundStyle(.white.opacity(0.65))
    }
    .lineLimit(1).accessibilityElement(children: .ignore).accessibilityLabel(entry.accessibility)
  }

  private func color(_ hex: String) -> Color {
    let value = UInt32(hex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0xFFFFFF
    return Color(
      red: Double((value >> 16) & 255) / 255, green: Double((value >> 8) & 255) / 255,
      blue: Double(value & 255) / 255)
  }
}
