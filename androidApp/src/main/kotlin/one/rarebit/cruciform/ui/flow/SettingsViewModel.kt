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
import one.rarebit.cruciform.domain.ApprovalActivity
import one.rarebit.cruciform.domain.EngineFailure
import one.rarebit.cruciform.domain.EngineResult
import one.rarebit.cruciform.domain.MemberDevice
import one.rarebit.cruciform.domain.RecoveryBackup
import one.rarebit.cruciform.domain.TrustedSite
import one.rarebit.cruciform.domain.VoidbindEngine
import one.rarebit.cruciform.domain.failureOrNull
import one.rarebit.cruciform.domain.valueOrNull
import one.rarebit.cruciform.platform.EndpointSetting
import one.rarebit.cruciform.platform.RelayConfig

/**
 * Settings and the screens it opens: the pairing-relay / push-plane fields, biometric
 * approval, trusted sites, the recovery-secret reveal, the approval activity log and
 * the device set.
 *
 * **Survives process death:** the text typed into the relay and push-plane fields
 * that has not been saved yet (a URL — the saved value is in SharedPreferences
 * already). **Deliberately NOT persisted:** the revealed recovery secret and the
 * recovery shares split from it — each is held in memory only while its screen is up
 * and is never written to the saved-state Bundle (which the system may store outside
 * the app); after process death the screen closes and the user re-authenticates to see
 * it again (a new split gives a new, equally valid set). The activity
 * log and the device list are re-read from the engine when their screens open.
 */
