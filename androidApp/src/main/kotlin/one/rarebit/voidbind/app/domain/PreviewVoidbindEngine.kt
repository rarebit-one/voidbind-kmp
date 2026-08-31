package one.rarebit.voidbind.app.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.rarebit.voidbind.policy.ApprovalPolicy
import one.rarebit.voidbind.policy.ApprovalPolicyManager
import one.rarebit.voidbind.policy.InMemoryApprovalAuditLog
import one.rarebit.voidbind.policy.InMemorySitePolicyStore

/**
 * A fully in-memory engine seeded with the mockup data, so the entire UI runs and
 * looks exactly like the design before the real coordinators + hardware are wired.
 * Used by @Preview and as the app's initial backend. It performs NO cryptography and
 * NO I/O — it is scaffolding, and every value here is placeholder content, not a
 * real identity. The real [VoidbindEngine] replaces it as a single DI swap.
 */
class PreviewVoidbindEngine(
    initial: IdentityState = SampleData.activeState,
) : VoidbindEngine {

    private val _identity = MutableStateFlow(initial)
    override val identity: StateFlow<IdentityState> = _identity.asStateFlow()

    // A real (in-memory) policy manager so the preview exercises TOFU + the audit log
    // exactly as the device engine does, just without persistence or a clock of record.
    private var previewClock = 1_724_000_000L
    private val policy = ApprovalPolicyManager(InMemorySitePolicyStore(), InMemoryApprovalAuditLog()) { previewClock }

    init {
        // Seed a plausible activity history + one trusted site so the log/settings screens
        // have something to render under @Preview.
        policy.trust("thesim.family")
        policy.recordApproval("thesim.family", "https://thesim.family", "L-1001")
        previewClock += 3_600
        policy.recordDenial("unknown.example", "https://unknown.example", "L-1002")
    }

    override suspend fun refresh() { /* already seeded */ }

    override suspend fun createIdentity(): RecoveryBackup {
        delay(400)
        _identity.value = SampleData.activeState
        return SampleData.recoveryBackup
    }

    override suspend fun restoreIdentity(recoverySecret: String) {
        delay(400)
        _identity.value = SampleData.activeState
    }

    override suspend fun revealRecoverySecret(): RecoveryBackup {
        delay(200)
        return SampleData.recoveryBackup
    }

    override fun parseScanned(raw: String): ScannedCode = when {
        raw.startsWith("voidbind:login") -> ScannedCode.WebLogin("https://thesim.family", "sample-login-id", raw)
        raw.startsWith("voidbind:pair") -> ScannedCode.PairInvite("wss://relay.thesim.family", "sample-session", raw)
        else -> ScannedCode.Unknown(raw)
    }

    override suspend fun fetchLoginRequest(code: ScannedCode.WebLogin): LoginRequestResult {
        delay(300)
        return LoginRequestResult.Ready(SampleData.loginRequest)
    }

    override suspend fun approveLogin(code: ScannedCode.WebLogin) { delay(600) }

    override suspend fun approveNumberMatch(code: ScannedCode.WebLogin, chosen: Int) { delay(600) }

    override suspend fun denyLogin() { delay(100) }

    override suspend fun registerForPush(endpoint: String): Boolean { delay(150); return true }

    override suspend fun unregisterFromPush() { delay(150) }

    override suspend fun startPairInvite(): PairInviteDisplay {
        delay(200)
        return SampleData.pairInvite
    }

    override suspend fun awaitPairHandshake(): PairSession {
        delay(1500) // stand in for the new device joining the relay + handshake
        return SampleData.pairSession
    }

    override suspend fun joinPairInvite(code: ScannedCode.PairInvite): PairSession {
        delay(500)
        return SampleData.pairSession
    }

    override suspend fun confirmPairing() { delay(600) }

    override suspend fun renameDevice(name: String) {
        _identity.update { s ->
            if (s is IdentityState.Active) s.copy(device = s.device.copy(name = name)) else s
        }
    }

    override suspend fun setBiometricApproval(enabled: Boolean) {
        _identity.update { s -> if (s is IdentityState.Active) s.copy(biometricApproval = enabled) else s }
    }

    override suspend fun revokeSite(siteId: String) {
        policy.forget(siteId)
        _identity.update { s ->
            if (s is IdentityState.Active) s.copy(trustedSites = s.trustedSites.filterNot { it.id == siteId }) else s
        }
    }

    override suspend fun sitePolicy(rp: String): SitePolicyView {
        val p = policy.policyFor(rp)
        return SitePolicyView(rp, p?.policy ?: ApprovalPolicy.AlwaysAsk, p?.pinnedAlwaysAsk ?: false)
    }

    override suspend fun setAlwaysAsk(rp: String, alwaysAsk: Boolean) {
        if (alwaysAsk) policy.setAlwaysAsk(rp) else policy.trust(rp)
    }

    override suspend fun approvalActivity(limit: Int): List<ApprovalActivity> =
        policy.auditEntries(limit).map { ApprovalActivity.from(it, whenLabel = "recently") }
}

/** Sample content mirroring the product mockups. Placeholder only. */
object SampleData {
    val identity = Identity(
        version = "vb1",
        fingerprint = "7C4A 91D2 0E8F",
        fullKey = "ed25519:7c4a91d20e8f5b3a2c1d4e6f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b",
        offlineVerifiable = true,
    )

    val device = DeviceInfo(
        name = "Jaryl's Nothing Phone",
        label = "dev · A29F 67B1",
        backing = HardwareBacking.STRONGBOX,
        biometricRequired = true,
    )

    val trustedSites = listOf(
        TrustedSite("thesim", "thesim.family", "All Thing", "last used today", SiteAccent.BLUE, policy = ApprovalPolicy.TrustedTofu),
        TrustedSite("cove", "home.cove.lan", "Cove Control", "yesterday", SiteAccent.PURPLE, policy = ApprovalPolicy.AlwaysAsk, pinnedAlwaysAsk = true),
        TrustedSite("bartley", "bartley.home", "Home Assistant", "6 days ago", SiteAccent.MINT, policy = ApprovalPolicy.TrustedTofu),
    )

    val activeState = IdentityState.Active(
        identity = identity,
        device = device,
        trustedSites = trustedSites,
        biometricApproval = true,
    )

    val recoveryBackup = RecoveryBackup(
        groupedSecret = "heyarr1 r9k7 x4pm 2qvt 8c3n h6wy f0ad j5se u2lz",
        rawSecret = "heyarr1r9k7x4pm2qvt8c3nh6wyf0adj5seu2lz",
    )

    val loginRequest = LoginRequest(
        domain = "thesim.family",
        appName = "All Thing",
        origin = "https://thesim.family",
        signInAs = "vb1 · 7C4A…0E8F",
        expiresInSeconds = 58,
        access = "Authentication only",
        signatureValid = true,
    )

    /** A push-woken, number-matching login: the phone shows candidates, the user taps the match. */
    val numberMatchRequest = loginRequest.copy(candidates = listOf(27, 84, 61))

    val pairInvite = PairInviteDisplay(
        inviteId = "INV · 8F2C 91A7",
        qrPayload = "voidbind:pair?v=2&relay=wss://relay.thesim.family&session=8f2c91a7&salt=…",
        expiresInSeconds = 278,
    )

    val pairSession = PairSession(
        thisDeviceName = "Jaryl's Nothing Phone",
        peerDeviceName = "Cove Tablet",
        securityCode = "482 7316",
    )
}
