package one.rarebit.cruciform.pairing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import one.rarebit.cruciform.MainActivity
import one.rarebit.cruciform.R

/**
 * The foreground service that holds the process while an invite waits on the relay
 * for the new device (ADR-0007). It does no work itself — the [InviteCoordinator]'s
 * job in the ViewModel scope polls the relay — it only keeps Android from freezing or
 * killing the app once the user switches to the relying-party app to create its key,
 * and shows "Waiting for the new device to join…" so the wait is visible. Type
 * `dataSync` (the poll is network I/O); started while the app is in the foreground.
 * Stopped by [ServiceKeepAlive.end] the moment the new device joins, the wait fails,
 * or the human cancels.
 */
class PairInviteService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pairing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while an invite waits for the new device to join."
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Waiting for the new device to join…")
            .setContentText("Come back to Cruciform to compare the security code.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        // Android 14 requires the type here to match the manifest's foregroundServiceType.
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL = "pairing"
        const val NOTIFICATION_ID = 41
        private const val TAG = "PairInviteService"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, PairInviteService::class.java))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException and friends: the wait still runs
                // in the ViewModel scope; we just lose the process guarantee. Never crash.
                Log.w(TAG, "could not start foreground service: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PairInviteService::class.java)) }
        }
    }
}

/** [ProcessKeepAlive] backed by [PairInviteService]. */
class ServiceKeepAlive(private val context: Context) : ProcessKeepAlive {
    override fun begin() = PairInviteService.start(context.applicationContext)
    override fun end() = PairInviteService.stop(context.applicationContext)
}
