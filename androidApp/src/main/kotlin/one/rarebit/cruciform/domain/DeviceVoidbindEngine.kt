package one.rarebit.cruciform.domain

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import one.rarebit.voidbind.AuthenticationRequiredException
import one.rarebit.voidbind.DeviceIdentity
import one.rarebit.voidbind.DeviceKeyStore
import one.rarebit.voidbind.Enrolment
import one.rarebit.voidbind.KeyRef
import one.rarebit.voidbind.LoginQr
import one.rarebit.voidbind.Membership
import one.rarebit.voidbind.MembershipOp
import one.rarebit.voidbind.RecoverySecret
import one.rarebit.voidbind.UserIdentity
import one.rarebit.cruciform.platform.ApprovalPolicyStore
import one.rarebit.cruciform.platform.BiometricAuthenticator
import one.rarebit.cruciform.platform.IdentityStore
import one.rarebit.cruciform.platform.RelayConfig
import one.rarebit.voidbind.crypto.Hex
import one.rarebit.voidbind.flow.DeviceAuthorization
import one.rarebit.voidbind.flow.DevicePairing
import one.rarebit.voidbind.flow.LoginApproval
import one.rarebit.voidbind.flow.PairingFailureKind
import one.rarebit.voidbind.flow.PairingFailures
import one.rarebit.voidbind.flow.PairingOutcome
import one.rarebit.voidbind.net.HttpTransport
import one.rarebit.voidbind.net.NotifyClient
import one.rarebit.voidbind.policy.ApprovalPolicy
import one.rarebit.voidbind.policy.ApprovalPolicyManager
import one.rarebit.voidbind.crypto.MiniJson
import java.io.InterruptedIOException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 *
 * MEMBERSHIP (ADR-0005): this device is one member of the identity's device set.
 * "Add a device" is available to ANY member — the initiator signs the new device's
 * add op with its own hardware key, citing the heads of the replica in
 * [IdentityStore]; the recovery secret is only the genesis fallback for a device
 * whose own admission has lapsed. Login assertions present the replica (`ops`);
 * Settings → Devices evaluates it and can sign a `remove` (biometric-gated) that is
 * pushed to the relying parties in [membershipRps].
 */
