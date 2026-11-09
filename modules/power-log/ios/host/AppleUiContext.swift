import Foundation
import UIKit

public final class AppleUiContext: @unchecked Sendable {
  public static let shared = AppleUiContext()

  private var owner: AnyObject?
  private var provider: (() -> UIViewController?)?

  func attach(_ owner: AnyObject, _ provider: @escaping () -> UIViewController?) {
    DispatchQueue.main.async {
      self.owner = owner
      self.provider = provider
    }
  }

  func detach(_ owner: AnyObject) {
    DispatchQueue.main.async {
      guard self.owner === owner else { return }
      self.owner = nil
      self.provider = nil
    }
  }

  public func presenter() -> UIViewController? {
    dispatchPrecondition(condition: .onQueue(.main))
    return provider?()
  }
}
