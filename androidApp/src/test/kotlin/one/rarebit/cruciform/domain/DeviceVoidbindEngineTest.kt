package one.rarebit.cruciform.domain

import kotlinx.coroutines.test.runTest
import one.rarebit.cruciform.platform.ApprovalPolicyStore
import one.rarebit.cruciform.platform.IdentityStore
import one.rarebit.cruciform.platform.StrongAuth
import one.rarebit.cruciform.testing.FakeBiometric
import one.rarebit.cruciform.testing.FakeTransport
import one.rarebit.cruciform.testing.InMemoryPrefs
import one.rarebit.cruciform.testing.InMemorySealer
import one.rarebit.cruciform.testing.SoftwareDeviceKeys
import one.rarebit.voidbind.AuthenticationRequiredException
import one.rarebit.voidbind.DeviceIdentity
import one.rarebit.voidbind.Enrolment
import one.rarebit.voidbind.KeyRef
import one.rarebit.voidbind.UserIdentity
import one.rarebit.voidbind.crypto.Base64Url
import one.rarebit.voidbind.net.HttpResponse
import one.rarebit.voidbind.policy.ApprovalPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * [DeviceVoidbindEngine] end to end over its seams: a software Ed25519 device key in
 * place of StrongBox, in-memory prefs/sealer in place of SharedPreferences/AndroidKeyStore,
 * a scripted HTTP transport and biometric. What stays real is everything the engine
 * itself decides — provisioning, the replica, the membership evaluation, the failure
 * classification — plus the library's actual crypto.
 *
 * Hardware non-extractability and the real biometric gate are device-tested
 * (docs/DEVICE-TESTING.md); nothing here claims otherwise.
 */
class DeviceVoidbindEngineTest {

    private val store = IdentityStore(InMemoryPrefs(), InMemorySealer())
    private val policyStore = ApprovalPolicyStore(InMemoryPrefs())
    private val transport = FakeTransport()
    private val biometric = FakeBiometric()
    private val keys = SoftwareDeviceKeys(HardwareBacking.TEE)
    private var relay = RELAY
    private var notify = ""

    private fun engine(membershipRps: List<String> = emptyList()) = DeviceVoidbindEngine(
        store = store,
        policyStore = policyStore,
        transport = transport,
        biometric = biometric,
        relay = { relay },
        notify = { notify },
        clock = { NOW },
        membershipRps = membershipRps,
        deviceKeys = keys,
        defaultDeviceName = { "Test Phone" },
    )

    private val selfId: String get() = KeyRef.ed25519(keys.publicKey).render()

    private fun active(engine: VoidbindEngine): IdentityState.Active = assertIs(engine.identity.value)

    private fun failure(result: EngineResult<*>): EngineFailure = assertIs<EngineResult.Failed>(result).failure

    /** A second member, admitted by genesis (the recovery secret) — as a Restore would admit it. */
    private fun admitSibling(rawSecret: String): String {
        val user = UserIdentity.restore(rawSecret)
        val sibling = SoftwareDeviceKeys().getOrCreate()
        val enc = DeviceIdentity.generateEncryptionKey()
        val device = DeviceIdentity(sibling.publicKey, enc.publicKey, enc.privateKey) { sibling.sign(it) }
        store.recordOps(listOf(Enrolment.selfEnrol(user, device, NOW)))
        return KeyRef.ed25519(sibling.publicKey).render()
    }

    // --- identity lifecycle ------------------------------------------------------

    @Test
    fun `refresh with nothing provisioned is None`() = runTest {
        val engine = engine()
        assertEquals(IdentityState.Loading, engine.identity.value)

        engine.refresh()

        assertEquals(IdentityState.None, engine.identity.value)
    }

    @Test
    fun `createIdentity provisions an owner and publishes Active`() = runTest {
        val engine = engine()

        val backup = engine.createIdentity()

        val user = UserIdentity.restore(backup.rawSecret)
        assertEquals(backup.rawSecret.chunked(4).joinToString(" "), backup.groupedSecret)
        val state = active(engine)
        assertEquals("Test Phone", state.identity.label)
        assertEquals(user.userId.render(), state.identity.fullKey)
        assertEquals(HardwareBacking.TEE, state.device.backing) // the key's REAL tier, not assumed
        assertTrue(state.device.label.startsWith("dev · "))
        assertTrue(store.hasUserKey())
        assertEquals(1, store.knownOps().size)
    }

