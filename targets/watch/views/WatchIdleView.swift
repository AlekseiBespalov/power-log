import SwiftUI

struct WatchIdleView: View {
  @ObservedObject var model: WatchRideModel

  var body: some View {
    let state = model.state
    VStack(spacing: 12) {
      if state.showsProgress {
        ProgressView().tint(.orange).padding(.vertical, 10)
        Button(state.controls[.cancelStart]?.label ?? "", role: .cancel) { model.cancelStart() }
          .control(state.controls[.cancelStart])
      } else if state.showsCheckmark {
        Image(systemName: "checkmark.circle").font(.system(size: 32, weight: .medium)).foregroundStyle(.orange)
      } else {
        ElectricBikeIcon().frame(width: 32, height: 32).foregroundStyle(.orange).accessibilityHidden(true)
      }
      Text(state.title).font(.headline).multilineTextAlignment(.center)
      if state.showsCheckmark {
        TimelineView(.periodic(from: .now, by: 1)) { context in
          Text(model.readings(at: context.date).timer).font(.system(.title2, design: .rounded).monospacedDigit())
        }
        if let health = state.health {
          Text(health).font(.caption2).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
      }
      if state.canEndInterrupted {
        Button(state.controls[.finish]?.label ?? "") { model.askToFinish() }.control(state.controls[.finish])
        Button(state.controls[.discard]?.label ?? "", role: .destructive) { model.discardInterrupted() }
          .control(state.controls[.discard])
      }
      if state.canStart {
        Button {
          model.start()
        } label: {
          Label(state.controls[.start]?.label ?? "", systemImage: "play.fill").frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .control(state.controls[.start])
        Button {
          model.showsOptions = true
        } label: {
          HStack {
            Text(state.optionsLabel)
            Spacer()
            Image(systemName: "slider.horizontal.3")
          }.font(.caption)
        }
        .control(state.controls[.options])
      }
      WatchIssueText(issue: state.issue)
    }
  }
}
