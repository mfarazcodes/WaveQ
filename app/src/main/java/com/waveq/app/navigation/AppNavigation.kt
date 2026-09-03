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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.waveq.app.ui.components.DisasterTopBar
import com.waveq.app.ui.screens.*
import com.waveq.app.ui.theme.*
import kotlinx.coroutines.launch

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val REPORT = "report"
    const val OPERATOR = "operator"
    const val PUBLIC = "public"
    const val ADMIN = "admin"
}

private data class DrawerItem(val route: String, val label: String, val icon: ImageVector)

private val drawerItems = listOf(
    DrawerItem(Routes.HOME, "Home", Icons.Filled.Home),
    DrawerItem(Routes.REPORT, "Report Incident", Icons.Filled.Error),
    DrawerItem(Routes.OPERATOR, "Operator Dashboard", Icons.Filled.Groups),
    DrawerItem(Routes.PUBLIC, "Public View", Icons.Filled.Shield),
    DrawerItem(Routes.ADMIN, "Admin Panel", Icons.Filled.Settings),
)

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showChrome = currentRoute != null && currentRoute != Routes.LOGIN

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
                        onNavigate = { route ->
                            scope.launch { drawerState.close() }
                            navController.navigate(route) {
                                launchSingleTop = true
                                popUpTo(Routes.HOME)
                            }
                        },
                        onLogout = {
                            scope.launch { drawerState.close() }
                            navController.navigate(Routes.LOGIN) {
                                popUpTo(0) { inclusive = true }
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
                    containerColor = AppBackground,
                    topBar = {
                        if (showChrome) {
                            DisasterTopBar(onMenuClick = { scope.launch { drawerState.open() } })
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        AppNavHost(navController)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onSignIn = { _, _ ->
                    navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
                },
                onDemoCitizen = {
                    navController.navigate(Routes.PUBLIC) { popUpTo(Routes.LOGIN) { inclusive = true } }
                },
                onDemoOperator = {
                    navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onReportIncident = { navController.navigate(Routes.REPORT) },
                onOperatorDashboard = { navController.navigate(Routes.OPERATOR) },
                onPublicView = { navController.navigate(Routes.PUBLIC) },
                onAdminPanel = { navController.navigate(Routes.ADMIN) },
            )
        }
        composable(Routes.REPORT) {
            ReportIncidentScreen(onReportDisaster = { })
        }
        composable(Routes.OPERATOR) { OperatorDashboardScreen() }
        composable(Routes.PUBLIC) { PublicCrisisScreen() }
        composable(Routes.ADMIN) { AdminScreen() }
    }
}

@Composable
private fun AppDrawer(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onLogout: () -> Unit,
    accountName: String = "Operator Account",
    accountEmail: String = "demo@example.com",
) {
    ModalDrawerSheet(
        drawerContainerColor = Surface,
        drawerShape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth(0.82f),
    ) {
        Column(Modifier.padding(Dimens.screenPadding)) {
            Spacer(Modifier.height(12.dp))
            Text(accountName, style = AppTypography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(accountEmail, style = AppTypography.bodySmall, color = TextSecondary)
        }
        HorizontalDivider(color = BorderLight)
        Spacer(Modifier.height(8.dp))

        drawerItems.forEach { item ->
            val selected = currentRoute == item.route
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) DrawerSelected else Color.Transparent)
                    .clickable { onNavigate(item.route) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    item.icon, contentDescription = null,
                    tint = if (selected) DrawerOnSelected else TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    item.label,
                    style = AppTypography.titleSmall,
                    color = if (selected) DrawerOnSelected else TextPrimary,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = BorderLight)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onLogout)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text("Logout", style = AppTypography.titleSmall, color = TextPrimary)
        }
    }
}