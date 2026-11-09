import SwiftUI

struct WatchControlsPage: View {
  @ObservedObject var model: WatchRideModel
  @Environment(\.isLuminanceReduced) private var luminanceReduced

  var body: some View {
    let state = model.state
    VStack(spacing: 10) {
      WatchTimerRow(model: model).padding(.horizontal, 4)
      ScrollView(showsIndicators: false) {
        VStack(spacing: 10) {
          HStack(spacing: 8) {
            Button {
              model.pauseOrResume()
            } label: {
              Image(systemName: state.paused ? "play.fill" : "pause.fill").font(.title3).frame(maxWidth: .infinity)
            }
            .tint(state.paused ? .orange : .gray)
            .control(state.controls[state.paused ? .resume : .pause])
            Button {
              model.lap()
            } label: {
              Image(systemName: "flag.fill").font(.title3).frame(maxWidth: .infinity)
            }
            .tint(.gray)
            .control(state.controls[.lap])
          }
          .buttonStyle(.borderedProminent)
          Button(state.controls[.finish]?.label ?? "", role: .destructive) { model.askToFinish() }
            .frame(maxWidth: .infinity)
            .control(state.controls[.finish])
          if let lap = state.lap { Text(lap).font(.caption2).foregroundStyle(.secondary) }
          WatchIssueText(issue: state.issue)
        }
        .padding(.horizontal, 4)
      }
    }
  }
}

struct WatchTimerRow: View {
  @ObservedObject var model: WatchRideModel
  @Environment(\.isLuminanceReduced) private var luminanceReduced

  var body: some View {
    let paused = model.state.paused
    HStack {
      Text(model.state.status)
        .font(.caption2.weight(.semibold)).foregroundStyle(paused ? Color.gray : Color.orange)
      Spacer()
      TimelineView(.periodic(from: .now, by: luminanceReduced ? 60.0 : 1.0)) { context in
        Text(model.readings(at: context.date).timer).font(.system(.body, design: .monospaced).weight(.semibold))
          .foregroundStyle(luminanceReduced || paused ? AnyShapeStyle(.secondary) : AnyShapeStyle(.primary))
      }
    }
  }
}
