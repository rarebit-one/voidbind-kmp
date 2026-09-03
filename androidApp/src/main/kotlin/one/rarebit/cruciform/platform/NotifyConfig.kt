package one.rarebit.cruciform.platform

/**
 * The **push/wake plane** base URL: its default and its validation — the notify
 * half of what [RelayConfig] is for pairing. Pure Kotlin (no Android types) so the
 * rules are unit-tested on the JVM; [NotifySettings] is the persisted half.
 *
 * The library appends `/v1/subscriptions` to whatever base it is handed
 * ([one.rarebit.voidbind.net.NotifyClient]), so this is the plane's mount point.
 *
 * The plane is a **wake channel, not a crypto path**: this phone registers a public
 * ntfy topic URL with it and is later woken by an opaque login tuple. No key
 * material, no challenge and no match number ever crosses it, so a plane reached
 * over LAN HTTP leaks nothing — the login is still pulled from the relying party
 * over its own transport and signed hardware-gated.
 */
object NotifyConfig {

    /**
     * The plane a fresh install registers its wake endpoint with. **`https://notify.thesim.family`
     * is the intended PUBLIC name once it is deployed**; today it does not resolve,
     * and shipping it as the default made push registration fail silently on every
     * install — the same "can't reach it" failure the pairing relay had before
     * [RelayConfig.DEFAULT_RELAY] moved to the LAN. Until the public name exists the
     * default is the plane on the Bartley Ridge LAN host (`voidbind-notify`, :2587,
     * reachable on the LAN and over the WireGuard client net); cleartext to that one
     * host is allowed by `res/xml/network_security_config.xml`.
     */
    const val DEFAULT_NOTIFY = "http://192.168.16.224:2587"

    /**
     * The same endpoint-base rules the relay field uses ([RelayConfig.validateBase]) —
     * an absolute http/https base with a host and no query/fragment/userinfo, trimmed
     * of trailing slashes so the client's `/v1/...` join cannot produce `base//v1`.
     */
    fun validate(input: String): RelayConfig.Validation =
        RelayConfig.validateBase(input, noun = "push plane", example = DEFAULT_NOTIFY)

    /** `validate(input)` as a nullable normalised URL, for callers that only need go/no-go. */
    fun normalizeOrNull(input: String): String? =
        (validate(input) as? RelayConfig.Validation.Valid)?.url
}
