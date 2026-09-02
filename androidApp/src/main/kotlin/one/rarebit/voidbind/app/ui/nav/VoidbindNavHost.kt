package one.rarebit.voidbind.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import one.rarebit.voidbind.app.domain.LoginRequestResult
import one.rarebit.voidbind.app.domain.PairInviteDisplay
import one.rarebit.voidbind.app.domain.PairSession
import one.rarebit.voidbind.app.domain.RecoveryBackup
import one.rarebit.voidbind.app.domain.ScannedCode
import one.rarebit.voidbind.app.handoff.Handoff
import one.rarebit.voidbind.app.domain.SitePolicyView
import one.rarebit.voidbind.app.ui.screens.ApprovalActivityScreen
import one.rarebit.voidbind.app.ui.screens.HomeScreen
import one.rarebit.voidbind.app.ui.screens.LoginApprovalScreen
import one.rarebit.voidbind.app.ui.screens.NumberMatchApprovalScreen
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
    const val ACTIVITY = "activity"
}

/**
 * A dismissible login-error to show as a dialog. [expired] flags a stale sign-in code (a 404/410
 * on the challenge fetch) so the dialog can title it "Expired" and say to scan a fresh QR, rather
 * than the generic "Sign-in unavailable" used for an unreachable or refusing RP.
 */
private data class LoginErrorState(val message: String, val expired: Boolean = false)

/**
 * [handoff] is a login/pairing the activity was woken into from outside (a push ping
 * or another app's `voidbind:` deep link); the graph runs the SAME approval flow a
 * scan does and reports the decision through [onHandoffFinished] — which, for a
 * deep link, finishes the activity so the calling app resumes.
 */
