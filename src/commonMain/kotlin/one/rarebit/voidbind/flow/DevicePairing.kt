package one.rarebit.voidbind.flow

import one.rarebit.voidbind.DeviceIdentity
import one.rarebit.voidbind.Invite
import one.rarebit.voidbind.net.Admission
import one.rarebit.voidbind.net.HttpTransport
import one.rarebit.voidbind.net.PairflowResponder
import one.rarebit.voidbind.net.RelayClient

/**
 * The **new device** joining an existing account, as one app flow: the user scans
 * the `voidbind:pair?…` invite QR (v3 — it names the identity) off the existing
 * device's screen, both sides run the commit-before-reveal handshake over the
 * relay, this side EVALUATES the initiator's membership ops and refuses a
 * non-member before any SAS exists, the two screens show a 7-digit SAS, and — only
 * after the human confirms the two devices show the SAME number — this device
 * receives the add op (and the ops that authorise it) the existing device sealed
 * to it.
 *
 * Two steps around the human gate:
 *  1. [begin] joins the relay session from the invite and runs the handshake →
 *     the [Handshake.sas] to display for comparison. Signs/receives nothing.
 *  2. [confirm] (only after the human matches the SAS) unseals + re-evaluates the
 *     delivered admission and returns the [Admission] (op + ops) to persist.
 *
 * Blocking (the relay is polled); run it off the main thread. Pairs with the
 * existing device's [DeviceAuthorization]; the SAS closes the rushing attack.
 * [clock] supplies unix seconds for the membership evaluation.
 */
class DevicePairing(
    private val http: HttpTransport,
    private val device: DeviceIdentity,
    private val clock: () -> Long,
    private val pollIntervalMillis: Long = 150,
) {
    /** The handshake result: the [sas] to compare, plus the state [confirm] resumes. */
    class Handshake internal constructor(
        val sas: String,
        internal val responder: PairflowResponder,
        /** The relay this handshake ran over — named in a [confirmCatching] failure. */
        internal val relayBase: String = "",
        /** The identity (`ed25519:<hex>`) this device is joining, from the invite. */
        val userId: String = "",
    )

    /**
     * Like [begin], but a transport failure (no route to the relay, refused, TLS, timeout,
     * cleartext-blocked), a relay refusal, a peer that never joins, a commitment that
     * does not open, or an initiator that is NOT a member resolves to a
     * [PairingOutcome.Failed] the caller renders — it never throws. This is the
     * boundary that keeps a blocking relay call from escaping a coroutine as an
     * uncaught crash. A malformed invite string also resolves to a `Failed` (PROTOCOL).
     * The throwing overloads remain for callers that already catch. Blocking; run off
     * the main thread.
     */
    fun beginCatching(inviteQr: String): PairingOutcome<Handshake> {
        val invite = try {
            Invite.decode(inviteQr)
        } catch (e: Throwable) {
            return PairingFailures.classify(e, relayBase = "")
        }
        return beginCatching(invite)
    }

    /** [beginCatching] for an already-parsed invite (the Scan screen / deep-link path). */
    fun beginCatching(invite: Invite.Parsed): PairingOutcome<Handshake> =
        PairingFailures.catching(invite.relay) { begin(invite) }

    /**
     * Like [confirm], but a failed delivery (relay unreachable / refused / the initiator
     * never posted the admission) or an admission that does not verify / does not
     * admit this device resolves to a [PairingOutcome.Failed] instead of throwing.
     */
    fun confirmCatching(handshake: Handshake): PairingOutcome<Admission> =
        PairingFailures.catching(handshake.relayBase) { confirm(handshake) }

    /** Scan the invite QR, join the relay, run the handshake, return the SAS. */
    @Throws(Exception::class)
    fun begin(inviteQr: String): Handshake = begin(Invite.decode(inviteQr))

    /** Same, when the Scan screen already parsed the invite via [one.rarebit.voidbind.VoidbindQr]. */
    @Throws(Exception::class)
    fun begin(invite: Invite.Parsed): Handshake {
        val relay = RelayClient(
            http, invite.relay, invite.session, RelayClient.ROLE_RESPONDER, pollIntervalMillis,
        )
        val responder = PairflowResponder(
            relay, invite.user, device.signPublicKey, device.encPublicKey, invite.salt, clock(),
        )
        return Handshake(responder.handshake(), responder, invite.relay, invite.user)
    }

    /**
     * After the human confirms the SAS matches: receive the sealed admission, unseal
     * it with this device's X25519 key, re-evaluate it, and return the [Admission]
     * (this device's admitting op + the ops that authorise it) to store. Throws if the
     * delivered op does not verify, binds a different device/user, or does not make
     * this device a member. `@Throws` so a delivery/verify failure is catchable in Swift.
     */
    @Throws(Exception::class)
    fun confirm(handshake: Handshake): Admission =
        handshake.responder.receive(device.encPrivateKey)
}