    @Test
    fun `restoreIdentity re-derives the same user identity from the secret`() = runTest {
        val backup = engine().createIdentity()
        val createdKey = active(engine().apply { refresh() }).identity.fullKey

        val otherStore = IdentityStore(InMemoryPrefs(), InMemorySealer())
        val restored = DeviceVoidbindEngine(
            store = otherStore,
            policyStore = ApprovalPolicyStore(InMemoryPrefs()),
            transport = transport,
            biometric = biometric,
            relay = { relay },
            clock = { NOW },
            membershipRps = emptyList(),
            deviceKeys = SoftwareDeviceKeys(),
            defaultDeviceName = { "Second Phone" },
        )
        restored.restoreIdentity(backup.rawSecret)

        assertEquals(createdKey, active(restored).identity.fullKey)
        assertTrue(otherStore.hasUserKey())
    }

    @Test
    fun `restoreIdentity with a mistyped secret throws and provisions nothing`() = runTest {
        val engine = engine()

        assertFailsWith<Exception> { engine.restoreIdentity("heyarr1notarealsecret") }

        assertFalse(store.isProvisioned())
    }

    @Test
    fun `a lapsed key window prompts once and retries`() = runTest {
        keys.authRequiredLoads = 1

        engine().createIdentity()

        assertEquals(listOf("Authenticate"), biometric.prompts)
        assertTrue(store.isProvisioned())
    }

    @Test
    fun `a lapsed key window with the prompt cancelled rethrows and provisions nothing`() = runTest {
        keys.authRequiredLoads = 1
        biometric.presence = false

        assertFailsWith<AuthenticationRequiredException> { engine().createIdentity() }

        assertFalse(store.isProvisioned())
    }

    @Test
    fun `revealRecoverySecret is biometric-gated and returns the sealed secret`() = runTest {
        val engine = engine()
        val backup = engine.createIdentity()

        assertEquals(backup, engine.revealRecoverySecret())
        assertEquals(listOf("Show recovery secret"), biometric.prompts)

        biometric.presence = false
        assertFailsWith<IllegalArgumentException> { engine.revealRecoverySecret() }
    }

    @Test
    fun `revealRecoverySecret refuses on a device that holds no secret`() = runTest {
        assertFailsWith<IllegalStateException> { engine().revealRecoverySecret() }
        assertTrue(biometric.prompts.isEmpty())
    }

    @Test
    fun `parseScanned never throws on junk`() {
        assertEquals(ScannedCode.Unknown("not a voidbind code"), engine().parseScanned("not a voidbind code"))
    }

    // --- web login -----------------------------------------------------------------

    private val login = ScannedCode.WebLogin(RP, "L1", "voidbind:login?…")

    private fun challengeJson(): String {
        val nonce = Base64Url.encode(ByteArray(32) { 1 })
        return """{"id":"L1","nonce":"$nonce","audience":"$RP","expires_at":${NOW + 60}}"""
    }

    private fun rpServes(approveStatus: Int = 204) {
        transport.handler = { method, url, _ ->
            when {
                method == "GET" && url == "$RP/login/L1/challenge" ->
                    HttpResponse(200, challengeJson().encodeToByteArray())

                method == "POST" && url == "$RP/login/L1/approve" -> HttpResponse(approveStatus, ByteArray(0))

                else -> HttpResponse(404, ByteArray(0))
            }
        }
    }

