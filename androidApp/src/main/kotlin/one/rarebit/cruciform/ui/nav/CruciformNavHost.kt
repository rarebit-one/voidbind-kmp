package one.rarebit.cruciform.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import one.rarebit.cruciform.AppViewModel
import one.rarebit.cruciform.domain.EngineFailure
import one.rarebit.cruciform.domain.IdentityState
import one.rarebit.cruciform.domain.ScannedCode
import one.rarebit.cruciform.handoff.Handoff
import one.rarebit.cruciform.handoff.RpAppIdentity
import one.rarebit.cruciform.handoff.RpPairLauncher
import one.rarebit.cruciform.handoff.SamePhoneJoin
import one.rarebit.cruciform.pairing.InviteCoordinator
import one.rarebit.cruciform.platform.NotifySettings
import one.rarebit.cruciform.platform.RelaySettings
import one.rarebit.cruciform.ui.flow.EngineErrorState
import one.rarebit.cruciform.ui.flow.LoginViewModel
import one.rarebit.cruciform.ui.flow.OnboardingViewModel
import one.rarebit.cruciform.ui.flow.PairViewModel
import one.rarebit.cruciform.ui.flow.SettingsViewModel
import one.rarebit.cruciform.ui.screens.ApprovalActivityScreen
import one.rarebit.cruciform.ui.screens.DevicesScreen
import one.rarebit.cruciform.ui.screens.HomeScreen
import one.rarebit.cruciform.ui.screens.LoginApprovalScreen
import one.rarebit.cruciform.ui.screens.NumberMatchApprovalScreen
import one.rarebit.cruciform.ui.screens.OnboardingScreen
import one.rarebit.cruciform.ui.screens.PairAllowScreen
import one.rarebit.cruciform.ui.screens.PairConnectScreen
import one.rarebit.cruciform.ui.screens.PairVerifyScreen
import one.rarebit.cruciform.ui.screens.RecoveryBackupScreen
import one.rarebit.cruciform.ui.screens.RecoveryCheckScreen
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

    /** The same-phone one-tap sheet (ADR-0008) — shown instead of PAIR_VERIFY when the RP checked out. */
    const val PAIR_ALLOW = "pair_allow"
    const val RECOVERY = "recovery"
    const val BACKUP_CONFIRM = "backup_confirm"
    const val RECOVERY_DRILL = "recovery_drill"
    const val ACTIVITY = "activity"
    const val DEVICES = "devices"
}

/**
 * [handoff] is a login/pairing the activity was woken into from outside (a push ping
 * or another app's `voidbind:` deep link); the graph runs the SAME approval flow a
 * scan does and reports the decision through [onHandoffFinished] — which, for a
 * deep link, finishes the activity so the calling app resumes.
 *
 * The graph is wiring only: each flow's state lives in its own ViewModel —
 * [LoginViewModel], [PairViewModel] (the responder side of pairing),
 * [SettingsViewModel], [OnboardingViewModel] — plus the app-scoped invite coordinator
 * in [AppViewModel]. What must survive process death is in each ViewModel's
 * SavedStateHandle (see their docs); secrets never are.
 */
