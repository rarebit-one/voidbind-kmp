package one.rarebit.voidbind

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import one.rarebit.voidbind.crypto.Base64Url
import one.rarebit.voidbind.crypto.Hex
import one.rarebit.voidbind.crypto.MiniJson

/**
 * A **membership op** — the v3 successor of the enrolment [Cert] (voidbind-go
 * `enrolment/op.go`, ADR-0007). Where a v1/v2 cert is "the user root vouches for
 * device D", an op is "member M says D is added / removed", carrying the causal
 * evidence (`prev`) that M was a member when it said so. An identity is no longer
 * one key that signs for everything; it is a SET of device keys, any of which can
 * admit or remove another, with the genesis key (derived from the recovery secret —
 * the `usr` every relying party pins) as a dormant, permanent, un-removable member
 * that only recovery ever signs with.
 *
 * Wire shape — byte-identical to voidbind-go, deliberately the cert's so the
 * `Device <op>~<proof>` credential and the possession proof's `sha256(token)`
 * binding survive unchanged:
 * ```
 * payload  {v:3, usr, op:"add"|"remove", dev, denc?, by, prev:[opHash…], cosig?:[{by,sig}…], iat, exp?}
 * token    b64url(payload) "." b64url(ed25519.Sign(by, payload))
 * hash     "sha256:" hex(sha256(token))
 * ```
 * The payload is compact JSON with the fields in exactly that order (they are
 * signed as-is). `denc`, `cosig` and `exp` are omitted when empty/zero, matching
 * Go's `omitempty`; `prev` is always present (`[]` when empty), sorted and
 * de-duplicated so equal head sets sign to equal bytes.
 *
 * **A v1/v2 cert IS a v3 op** — an add signed by genesis (`by = usr`) with no
 * `prev` — and [verify] reads it as one, so nothing already issued is reissued.
 */
