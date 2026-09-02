package one.rarebit.cruciform.platform.push

import android.content.Context

/**
 * Persists the current UnifiedPush wake **endpoint** (the ntfy topic URL a
 * distributor hands us via [UnifiedPushReceiver] `NEW_ENDPOINT`). The endpoint is a
 * public address, not a secret, so plain SharedPreferences is enough; the app reads
 * it on open and (re-)registers it with the notify plane, cert-authenticated, via
 * `VoidbindEngine.registerForPush`.
 */
class PushEndpointStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(endpoint: String) = prefs.edit().putString(KEY, endpoint).apply()

    fun current(): String? = prefs.getString(KEY, null)

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val FILE = "voidbind_push"
        const val KEY = "endpoint"
    }
}
