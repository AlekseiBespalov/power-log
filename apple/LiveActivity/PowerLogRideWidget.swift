import ActivityKit
import SwiftUI
import WidgetKit

@main
struct PowerLogRideWidgetBundle: WidgetBundle {
  var body: some Widget { PowerLogRideWidget() }
}

struct PowerLogRideWidget: Widget {
  var body: some WidgetConfiguration {
    ActivityConfiguration(for: PowerLogRideAttributes.self) { context in
      RideActivityView(context: context)
        .padding(16)
        .activityBackgroundTint(Color(red: 0.05, green: 0.06, blue: 0.07))
        .activitySystemActionForegroundColor(.white)
        .widgetURL(URL(string: "power-log://"))
    } dynamicIsland: { context in
      DynamicIsland {
        DynamicIslandExpandedRegion(.leading) {
          Label("Power Log", systemImage: "bolt.fill").foregroundStyle(.orange).font(.headline)
        }
        DynamicIslandExpandedRegion(.trailing) { RideElapsed(state: context.state, stale: context.isStale) }
        DynamicIslandExpandedRegion(.bottom) { RideActivityView(context: context, expanded: true) }
      } compactLeading: {
        Image(systemName: activitySymbol(context))
          .foregroundStyle(.orange)
          .accessibilityLabel(context.isStale ? "Ride status unavailable" : context.state.status)
      } compactTrailing: {
        RideElapsed(state: context.state, stale: context.isStale).frame(maxWidth: 76)
      } minimal: {
        Image(systemName: activitySymbol(context)).foregroundStyle(.orange)
          .accessibilityLabel(context.isStale ? "Ride status unavailable" : context.state.status)
      }
      .widgetURL(URL(string: "power-log://"))
      .keylineTint(.orange)
    }
  }

  private func activitySymbol(_ context: ActivityViewContext<PowerLogRideAttributes>) -> String {
    if context.isStale { return "clock.badge.questionmark" }
    if context.state.isBikeUnavailable && context.state.isRunning { return "antenna.radiowaves.left.and.right.slash" }
    return context.state.isRunning ? "bicycle" : "pause.fill"
  }
}

private struct RideElapsed: View {
  let state: PowerLogRideAttributes.ContentState
  let stale: Bool
  var body: some View {
    Group {
      if stale { Text("—") }
      else if state.isRunning {
        Text(timerInterval: state.timerOrigin...Date.distantFuture, countsDown: false)
      } else { Text(Duration.seconds(state.timerSeconds).formatted(.time(pattern: .hourMinuteSecond))) }
    }
    .monospacedDigit().font(.system(.body, design: .rounded).weight(.semibold))
    .foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.8)
    .accessibilityLabel(stale ? "Ride status unavailable" : "Ride time")
  }
}

private struct RideActivityView: View {
  let context: ActivityViewContext<PowerLogRideAttributes>
  var expanded = false
  private var state: PowerLogRideAttributes.ContentState { context.state }
  var body: some View {
    VStack(alignment: .leading, spacing: 10) {
      HStack {
        Label(context.isStale ? "Open Power Log" : state.status, systemImage: "bicycle")
          .font(.headline).foregroundStyle(.white).lineLimit(1)
        Spacer(minLength: 8)
        if !expanded { RideElapsed(state: state, stale: context.isStale) }
      }
      if !context.isStale {
        HStack(spacing: 20) {
          metric(state.riderPowerW, unit: "W", color: .orange, measuredAt: state.lastBikeSampleAt, maximumAge: 6)
          metric(state.heartRateBpm, unit: "bpm", color: .pink, measuredAt: state.lastHeartSampleAt, maximumAge: 15)
          Spacer(minLength: 0)
        }
        if state.canControl {
          HStack {
            control(state.isRunning ? "Pause" : "Resume", symbol: state.isRunning ? "pause.fill" : "play.fill",
              action: state.isRunning ? "pause" : "resume")
            Spacer(minLength: 8)
            control("Finish", symbol: "stop.fill", action: "finish")
          }
        }
      }
    }
  }

  @ViewBuilder private func metric(_ value: Double?, unit: String, color: Color, measuredAt: Date?, maximumAge: Double) -> some View {
    let fresh = measuredAt.map { (0...maximumAge).contains(state.observedAt.timeIntervalSince($0)) } ?? false
    HStack(alignment: .firstTextBaseline, spacing: 4) {
      Text(fresh && value?.isFinite == true ? String(format: "%.0f", value!) : "—").font(.title3.bold()).monospacedDigit().foregroundStyle(color)
      Text(unit).font(.caption).foregroundStyle(.white.opacity(0.65))
    }
    .lineLimit(1)
  }

  private func control(_ title: String, symbol: String, action: String) -> some View {
    Button(intent: PowerLogRideIntent(rideID: context.attributes.rideID, commandID: state.controlToken,
      action: action, expectedPhase: state.phase)) { Label(title, systemImage: symbol) }
      .buttonStyle(.bordered).tint(.orange).font(.subheadline.weight(.semibold))
  }
}
