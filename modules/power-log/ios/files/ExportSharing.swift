import Foundation
import UIKit

enum ExportSharing {
  static func share(
    _ file: [String: Any], root: URL, from presenter: UIViewController?,
    done: @escaping (Result<String, SinkFailure>) -> Void
  ) {
    guard let presenter else {
      return done(.failure(SinkFailure(code: "uiUnavailable", message: "Power Log has no screen to share from.")))
    }
    guard let url = committed(file["uri"] as? String, root: root) else {
      return done(.failure(SinkFailure.invalid("This export file is unavailable.")))
    }
    let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
    var answered = false
    controller.completionWithItemsHandler = { activity, completed, _, error in
      guard !answered else { return }
      if let error {
        answered = true
        let message = "The file could not be shared: \(error.localizedDescription)"
        done(.failure(SinkFailure(code: "failed", message: message)))
      } else if completed || activity == nil {
        answered = true
        done(.success(completed ? "shared" : "dismissed"))
      }
    }
    if let popover = controller.popoverPresentationController {
      popover.sourceView = presenter.view
      popover.sourceRect = CGRect(x: presenter.view.bounds.midX, y: presenter.view.bounds.midY, width: 0, height: 0)
      popover.permittedArrowDirections = []
    }
    presenter.present(controller, animated: true)
  }

  static func committed(_ uri: String?, root: URL) -> URL? {
    guard let uri, let url = URL(string: uri), url.isFileURL else { return nil }
    let file = url.standardizedFileURL.resolvingSymlinksInPath()
    let exports = root.standardizedFileURL.resolvingSymlinksInPath().path + "/"
    guard file.path.hasPrefix(exports), FileManager.default.fileExists(atPath: file.path) else { return nil }
    return file
  }
}
