import SwiftUI

/// The **recovery backup** screen: shows the bech32m recovery secret exactly once.
/// It is the only way to recover the account — never persisted, so this is the
/// user's single chance to write it down. Shown right after Create.
struct RecoveryBackupView: View {
    let secret: String
    let userId: String
    var onSaved: () -> Void

    @State private var copied = false
    @State private var acknowledged = false

    /// Split the secret into groups so it's transcribable by hand.
    private var grouped: [String] {
        stride(from: 0, to: secret.count, by: 6).map { i in
            let start = secret.index(secret.startIndex, offsetBy: i)
            let end = secret.index(start, offsetBy: min(6, secret.count - i))
            return String(secret[start..<end])
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 8) {
                VBPill(text: "Back this up now", systemImage: "exclamationmark.shield.fill", tint: VB.warn)
                Text("Your recovery secret").font(VB.rounded(26, .bold))
                Text("Write these words down and keep them offline. Anyone with them controls your identity — and without them, a lost device can’t be recovered.")
                    .font(VB.rounded(15)).foregroundStyle(VB.textSecondary)
            }

            // The secret, grouped, monospace, selectable.
            VStack(alignment: .leading, spacing: 10) {
                Text(grouped.joined(separator: "  "))
                    .font(VB.mono(16, .medium))
                    .foregroundStyle(VB.teal)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                Divider().overlay(VB.hairline)
                HStack {
                    Text("Identity").font(VB.rounded(12)).foregroundStyle(VB.textFaint)
                    Spacer()
                    Text(Fingerprint.short(userId)).font(VB.mono(12)).foregroundStyle(VB.textSecondary)
                }
            }
            .vbCard()

            Button {
                UIPasteboard.general.string = secret
                withAnimation { copied = true }
            } label: {
                Label(copied ? "Copied" : "Copy to clipboard",
                      systemImage: copied ? "checkmark" : "doc.on.doc")
            }
            .buttonStyle(VBSecondaryButtonStyle())

            Toggle(isOn: $acknowledged) {
                Text("I’ve stored my recovery secret somewhere safe.")
                    .font(VB.rounded(14)).foregroundStyle(VB.textSecondary)
            }
            .tint(VB.teal)

            Spacer()

            Button(action: onSaved) { Text("Continue") }
                .buttonStyle(VBPrimaryButtonStyle(enabled: acknowledged))
                .disabled(!acknowledged)
        }
        .padding(22)
        .vbScreen()
    }
}
