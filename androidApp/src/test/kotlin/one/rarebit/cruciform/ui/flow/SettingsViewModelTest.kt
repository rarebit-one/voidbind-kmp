package one.rarebit.cruciform.ui.flow

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import one.rarebit.cruciform.domain.EngineFailure
import one.rarebit.cruciform.domain.EngineResult
import one.rarebit.cruciform.domain.MemberDevice
import one.rarebit.cruciform.domain.TrustedSite
import one.rarebit.cruciform.platform.EndpointSetting
import one.rarebit.cruciform.platform.RelayConfig
import one.rarebit.cruciform.testing.ScriptedEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    /** An in-memory endpoint setting with the real validation rules. */
    private class FakeSetting(private val default: String) : EndpointSetting {
        var stored: String? = null
        override fun current() = stored ?: default
        override fun isDefault() = stored == null
        override fun set(input: String): RelayConfig.Validation = RelayConfig.validate(input).also {
            if (it is RelayConfig.Validation.Valid) stored = it.url
        }
        override fun reset() {
            stored = null
        }
    }

    private val engine = ScriptedEngine()
    private val relay = FakeSetting(default = "https://relay.example.test/pair")
    private val notify = FakeSetting(default = "")
    private val saved = SavedStateHandle()
    private fun vm(state: SavedStateHandle = saved) = SettingsViewModel(engine, relay, notify, state)

    @Test
    fun `the relay field starts at the persisted value`() {
        val s = vm().relay.state.value
        assertEquals("https://relay.example.test/pair", s.url)
        assertTrue(s.isDefault)
        assertEquals(s.url, s.draft)
    }

    @Test
    fun `an unsaved draft survives process death`() {
        vm().relay.onDraftChange("https://relay.other.test/pa")

        // A new ViewModel over the saved state the old one left behind.
        val restored = vm(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))

        assertEquals("https://relay.other.test/pa", restored.relay.state.value.draft)
        assertEquals("https://relay.example.test/pair", restored.relay.state.value.url) // nothing was saved
    }

    @Test
    fun `saving a valid URL persists it and re-seeds the draft`() {
        val vm = vm()
        vm.relay.onDraftChange("https://relay.other.test/pair/")

        val v = vm.relay.save("https://relay.other.test/pair/")

        assertTrue(v is RelayConfig.Validation.Valid)
        val s = vm.relay.state.value
        assertEquals(relay.current(), s.url)
        assertFalse(s.isDefault)
        assertEquals(relay.current(), s.draft)
        assertNull(saved.get<String>(SettingsViewModel.KEY_RELAY_DRAFT))
    }

    @Test
    fun `saving an invalid URL writes nothing and keeps the draft`() {
        val vm = vm()
        vm.relay.onDraftChange("not a url")

        assertTrue(vm.relay.save("not a url") is RelayConfig.Validation.Invalid)

        assertNull(relay.stored)
        assertEquals("not a url", vm.relay.state.value.draft)
    }

    @Test
    fun `reset drops the override`() {
        relay.stored = "https://relay.other.test"
        val vm = vm()

        vm.notify.onDraftChange("https://push.example.test")
        vm.relay.reset()

        assertTrue(vm.relay.state.value.isDefault)
        assertEquals("https://push.example.test", vm.notify.state.value.draft) // fields are independent
    }

    @Test
    fun `the revealed recovery secret is held in memory only`() = runTest {
        val vm = vm()

        vm.revealRecovery()

        assertEquals(SettingsViewModel.Event.ShowRecovery, vm.eventFlow.first())
        assertEquals(ScriptedEngine.BACKUP, vm.revealed.value)
        // Never in the saved-state Bundle.
        assertTrue(saved.keys().none { saved.get<Any>(it).toString().contains(ScriptedEngine.BACKUP.rawSecret) })
        vm.clearRecovery()
        assertNull(vm.revealed.value)
    }

    @Test
    fun `a cancelled reveal is a dialog, not a screen`() {
        engine.revealResult = ScriptedEngine.failure(EngineFailure.Kind.CANCELLED)
        val vm = vm()

        vm.revealRecovery()

        assertEquals(EngineFailure.Kind.CANCELLED, vm.error.value?.failure?.kind)
        assertNull(vm.revealed.value)
    }

    @Test
    fun `devices load before Devices opens and reload after a remove`() = runTest {
        val a = device("a", self = true)
        val b = device("b", self = false)
        engine.devicesResult = EngineResult.Ready(listOf(a, b))
        val vm = vm()

        vm.loadDevices(open = true)
        assertEquals(SettingsViewModel.Event.ShowDevices, vm.eventFlow.first())
        assertEquals(listOf(a, b), vm.devices.value)

        engine.devicesResult = EngineResult.Ready(listOf(a))
        vm.removeDevice(b)

        assertTrue("removeDevice:b" in engine.calls)
        assertEquals(listOf(a), vm.devices.value)
    }

    @Test
    fun `a refused remove is a dialog and keeps the list`() {
        val a = device("a", self = true)
        engine.devicesResult = EngineResult.Ready(listOf(a))
        engine.removeResult = ScriptedEngine.failure(EngineFailure.Kind.INTERNAL, "This device can't remove itself.")
        val vm = vm()
        vm.loadDevices(open = false)

        vm.removeDevice(a)

        assertEquals("This device can't remove itself.", vm.error.value?.failure?.message)
        assertEquals(listOf(a), vm.devices.value)
    }

    @Test
    fun `settings toggles call the engine and surface failures`() {
        val vm = vm()
        vm.setBiometricApproval(false)
        vm.revoke(TrustedSite("a.example", "a.example", "", "just now"))
        assertEquals(listOf("setBiometricApproval:false", "revokeSite:a.example"), engine.calls)
        assertNull(vm.error.value)

        engine.settingsResult = ScriptedEngine.failure(EngineFailure.Kind.INTERNAL, "Couldn't revoke a.example.")
        vm.revoke(TrustedSite("a.example", "a.example", "", "just now"))
        assertEquals("Couldn't revoke a.example.", vm.error.value?.failure?.message)
    }

    @Test
    fun `Change relay focuses the field once`() {
        val vm = vm()
        vm.setRelayFocus(true)
        assertTrue(vm.focusRelay.value)
        vm.setRelayFocus(false)
        assertFalse(vm.focusRelay.value)
    }

    private fun device(id: String, self: Boolean) = MemberDevice(id, id.uppercase(), self, "genesis", "1 Sep", "30 Nov")
}
