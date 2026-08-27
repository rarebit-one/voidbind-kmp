package one.rarebit.voidbind.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import one.rarebit.voidbind.app.AppViewModel
import one.rarebit.voidbind.app.domain.IdentityState
import one.rarebit.voidbind.app.domain.LoginRequest
import one.rarebit.voidbind.app.domain.PairInviteDisplay
import one.rarebit.voidbind.app.domain.PairSession
import one.rarebit.voidbind.app.domain.RecoveryBackup
import one.rarebit.voidbind.app.domain.ScannedCode
import one.rarebit.voidbind.app.ui.screens.HomeScreen
import one.rarebit.voidbind.app.ui.screens.LoginApprovalScreen
import one.rarebit.voidbind.app.ui.screens.OnboardingScreen
import one.rarebit.voidbind.app.ui.screens.PairConnectScreen
import one.rarebit.voidbind.app.ui.screens.PairVerifyScreen
import one.rarebit.voidbind.app.ui.screens.RecoveryBackupScreen
import one.rarebit.voidbind.app.ui.screens.RestoreScreen
import one.rarebit.voidbind.app.ui.screens.ScanScreen
import one.rarebit.voidbind.app.ui.screens.SettingsScreen
import one.rarebit.voidbind.app.ui.theme.VbColors

