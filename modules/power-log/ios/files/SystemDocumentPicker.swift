import Foundation
import UIKit
import UniformTypeIdentifiers

final class SystemDocumentPicker: NSObject, DocumentPicking, UIDocumentPickerDelegate {
  private let presenter: () -> UIViewController?
  private var picked: ((URL?) -> Void)?

  init(presenter: @escaping () -> UIViewController?) {
    self.presenter = presenter
  }

  func pickCsv(_ picked: @escaping (URL?) -> Void) -> Bool {
    guard self.picked == nil, let presenter = presenter() else { return false }
    let controller = UIDocumentPickerViewController(forOpeningContentTypes: [.commaSeparatedText], asCopy: true)
    controller.delegate = self
    controller.allowsMultipleSelection = false
    self.picked = picked
    presenter.present(controller, animated: true)
    return true
  }

  func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
    finish(urls.first)
  }

  func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
    finish(nil)
  }

  private func finish(_ url: URL?) {
    let picked = self.picked
    self.picked = nil
    picked?(url)
  }
}
