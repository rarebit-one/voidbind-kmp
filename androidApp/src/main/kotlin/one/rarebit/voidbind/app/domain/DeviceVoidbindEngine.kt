package one.rarebit.voidbind.app.domain

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import one.rarebit.voidbind.AuthenticationRequiredException
import one.rarebit.voidbind.Cert
import one.rarebit.voidbind.DeviceIdentity
import one.rarebit.voidbind.DeviceKeyStore
import one.rarebit.voidbind.Enrolment
import one.rarebit.voidbind.LoginQr
import one.rarebit.voidbind.RecoverySecret
import one.rarebit.voidbind.UserIdentity
import one.rarebit.voidbind.app.platform.BiometricAuthenticator
import one.rarebit.voidbind.app.platform.IdentityStore
import one.rarebit.voidbind.crypto.Hex
import one.rarebit.voidbind.flow.DeviceAuthorization
import one.rarebit.voidbind.flow.DevicePairing
import one.rarebit.voidbind.flow.LoginApproval
import one.rarebit.voidbind.net.HttpTransport
import one.rarebit.voidbind.net.NotifyClient
import java.net.URI

/**
 * The real [VoidbindEngine]: wires the app UI to the library's commonMain device
 * brain (UserIdentity, DeviceIdentity, Enrolment, the flow coordinators) over the
 * hardware [DeviceKeyStore] + a sealed X25519 enc-key store ([IdentityStore]) and an
 * OkHttp [HttpTransport], gating every signature behind [BiometricAuthenticator].
 *
 * All calls that touch hardware or the network run on [Dispatchers.IO]. A hardware
 * signature whose auth window has lapsed throws [AuthenticationRequiredException];
 * [withDeviceAuth] catches it, prompts, and retries once within the window.
 *
 * NOTE (device-tested, not CI): StrongBox non-extractability and the biometric gate
 * exist only on real hardware (docs/DEVICE-TESTING.md). This engine compiles and is
 * architecturally wired; its runtime behaviour is proven on a physical device, not
 * on an emulator or in CI. Both pairing directions are now UI-driven: the responder
 * (join → VERIFY → confirm) and the initiator (invite → [awaitPairHandshake] →
 * VERIFY → authorise).
 */
