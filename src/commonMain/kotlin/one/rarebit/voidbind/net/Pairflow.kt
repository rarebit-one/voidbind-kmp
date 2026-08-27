package one.rarebit.voidbind.net

import one.rarebit.voidbind.Cert
import one.rarebit.voidbind.Ed25519Engine
import one.rarebit.voidbind.KeyRef
import one.rarebit.voidbind.Labels
import one.rarebit.voidbind.Pairing
import one.rarebit.voidbind.crypto.Base64Url
import one.rarebit.voidbind.crypto.MiniJson

/**
 * Device-side pairing over an untrusted relay (voidbind-go `pairflow`): the
 * two-sided commit-before-reveal handshake, with the human gate.
 *
 * [PairflowInitiator] is the existing device (holds the user identity key);
 * [PairflowResponder] is the new device. Each side runs [handshake] — commit,
 * fetch the peer commit BEFORE revealing, reveal, OPEN the peer commitment, then
 * derive the SAS — and returns the string a human compares. Handshake signs
 * nothing; only [PairflowInitiator.authorise] signs + hands over the cert, and
 * only [PairflowResponder.receive] takes it in, so the human comparison gates
 * enrolment.
 *
 * The relay envelope is byte-identical to voidbind-go: the `reveal` is
 * `{"sign":"ed25519:<hex>"[,"enc":"x25519:<hex>"]}`, and the `cert` message is
 * `{"wrapped":"<b64url>","cipher":"<b64url>"}` — a space key sealed to the
 * responder's X25519 key plus the cert token encrypted under it.
 *
 * The X25519 SEAL/UNSEAL crypto ([CertSealer]) is implemented by
 * [VoidbindCertSealer] (voidbind-go/encryption, byte-for-byte, KAT-verified) and
 * is the default; a caller may inject an alternative for testing.
 */

/** The X25519-sealed enrolment cert as it crosses the relay. */
class SealedCert(val wrapped: ByteArray, val cipher: ByteArray)

/**
 * Seals/opens the enrolment cert to a device's X25519 encryption key. The
 * production implementation is [VoidbindCertSealer] (ephemeral-static ECDH seal +
 * XChaCha20-Poly1305, byte-identical to voidbind-go/encryption).
 */
interface CertSealer {
    fun seal(certToken: String, recipientEncPub: ByteArray): SealedCert
    fun open(sealed: SealedCert, recipientEncPriv: ByteArray): String
}

private fun revealJson(signRendered: String, encRendered: String?): ByteArray {
    val fields = mutableListOf<Pair<String, Any>>("sign" to signRendered)
    if (encRendered != null) fields.add("enc" to encRendered)
    return MiniJson.encodeObject(fields).encodeToByteArray()
}

private class Reveal(val sign: ByteArray, val enc: ByteArray)

private fun parseReveal(bytes: ByteArray): Reveal {
    val obj = MiniJson.parseObject(bytes.decodeToString())
    val sign = KeyRef.parse(obj["sign"] as String).bytes
    val encStr = obj["enc"] as? String
    val enc = if (encStr.isNullOrEmpty()) ByteArray(0) else KeyRef.parse(encStr).bytes
    return Reveal(sign, enc)
}

class PairflowInitiator(
    private val relay: RelayClient,
    private val userSeed: ByteArray,
    private val userPub: ByteArray,
    private val salt: ByteArray,
    private val issuedAt: Long,
    private val lifetimeSeconds: Long,
) {
    private var respSign: ByteArray = ByteArray(0)
    private var respEnc: ByteArray = ByteArray(0)
    private var handshook = false

    /** Run the handshake and return the SAS to compare. Signs nothing. */
    fun handshake(): String {
        val myCommit = Pairing.commit(userPub) // user identity has no enc key
        relay.post("commit", myCommit)
        val peerCommit = relay.fetch("commit")

        relay.post("reveal", revealJson(KeyRef.ed25519(userPub).render(), null))
        val peer = parseReveal(relay.fetch("reveal"))
        require(Pairing.opens(peerCommit, peer.sign, peer.enc)) {
            "pairflow: responder commitment does not open (rushing attacker)"
        }
        val sas = Pairing.deriveSas(
            Pairing.Keys(userPub),
            Pairing.Keys(peer.sign, peer.enc),
            salt,
        )
        respSign = peer.sign
        respEnc = peer.enc
        handshook = true
        return sas
    }

    /** After the human confirms the SAS: sign the cert, seal it, hand it over. */
    fun authorise(sealer: CertSealer = VoidbindCertSealer) {
        check(handshook) { "pairflow: authorise before a successful handshake" }
        val cert = Cert(
            version = Labels.CERT_VERSION,
            user = KeyRef.ed25519(userPub),
            device = KeyRef.ed25519(respSign),
            deviceEnc = KeyRef.x25519(respEnc),
            issuedAt = issuedAt,
            expiresAt = issuedAt + lifetimeSeconds,
        ).encode { msg -> Ed25519Engine.sign(userSeed, msg) }
        val sealed = sealer.seal(cert, respEnc)
        val msg = MiniJson.encodeObject(
            listOf(
                "wrapped" to Base64Url.encode(sealed.wrapped),
                "cipher" to Base64Url.encode(sealed.cipher),
            ),
        )
        relay.post("cert", msg.encodeToByteArray())
    }
}

class PairflowResponder(
    private val relay: RelayClient,
    private val deviceSignSeed: ByteArray,
    private val deviceSignPub: ByteArray,
    private val deviceEncPub: ByteArray,
    private val salt: ByteArray,
) {
    private var initSign: ByteArray = ByteArray(0)
    private var handshook = false

    val deviceId: String get() = KeyRef.ed25519(deviceSignPub).render()
    val deviceEncId: String get() = KeyRef.x25519(deviceEncPub).render()

    /** Run the handshake and return the SAS to compare. */
    fun handshake(): String {
        val myCommit = Pairing.commit(deviceSignPub, deviceEncPub)
        relay.post("commit", myCommit)
        val peerCommit = relay.fetch("commit")

        relay.post("reveal", revealJson(deviceId, deviceEncId))
        val peer = parseReveal(relay.fetch("reveal"))
        require(Pairing.opens(peerCommit, peer.sign)) { // initiator has no enc key
            "pairflow: initiator commitment does not open"
        }
        val sas = Pairing.deriveSas(
            Pairing.Keys(peer.sign),
            Pairing.Keys(deviceSignPub, deviceEncPub),
            salt,
        )
        initSign = peer.sign
        handshook = true
        return sas
    }

    /** After the human confirms: receive the sealed cert, unseal + verify it. */
    fun receive(deviceEncPriv: ByteArray, sealer: CertSealer = VoidbindCertSealer): String {
        check(handshook) { "pairflow: receive before a successful handshake" }
        val obj = MiniJson.parseObject(relay.fetch("cert").decodeToString())
        val sealed = SealedCert(
            Base64Url.decode(obj["wrapped"] as String),
            Base64Url.decode(obj["cipher"] as String),
        )
        val token = sealer.open(sealed, deviceEncPriv)
        val parsed = Cert.parse(token)
        require(parsed.verify(Ed25519Engine.verifier())) { "pairflow: received cert does not verify" }
        require(parsed.cert.user.bytes.contentEquals(initSign)) { "pairflow: cert signed by an unexpected user" }
        require(parsed.cert.device.render() == deviceId) { "pairflow: cert binds a different device" }
        return token
    }
}
