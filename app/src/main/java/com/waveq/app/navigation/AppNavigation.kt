package com.waveq.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import com.waveq.app.data.IncidentViewModel
import kotlinx.coroutines.launch
import androidx.navigation.navArgument
import com.waveq.app.auth.SessionManager
import com.waveq.app.auth.UserRole
import com.waveq.app.auth.satisfies
import com.waveq.app.location.LocationController
import com.waveq.app.mesh.MeshViewModel
import com.waveq.app.mesh.SosTriage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.waveq.app.mesh.PermissionUtils
import com.waveq.app.settings.DisplayNameSettings
import com.waveq.app.settings.MeshOnboarding
import com.waveq.app.settings.ThemeMode
import com.waveq.app.settings.ThemeSettings
import com.waveq.app.ui.components.*
import com.waveq.app.ui.screens.*
import com.waveq.app.ui.theme.*
import kotlinx.coroutines.launch

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val REPORT = "report"
    const val OPERATOR = "operator"
    const val BROADCAST = "broadcast_alert"
    const val PUBLIC = "public"
    const val ADMIN = "admin"
    const val MESH_CHANNELS = "mesh_channels"
    const val MESH_CHAT = "mesh_chat/{channelId}"
    const val SOS = "sos"
    const val RISK = "risk_detail"
    const val LOCATION = "location_picker"
    const val EVACUATION = "evacuation"
    const val SETTINGS = "settings"
    const val ALERT_DELIVERY = "alert_delivery"

    fun meshChat(channelId: String) = "mesh_chat/$channelId"
}

/** [requiredRole] null = open to everyone; see [satisfies] for how ADMIN also satisfies an OPERATOR gate. */
private data class DrawerItem(val route: String, val label: String, val icon: ImageVector, val requiredRole: UserRole? = null)

