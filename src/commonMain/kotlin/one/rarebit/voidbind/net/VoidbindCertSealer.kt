package one.rarebit.voidbind.net

import one.rarebit.voidbind.crypto.VoidbindEncryption

/**
 * The production [CertSealer]: seals the enrolment cert to the responder's X25519
 * key exactly as voidbind-go's pairflow does — a fresh space key wrapped to the
 * recipient ([SealedCert.wrapped]) plus the cert token encrypted under that key
 * ([SealedCert.cipher]). The relay only ever ferries these two ciphertexts.
 *
 * This is the piece that completes the KMP pairing cert delivery; the handshake
 * and SAS needed no crypto beyond the primitives already present.
 */
object VoidbindCertSealer : CertSealer {

    override fun seal(certToken: String, recipientEncPub: ByteArray): SealedCert {
        val spaceKey = VoidbindEncryption.newSpaceKey()
        val wrapped = VoidbindEncryption.seal(spaceKey, recipientEncPub)
        val cipher = VoidbindEncryption.encryptChange(spaceKey, certToken.encodeToByteArray())
        return SealedCert(wrapped = wrapped, cipher = cipher)
    }

    override fun open(sealed: SealedCert, recipientEncPriv: ByteArray): String {
        val spaceKey = VoidbindEncryption.unwrap(sealed.wrapped, recipientEncPriv)
        val plaintext = VoidbindEncryption.decryptChange(spaceKey, sealed.cipher)
        return plaintext.decodeToString()
    }
}
