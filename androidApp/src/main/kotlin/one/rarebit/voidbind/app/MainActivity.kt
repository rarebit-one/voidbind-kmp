package one.rarebit.voidbind.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import one.rarebit.voidbind.app.domain.PreviewVoidbindEngine
import one.rarebit.voidbind.app.domain.VoidbindEngine
import one.rarebit.voidbind.app.ui.nav.VoidbindNavHost
import one.rarebit.voidbind.app.ui.theme.VoidbindTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoidbindTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // Until the DeviceVoidbindEngine (hardware key + coordinators) is
                    // wired, the app runs on the preview engine so the whole UI is
                    // reviewable against the mockups. Swapping the backend is one line.
                    val engine: VoidbindEngine = remember { PreviewVoidbindEngine() }
                    val vm: AppViewModel = viewModel { AppViewModel(engine) }
                    VoidbindNavHost(vm)
                }
            }
        }
    }
}
