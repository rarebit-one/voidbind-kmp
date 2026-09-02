package one.rarebit.cruciform

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import one.rarebit.cruciform.domain.VoidbindEngine
import one.rarebit.cruciform.pairing.InviteCoordinator
import one.rarebit.cruciform.pairing.ProcessKeepAlive

/**
 * Owns the [VoidbindEngine] across configuration changes and exposes its identity
 * state to the nav graph. Action methods live on the engine and are invoked from
 * screens (each manages its own transient busy/error state), keeping this thin.
 *
 * Also owns the [InviteCoordinator] (ADR-0007): the initiator's invite + relay wait
 * live in [viewModelScope], so they survive the user switching to the relying-party
 * app on the same phone — a screen only observes them.
 */
class AppViewModel(
    val engine: VoidbindEngine,
    relayUrl: () -> String = { "" },
    keepAlive: ProcessKeepAlive = ProcessKeepAlive.None,
) : ViewModel() {
    val identity = engine.identity

    val invites = InviteCoordinator(
        engine = engine,
        scope = viewModelScope,
        relayUrl = relayUrl,
        keepAlive = keepAlive,
        log = { Log.d("PairInvite", it) },
    )

    init {
        viewModelScope.launch { engine.refresh() }
    }
}
