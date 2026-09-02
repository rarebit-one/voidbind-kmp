package one.rarebit.cruciform.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import one.rarebit.cruciform.AppViewModel
import one.rarebit.cruciform.domain.EngineFailure
import one.rarebit.cruciform.domain.EngineResult
import one.rarebit.cruciform.domain.IdentityState
import one.rarebit.cruciform.domain.LoginRequest
import one.rarebit.cruciform.domain.LoginRequestResult
import one.rarebit.cruciform.domain.PairInviteDisplay
import one.rarebit.cruciform.domain.PairSession
import one.rarebit.cruciform.domain.RecoveryBackup
import one.rarebit.cruciform.domain.ScannedCode
import one.rarebit.cruciform.handoff.Handoff
import one.rarebit.cruciform.handoff.RpPairLauncher
import one.rarebit.cruciform.platform.RelaySettings
import one.rarebit.cruciform.domain.SitePolicyView
import one.rarebit.cruciform.ui.screens.ApprovalActivityScreen
import one.rarebit.cruciform.ui.screens.DevicesScreen
import one.rarebit.cruciform.ui.screens.HomeScreen
import one.rarebit.cruciform.ui.screens.LoginApprovalScreen
import one.rarebit.cruciform.ui.screens.NumberMatchApprovalScreen
import one.rarebit.cruciform.ui.screens.OnboardingScreen
import one.rarebit.cruciform.ui.screens.PairConnectScreen
import one.rarebit.cruciform.ui.screens.PairVerifyScreen
import one.rarebit.cruciform.ui.screens.RecoveryBackupScreen
import one.rarebit.cruciform.ui.screens.RestoreScreen
import one.rarebit.cruciform.ui.screens.ScanScreen
import one.rarebit.cruciform.ui.screens.SettingsScreen
import one.rarebit.cruciform.ui.theme.VbColors

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
    const val DEVICES = "devices"
}

/**
 * A dismissible login-error to show as a dialog. [expired] flags a stale sign-in code (a 404/410
 * on the challenge fetch) so the dialog can title it "Expired" and say to scan a fresh QR, rather
 * than the generic "Sign-in unavailable" used for an unreachable or refusing RP.
 */
private data class LoginErrorState(val message: String, val expired: Boolean = false)

/**
 * A pairing (or other engine) failure to show as a dismissible dialog. [retry], when
 * present, re-runs the SAME step (re-join the same invite, re-mint the invite,
 * re-confirm) — the engine returns these as values, so nothing here ever throws.
 * [relayUrl] is set when the failure is against THIS phone's configured pairing relay
 * (minting an invite — not joining someone else's, whose relay is in the invite):
 * the dialog then names that URL and offers "Change relay" → Settings.
 */
private data class EngineErrorState(
    val failure: EngineFailure,
    val retry: (() -> Unit)? = null,
    val relayUrl: String? = null,
)

/**
 * [handoff] is a login/pairing the activity was woken into from outside (a push ping
 * or another app's `voidbind:` deep link); the graph runs the SAME approval flow a
 * scan does and reports the decision through [onHandoffFinished] — which, for a
 * deep link, finishes the activity so the calling app resumes.
 */
