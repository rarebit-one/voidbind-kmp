package one.rarebit.cruciform.platform

import android.content.Context
import one.rarebit.cruciform.domain.SiteAccent
import one.rarebit.cruciform.domain.TrustedSite
import one.rarebit.voidbind.Membership
import one.rarebit.voidbind.crypto.Hex

/**
 * On-device persistence for the provisioned identity. **Public** material (the
 * admitting op, the membership replica, the user + device public keys, the device
 * name, the trusted-site list) lives in plain SharedPreferences — none of it can
 * authenticate as the user. **Secret** material is sealed in hardware via
 * [SealedSecretStore]:
 *
 *  - `device-enc` — the X25519 device encryption private key (no secure element
 *    holds an agreement key; sealed at rest per ADR-0001).
 *  - `recovery` — the 32-byte recovery secret, on an install that created or
 *    restored the identity. Sealed so the app can re-show it biometric-gated from
 *    Settings and act as GENESIS for recovery re-adds. It is NOT needed to add a
 *    device any more (ADR-0005): any member admits the next with its device key.
 *
 * **Membership (ADR-0005).** `cert` holds this device's ADMITTING op — the
 * credential it presents — and `ops` the replica of the identity's membership ops
 * it knows (newline-joined tokens). An install from before 0.5.0 has a v2 cert and
 * no `ops`; a v2 cert IS a genesis add, so [knownOps] simply merges the cert in and
 * [load] writes the replica on first read — no re-enrolment, nothing lost.
 *
 * The library never persists anything — this is entirely the app's plumbing.
 */
class IdentityStore(context: Context) {

    private val prefs = context.getSharedPreferences("voidbind.identity", Context.MODE_PRIVATE)
    private val sealed = SealedSecretStore(context)

    data class Persisted(
        /** This device's admitting op (a v3 add, or a v1/v2 cert — which is one). */
        val enrolmentCert: String,
        val userPublicKey: ByteArray,
        val encPublicKey: ByteArray,
        val deviceName: String,
        val biometricApproval: Boolean,
        /** The membership replica, ALWAYS including [enrolmentCert]; de-duplicated, hash order. */
        val ops: List<String>,
    )

    fun isProvisioned(): Boolean = prefs.contains(KEY_CERT)

    fun load(): Persisted? {
        val cert = prefs.getString(KEY_CERT, null) ?: return null
        val userPub = prefs.getString(KEY_USER_PUB, null) ?: return null
        val encPub = prefs.getString(KEY_ENC_PUB, null) ?: return null
        val stored = readOps()
        val ops = Membership.merge(stored, listOf(cert))
        if (stored.size != ops.size) {
            // Migration of a pre-0.5.0 install (or a replica that somehow lost its own
            // admission): the cert is a genesis add — record it as the replica's first op.
            writeOps(ops)
        }
        return Persisted(
            enrolmentCert = cert,
            userPublicKey = Hex.decode(userPub),
            encPublicKey = Hex.decode(encPub),
            deviceName = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME,
            biometricApproval = prefs.getBoolean(KEY_BIOMETRIC, true),
            ops = ops,
        )
    }

    /**
     * Persist the OWNER device — the one that created or restored the identity, so it
     * holds the recovery secret (sealed) and can re-show it + act as genesis. Its
     * self-enrolment cert is the replica's first op.
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
        writeCommon(enrolmentCert, listOf(enrolmentCert), userPublicKey, encPublicKey, encPrivateKey, deviceName)
    }

    /**
     * Persist a JOINED device — one admitted by pairing against an existing member.
     * It holds its admitting [op] and the [ops] that authorise it (the replica it
     * received), but NOT the recovery secret. It can still add further devices: any
     * member admits the next (ADR-0005).
     */
    fun saveJoined(
        op: String,
        ops: List<String>,
        userPublicKey: ByteArray,
        encPublicKey: ByteArray,
        encPrivateKey: ByteArray,
        deviceName: String,
    ) = writeCommon(op, Membership.merge(ops, listOf(op)), userPublicKey, encPublicKey, encPrivateKey, deviceName)

    private fun writeCommon(
        enrolmentCert: String,
        ops: List<String>,
        userPublicKey: ByteArray,
        encPublicKey: ByteArray,
        encPrivateKey: ByteArray,
        deviceName: String,
    ) {
        sealed.seal(SEAL_ENC, encPrivateKey)
        prefs.edit()
            .putString(KEY_CERT, enrolmentCert)
            .putString(KEY_OPS, ops.joinToString("\n"))
            .putString(KEY_USER_PUB, Hex.encode(userPublicKey))
            .putString(KEY_ENC_PUB, Hex.encode(encPublicKey))
            .putString(KEY_DEVICE_NAME, deviceName)
            .putBoolean(KEY_BIOMETRIC, true)
            .apply()
    }

    // --- membership replica (ADR-0005) ----------------------------------------------

    /** The ops this device knows, always including its own admitting op; hash order. */
    fun knownOps(): List<String> {
        val cert = prefs.getString(KEY_CERT, null) ?: return emptyList()
        return Membership.merge(readOps(), listOf(cert))
    }

    /**
     * Merge [ops] into the replica — what the device does with the ops a pairing peer
     * or an RP hands it (a new sibling's add, a remove it had not seen, the remove it
     * just signed). Tokens are stored as given; `Membership.evaluate` judges them on
     * every read, so a junk or foreign token recorded here is inert, never trusted.
     */
    fun recordOps(ops: List<String>) {
        writeOps(Membership.merge(knownOps(), ops))
    }

    private fun readOps(): List<String> =
        (prefs.getString(KEY_OPS, "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun writeOps(ops: List<String>) {
        prefs.edit().putString(KEY_OPS, ops.joinToString("\n")).apply()
    }

    /** True on an install that holds the sealed recovery secret (can act as genesis / re-show it). */
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
            val p = line.split("")
            if (p.size < 5) null
            else TrustedSite(p[0], p[1], p[2], p[3], runCatching { SiteAccent.valueOf(p[4]) }.getOrDefault(SiteAccent.BLUE))
        }

    fun upsertTrustedSite(site: TrustedSite) {
        val current = trustedSites().filterNot { it.id == site.id }
        val next = (current + site).joinToString("\n") {
            listOf(it.id, it.domain, it.appName, it.lastUsed, it.accent.name).joinToString("")
        }
        prefs.edit().putString(KEY_SITES, next).apply()
    }

    fun removeTrustedSite(id: String) {
        val next = trustedSites().filterNot { it.id == id }.joinToString("\n") {
            listOf(it.id, it.domain, it.appName, it.lastUsed, it.accent.name).joinToString("")
        }
        prefs.edit().putString(KEY_SITES, next).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_CERT = "cert"
        const val KEY_OPS = "ops"
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
