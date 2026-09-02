package one.rarebit.cruciform.platform

import java.net.URI
import java.net.URISyntaxException

/**
 * The pairing-relay base URL: its default and its validation. Pure Kotlin (no Android
 * types) so the rules are unit-tested on the JVM; [RelaySettings] is the persisted
 * half. The library appends `/v1/sessions…` to whatever base it is handed
 * ([one.rarebit.voidbind.net.RelayClient]), so the base is the mount point of a
 * relay, e.g. `http://192.168.16.224:7777/pair` (the heyarr node's `RelayPrefix`).
 */
object RelayConfig {

    /**
     * The relay a fresh install pairs through. **`https://relay.thesim.family` is the
     * intended PUBLIC relay once it is deployed**; today it does not resolve, and
     * shipping it as the default is exactly the "Add a device → can't reach the
     * relay" failure this setting exists to fix. Until then the default is the relay
     * the heyarr node mounts on the Bartley Ridge LAN (`/pair` on :7777) — cleartext
     * to that one host is allowed by `res/xml/network_security_config.xml`. The
     * relay only ever carries the encrypted pairing transcript, so a LAN-HTTP relay
     * leaks nothing (the SAS compare is what authenticates the pairing).
     */
    const val DEFAULT_RELAY = "http://192.168.16.224:7777/pair"

    /** What [validate] decided about a typed URL. */
    sealed interface Validation {
        /** [url] is the normalised base to persist (trimmed, no trailing slash). */
        data class Valid(val url: String) : Validation

        /** Human-readable reason, shown inline under the field. */
        data class Invalid(val reason: String) : Validation
    }

    /**
     * Accept an absolute `http`/`https` URL with a host and no query/fragment/userinfo;
     * normalise by trimming whitespace and trailing slashes (the relay client joins
     * with its own `/v1/...`, and a `base//v1` would 404). Blank input is invalid —
     * "use the default" is [RelaySettings.reset], not an empty string.
     */
    fun validate(input: String): Validation {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Validation.Invalid("Enter the relay URL, or reset to the default.")
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return Validation.Invalid("Not a valid URL.")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Validation.Invalid("The URL must start with http:// or https://.")
        }
        if (uri.host.isNullOrBlank() || uri.rawAuthority.isNullOrBlank()) {
            return Validation.Invalid("The URL needs a host, like http://192.168.16.224:7777/pair.")
        }
        if (uri.rawUserInfo != null) return Validation.Invalid("The URL can't carry credentials.")
        if (uri.rawQuery != null || uri.rawFragment != null) {
            return Validation.Invalid("Enter just the relay base — no ?query or #fragment.")
        }
        return Validation.Valid(trimmed.trimEnd('/'))
    }

    /** `validate(input)` as a nullable normalised URL, for callers that only need go/no-go. */
    fun normalizeOrNull(input: String): String? = (validate(input) as? Validation.Valid)?.url
}