class DeviceVoidbindEngine(
    private val store: IdentityStore,
    private val transport: HttpTransport,
    private val biometric: BiometricAuthenticator,
    private val relayBase: String,
    private val notifyBase: String = DEFAULT_NOTIFY,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : VoidbindEngine {

    private val deviceAlias = "device"
    private val _identity = MutableStateFlow<IdentityState>(IdentityState.Loading)
    override val identity: StateFlow<IdentityState> = _identity.asStateFlow()

    // In-session state for multi-step flows.
    private var sessionUser: UserIdentity? = null
    private var pendingLogin: Pair<LoginApproval, LoginApproval.Request>? = null
    private var pendingJoin: PendingJoin? = null
    private var pendingAuthorization: Pair<DeviceAuthorization, DeviceAuthorization.Invitation>? = null

    private class PendingJoin(
        val pairing: DevicePairing,
        val handshake: DevicePairing.Handshake,
        val enc: DeviceIdentity.EncryptionKey,
    )

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        _identity.value = loadState()
    }

    // --- Onboarding -----------------------------------------------------------

    override suspend fun createIdentity(): RecoveryBackup = withContext(Dispatchers.IO) {
        provision(UserIdentity.create()).also { _identity.value = loadState() }
    }

    override suspend fun restoreIdentity(recoverySecret: String) = withContext(Dispatchers.IO) {
        provision(UserIdentity.restore(recoverySecret)) // throws on a mistyped secret
        _identity.value = loadState()
    }

    /** Provision THIS device as the owner for [user]: hardware key, enc key, self-cert. */
    private suspend fun provision(user: UserIdentity): RecoveryBackup {
        val enc = DeviceIdentity.generateEncryptionKey()
        val ks = withDeviceAuth { DeviceKeyStore.getOrCreate(deviceAlias) }
        val device = DeviceIdentity(ks.publicKey().bytes, enc.publicKey, enc.privateKey) { ks.sign(it) }
        val cert = Enrolment.selfEnrol(user, device, clock())
        store.saveOwner(cert, user.userPublicKey, enc.publicKey, enc.privateKey, user.recovery.bytes, defaultDeviceName())
        sessionUser = user
        return backup(user.recovery)
    }

    override suspend fun revealRecoverySecret(): RecoveryBackup = withContext(Dispatchers.IO) {
        check(store.hasUserKey()) { "This device has no recovery secret — it was added by pairing." }
        require(biometric.authenticate("Show recovery secret", "Confirm it's you")) { "Authentication cancelled." }
        val bytes = store.recoverySecret() ?: error("recovery secret is not available")
        backup(RecoverySecret.of(bytes))
    }

    // --- Scanning -------------------------------------------------------------

    override fun parseScanned(raw: String): ScannedCode = try {
        when (val qr = one.rarebit.voidbind.VoidbindQr.parse(raw)) {
            is one.rarebit.voidbind.VoidbindQr.Login -> ScannedCode.WebLogin(qr.request.rp, qr.request.id, raw)
            is one.rarebit.voidbind.VoidbindQr.Pair -> ScannedCode.PairInvite(qr.invite.relay, qr.invite.session, raw)
        }
    } catch (_: Throwable) {
        ScannedCode.Unknown(raw)
    }

    // --- Web login ------------------------------------------------------------

    override suspend fun fetchLoginRequest(code: ScannedCode.WebLogin): LoginRequestResult = withContext(Dispatchers.IO) {
        val persisted = store.load()
            ?: return@withContext LoginRequestResult.Failed("No identity on this device.")
        val approval = LoginApproval(transport, buildDevice(), persisted.enrolmentCert)
        // beginCatching converts every transport/IO failure and non-2xx into an Outcome.Failed
        // instead of throwing, so an unreachable/misconfigured RP surfaces as a login error and
        // never becomes an uncaught main-thread FATAL. The extra runCatching is belt-and-braces:
        // no unexpected throw from this boundary may escape the approval coroutine.
        val outcome = runCatching { approval.beginCatching(LoginQr.Parsed(code.rpBase, code.loginId)) }
            .getOrElse { LoginApproval.Outcome.Failed(LoginApproval.FailureKind.UNREACHABLE, "Couldn't reach the site.") }
        when (outcome) {
            is LoginApproval.Outcome.Failed -> {
                pendingLogin = null
                LoginRequestResult.Failed(outcome.message)
            }
            is LoginApproval.Outcome.Ready -> {
                val request = outcome.request
                pendingLogin = approval to request
                LoginRequestResult.Ready(
                    LoginRequest(
                        domain = host(request.rp),
                        appName = "",
                        origin = request.audience,
                        signInAs = "vb1 · ${shortFingerprint(persisted.userPublicKey)}",
                        expiresInSeconds = (request.expiresAt - clock()).toInt().coerceAtLeast(0),
                        access = "Authentication only",
                        signatureValid = true,
                        candidates = request.candidates, // non-empty ⇒ a number-matching (push) login
                    ),
                )
            }
        }
    }

    override suspend fun approveLogin(code: ScannedCode.WebLogin) = withContext(Dispatchers.IO) {
        val (approval, request) = pendingLogin ?: error("no login in progress")
        withDeviceAuth { approval.approve(request) }
        finishApproval(request)
    }

    override suspend fun approveNumberMatch(code: ScannedCode.WebLogin, chosen: Int) = withContext(Dispatchers.IO) {
        val (approval, request) = pendingLogin ?: error("no login in progress")
        // approval.approve(request, chosen) signs the v2 binding; a decoy tap binds the
        // wrong number and the RP refuses it (thrown) — no site is recorded on refusal.
        withDeviceAuth { approval.approve(request, chosen) }
        finishApproval(request)
    }

    private fun finishApproval(request: LoginApproval.Request) {
        val domain = host(request.rp)
        store.upsertTrustedSite(TrustedSite(domain, domain, "", "just now", accentFor(domain)))
        pendingLogin = null
        _identity.value = loadState()
    }

    // --- Push wake ------------------------------------------------------------

    override suspend fun registerForPush(endpoint: String): Boolean = withContext(Dispatchers.IO) {
        val persisted = store.load() ?: return@withContext false
        try {
            NotifyClient(transport, notifyBase).subscribe(persisted.enrolmentCert, endpoint)
            true
        } catch (_: Throwable) {
            // Best-effort: a failed wake registration just means no background push;
            // scanned QR login is unaffected. Do not surface it as an app error.
            false
        }
    }

    override suspend fun unregisterFromPush() = withContext(Dispatchers.IO) {
        val persisted = store.load() ?: return@withContext
        runCatching { NotifyClient(transport, notifyBase).unsubscribe(persisted.enrolmentCert) }
        Unit
    }

    // --- Pairing --------------------------------------------------------------

    override suspend fun startPairInvite(): PairInviteDisplay = withContext(Dispatchers.IO) {
        val authorization = DeviceAuthorization(transport, requireUser(), clock)
        val invitation = authorization.invite(relayBase)
        pendingAuthorization = authorization to invitation
        PairInviteDisplay(
            inviteId = "INV · ${invitation.relaySession.uppercase().take(8).chunked(4).joinToString(" ")}",
            qrPayload = invitation.inviteQr,
            expiresInSeconds = 300,
        )
    }

    override suspend fun awaitPairHandshake(): PairSession = withContext(Dispatchers.IO) {
        val (authorization, invitation) = pendingAuthorization ?: error("no pairing invite in progress")
        // Blocks (polls the relay) until the new device joins and both sides
        // commit → reveal → open; returns the SAS. Signs nothing yet — the human
        // matches this against the new device's screen, then confirmPairing()
        // authorises. handshake() flips the initiator's `handshook` flag so the
        // subsequent authorise() on the SAME invitation is valid.
        val sas = authorization.handshake(invitation)
        PairSession(
            thisDeviceName = defaultDeviceName(),
            peerDeviceName = "New device",
            securityCode = formatSas(sas),
        )
    }

    override suspend fun joinPairInvite(code: ScannedCode.PairInvite): PairSession = withContext(Dispatchers.IO) {
        val ks = withDeviceAuth { DeviceKeyStore.getOrCreate(deviceAlias) }
        val enc = existingEncKey() ?: DeviceIdentity.generateEncryptionKey()
        val device = DeviceIdentity(ks.publicKey().bytes, enc.publicKey, enc.privateKey) { ks.sign(it) }
        val pairing = DevicePairing(transport, device)
        val handshake = pairing.begin(code.raw)
        pendingJoin = PendingJoin(pairing, handshake, enc)
        PairSession(
            thisDeviceName = defaultDeviceName(),
            peerDeviceName = "New device",
            securityCode = formatSas(handshake.sas),
        )
    }

    override suspend fun confirmPairing() = withContext(Dispatchers.IO) {
        val join = pendingJoin
        if (join != null) {
            require(biometric.authenticate("Confirm pairing", "Approve on this device")) { "Authentication cancelled." }
            val cert = join.pairing.confirm(join.handshake)
            val userPub = Cert.parse(cert).cert.user.bytes
            store.saveJoined(cert, userPub, join.enc.publicKey, join.enc.privateKey, defaultDeviceName())
            pendingJoin = null
            sessionUser = null // a joined device holds no user key
            _identity.value = loadState()
            return@withContext
        }
        val authorization = pendingAuthorization
        if (authorization != null) {
            require(biometric.authenticate("Authorise new device", "Approve on this device")) { "Authentication cancelled." }
            authorization.first.authorise(authorization.second)
            pendingAuthorization = null
            return@withContext
        }
        error("no pairing in progress")
    }

    // --- Settings -------------------------------------------------------------

    override suspend fun renameDevice(name: String) = withContext(Dispatchers.IO) {
        store.setDeviceName(name)
        _identity.value = loadState()
    }

    override suspend fun setBiometricApproval(enabled: Boolean) = withContext(Dispatchers.IO) {
        store.setBiometricApproval(enabled)
        _identity.value = loadState()
    }

    override suspend fun revokeSite(siteId: String) = withContext(Dispatchers.IO) {
        store.removeTrustedSite(siteId)
        _identity.value = loadState()
    }

    // --- internals ------------------------------------------------------------

    private fun loadState(): IdentityState {
        val persisted = store.load() ?: return IdentityState.None
        val ks = DeviceKeyStore.getOrCreate(deviceAlias)
        val devicePub = ks.publicKey().bytes
        val backing = when (ks.securityLevel()) {
            DeviceKeyStore.SecurityLevel.STRONGBOX -> HardwareBacking.STRONGBOX
            DeviceKeyStore.SecurityLevel.TEE -> HardwareBacking.TEE
            DeviceKeyStore.SecurityLevel.SOFTWARE -> HardwareBacking.SOFTWARE
        }
        return IdentityState.Active(
            identity = Identity(
                version = "vb1",
                fingerprint = fingerprint(persisted.userPublicKey),
                fullKey = one.rarebit.voidbind.KeyRef.ed25519(persisted.userPublicKey).render(),
                offlineVerifiable = true,
            ),
            device = DeviceInfo(
                name = persisted.deviceName,
                label = "dev · ${shortFingerprint(devicePub)}",
                backing = backing, // the REAL tier queried from the wrapping key, not assumed
                biometricRequired = persisted.biometricApproval,
            ),
            trustedSites = store.trustedSites(),
            biometricApproval = persisted.biometricApproval,
        )
    }

    private fun buildDevice(): DeviceIdentity {
        val ks = DeviceKeyStore.getOrCreate(deviceAlias)
        val persisted = store.load() ?: error("no identity on this device")
        val encPriv = store.encPrivateKey() ?: error("device encryption key missing")
        return DeviceIdentity(ks.publicKey().bytes, persisted.encPublicKey, encPriv) { ks.sign(it) }
    }

    private fun existingEncKey(): DeviceIdentity.EncryptionKey? {
        val priv = store.encPrivateKey() ?: return null
        val pub = store.load()?.encPublicKey ?: return null
        return DeviceIdentity.EncryptionKey(priv, pub)
    }

    private fun requireUser(): UserIdentity = sessionUser ?: run {
        check(store.hasUserKey()) { "Only the device that created the identity can add new devices." }
        val bytes = store.recoverySecret() ?: error("user key is not available")
        UserIdentity.fromSecret(RecoverySecret.of(bytes)).also { sessionUser = it }
    }

    private suspend fun <T> withDeviceAuth(block: () -> T): T = try {
        block()
    } catch (e: AuthenticationRequiredException) {
        if (biometric.authenticate("Authenticate", "Confirm it's you to use your device key")) block()
        else throw e
    }

    private fun backup(secret: RecoverySecret): RecoveryBackup {
        val rendered = secret.format()
        return RecoveryBackup(groupedSecret = rendered.chunked(4).joinToString(" "), rawSecret = rendered)
    }

    private fun fingerprint(pub: ByteArray): String =
        Hex.encode(pub).uppercase().take(12).chunked(4).joinToString(" ")

    private fun shortFingerprint(pub: ByteArray): String =
        Hex.encode(pub).uppercase().take(8).chunked(4).joinToString(" ")

    private fun formatSas(sas: String): String =
        if (sas.length >= 7) "${sas.substring(0, 3)} ${sas.substring(3)}" else sas

    private fun host(url: String): String = try {
        URI(url).host ?: url
    } catch (_: Throwable) {
        url
    }

    private fun accentFor(key: String): SiteAccent =
        SiteAccent.entries[(key.hashCode() and 0x7fffffff) % SiteAccent.entries.size]

    private fun defaultDeviceName(): String {
        val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        return name.ifBlank { "This device" }
    }

    companion object {
        /** A sensible default relay base for the family homelab; override at construction. */
        const val DEFAULT_RELAY = "https://relay.thesim.family"

        /** Default notify-plane base (POST/DELETE /v1/subscriptions); override at construction. */
        const val DEFAULT_NOTIFY = "https://notify.thesim.family"
    }
}
