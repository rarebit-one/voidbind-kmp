package one.rarebit.voidbind.flow

import one.rarebit.voidbind.DeviceIdentity
import one.rarebit.voidbind.Invite
import one.rarebit.voidbind.net.HttpTransport
import one.rarebit.voidbind.net.PairflowResponder
import one.rarebit.voidbind.net.RelayClient

/**
 * The **new device** joining an existing account, as one app flow: the user scans
 * the `voidbind:pair?…` invite QR off the existing device's screen, both sides run
 * the commit-before-reveal handshake over the relay, the two screens show a
 * 7-digit SAS, and — only after the human confirms the two devices show the SAME
 * number — this device receives the enrolment cert the existing device sealed to
 * it.
 *
 * Two steps around the human gate:
 *  1. [begin] joins the relay session from the invite and runs the handshake →
 *     the [Handshake.sas] to display for comparison. Signs/receives nothing.
 *  2. [confirm] (only after the human matches the SAS) unseals + verifies the
 *     delivered cert and returns the token to persist.
 *
 * Blocking (the relay is polled); run it off the main thread. Pairs with the
 * existing device's [DeviceAuthorization]; the SAS closes the rushing attack.
 */
class DevicePairing(
    private val http: HttpTransport,
    private val device: DeviceIdentity,
    private val pollIntervalMillis: Long = 150,
) {
    /** The handshake result: the [sas] to compare, plus the state [confirm] resumes. */
    class Handshake internal constructor(
        val sas: String,
        internal val responder: PairflowResponder,
    )

    /** Scan the invite QR, join the relay, run the handshake, return the SAS. */
    @Throws(Exception::class)
    fun begin(inviteQr: String): Handshake = begin(Invite.decode(inviteQr))

    /** Same, when the Scan screen already parsed the invite via [one.rarebit.voidbind.VoidbindQr]. */
    @Throws(Exception::class)
    fun begin(invite: Invite.Parsed): Handshake {
        val relay = RelayClient(
            http, invite.relay, invite.session, RelayClient.ROLE_RESPONDER, pollIntervalMillis,
        )
        val responder = PairflowResponder(relay, device.signPublicKey, device.encPublicKey, invite.salt)
        return Handshake(responder.handshake(), responder)
    }

    /**
     * After the human confirms the SAS matches: receive the sealed cert, unseal it
     * with this device's X25519 key, verify it, and return the enrolment cert token
     * to store. Throws if the delivered cert does not verify or binds a different
     * device/user. `@Throws` so a delivery/verify failure is catchable in Swift.
     */
    @Throws(Exception::class)
    fun confirm(handshake: Handshake): String =
        handshake.responder.receive(device.encPrivateKey)
}
