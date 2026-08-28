import SwiftUI

/// The home dashboard: who you are (identity fingerprint), where your key lives
/// (Secure Enclave hardware card), the sites you've signed into (trusted sites),
/// and the primary action — Scan — as a floating button.
struct HomeView: View {
    @ObservedObject var model: AppModel
    @State private var showScan = false
    @State private var showSettings = false

    private var userId: String {
        if case let .enrolled(userId, _) = model.enrolment { return userId }
        return ""
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            ScrollView {
                VStack(spacing: 16) {
                    identityCard
                    hardwareCard
                    trustedSites
                    Color.clear.frame(height: 88)   // room for the FAB
                }
                .padding(.horizontal, 18)
                .padding(.top, 8)
            }

            scanFAB
                .padding(.trailing, 22)
                .padding(.bottom, 28)
        }
        .vbScreen()
        .safeAreaInset(edge: .top) { header }
        .sheet(isPresented: $showScan) {
            NavigationStack { ScanView(engine: model.engine) { origin in
                model.recordLogin(origin: origin)
            } }
        }
        .sheet(isPresented: $showSettings) {
            NavigationStack { SettingsView(model: model) }
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack {
            Text("Voidbind").font(VB.rounded(20, .bold))
            Spacer()
            Button { showSettings = true } label: {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(VB.textSecondary)
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 12)
        .background(VB.bg.opacity(0.97))
    }

    // MARK: - Identity

    private var identityCard: some View {
        VStack(spacing: 14) {
            ZStack {
                Circle().fill(VB.teal.opacity(0.12)).frame(width: 72, height: 72)
                Image(systemName: "person.fill.checkmark")
                    .font(.system(size: 28, weight: .semibold)).foregroundStyle(VB.teal)
            }
            Text("This device is enrolled").font(VB.rounded(17, .semibold))
            VStack(spacing: 3) {
                Text("IDENTITY FINGERPRINT").font(VB.rounded(11, .semibold)).foregroundStyle(VB.textFaint)
                Text(Fingerprint.short(userId)).font(VB.mono(18, .medium)).foregroundStyle(VB.teal)
            }
        }
        .frame(maxWidth: .infinity)
        .vbCard(padding: 22)
    }

    // MARK: - Hardware

    private var hardwareCard: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 12).fill(VB.teal.opacity(0.12)).frame(width: 46, height: 46)
                Image(systemName: "cpu.fill").font(.system(size: 20, weight: .semibold)).foregroundStyle(VB.teal)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text("Secure Enclave").font(VB.rounded(16, .semibold))
                Text("Your signing key is sealed in hardware and gated by Face ID.")
                    .font(VB.rounded(13)).foregroundStyle(VB.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 4)
            VBPill(text: "Sealed", systemImage: "lock.fill")
        }
        .vbCard()
    }

    // MARK: - Trusted sites

    private var trustedSites: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Trusted sites").font(VB.rounded(16, .semibold))
            if model.trustedSites.isEmpty {
                HStack(spacing: 10) {
                    Image(systemName: "globe").foregroundStyle(VB.textFaint)
                    Text("No sites yet. Scan a sign-in QR to approve one.")
                        .font(VB.rounded(14)).foregroundStyle(VB.textSecondary)
                }
                .padding(.vertical, 6)
            } else {
                ForEach(model.trustedSites) { site in
                    HStack(spacing: 12) {
                        Image(systemName: "globe.badge.chevron.backward")
                            .foregroundStyle(VB.teal)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(site.host).font(VB.rounded(15, .medium))
                            Text("Last used \(site.lastUsed.formatted(.relative(presentation: .named)))")
                                .font(VB.rounded(12)).foregroundStyle(VB.textFaint)
                        }
                        Spacer()
                        VBPill(text: "Trusted", tint: VB.good)
                    }
                    if site.id != model.trustedSites.last?.id { Divider().overlay(VB.hairline) }
                }
            }
        }
        .vbCard()
    }

    // MARK: - Scan FAB

    private var scanFAB: some View {
        Button { showScan = true } label: {
            Image(systemName: "qrcode.viewfinder")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(Color(hex: 0x04110F))
                .frame(width: 64, height: 64)
                .background(VB.teal, in: Circle())
                .shadow(color: VB.teal.opacity(0.4), radius: 14, y: 6)
        }
    }
}