class DeviceVoidbindEngine(
    private val store: IdentityStore,
    private val policyStore: ApprovalPolicyStore,
    private val transport: HttpTransport,
    private val biometric: BiometricAuthenticator,
    /**
     * The pairing-relay base this device MINTS invites through, read on every call —
     * a provider, not a value, so Settings → "Pairing relay" takes effect on the next
     * "Add a device" without recreating the engine (`RelaySettings.current`).
     */
    private val relay: () -> String,
    private val notifyBase: String = DEFAULT_NOTIFY,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Relying parties that serve `POST /membership/{usr}`; a remove is pushed to each, best-effort. */
    private val membershipRps: List<String> = DEFAULT_MEMBERSHIP_RPS,
) : VoidbindEngine {

    private val deviceAlias = "device"

    /** The configured relay, resolved now (Settings may have changed it since the last call). */
    private val relayBase: String get() = relay()

    /**
     * The relay a pending invite was MINTED against. The handshake/confirm steps
     * classify their failures against this, not [relayBase], so a Settings change
     * mid-flow cannot mislabel which relay dropped.
     */
    private var inviteRelay: String? = null

    // The per-RP approval policy + audit trail. Pure commonMain brain; [policyStore]
    // supplies persistence. It records consent AROUND the unchanged hardware signature.
    private val policy = ApprovalPolicyManager(policyStore, policyStore, clock)
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
        // The assertion presents the replica (`ops`) beside the admitting op, so an RP
        // that never met the member that admitted this device can still evaluate it.
        val approval = LoginApproval(transport, buildDevice(), persisted.enrolmentCert, knownOps = persisted.ops)
        // beginCatching converts every transport/IO failure and non-2xx into an Outcome.Failed
        // instead of throwing, so an unreachable/misconfigured RP surfaces as a login error and
        // never becomes an uncaught main-thread FATAL. The extra runCatching is belt-and-braces:
        // no unexpected throw from this boundary may escape the approval coroutine.
        val outcome = runCatching { approval.beginCatching(LoginQr.Parsed(code.rpBase, code.loginId)) }
            .getOrElse { LoginApproval.Outcome.Failed(LoginApproval.FailureKind.UNREACHABLE, "Couldn't reach the site.") }
        when (outcome) {
            is LoginApproval.Outcome.Failed -> {
                pendingLogin = null
                LoginRequestResult.Failed(
                    outcome.message,
                    expired = outcome.kind == LoginApproval.FailureKind.EXPIRED,
                )
            }
            is LoginApproval.Outcome.Ready -> {
                val request = outcome.request
                pendingLogin = approval to request
                LoginRequestResult.Ready(
                    LoginRequest(
                        domain = host(request.rp),
                        appName = "",
                        origin = request.audience,
                        signInAs = "${persisted.deviceName} · ${shortFingerprint(persisted.userPublicKey)}",
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
        finishApproval(request, matchNumber = null)
    }

    override suspend fun approveNumberMatch(code: ScannedCode.WebLogin, chosen: Int) = withContext(Dispatchers.IO) {
        val (approval, request) = pendingLogin ?: error("no login in progress")
        // approval.approve(request, chosen) signs the v2 binding; a decoy tap binds the
        // wrong number and the RP refuses it (thrown) — no site is recorded on refusal.
        withDeviceAuth { approval.approve(request, chosen) }
        finishApproval(request, matchNumber = chosen)
    }

    override suspend fun denyLogin() = withContext(Dispatchers.IO) {
        // The human declined. Nothing is signed; record the denial in the audit trail
        // (trust is untouched — a denial never trusts a site) and drop the pending login.
        pendingLogin?.let { (_, request) ->
            policy.recordDenial(host(request.rp), request.audience, request.loginId, matchNumber = null)
        }
        pendingLogin = null
    }

    private fun finishApproval(request: LoginApproval.Request, matchNumber: Int?) {
        val domain = host(request.rp)
        // Record the approval in the audit trail and apply trust-on-first-use. The
        // signature above is unchanged — this is consent/audit state around it.
        policy.recordApproval(domain, request.audience, request.loginId, matchNumber)
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
    //
    // Every step below runs the BLOCKING relay transport on Dispatchers.IO and converts
    // any failure into an EngineResult.Failed ON THAT THREAD, inside the withContext
    // block. That is the load-bearing part of the on-device crash fix: a call-site
    // runCatching in the UI did not save the app, because once the calling coroutine
    // (a LaunchedEffect) had been cancelled, the SocketTimeoutException the blocking
    // call threw 15s later had no caller to be delivered to and went to the uncaught
    // handler as a main-thread FATAL. Nothing may throw out of these blocks.

    override suspend fun startPairInvite(): EngineResult<PairInviteDisplay> = withContext(Dispatchers.IO) {
        // Read the relay ONCE per invite: Settings → "Pairing relay" is consulted here, at
        // invite time, so a change applies to the next invite with no restart.
        val relayBase = relayBase
        inviteRelay = relayBase
        engineCatching(relayBase) {
            val authorization = memberAuthorization()
            when (val outcome = authorization.inviteCatching(relayBase)) {
                is PairingOutcome.Failed -> EngineResult.Failed(outcome.toEngineFailure())
                is PairingOutcome.Ready -> {
                    val invitation = outcome.value
                    pendingAuthorization = authorization to invitation
                    EngineResult.Ready(
                        PairInviteDisplay(
                            inviteId = "INV · ${invitation.relaySession.uppercase().take(8).chunked(4).joinToString(" ")}",
                            qrPayload = invitation.inviteQr,
                            expiresInSeconds = INVITE_TTL_SECONDS,
                            // The relay session the invite names: an RP on this phone
                            // reports it back over the one-tap callback (ADR-0008).
                            session = invitation.relaySession,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun awaitPairHandshake(): EngineResult<PairSession> = withContext(Dispatchers.IO) {
        engineCatching(inviteRelay ?: relayBase) {
            val (authorization, invitation) = pendingAuthorization
                ?: return@engineCatching internalFailure("No pairing invite is in progress.")
            // Blocks (polls the relay) until the new device joins and both sides
            // commit → reveal → open; returns the SAS. Signs nothing yet — the human
            // matches this against the new device's screen, then confirmPairing()
            // authorises. handshake() flips the initiator's `handshook` flag so the
            // subsequent authorise() on the SAME invitation is valid.
            when (val outcome = authorization.handshakeCatching(invitation)) {
                is PairingOutcome.Failed -> EngineResult.Failed(outcome.toEngineFailure())
                is PairingOutcome.Ready -> EngineResult.Ready(
                    PairSession(
                        thisDeviceName = defaultDeviceName(),
                        peerDeviceName = "New device",
                        securityCode = formatSas(outcome.value),
                        // What the RELAY revealed. The one-tap path (ADR-0008) checks the
                        // RP's own local report against this; a mismatch signs nothing.
                        peerDeviceKey = invitation.responderDeviceId,
                    ),
                )
            }
        }
    }

    override suspend fun joinPairInvite(code: ScannedCode.PairInvite): EngineResult<PairSession> = withContext(Dispatchers.IO) {
        engineCatching(code.relay) {
            val ks = withDeviceAuth { DeviceKeyStore.getOrCreate(deviceAlias) }
            val enc = existingEncKey() ?: DeviceIdentity.generateEncryptionKey()
            val device = DeviceIdentity(ks.publicKey().bytes, enc.publicKey, enc.privateKey) { ks.sign(it) }
            val pairing = DevicePairing(transport, device, clock)
            // beginCatching turns the blocking relay handshake's every failure (no route,
            // refused, TLS, timeout, relay non-2xx, a commitment that does not open) into a
            // classified outcome instead of throwing — the SocketTimeoutException that
            // killed the app arrived through exactly this call.
            when (val outcome = pairing.beginCatching(code.raw)) {
                is PairingOutcome.Failed -> {
                    pendingJoin = null
                    EngineResult.Failed(outcome.toEngineFailure())
                }
                is PairingOutcome.Ready -> {
                    val handshake = outcome.value
                    pendingJoin = PendingJoin(pairing, handshake, enc)
                    EngineResult.Ready(
                        PairSession(
                            thisDeviceName = defaultDeviceName(),
                            peerDeviceName = "New device",
                            securityCode = formatSas(handshake.sas),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun confirmPairing(): EngineResult<Unit> = withContext(Dispatchers.IO) {
        engineCatching(inviteRelay ?: relayBase) {
            val join = pendingJoin
            if (join != null) {
                if (!biometric.authenticate("Confirm pairing", "Approve on this device")) {
                    return@engineCatching cancelledFailure()
                }
                return@engineCatching when (val outcome = join.pairing.confirmCatching(join.handshake)) {
                    is PairingOutcome.Failed -> EngineResult.Failed(outcome.toEngineFailure())
                    is PairingOutcome.Ready -> {
                        // The admission: this device's admitting op (its credential) plus
                        // the ops that authorise it (its replica from here on).
                        val admission = outcome.value
                        val userPub = KeyRef.parse(MembershipOp.verify(admission.op).user).bytes
                        store.saveJoined(admission.op, admission.ops, userPub, join.enc.publicKey, join.enc.privateKey, defaultDeviceName())
                        pendingJoin = null
                        sessionUser = null // a joined device holds no user key
                        _identity.value = loadState()
                        EngineResult.Ready(Unit)
                    }
                }
            }
            val authorization = pendingAuthorization
            if (authorization != null) {
                if (!biometric.authenticate("Authorise new device", "Approve on this device")) {
                    return@engineCatching cancelledFailure()
                }
                // authorise() SIGNS the add with this device's hardware key (a member
                // initiator) — so it runs under withDeviceAuth, which re-prompts if the
                // keystore's auth window has lapsed; any throw lands in engineCatching.
                val ops = withDeviceAuth { authorization.first.authorise(authorization.second) }
                store.recordOps(ops)
                pendingAuthorization = null
                pushMembership(ops) // best-effort: the RPs learn the new member now, not at its first login
                _identity.value = loadState()
                return@engineCatching EngineResult.Ready(Unit)
            }
            internalFailure("No pairing is in progress.")
        }
    }

    /**
     * The last line of defence for a pairing step: whatever [block] throws that the
     * library's `*Catching` layer did not already classify (a keystore error, a missing
     * precondition, a bug) becomes a `Failed` here, on the IO thread, so it can never
     * escape the coroutine. The biometric gate's "cancelled" signal gets its own kind.
     */
    private inline fun <T> engineCatching(relayBase: String, block: () -> EngineResult<T>): EngineResult<T> = try {
        block()
    } catch (e: AuthenticationRequiredException) {
        cancelledFailure()
    } catch (e: IllegalStateException) {
        // check()/error() in this engine: "Only the device that created the identity can
        // add new devices", "no identity" — a precondition, not a network problem.
        EngineResult.Failed(EngineFailure(e.message ?: "Couldn't start the pairing.", EngineFailure.Kind.INTERNAL, retryable = false))
    } catch (e: InterruptedIOException) {
        // The blocking relay call was interrupted (the thread, not the network): this is
        // NOT "can't reach the relay" — the relay never answered badly. Distinct kind so
        // the dialog doesn't send the user to check Wi-Fi.
        EngineResult.Failed(EngineFailure("The pairing was interrupted. Start again with a fresh invite.", EngineFailure.Kind.CANCELLED, retryable = true))
    } catch (e: InterruptedException) {
        EngineResult.Failed(EngineFailure("The pairing was interrupted. Start again with a fresh invite.", EngineFailure.Kind.CANCELLED, retryable = true))
    } catch (e: Throwable) {
        EngineResult.Failed(PairingFailures.classify(e, relayBase).toEngineFailure())
    }

    private fun <T> internalFailure(message: String): EngineResult<T> =
        EngineResult.Failed(EngineFailure(message, EngineFailure.Kind.INTERNAL, retryable = false))

    private fun <T> cancelledFailure(): EngineResult<T> =
        EngineResult.Failed(EngineFailure("Authentication cancelled.", EngineFailure.Kind.CANCELLED, retryable = false))

    private fun PairingOutcome.Failed.toEngineFailure(): EngineFailure = EngineFailure(
        message = message,
        kind = when (kind) {
            PairingFailureKind.UNREACHABLE -> EngineFailure.Kind.UNREACHABLE
            PairingFailureKind.TIMEOUT -> EngineFailure.Kind.TIMEOUT
            PairingFailureKind.REJECTED -> EngineFailure.Kind.REJECTED
            PairingFailureKind.PROTOCOL -> EngineFailure.Kind.PROTOCOL
        },
        // Unreachable: retry the same step once the network is back. Timeout/rejected:
        // a fresh invite is needed, so the UI's retry re-mints/re-scans (still "retryable"
        // from the human's point of view). Protocol: never against the same session.
        retryable = kind != PairingFailureKind.PROTOCOL,
    )

    // --- Devices (membership, ADR-0005) ---------------------------------------

    override suspend fun devices(): List<MemberDevice> = withContext(Dispatchers.IO) {
        val persisted = store.load() ?: return@withContext emptyList()
        val usr = KeyRef.ed25519(persisted.userPublicKey).render()
        val self = KeyRef.ed25519(DeviceKeyStore.getOrCreate(deviceAlias).publicKey().bytes).render()
        val view = Membership.evaluate(usr, persisted.ops, clock())
        view.members.values
            .sortedWith(compareBy<Membership.Member> { it.device != self }.thenBy { it.admittedAt })
            .map { m ->
                val admitting = view.accepted[m.admittedBy]
                MemberDevice(
                    id = m.device,
                    fingerprint = shortFingerprint(KeyRef.parse(m.device).bytes),
                    isThisDevice = m.device == self,
                    admittedByLabel = when {
                        admitting == null -> "unknown"
                        admitting.genesis -> "genesis (recovery key)"
                        admitting.by == self -> "this device"
                        else -> shortFingerprint(KeyRef.parse(admitting.by).bytes)
                    },
                    admittedLabel = dateLabel(m.admittedAt),
                    expiresLabel = "renews by ${dateLabel(m.expiresAt)}",
                )
            }
    }

    override suspend fun removeDevice(deviceId: String): EngineResult<Unit> = withContext(Dispatchers.IO) {
        engineCatching(relayBase) {
            val persisted = store.load() ?: return@engineCatching internalFailure("No identity on this device.")
            val ks = DeviceKeyStore.getOrCreate(deviceAlias)
            val selfPub = ks.publicKey().bytes
            val self = KeyRef.ed25519(selfPub).render()
            if (deviceId == self) return@engineCatching internalFailure("This device can't remove itself. Remove it from another device.")
            val usr = KeyRef.ed25519(persisted.userPublicKey).render()
            val now = clock()
            val view = Membership.evaluate(usr, persisted.ops, now)
            if (!view.isMember(self)) return@engineCatching internalFailure("This device is no longer a member, so it can't remove others.")
            if (!view.isMember(deviceId)) return@engineCatching internalFailure("That device is not a member any more.")
            if (!biometric.authenticate("Remove device", "Sign the removal with this device")) {
                return@engineCatching cancelledFailure()
            }
            // The remove is signed by THIS device's hardware key, citing the replica's heads —
            // the causal evidence that it was a member when it said so (ADR-0005 rule 2).
            val removeOp = withDeviceAuth {
                MembershipOp.sign(
                    { ks.sign(it) }, selfPub, usr, MembershipOp.Kind.REMOVE,
                    dev = deviceId, deviceEnc = "", prev = view.heads, issuedAt = now,
                )
            }
            store.recordOps(listOf(removeOp))
            val ops = store.knownOps()
            check(!Membership.evaluate(usr, ops, now).isMember(deviceId)) { "the removal did not take effect locally" }
            pushMembership(ops)
            _identity.value = loadState()
            EngineResult.Ready(Unit)
        }
    }

    /**
     * Push the replica to every relying party this app knows (`POST /membership/{usr}
     * {"ops":[…]}` — heyarr-core / All Thing), so a remove reaches them now rather than
     * at some device's next authenticated call. Best-effort by design: an RP that is
     * unreachable, or does not serve the route yet (404), is skipped — the ops still
     * travel with every login assertion. Returns how many RPs accepted.
     */
    private fun pushMembership(ops: List<String>): Int {
        val persisted = store.load() ?: return 0
        val usr = KeyRef.ed25519(persisted.userPublicKey).render()
        val body = MiniJson.encodeObject(listOf("ops" to one.rarebit.voidbind.WebLogin.presentable(ops))).encodeToByteArray()
        var accepted = 0
        for (rp in membershipRps) {
            val ok = runCatching {
                transport.post(rp.trimEnd('/') + "/membership/" + usr, body, "application/json").status in 200..299
            }.getOrDefault(false)
            if (ok) accepted++
        }
        return accepted
    }

    private fun dateLabel(unixSeconds: Long): String =
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(unixSeconds * 1000))

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
        policy.forget(siteId) // the site's TrustedSite.id is its host, the policy key
        _identity.value = loadState()
    }

    // --- Per-RP approval policy + audit ---------------------------------------

    override suspend fun sitePolicy(rp: String): SitePolicyView = withContext(Dispatchers.IO) {
        val p = policy.policyFor(rp)
        SitePolicyView(
            rp = rp,
            policy = p?.policy ?: ApprovalPolicy.AlwaysAsk,
            pinnedAlwaysAsk = p?.pinnedAlwaysAsk ?: false,
        )
    }

    override suspend fun setAlwaysAsk(rp: String, alwaysAsk: Boolean) = withContext(Dispatchers.IO) {
        if (alwaysAsk) policy.setAlwaysAsk(rp) else policy.trust(rp)
        _identity.value = loadState()
    }

    override suspend fun approvalActivity(limit: Int): List<ApprovalActivity> = withContext(Dispatchers.IO) {
        val now = clock()
        policy.auditEntries(limit).map { ApprovalActivity.from(it, relativeTime(now, it.timestampSeconds)) }
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
                label = persisted.deviceName, // the enrolled/chosen device name — never a leftover tag
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
            trustedSites = store.trustedSites().map { site ->
                // Join the per-RP policy (kept in a separate store) onto each site row.
                val p = policyStore.get(site.id)
                site.copy(
                    policy = p?.policy ?: ApprovalPolicy.AlwaysAsk,
                    pinnedAlwaysAsk = p?.pinnedAlwaysAsk ?: false,
                )
            },
            biometricApproval = persisted.biometricApproval,
        )
    }

    /** A coarse "just now / 5m ago / 3h ago / 2d ago" label from two unix-seconds stamps. */
    private fun relativeTime(now: Long, then: Long): String {
        val secs = (now - then).coerceAtLeast(0)
        return when {
            secs < 60 -> "just now"
            secs < 3_600 -> "${secs / 60}m ago"
            secs < 86_400 -> "${secs / 3_600}h ago"
            else -> "${secs / 86_400}d ago"
        }
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

    /**
     * The authority for "Add a device": THIS device as a member (ADR-0005 — no secret
     * involved), signing with its hardware key and citing the heads of its replica.
     * Only if its own ops no longer find it a member (its add lapsed, or it was
     * removed) does an install that holds the recovery secret fall back to GENESIS,
     * which is the one authority that can re-admit — a Restore-based install is
     * exactly that case.
     */
    private fun memberAuthorization(): DeviceAuthorization {
        val persisted = store.load() ?: error("No identity on this device.")
        val device = buildDevice()
        val usr = KeyRef.ed25519(persisted.userPublicKey).render()
        val view = Membership.evaluate(usr, persisted.ops, clock())
        if (view.isMember(device.deviceId.render())) {
            return DeviceAuthorization(transport, device, persisted.enrolmentCert, persisted.ops, clock, maxWaitMillis = INVITE_TTL_SECONDS * 1000L)
        }
        check(store.hasUserKey()) {
            "This device is no longer a member of the identity, so it can't add devices. Re-admit it from another device, or restore from the recovery secret."
        }
        return DeviceAuthorization(transport, requireUser(), clock, knownOps = persisted.ops, maxWaitMillis = INVITE_TTL_SECONDS * 1000L)
    }

    private fun requireUser(): UserIdentity = sessionUser ?: run {
        check(store.hasUserKey()) { "This device holds no recovery secret." }
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
        /**
         * How long a minted invite waits for the new device, and the initiator's relay
         * poll bound: the relay's session TTL (voidbind-go `relay.DefaultSessionTTL`,
         * 10 min on the heyarr node). The new device may have to be created first — a
         * key behind a fingerprint in another app — so the wait is human-paced, not
         * transport-paced (the library default of 60 s stranded every same-phone
         * enrolment: the initiator gave up before the responder posted its commit).
         */
        const val INVITE_TTL_SECONDS = 600

        /**
         * The default pairing relay when Settings holds no override — the heyarr node's
         * `/pair` mount on the Bartley Ridge LAN. `https://relay.thesim.family` is the
         * intended public relay once it is deployed; it is not reachable today, which is
         * why it is no longer the default (see [RelayConfig.DEFAULT_RELAY]).
         */
        const val DEFAULT_RELAY = RelayConfig.DEFAULT_RELAY

        /** Default notify-plane base (POST/DELETE /v1/subscriptions); override at construction. */
        const val DEFAULT_NOTIFY = "https://notify.thesim.family"

        /**
         * Relying parties that (will) serve `POST /membership/{usr}`: the heyarr node and
         * All Thing on hyperion-1 (Bartley Ridge LAN). Best-effort targets for a pushed
         * remove/add; a 404 from one that has not landed the route yet is tolerated.
         */
        val DEFAULT_MEMBERSHIP_RPS: List<String> = listOf(
            "http://192.168.16.224:7777",
            "http://192.168.16.224:8080",
        )
    }
}
