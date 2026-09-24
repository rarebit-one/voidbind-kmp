package one.rarebit.cruciform.ui.flow

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import one.rarebit.cruciform.domain.EngineFailure
import one.rarebit.cruciform.domain.EngineResult
import one.rarebit.cruciform.domain.ScannedCode
import one.rarebit.cruciform.testing.ScriptedEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PairViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val engine = ScriptedEngine()
    private val saved = SavedStateHandle()
    private val vm = PairViewModel(engine, saved)
    private val invite = engine.parseScanned(ScriptedEngine.INVITE) as ScannedCode.PairInvite

    @Test
    fun `join runs the handshake, shows VERIFY and persists the invite`() = runTest {
        vm.join(invite, fromScan = true)

        assertEquals(PairViewModel.Event.Verify(fromScan = true), vm.eventFlow.first())
        assertEquals(ScriptedEngine.SESSION, vm.session.value)
        assertEquals(ScriptedEngine.INVITE, saved.get<String>(PairViewModel.KEY_INVITE))
    }

    @Test
    fun `a retryable join failure offers a Retry that re-joins the SAME invite`() = runTest {
        engine.joinResult = ScriptedEngine.failure(EngineFailure.Kind.UNREACHABLE)
        vm.join(invite, fromScan = false)
        val error = assertNotNull(vm.error.value)
        engine.joinResult = EngineResult.Ready(ScriptedEngine.SESSION)

        error.retry!!.invoke()

        assertEquals(PairViewModel.Event.Verify(fromScan = false), vm.eventFlow.first())
        assertEquals(2, engine.calls.count { it == "joinPairInvite:${ScriptedEngine.INVITE}" })
        assertNull(vm.error.value)
    }

    @Test
    fun `a protocol failure has no Retry`() = runTest {
        engine.joinResult = ScriptedEngine.failure(EngineFailure.Kind.PROTOCOL)

        vm.join(invite, fromScan = false)

        assertNull(vm.error.value!!.retry)
    }

    @Test
    fun `confirm admits`() = runTest {
        vm.confirm()
        assertEquals(PairViewModel.Event.Admitted, vm.eventFlow.first())
    }

    @Test
    fun `a failed confirm reports whether it can be retried`() = runTest {
        engine.confirmResult = ScriptedEngine.failure(EngineFailure.Kind.CANCELLED)

        vm.confirm()

        assertEquals(PairViewModel.Event.ConfirmFailed(retryable = false), vm.eventFlow.first())
        assertEquals(EngineFailure.Kind.CANCELLED, vm.error.value?.failure?.kind)
    }

    @Test
    fun `after process death VERIFY offers to re-join the persisted invite`() = runTest {
        val restored = PairViewModel(engine, SavedStateHandle(mapOf(PairViewModel.KEY_INVITE to ScriptedEngine.INVITE)))
        assertNull(restored.session.value)

        assertTrue(restored.resumeInterrupted())

        val error = assertNotNull(restored.error.value)
        assertEquals(PairViewModel.INTERRUPTED, error.failure.message)
        error.retry!!.invoke()
        assertEquals(PairViewModel.Event.Verify(fromScan = false), restored.eventFlow.first())
        assertEquals(ScriptedEngine.SESSION, restored.session.value)
    }

    @Test
    fun `dismissing the interrupted dialog forgets the invite`() {
        val restored = PairViewModel(engine, SavedStateHandle(mapOf(PairViewModel.KEY_INVITE to ScriptedEngine.INVITE)))
        restored.resumeInterrupted()

        restored.error.value!!.onDismiss()

        assertNull(restored.error.value)
        assertNull(restored.pendingInvite.value)
    }

    @Test
    fun `resumeInterrupted with nothing persisted is false`() {
        assertFalse(vm.resumeInterrupted())
    }

    @Test
    fun `finish forgets the invite`() = runTest {
        vm.join(invite, fromScan = false)
        vm.finish()
        assertNull(vm.pendingInvite.value)
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
