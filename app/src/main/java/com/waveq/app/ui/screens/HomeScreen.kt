package com.waveq.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.waveq.app.alerts.AlertSettings
import com.waveq.app.auth.SessionManager
import com.waveq.app.auth.UserRole
import com.waveq.app.auth.satisfies
import com.waveq.app.location.LocationController
import com.waveq.app.mesh.MeshViewModel
import com.waveq.app.mesh.MessageType
import com.waveq.app.prediction.RiskRepository
import com.waveq.app.settings.MeshOnboarding
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*

@Composable
fun HomeScreen(
    // No "Operator" default: the greeting must reflect the actual session, or a
    // CITIZEN gets welcomed as an operator on a screen that then hides every
    // operator action from them.
    userName: String,
    meshViewModel: MeshViewModel,
    /** Live count from the incident store, not a hardcoded figure. */
    incidentCount: Int,
    meshRunning: Boolean,
    onEnableMesh: () -> Unit,
    onOpenAlertDelivery: () -> Unit,
    onReportIncident: () -> Unit,
    onOperatorDashboard: () -> Unit,
    onPublicView: () -> Unit,
    onAdminPanel: () -> Unit,
    onRiskDetail: () -> Unit,
    onChangeLocation: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Re-read on every resume, not computed once inside remember. The grant is
    // made in system Settings, so the user always returns to this screen having
    // just changed it - and the cached version went on telling them alerts were
    // blocked for the rest of the session.
    var deliveryStatus by remember { mutableStateOf(readAlertDeliveryStatus(context)) }
    var setupCardDismissed by remember { mutableStateOf(AlertSettings.isFullScreenSetupCardDismissed(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                deliveryStatus = readAlertDeliveryStatus(context)
                setupCardDismissed = AlertSettings.isFullScreenSetupCardDismissed(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val showFullScreenSetupCard = !deliveryStatus.allGranted && !setupCardDismissed
    val session by SessionManager.session.collectAsState()
    val role = session?.role
    val peerCount by meshViewModel.peerCount.collectAsState()
    val meshCardDismissed by MeshOnboarding.homeCardDismissed.collectAsState()
    val carryingStats by meshViewModel.carryingStats.collectAsState()
    val messagesByChannel by meshViewModel.messagesByChannel.collectAsState()
    val riskState by RiskRepository.state.collectAsState()
    val viewingPlace by LocationController.viewingPlace.collectAsState()
    // Re-reads the clock every minute so the "fetched N min ago" caption ages
    // while the screen is open rather than freezing at what it said on entry.
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000L)
        }
    }

    // Real alerts actually received over the mesh, newest first - not the three
    // hardcoded rows that used to sit here. FLOOD_ALERT is the authoritative
    // alert type (it is what fires sirens), so it is what this section reports.
    val recentAlerts = remember(messagesByChannel, now) {
        messagesByChannel.values.asSequence()
            .flatten()
            .filter { it.type == MessageType.FLOOD_ALERT }
            .sortedByDescending { it.timestamp }
            .mapNotNull { message ->
                val severity = Severity.entries.firstOrNull { it.name == message.severity }
                    ?: return@mapNotNull null
                if (!severity.isNotable) return@mapNotNull null
                val text = message.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RecentAlert(
                    title = text,
                    timeAgo = "${formatAge((now - message.timestamp).coerceAtLeast(0L))} ago",
                    severity = severity,
                )
            }
            .take(MAX_RECENT_ALERTS)
            .toList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding),
    ) {
        // Which place the gauge below refers to, tappable, at the very top of
        // the screen - a user must never have to guess whether the score they
        // are reading is for where they are or somewhere they looked up.
        Spacer(Modifier.height(Dimens.cardSpacing))
        LocationHeaderButton(
            place = viewingPlace,
            isOverridden = riskState.isViewingOverridden,
            onClick = onChangeLocation,
        )

        Spacer(Modifier.height(Dimens.cardSpacing))
        MicroLabel("Welcome")
        Spacer(Modifier.height(6.dp))
        Text(userName, style = AppTypography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)

        // The follow-up to declining mesh onboarding. Persistent while the mesh
        // is off, because "you are receiving no alerts" is not a state the user
        // should have to remember they chose.
        if (!meshRunning && !meshCardDismissed) {
            Spacer(Modifier.height(Dimens.sectionSpacing))
            MeshOffCard(
                onEnable = onEnableMesh,
                onDismiss = { MeshOnboarding.dismissHomeCard(context) },
            )
        }

        if (showFullScreenSetupCard) {
            Spacer(Modifier.height(Dimens.sectionSpacing))
            FullScreenAlertSetupCard(
                status = deliveryStatus,
                onDismiss = {
                    AlertSettings.dismissFullScreenSetupCard(context)
                    setupCardDismissed = true
                },
                onOpenSettings = onOpenAlertDelivery,
            )
        }

        // Hero: one dominant element, sitting in open space. Fed by the
        // on-device flash flood risk engine - locally computed, or adopted from
        // a nearby device over the mesh when this one has no connectivity.
        Spacer(Modifier.height(Dimens.heroSpacing))
        val assessment = riskState.viewing
        if (assessment != null) {
            RiskGauge(
                level = assessment.severity,
                location = assessment.headline,
                fraction = assessment.gaugeFraction(),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(Dimens.cardSpacing))
            // Provenance sits directly under the gauge, never a tap away: a user
            // in an emergency has to know whether this phone measured it or
            // whether it arrived second-hand across the mesh.
            Text(
                provenanceLabel(assessment, now),
                style = AppTypography.bodySmall,
                color = MaterialTheme.appExtraColors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (riskState.sharingDeviceCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (riskState.sharingDeviceCount == 1) {
                        "1 nearby device sharing risk data"
                    } else {
                        "${riskState.sharingDeviceCount} nearby devices sharing risk data"
                    },
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.appExtraColors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            RiskGaugeEmpty(
                caption = riskState.statusMessage ?: "Assessing local flood risk...",
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        // Secondary metrics: small, muted, no chrome.
        Spacer(Modifier.height(Dimens.heroSpacing))
        Row(modifier = Modifier.fillMaxWidth()) {
            MetricStat("$incidentCount", "Incidents", Modifier.weight(1f))
            MetricStat("$peerCount", "Peers", Modifier.weight(1f))
            MetricStat("${carryingStats.totalCount}", "Carried", Modifier.weight(1f))
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))
        SectionHeading("Quick actions")
        Spacer(Modifier.height(Dimens.cardSpacing))

        ListRow(
            icon = Icons.Filled.WarningAmber,
            title = "Flood risk detail",
            subtitle = assessment?.let { "Score ${it.score}/100 - see how it was calculated" }
                ?: "See how local flood risk is assessed",
            onClick = onRiskDetail,
        )
        ListRow(
            icon = Icons.Filled.Error,
            title = "Report an incident",
            subtitle = "Submit an emergency report",
            onClick = onReportIncident,
        )
        if (role.satisfies(UserRole.OPERATOR)) {
            ListRow(
                icon = Icons.Filled.Groups,
                title = "Operator dashboard",
                subtitle = "Validate incident reports",
                onClick = onOperatorDashboard,
            )
        }
        ListRow(
            icon = Icons.Filled.Shield,
            title = "Public crisis view",
            subtitle = "View confirmed incidents",
            onClick = onPublicView,
        )
        if (role.satisfies(UserRole.ADMIN)) {
            ListRow(
                icon = Icons.Filled.Settings,
                title = "System administration",
                subtitle = "Manage users and settings",
                onClick = onAdminPanel,
            )
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))
        SectionHeading("Recent critical alerts")
        Spacer(Modifier.height(Dimens.cardSpacing))
        if (recentAlerts.isEmpty()) {
            Text(
                "No critical alerts have been received over the mesh.",
                style = AppTypography.bodySmall,
                color = MaterialTheme.appExtraColors.textTertiary,
            )
        } else {
            recentAlerts.forEachIndexed { index, alert ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                AlertRow(alert.title, alert.timeAgo, alert.severity)
            }
        }

        // The mesh is the only subsystem this app can actually observe, so it is
        // the only one reported here. The previous block asserted "System -
        // Operational - Online" and "SMS Gateway - Connected - Active"; nothing
        // checked either, and there is no SMS gateway in this codebase at all.
        Spacer(Modifier.height(Dimens.sectionSpacing))
        SectionHeading("System status")
        Spacer(Modifier.height(4.dp))
        StatusRow(
            name = "Mesh network",
            detail = when {
                !meshRunning -> "Not started - open Mesh Channels to enable it"
                peerCount == 0 -> "Running, no devices in range"
                peerCount == 1 -> "Running, 1 device in range"
                else -> "Running, $peerCount devices in range"
            },
            pillText = if (meshRunning) "Online" else "Offline",
            pillColor = if (meshRunning) {
                MaterialTheme.appExtraColors.statusOnline
            } else {
                MaterialTheme.appExtraColors.textTertiary
            },
        )
        StatusRow(
            name = "Carrying for relay",
            detail = "${carryingStats.totalCount} message(s), ${carryingStats.sosCount} SOS",
            pillText = if (carryingStats.totalCount > 0) "Active" else "Empty",
            pillColor = if (carryingStats.totalCount > 0) {
                MaterialTheme.appExtraColors.statusOnline
            } else {
                MaterialTheme.appExtraColors.textTertiary
            },
        )

        Spacer(Modifier.height(Dimens.sectionSpacing))
    }
}

/**
 * The numbers behind PublicCrisisScreen's metric row.
 *
 * Extracted from the composable so the counting rules are testable - the bug
 * this replaces was arithmetic, not layout.
 *
 * Note [criticalOrEvacuate] and [high] do NOT sum to [active]: LOW and MEDIUM
 * incidents are active but appear in no severity tile. The row is a highlight of
 * the severe end, not a partition of the list. What must hold is that the
 * severity tiles are disjoint and together cover everything at or above
 * [com.waveq.app.ui.components.NOTABLE_THRESHOLD] - see SeverityEscalationTest.
 */
data class CrisisMetrics(
    val active: Int,
    val criticalOrEvacuate: Int,
    val high: Int,
) {
    /** Everything severe enough to be broken out into its own tile. */
    val notable: Int get() = criticalOrEvacuate + high
}

fun crisisMetricsFor(confirmed: List<Incident>): CrisisMetrics = CrisisMetrics(
    active = confirmed.size,
    criticalOrEvacuate = confirmed.count { it.severity.soundsAlarm },
    high = confirmed.count { it.severity == Severity.HIGH },
)

private const val MAX_RECENT_ALERTS = 3

/** One row of the "Recent critical alerts" section, built from a real mesh FLOOD_ALERT. */
private data class RecentAlert(val title: String, val timeAgo: String, val severity: Severity)

/**
 * One-time (dismissible) nudge shown when Android 14+ has denied this app's
 * full-screen-intent permission - without it, CRITICAL alerts degrade to a
 * heads-up notification instead of the full-screen takeover.
 */
@Composable
private fun FullScreenAlertSetupCard(
    status: AlertDeliveryStatus,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val severityColors = Severity.HIGH.colors()
    AppCard(modifier = Modifier.fillMaxWidth(), background = severityColors.bg) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = severityColors.fg,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (status.hasNoDismissalSurface) {
                        "Critical alerts cannot reach you"
                    } else {
                        "Alert delivery is not fully set up"
                    },
                    style = AppTypography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    status.hasNoDismissalSurface ->
                        "Notifications are blocked and full-screen alerts are unavailable, so " +
                            "there is nowhere for a critical alert to appear."
                    !status.notificationsGranted ->
                        "Notifications are blocked, so an alert has no on-screen controls."
                    !status.fullScreenGranted ->
                        "CRITICAL alerts will show as a notification rather than taking over the screen."
                    else ->
                        "Battery optimisation may delay or stop background alerts."
                },
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Dimens.cardSpacing))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.cardSpacing)) {
                TextButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                    Text("Review", style = AppTypography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Dismiss", style = AppTypography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun fullScreenIntentSettingsIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }

@Composable
fun ReportIncidentScreen(onReportDisaster: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Dimens.heroSpacing))
        AlertLogo(size = 56.dp)
        Spacer(Modifier.height(Dimens.sectionSpacing))
        Text(
            "Report a disaster",
            style = AppTypography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Help emergency services respond quickly by reporting incidents in your area.",
            style = AppTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Dimens.heroSpacing))
        PrimaryButton(
            text = "Report disaster",
            onClick = onReportDisaster,
            leadingIcon = Icons.Filled.Error,
        )

        Spacer(Modifier.height(Dimens.sectionSpacing))
        InfoLine("Quick access", "Large, easy-to-tap controls designed for emergencies.")
        InfoLine("Works offline", "Reaches nearby devices over the mesh with no network.")
        Spacer(Modifier.height(Dimens.sectionSpacing))
    }
}