@Composable
fun CruciformNavHost(
    viewModel: AppViewModel,
    handoff: Handoff? = null,
    onHandoffFinished: (Handoff, approved: Boolean) -> Unit = { _, _ -> },
) {
    val nav = rememberNavController()
    val engine = viewModel.engine
    val identityState by viewModel.identity.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val appContext = LocalContext.current.applicationContext
    // Settings → "Pairing relay": persisted; the engine reads it at invite time.
    val relaySettings = remember(appContext) { RelaySettings(appContext) }
    // Set by the error dialog's "Change relay": Settings focuses the relay field once.
    var focusRelay by remember { mutableStateOf(false) }

    // Ephemeral flow state carried between destinations (nav args are strings; these
    // are the already-fetched objects a pushed screen renders). Lost on process death,
    // where the destination falls back to the home graph.
    var loginRequest by remember { mutableStateOf<LoginRequest?>(null) }
    var loginCode by remember { mutableStateOf<ScannedCode.WebLogin?>(null) }
    // The RP's per-site approval policy, fetched when the approval sheet opens.
    var loginPolicy by remember { mutableStateOf<SitePolicyView?>(null) }
    var approvalActivity by remember { mutableStateOf<List<one.rarebit.cruciform.domain.ApprovalActivity>>(emptyList()) }
    // The identity's device set (Settings → Devices), evaluated from this device's replica.
    var memberDevices by remember { mutableStateOf<List<one.rarebit.cruciform.domain.MemberDevice>>(emptyList()) }
    // A failed login-challenge fetch (unreachable/misconfigured RP, TLS, timeout, non-2xx,
    // cleartext-blocked) surfaces here as a dismissible error instead of crashing the app.
    var loginError by remember { mutableStateOf<LoginErrorState?>(null) }
    // A failed pairing step (no route to the relay, relay refused, invite expired unjoined,
    // a handshake that did not verify, a cancelled biometric prompt) surfaces here as a
    // dismissible error WITH a Retry, instead of crashing the app. Every engine pairing
    // entry point returns an EngineResult; this is where a Failed lands.
    var engineError by remember { mutableStateOf<EngineErrorState?>(null) }
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

    /**
     * Join a pairing invite as the new device and go to VERIFY — the one path a scan, a
     * deep link and the error dialog's Retry all share, so Retry re-joins the SAME
     * invite. [beforeVerify] tailors the navigation (a scan pops the scanner first).
     */
    fun joinInvite(code: ScannedCode.PairInvite, beforeVerify: () -> Unit = {}) {
        scope.launch {
            // joinPairInvite never throws for a transport failure (it resolves to Failed on the
            // IO thread); the runCatching is a final guard for anything unexpected.
            val result = runCatching { engine.joinPairInvite(code) }.getOrElse { EngineResult.Failed(unexpected(it)) }
            when (result) {
                is EngineResult.Ready -> {
                    pairSession = result.value
                    beforeVerify()
                    nav.navigate(Routes.PAIR_VERIFY)
                }
                is EngineResult.Failed -> engineError = EngineErrorState(
                    result.failure,
                    retry = if (result.failure.retryable) ({ joinInvite(code, beforeVerify) }) else null,
                )
            }
        }
    }

    /** Evaluate the device set from this device's replica and open Settings → Devices. */
    fun openDevices() {
        scope.launch {
            memberDevices = runCatching { engine.devices() }.getOrDefault(emptyList())
            if (route != Routes.DEVICES) nav.navigate(Routes.DEVICES)
        }
    }

    /**
     * Mint a pairing invite as the existing device and show it; Retry re-mints. This is
     * the one step that dials THIS phone's configured relay, so a relay that can't be
     * reached (or refuses — a wrong mount path 404s) names the configured URL and
     * offers "Change relay".
     */
    fun startInvite() {
        scope.launch {
            val relayUrl = relaySettings.current()
            val result = runCatching { engine.startPairInvite() }.getOrElse { EngineResult.Failed(unexpected(it)) }
            when (result) {
                is EngineResult.Ready -> {
                    pairInvite = result.value
                    if (route != Routes.PAIR_CONNECT) nav.navigate(Routes.PAIR_CONNECT)
                }
                is EngineResult.Failed -> engineError = EngineErrorState(
                    result.failure,
                    retry = if (result.failure.retryable) ({ startInvite() }) else null,
                    relayUrl = relayUrl.takeIf {
                        result.failure.kind == EngineFailure.Kind.UNREACHABLE || result.failure.kind == EngineFailure.Kind.REJECTED
                    },
                )
            }
        }
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
            // The pairing deep link (#27) joins as the new device. Failure — the phone has
            // no route to the relay, most likely — is a dialog with Retry, never a crash.
            is ScannedCode.PairInvite -> joinInvite(code)
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

    // A pairing-step failure: titled by kind, with a Retry that re-runs the same step
    // when that can help (unreachable relay → once Wi-Fi/VPN is back; expired invite →
    // a fresh one). Cancelling during a deep-link handoff returns to the caller.
    engineError?.let { error ->
        val dismiss = {
            engineError = null
            val h = activeHandoff
            if (h != null && h.returnsToCaller) decided(false)
        }
        val retry = error.retry
        val relayUrl = error.relayUrl
        // "Change relay" → Settings with the relay field focused; the failed invite is
        // simply dropped (nothing was minted), and "Add a device" can be tried again.
        val changeRelay = {
            engineError = null
            focusRelay = true
            if (route != Routes.SETTINGS) nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
        }
        AlertDialog(
            onDismissRequest = dismiss,
            confirmButton = {
                if (retry != null) {
                    TextButton(onClick = { engineError = null; retry() }) { Text("Retry") }
                } else {
                    TextButton(onClick = dismiss) { Text("OK") }
                }
            },
            dismissButton = when {
                relayUrl != null -> ({
                    Row {
                        TextButton(onClick = changeRelay) { Text("Change relay") }
                        if (retry != null) TextButton(onClick = dismiss) { Text("Cancel") }
                    }
                })
                retry != null -> ({ TextButton(onClick = dismiss) { Text("Cancel") } })
                else -> null
            },
            title = { Text(titleFor(error.failure.kind)) },
            text = {
                Text(
                    if (relayUrl != null) "${error.failure.message}\n\nPairing relay: $relayUrl"
                    else error.failure.message,
                )
            },
        )
    }

    Scaffold(
        containerColor = VbColors.Background,
        bottomBar = {
            if (showBottomBar) {
                CruciformBottomBar(
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
                // Biometric-gated: a cancelled prompt or a keystore error must not escape the
                // effect as a crash — surface it and return to onboarding.
                LaunchedEffect(Unit) {
                    runCatching { engine.createIdentity() }
                        .onSuccess { backup = it }
                        .onFailure {
                            engineError = EngineErrorState(unexpected(it, "Couldn't create the identity."))
                            nav.popBackStack()
                        }
                }
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
                        onDevice = { startInvite() },
                        onDevices = { openDevices() },
                        onSite = { /* site detail — later */ },
                    )
                }
            }

            composable(Routes.SETTINGS) {
                val active = identityState as? IdentityState.Active
                if (active != null) {
                    // The persisted relay, re-read after every Save/Reset so the field and
                    // the Default/Custom pill track the store (the engine reads the store
                    // itself, at invite time).
                    var relayUrl by remember { mutableStateOf(relaySettings.current()) }
                    var relayIsDefault by remember { mutableStateOf(relaySettings.isDefault()) }
                    SettingsScreen(
                        state = active,
                        relayUrl = relayUrl,
                        relayIsDefault = relayIsDefault,
                        onSaveRelay = { input ->
                            relaySettings.set(input).also {
                                relayUrl = relaySettings.current()
                                relayIsDefault = relaySettings.isDefault()
                            }
                        },
                        onResetRelay = {
                            relaySettings.reset()
                            relayUrl = relaySettings.current()
                            relayIsDefault = true
                        },
                        focusRelay = focusRelay,
                        onRelayFocused = { focusRelay = false },
                        onRename = { /* rename dialog — later */ },
                        onToggleBiometric = { scope.launch { engine.setBiometricApproval(it) } },
                        onRevoke = { site -> scope.launch { engine.revokeSite(site.id) } },
                        onManageSites = { /* full list — later */ },
                        onRecoveryBackup = {
                            scope.launch {
                                // Biometric-gated; a cancelled prompt is an error, not a crash.
                                runCatching { engine.revealRecoverySecret() }
                                    .onSuccess { revealBackup = it; nav.navigate(Routes.RECOVERY) }
                                    .onFailure { engineError = EngineErrorState(unexpected(it, "Couldn't show the recovery secret.")) }
                            }
                        },
                        onApprovalActivity = {
                            scope.launch {
                                approvalActivity = engine.approvalActivity()
                                nav.navigate(Routes.ACTIVITY)
                            }
                        },
                        onDevices = { openDevices() },
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
                            // Join as the new device; on failure the scanner stays up under the
                            // error dialog so Retry re-joins the same invite without rescanning.
                            is ScannedCode.PairInvite -> joinInvite(c, beforeVerify = { nav.popBackStack() })
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
                        val result = runCatching { engine.awaitPairHandshake() }.getOrElse { EngineResult.Failed(unexpected(it)) }
                        when (result) {
                            is EngineResult.Ready -> {
                                pairSession = result.value
                                nav.navigate(Routes.PAIR_VERIFY) {
                                    popUpTo(Routes.PAIR_CONNECT) { inclusive = true }
                                }
                            }
                            // The relay dropped, or nobody joined before the invite expired: say
                            // so, and let Retry mint a fresh invite (the old session is spent).
                            is EngineResult.Failed -> engineError = EngineErrorState(
                                result.failure,
                                retry = if (result.failure.retryable) ({ startInvite() }) else null,
                            )
                        }
                    }
                    // The REVERSE same-device handoff (ADR-0006): RP apps on this phone
                    // that take the invite by deep link. Resolved once per invite; the
                    // handshake above keeps waiting on the relay while the RP joins, so
                    // returning here lands on VERIFY with the SAS.
                    val context = LocalContext.current
                    val sameDeviceTargets = remember(inv.inviteId) { RpPairLauncher.resolvable(context) }
                    PairConnectScreen(
                        invite = inv,
                        onBack = { nav.popBackStack() },
                        onScanInstead = { nav.navigate(Routes.SCAN) },
                        sameDeviceTargets = sameDeviceTargets,
                        onSendTo = { target -> RpPairLauncher.sendTo(context, target, inv.qrPayload) },
                        onShare = { RpPairLauncher.share(context, inv.qrPayload) },
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
                            // confirmPairing returns a value for every failure (relay dropped while
                            // the cert was in flight, cert did not verify, biometric cancelled).
                            fun confirm() {
                                scope.launch {
                                    val result = runCatching { engine.confirmPairing() }.getOrElse { EngineResult.Failed(unexpected(it)) }
                                    when (result) {
                                        is EngineResult.Ready -> decided(true)
                                        is EngineResult.Failed -> {
                                            // The error stays on screen (a deep-link caller is told
                                            // "not approved" only when the human dismisses it).
                                            engineError = EngineErrorState(
                                                result.failure,
                                                retry = if (result.failure.retryable) ({ confirm() }) else null,
                                            )
                                            if (activeHandoff?.returnsToCaller != true && !result.failure.retryable) decided(false)
                                        }
                                    }
                                }
                            }
                            confirm()
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

            composable(Routes.DEVICES) {
                DevicesScreen(
                    devices = memberDevices,
                    onBack = { nav.popBackStack() },
                    onAddDevice = { startInvite() },
                    onRemove = { device ->
                        scope.launch {
                            // removeDevice never throws for a transport/biometric failure; a Failed
                            // (cancelled prompt, not a member, no identity) lands in the dialog.
                            val result = runCatching { engine.removeDevice(device.id) }.getOrElse { EngineResult.Failed(unexpected(it, "Couldn't remove the device.")) }
                            when (result) {
                                is EngineResult.Ready -> memberDevices = engine.devices()
                                is EngineResult.Failed -> engineError = EngineErrorState(result.failure)
                            }
                        }
                    },
                )
            }
        }
    }
}

/** Dialog title per failure kind — the message itself already says what to do. */
private fun titleFor(kind: EngineFailure.Kind): String = when (kind) {
    EngineFailure.Kind.UNREACHABLE -> "Can't reach the relay"
    EngineFailure.Kind.TIMEOUT -> "Pairing timed out"
    EngineFailure.Kind.REJECTED -> "Pairing refused"
    EngineFailure.Kind.PROTOCOL -> "Pairing didn't verify"
    EngineFailure.Kind.CANCELLED -> "Cancelled"
    EngineFailure.Kind.INTERNAL -> "Something went wrong"
}

/**
 * The final guard's failure for a throw the engine did not classify (it should not
 * happen — every engine pairing step returns a value). Never shows raw exception text.
 */
private fun unexpected(t: Throwable, message: String = "Couldn't complete the pairing."): EngineFailure =
    EngineFailure(
        message = if (t is IllegalArgumentException || t is IllegalStateException) t.message ?: message else message,
        kind = EngineFailure.Kind.INTERNAL,
        retryable = false,
    )

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = VbColors.Mint)
    }
}