private val allDrawerItems = listOf(
    DrawerItem(Routes.HOME, "Home", Icons.Filled.Home),
    DrawerItem(Routes.RISK, "Flood Risk", Icons.Filled.WarningAmber),
    DrawerItem(Routes.LOCATION, "Change Location", Icons.Filled.Place),
    DrawerItem(Routes.REPORT, "Report Incident", Icons.Filled.Error),
    DrawerItem(Routes.OPERATOR, "Operator Dashboard", Icons.Filled.Groups, UserRole.OPERATOR),
    DrawerItem(Routes.BROADCAST, "Broadcast Alert", Icons.Filled.Campaign, UserRole.OPERATOR),
    DrawerItem(Routes.PUBLIC, "Public View", Icons.Filled.Shield),
    DrawerItem(Routes.MESH_CHANNELS, "Mesh Channels", Icons.Filled.Forum),
    DrawerItem(Routes.EVACUATION, "Evacuation", Icons.AutoMirrored.Filled.DirectionsRun),
    DrawerItem(Routes.SOS, "SOS Beacon", Icons.Filled.Sos),
    DrawerItem(Routes.ADMIN, "Admin Panel", Icons.Filled.Groups, UserRole.ADMIN),
    DrawerItem(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot(
    prearmedSosNote: String? = null,
    onPrearmedSosConsumed: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val meshViewModel: MeshViewModel = viewModel()
    // Activity-scoped, so its collectors survive navigating between destinations.
    val incidentViewModel: IncidentViewModel = viewModel()

    LaunchedEffect(Unit) {
        SessionManager.init(context)
        DisplayNameSettings.init(context)
        MeshOnboarding.init(context)
    }
    val session by SessionManager.session.collectAsState()
    val role = session?.role

    // Nothing may be sent under a placeholder name, so the prompt is modal and
    // non-dismissible until one is chosen. It reappears on every demo login
    // (each demo run is a different person at the same device) and on demand
    // from the drawer.
    val displayName by DisplayNameSettings.name.collectAsState()
    var forceNamePrompt by remember { mutableStateOf(false) }
    val needsName = session != null && displayName.isNullOrBlank()
    val showNamePrompt = needsName || (session != null && forceNamePrompt)

    // Mesh onboarding, after the name prompt: a fresh install otherwise receives
    // nothing at all until the user finds the switch inside Mesh Channels.
    val meshIntroShown by MeshOnboarding.introShown.collectAsState()
    val alertSetupShown by MeshOnboarding.alertSetupShown.collectAsState()
    val meshRunning by meshViewModel.isRunning.collectAsState()
    val showMeshIntro = session != null && !needsName && !forceNamePrompt && !meshIntroShown

    val meshPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            meshViewModel.startMesh()
            MeshOnboarding.resetHomeCard(context)
        }
        // Either way the explainer has been answered - a denied permission is a
        // decision, and the Home card is what follows up on it.
        MeshOnboarding.markIntroShown(context)
    }

    val performLogout: () -> Unit = {
        SessionManager.signOut(context)
        navController.navigate(Routes.LOGIN) {
            popUpTo(0) { inclusive = true }
        }
    }

    val requestMeshEnable: () -> Unit = {
        if (PermissionUtils.hasAllMeshPermissions(context)) {
            meshViewModel.startMesh()
            MeshOnboarding.resetHomeCard(context)
            MeshOnboarding.markIntroShown(context)
        } else {
            meshPermissionLauncher.launch(PermissionUtils.requiredMeshPermissions())
        }
    }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showChrome = currentRoute != null && currentRoute != Routes.LOGIN

    val viewingOverride by LocationController.viewingOverride.collectAsState()
    val wasOverrideRestored by LocationController.wasOverrideRestored.collectAsState()

    val sosBeacons by meshViewModel.sosBeacons.collectAsState()
    LaunchedEffect(Unit) { SosTriage.init(context) }
    val pinnedBeaconIds by SosTriage.pinned.collectAsState()
    val dismissedBeaconIds by SosTriage.dismissed.collectAsState()
    val activeOtherSosCount = sosBeacons.values.count { record ->
        !record.isMine &&
            record.beacon.beaconId !in dismissedBeaconIds &&
            System.currentTimeMillis() - record.receivedAt <= 120_000L
    }
    // Beacons this user said they are responding to stay in front of them
    // app-wide until they unpin, not just while the SOS screen is open.
    val respondingCount = sosBeacons.values.count { record ->
        !record.isMine && record.beacon.beaconId in pinnedBeaconIds
    }

    // Captured separately from prearmedSosNote (rather than read directly further
    // down) because onPrearmedSosConsumed() clears the source parameter to null
    // in the same effect that navigates - AppNavHost needs a value that survives that.
    var pendingSosNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(prearmedSosNote) {
        if (prearmedSosNote != null) {
            pendingSosNote = prearmedSosNote
            navController.navigate(Routes.SOS) {
                popUpTo(Routes.LOGIN) { inclusive = true }
                launchSingleTop = true
            }
            onPrearmedSosConsumed()
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl,
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = showChrome,
            drawerContent = {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
                ) {
                    AppDrawer(
                        currentRoute = currentRoute,
                        role = role,
                        accountName = session?.displayName ?: "Guest",
                        meshName = displayName,
                        onNavigate = { route ->
                            scope.launch { drawerState.close() }
                            navController.navigate(route) {
                                launchSingleTop = true
                                popUpTo(Routes.HOME)
                            }
                        },
                    )
                }
            },
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
            ) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        if (showChrome) {
                            Column {
                                DisasterTopBar(
                                    onMenuClick = { scope.launch { drawerState.open() } },
                                    onSosClick = {
                                        navController.navigate(Routes.SOS) { launchSingleTop = true }
                                    },
                                )
                                if (respondingCount > 0 && currentRoute != Routes.SOS) {
                                    RespondingBanner(
                                        count = respondingCount,
                                        onClick = { navController.navigate(Routes.SOS) { launchSingleTop = true } },
                                    )
                                } else if (activeOtherSosCount > 0 && currentRoute != Routes.SOS) {
                                    SosBanner(
                                        count = activeOtherSosCount,
                                        onClick = { navController.navigate(Routes.SOS) { launchSingleTop = true } },
                                    )
                                }
                                // App-wide, not just on the risk screens: the
                                // confusion this guards against (reading another
                                // place's score as your own) follows the user
                                // onto every screen the override is active on.
                                viewingOverride?.let { override ->
                                    ViewingLocationBanner(
                                        placeName = override.fullLabel(),
                                        wasRestored = wasOverrideRestored,
                                        onReset = { LocationController.resetToCurrentLocation() },
                                        onClick = {
                                            navController.navigate(Routes.LOCATION) { launchSingleTop = true }
                                        },
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        AppNavHost(
                            navController = navController,
                            meshViewModel = meshViewModel,
                            incidentViewModel = incidentViewModel,
                            pendingSosNote = pendingSosNote,
                            role = role,
                            userName = displayName ?: session?.displayName ?: "Guest",
                            onDemoSignIn = { forceNamePrompt = true },
                            meshRunning = meshRunning,
                            onEnableMesh = requestMeshEnable,
                            onLogout = performLogout,
                        )
                    }
                }
            }
        }
    }

    // Above everything, and only once the user has a name: one decision at a time.
    if (showMeshIntro) {
        MeshIntroScreen(
            onEnable = requestMeshEnable,
            onSkip = { MeshOnboarding.markIntroShown(context) },
        )
    } else if (session != null && !needsName && !forceNamePrompt && !alertSetupShown) {
        // Second and last onboarding step: a visual alert depends on three OS
        // grants the app cannot assume, and the user is the only one who can
        // give them.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AlertDeliveryScreen(onDone = { MeshOnboarding.markAlertSetupShown(context) })
        }
    }

    if (showNamePrompt) {
        DisplayNameDialog(
            // Prefilled from the session so the common case is one tap: the
            // account already knows what to call this person.
            initialValue = displayName ?: session?.displayName.orEmpty(),
            dismissible = !needsName,
            onDismiss = { forceNamePrompt = false },
            onConfirm = { chosen ->
                meshViewModel.setSenderName(chosen)
                forceNamePrompt = false
            },
        )
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    meshViewModel: MeshViewModel,
    incidentViewModel: IncidentViewModel,
    pendingSosNote: String?,
    role: UserRole?,
    userName: String,
    onDemoSignIn: () -> Unit,
    meshRunning: Boolean,
    onEnableMesh: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    NavHost(navController = navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onSignIn = { email, _, signInRole ->
                    SessionManager.signIn(
                        context,
                        userId = email.ifBlank { "demo-user" },
                        displayName = email.ifBlank { "Demo User" },
                        role = signInRole,
                    )
                    navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
                },
                // Every sign-in path lands on Home with LOGIN popped, including
                // the citizen demo, which used to drop straight onto the public
                // view - a different starting screen depending on how you signed
                // in, and one with no Home in the back stack for the drawer's
                // popUpTo to match.
                onDemoCitizen = {
                    SessionManager.signIn(context, userId = "demo-citizen", displayName = "Demo Citizen", role = UserRole.CITIZEN)
                    onDemoSignIn()
                    navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
                },
                onDemoOperator = {
                    SessionManager.signIn(context, userId = "demo-operator", displayName = "Demo Operator", role = UserRole.OPERATOR)
                    onDemoSignIn()
                    navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
                },
            )
        }
        composable(Routes.HOME) {
            val incidentCount by incidentViewModel.incidentCount.collectAsState()
            HomeScreen(
                userName = userName,
                meshViewModel = meshViewModel,
                incidentCount = incidentCount,
                meshRunning = meshRunning,
                onEnableMesh = onEnableMesh,
                onOpenAlertDelivery = { navController.navigate(Routes.ALERT_DELIVERY) },
                onReportIncident = { navController.navigate(Routes.REPORT) },
                onOperatorDashboard = { navController.navigate(Routes.OPERATOR) },
                onPublicView = { navController.navigate(Routes.PUBLIC) },
                onAdminPanel = { navController.navigate(Routes.ADMIN) },
                onRiskDetail = { navController.navigate(Routes.RISK) },
                onChangeLocation = { navController.navigate(Routes.LOCATION) },
            )
        }
        composable(Routes.REPORT) {
            var showReportSheet by remember { mutableStateOf(false) }
            // Observable, process-scoped result: the write outlives this
            // destination, so the confirmation must not be a composable-local var.
            val submission by incidentViewModel.lastSubmission.collectAsState()

            ReportIncidentScreen(onReportDisaster = { showReportSheet = true })

            if (showReportSheet) {
                ReportDisasterSheet(
                    onDismiss = { showReportSheet = false },
                    onSubmit = { type, severity, location, description ->
                        showReportSheet = false
                        // Stores first, then relays - a report must survive
                        // having no peer in range at the moment it is filed.
                        incidentViewModel.submitReport(
                            type = type,
                            severity = severity,
                            location = location,
                            description = description,
                        ) { saved ->
                            // peersReached is the real dispatch count from the
                            // transport, not a fabricated number.
                            meshViewModel.broadcastIncidentReport(
                                referenceId = saved.id,
                                type = type,
                                severity = severity,
                                location = location,
                                description = description,
                                latitude = saved.latitude,
                                longitude = saved.longitude,
                                reportedAtMillis = saved.reportedAtMillis,
                            )
                        }
                    },
                )
            }

            submission?.let { result ->
                ReportSubmittedDialog(
                    referenceId = result.referenceId,
                    peersReached = result.peersReached,
                    onDismiss = { incidentViewModel.consumeSubmission() },
                )
            }
        }
        composable(Routes.OPERATOR) {
            val storedIncidents by incidentViewModel.incidents.collectAsState()
            val incidents = remember(storedIncidents) { storedIncidents.map { it.toIncident() } }
            val pendingCount by incidentViewModel.pendingCount.collectAsState()
            val moderationResult by incidentViewModel.lastModeration.collectAsState()
            RequireRole(role, UserRole.OPERATOR, navController) {
                OperatorDashboardScreen(
                    incidents = incidents,
                    pendingCount = pendingCount,
                    moderationResult = moderationResult,
                    onAcknowledgeModeration = { incidentViewModel.consumeModeration() },
                    onConfirm = { incident ->
                        incidentViewModel.verifyReport(incident.id) { saved ->
                            meshViewModel.broadcastVerification(saved)
                        }
                    },
                    onDismiss = { incident -> incidentViewModel.dismissReport(incident.id) },
                )
            }
        }
        composable(Routes.BROADCAST) {
            RequireRole(role, UserRole.OPERATOR, navController) { BroadcastAlertScreen(viewModel = meshViewModel) }
        }
        composable(Routes.PUBLIC) {
            val storedIncidents by incidentViewModel.incidents.collectAsState()
            val incidents = remember(storedIncidents) { storedIncidents.map { it.toIncident() } }
            PublicCrisisScreen(incidents = incidents)
        }
        composable(Routes.ALERT_DELIVERY) { AlertDeliveryScreen() }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                meshViewModel = meshViewModel,
                onOpenAlertDelivery = { navController.navigate(Routes.ALERT_DELIVERY) },
                onEnableMesh = onEnableMesh,
                onLogout = onLogout,
            )
        }
        composable(Routes.EVACUATION) { EvacuationMapScreen() }
        composable(Routes.ADMIN) {
            val reportCount by incidentViewModel.incidentCount.collectAsState()
            RequireRole(role, UserRole.ADMIN, navController) {
                AdminScreen(reportCount = reportCount)
            }
        }
        composable(Routes.MESH_CHANNELS) {
            MeshChannelsScreen(
                viewModel = meshViewModel,
                onOpenChannel = { channelId -> navController.navigate(Routes.meshChat(channelId)) },
            )
        }
        composable(
            Routes.MESH_CHAT,
            arguments = listOf(navArgument("channelId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val channelId = backStackEntry.arguments?.getString("channelId").orEmpty()
            MeshChatScreen(channelId = channelId, viewModel = meshViewModel)
        }
        composable(Routes.RISK) { RiskDetailScreen() }
        composable(Routes.LOCATION) {
            LocationPickerScreen(onDone = { navController.popBackStack() })
        }
        composable(Routes.SOS) {
            SosScreen(viewModel = meshViewModel, initialNote = pendingSosNote.orEmpty())
        }
    }
}

