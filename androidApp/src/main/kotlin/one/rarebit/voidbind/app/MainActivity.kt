package one.rarebit.voidbind.app

import android.content.Intent
import android.os.Bundle
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
import one.rarebit.voidbind.app.domain.DeviceVoidbindEngine
import one.rarebit.voidbind.app.domain.PreviewVoidbindEngine
import one.rarebit.voidbind.app.domain.VoidbindEngine
import one.rarebit.voidbind.app.platform.AndroidBiometricAuthenticator
import one.rarebit.voidbind.app.platform.ApprovalPolicyStore
import one.rarebit.voidbind.app.platform.IdentityStore
import one.rarebit.voidbind.app.platform.OkHttpTransport
import one.rarebit.voidbind.app.platform.push.PushEndpointStore
import one.rarebit.voidbind.app.platform.push.UnifiedPushReceiver
import one.rarebit.voidbind.app.ui.nav.VoidbindNavHost
import one.rarebit.voidbind.app.ui.theme.VoidbindTheme

/**
 * A [FragmentActivity] because `BiometricPrompt` binds to one. Chooses the engine:
 * the real [DeviceVoidbindEngine] (hardware key + coordinators over the network,
 * biometric-gated) or the [PreviewVoidbindEngine] (in-memory mockup data). The
 * preview engine is the default so the UI is reviewable without hardware; flip
 * [USE_DEVICE_ENGINE] to run the real thing on a physical StrongBox device.
 *
 * A **push wake** ([UnifiedPushReceiver]) launches this activity with the opaque
 * login tuple as [UnifiedPushReceiver.EXTRA_LOGIN_TUPLE]; it is hoisted into
 * [wakeTuple] (updated on [onNewIntent] too) and handed to the nav graph, which
 * fetches the number-matching request and opens the approval.
 */
class MainActivity : FragmentActivity() {

    private var wakeTuple by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeTuple = intent?.getStringExtra(UnifiedPushReceiver.EXTRA_LOGIN_TUPLE)
        setContent {
            VoidbindTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val engine: VoidbindEngine = remember {
                        if (USE_DEVICE_ENGINE) {
                            DeviceVoidbindEngine(
                                store = IdentityStore(applicationContext),
                                policyStore = ApprovalPolicyStore(applicationContext),
                                transport = OkHttpTransport(),
                                biometric = AndroidBiometricAuthenticator(this@MainActivity),
                                relayBase = DeviceVoidbindEngine.DEFAULT_RELAY,
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
                    VoidbindNavHost(vm, wakeTuple = wakeTuple)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A warm app woken by a fresh push: surface the new login.
        intent.getStringExtra(UnifiedPushReceiver.EXTRA_LOGIN_TUPLE)?.let { wakeTuple = it }
    }

    private companion object {
        /**
         * The real hardware engine is not the default yet: its StrongBox + biometric
         * behaviour is device-tested, not verifiable on an emulator or in CI (see
         * docs/DEVICE-TESTING.md), and the reviewable UI should not depend on it.
         */
        const val USE_DEVICE_ENGINE = false
    }
}
