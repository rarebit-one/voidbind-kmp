package one.rarebit.voidbind.app.platform

import android.content.Context
import one.rarebit.voidbind.app.domain.SiteAccent
import one.rarebit.voidbind.app.domain.TrustedSite
import one.rarebit.voidbind.crypto.Hex

/**
 * On-device persistence for the provisioned identity. **Public** material (the
 * enrolment cert, the user + device public keys, the device name, the trusted-site
 * list) lives in plain SharedPreferences — none of it can authenticate as the user.
 * **Secret** material is sealed in hardware via [SealedSecretStore]:
 *
 *  - `device-enc` — the X25519 device encryption private key (no secure element
 *    holds an agreement key; sealed at rest per ADR-0001).
 *  - `recovery` — the 32-byte recovery secret. Sealed so the app can (a) re-show it
 *    biometric-gated from Settings and (b) rehydrate the user identity to authorise
 *    a new device across launches. The recovery secret's role as the offline backup
 *    is unchanged; an attacker needs the secure element to unseal it.
 *
 * The library never persists anything — this is entirely the app's plumbing.
 */
class IdentityStore(context: Context) {

    private val prefs = context.getSharedPreferences("voidbind.identity", Context.MODE_PRIVATE)
    private val sealed = SealedSecretStore(context)

    data class Persisted(
        val enrolmentCert: String,
        val userPublicKey: ByteArray,
        val encPublicKey: ByteArray,
        val deviceName: String,
        val biometricApproval: Boolean,
    )

    fun isProvisioned(): Boolean = prefs.contains(KEY_CERT)

    fun load(): Persisted? {
        val cert = prefs.getString(KEY_CERT, null) ?: return null
        val userPub = prefs.getString(KEY_USER_PUB, null) ?: return null
        val encPub = prefs.getString(KEY_ENC_PUB, null) ?: return null
        return Persisted(
            enrolmentCert = cert,
            userPublicKey = Hex.decode(userPub),
            encPublicKey = Hex.decode(encPub),
            deviceName = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME,
            biometricApproval = prefs.getBoolean(KEY_BIOMETRIC, true),
        )
    }

    /**
     * Persist the OWNER device — the one that created or restored the identity, so it
     * holds the recovery secret (sealed) and can re-show it + authorise new devices.
     */
    fun saveOwner(
        enrolmentCert: String,
        userPublicKey: ByteArray,
        encPublicKey: ByteArray,
        encPrivateKey: ByteArray,
        recoverySecret: ByteArray,
        deviceName: String,
    ) {
        sealed.seal(SEAL_RECOVERY, recoverySecret)
        writeCommon(enrolmentCert, userPublicKey, encPublicKey, encPrivateKey, deviceName)
    }

    /**
     * Persist a JOINED device — one enrolled by pairing against an existing device. It
     * holds a device cert but NOT the user key / recovery secret, so it cannot re-show
     * the recovery secret or authorise further devices (that stays with the owner).
     */
    fun saveJoined(
        enrolmentCert: String,
        userPublicKey: ByteArray,
        encPublicKey: ByteArray,
        encPrivateKey: ByteArray,
        deviceName: String,
    ) = writeCommon(enrolmentCert, userPublicKey, encPublicKey, encPrivateKey, deviceName)

    private fun writeCommon(
        enrolmentCert: String,
        userPublicKey: ByteArray,
        encPublicKey: ByteArray,
        encPrivateKey: ByteArray,
        deviceName: String,
    ) {
        sealed.seal(SEAL_ENC, encPrivateKey)
        prefs.edit()
            .putString(KEY_CERT, enrolmentCert)
            .putString(KEY_USER_PUB, Hex.encode(userPublicKey))
            .putString(KEY_ENC_PUB, Hex.encode(encPublicKey))
            .putString(KEY_DEVICE_NAME, deviceName)
            .putBoolean(KEY_BIOMETRIC, true)
            .apply()
    }

    /** True on the owner device (holds the sealed recovery secret / user authority). */
    fun hasUserKey(): Boolean = sealed.exists(SEAL_RECOVERY)

    /** The sealed X25519 device encryption private key, or null if not provisioned. */
    fun encPrivateKey(): ByteArray? = sealed.unseal(SEAL_ENC)

    /** The sealed 32-byte recovery secret, or null. Caller should biometric-gate the reveal. */
    fun recoverySecret(): ByteArray? = sealed.unseal(SEAL_RECOVERY)

    fun deviceName(): String = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME

    fun setDeviceName(name: String) = prefs.edit().putString(KEY_DEVICE_NAME, name).apply()

    fun biometricApproval(): Boolean = prefs.getBoolean(KEY_BIOMETRIC, true)

    fun setBiometricApproval(enabled: Boolean) = prefs.edit().putBoolean(KEY_BIOMETRIC, enabled).apply()

    // --- trusted sites (app-tracked; the library does not model an RP list) ------

    fun trustedSites(): List<TrustedSite> =
        (prefs.getString(KEY_SITES, "") ?: "").split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split("")
            if (p.size < 5) null
            else TrustedSite(p[0], p[1], p[2], p[3], runCatching { SiteAccent.valueOf(p[4]) }.getOrDefault(SiteAccent.BLUE))
        }

    fun upsertTrustedSite(site: TrustedSite) {
        val current = trustedSites().filterNot { it.id == site.id }
        val next = (current + site).joinToString("\n") {
            listOf(it.id, it.domain, it.appName, it.lastUsed, it.accent.name).joinToString("")
        }
        prefs.edit().putString(KEY_SITES, next).apply()
    }

    fun removeTrustedSite(id: String) {
        val next = trustedSites().filterNot { it.id == id }.joinToString("\n") {
            listOf(it.id, it.domain, it.appName, it.lastUsed, it.accent.name).joinToString("")
        }
        prefs.edit().putString(KEY_SITES, next).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_CERT = "cert"
        const val KEY_USER_PUB = "userPub"
        const val KEY_ENC_PUB = "encPub"
        const val KEY_DEVICE_NAME = "deviceName"
        const val KEY_BIOMETRIC = "biometric"
        const val KEY_SITES = "sites"
        const val SEAL_ENC = "device-enc"
        const val SEAL_RECOVERY = "recovery"
        const val DEFAULT_DEVICE_NAME = "This device"
    }
}
