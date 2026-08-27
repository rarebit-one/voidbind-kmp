package one.rarebit.voidbind.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import one.rarebit.voidbind.app.domain.DeviceVoidbindEngine
import one.rarebit.voidbind.app.domain.PreviewVoidbindEngine
import one.rarebit.voidbind.app.domain.VoidbindEngine
import one.rarebit.voidbind.app.platform.AndroidBiometricAuthenticator
import one.rarebit.voidbind.app.platform.IdentityStore
import one.rarebit.voidbind.app.platform.OkHttpTransport
import one.rarebit.voidbind.app.ui.nav.VoidbindNavHost
import one.rarebit.voidbind.app.ui.theme.VoidbindTheme

/**
 * A [FragmentActivity] because `BiometricPrompt` binds to one. Chooses the engine:
 * the real [DeviceVoidbindEngine] (hardware key + coordinators over the network,
 * biometric-gated) or the [PreviewVoidbindEngine] (in-memory mockup data). The
 * preview engine is the default so the UI is reviewable without hardware; flip
 * [USE_DEVICE_ENGINE] to run the real thing on a physical StrongBox device.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoidbindTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val engine: VoidbindEngine = remember {
                        if (USE_DEVICE_ENGINE) {
                            DeviceVoidbindEngine(
                                store = IdentityStore(applicationContext),
                                transport = OkHttpTransport(),
                                biometric = AndroidBiometricAuthenticator(this@MainActivity),
                                relayBase = DeviceVoidbindEngine.DEFAULT_RELAY,
                            )
                        } else {
                            PreviewVoidbindEngine()
                        }
                    }
                    val vm: AppViewModel = viewModel { AppViewModel(engine) }
                    VoidbindNavHost(vm)
                }
            }
        }
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
