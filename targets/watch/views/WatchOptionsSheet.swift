import SwiftUI

struct WatchOptionsSheet: View {
  @ObservedObject var model: WatchRideModel

  var body: some View {
    let options = model.options
    NavigationStack {
      Form {
        Toggle("Indoor ride", isOn: binding(\.indoor))
        Toggle("Save to Health", isOn: binding(\.saveToHealth))
        Toggle(
          "Record GPS",
          isOn: Binding(
            get: { options.recordGps ?? !options.indoor },
            set: { on in
              var next = model.options
              next.gps = on ? .on : .off
              model.setOptions(next)
            }))
      }
      .navigationTitle("Ride options")
      .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { model.showsOptions = false } } }
    }
  }

  private func binding(_ field: WritableKeyPath<WatchOptions, Bool>) -> Binding<Bool> {
    Binding(
      get: { model.options[keyPath: field] },
      set: { value in
        var next = model.options
        next[keyPath: field] = value
        model.setOptions(next)
      })
  }
}