    @Test
    fun `fetchLoginRequest without an identity fails cleanly`() = runTest {
        val result = engine().fetchLoginRequest(login)

        assertEquals(LoginRequestResult.Failed("No identity on this device."), result)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `fetchLoginRequest maps an unreachable RP to Failed, not a throw`() = runTest {
        val engine = engine()
        engine.createIdentity()

        val result = assertIs<LoginRequestResult.Failed>(engine.fetchLoginRequest(login))

        assertFalse(result.expired)
        // Nothing is pending, so an approve now is a precondition failure.
        assertFailsWith<IllegalStateException> { engine.approveLogin(login) }
    }

    @Test
    fun `fetchLoginRequest maps a 404 challenge to expired`() = runTest {
        val engine = engine()
        engine.createIdentity()
        transport.handler = { _, _, _ -> HttpResponse(404, ByteArray(0)) }

        assertTrue(assertIs<LoginRequestResult.Failed>(engine.fetchLoginRequest(login)).expired)
    }

    @Test
    fun `approving a fetched login signs, trusts the site and records the approval`() = runTest {
        val engine = engine()
        engine.createIdentity()
        rpServes()

        val ready = assertIs<LoginRequestResult.Ready>(engine.fetchLoginRequest(login)).request
        assertEquals("rp.example.test", ready.domain)
        assertEquals(60, ready.expiresInSeconds)
        assertTrue(ready.candidates.isEmpty())

        engine.approveLogin(login)

        assertTrue(transport.requests.contains("POST $RP/login/L1/approve"))
        assertEquals(listOf("rp.example.test"), active(engine).trustedSites.map { it.id })
        val activity = engine.approvalActivity()
        assertEquals(1, activity.size)
        assertTrue(activity.single().approved)
        assertEquals("just now", activity.single().whenLabel)
        // The pending login is consumed.
        assertFailsWith<IllegalStateException> { engine.approveLogin(login) }
    }

    @Test
    fun `an RP refusing the approval throws and trusts nothing`() = runTest {
        val engine = engine()
        engine.createIdentity()
        rpServes(approveStatus = 403)
        engine.fetchLoginRequest(login)

        assertFailsWith<IllegalArgumentException> { engine.approveLogin(login) }

        assertTrue(active(engine).trustedSites.isEmpty())
        assertTrue(engine.approvalActivity().isEmpty())
    }

    @Test
    fun `denying a fetched login records a denial and signs nothing`() = runTest {
        val engine = engine()
        engine.createIdentity()
        rpServes()
        engine.fetchLoginRequest(login)

        engine.denyLogin()

        assertFalse(transport.requests.any { it.startsWith("POST") })
        assertFalse(engine.approvalActivity().single().approved)
        assertTrue(active(engine).trustedSites.isEmpty())
    }

    @Test
    fun `denyLogin with nothing pending is a no-op`() = runTest {
        val engine = engine()
        engine.denyLogin()
        assertTrue(engine.approvalActivity().isEmpty())
    }

    // --- push ------------------------------------------------------------------------

    @Test
    fun `registerForPush is false without an identity or a configured plane`() = runTest {
        val engine = engine()
        notify = NOTIFY
        assertFalse(engine.registerForPush("https://push.example.test/up/abc"))

        engine.createIdentity()
        notify = ""
        assertFalse(engine.registerForPush("https://push.example.test/up/abc"))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `registerForPush swallows a transport failure`() = runTest {
        val engine = engine()
        engine.createIdentity()
        notify = NOTIFY

        assertFalse(engine.registerForPush("https://push.example.test/up/abc"))
        assertEquals(1, transport.requests.size)
        engine.unregisterFromPush() // best-effort: must not throw either
    }

    @Test
    fun `registerForPush succeeds when the plane accepts`() = runTest {
        val engine = engine()
        engine.createIdentity()
        notify = NOTIFY
        transport.handler = { _, _, _ ->
            val body = """{"user_id":"u","device_key":"d","channel":"ntfy","expires_at":1}"""
            HttpResponse(200, body.encodeToByteArray())
        }

        assertTrue(engine.registerForPush("https://push.example.test/up/abc"))
        assertTrue(transport.requests.single().startsWith("POST $NOTIFY"))
    }

    // --- pairing: every failure is a value, never a throw ----------------------------

    @Test
    fun `startPairInvite with no relay configured says so without dialling`() = runTest {
        val engine = engine()
        engine.createIdentity()
        relay = ""

        val f = failure(engine.startPairInvite())

        assertEquals(EngineFailure.Kind.REJECTED, f.kind)
        assertFalse(f.retryable)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `startPairInvite without an identity is an internal failure`() = runTest {
        assertEquals(EngineFailure.Kind.INTERNAL, failure(engine().startPairInvite()).kind)
    }

    @Test
    fun `startPairInvite against an unreachable relay is retryable UNREACHABLE naming the host`() = runTest {
        val engine = engine()
        engine.createIdentity()

        val f = failure(engine.startPairInvite())

        assertEquals(EngineFailure.Kind.UNREACHABLE, f.kind)
        assertTrue(f.retryable)
        assertTrue(f.message, f.message.contains("relay.example.test"))
    }

    @Test
    fun `awaitPairHandshake and confirmPairing with nothing in progress are internal failures`() = runTest {
        val engine = engine()
        engine.createIdentity()

        assertEquals(EngineFailure.Kind.INTERNAL, failure(engine.awaitPairHandshake()).kind)
        assertEquals(EngineFailure.Kind.INTERNAL, failure(engine.confirmPairing()).kind)
    }

    @Test
    fun `joinPairInvite with a malformed invite is a non-retryable protocol failure`() = runTest {
        val f = failure(engine().joinPairInvite(ScannedCode.PairInvite(RELAY, "s1", "voidbind:pair?garbage")))

        assertEquals(EngineFailure.Kind.PROTOCOL, f.kind)
        assertFalse(f.retryable)
    }

    // --- devices (membership) ----------------------------------------------------

    @Test
    fun `devices lists this device first, admitted by genesis`() = runTest {
        val engine = engine()
        assertTrue(engine.devices().isEmpty())
        val backup = engine.createIdentity()
        val sibling = admitSibling(backup.rawSecret)

        val devices = engine.devices()

        assertEquals(listOf(selfId, sibling), devices.map { it.id })
        assertTrue(devices.first().isThisDevice)
        assertFalse(devices.last().isThisDevice)
        assertEquals("genesis (recovery key)", devices.first().admittedByLabel)
    }

    @Test
    fun `removeDevice refuses this device, a non-member and a missing identity`() = runTest {
        val engine = engine()
        assertEquals(EngineFailure.Kind.INTERNAL, failure(engine.removeDevice(selfId)).kind)

        engine.createIdentity()
        assertTrue(failure(engine.removeDevice(selfId)).message.contains("can't remove itself"))
        assertTrue(failure(engine.removeDevice("ed25519:" + "00".repeat(32))).message.contains("not a member"))
        assertTrue(biometric.prompts.isEmpty()) // refused before any prompt
    }

    @Test
    fun `removeDevice needs a strong biometric`() = runTest {
        val engine = engine()
        val sibling = admitSibling(engine.createIdentity().rawSecret)

        biometric.strong = StrongAuth.CANCELLED
        assertEquals(EngineFailure.Kind.CANCELLED, failure(engine.removeDevice(sibling)).kind)

        biometric.strong = StrongAuth.UNAVAILABLE
        val f = failure(engine.removeDevice(sibling))
        assertFalse(f.retryable)
        assertTrue(f.message.contains("fingerprint or face"))

        assertEquals(2, engine.devices().size) // nothing was signed
    }

    @Test
    fun `removeDevice signs a remove, drops the member and pushes the replica to each RP`() = runTest {
        val engine = engine(membershipRps = listOf("$RP/", "https://down.example.test"))
        val sibling = admitSibling(engine.createIdentity().rawSecret)
        val usr = active(engine).identity.fullKey
        transport.handler = { _, url, _ ->
            if (url.startsWith(RP)) HttpResponse(200, ByteArray(0)) else throw java.io.IOException("down")
        }

        assertEquals(EngineResult.Ready(Unit), engine.removeDevice(sibling))

        assertEquals(listOf(selfId), engine.devices().map { it.id })
        assertEquals(3, store.knownOps().size) // genesis add, sibling add, the remove
        assertEquals(
            listOf("POST $RP/membership/$usr", "POST https://down.example.test/membership/$usr"),
            transport.requests,
        )
    }

    // --- settings + policy -------------------------------------------------------

    @Test
    fun `settings changes are persisted and republished`() = runTest {
        val engine = engine()
        engine.createIdentity()

        engine.renameDevice("Work phone")
        engine.setBiometricApproval(false)

        val state = active(engine)
        assertEquals("Work phone", state.device.name)
        assertEquals("Work phone", state.identity.label)
        assertFalse(state.biometricApproval)
    }

    @Test
    fun `site policy toggles between trusted and pinned always-ask, and revoke forgets it`() = runTest {
        val engine = engine()
        engine.createIdentity()
        assertEquals(
            SitePolicyView("a.example", ApprovalPolicy.AlwaysAsk, pinnedAlwaysAsk = false),
            engine.sitePolicy("a.example"),
        )

        engine.setAlwaysAsk("a.example", alwaysAsk = false)
        assertTrue(engine.sitePolicy("a.example").trusted)

        engine.setAlwaysAsk("a.example", alwaysAsk = true)
        assertTrue(engine.sitePolicy("a.example").pinnedAlwaysAsk)
        assertFalse(engine.sitePolicy("a.example").trusted)

        store.upsertTrustedSite(TrustedSite("a.example", "a.example", "", "just now"))
        engine.refresh()
        assertTrue(active(engine).trustedSites.single().pinnedAlwaysAsk) // policy joined onto the row

        engine.revokeSite("a.example")
        assertTrue(active(engine).trustedSites.isEmpty())
        assertFalse(engine.sitePolicy("a.example").pinnedAlwaysAsk)
    }

    private companion object {
        const val NOW = 1_800_000_000L
        const val RELAY = "https://relay.example.test/pair"
        const val NOTIFY = "https://notify.example.test"
        const val RP = "https://rp.example.test"
    }
}
