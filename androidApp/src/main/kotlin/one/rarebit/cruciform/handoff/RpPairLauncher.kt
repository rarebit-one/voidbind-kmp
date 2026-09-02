package one.rarebit.cruciform.handoff

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * The Android side of [RpPairHandoff]: which known RP apps are installed on this
 * phone, and firing the invite at one of them (or at the system Sharesheet).
 */
object RpPairLauncher {

    private const val TAG = "RpPairHandoff"

    /**
     * The subset of [RpPairHandoff.KNOWN] some installed app will take, checked with
     * `resolveActivity` (Android 11+ package visibility needs the manifest `<queries>`
     * for each scheme). The Connect screen shows one button per hit and none otherwise.
     */
    fun resolvable(context: Context, targets: List<RpPairTarget> = RpPairHandoff.KNOWN): List<RpPairTarget> =
        targets.filter { t ->
            Intent(Intent.ACTION_VIEW, Uri.parse("${t.callbackBase}?${RpPairHandoff.INVITE}=probe"))
                .resolveActivity(context.packageManager) != null
        }

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
