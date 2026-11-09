import SwiftUI
import WatchKit

struct WatchRideView: View {
  @ObservedObject var model: WatchRideModel

  var body: some View {
    NavigationStack {
      Group {
        if model.state.mode == .active {
          TabView(selection: $model.page) {
            WatchControlsPage(model: model).tag(0)
            WatchReadingsPage(model: model).tag(1)
          }
          .tabViewStyle(.verticalPage)
        } else {
          ScrollView(showsIndicators: false) {
            WatchIdleView(model: model).padding(.horizontal, 4)
          }
        }
      }
      .navigationTitle("Power Log")
      .toolbar(model.state.mode == .active ? .hidden : .visible, for: .navigationBar)
      .confirmationDialog(
        "Finish this ride?",
        isPresented: Binding(get: { model.confirmingRideID != nil }, set: { if !$0 { model.confirmingRideID = nil } }),
        titleVisibility: .visible
      ) {
        Button("Save ride") { model.confirmFinish(discard: false) }
        Button("Discard ride", role: .destructive) { model.confirmFinish(discard: true) }
        Button("Keep recording", role: .cancel) { model.confirmingRideID = nil }
      }
      .sheet(isPresented: $model.showsOptions) { WatchOptionsSheet(model: model) }
      .onChange(of: model.state.lap) { _, lap in
        if lap != nil { WKInterfaceDevice.current().play(.click) }
      }
    }
    .tint(.orange)
  }
}

struct WatchIssueText: View {
  let issue: String?

  var body: some View {
    if let issue {
      Text(issue).font(.caption2).foregroundStyle(.orange)
        .frame(maxWidth: .infinity, alignment: .leading).fixedSize(horizontal: false, vertical: true)
    }
  }
}

extension View {
  func control(_ control: WatchRideModel.Control?) -> some View {
    disabled(control?.enabled != true)
      .accessibilityLabel(control?.accessibilityLabel ?? "")
      .accessibilityHint(control?.hint ?? "")
  }
}
