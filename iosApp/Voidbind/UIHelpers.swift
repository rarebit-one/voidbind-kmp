import CoreImage.CIFilterBuiltins
import SwiftUI

/// Render a string as a QR code image (CoreImage, offline). Used for the pairing
/// invite QR on the CONNECT screen.
enum QRImage {
    static func generate(_ string: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        // Upscale so it stays crisp when displayed large.
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        let context = CIContext()
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// A QR view with a light card behind it (QR codes need a light quiet-zone to scan).
struct QRCodeView: View {
    let payload: String
    var size: CGFloat = 220
    var body: some View {
        Group {
            if let img = QRImage.generate(payload) {
                Image(uiImage: img)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
            } else {
                Image(systemName: "qrcode").resizable().scaledToFit().padding(24)
                    .foregroundStyle(VB.textFaint)
            }
        }
        .frame(width: size, height: size)
        .padding(16)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// Format a `KeyRef` render string (`ed25519:<hex>`) into a short, human-verifiable
/// fingerprint: uppercase hex grouped in fours, first 16 hex chars. Enough to
/// eyeball two devices show the same identity without displaying the full key.
enum Fingerprint {
    static func short(_ keyRefRendered: String) -> String {
        let hex = keyRefRendered.split(separator: ":").last.map(String.init) ?? keyRefRendered
        let head = String(hex.prefix(16)).uppercased()
        return stride(from: 0, to: head.count, by: 4).map { i -> String in
            let start = head.index(head.startIndex, offsetBy: i)
            let end = head.index(start, offsetBy: min(4, head.count - i))
            return String(head[start..<end])
        }.joined(separator: " ")
    }
}
