package one.rarebit.cruciform.handoff

/**
 * The **reverse** same-device handoff (ADR-0006): this authenticator holds a pairing
 * invite it just minted (Settings → Devices → "Add a device") and the NEW device is a
 * relying-party app on the SAME phone — heyarr-mobile, All Thing — which cannot scan
 * our screen. ADR-0003 already lets an RP open us with a `voidbind:` link; this is
 * the mirror: we open the RP with its own **pair-callback** URI carrying the invite.
 *
 *     heyarr-mobile://pair?invite=<percent-encoded voidbind:pair?… tuple>
 *     allthing://pair?invite=<…>
 *
 * The scheme is the RP's, never `voidbind:` — that one is ours, and firing it would
 * only loop back into this app. The invite tuple travels verbatim (byte-identical to
 * the QR), so the RP feeds it to the same `Invite.decode` a scan would; nothing here
 * is a new wire format. The registry of known RP callbacks is small and app-local:
 * a button appears only for a target that actually resolves on the phone
 * ([RpPairLauncher.resolvable]) — unknown or absent apps get the Sharesheet fallback.
 *
 * Pure Kotlin (no android.* imports) so the URI shape is unit-tested on the JVM.
 */
data class RpPairTarget(
    /** The human name on the button: "Send to heyarr on this phone". */
    val appName: String,
    /** The RP's pair-callback base, `scheme://host` — the app registers a VIEW filter for it. */
    val callbackBase: String,
) {
    val scheme: String get() = callbackBase.substringBefore(':')
}

object RpPairHandoff {

    /** The query key the RP reads the invite from. */
    const val INVITE = "invite"

    /**
     * The relying parties that accept a pairing invite by deep link on the same phone.
     * Each must register `ACTION_VIEW` + `BROWSABLE` for its `callbackBase`; the
     * matching `<queries>` entries in `androidApp/AndroidManifest.xml` give us package
     * visibility to check that before showing a button.
     */
    val KNOWN: List<RpPairTarget> = listOf(
        RpPairTarget(appName = "heyarr", callbackBase = "heyarr-mobile://pair"),
        RpPairTarget(appName = "All Thing", callbackBase = "allthing://pair"),
    )

    /**
     * The known target whose scheme is [scheme], or null. Used on the return leg of the
     * same-phone one-tap path (ADR-0008): a `cruciform://pair-joined` callback names the
     * app by the package Android reports, and this maps that app's scheme back to the
     * registry row so the `<scheme>://pair-done` landing can be addressed.
     *
     * NOTE (#39): this registry is still a hard-coded whitelist, so a new relying party
     * needs a Cruciform release to appear here and on the return leg. #39 proposes RPs
     * self-advertising through a shared intent category, which would replace BOTH this
     * list and the per-scheme `<queries>` entries with one generic query; the one-tap
     * callback is deliberately built from the RESOLVED scheme, not a second list, so it
     * needs no change when that lands.
     */
    fun targetForScheme(scheme: String?): RpPairTarget? =
        scheme?.takeIf { it.isNotBlank() }?.let { s -> KNOWN.firstOrNull { it.scheme.equals(s, ignoreCase = true) } }

    /**
     * Build the URI that hands [inviteTuple] (the exact `voidbind:pair?…` string the QR
     * shows) to [target]. The tuple is percent-encoded as a single query value so its
     * own `?`/`&`/`=` survive; the RP decodes it back to the byte-identical invite.
     * Refuses anything that is not a pair invite — an RP must never receive a login
     * tuple through this door.
     */
    fun uriFor(target: RpPairTarget, inviteTuple: String): String {
        require(inviteTuple.startsWith("voidbind:pair?")) { "not a voidbind pairing invite" }
        return "${target.callbackBase}?$INVITE=${percentEncode(inviteTuple)}"
    }

    /**
     * RFC 3986 percent-encoding of a query VALUE: keep the unreserved set
     * `A-Za-z0-9-_.~`, encode every other byte (UTF-8) as uppercase `%XX`. Deliberately
     * not Go's `url.QueryEscape` (which writes a space as `+`) so the RP can decode it
     * with any standard percent-decoder without a `+` ambiguity.
     */
    fun percentEncode(s: String): String {
        val bytes = s.encodeToByteArray()
        val out = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                out.append(ch)
            } else {
                out.append('%').append(HEX[c ushr 4]).append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
