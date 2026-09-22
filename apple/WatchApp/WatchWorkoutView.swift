import SwiftUI

struct WatchWorkoutView: View {
  @ObservedObject var engine: WatchWorkoutEngine
  @AppStorage("ride.indoor") private var indoor = false
  @AppStorage("ride.saveToHealth") private var saveToHealth = true
  @AppStorage("ride.recordGPS") private var recordGPSSetting = 0
  @Environment(\.isLuminanceReduced) private var luminanceReduced
  @State private var confirmEnd = false
  @State private var showOptions = false
  @State private var page = 0

  private var recordGPS: Bool? { recordGPSSetting == 0 ? nil : recordGPSSetting == 1 }

  var body: some View {
    NavigationStack {
      Group {
        if engine.isActive {
          TabView(selection: $page) {
            controlsPage.tag(0)
            readingsPage.tag(1)
          }
          .tabViewStyle(.verticalPage)
        } else {
          ScrollView(showsIndicators: false) {
            VStack(spacing: 12) { startWorkout; issues }.padding(.horizontal, 4)
          }
        }
      }
      .navigationTitle("Power Log")
      .toolbar(engine.isActive ? .hidden : .visible, for: .navigationBar)
      .confirmationDialog("Finish this ride?", isPresented: $confirmEnd, titleVisibility: .visible) {
        Button("Save ride") { Task { await engine.end() } }
        Button("Discard ride", role: .destructive) { Task { await engine.end(discard: true) } }
        Button("Keep recording", role: .cancel) {}
      }
      .sheet(isPresented: $showOptions) {
        NavigationStack {
          Form {
            Toggle("Indoor ride", isOn: $indoor)
            Toggle("Save to Health", isOn: $saveToHealth)
            Toggle("Record GPS", isOn: Binding(get: { recordGPS ?? !indoor }, set: { recordGPSSetting = $0 ? 1 : 2 }))
          }
          .navigationTitle("Ride options")
          .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showOptions = false } } }
        }
      }
      .onChange(of: engine.phase) { _, phase in
        if phase == "preparing" || engine.isActive { showOptions = false }
        if !engine.isActive { page = 0 }
      }
    }
    .tint(.orange)
  }

  @ViewBuilder private var issues: some View {
    if let message = engine.displayedIssue {
      Text(message).font(.caption2).foregroundStyle(.orange).frame(maxWidth: .infinity, alignment: .leading)
    }
    if engine.canRetryHealthSave {
      Button("Retry Health save") { Task { await engine.retryHealthSave() } }.font(.caption2)
    }
  }

  // Keep controls in the normal observed view. Watch timelines can pre-render
  // future content; only the read-only active measurements below use them.
  private var startWorkout: some View {
    let date = Date()
    let presentation = engine.idlePresentation(at: date)
    return VStack(spacing: 12) {
      if presentation.showsProgress {
        ProgressView().tint(.orange).padding(.vertical, 10)
      } else {
        Image(systemName: presentation == .saved ? "checkmark.circle" : "bicycle")
          .font(.system(size: 32, weight: .medium)).foregroundStyle(.orange)
      }
      Text(presentation.title).font(.headline).multilineTextAlignment(.center)
      if presentation == .saved {
        Text(engine.elapsed(at: date)).font(.system(.title2, design: .rounded).monospacedDigit())
        Text(engine.savedStatusLabel).font(.caption2).foregroundStyle(.secondary)
          .multilineTextAlignment(.center)
      }
      if !presentation.showsProgress {
        Button {
          Task { await engine.start(indoor: indoor, eBike: true, saveToHealth: saveToHealth, recordGPS: recordGPS) }
        } label: {
          Label("Start ride", systemImage: "play.fill").frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .disabled(!engine.canStart)
        Button { showOptions = true } label: {
          HStack {
            Text(indoor ? "Indoor ride" : "Outdoor ride")
            Spacer()
            Image(systemName: "slider.horizontal.3")
          }.font(.caption)
        }
        .accessibilityLabel("Ride options, \(indoor ? "indoor" : "outdoor")")
        .disabled(!engine.canStart)
      }
    }
  }

  private var paused: Bool { engine.phase == "paused" }
  private var dimmed: Bool { luminanceReduced || paused }

  private var timerRow: some View {
    HStack {
      Text(paused ? "PAUSED" : "RIDING")
        .font(.caption2.weight(.semibold)).foregroundStyle(paused ? Color.gray : Color.orange)
      Spacer()
      TimelineView(.periodic(from: .now, by: luminanceReduced ? 60.0 : 1.0)) { context in
        Text(engine.elapsed(at: context.date)).font(.system(.body, design: .monospaced).weight(.semibold))
          .foregroundStyle(dimmed ? AnyShapeStyle(.secondary) : AnyShapeStyle(.primary))
      }
    }
  }

  private var controlsPage: some View {
    VStack(spacing: 10) {
      timerRow
      Spacer(minLength: 0)
      HStack(spacing: 8) {
        Button {
          paused ? engine.resume() : engine.pause()
        } label: {
          Image(systemName: paused ? "play.fill" : "pause.fill").font(.title3).frame(maxWidth: .infinity)
        }
        .tint(paused ? .orange : .gray)
        .accessibilityLabel(paused ? "Resume ride" : "Pause ride")
        Button { engine.lap() } label: { Image(systemName: "flag.fill").font(.title3).frame(maxWidth: .infinity) }
          .tint(.gray)
          .accessibilityLabel("Mark lap")
      }
      .buttonStyle(.borderedProminent)
      .disabled(engine.isBusy || !engine.canControl)
      Button("Finish ride", role: .destructive) { confirmEnd = true }
        .frame(maxWidth: .infinity)
        .disabled(engine.isBusy || !engine.canControl)
      if engine.lapCount > 0 { Text("Lap \(engine.lapCount + 1)").font(.caption2).foregroundStyle(.secondary) }
      issues
    }
    .padding(.horizontal, 4)
  }

  private var readingsPage: some View {
    ScrollView(showsIndicators: false) {
      VStack(spacing: 9) {
        timerRow
        TimelineView(.periodic(from: .now, by: luminanceReduced ? 60.0 : 1.0)) { context in
          HStack(alignment: .firstTextBaseline) {
            metric(engine.power(at: context.date), unit: "W", color: .orange)
            Spacer()
            metric(engine.cadence(at: context.date), unit: "rpm", color: .purple)
          }
          HStack {
            Label(engine.heartRate(at: context.date), systemImage: "heart.fill").foregroundStyle(dimmed ? AnyShapeStyle(.secondary) : AnyShapeStyle(.pink))
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
              Text(engine.distanceLabel)
              Text(engine.distanceSourceLabel).font(.caption2).foregroundStyle(.secondary).lineLimit(1).minimumScaleFactor(0.8)
            }
          }.font(.system(.body, design: .default).weight(.semibold)).monospacedDigit()
        }
        HStack {
          Label(engine.gpsLabel, systemImage: "location.fill")
          Spacer()
          if engine.lapCount > 0 { Text("Lap \(engine.lapCount + 1)") }
        }.font(.caption2).foregroundStyle(.secondary)
      }
      .padding(.horizontal, 4)
    }
  }

  private func metric(_ value: String, unit: String, color: Color) -> some View {
    VStack(alignment: .leading, spacing: 0) {
      Text(value).font(.system(size: 36, weight: dimmed ? .semibold : .bold, design: .default))
        .monospacedDigit().minimumScaleFactor(0.6).lineLimit(1).foregroundStyle(dimmed ? AnyShapeStyle(.secondary) : AnyShapeStyle(color))
      Text(unit).font(.caption2).foregroundStyle(.secondary)
    }.frame(maxWidth: .infinity, alignment: .leading)
  }
}