@Composable
fun VoidbindNavHost(
    viewModel: AppViewModel,
    handoff: Handoff? = null,
    onHandoffFinished: (Handoff, approved: Boolean) -> Unit = { _, _ -> },
) {
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
    // The RP's per-site approval policy, fetched when the approval sheet opens.
    var loginPolicy by remember { mutableStateOf<SitePolicyView?>(null) }
    var approvalActivity by remember { mutableStateOf<List<one.rarebit.voidbind.app.domain.ApprovalActivity>>(emptyList()) }
    // A failed login-challenge fetch (unreachable/misconfigured RP, TLS, timeout, non-2xx,
    // cleartext-blocked) surfaces here as a dismissible error instead of crashing the app.
    var loginError by remember { mutableStateOf<LoginErrorState?>(null) }
    var pairSession by remember { mutableStateOf<PairSession?>(null) }
    var pairInvite by remember { mutableStateOf<PairInviteDisplay?>(null) }
    var revealBackup by remember { mutableStateOf<RecoveryBackup?>(null) }
    // The handoff whose approval/pairing is currently on screen; its decision is routed
    // to onHandoffFinished instead of plain goHome (a deep-link caller is waiting).
    var activeHandoff by remember { mutableStateOf<Handoff?>(null) }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBottomBar = route == Routes.HOME || route == Routes.SETTINGS

    // The start destination MUST be stable for the NavHost's lifetime. Compose keys the
    // nav graph on `startDestination`, so recomputing it from live identity state — which
    // flips to Active mid create-flow (createIdentity provisions the identity before the
    // user acknowledges the recovery secret) — rebuilds the graph and resets the user to
    // Home, skipping the recovery-secret screen entirely. Latch it once from the first
    // resolved (non-Loading) state; show a loader until then. rememberSaveable so a
    // config change mid create-flow doesn't re-derive it to Home and yank the backstack.
    var latchedStart by rememberSaveable { mutableStateOf<String?>(null) }
    val currentIdentity = identityState
    if (latchedStart == null && currentIdentity !is IdentityState.Loading) {
        latchedStart = if (currentIdentity is IdentityState.Active) Routes.HOME else Routes.ONBOARDING
    }
    val start = latchedStart
    if (start == null) {
        Loading()
        return
    }

    fun goHome() = nav.navigate(Routes.HOME) {
        popUpTo(nav.graph.id) { inclusive = true }
    }

    /**
     * Route a decision on the LOGIN / PAIR_VERIFY screen: a deep-link handoff returns
     * to the calling app (the activity finishes — no navigation here); a push wake or
     * an in-app scan goes Home.
     */
    fun decided(approved: Boolean) {
        val h = activeHandoff
        activeHandoff = null
        if (h != null) onHandoffFinished(h, approved)
        if (h == null || !h.returnsToCaller) goHome()
    }

    // A wake from outside — a push ping, or another app's `voidbind:` deep link (the
    // same-device handoff). The tuple is the bare QR string, so this is EXACTLY the scan
    // path: fetch the request from the RP (the origin is shown, nothing auto-approves)
    // and open the same LOGIN destination, so the number-match (v2) grid vs.
    // plain-approve branch is shared. A pair invite joins as the new device → VERIFY.
    LaunchedEffect(handoff, start) {
        val h = handoff ?: return@LaunchedEffect
        activeHandoff = h
        when (val code = engine.parseScanned(h.tuple)) {
            is ScannedCode.WebLogin -> {
                loginCode = code
                // fetchLoginRequest never throws for a fetch failure; the runCatching is a final
                // guard so no unexpected throw in this effect can become a main-thread FATAL.
                when (val result = runCatching { engine.fetchLoginRequest(code) }.getOrNull()) {
                    is LoginRequestResult.Ready -> {
                        loginRequest = result.request
                        nav.navigate(Routes.LOGIN)
                    }
                    is LoginRequestResult.Failed -> loginError = LoginErrorState(result.message, result.expired)
                    null -> loginError = LoginErrorState("Couldn't reach the site.")
                }
            }
            is ScannedCode.PairInvite -> {
                when (val session = runCatching { engine.joinPairInvite(code) }.getOrNull()) {
                    null -> loginError = LoginErrorState("Couldn't join the pairing invite.")
                    else -> {
                        pairSession = session
                        nav.navigate(Routes.PAIR_VERIFY)
                    }
                }
            }
            is ScannedCode.Unknown -> loginError = LoginErrorState("Not a Voidbind code.")
        }
    }

    // A login-fetch failure (couldn't reach / site refused / stale code) — a dismissible dialog,
    // not a crash. A stale sign-in code (404/410) is titled "Expired" and reads as "scan a fresh
    // QR"; everything else keeps the generic "Sign-in unavailable".
    loginError?.let { error ->
        // Dismissing an error raised during a deep-link handoff (the fetch/join failed, or
        // the approval itself did) returns to the caller, not approved; an in-app error
        // just closes.
        val dismiss = {
            loginError = null
            val h = activeHandoff
            if (h != null && h.returnsToCaller) decided(false)
        }
        AlertDialog(
            onDismissRequest = dismiss,
            confirmButton = { TextButton(onClick = dismiss) { Text("OK") } },
            title = { Text(if (error.expired) "Expired" else "Sign-in unavailable") },
            text = { Text(error.message) },
        )
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
                        onApprovalActivity = {
                            scope.launch {
                                approvalActivity = engine.approvalActivity()
                                nav.navigate(Routes.ACTIVITY)
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
                                // fetchLoginRequest resolves a failed challenge fetch to Failed
                                // instead of throwing; the runCatching is a final guard so no
                                // unexpected throw can escape this coroutine as a FATAL.
                                when (val result = runCatching { engine.fetchLoginRequest(c) }.getOrNull()) {
                                    is LoginRequestResult.Ready -> {
                                        loginRequest = result.request
                                        nav.navigate(Routes.LOGIN) { popUpTo(Routes.SCAN) { inclusive = true } }
                                    }
                                    is LoginRequestResult.Failed -> {
                                        loginError = LoginErrorState(result.message, result.expired)
                                        nav.popBackStack()
                                    }
                                    null -> {
                                        loginError = LoginErrorState("Couldn't reach the site.")
                                        nav.popBackStack()
                                    }
                                }
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
                when {
                    req == null -> LaunchedEffect(Unit) { goHome() }
                    // A push-woken, number-matching login: show the candidate grid and
                    // approve by the tapped number (v2). A decoy tap is refused by the RP.
                    req.isNumberMatch -> NumberMatchApprovalScreen(
                        request = req,
                        onDeny = {
                            scope.launch {
                                runCatching { engine.denyLogin() }
                                decided(false)
                            }
                        },
                        onApprove = { chosen ->
                            scope.launch {
                                // A refused/failed approval (RP rejects, network drops) is caught so
                                // it surfaces as an error instead of an uncaught main-thread FATAL.
                                val ok = runCatching { loginCode?.let { engine.approveNumberMatch(it, chosen) } }
                                    .onFailure { loginError = LoginErrorState("Couldn't complete the sign-in.") }
                                    .isSuccess
                                // A failed approval during a deep-link handoff keeps the error on
                                // screen; dismissing it returns to the caller.
                                if (ok || activeHandoff?.returnsToCaller != true) decided(ok)
                            }
                        },
                    )
                    else -> {
                        // Fetch this RP's current approval policy when the sheet opens so it can
                        // show trusted/always-ask and offer the inline pin toggle.
                        LaunchedEffect(req.domain) { loginPolicy = engine.sitePolicy(req.domain) }
                        LoginApprovalScreen(
                            request = req,
                            onDeny = {
                                scope.launch {
                                    runCatching { engine.denyLogin() }
                                    decided(false)
                                }
                            },
                            onApprove = {
                                scope.launch {
                                    val ok = runCatching { loginCode?.let { engine.approveLogin(it) } }
                                        .onFailure { loginError = LoginErrorState("Couldn't complete the sign-in.") }
                                        .isSuccess
                                    if (ok || activeHandoff?.returnsToCaller != true) decided(ok)
                                }
                            },
                            policy = loginPolicy,
                            onSetAlwaysAsk = { alwaysAsk ->
                                scope.launch {
                                    engine.setAlwaysAsk(req.domain, alwaysAsk)
                                    loginPolicy = engine.sitePolicy(req.domain)
                                }
                            },
                        )
                    }
                }
            }

            composable(Routes.PAIR_CONNECT) {
                val inv = pairInvite
                if (inv == null) {
                    LaunchedEffect(Unit) { goHome() }
                } else {
                    // The initiator shows the invite AND, concurrently, blocks on the
                    // relay handshake (its commit must be posted for the new device's
                    // fetch to complete). When the new device joins and the SAS is
                    // derived, advance to VERIFY; a timeout/expiry returns home. The
                    // effect is cancelled if the user leaves this screen first.
                    LaunchedEffect(inv) {
                        runCatching { engine.awaitPairHandshake() }
                            .onSuccess { session ->
                                pairSession = session
                                nav.navigate(Routes.PAIR_VERIFY) {
                                    popUpTo(Routes.PAIR_CONNECT) { inclusive = true }
                                }
                            }
                            .onFailure { if (route == Routes.PAIR_CONNECT) goHome() }
                    }
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
                        onCancel = { decided(false) },
                        onConfirm = {
                            scope.launch {
                                val ok = runCatching { engine.confirmPairing() }
                                    .onFailure { loginError = LoginErrorState("Couldn't complete the pairing.") }
                                    .isSuccess
                                // A failed approval during a deep-link handoff keeps the error on
                                // screen; dismissing it returns to the caller.
                                if (ok || activeHandoff?.returnsToCaller != true) decided(ok)
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

            composable(Routes.ACTIVITY) {
                ApprovalActivityScreen(
                    activity = approvalActivity,
                    onBack = { nav.popBackStack() },
                )
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
