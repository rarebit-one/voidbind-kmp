package one.rarebit.cruciform.handoff

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * The Android side of [RpPairHandoff]: which RP apps on this phone advertise themselves
 * as same-phone handoff targets (ADR-0009 discovery), and firing the invite at one of
 * them (or at the system Sharesheet).
 */
object RpPairLauncher {

    private const val TAG = "RpPairHandoff"

    /**
     * Query the phone for RP apps that advertise same-phone handoff (ADR-0009): a VIEW
     * intent carrying [RpPairHandoff.CATEGORY_RP_HANDOFF] and no data, resolved with
     * `GET_META_DATA` so we can read each activity's [RpPairHandoff.META_PAIR_SCHEME].
     * Android 11+ package visibility needs the one generic `<queries>` entry for the
     * same category (in `AndroidManifest.xml`). The label is the activity's own
     * `android:label`; a missing scheme is left null and filtered out downstream.
     */
    fun adverts(context: Context): List<RpHandoffAdvert> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW).addCategory(RpPairHandoff.CATEGORY_RP_HANDOFF)
        val resolved = try {
            pm.queryIntentActivities(probe, PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            Log.w(TAG, "advert query failed (${e.javaClass.simpleName})")
            emptyList()
        }
        return resolved.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            val scheme = ai.metaData?.getString(RpPairHandoff.META_PAIR_SCHEME)
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull()
            // Diagnosable from logcat: `adb logcat -s RpPairHandoff`. A resolved activity
            // with a null scheme is missing (or misnamed) its META_PAIR_SCHEME meta-data.
            Log.d(TAG, "advert ${ai.packageName} label=$label scheme=$scheme")
            RpHandoffAdvert(packageName = ai.packageName, label = label, pairScheme = scheme)
        }
    }

    /**
     * The discovered "Send to `<app>` on this phone" targets — the [adverts] mapped and
     * validated by [RpPairHandoff.targetsFrom]. The Connect screen shows one button per
     * target and none otherwise.
     */
    fun resolvable(context: Context): List<RpPairTarget> =
        RpPairHandoff.targetsFrom(adverts(context))

    /**
     * Open [target] with the invite. `FLAG_ACTIVITY_NEW_TASK` so the RP lands in ITS OWN
     * task (a `singleTop` RP already running is resumed via `onNewIntent`) rather than
     * being stacked inside ours — the user comes back to this invite flow through recent
     * apps, exactly where they left it. Returns false if no activity took it.
     */
    fun sendTo(context: Context, target: RpPairTarget, inviteTuple: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(RpPairHandoff.uriFor(target, inviteTuple)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no activity for ${target.callbackBase}")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "system refused ${target.callbackBase}")
        false
    }

    /**
     * The Sharesheet fallback: the invite as `text/plain`, for an RP we do not know or
     * a user who wants to paste it. The invite is one-time and expires with the relay
     * session, and the SAS still closes a substituted invite, so sharing the tuple
     * leaks nothing a QR on screen does not.
     */
    fun share(context: Context, inviteTuple: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, inviteTuple)
            .putExtra(Intent.EXTRA_SUBJECT, "Cruciform pairing invite")
        try {
            context.startActivity(Intent.createChooser(send, "Share invite"))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no share target")
        }
    }
}