/**
 * Nav-graph-level gate: hiding a drawer item is not a gate on its own, so
 * every operator-or-above route also checks the role here before rendering
 * anything. If a CITIZEN somehow reaches one of these routes (a stale deep
 * link, a missed guard elsewhere), this redirects to Home instead of showing
 * so much as a flash of the screen's content.
 */
@Composable
private fun RequireRole(
    role: UserRole?,
    required: UserRole,
    navController: NavHostController,
    content: @Composable () -> Unit,
) {
    if (role.satisfies(required)) {
        content()
    } else {
        LaunchedEffect(Unit) {
            navController.navigate(Routes.HOME) {
                popUpTo(Routes.HOME) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
}

@Composable
private fun AppDrawer(
    currentRoute: String?,
    role: UserRole?,
    onNavigate: (String) -> Unit,
    /** The name announced on the mesh - distinct from the account name above it. */
    meshName: String?,
    accountName: String = "Guest",
) {
    val visibleItems = remember(role) { allDrawerItems.filter { role.satisfies(it.requiredRole) } }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerShape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth(0.82f),
    ) {
        Column(Modifier.padding(horizontal = Dimens.screenPadding)) {
            Spacer(Modifier.height(Dimens.sectionSpacing))
            Text(accountName, style = AppTypography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            MicroLabel(role?.let { "Signed in as ${it.label}" } ?: "Not signed in")
            meshName?.let {
                Spacer(Modifier.height(4.dp))
                MicroLabel("On the mesh as $it")
            }
        }
        Spacer(Modifier.height(Dimens.sectionSpacing))

        visibleItems.forEach { item ->
            val selected = currentRoute == item.route
            // Selection is a tone lift, not a filled chip - no chrome in the drawer either.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(Dimens.cardRadius))
                    .background(
                        if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent,
                    )
                    .clickable { onNavigate(item.route) }
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    item.icon, contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(18.dp))
                Text(
                    item.label,
                    style = AppTypography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // The drawer is navigation only. Theme, display name, mesh, alert
        // delivery and logout all live on the Settings screen - a user looking
        // for "settings" should not have to find them in a nav drawer.
        Spacer(Modifier.height(Dimens.sectionSpacing))
    }
}