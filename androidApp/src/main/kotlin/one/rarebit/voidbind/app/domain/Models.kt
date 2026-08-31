package one.rarebit.voidbind.app.domain

/**
 * UI-facing domain models. These are the app's own view of identity state, kept
 * deliberately separate from the wire types in the KMP library so a screen never
 * touches raw key bytes — it renders a already-formatted [fingerprint] and a
 * boolean [hardwareBacked]. The real values are produced by a [VoidbindEngine];
 * the commonMain flow-coordinators (owned by the library side) are the source of
 * truth for anything cryptographic.
 */

/** A hardware-backing level, driving the "StrongBox / TEE / software" status copy. */
enum class HardwareBacking { STRONGBOX, TEE, SOFTWARE }

/** The signing identity as the UI shows it. */
data class Identity(
    /** Version tag, e.g. `vb1`. */
    val version: String,
    /** Space-grouped short fingerprint of the user identity key, e.g. `7C4A 91D2 0E8F`. */
    val fingerprint: String,
    /** Full `ed25519:<hex>` rendering, for copy. */
    val fullKey: String,
    val offlineVerifiable: Boolean = true,
)

/** This device's signing key + its secure-element status. */
data class DeviceInfo(
    val name: String,
    /** Role + short fingerprint, e.g. `dev · A29F 67B1`. */
    val label: String,
    val backing: HardwareBacking,
    val biometricRequired: Boolean,
) {
    val hardwareBacked: Boolean get() = backing != HardwareBacking.SOFTWARE
}

/** A relying party this identity has enrolled with. */
data class TrustedSite(
    val id: String,
    val domain: String,
    val appName: String,
    val lastUsed: String,
    /** A stable seed for the leading avatar's accent, 0..n. */
    val accent: SiteAccent = SiteAccent.BLUE,
)

enum class SiteAccent { BLUE, PURPLE, MINT }

/** Overall identity state the app boots into. */
sealed interface IdentityState {
    /** No identity yet — the app shows onboarding. */
    data object None : IdentityState

    /** An identity is provisioned on this device. */
    data class Active(
        val identity: Identity,
        val device: DeviceInfo,
        val trustedSites: List<TrustedSite>,
        val biometricApproval: Boolean = true,
    ) : IdentityState

    /** Still loading from the keystore. */
    data object Loading : IdentityState
}

/** The newly minted recovery secret shown on the backup screen (create flow). */
data class RecoveryBackup(
    /** The bech32m secret, e.g. `heyarr1...`, already grouped for display. */
    val groupedSecret: String,
    /** The raw single-line secret, for copy. */
    val rawSecret: String,
)

/**
 * A decoded QR the scanner produced — either a web-login request or a device
 * pairing invite. The library's `LoginQr` parser (commonMain) is the authority on
 * the wire form; this is the app's dispatch shape.
 */
sealed interface ScannedCode {
    data class WebLogin(val rpBase: String, val loginId: String, val raw: String) : ScannedCode
    data class PairInvite(val relay: String, val session: String, val raw: String) : ScannedCode
    data class Unknown(val raw: String) : ScannedCode
}

/** A pending web-login the user is being asked to approve. */
data class LoginRequest(
    val domain: String,
    val appName: String,
    val origin: String,
    val signInAs: String,
    val expiresInSeconds: Int,
    val access: String = "Authentication only",
    val signatureValid: Boolean = true,
    /**
     * Non-empty only for a **number-matching (v2)** login — a push wake, where
     * nothing was scanned. The approval UI shows these numbers and the user taps the
     * one matching the initiating surface; the tap is what proves the human is
     * looking at the right screen (see [VoidbindEngine.approveNumberMatch]). Empty
     * for a scanned v1 login, whose QR already carries its origin-binding.
     */
    val candidates: List<Int> = emptyList(),
) {
    /** True when this login must be approved by tapping a matched number, not a plain Approve. */
    val isNumberMatch: Boolean get() = candidates.isNotEmpty()
}

/**
 * The result of fetching a web-login request. A fetch touches the network (the RP is the
 * source of truth for the challenge), so it can fail — an unreachable RP, a TLS error, a
 * timeout, a non-2xx, or a cleartext-blocked URL. The engine catches every such failure
 * and returns [Failed] rather than throwing, so the UI can render an error instead of the
 * app crashing with an uncaught main-thread exception (see [VoidbindEngine.fetchLoginRequest]).
 */
sealed interface LoginRequestResult {
    /** The RP answered; show the approval sheet for [request]. */
    data class Ready(val request: LoginRequest) : LoginRequestResult

    /**
     * The fetch failed; show [message] (already human-readable, no raw exception text).
     * [expired] is true when the login code was stale (a 404/410 on the challenge fetch) rather
     * than unreachable or refused — the UI can then title the dialog "Expired" and tell the user
     * to scan a fresh QR instead of the generic "Sign-in unavailable".
     */
    data class Failed(val message: String, val expired: Boolean = false) : LoginRequestResult
}

/** SAS-compare state for the pair VERIFY step. */
data class PairSession(
    val thisDeviceName: String,
    val peerDeviceName: String,
    /** The 7-digit security code, grouped as `NNN NNNN`. */
    val securityCode: String,
)

/** The invite this device shows when it is the existing (initiator) side. */
data class PairInviteDisplay(
    val inviteId: String,
    val qrPayload: String,
    val expiresInSeconds: Int,
)