class SettingsViewModel(
    private val engine: VoidbindEngine,
    relaySetting: EndpointSetting,
    notifySetting: EndpointSetting,
    saved: SavedStateHandle,
) : ViewModel() {

    sealed interface Event {
        data object ShowRecovery : Event
        data object ShowShares : Event
        data object ShowActivity : Event
        data object ShowDevices : Event
    }

    /** One endpoint field: the persisted value plus the (persisted) unsaved draft. */
    class EndpointEditor internal constructor(
        private val setting: EndpointSetting,
        private val saved: SavedStateHandle,
        private val draftKey: String,
    ) {
        data class State(val url: String, val isDefault: Boolean, val draft: String)

        private val _state = MutableStateFlow(
            State(setting.current(), setting.isDefault(), saved.get<String>(draftKey) ?: setting.current()),
        )
        val state: StateFlow<State> = _state.asStateFlow()

        fun onDraftChange(text: String) {
            saved[draftKey] = text
            _state.value = _state.value.copy(draft = text)
        }

        /** Validate + persist; the draft re-seeds from the saved (normalised) value. */
        fun save(input: String): RelayConfig.Validation = setting.set(input).also {
            if (it is RelayConfig.Validation.Valid) reseed()
        }

        fun reset() {
            setting.reset()
            reseed()
        }

        private fun reseed() {
            saved.remove<String>(draftKey)
            _state.value = State(setting.current(), setting.isDefault(), setting.current())
        }
    }

    val relay = EndpointEditor(relaySetting, saved, KEY_RELAY_DRAFT)
    val notify = EndpointEditor(notifySetting, saved, KEY_NOTIFY_DRAFT)

    // Set by the error dialog's "Change relay": the relay field takes focus once.
    private val _focusRelay = MutableStateFlow(false)
    val focusRelay: StateFlow<Boolean> = _focusRelay.asStateFlow()

    private val _revealed = MutableStateFlow<RecoveryBackup?>(null)

    /** The recovery secret while its screen is up — memory only, see the class doc. */
    val revealed: StateFlow<RecoveryBackup?> = _revealed.asStateFlow()

    private val _shares = MutableStateFlow<List<String>?>(null)

    /** The recovery shares while their screen is up — memory only, see the class doc. */
    val shares: StateFlow<List<String>?> = _shares.asStateFlow()

    private val _activity = MutableStateFlow<List<ApprovalActivity>>(emptyList())
    val activity: StateFlow<List<ApprovalActivity>> = _activity.asStateFlow()

    private val _devices = MutableStateFlow<List<MemberDevice>>(emptyList())
    val devices: StateFlow<List<MemberDevice>> = _devices.asStateFlow()

    private val _error = MutableStateFlow<EngineErrorState?>(null)
    val error: StateFlow<EngineErrorState?> = _error.asStateFlow()

    private val events = Channel<Event>(Channel.BUFFERED)
    val eventFlow: Flow<Event> = events.receiveAsFlow()

    /** True → the relay field takes focus (the dialog's "Change relay"); false once it has. */
    fun setRelayFocus(focus: Boolean) {
        _focusRelay.value = focus
    }

    fun setBiometricApproval(enabled: Boolean) {
        viewModelScope.launch { engine.setBiometricApproval(enabled).failureOrNull()?.let(::fail) }
    }

    fun revoke(site: TrustedSite) {
        viewModelScope.launch { engine.revokeSite(site.id).failureOrNull()?.let(::fail) }
    }

    /**
     * Check a written recovery secret against this identity (signs nothing). The drill
     * screen shows the returned line, or the failure inline.
     */
    suspend fun checkRecoverySecret(secret: String): EngineResult<String> {
        val result = engine.verifyRecoverySecret(secret)
        return when (result) {
            is EngineResult.Ready -> EngineResult.Ready("It matches this identity: ${result.value.fingerprint}")
            is EngineResult.Failed -> result
        }
    }

    /** Remove this phone's recovery copy (strong-biometric-gated); a refusal is a dialog. */
    fun forgetRecoverySecret() {
        viewModelScope.launch {
            val result = engine.forgetRecoverySecret()
            if (result is EngineResult.Failed) fail(result.failure)
        }
    }

    /** Biometric-gated reveal; a cancelled prompt is a dialog, not a crash. */
    fun revealRecovery() {
        viewModelScope.launch {
            when (val result = engine.revealRecoverySecret()) {
                is EngineResult.Ready -> {
                    _revealed.value = result.value
                    events.send(Event.ShowRecovery)
                }

                is EngineResult.Failed -> fail(result.failure)
            }
        }
    }

    /** Forget the revealed secret (its screen closed). */
    fun clearRecovery() {
        _revealed.value = null
    }

    /**
     * Split the kept recovery secret into 2-of-3 shares (strong-biometric-gated) and
     * open their screen; a refusal or a cancelled prompt is a dialog.
     */
    fun splitIntoShares() {
        viewModelScope.launch {
            when (val result = engine.splitRecoverySecret()) {
                is EngineResult.Ready -> {
                    _shares.value = result.value
                    events.send(Event.ShowShares)
                }

                is EngineResult.Failed -> fail(result.failure)
            }
        }
    }

    /** Forget the shares (their screen closed). */
    fun clearShares() {
        _shares.value = null
    }

    /**
     * Read the approval log; [open] then shows its screen (a failure is a dialog).
     * Without [open] it just refreshes a screen already up (e.g. after process death).
     */
    fun loadActivity(open: Boolean) {
        viewModelScope.launch {
            when (val result = engine.approvalActivity()) {
                is EngineResult.Ready -> {
                    _activity.value = result.value
                    if (open) events.send(Event.ShowActivity)
                }

                is EngineResult.Failed -> if (open) fail(result.failure)
            }
        }
    }

    /**
     * Evaluate the device set from this device's replica; [open] then shows Devices
     * (an unreadable set opens it empty, as before). Without [open] it refreshes a
     * screen already up.
     */
    fun loadDevices(open: Boolean) {
        viewModelScope.launch {
            val devices = engine.devices().valueOrNull()
            if (open) {
                _devices.value = devices ?: emptyList()
                events.send(Event.ShowDevices)
            } else if (devices != null) {
                _devices.value = devices
            }
        }
    }

    /** Remove another device; a Failed (cancelled prompt, not a member) is a dialog. */
    fun removeDevice(device: MemberDevice) {
        viewModelScope.launch {
            when (val result = engine.removeDevice(device.id)) {
                is EngineResult.Ready -> engine.devices().valueOrNull()?.let { _devices.value = it }
                is EngineResult.Failed -> fail(result.failure)
            }
        }
    }

    /** Renew this device's membership now; the identity state republishes on success. */
    fun renewMembership() {
        viewModelScope.launch {
            val result = engine.renewMembership()
            if (result is EngineResult.Failed) fail(result.failure)
        }
    }

    override fun onCleared() {
        clearRecovery()
        clearShares()
    }

    private fun fail(failure: EngineFailure) {
        _error.value = EngineErrorState(failure, onDismiss = { _error.value = null })
    }

    companion object {
        const val KEY_RELAY_DRAFT = "settings.relay.draft"
        const val KEY_NOTIFY_DRAFT = "settings.notify.draft"
    }
}
