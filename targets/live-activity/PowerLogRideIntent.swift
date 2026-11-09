import AppIntents
import Foundation
#if POWER_LOG_APP
  internal import PowerLogPlatform
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
      do {
        try await PowerLogHost.performRideCardIntent(
          rideId: rideID, token: controlToken, phase: expectedPhase, action: action,
          continueInForeground: { try await self.continueInForeground(alwaysConfirm: false) })
      } catch is RideCardIntentFailed {
        throw RideIntentError.failed
      }
    #else
      throw RideIntentError.requiresApp
    #endif
    return .result()
  }
}

private enum RideIntentError: LocalizedError {
  case requiresApp
  case failed
  var errorDescription: String? { "Open Power Log to control this ride." }
}
