package one.rarebit.cruciform

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import one.rarebit.cruciform.domain.DeviceVoidbindEngine
import one.rarebit.cruciform.domain.PreviewVoidbindEngine
import one.rarebit.cruciform.domain.VoidbindEngine
import one.rarebit.cruciform.handoff.Handoff
import one.rarebit.cruciform.handoff.HandoffRouter
import one.rarebit.cruciform.platform.AndroidBiometricAuthenticator
import one.rarebit.cruciform.platform.ApprovalPolicyStore
import one.rarebit.cruciform.platform.IdentityStore
import one.rarebit.cruciform.platform.OkHttpTransport
import one.rarebit.cruciform.platform.RelaySettings
import one.rarebit.cruciform.platform.push.PushEndpointStore
import one.rarebit.cruciform.platform.push.UnifiedPushReceiver
import one.rarebit.cruciform.ui.nav.CruciformNavHost
import one.rarebit.cruciform.ui.theme.CruciformTheme

/**
 * A [FragmentActivity] because `BiometricPrompt` binds to one. Chooses the engine:
 * the real [DeviceVoidbindEngine] (hardware key + coordinators over the network,
 * biometric-gated) or the [PreviewVoidbindEngine] (in-memory mockup data). The
 * preview engine is the default so the UI is reviewable without hardware; the real
 * one is selected at BUILD time with `./gradlew -PdeviceEngine=true …`
 * ([BuildConfig.USE_DEVICE_ENGINE]) — no source edit.
 *
 * Two ways the activity is woken into a login from outside its own UI, both hoisted
 * into one [handoff] the nav graph consumes (updated on [onNewIntent] too):
 *
 * - a **push wake** ([UnifiedPushReceiver]) with the opaque tuple in
 *   [UnifiedPushReceiver.EXTRA_LOGIN_TUPLE] — the app stays open afterwards;
 * - a **same-device deep link** (`ACTION_VIEW voidbind:login?…` / `voidbind:pair?…`)
 *   from a relying-party app on this phone — the identical approval flow, and then
 *   [finishHandoff] returns to the caller (finishing this activity, and launching the
 *   RP's optional private-scheme `callback` bare after a successful approval). The
 *   activity is `singleTask` so a warm app receives the link via [onNewIntent].
 */
class MainActivity : FragmentActivity() {

    private var handoff by mutableStateOf<Handoff?>(null)
    private var handoffSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A cold start from a deep link / push: route the launching intent. On a
        // recreation (rotation) the intent is the same one, but the handoff state is
        // fresh — re-routing it re-fires the approval, which is the right thing (the
        // flow state it held was ephemeral).
        handoff = routeIntent(intent)
        setContent {
            CruciformTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val engine: VoidbindEngine = remember {
                        if (BuildConfig.USE_DEVICE_ENGINE) {
                            DeviceVoidbindEngine(
                                store = IdentityStore(applicationContext),
                                policyStore = ApprovalPolicyStore(applicationContext),
                                transport = OkHttpTransport(),
                                biometric = AndroidBiometricAuthenticator(this@MainActivity),
                                // Read at invite time, not captured here: Settings → "Pairing
                                // relay" applies to the next "Add a device" without a restart.
                                relay = RelaySettings(applicationContext)::current,
                            )
                        } else {
                            PreviewVoidbindEngine()
                        }
                    }
                    // On open, (re-)register the current UnifiedPush wake endpoint with the
                    // notify plane, cert-authenticated. Best-effort: a failure just means no
                    // background wake, and scanned QR login still works.
                    LaunchedEffect(engine) {
                        PushEndpointStore(applicationContext).current()?.let { engine.registerForPush(it) }
                    }
                    val vm: AppViewModel = viewModel { AppViewModel(engine) }
                    CruciformNavHost(vm, handoff = handoff, onHandoffFinished = ::finishHandoff)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A warm app woken by a fresh push or a fresh deep link: surface the new login.
        routeIntent(intent)?.let { handoff = it }
    }

    /**
     * The push extra wins if present (the receiver built it from a parsed ping); else a
     * `voidbind:` VIEW deep link. The URI is untrusted input from another app — the
     * router parses it as strictly as a scan and drops a malformed callback.
     */
    private fun routeIntent(intent: Intent?): Handoff? {
        intent ?: return null
        val seq = ++handoffSeq
        return HandoffRouter.fromPush(intent.getStringExtra(UnifiedPushReceiver.EXTRA_LOGIN_TUPLE), seq)
            ?: HandoffRouter.fromDeepLink(intent.action, intent.dataString, seq)
    }

    /**
     * The human decided (or the login could not be shown). For a deep-link handoff:
     * launch the RP's callback — bare, nothing appended — only after a SUCCESSFUL
     * approval, then finish so the caller's task resumes (it learns the outcome from
     * its own broker poll, never from us). A push wake stays in the app.
     */
    private fun finishHandoff(done: Handoff, approved: Boolean) {
        if (handoff?.seq == done.seq) handoff = null
        if (!done.returnsToCaller) return
        val callback = done.callback
        if (approved && callback != null) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(callback))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "callback has no handler; caller will resume by itself")
            } catch (e: SecurityException) {
                Log.w(TAG, "callback refused by the system; caller will resume by itself")
            }
        }
        finishAndRemoveTask()
    }

    private companion object {
        const val TAG = "VoidbindHandoff"
    }
}
