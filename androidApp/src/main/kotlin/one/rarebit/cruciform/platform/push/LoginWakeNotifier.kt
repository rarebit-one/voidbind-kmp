package one.rarebit.cruciform.platform.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import one.rarebit.cruciform.MainActivity

/**
 * Surfaces a push-delivered login as a **high-importance, full-screen-intent
 * notification** — the sanctioned way to bring UI up from a background push.
 *
 * [UnifiedPushReceiver] cannot just `startActivity`: a background broadcast receiver's
 * activity start is refused by Android's background-activity-launch rules (and the ntfy
 * distributor can only "raise" an app that has a foreground service, which Cruciform does
 * not at wake time). A notification is the exception: with a **full-screen intent** it
 * launches the approval directly on a locked/off screen, and shows as a heads-up the user
 * taps when the screen is on — exactly the incoming-approval UX. The tuple is opaque (the
 * same one a QR carries); tapping/opening pulls the real challenge from the RP over TLS.
 */
object LoginWakeNotifier {

    private const val CHANNEL = "login-wake"
    private const val NOTIFICATION_ID = 0x10_9114 // stable: a new login replaces the last

    fun notify(context: Context, loginTuple: String) {
        ensureChannel(context)

        val open = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(UnifiedPushReceiver.EXTRA_LOGIN_TUPLE, loginTuple)
        }
        val pi = PendingIntent.getActivity(
            context, NOTIFICATION_ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Approve a sign-in?")
            .setContentText("A site is asking to sign in as you. Tap to review.")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true) // launch directly on a locked/off screen
            .build()

        // POST_NOTIFICATIONS (Android 13+) may be ungranted; areNotificationsEnabled()
        // guards the post so a denied permission fails soft (no wake, QR login still works)
        // rather than throwing. The RP times the login out; nothing is stranded.
        val nm = NotificationManagerCompat.from(context)
        if (nm.areNotificationsEnabled()) {
            try {
                nm.notify(NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS revoked between the check and the post — best-effort.
            }
        }
    }

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sign-in approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A site is asking to sign in as you — approve or deny on this phone."
                setShowBadge(true)
            },
        )
    }
}
