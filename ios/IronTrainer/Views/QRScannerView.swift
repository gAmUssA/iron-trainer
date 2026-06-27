import SwiftUI
import VisionKit

/// A QR scanner backed by VisionKit's DataScannerViewController. Calls `onScan`
/// with the raw payload of the first QR code found. (Camera is unavailable in the
/// Simulator — use manual code entry there.)
struct QRScannerView: UIViewControllerRepresentable {
    var onScan: (String) -> Void

    static var isSupported: Bool {
        DataScannerViewController.isSupported && DataScannerViewController.isAvailable
    }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        try? scanner.startScanning()
        return scanner
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onScan: onScan) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onScan: (String) -> Void
        private var fired = false
        init(onScan: @escaping (String) -> Void) { self.onScan = onScan }

        func dataScanner(_ scanner: DataScannerViewController, didAdd added: [RecognizedItem],
                         allItems: [RecognizedItem]) {
            guard !fired else { return }
            for item in added {
                if case let .barcode(code) = item, let payload = code.payloadStringValue {
                    fired = true
                    onScan(payload)
                    break
                }
            }
        }
    }
}

/// Parse `irontrainer://pair?server=<enc>&code=<code>` into (serverURL, code).
func parsePairingPayload(_ s: String) -> (URL, String)? {
    guard let comps = URLComponents(string: s),
          comps.scheme == "irontrainer", comps.host == "pair",
          let server = comps.queryItems?.first(where: { $0.name == "server" })?.value,
          let code = comps.queryItems?.first(where: { $0.name == "code" })?.value,
          let url = URL(string: server) else { return nil }
    return (url, code)
}
