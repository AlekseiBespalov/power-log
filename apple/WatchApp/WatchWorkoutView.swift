import SwiftUI

private struct ElectricBikeIcon: View {
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

struct WatchWorkoutView: View {
  @ObservedObject var engine: WatchWorkoutEngine
  @AppStorage("ride.indoor") private var indoor = WatchRidePreferences.defaults.indoor
  @AppStorage("ride.saveToHealth") private var saveToHealth = WatchRidePreferences.defaults.saveToHealth!.boolValue
  @AppStorage("ride.recordGPS") private var recordGPSSetting = WatchRidePreferences.defaultGPSSetting
  @Environment(\.isLuminanceReduced) private var luminanceReduced
  @State private var confirmEnd = false
  @State private var confirmedRideID: String?
  @State private var showOptions = false
  @State private var page = 0
  private static let powerColor = color(WatchMetricAppearance.power)
  private static let cadenceColor = color(WatchMetricAppearance.cadence)
  private static let heartColor = color(WatchMetricAppearance.heart)

  private var recordGPS: Bool {
    (try? WatchRidePreferences.resolve(
      indoor: indoor, saveToHealth: saveToHealth, gpsSetting: recordGPSSetting
    ).recordGPS) == true
  }

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
            VStack(spacing: 12) {
              startWorkout
              issues
            }.padding(.horizontal, 4)
          }
        }
      }
      .navigationTitle("Power Log")
      .toolbar(engine.isActive ? .hidden : .visible, for: .navigationBar)
      .confirmationDialog("Finish this ride?", isPresented: $confirmEnd, titleVisibility: .visible) {
        Button("Save ride") { Task { await engine.finishRide(discard: false, confirmedRideID: confirmedRideID) } }
        Button("Discard ride", role: .destructive) {
          Task { await engine.finishRide(discard: true, confirmedRideID: confirmedRideID) }
        }
        Button("Keep recording", role: .cancel) {}
      }
      .sheet(isPresented: $showOptions) {
        NavigationStack {
          Form {
            Toggle("Indoor ride", isOn: $indoor)
            Toggle("Save to Health", isOn: $saveToHealth)
            Toggle("Record GPS", isOn: Binding(get: { recordGPS }, set: { recordGPSSetting = $0 ? 1 : 2 }))
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
      Text(message).font(.caption2).foregroundStyle(.orange)
        .frame(maxWidth: .infinity, alignment: .leading).fixedSize(horizontal: false, vertical: true)
    }

  }

  private var startWorkout: some View {
    let presentation = engine.idlePresentation()
    return VStack(spacing: 12) {
      if presentation.showsProgress {
        ProgressView().tint(.orange).padding(.vertical, 10)
        Button("Cancel Start", role: .cancel) { engine.cancelStart() }
      } else {
        if presentation == .saved {
          Image(systemName: "checkmark.circle")
            .font(.system(size: 32, weight: .medium)).foregroundStyle(.orange)
        } else {
          ElectricBikeIcon().frame(width: 32, height: 32).foregroundStyle(.orange).accessibilityHidden(true)
        }
      }
      Text(presentation.title).font(.headline).multilineTextAlignment(.center)
      if presentation == .saved {
        Text(engine.elapsed()).font(.system(.title2, design: .rounded).monospacedDigit())
        Text(engine.savedStatusLabel).font(.caption2).foregroundStyle(.secondary)
          .multilineTextAlignment(.center)
      }
      if engine.canFinishAbsentOwner {
        Button("Finish ride") {
          confirmedRideID = engine.selectedRideID
          confirmEnd = true
        }
        Button("Discard ride", role: .destructive) {
          Task { await engine.finishRide(discard: true, confirmedRideID: engine.selectedRideID) }
        }
      }
      if !engine.hasUnfinishedOwner && !presentation.showsProgress {
        Button {
          Task {
            await engine.startRide(
              indoor: indoor, saveToHealth: saveToHealth, gpsSetting: recordGPSSetting)
          }
        } label: {
          Label("Start ride", systemImage: "play.fill").frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .disabled(!engine.actionAvailable(.start))
        Button {
          showOptions = true
        } label: {
          HStack {
            Text(indoor ? "Indoor ride" : "Outdoor ride")
            Spacer()
            Image(systemName: "slider.horizontal.3")
          }.font(.caption)
        }
        .accessibilityLabel("Ride options, \(indoor ? "indoor" : "outdoor")")
        .disabled(!engine.actionAvailable(.start))
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
      TimelineView(.periodic(from: .now, by: luminanceReduced ? 60.0 : 1.0)) { _ in
        Text(engine.elapsed()).font(.system(.body, design: .monospaced).weight(.semibold))
          .foregroundStyle(dimmed ? AnyShapeStyle(.secondary) : AnyShapeStyle(.primary))
      }
    }
  }

  private var controlsPage: some View {
    VStack(spacing: 10) {
      timerRow.padding(.horizontal, 4)
      ScrollView(showsIndicators: false) {
        VStack(spacing: 10) {
          HStack(spacing: 8) {
            Button {
              paused ? engine.resume() : engine.pause()
            } label: {
              Image(systemName: paused ? "play.fill" : "pause.fill").font(.title3).frame(maxWidth: .infinity)
            }
            .tint(paused ? .orange : .gray)
            .accessibilityLabel(paused ? "Resume ride" : "Pause ride")
            .disabled(!engine.actionAvailable(paused ? .resume : .pause))
            Button {
              engine.lap()
            } label: {
              Image(systemName: "flag.fill").font(.title3).frame(maxWidth: .infinity)
            }
            .tint(.gray)
            .accessibilityLabel("Mark lap")
            .disabled(!engine.actionAvailable(.lap))
          }
          .buttonStyle(.borderedProminent)
          Button("Finish ride", role: .destructive) {
            confirmedRideID = engine.selectedRideID
            confirmEnd = true
          }
          .frame(maxWidth: .infinity)
          .disabled(!engine.actionAvailable(.stop, confirmation: engine.selectedRideID))
          if engine.lapCount > 0 { Text("Lap \(engine.lapCount + 1)").font(.caption2).foregroundStyle(.secondary) }
          issues
        }
        .padding(.horizontal, 4)
      }
    }
  }

  private var readingsPage: some View {
    VStack(spacing: 9) {
      timerRow.padding(.horizontal, 4)
      ScrollView(showsIndicators: false) {
        VStack(spacing: 9) {
          TimelineView(.periodic(from: .now, by: luminanceReduced ? 60.0 : 1.0)) { context in
            let readings = engine.readings(tick: context.date)
            HStack(alignment: .firstTextBaseline) {
              metric(readings.power, appearance: WatchMetricAppearance.power, color: Self.powerColor)
              Spacer()
              metric(readings.cadence, appearance: WatchMetricAppearance.cadence, color: Self.cadenceColor)
            }
            if let source = readings.controller {
              Text(source.label).font(.caption2).foregroundStyle(
                source.tone == "warning" ? Color.orange : Color.secondary)
            }
            HStack {
              Label(readings.heart, systemImage: "heart.fill").foregroundStyle(
                dimmed
                  ? AnyShapeStyle(.secondary)
                  : AnyShapeStyle(Self.heartColor))
              Spacer()
              VStack(alignment: .trailing, spacing: 2) {
                Text(engine.distanceLabel)
                Text(engine.distanceSourceLabel).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                  .minimumScaleFactor(0.8)
              }
            }.font(.system(.body, design: .default).weight(.semibold)).monospacedDigit()
            HStack {
              Label(readings.gps, systemImage: "location.fill")
              Spacer()
              if engine.lapCount > 0 { Text("Lap \(engine.lapCount + 1)") }
            }.font(.caption2).foregroundStyle(.secondary)
          }
        }
        .padding(.horizontal, 4)
      }
    }
  }

  private func metric(_ value: String, appearance: WatchMetricAppearance, color: Color) -> some View {
    let declaration = appearance.declaration
    return VStack(alignment: .leading, spacing: 0) {
      Text(value).font(.system(size: 36, weight: dimmed ? .semibold : .bold, design: .default))
        .monospacedDigit().minimumScaleFactor(0.6).lineLimit(1).foregroundStyle(
          dimmed ? AnyShapeStyle(.secondary) : AnyShapeStyle(color))
      Text(declaration.unit).font(.caption2).foregroundStyle(.secondary)
    }.frame(maxWidth: .infinity, alignment: .leading)
      .accessibilityLabel("\(declaration.label), \(value) \(declaration.unit)")
  }

  private static func color(_ appearance: WatchMetricAppearance) -> Color {
    Color(red: appearance.red, green: appearance.green, blue: appearance.blue)
  }
}
