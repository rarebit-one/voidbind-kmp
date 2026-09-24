package one.rarebit.cruciform.ui.flow

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import one.rarebit.cruciform.domain.EngineFailure
import one.rarebit.cruciform.domain.EngineResult
import one.rarebit.cruciform.domain.RecoveryBackup
import one.rarebit.cruciform.domain.VoidbindEngine

/**
 * Creating an identity: provision it, then show the recovery secret to back up.
 * (Restoring passes the typed secret straight to the engine — it is never held here.)
 *
 * **Survives process death:** only a "creation started" flag. The recovery secret is
 * deliberately NOT persisted (memory only, never in the saved-state Bundle). What the
 * flag buys: [create] runs at most once per flow — a rotation keeps the secret on
 * screen instead of provisioning again (which the old composition-scoped state did),
 * and after process death the flow does not silently create a SECOND identity over the
 * first; it tells the user to back up the secret from Settings instead.
 */
class OnboardingViewModel(
    private val engine: VoidbindEngine,
    private val saved: SavedStateHandle,
) : ViewModel() {

    sealed interface Event {
        /** Creation failed ([error] is set): leave the create screen. */
        data object CreateFailed : Event

        /** The process died mid-creation ([error] explains): go Home. */
        data object Interrupted : Event
    }

    private val _backup = MutableStateFlow<RecoveryBackup?>(null)

    /** The new recovery secret to back up — memory only. */
    val backup: StateFlow<RecoveryBackup?> = _backup.asStateFlow()

    private val _error = MutableStateFlow<EngineErrorState?>(null)
    val error: StateFlow<EngineErrorState?> = _error.asStateFlow()

    private val events = Channel<Event>(Channel.BUFFERED)
    val eventFlow: Flow<Event> = events.receiveAsFlow()

    private var creating = false

    /** Provision a new identity, once. Biometric-gated: a cancelled prompt is a dialog. */
    fun create() {
        if (_backup.value != null || creating) return
        if (saved.get<Boolean>(KEY_STARTED) == true) {
            saved.remove<Boolean>(KEY_STARTED)
            _error.value = EngineErrorState(
                EngineFailure(INTERRUPTED, EngineFailure.Kind.INTERNAL, retryable = false),
                onDismiss = ::dismissError,
            )
            viewModelScope.launch { events.send(Event.Interrupted) }
            return
        }
        saved[KEY_STARTED] = true
        creating = true
        viewModelScope.launch {
            when (val result = engine.createIdentity()) {
                is EngineResult.Ready -> _backup.value = result.value

                is EngineResult.Failed -> {
                    saved.remove<Boolean>(KEY_STARTED)
                    _error.value = EngineErrorState(result.failure, onDismiss = ::dismissError)
                    events.send(Event.CreateFailed)
                }
            }
            creating = false
        }
    }

    /** Restore from a typed recovery secret; the result is the Restore screen's to show. */
    suspend fun restore(secret: String): EngineResult<Unit> = engine.restoreIdentity(secret)

    /** The backup screen is done (saved or backed out of): forget the secret and the flag. */
    fun finished() {
        saved.remove<Boolean>(KEY_STARTED)
        _backup.value = null
    }

    fun dismissError() {
        _error.value = null
    }

    companion object {
        const val KEY_STARTED = "onboarding.create.started"
        const val INTERRUPTED =
            "Cruciform was closed while your identity was being created. If it was created, " +
                "back up its recovery secret now from Settings → Recovery backup; otherwise, start again."
    }
}
