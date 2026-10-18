import AppIntents
import Foundation
#if POWER_LOG_APP
  internal import PowerLogBridge
#endif

struct PowerLogRideIntent: LiveActivityIntent {
  static var title: LocalizedStringResource = "Control ride"
  static var isDiscoverable: Bool = false
  static var supportedModes: IntentModes { [.background, .foreground(.dynamic)] }
  // IntentExecutionTargets exists only in the iOS 27 SDK, which ships with the Swift 6.4 toolchain.
  #if compiler(>=6.4)
    @available(iOS 27.0, *)
    static var allowedExecutionTargets: IntentExecutionTargets { .main }
  #endif

  @Parameter(title: "Ride") var rideID: String
  @Parameter(title: "Control token") var controlToken: String
  @Parameter(title: "Action") var action: String
  @Parameter(title: "Phase") var expectedPhase: String

  init() {}
  init(rideID: String, controlToken: String, action: String, expectedPhase: String) {
    self.rideID = rideID
    self.controlToken = controlToken
    self.action = action
    self.expectedPhase = expectedPhase
  }

  func perform() async throws -> some IntentResult {
    #if POWER_LOG_APP
      try await PowerLogActivityControl.perform(
        rideID: rideID, controlToken: controlToken,
        action: action, expectedPhase: expectedPhase)
      if action == "finish" {
        try await continueInForeground(alwaysConfirm: false)
      }
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
