package one.rarebit.cruciform.domain

import one.rarebit.voidbind.policy.ApprovalAuditEntry
import one.rarebit.voidbind.policy.ApprovalPolicy

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
    /**
     * The human label shown beside the fingerprint: the name of THIS device as enrolled
     * (what the user chose in Settings, or the enrolled device name — never a placeholder
     * such as a mockup version tag). A paired "Add this device" install shows the name it
     * enrolled under; a device with no name shows `This device`.
     */
    val label: String,
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
    /**
     * This RP's per-site approval policy (trust-on-first-use vs. always-ask). Joined
     * in from the [one.rarebit.voidbind.policy.SitePolicyStore] at load time — it is
     * NOT part of the trusted-site serialization, so the two stores stay independent.
     */
    val policy: ApprovalPolicy = ApprovalPolicy.AlwaysAsk,
    /** True when the user pinned "always ask" — the toggle reflects an explicit choice, not a default. */
    val pinnedAlwaysAsk: Boolean = false,
)

enum class SiteAccent { BLUE, PURPLE, MINT }

/**
 * One row in the approval-activity log the UI renders — a UI projection of the
 * library's [ApprovalAuditEntry] with display-ready fields. The engine formats the
 * timestamp; the screen stays free of date math.
 */
data class ApprovalActivity(
    val rp: String,
    val audience: String,
    val loginId: String,
    val approved: Boolean,
    /** Already-formatted time, e.g. "just now" / "2h ago" / an absolute date. */
    val whenLabel: String,
    /** The tapped number for a number-matching (v2) login, or null for a scanned login. */
    val matchNumber: Int? = null,
) {
    companion object {
        fun from(entry: ApprovalAuditEntry, whenLabel: String): ApprovalActivity = ApprovalActivity(
            rp = entry.rp,
            audience = entry.audience,
            loginId = entry.loginId,
            approved = entry.decision == one.rarebit.voidbind.policy.ApprovalDecision.Approved,
            whenLabel = whenLabel,
            matchNumber = entry.matchNumber,
        )
    }
}

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

/**
 * A user-facing failure from an engine step that touches the network or the hardware
 * gate. [message] is already human-readable and says what to do ("Can't reach the relay
 * at <host>. Check Wi-Fi or your VPN and try again.") — never raw exception text.
 * [retryable] tells the UI whether a Retry that re-runs the SAME step (re-join the same
 * invite, re-mint the invite, re-confirm) can plausibly succeed; a protocol failure or
 * a cancelled biometric prompt is not retried against the same session.
 */
data class EngineFailure(
    val message: String,
    val kind: Kind,
    val retryable: Boolean = kind == Kind.UNREACHABLE || kind == Kind.REJECTED,
) {
    enum class Kind {
        /** No route to the relay / RP (Wi-Fi, VPN, DNS, TLS, timeout). Retry when the network is back. */
        UNREACHABLE,
        /** The peer never showed up: the invite expired unjoined. Retry = a fresh invite. */
        TIMEOUT,
        /** The relay was reached but refused (stale / used session, server error). */
        REJECTED,
        /** The bytes did not verify — do not retry the same session. */
        PROTOCOL,
        /** The human cancelled the biometric prompt. */
        CANCELLED,
        /** Anything else (a bug, a missing precondition such as no identity). */
        INTERNAL,
    }
}

/**
 * The result of an engine step that MUST NOT throw for a transport/hardware failure:
 * every pairing entry point returns one, so a blocking relay call that blows up inside
 * `withContext(Dispatchers.IO)` is turned into a value on the IO thread — it never
 * escapes the coroutine. (A call-site `runCatching` is not enough: when the calling
 * coroutine has already been cancelled, an exception thrown later by the blocking call
 * has no caller to deliver to and reaches the uncaught handler as a main-thread FATAL,
 * which is exactly the crash seen on-device.)
 */
sealed interface EngineResult<out T> {
    data class Ready<T>(val value: T) : EngineResult<T>
    data class Failed(val failure: EngineFailure) : EngineResult<Nothing>
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
