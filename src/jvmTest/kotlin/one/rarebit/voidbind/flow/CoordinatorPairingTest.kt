package one.rarebit.voidbind.flow

import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import one.rarebit.voidbind.Cert
import one.rarebit.voidbind.DeviceIdentity
import one.rarebit.voidbind.Ed25519Engine
import one.rarebit.voidbind.KeyRef
import one.rarebit.voidbind.Pairing
import one.rarebit.voidbind.UserIdentity
import one.rarebit.voidbind.crypto.Ed25519Group
import one.rarebit.voidbind.net.HttpResponse
import one.rarebit.voidbind.net.HttpTransport

/**
 * The two pairing coordinators against each other through an in-memory relay:
 * [DeviceAuthorization] (existing device, holds the user key) authorises
 * [DevicePairing] (new device). Proves the SAS matches on both screens and the
 * cert the new device receives verifies + binds the right device and user — the
 * full "add a device" flow, minus the physical QR and the human tap.
 */
class CoordinatorPairingTest {

    /** A faithful in-memory voidbind relay: opaque, write-once slots over HTTP. */
    private class RelayTransport : HttpTransport {
        private val slots = ConcurrentHashMap<String, ByteArray>()
        private var seq = 0
        override fun post(url: String, body: ByteArray?, contentType: String?): HttpResponse {
            if (url.endsWith("/v1/sessions")) {
                val id = "s${++seq}"
                return HttpResponse(200, """{"session_id":"$id"}""".encodeToByteArray())
            }
            return HttpResponse(404, ByteArray(0))
        }
        override fun put(url: String, body: ByteArray, contentType: String?): HttpResponse {
            val key = url.substringAfter("/v1/sessions/")
            return if (slots.putIfAbsent(key, body) != null) HttpResponse(409, ByteArray(0))
            else HttpResponse(204, ByteArray(0))
        }
        override fun get(url: String): HttpResponse {
            val key = url.substringAfter("/v1/sessions/")
            val v = slots[key] ?: return HttpResponse(404, ByteArray(0))
            return HttpResponse(200, v)
        }
        override fun sleep(millis: Long) = Thread.sleep(millis)
    }

    private fun softwareDevice(seedByte: Int): DeviceIdentity {
        val seed = ByteArray(32) { (it + seedByte).toByte() }
        val enc = DeviceIdentity.generateEncryptionKey()
        return DeviceIdentity(
            signPublicKey = Ed25519Group.publicKeyFromSeed(seed),
            encPublicKey = enc.publicKey,
            encPrivateKey = enc.privateKey,
            signFn = { msg -> Ed25519Engine.sign(seed, msg) },
        )
    }

    @Test
    fun existingDeviceAuthorisesANewDeviceAndTheCertVerifies() {
        val http = RelayTransport()
        val relayBase = "http://relay.test"
        val user = UserIdentity.create()
        val newDevice = softwareDevice(seedByte = 11)

        val auth = DeviceAuthorization(http, user, clock = { 1_724_700_000L }, pollIntervalMillis = 10)
        val pairing = DevicePairing(http, newDevice, pollIntervalMillis = 10)

        // The existing device opens the session and renders the invite QR.
        val salt = ByteArray(32) { (it * 5 + 1).toByte() }
        val invitation = auth.invite(relayBase, salt)
        assertTrue(invitation.inviteQr.startsWith("voidbind:pair?"), "must be a pairing invite")
        assertTrue(invitation.inviteQr.contains("v=2"), "invite must carry version 2")

        // Both run the handshake concurrently (each blocks on the peer's slot).
        var sasInitiator = ""
        var responderHandshake: DevicePairing.Handshake? = null
        val tA = Thread { sasInitiator = auth.handshake(invitation) }
        val tB = Thread { responderHandshake = pairing.begin(invitation.inviteQr) }
        tA.start(); tB.start(); tA.join(15_000); tB.join(15_000)

        val handshake = responderHandshake!!
        assertEquals(sasInitiator, handshake.sas, "both screens must show the same SAS")
        assertEquals(Pairing.DIGITS, handshake.sas.length)

        // Human confirms → the existing device signs+seals, the new device receives.
        auth.authorise(invitation)
        val certToken = pairing.confirm(handshake)

        val parsed = Cert.parse(certToken)
        assertTrue(parsed.verify(Ed25519Engine.verifier()), "the delivered cert must verify")
        assertEquals(user.userId, parsed.cert.user, "cert must be signed by the pairing user identity")
        assertEquals(KeyRef.ed25519(newDevice.signPublicKey), parsed.cert.device, "cert must bind the new device")
        assertEquals(KeyRef.x25519(newDevice.encPublicKey), parsed.cert.deviceEnc)
    }
}
