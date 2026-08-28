import SwiftUI
import Voidbind

/// The SwiftUI app entry. Builds the ``VoidbindEngine`` once (which injects the
/// Swift Secure Enclave sealer into the KMP library) and hands it to the views.
///
/// The full screen set from Jaryl's mockups (home dashboard, QR scanner,
/// web-login approval sheet, pair connect/verify, recovery backup, settings) is
/// fleshed out screen by screen; each view binds to a ``VoidbindEngine``
/// coordinator exactly as ``OnboardingViewModel`` does.
///
/// > Builds + runs in the iOS Simulator via the XcodeGen project (see
/// > iosApp/README.md). The biometric/Secure-Enclave paths still need a real
/// > device (docs/DEVICE-TESTING.md).
@main
struct VoidbindApp: App {
    @StateObject private var model = OnboardingViewModel(engine: VoidbindEngine())

    var body: some Scene {
        WindowGroup {
            OnboardingView(model: model)
        }
    }
}
