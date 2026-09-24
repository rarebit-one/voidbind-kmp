package one.rarebit.cruciform.platform

/**
 * A persisted endpoint base the user can override in Settings — the pairing relay
 * ([RelaySettings]) and the push plane ([NotifySettings]). A seam so the Settings
 * ViewModel is unit-testable without SharedPreferences.
 */
interface EndpointSetting {
    /** The configured base, or the build default when no override is stored. */
    fun current(): String

    /** True when no override is stored (the default is in effect). */
    fun isDefault(): Boolean

    /** Validate and persist; nothing is written on `Invalid`. */
    fun set(input: String): RelayConfig.Validation

    /** Drop the override. */
    fun reset()
}