@Composable
private fun InfoLine(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, style = AppTypography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(body, style = AppTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * [incidents] is the full incident list; this screen shows only the verified
 * subset, because that is exactly what its copy promises. The counts used to be
 * the hardcoded strings "4"/"2"/"1", which happened to match the old sample data
 * and matched nothing once real reports started arriving.
 *
 * Nothing in the app verifies a report yet, so this legitimately reads zero
 * until an operator verification flow exists. That is the honest answer for a
 * screen that says "validated by emergency operators", and it is why the empty
 * state explains itself rather than just showing 0.
 */
@Composable
fun PublicCrisisScreen(incidents: List<Incident>, emergencyNumber: String = "112") {
    // Confirmed only, and dismissed reports can never reach here even if a
    // future change flips `verified` by mistake.
    val confirmed = remember(incidents) { incidents.filter { it.verified && !it.isRejected } }
    val metrics = remember(confirmed) { crisisMetricsFor(confirmed) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding),
    ) {
        Spacer(Modifier.height(Dimens.cardSpacing))
        Text("Public crisis view", style = AppTypography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            "Confirmed incidents only. For emergency assistance, call $emergencyNumber.",
            style = AppTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Dimens.heroSpacing))
        Row(modifier = Modifier.fillMaxWidth()) {
            MetricStat("${metrics.active}", "Active", Modifier.weight(1f))
            // Label says "Critical / Evacuate" because that is what it counts.
            // EVACUATE ranks above CRITICAL, so an equality check left the most
            // severe incidents in no tile at all.
            MetricStat(
                "${metrics.criticalOrEvacuate}",
                "Critical / Evacuate",
                Modifier.weight(1f),
                valueColor = MaterialTheme.appExtraColors.severityCritical,
            )
            MetricStat("${metrics.high}", "High", Modifier.weight(1f), valueColor = MaterialTheme.appExtraColors.severityHigh)
        }

        if (confirmed.isEmpty()) {
            Spacer(Modifier.height(Dimens.cardSpacing))
            Text(
                when {
                    incidents.isEmpty() -> "No incidents have been reported yet."
                    incidents.count { it.isPending } > 0 ->
                        "${incidents.count { it.isPending }} report(s) are waiting for an " +
                            "operator to confirm them. Only confirmed reports appear here."
                    else -> "No reports have been confirmed by an operator."
                },
                style = AppTypography.bodySmall,
                color = MaterialTheme.appExtraColors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))
        SectionHeading("Crisis map")
        Spacer(Modifier.height(Dimens.cardSpacing))
        IncidentMap(incidents = confirmed)

        Spacer(Modifier.height(Dimens.sectionSpacing))
        NoticeCard(
            icon = Icons.Filled.Info,
            title = "Verified incidents only",
            body = "This view displays only incidents validated by emergency operators.",
        )
        Spacer(Modifier.height(Dimens.sectionSpacing))
    }
}
