import SwiftUI
import Voidbind

/// Drives onboarding against the ``VoidbindEngine``: create a new identity (show
/// the recovery secret to back up, then self-enrol this device) or restore from a
/// written-down secret. Coordinator/identity calls run OFF the main thread (the
/// KMP transport is blocking); results publish back on the main actor.
@MainActor
final class OnboardingViewModel: ObservableObject {

    enum Phase: Equatable {
        case choosing
        case showRecoverySecret(String)   // back this up
        case enrolled(userId: String)
        case failed(String)
    }

    @Published var phase: Phase = .choosing
    @Published var busy = false

    private let engine: VoidbindEngine

    init(engine: VoidbindEngine) { self.engine = engine }

    /// Create a brand-new identity, self-enrol this device, and surface the recovery
    /// secret for the user to store once.
    func createIdentity() {
        run {
            let identity = self.engine.createIdentity()
            let device = self.engine.deviceIdentity()          // provisions the SE key (biometric)
            _ = self.engine.enrolFirstDevice(identity: identity, device: device)
            return .showRecoverySecret(identity.recovery.format())
        }
    }

    /// Restore an identity from the recovery secret, then self-enrol this device.
    func restoreIdentity(_ secret: String) {
        run {
            do {
                let identity = try self.engine.restoreIdentity(secret)
                let device = self.engine.deviceIdentity()
                _ = self.engine.enrolFirstDevice(identity: identity, device: device)
                return .enrolled(userId: identity.userId.render())
            } catch {
                return .failed("That recovery secret didn’t check out — re-read it and try again.")
            }
        }
    }

    private func run(_ work: @escaping () -> Phase) {
        busy = true
        Task.detached(priority: .userInitiated) {
            let next = work()
            await MainActor.run {
                self.phase = next
                self.busy = false
            }
        }
    }
}

/// A minimal onboarding screen — the scaffold that proves the create/restore →
/// enrol wiring. The full dark-first/teal design from Jaryl's mockups is applied
/// in the on-device session; the bindings to ``OnboardingViewModel`` stay.
struct OnboardingView: View {
    @ObservedObject var model: OnboardingViewModel
    @State private var secretInput = ""

    var body: some View {
        VStack(spacing: 24) {
            Text("Voidbind").font(.largeTitle.bold())

            switch model.phase {
            case .choosing:
                Button("Create a new identity") { model.createIdentity() }
                    .buttonStyle(.borderedProminent)
                Divider().padding(.vertical, 8)
                TextField("Recovery secret (heyarr1…)", text: $secretInput)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                Button("Restore from recovery secret") { model.restoreIdentity(secretInput) }
                    .disabled(secretInput.isEmpty)

            case .showRecoverySecret(let secret):
                Text("Write this down and keep it safe. It is the only way to recover your account.")
                    .font(.callout).multilineTextAlignment(.center)
                Text(secret).font(.system(.body, design: .monospaced))
                    .textSelection(.enabled).padding().background(.quaternary).cornerRadius(8)
                Button("I’ve saved it") { model.phase = .enrolled(userId: "") }

            case .enrolled(let userId):
                Image(systemName: "checkmark.seal.fill").font(.system(size: 44)).foregroundStyle(.green)
                Text("This device is enrolled.").font(.headline)
                if !userId.isEmpty {
                    Text(userId).font(.caption.monospaced()).foregroundStyle(.secondary)
                }

            case .failed(let message):
                Text(message).foregroundStyle(.red).multilineTextAlignment(.center)
                Button("Try again") { model.phase = .choosing }
            }

            if model.busy { ProgressView() }
        }
        .padding()
    }
}
