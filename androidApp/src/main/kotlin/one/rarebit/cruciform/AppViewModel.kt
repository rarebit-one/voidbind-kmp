package one.rarebit.cruciform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import one.rarebit.cruciform.domain.VoidbindEngine

/**
 * Owns the [VoidbindEngine] across configuration changes and exposes its identity
 * state to the nav graph. Action methods live on the engine and are invoked from
 * screens (each manages its own transient busy/error state), keeping this thin.
 */
class AppViewModel(val engine: VoidbindEngine) : ViewModel() {
    val identity = engine.identity

    init {
        viewModelScope.launch { engine.refresh() }
    }
}
