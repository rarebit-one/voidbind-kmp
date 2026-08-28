import SwiftUI
import Voidbind

/// The **web-login approval** sheet. After scanning a `voidbind:login` QR, the app
/// fetches the challenge (via ``LoginApproval/begin``), shows the human WHAT they
/// are signing into and a live expiry countdown, and only on Approve signs the
/// challenge with the biometric-gated device key (``LoginApproval/approve``).
@MainActor
final class WebLoginModel: ObservableObject {

    enum Phase: Equatable { case loading, ready, approving, approved, failed(String) }

    @Published var phase: Phase
    @Published var rp: String = ""
    @Published var audience: String = ""
    @Published var expiresAt: Date = .now

    private let engine: VoidbindEngine?
    private let cert: String
    private let loginQr: String
    private var approval: LoginApproval?
    private var request: LoginApproval.Request?

    /// Live path: fetch the challenge from the RP.
    init(engine: VoidbindEngine, cert: String, loginQr: String) {
        self.engine = engine; self.cert = cert; self.loginQr = loginQr
        self.phase = .loading
        begin()
    }

    /// Sample path (screenshots / previews): a ready approval with fixed fields.
    init(sampleRP: String, audience: String, expiresIn: TimeInterval) {
        self.engine = nil; self.cert = ""; self.loginQr = ""
        self.phase = .ready; self.rp = sampleRP; self.audience = audience
        self.expiresAt = Date().addingTimeInterval(expiresIn)
    }

    private func begin() {
        guard let engine else { return }
        Task.detached(priority: .userInitiated) {
            let approval = engine.makeLoginApproval(cert: self.cert)
            do {
                let request = try approval.begin(loginQr: self.loginQr)
                await MainActor.run {
                    self.approval = approval; self.request = request
                    self.rp = request.rp; self.audience = request.audience
                    self.expiresAt = Date(timeIntervalSince1970: Double(request.expiresAt))
                    self.phase = .ready
                }
            } catch {
                await MainActor.run { self.phase = .failed("Couldn’t reach that site, or the sign-in expired.") }
            }
        }
    }

    func approve(onApproved: @escaping (_ origin: String) -> Void) {
        guard let approval, let request else { onApproved(audience); return }
        phase = .approving
        Task.detached(priority: .userInitiated) {
            do {
                try approval.approve(request: request)
                await MainActor.run { self.phase = .approved; onApproved(self.audience) }
            } catch {
                await MainActor.run { self.phase = .failed("The site refused this sign-in. Try scanning again.") }
            }
        }
    }
}

struct WebLoginApprovalView: View {
    @StateObject private var model: WebLoginModel
    @Environment(\.dismiss) private var dismiss
    var onApproved: (_ origin: String) -> Void

    /// Live.
    init(engine: VoidbindEngine, cert: String, loginQr: String,
         onApproved: @escaping (String) -> Void) {
        _model = StateObject(wrappedValue: WebLoginModel(engine: engine, cert: cert, loginQr: loginQr))
        self.onApproved = onApproved
    }
    /// Sample.
    init(model: WebLoginModel, onApproved: @escaping (String) -> Void = { _ in }) {
        _model = StateObject(wrappedValue: model)
        self.onApproved = onApproved
    }

    var body: some View {
        VStack(spacing: 22) {
            Capsule().fill(VB.hairline).frame(width: 40, height: 5).padding(.top, 10)

            switch model.phase {
            case .loading:
                Spacer(); ProgressView().tint(VB.teal)
                Text("Reaching the site…").font(VB.rounded(14)).foregroundStyle(VB.textSecondary); Spacer()
            case .ready, .approving:
                approvalBody
            case .approved:
                result(icon: "checkmark.seal.fill", tint: VB.good, title: "Signed in",
                       subtitle: "You approved \(model.audience).")
            case .failed(let message):
                result(icon: "xmark.octagon.fill", tint: VB.danger, title: "Couldn’t sign in", subtitle: message)
            }
        }
        .padding(.horizontal, 22).padding(.bottom, 22)
        .vbScreen()
    }

    private var approvalBody: some View {
        VStack(spacing: 20) {
            VStack(spacing: 12) {
                ZStack {
                    Circle().fill(VB.teal.opacity(0.12)).frame(width: 78, height: 78)
                    Image(systemName: "globe").font(.system(size: 32, weight: .semibold)).foregroundStyle(VB.teal)
                }
                Text("Sign in to").font(VB.rounded(15)).foregroundStyle(VB.textSecondary)
                Text(host(model.audience)).font(VB.rounded(22, .bold))
                Text(model.audience).font(VB.mono(12)).foregroundStyle(VB.textFaint)
            }
            .padding(.top, 8)

            VStack(spacing: 10) {
                infoRow(icon: "checkmark.shield", label: "Verified offline against your pinned key")
                infoRow(icon: "faceid", label: "Face ID confirms it’s you before signing")
                CountdownRow(expiresAt: model.expiresAt)
            }
            .vbCard()

            Spacer()

            if model.phase == .approving {
                ProgressView().tint(VB.teal)
            } else {
                Button { model.approve(onApproved: onApproved) } label: {
                    Label("Approve with Face ID", systemImage: "faceid")
                }
                .buttonStyle(VBPrimaryButtonStyle())
            }
            Button { dismiss() } label: { Text("Not now") }
                .buttonStyle(VBSecondaryButtonStyle())
        }
    }

    private func infoRow(icon: String, label: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(VB.teal).frame(width: 22)
            Text(label).font(VB.rounded(14)).foregroundStyle(VB.textSecondary)
            Spacer()
        }
    }

    private func result(icon: String, tint: Color, title: String, subtitle: String) -> some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: icon).font(.system(size: 52)).foregroundStyle(tint)
            Text(title).font(VB.rounded(22, .bold))
            Text(subtitle).font(VB.rounded(15)).foregroundStyle(VB.textSecondary)
                .multilineTextAlignment(.center).padding(.horizontal, 20)
            Spacer()
            Button { dismiss() } label: { Text("Done") }.buttonStyle(VBSecondaryButtonStyle())
        }
    }

    private func host(_ origin: String) -> String { URL(string: origin)?.host ?? origin }
}

/// A live-updating expiry countdown row.
struct CountdownRow: View {
    let expiresAt: Date
    @State private var now = Date()
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var remaining: Int { max(0, Int(expiresAt.timeIntervalSince(now))) }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "clock").foregroundStyle(remaining > 0 ? VB.teal : VB.danger).frame(width: 22)
            Text(remaining > 0 ? "Expires in \(remaining)s" : "Expired")
                .font(VB.rounded(14)).foregroundStyle(remaining > 0 ? VB.textSecondary : VB.danger)
            Spacer()
        }
        .onReceive(timer) { now = $0 }
    }
}
