import Foundation
import SwiftUI
import Voidbind

/// A relying party this identity has signed into, shown on Home.
struct TrustedSite: Identifiable, Hashable {
    let id = UUID()
    let origin: String        // e.g. "https://thesim.family"
    let lastUsed: Date
    var host: String { URL(string: origin)?.host ?? origin }
}

/// Root app state: holds the ``VoidbindEngine``, the enrolled identity (persisted
/// across launches), and the list of trusted sites. Views observe it; the real
/// engine work runs off the main thread and publishes back here.
///
/// The enrolment cert + user id are public assertions (not secret material — the
/// signing seed stays in the Secure Enclave), so they persist in `UserDefaults`.
@MainActor
final class AppModel: ObservableObject {

    enum Enrolment: Equatable {
        case none
        case enrolled(userId: String, cert: String)
    }

    @Published var enrolment: Enrolment = .none
    @Published var trustedSites: [TrustedSite] = []

    let engine: VoidbindEngine

    private let defaults = UserDefaults.standard
    private enum Key { static let userId = "vb.userId", cert = "vb.cert" }

    init(engine: VoidbindEngine = VoidbindEngine(), preview: Bool = false) {
        self.engine = engine
        if preview { return }
        if let userId = defaults.string(forKey: Key.userId),
           let cert = defaults.string(forKey: Key.cert) {
            enrolment = .enrolled(userId: userId, cert: cert)
        }
    }

    var isEnrolled: Bool { if case .enrolled = enrolment { return true } else { return false } }

    /// Persist a freshly enrolled identity and move to Home.
    func completeEnrolment(userId: String, cert: String) {
        defaults.set(userId, forKey: Key.userId)
        defaults.set(cert, forKey: Key.cert)
        enrolment = .enrolled(userId: userId, cert: cert)
    }

    /// Forget this identity on this device (Settings → sign out). Does not destroy
    /// the account — the recovery secret still restores it.
    func signOut() {
        defaults.removeObject(forKey: Key.userId)
        defaults.removeObject(forKey: Key.cert)
        enrolment = .none
        trustedSites = []
    }

    func recordLogin(origin: String) {
        trustedSites.removeAll { $0.origin == origin }
        trustedSites.insert(TrustedSite(origin: origin, lastUsed: Date()), at: 0)
    }
}
