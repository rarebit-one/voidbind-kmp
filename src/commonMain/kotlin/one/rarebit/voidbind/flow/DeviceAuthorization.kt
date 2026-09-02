package one.rarebit.voidbind.flow

import dev.whyoleg.cryptography.random.CryptographyRandom
import one.rarebit.voidbind.Enrolment
import one.rarebit.voidbind.Invite
import one.rarebit.voidbind.Pairing
import one.rarebit.voidbind.UserIdentity
import one.rarebit.voidbind.net.HttpTransport
import one.rarebit.voidbind.net.PairflowInitiator
import one.rarebit.voidbind.net.RelayClient

/**
 * The **existing device authorising a new one**, as one app flow (the mirror of
 * [DevicePairing]): this device holds the user identity key. It opens a relay
 * session, shows the new device a `voidbind:pair?…` invite QR, runs the
 * commit-before-reveal handshake, displays the 7-digit SAS, and — only after the
 * human confirms both screens match — signs a device cert, seals it to the new
 * device's X25519 key, and posts it over the relay.
 *
 * Three steps around the human gate:
 *  1. [invite] opens the session + returns the [Invitation.inviteQr] to render.
 *  2. [handshake] runs once the new device joins → the SAS to compare.
 *  3. [authorise] (only after the human matches the SAS) signs + seals + delivers.
 *
 * Blocking (the relay is polled); run it off the main thread. [clock] supplies
 * unix seconds (`commonMain` has no clock) for the cert's issued-at.
 */
class DeviceAuthorization(
    private val http: HttpTransport,
    private val identity: UserIdentity,
    private val clock: () -> Long,
    private val certLifetimeSeconds: Long = Enrolment.DEFAULT_LIFETIME_SECONDS,
    private val pollIntervalMillis: Long = 150,
) {
    /** An opened pairing session: the [inviteQr] to render + the state the later steps resume. */
    class Invitation internal constructor(
        val inviteQr: String,
        val relaySession: String,
        internal val initiator: PairflowInitiator,
        /** The relay this invitation lives on — named in a `*Catching` failure. */
        internal val relayBase: String = "",
    )

    /**
     * Open a relay session and render the invite QR. A fresh random [salt] is drawn
     * per pairing (freshness for the SAS); pass one only to pin it in a test.
     */
    @Throws(Exception::class)
    fun invite(relayBase: String, salt: ByteArray = randomSalt()): Invitation {
        val session = RelayClient.createSession(http, relayBase)
        val relay = RelayClient(http, relayBase, session, RelayClient.ROLE_INITIATOR, pollIntervalMillis)
        val initiator = PairflowInitiator(
            relay,
            identity.userSeed,
            identity.userPublicKey,
            salt,
            issuedAt = clock(),
            lifetimeSeconds = certLifetimeSeconds,
        )
        return Invitation(Invite.encode(relayBase, session, salt), session, initiator, relayBase)
    }

    /**
     * Like [invite], but a relay that cannot be reached (no route, refused, TLS, timeout,
     * cleartext-blocked) or that refuses to open a session resolves to a
     * [PairingOutcome.Failed] the caller renders — it never throws, so the "Add a
     * device" tap cannot crash the app when the phone has no route to the relay.
     */
    fun inviteCatching(relayBase: String, salt: ByteArray = randomSalt()): PairingOutcome<Invitation> =
        PairingFailures.catching(relayBase) { invite(relayBase, salt) }

    /**
     * Like [handshake], but a transport failure, a relay refusal, an invite that expires
     * unjoined ([PairingFailureKind.TIMEOUT]) or a commitment that does not open resolves
     * to a [PairingOutcome.Failed] instead of throwing.
     */
    fun handshakeCatching(invitation: Invitation): PairingOutcome<String> =
        PairingFailures.catching(invitation.relayBase) { handshake(invitation) }

    /**
     * Like [authorise], but a transport failure or relay refusal while delivering the
     * sealed cert resolves to a [PairingOutcome.Failed] instead of throwing.
     */
    fun authoriseCatching(invitation: Invitation): PairingOutcome<Unit> =
        PairingFailures.catching(invitation.relayBase) { authorise(invitation) }

    /** Run the handshake once the new device joins; return the SAS to compare. */
    @Throws(Exception::class)
    fun handshake(invitation: Invitation): String = invitation.initiator.handshake()

    /** After the human confirms the SAS: sign, seal, and deliver the cert. */
    @Throws(Exception::class)
    fun authorise(invitation: Invitation) = invitation.initiator.authorise()

    private companion object {
        /** 32 bytes of salt — comfortably above [Pairing.MIN_SALT_LEN]. */
        fun randomSalt(): ByteArray = CryptographyRandom.Default.nextBytes(maxOf(32, Pairing.MIN_SALT_LEN))
    }
}