/** Navigation routes. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val CREATE = "create"
    const val RESTORE = "restore"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val SCAN = "scan"
    const val LOGIN = "login"
    const val PAIR_CONNECT = "pair_connect"
    const val PAIR_VERIFY = "pair_verify"
    const val RECOVERY = "recovery"
}

@Composable
fun VoidbindNavHost(viewModel: AppViewModel) {
    val nav = rememberNavController()
    val engine = viewModel.engine
    val identityState by viewModel.identity.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // Ephemeral flow state carried between destinations (nav args are strings; these
    // are the already-fetched objects a pushed screen renders). Lost on process death,
    // where the destination falls back to the home graph.
    var loginRequest by remember { mutableStateOf<LoginRequest?>(null) }
    var loginCode by remember { mutableStateOf<ScannedCode.WebLogin?>(null) }
    var pairSession by remember { mutableStateOf<PairSession?>(null) }
    var pairInvite by remember { mutableStateOf<PairInviteDisplay?>(null) }
    var revealBackup by remember { mutableStateOf<RecoveryBackup?>(null) }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBottomBar = route == Routes.HOME || route == Routes.SETTINGS

    val start = if (identityState is IdentityState.Active) Routes.HOME else Routes.ONBOARDING

    fun goHome() = nav.navigate(Routes.HOME) {
        popUpTo(nav.graph.id) { inclusive = true }
    }

    Scaffold(
        containerColor = VbColors.Background,
        bottomBar = {
            if (showBottomBar) {
                VoidbindBottomBar(
                    currentRoute = route,
                    onHome = { if (route != Routes.HOME) nav.navigate(Routes.HOME) { launchSingleTop = true } },
                    onScan = { nav.navigate(Routes.SCAN) },
                    onSettings = { if (route != Routes.SETTINGS) nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onCreate = { nav.navigate(Routes.CREATE) },
                    onRestore = { nav.navigate(Routes.RESTORE) },
                    onAddDevice = { nav.navigate(Routes.SCAN) },
                )
            }

            composable(Routes.CREATE) {
                var backup by remember { mutableStateOf<RecoveryBackup?>(null) }
                LaunchedEffect(Unit) { backup = engine.createIdentity() }
                val b = backup
                if (b == null) {
                    Loading()
                } else {
                    RecoveryBackupScreen(
                        backup = b,
                        onBack = { nav.popBackStack() },
                        onSaved = { goHome() },
                        stepLabel = "BACKUP REQUIRED",
                    )
                }
            }

            composable(Routes.RESTORE) {
                RestoreScreen(
                    onBack = { nav.popBackStack() },
                    onRestore = { engine.restoreIdentity(it) },
                    onDone = { goHome() },
                )
            }

            composable(Routes.HOME) {
                val active = identityState as? IdentityState.Active
                if (active == null) {
                    LaunchedEffect(Unit) { nav.navigate(Routes.ONBOARDING) { popUpTo(nav.graph.id) { inclusive = true } } }
                    Loading()
                } else {
                    HomeScreen(
                        identity = active.identity,
                        device = active.device,
                        trustedSites = active.trustedSites,
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                        onCopyIdentity = { clipboard.setText(AnnotatedString(active.identity.fullKey)) },
                        onDevice = {
                            scope.launch {
                                pairInvite = engine.startPairInvite()
                                nav.navigate(Routes.PAIR_CONNECT)
                            }
                        },
                        onSite = { /* site detail — later */ },
                    )
                }
            }

            composable(Routes.SETTINGS) {
                val active = identityState as? IdentityState.Active
                if (active != null) {
                    SettingsScreen(
                        state = active,
                        onRename = { /* rename dialog — later */ },
                        onToggleBiometric = { scope.launch { engine.setBiometricApproval(it) } },
                        onRevoke = { site -> scope.launch { engine.revokeSite(site.id) } },
                        onManageSites = { /* full list — later */ },
                        onRecoveryBackup = {
                            scope.launch {
                                revealBackup = engine.revealRecoverySecret()
                                nav.navigate(Routes.RECOVERY)
                            }
                        },
                        onAbout = { },
                        onSecurity = { },
                        onLicenses = { },
                    )
                }
            }

            composable(Routes.SCAN) {
                ScanScreen(
                    onClose = { nav.popBackStack() },
                    onEnterManually = { /* manual entry sheet — later */ },
                    onCode = { raw ->
                        when (val c = engine.parseScanned(raw)) {
                            is ScannedCode.WebLogin -> scope.launch {
                                loginCode = c
                                loginRequest = engine.fetchLoginRequest(c)
                                nav.navigate(Routes.LOGIN) { popUpTo(Routes.SCAN) { inclusive = true } }
                            }
                            is ScannedCode.PairInvite -> scope.launch {
                                pairSession = engine.joinPairInvite(c)
                                nav.navigate(Routes.PAIR_VERIFY) { popUpTo(Routes.SCAN) { inclusive = true } }
                            }
                            is ScannedCode.Unknown -> { /* not a Voidbind code — surfaced later */ }
                        }
                    },
                )
            }

            composable(Routes.LOGIN) {
                val req = loginRequest
                if (req == null) {
                    LaunchedEffect(Unit) { goHome() }
                } else {
                    LoginApprovalScreen(
                        request = req,
                        onDeny = { goHome() },
                        onApprove = {
                            scope.launch {
                                loginCode?.let { engine.approveLogin(it) }
                                goHome()
                            }
                        },
                    )
                }
            }

            composable(Routes.PAIR_CONNECT) {
                val inv = pairInvite
                if (inv == null) {
                    LaunchedEffect(Unit) { goHome() }
                } else {
                    PairConnectScreen(
                        invite = inv,
                        onBack = { nav.popBackStack() },
                        onScanInstead = { nav.navigate(Routes.SCAN) },
                    )
                }
            }

            composable(Routes.PAIR_VERIFY) {
                val ses = pairSession
                if (ses == null) {
                    LaunchedEffect(Unit) { goHome() }
                } else {
                    PairVerifyScreen(
                        session = ses,
                        onCancel = { goHome() },
                        onConfirm = {
                            scope.launch {
                                engine.confirmPairing()
                                goHome()
                            }
                        },
                    )
                }
            }

            composable(Routes.RECOVERY) {
                val b = revealBackup
                if (b == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    RecoveryBackupScreen(
                        backup = b,
                        onBack = { nav.popBackStack() },
                        onSaved = { nav.popBackStack() },
                        stepLabel = "RECOVERY SECRET",
                    )
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = VbColors.Mint)
    }
}
