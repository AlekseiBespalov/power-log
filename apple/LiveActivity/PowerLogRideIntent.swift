import AppIntents
import Foundation
#if POWER_LOG_APP
internal import CycBridge
#endif

@available(iOS 26.0, *)
struct PowerLogRideIntent: LiveActivityIntent {
  static var title: LocalizedStringResource = "Control ride"
  static var isDiscoverable: Bool = false
  static var supportedModes: IntentModes { .background }
  @available(iOS 27.0, *)
  static var allowedExecutionTargets: IntentExecutionTargets { .main }

  @Parameter(title: "Ride") var rideID: String
  @Parameter(title: "Command") var commandID: String
  @Parameter(title: "Action") var action: String
  @Parameter(title: "Phase") var expectedPhase: String

  init() {}
  init(rideID: String, commandID: String, action: String, expectedPhase: String) {
    self.rideID = rideID; self.commandID = commandID
    self.action = action; self.expectedPhase = expectedPhase
  }

  func perform() async throws -> some IntentResult {
    if action == "finish" {
      try await requestConfirmation(actionName: .continue, dialog: "Finish and save this ride?")
    }
    #if POWER_LOG_APP
    try await PowerLogActivityControl.perform(rideID: rideID, commandID: commandID,
      action: action, expectedPhase: expectedPhase)
    #else
    // LiveActivityIntent executes in the app. Fail closed if the OS routes it elsewhere.
    throw RideIntentError.requiresApp
    #endif
    return .result()
  }
}

private enum RideIntentError: LocalizedError {
  case requiresApp
  var errorDescription: String? { "Open Power Log to control this ride." }
}
