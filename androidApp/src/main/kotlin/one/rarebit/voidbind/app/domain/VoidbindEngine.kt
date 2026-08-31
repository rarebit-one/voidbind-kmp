package one.rarebit.voidbind.app.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * The app-side contract for "the brain" — everything the UI needs the identity core
 * to do. The real implementation binds these to the KMP library's commonMain flow
 * coordinators (identity derivation, pairing, web-login, recovery) + the hardware
 * [one.rarebit.voidbind.DeviceKeyStore]; a [PreviewVoidbindEngine] backs @Preview
 * and lets the whole UI run against the mockup data before the coordinators land.
 *
 * Everything cryptographic stays behind this seam: screens receive already-formatted
 * fingerprints and booleans and never see key bytes. Suspending calls are the ones
 * that touch hardware (a biometric-gated signature) or the network (relay/RP).
 */
interface VoidbindEngine {

    /** Current identity state; drives whether the app shows onboarding or home. */
    val identity: StateFlow<IdentityState>

    /** Load identity state from the keystore (call on start). */
    suspend fun refresh()

    // --- Onboarding -----------------------------------------------------------

    /**
     * Create a brand-new identity: draw a recovery secret, derive the user key,
     * provision the hardware device key, self-sign the first enrolment cert. Returns
     * the recovery secret to display for backup. Biometric-gated.
     */
    suspend fun createIdentity(): RecoveryBackup

    /** Restore an identity on this device from a recovery secret string. */
    suspend fun restoreIdentity(recoverySecret: String)

    /** Re-display the recovery secret (Settings → Recovery backup). Biometric-gated. */
    suspend fun revealRecoverySecret(): RecoveryBackup

    // --- Scanning -------------------------------------------------------------

    /** Parse a scanned QR string into a dispatchable [ScannedCode]. */
    fun parseScanned(raw: String): ScannedCode

    // --- Web login ------------------------------------------------------------

    /**
     * Fetch the details of a web-login request (from the RP) for the approval sheet.
     * The same call serves a scanned v1 login and a push-woken **number-matching**
     * one — the returned [LoginRequest.candidates] is non-empty for the latter, and
     * the UI shows the number grid instead of a plain Approve button.
     *
     * The fetch touches the network and MUST NOT throw for a transport/IO failure: an
     * unreachable RP, a TLS error, a timeout, a non-2xx, or a cleartext-blocked URL all
     * resolve to [LoginRequestResult.Failed] so the UI can render an error rather than the
     * app crashing with an uncaught main-thread exception.
     */
    suspend fun fetchLoginRequest(code: ScannedCode.WebLogin): LoginRequestResult

    /** Approve a scanned (v1) web login — sign the challenge with the hardware key. Biometric-gated. */
    suspend fun approveLogin(code: ScannedCode.WebLogin)

    /**
     * Approve a **number-matching (v2)** login by the number the human tapped — sign
     * the challenge bound to [chosen] (biometric-gated) and submit it. Tapping a
     * decoy binds the wrong number and the RP refuses it (no login), which is the
     * anti-phishing point. [chosen] must be one of the fetched
     * [LoginRequest.candidates].
     */
    suspend fun approveNumberMatch(code: ScannedCode.WebLogin, chosen: Int)

    // --- Push wake (self-hosted ntfy / UnifiedPush) ---------------------------

    /**
     * Register this device's [endpoint] (the ntfy topic URL a UnifiedPush distributor
     * handed us) with the notify plane, cert-authenticated, so a push login can wake
     * this phone. Call on app open with the current endpoint; a subscription is
     * short-lived by design, so re-registering is normal. Returns true on success,
     * false if there is no identity yet or the plane refused it (best-effort — a
     * failure just means no background wake, and QR login still works).
     */
    suspend fun registerForPush(endpoint: String): Boolean

    /** Drop this device's wake subscription (cert-authenticated). Best-effort. */
    suspend fun unregisterFromPush()

    // --- Pairing --------------------------------------------------------------

    /** As the existing device: mint a pairing invite to display as a QR. */
    suspend fun startPairInvite(): PairInviteDisplay

    /**
     * As the existing device, after [startPairInvite] rendered the invite: block
     * until the new device joins the relay, run the commit-before-reveal handshake,
     * and return the 7-digit SAS to compare. Signs nothing — the human gate on the
     * VERIFY screen precedes [confirmPairing], which authorises. Blocking (polls the
     * relay); throws if the invite expires unjoined.
     */
    suspend fun awaitPairHandshake(): PairSession

    /** As the new device: join a scanned invite and run the handshake to the SAS. */
    suspend fun joinPairInvite(code: ScannedCode.PairInvite): PairSession

    /** After the human confirms the SAS matches: authorise/receive the sealed cert. */
    suspend fun confirmPairing()

    // --- Settings actions -----------------------------------------------------

    suspend fun renameDevice(name: String)
    suspend fun setBiometricApproval(enabled: Boolean)
    suspend fun revokeSite(siteId: String)
}
