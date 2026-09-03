package one.rarebit.cruciform.handoff

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log

/**
 * Who the app on the other end of a `cruciform://pair-joined` callback IS, as the
 * system reports it — its label and launcher icon, for the one-tap sheet ("Allow
 * *heyarr* on this phone to act as you?").
 *
 * The resolution is deliberately narrow and goes through the SAME `<queries>` package
 * visibility the "Send to <app>" button already needs: we resolve the RP apps that
 * advertise same-phone handoff (`RpPairLauncher.adverts`, ADR-0009) and keep the one
 * whose package matches the caller Android named. So an app can only be *labelled* here
 * if it is a self-advertised relying party AND it actually handles the pair scheme — an
 * unknown caller gets a generic label, never a borrowed one.
 *
 * None of this is a security check: the decision is made by
 * [SamePhonePairCallback.decide] against the relay, and an unidentified caller whose
 * key and SAS match is still a match. This only decides what the sheet says.
 */
data class RpAppIdentity(
    /** The human name for the sheet. */
    val label: String,
    /** The RP's pair scheme, used to address the `<scheme>://pair-done` return trip. */
    val scheme: String?,
    /** The calling package Android reported, when it did. */
    val packageName: String?,
    /** The app's icon, when it could be loaded. */
    val icon: Drawable? = null,
) {
    companion object {
        private const val TAG = "RpPairHandoff"

        /** What the sheet says when the caller could not be identified at all. */
        fun unknown(): RpAppIdentity = RpAppIdentity(label = "This app", scheme = null, packageName = null)

        /**
         * Identify [callerPackage] (from `Activity.referrer`) as a self-advertised
         * relying party (ADR-0009): match it against the discovered adverts and, when
         * found, take its advertised scheme + label. Falls back to the package's own
         * label, then to [unknown].
         */
        fun resolve(context: Context, callerPackage: String?): RpAppIdentity {
            if (callerPackage.isNullOrBlank()) return unknown()
            val pm = context.packageManager
            val advert = RpPairHandoff.advertForPackage(RpPairLauncher.adverts(context), callerPackage)
            val icon = runCatching { pm.getApplicationIcon(callerPackage) }.getOrNull()
            if (advert != null) {
                val scheme = RpPairHandoff.targetFrom(advert)?.scheme
                val label = advert.label?.trim()?.takeIf { it.isNotEmpty() }
                    ?: pm.appLabelOrNull(callerPackage)
                return RpAppIdentity(label ?: "This app", scheme, callerPackage, icon)
            }
            return RpAppIdentity(pm.appLabelOrNull(callerPackage) ?: "This app", scheme = null, packageName = callerPackage, icon = icon)
        }

        private fun android.content.pm.PackageManager.appLabelOrNull(pkg: String): String? =
            runCatching { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() }.getOrElse {
                Log.d(TAG, "no label for $pkg (${it.javaClass.simpleName})")
                null
            }

        /**
         * Launch the RP's `<scheme>://pair-done?session=…` landing so the human ends up
         * back where they started, enrolled. Bare — the session id only; the admission
         * itself reached the RP sealed, over the relay. Returns false when nothing took it.
         */
        fun openDone(context: Context, scheme: String?, session: String): Boolean {
            val uri = runCatching { SamePhonePairCallback.doneUri(scheme ?: return false, session) }.getOrNull() ?: return false
            return try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                // ActivityNotFound / SecurityException: the RP is enrolled either way, it
                // simply does not get foregrounded. Never a reason to fail the pairing.
                Log.w(TAG, "no handler for $uri (${e.javaClass.simpleName})")
                false
            }
        }
    }
}

/**
 * One same-phone one-tap report as the Activity received it (ADR-0008): what the RP
 * said, who Android says said it, and a [seq] so two identical callbacks are distinct
 * and the second one re-fires the flow.
 */
data class SamePhoneJoin(
    val report: SamePhonePairCallback.Joined,
    val rp: RpAppIdentity,
    val seq: Int,
)
