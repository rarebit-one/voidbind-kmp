import SwiftUI

/// The app's root: show Home once this device is enrolled, otherwise onboarding.
struct RootView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        if model.isEnrolled {
            HomeView(model: model)
        } else {
            OnboardingHost(model: model)
        }
    }
}

/// Owns the onboarding view model and routes a completed enrolment back into the
/// ``AppModel`` (persist + switch to Home).
struct OnboardingHost: View {
    @ObservedObject var model: AppModel
    @StateObject private var vm: OnboardingViewModel

    init(model: AppModel) {
        self.model = model
        _vm = StateObject(wrappedValue: OnboardingViewModel(engine: model.engine))
    }

    var body: some View {
        OnboardingView(model: vm, engine: model.engine)
            .onAppear {
                vm.onEnrolled = { userId, cert in
                    model.completeEnrolment(userId: userId, cert: cert)
                }
            }
    }
}