@Composable
fun CruciformNavHost(
    appViewModel: AppViewModel,
    handoff: Handoff? = null,
    onHandoffFinished: (Handoff, approved: Boolean) -> Unit = { _, _ -> },
    /**
     * A `cruciform://pair-joined` report from a relying-party app on this phone
     * (ADR-0008). Handed to the invite coordinator, which checks it against the relay
     * before anything is shown.
     */
    samePhoneJoin: SamePhoneJoin? = null,
    /**
     * The one-tap add is signed and delivered: send the human back to the RP's
     * `<scheme>://pair-done?session=` landing and finish. `(rpScheme, session)`.
     */
    onSamePhoneDone: (String?, String) -> Unit = { _, _ -> },
    /**
     * The one-tap report was refused (a key or code that disagreed with the relay):
     * tell the RP through its `pair-done?outcome=refused` leg so it stops waiting.
     * `(rpScheme, session, reason)`. Cruciform's own failure dialog stays up.
     */
    onSamePhoneRefused: (String?, String, String) -> Unit = { _, _, _ -> },
) {
    val nav = rememberNavController()
    val engine = appViewModel.engine
    val identityState by appViewModel.identity.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext
    val clipboard = LocalClipboardManager.current

    // Per-flow state holders, activity-scoped so a flow spanning several destinations
    // (scan → approve, join → verify) keeps one owner.
    val loginVm: LoginViewModel = viewModel { LoginViewModel(engine, createSavedStateHandle()) }
    val pairVm: PairViewModel = viewModel { PairViewModel(engine, createSavedStateHandle()) }
    val onboardingVm: OnboardingViewModel = viewModel { OnboardingViewModel(engine, createSavedStateHandle()) }
    val settingsVm: SettingsViewModel = viewModel {
        SettingsViewModel(engine, RelaySettings(appContext), NotifySettings(appContext), createSavedStateHandle())
    }

    // The initiator's invite lifecycle (ADR-0007): app-scoped, observed here, never
    // restarted by a screen. Its handshake keeps polling the relay while the user is in
    // the relying-party app; this graph reacts to its state transitions below.
    val invites = appViewModel.invites
    val inviteState by invites.state.collectAsStateWithLifecycle()
    // The same-phone one-tap verdict (ADR-0008): None until a relying-party app on this
    // phone has reported a device key + SAS that MATCH what the relay revealed. A
    // mismatch never lands here — it fails the invite outright, through [inviteState].
    val samePhone by invites.samePhone.collectAsStateWithLifecycle()

    // Wiring-only state: the coordinator's failure as a dialog, the handoff whose
    // decision is routed back to the activity, and who a one-tap report came from.
    var inviteError by remember { mutableStateOf<EngineErrorState?>(null) }
    var activeHandoff by remember { mutableStateOf<Handoff?>(null) }
    var samePhoneRp by remember { mutableStateOf<RpAppIdentity?>(null) }
    LaunchedEffect(samePhoneJoin?.seq) {
        val j = samePhoneJoin ?: return@LaunchedEffect
        samePhoneRp = j.rp
        invites.samePhoneJoined(j.report, rpScheme = j.rp.scheme, callerPackage = j.rp.packageName)
    }

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

    fun currentRoute() = nav.currentDestination?.route

    fun goHome() = nav.navigate(Routes.HOME) {
        popUpTo(nav.graph.id) { inclusive = true }
    }

    /**
     * Route a decision on the LOGIN / PAIR_VERIFY screen: a deep-link handoff returns
     * to the calling app (the activity finishes — no navigation here); a push wake or
     * an in-app scan goes Home. Either way the flows are over.
     */
    fun decided(approved: Boolean) {
        loginVm.finish()
        pairVm.finish()
        val h = activeHandoff
        activeHandoff = null
        if (h != null) onHandoffFinished(h, approved)
        if (h == null || !h.returnsToCaller) goHome()
    }

    /** A dialog closed without Retry: a deep-link caller is told "not approved". */
    fun errorClosed() {
        val h = activeHandoff
        if (h != null && h.returnsToCaller) decided(false)
    }

    /**
     * "Add a device": make sure an invite is live and show it. The coordinator mints
     * one only when none is minting / waiting / joined — pressing this twice, or coming
     * back from the RP app, never spends a fresh relay session. Failures surface through
     * [inviteState] (below): a relay that can't be reached at MINT names the configured
     * URL and offers "Change relay".
     */
    fun startInvite() {
        invites.ensureInvite()
        if (route != Routes.PAIR_CONNECT && route != Routes.PAIR_VERIFY) nav.navigate(Routes.PAIR_CONNECT)
    }

    // Flow events → navigation.
    LaunchedEffect(loginVm) {
        loginVm.eventFlow.collect { e ->
            when (e) {
                is LoginViewModel.Event.Show -> if (e.fromScan) {
                    nav.navigate(Routes.LOGIN) { popUpTo(Routes.SCAN) { inclusive = true } }
                } else if (currentRoute() != Routes.LOGIN) {
                    nav.navigate(Routes.LOGIN)
                }

                is LoginViewModel.Event.FetchFailed -> if (e.fromScan) nav.popBackStack()

                LoginViewModel.Event.Approved -> decided(true)

                LoginViewModel.Event.Denied -> decided(false)

                // A failed approval during a deep-link handoff keeps the error on screen;
                // dismissing it returns to the caller.
                LoginViewModel.Event.ApprovalFailed -> if (activeHandoff?.returnsToCaller != true) decided(false)
            }
        }
    }
    LaunchedEffect(pairVm) {
        pairVm.eventFlow.collect { e ->
            when (e) {
                is PairViewModel.Event.Verify -> {
                    if (e.fromScan) nav.popBackStack()
                    if (currentRoute() != Routes.PAIR_VERIFY) nav.navigate(Routes.PAIR_VERIFY)
                }

                PairViewModel.Event.Admitted -> decided(true)

                // The error stays on screen (a deep-link caller is told "not approved"
                // only when the human dismisses it).
                is PairViewModel.Event.ConfirmFailed ->
                    if (activeHandoff?.returnsToCaller != true && !e.retryable) decided(false)
            }
        }
    }
    LaunchedEffect(settingsVm) {
        settingsVm.eventFlow.collect { e ->
            when (e) {
                SettingsViewModel.Event.ShowRecovery -> nav.navigate(Routes.RECOVERY)

                SettingsViewModel.Event.ShowActivity -> nav.navigate(Routes.ACTIVITY)

                SettingsViewModel.Event.ShowDevices -> {
                    if (currentRoute() != Routes.DEVICES) nav.navigate(Routes.DEVICES)
                }
            }
        }
    }
    LaunchedEffect(onboardingVm) {
        onboardingVm.eventFlow.collect { e ->
            when (e) {
                OnboardingViewModel.Event.CreateFailed -> nav.popBackStack()
                OnboardingViewModel.Event.Interrupted -> goHome()
            }
        }
    }

    // React to the invite lifecycle, wherever the user is in the graph: the new device
    // joined (possibly while this app was in the background) → VERIFY with the SAS; a
    // step failed → the dialog, whose Retry re-runs the RIGHT step (a fresh invite after
    // a spent session, a re-confirm after a delivery failure); admitted → done.
    LaunchedEffect(inviteState) {
        when (val st = inviteState) {
            is InviteCoordinator.State.Joined -> {
                // One phone, both apps: the SAS was already compared BY THE APPS over the
                // local intent channel, so the human sees one question, not a code. Any
                // other case — cross-device, or an RP that never called back — is VERIFY.
                val verified = samePhone is InviteCoordinator.SamePhone.Verified
                val target = if (verified) Routes.PAIR_ALLOW else Routes.PAIR_VERIFY
                if (route != target) {
                    nav.navigate(target) {
                        popUpTo(Routes.PAIR_CONNECT) { inclusive = true }
                    }
                }
            }

            is InviteCoordinator.State.Failed -> {
                val clear = { inviteError = null }
                inviteError = EngineErrorState(
                    st.failure,
                    retry = if (st.failure.retryable) {
                        {
                            clear()
                            invites.retry()
                        }
                    } else {
                        null
                    },
                    relayUrl = st.relayUrl.takeIf {
                        st.phase == InviteCoordinator.Phase.MINT &&
                            (
                                st.failure.kind == EngineFailure.Kind.UNREACHABLE ||
                                    st.failure.kind == EngineFailure.Kind.REJECTED
                                )
                    },
                    onDismiss = {
                        clear()
                        invites.dismissFailure()
                    },
                )
            }

            is InviteCoordinator.State.Admitted -> {
                // On the one-tap path the RP is waiting on its own screen: land the human
                // back in it, enrolled. Otherwise the ordinary Home.
                val verified = samePhone as? InviteCoordinator.SamePhone.Verified
                invites.reset()
                if (verified != null) {
                    activeHandoff = null
                    onSamePhoneDone(verified.rpScheme, verified.report.session)
                } else {
                    decided(true)
                }
            }

            else -> Unit
        }
    }

    // A one-tap verdict that lands AFTER the SAS screen is already up (the RP called back
    // a beat late): promote to the one-tap sheet — the comparison is settled, so asking
    // the human for it would be asking for nothing.
    LaunchedEffect(samePhone) {
        when (val sp = samePhone) {
            is InviteCoordinator.SamePhone.Verified -> if (route == Routes.PAIR_VERIFY) {
                nav.navigate(Routes.PAIR_ALLOW) { popUpTo(Routes.PAIR_VERIFY) { inclusive = true } }
            }

            is InviteCoordinator.SamePhone.Refused -> onSamePhoneRefused(sp.rpScheme, sp.report.session, sp.reason)

            InviteCoordinator.SamePhone.None -> Unit
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
            is ScannedCode.WebLogin -> loginVm.open(code, fromScan = false)

            // The pairing deep link (#27) joins as the new device. Failure — the phone has
            // no route to the relay, most likely — is a dialog with Retry, never a crash.
            is ScannedCode.PairInvite -> pairVm.join(code, fromScan = false)

            is ScannedCode.Unknown -> loginVm.showError("Not a Voidbind code.")
        }
    }

    // Errors: at most one dialog of each kind is up; the login one first.
    val loginError by loginVm.loginError.collectAsStateWithLifecycle()
    loginError?.let { error ->
        // Dismissing an error raised during a deep-link handoff (the fetch/join failed, or
        // the approval itself did) returns to the caller, not approved.
        LoginErrorDialog(error) {
            loginVm.dismissLoginError()
            errorClosed()
        }
    }
    val onboardingError by onboardingVm.error.collectAsStateWithLifecycle()
    val loginEngineError by loginVm.error.collectAsStateWithLifecycle()
    val pairError by pairVm.error.collectAsStateWithLifecycle()
    val settingsError by settingsVm.error.collectAsStateWithLifecycle()
    (inviteError ?: pairError ?: onboardingError ?: loginEngineError ?: settingsError)?.let { error ->
        EngineErrorDialog(
            error = error,
            onClosed = ::errorClosed,
            // "Change relay" → Settings with the relay field focused; the failed invite is
            // simply dropped (nothing was minted), and "Add a device" can be tried again.
            onChangeRelay = {
                settingsVm.setRelayFocus(true)
                if (route != Routes.SETTINGS) nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
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
                    onSettings = {
                        if (route !=
                            Routes.SETTINGS
                        ) {
                            nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
                        }
                    },
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
                // Biometric-gated and run once per flow: a cancelled prompt or a keystore
                // error is a dialog and a return to onboarding; a rotation keeps the secret.
                LaunchedEffect(Unit) { onboardingVm.create() }
                val backup by onboardingVm.backup.collectAsStateWithLifecycle()
                val b = backup
                if (b == null) {
                    Loading()
                } else {
                    RecoveryBackupScreen(
                        backup = b,
                        onBack = {
                            onboardingVm.finished()
                            nav.popBackStack()
                        },
                        // Written down: now prove it by re-entering a few groups.
                        onSaved = { nav.navigate(Routes.BACKUP_CONFIRM) },
                        stepLabel = "BACKUP REQUIRED",
                    )
                }
            }

            composable(Routes.BACKUP_CONFIRM) {
                val challenge by onboardingVm.challenge.collectAsStateWithLifecycle()
                val leave = {
                    onboardingVm.finished()
                    goHome()
                }
                if (challenge.isEmpty()) {
                    // The secret is gone (process death): Home keeps asking for the check.
                    LaunchedEffect(Unit) { leave() }
                } else {
                    RecoveryCheckScreen(
                        title = "Check your backup",
                        intro = "From what you wrote down, enter the groups below. " +
                            "This proves you can read it back before you ever need it.",
                        fields = challenge.map { "Group $it" },
                        onCheck = onboardingVm::confirmGroups,
                        onDone = leave,
                        onBack = { nav.popBackStack() },
                        onSkip = leave,
                    )
                }
            }

            composable(Routes.RECOVERY_DRILL) {
                RecoveryCheckScreen(
                    title = "Test recovery secret",
                    intro = "Type your written recovery secret, spaces and all. It is checked against this " +
                        "identity and nothing is signed or stored. A single wrong character is caught.",
                    fields = listOf("Recovery secret"),
                    onCheck = { settingsVm.checkRecoverySecret(it.single()) },
                    onDone = { nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                )
            }

            composable(Routes.RESTORE) {
                RestoreScreen(
                    onBack = { nav.popBackStack() },
                    onRestore = onboardingVm::restore,
                    onDone = { goHome() },
                )
            }

            composable(Routes.HOME) {
                val active = identityState as? IdentityState.Active
                if (active == null) {
                    LaunchedEffect(Unit) {
                        nav.navigate(Routes.ONBOARDING) { popUpTo(nav.graph.id) { inclusive = true } }
                    }
                    Loading()
                } else {
                    HomeScreen(
                        identity = active.identity,
                        device = active.device,
                        trustedSites = active.trustedSites,
                        membership = active.membership,
                        onRenew = { settingsVm.renewMembership() },
                        backupPending = active.backup.confirmPending,
                        onCheckBackup = { nav.navigate(Routes.RECOVERY_DRILL) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                        onCopyIdentity = { clipboard.setText(AnnotatedString(active.identity.fullKey)) },
                        onDevice = { startInvite() },
                        onDevices = { settingsVm.loadDevices(open = true) },
                        onSite = { /* site detail — later */ },
                    )
                }
            }

            composable(Routes.SETTINGS) {
                val active = identityState as? IdentityState.Active
                if (active != null) {
                    // The persisted endpoints (re-read after every Save/Reset so the field
                    // and the Default/Custom pill track the store; the engine reads the
                    // store itself, at invite / registration time) and their drafts.
                    val relay by settingsVm.relay.state.collectAsStateWithLifecycle()
                    val notify by settingsVm.notify.state.collectAsStateWithLifecycle()
                    val focusRelay by settingsVm.focusRelay.collectAsStateWithLifecycle()
                    SettingsScreen(
                        state = active,
                        notifyUrl = notify.url,
                        notifyIsDefault = notify.isDefault,
                        onSaveNotify = settingsVm.notify::save,
                        onResetNotify = settingsVm.notify::reset,
                        notifyDraft = notify.draft,
                        onNotifyDraftChange = settingsVm.notify::onDraftChange,
                        relayUrl = relay.url,
                        relayIsDefault = relay.isDefault,
                        onSaveRelay = settingsVm.relay::save,
                        onResetRelay = settingsVm.relay::reset,
                        relayDraft = relay.draft,
                        onRelayDraftChange = settingsVm.relay::onDraftChange,
                        focusRelay = focusRelay,
                        onRelayFocused = { settingsVm.setRelayFocus(false) },
                        onRename = { /* rename dialog — later */ },
                        onToggleBiometric = settingsVm::setBiometricApproval,
                        onRevoke = settingsVm::revoke,
                        onManageSites = { /* full list — later */ },
                        onRecoveryBackup = settingsVm::revealRecovery,
                        onTestRecovery = { nav.navigate(Routes.RECOVERY_DRILL) },
                        onForgetRecovery = settingsVm::forgetRecoverySecret,
                        onApprovalActivity = { settingsVm.loadActivity(open = true) },
                        onDevices = { settingsVm.loadDevices(open = true) },
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
                            is ScannedCode.WebLogin -> loginVm.open(c, fromScan = true)

                            // Join as the new device; on failure the scanner stays up under the
                            // error dialog so Retry re-joins the same invite without rescanning.
                            is ScannedCode.PairInvite -> pairVm.join(c, fromScan = true)

                            is ScannedCode.Unknown -> { /* not a Voidbind code — surfaced later */ }
                        }
                    },
                )
            }

            composable(Routes.LOGIN) {
                val req by loginVm.request.collectAsStateWithLifecycle()
                val pendingCode by loginVm.pendingCode.collectAsStateWithLifecycle()
                val policy by loginVm.policy.collectAsStateWithLifecycle()
                val request = req
                when {
                    // Back after process death: the code survived, the fetched request did
                    // not — re-fetch it. Nothing to resume → Home.
                    request == null -> if (pendingCode != null) {
                        LaunchedEffect(pendingCode) { loginVm.resume() }
                        Loading()
                    } else {
                        LaunchedEffect(Unit) { goHome() }
                    }

                    // A push-woken, number-matching login: show the candidate grid and
                    // approve by the tapped number (v2). A decoy tap is refused by the RP.
                    request.isNumberMatch -> NumberMatchApprovalScreen(
                        request = request,
                        onDeny = loginVm::deny,
                        onApprove = { chosen -> loginVm.approve(chosen) },
                    )

                    else -> {
                        // Fetch this RP's current approval policy when the sheet opens so it can
                        // show trusted/always-ask and offer the inline pin toggle.
                        LaunchedEffect(request.domain) { loginVm.loadPolicy(request.domain) }
                        LoginApprovalScreen(
                            request = request,
                            onDeny = loginVm::deny,
                            onApprove = { loginVm.approve() },
                            policy = policy,
                            onSetAlwaysAsk = { alwaysAsk -> loginVm.setAlwaysAsk(request.domain, alwaysAsk) },
                        )
                    }
                }
            }

            composable(Routes.PAIR_CONNECT) {
                // This screen only OBSERVES the app-scoped invite (ADR-0007). Leaving and
                // coming back — or the process being backgrounded while the RP app creates
                // its key — restarts nothing: the handshake job keeps polling the relay and
                // the countdown is the coordinator's clock, not this composition's.
                val st = inviteState
                val inv = st.invite
                when {
                    st is InviteCoordinator.State.Idle -> LaunchedEffect(Unit) { nav.popBackStack() }

                    inv == null -> Loading()

                    // Minting (or a mint failure: the dialog is up, then Idle pops)
                    else -> {
                        val context = LocalContext.current
                        // The REVERSE same-device handoff (ADR-0006): RP apps on this phone that
                        // take the invite by deep link. Resolved once per invite. Sending never
                        // re-mints: the invite is the coordinator's, and it is still waiting on
                        // the relay while the RP joins, so returning here lands on VERIFY.
                        val sameDeviceTargets = remember(inv.inviteId) { RpPairLauncher.resolvable(context) }
                        var remaining by remember(inv.inviteId) { mutableIntStateOf(invites.remainingSeconds()) }
                        LaunchedEffect(inv.inviteId) {
                            while (true) {
                                remaining = invites.remainingSeconds()
                                delay(1000)
                            }
                        }
                        // Leaving this screen on purpose — the top-bar Back or the system back
                        // gesture — cancels the invite (drops the wait + the keep-alive). Only the
                        // human does this; switching apps is not leaving.
                        val leave = {
                            invites.cancel()
                            nav.popBackStack()
                            Unit
                        }
                        BackHandler(onBack = leave)
                        PairConnectScreen(
                            invite = inv,
                            onBack = leave,
                            onScanInstead = { nav.navigate(Routes.SCAN) },
                            sameDeviceTargets = sameDeviceTargets,
                            onSendTo = { target -> RpPairLauncher.sendTo(context, target, inv.qrPayload) },
                            onShare = { RpPairLauncher.share(context, inv.qrPayload) },
                            remainingSeconds = remaining,
                            status = when (st) {
                                is InviteCoordinator.State.Waiting -> "Waiting for the new device to join… you can switch apps; this keeps waiting."
                                is InviteCoordinator.State.Failed -> null
                                else -> "The new device joined — comparing the security code…"
                            },
                        )
                    }
                }
            }

            composable(Routes.PAIR_VERIFY) {
                // The initiator's session comes from the coordinator (it stays valid across a
                // return from the RP app); the responder's from the join flow.
                val initiator = when (val st = inviteState) {
                    is InviteCoordinator.State.Joined -> st.session
                    is InviteCoordinator.State.Confirming -> st.session
                    is InviteCoordinator.State.Failed -> st.resume?.session
                    else -> null
                }
                val responder by pairVm.session.collectAsStateWithLifecycle()
                val pendingInvite by pairVm.pendingInvite.collectAsStateWithLifecycle()
                val ses = initiator ?: responder
                when {
                    // Back after process death mid-join: the handshake is gone, the invite
                    // is not — the dialog offers to re-join it. Nothing to resume → Home.
                    ses == null -> if (pendingInvite != null) {
                        LaunchedEffect(pendingInvite) { if (!pairVm.resumeInterrupted()) goHome() }
                        Loading()
                    } else {
                        LaunchedEffect(Unit) { goHome() }
                    }

                    initiator != null -> PairVerifyScreen(
                        session = ses,
                        onCancel = {
                            invites.cancel()
                            decided(false)
                        },
                        // confirm() → Admitted (decided(true) above) or Failed (the dialog,
                        // Retry re-confirms the SAME session — no re-mint).
                        onConfirm = { invites.confirm() },
                    )

                    // confirm returns a value for every failure (relay dropped while the cert
                    // was in flight, cert did not verify, biometric cancelled).
                    else -> PairVerifyScreen(
                        session = ses,
                        onCancel = { decided(false) },
                        onConfirm = pairVm::confirm,
                    )
                }
            }

            composable(Routes.PAIR_ALLOW) {
                // ADR-0008: reached ONLY from a verified same-phone report. If the verdict
                // is gone (a fresh invite, a cancel) fall back to the SAS screen rather
                // than showing a one-tap approval nothing checked.
                val verified = samePhone as? InviteCoordinator.SamePhone.Verified
                val st = inviteState
                val busy = st is InviteCoordinator.State.Confirming
                when {
                    verified == null -> LaunchedEffect(Unit) {
                        nav.navigate(Routes.PAIR_VERIFY) { popUpTo(Routes.PAIR_ALLOW) { inclusive = true } }
                    }

                    st !is InviteCoordinator.State.Joined && st !is InviteCoordinator.State.Confirming ->
                        LaunchedEffect(Unit) { goHome() }

                    else -> {
                        val cancel = {
                            invites.cancel()
                            decided(false)
                        }
                        BackHandler(onBack = cancel)
                        PairAllowScreen(
                            appName = samePhoneRp?.label ?: "This app",
                            appIcon = samePhoneRp?.icon,
                            busy = busy,
                            // confirm() is the SAME signing path the SAS screen uses — the
                            // biometric, the add op, the sealed delivery. Only the human
                            // question in front of it changed.
                            onAllow = { invites.confirm() },
                            onCancel = cancel,
                        )
                    }
                }
            }

            composable(Routes.RECOVERY) {
                // The revealed secret is memory-only: after process death it is gone and
                // this screen closes (the user re-authenticates to see it again).
                val revealed by settingsVm.revealed.collectAsStateWithLifecycle()
                val b = revealed
                val close = {
                    settingsVm.clearRecovery()
                    nav.popBackStack()
                    Unit
                }
                if (b == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    RecoveryBackupScreen(
                        backup = b,
                        onBack = close,
                        onSaved = close,
                        stepLabel = "RECOVERY SECRET",
                    )
                }
            }

            composable(Routes.ACTIVITY) {
                LaunchedEffect(Unit) { settingsVm.loadActivity(open = false) }
                val activity by settingsVm.activity.collectAsStateWithLifecycle()
                ApprovalActivityScreen(
                    activity = activity,
                    onBack = { nav.popBackStack() },
                )
            }

            composable(Routes.DEVICES) {
                LaunchedEffect(Unit) { settingsVm.loadDevices(open = false) }
                val devices by settingsVm.devices.collectAsStateWithLifecycle()
                DevicesScreen(
                    devices = devices,
                    onBack = { nav.popBackStack() },
                    onAddDevice = { startInvite() },
                    // removeDevice never throws; a Failed (cancelled prompt, not a member, no
                    // identity) lands in the dialog.
                    onRemove = settingsVm::removeDevice,
                )
            }
        }
    }
}