data class MembershipOp(
    /** [HASH_PREFIX] + hex(sha256(token)) — the id other ops cite in `prev`. */
    val hash: String,
    val token: String,
    val version: Int,
    /** The identity (genesis key) this op belongs to, rendered `ed25519:<hex>`. */
    val user: String,
    val kind: Kind,
    /** The device the op adds or removes, rendered `ed25519:<hex>`. */
    val device: String,
    /** The device's X25519 encryption key (`x25519:<hex>`) for an add; empty for a remove or a v1 add. */
    val deviceEnc: String,
    /** The signer's rendered key — equal to [user] for a genesis-signed op. */
    val by: String,
    /** Op hashes the signer cited as its heads (sorted, de-duplicated). */
    val prev: List<String>,
    /** Reserved co-signatures — parsed and preserved, never enforced (v1.1's 2-of-N). */
    val cosig: List<Cosig>,
    /** Issued-at, unix seconds. */
    val issuedAt: Long,
    /** Expiry (unix seconds) for an add; 0 for a remove, which never expires. */
    val expiresAt: Long,
) {
    /** What an op does to the membership set. */
    enum class Kind(val wire: String) {
        ADD("add"),
        REMOVE("remove");

        companion object {
            fun fromWire(s: String): Kind? = entries.firstOrNull { it.wire == s }
        }
    }

    /** A reserved co-signature over the op payload by a second member (see [cosig]). */
    data class Cosig(val by: String, val sig: String)

    /** Whether the op is signed by the identity's genesis key. */
    val genesis: Boolean get() = by == user

    /**
     * Why an op is refused by [verify]. Distinct because they call for different
     * actions; [Membership.evaluate] maps them onto its `rejected` reasons.
     */
    enum class Failure { MALFORMED, BAD_SIGNATURE, GENESIS, NO_PREV }

    /** Thrown by [verify] (and [sign]) for an op that is not an op. */
    class OpException(val failure: Failure, message: String) : IllegalArgumentException(message)

    companion object {
        /** The version prefixing an op payload; v1 and v2 are certs and are reinterpreted. */
        const val VERSION = 3

        /** Renders an op hash: `sha256:<hex>` over the token's bytes. */
        const val HASH_PREFIX = "sha256:"

        /** Bounds how many heads one op may cite (voidbind-go `enrolment.MaxPrev`). */
        const val MAX_PREV = 64

        /** The default add lifetime — 90 days, renewable by any member re-adding. */
        const val DEFAULT_LIFETIME_SECONDS: Long = Enrolment.DEFAULT_LIFETIME_SECONDS

        private val sha256 = CryptographyProvider.Default.get(SHA256).hasher()

        /** The id of a token: [HASH_PREFIX] + hex(sha256(token)). */
        fun hash(token: String): String =
            HASH_PREFIX + Hex.encode(sha256.hashBlocking(token.encodeToByteArray()))

        /**
         * Mint a v3 op signed by [signer] (whose public key is [byPublicKey]) for
         * identity [usr]: an add of [dev] (with its encryption key [deviceEnc], opaque
         * here) valid for [lifetimeSeconds] from [issuedAt] (≤ 0 → the default), or a
         * remove of [dev] (lifetime ignored). [prev] are the signer's current heads
         * ([Membership.View.heads]) — the causal evidence that the signer was a member.
         *
         * A genesis signer (`byPublicKey` renders to [usr]) may cite no prev — the first
         * add, or a recovery re-add — but must cite the remove it overrides for a
         * re-add to be honoured. A member signer must cite at least one head
         * ([Failure.NO_PREV]): an empty past is never a member's.
         *
         * Mirrors voidbind-go `enrolment.SignOp` byte-for-byte.
         */
        fun sign(
            signer: Ed25519Signer,
            byPublicKey: ByteArray,
            usr: String,
            kind: Kind,
            dev: String,
            deviceEnc: String,
            prev: List<String>,
            issuedAt: Long,
            lifetimeSeconds: Long = DEFAULT_LIFETIME_SECONDS,
        ): String {
            val usrRef = try { KeyRef.parse(usr) } catch (e: IllegalArgumentException) {
                throw OpException(Failure.MALFORMED, "usr: ${e.message}")
            }
            require(usrRef.alg == Labels.ALG_ED25519 && usrRef.bytes.size == 32) { "usr must be a 32-byte ed25519 key" }
            if (dev.isEmpty()) throw OpException(Failure.MALFORMED, "a device is required")
            if (dev == usr) throw OpException(Failure.GENESIS, "genesis cannot be added or removed")
            if (issuedAt <= 0) throw OpException(Failure.MALFORMED, "an issued-at is required")
            val by = KeyRef.ed25519(byPublicKey).render()
            val heads = normalisePrev(prev)
            if (heads.size > MAX_PREV) throw OpException(Failure.MALFORMED, "${heads.size} prev, max $MAX_PREV")
            if (by != usr && heads.isEmpty()) throw OpException(Failure.NO_PREV, "a member-signed op must cite its heads")

            val fields = ArrayList<Pair<String, Any>>()
            fields += "v" to VERSION
            fields += "usr" to usr
            fields += "op" to kind.wire
            fields += "dev" to dev
            var exp = 0L
            when (kind) {
                Kind.ADD -> {
                    val life = if (lifetimeSeconds <= 0) DEFAULT_LIFETIME_SECONDS else lifetimeSeconds
                    if (deviceEnc.isNotEmpty()) fields += "denc" to deviceEnc
                    exp = issuedAt + life
                }
                Kind.REMOVE -> { /* a remove binds no encryption key and never expires */ }
            }
            fields += "by" to by
            fields += "prev" to heads
            fields += "iat" to issuedAt
            if (exp != 0L) fields += "exp" to exp
            val body = MiniJson.encodeObject(fields).encodeToByteArray()
            return Base64Url.encode(body) + "." + Base64Url.encode(signer.sign(body))
        }

        /**
         * Parse a token and check its signature under its own `by`. Self-contained —
         * no pinned key, no clock — because the trust decision is not made here: the
         * signature only proves WHO said it, and whether that signer was a member at
         * the time is [Membership.evaluate]'s causal question.
         *
         * A v1 or v2 cert is read as the op it is: an add of its device, signed by
         * genesis (`by = usr`), with no prev. Throws [OpException] for anything that is
         * not an op. Mirrors voidbind-go `enrolment.VerifyOp`.
         */
        fun verify(rawToken: String, verifier: Ed25519Verifier = Ed25519Engine.verifier()): MembershipOp {
            val token = rawToken.trim()
            val dot = token.indexOf('.')
            if (dot < 0) throw OpException(Failure.MALFORMED, "malformed membership op")
            val body = decodeOrNull(token.substring(0, dot)) ?: throw OpException(Failure.MALFORMED, "payload is not base64url")
            val sig = decodeOrNull(token.substring(dot + 1)) ?: throw OpException(Failure.MALFORMED, "signature is not base64url")
            val obj = try {
                MiniJson.parseObject(body.decodeToString())
            } catch (_: Throwable) {
                throw OpException(Failure.MALFORMED, "payload is not JSON")
            }
            val v = (obj["v"] as? Long)?.toInt() ?: throw OpException(Failure.MALFORMED, "no version")
            val usr = obj["usr"] as? String ?: ""
            val op: MembershipOp = when {
                v in 1 until VERSION -> {
                    // A cert: genesis add, no prev. Its payload has no op/by/prev fields.
                    val dev = obj["dev"] as? String ?: ""
                    if (usr.isEmpty() || dev.isEmpty()) throw OpException(Failure.MALFORMED, "a binding is empty")
                    MembershipOp(
                        hash = hash(token), token = token, version = v, user = usr,
                        kind = Kind.ADD, device = dev,
                        deviceEnc = obj["denc"] as? String ?: "",
                        by = usr, prev = emptyList(), cosig = emptyList(),
                        issuedAt = obj["iat"] as? Long ?: 0L,
                        expiresAt = obj["exp"] as? Long ?: 0L,
                    )
                }
                v == VERSION -> {
                    val kindWire = obj["op"] as? String ?: ""
                    val prevRaw = obj["prev"]
                    val prev = when (prevRaw) {
                        null, is MiniJson.Null -> emptyList()
                        is List<*> -> prevRaw.map { it as? String ?: throw OpException(Failure.MALFORMED, "prev is not a string list") }
                        else -> throw OpException(Failure.MALFORMED, "prev is not a list")
                    }
                    val cosigRaw = obj["cosig"]
                    val cosig = when (cosigRaw) {
                        null, is MiniJson.Null -> emptyList()
                        is List<*> -> cosigRaw.map { e ->
                            val m = e as? Map<*, *> ?: throw OpException(Failure.MALFORMED, "cosig entry is not an object")
                            Cosig(m["by"] as? String ?: "", m["sig"] as? String ?: "")
                        }
                        else -> throw OpException(Failure.MALFORMED, "cosig is not a list")
                    }
                    MembershipOp(
                        hash = hash(token), token = token, version = v, user = usr,
                        kind = Kind.fromWire(kindWire) ?: throw OpException(Failure.MALFORMED, "op \"$kindWire\""),
                        device = obj["dev"] as? String ?: "",
                        deviceEnc = obj["denc"] as? String ?: "",
                        by = obj["by"] as? String ?: "",
                        prev = normalisePrev(prev),
                        cosig = cosig,
                        issuedAt = obj["iat"] as? Long ?: 0L,
                        expiresAt = obj["exp"] as? Long ?: 0L,
                    )
                }
                else -> throw OpException(Failure.MALFORMED, "unknown op version $v")
            }
            op.validate()
            // The signature is checked under `by` — the claimed signer. That `by` is a
            // member (or genesis) is Evaluate's job; here we only establish that the
            // holder of `by` signed these bytes.
            val byPub = try { KeyRef.parse(op.by) } catch (_: IllegalArgumentException) { null }
            if (byPub == null || byPub.alg != Labels.ALG_ED25519 || byPub.bytes.size != 32) {
                throw OpException(Failure.MALFORMED, "by: unreadable key")
            }
            val ok = try { verifier.verify(byPub.bytes, body, sig) } catch (_: Throwable) { false }
            if (!ok) throw OpException(Failure.BAD_SIGNATURE, "membership op signature does not verify")
            return op
        }

        /**
         * The identity an op CLAIMS, without verifying it — the hint an RP uses to pick
         * which pinned genesis key to evaluate under (voidbind-go `enrolment.OpUser`).
         * Accepts every version [verify] accepts; throws [OpException] otherwise.
         */
        fun user(rawToken: String): String {
            val token = rawToken.trim()
            val dot = token.indexOf('.')
            if (dot < 0) throw OpException(Failure.MALFORMED, "malformed membership op")
            val body = decodeOrNull(token.substring(0, dot)) ?: throw OpException(Failure.MALFORMED, "payload is not base64url")
            val obj = try { MiniJson.parseObject(body.decodeToString()) } catch (_: Throwable) {
                throw OpException(Failure.MALFORMED, "payload is not JSON")
            }
            val v = (obj["v"] as? Long)?.toInt() ?: 0
            val usr = obj["usr"] as? String ?: ""
            if (v < 1 || v > VERSION || usr.isEmpty()) throw OpException(Failure.MALFORMED, "malformed membership op")
            return usr
        }

        /** Sort + de-duplicate a prev list so equal head sets sign to equal bytes. */
        fun normalisePrev(prev: List<String>): List<String> =
            prev.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

        private fun decodeOrNull(s: String): ByteArray? = try {
            if (s.contains('=') || s.contains('+') || s.contains('/')) null else Base64Url.decode(s)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The structural check shared by every version once parsed: bindings present,
     * kinds sane, an add has a window that advances, a remove has none, and genesis
     * is never a device. Mirrors Go's `Op.validate`.
     */
    private fun validate() {
        if (user.isEmpty() || device.isEmpty() || by.isEmpty()) throw OpException(Failure.MALFORMED, "a binding is empty")
        val usrRef = try { KeyRef.parse(user) } catch (e: IllegalArgumentException) {
            throw OpException(Failure.MALFORMED, "usr: ${e.message}")
        }
        if (usrRef.alg != Labels.ALG_ED25519 || usrRef.bytes.size != 32) throw OpException(Failure.MALFORMED, "usr: not an ed25519 key")
        if (device == user) throw OpException(Failure.GENESIS, "genesis cannot be added or removed")
        if (issuedAt <= 0) throw OpException(Failure.MALFORMED, "no issued-at")
        if (prev.size > MAX_PREV) throw OpException(Failure.MALFORMED, "${prev.size} prev, max $MAX_PREV")
        for (h in prev) {
            if (!h.startsWith(HASH_PREFIX) || h.length != HASH_PREFIX.length + 64) {
                throw OpException(Failure.MALFORMED, "prev \"$h\" is not an op hash")
            }
        }
        when (kind) {
            Kind.ADD -> if (expiresAt == 0L || expiresAt <= issuedAt) {
                throw OpException(Failure.MALFORMED, "an add needs an expiry after its issued-at")
            }
            Kind.REMOVE -> {
                if (expiresAt != 0L) throw OpException(Failure.MALFORMED, "a remove does not expire")
                if (deviceEnc.isNotEmpty()) throw OpException(Failure.MALFORMED, "a remove binds no encryption key")
            }
        }
        if (!genesis && prev.isEmpty()) throw OpException(Failure.NO_PREV, "a member-signed op must cite its heads")
    }
}
