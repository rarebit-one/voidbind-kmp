package one.rarebit.cruciform.ui.flow

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hands a scanned recovery secret from the scanner to the Restore or drill field.
 *
 * Memory only, and deliberately NOT a `SavedStateHandle` result: a back-stack entry's
 * saved state is parcelled into the activity's saved-instance Bundle, and the recovery
 * secret never goes there (see [OnboardingViewModel]). The field [consume]s it the
 * moment it fills itself, so the secret is held here for one frame, not the session.
 */
class ScannedSecretViewModel : ViewModel() {
    private val _secret = MutableStateFlow<String?>(null)

    /** The scanned secret waiting for its field, or null. */
    val secret: StateFlow<String?> = _secret.asStateFlow()

    fun deliver(secret: String) {
        _secret.value = secret
    }

    /** The field took it: forget it. */
    fun consume() {
        _secret.value = null
    }

    override fun onCleared() {
        consume()
    }
}
