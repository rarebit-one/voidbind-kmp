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

    /** Fetch the details of a web-login request (from the RP) for the approval sheet. */
    suspend fun fetchLoginRequest(code: ScannedCode.WebLogin): LoginRequest

    /** Approve a web login — sign the challenge with the hardware key. Biometric-gated. */
    suspend fun approveLogin(code: ScannedCode.WebLogin)

    // --- Pairing --------------------------------------------------------------

    /** As the existing device: mint a pairing invite to display as a QR. */
    suspend fun startPairInvite(): PairInviteDisplay

    /** As the new device: join a scanned invite and run the handshake to the SAS. */
    suspend fun joinPairInvite(code: ScannedCode.PairInvite): PairSession

    /** After the human confirms the SAS matches: authorise/receive the sealed cert. */
    suspend fun confirmPairing()

    // --- Settings actions -----------------------------------------------------

    suspend fun renameDevice(name: String)
    suspend fun setBiometricApproval(enabled: Boolean)
    suspend fun revokeSite(siteId: String)
}
