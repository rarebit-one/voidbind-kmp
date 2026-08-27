import SwiftUI
import Voidbind

/// The SwiftUI app entry. Builds the ``VoidbindEngine`` once (which injects the
/// Swift Secure Enclave sealer into the KMP library) and hands it to the views.
///
/// This is the **iOS shell scaffold** — enough to prove the wiring pattern
/// end-to-end (onboarding → identity → first-device enrolment). The full screen
/// set from Jaryl's mockups (home dashboard, QR scanner, web-login approval sheet,
/// pair connect/verify, recovery backup, settings) is fleshed out in the on-device
/// session where SwiftUI + the Secure Enclave can actually be exercised; each view
/// binds to a ``VoidbindEngine`` coordinator exactly as ``OnboardingViewModel`` does.
///
/// > ⚠️ NOT COMPILED IN CI — needs an Xcode project linking `Voidbind.xcframework`
/// > (see iosApp/README.md) and a real device for the biometric/Enclave paths.
@main
struct VoidbindApp: App {
    @StateObject private var model = OnboardingViewModel(engine: VoidbindEngine())

    var body: some Scene {
        WindowGroup {
            OnboardingView(model: model)
        }
    }
}
