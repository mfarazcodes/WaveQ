package com.waveq.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.waveq.app.sensor.SensorStatus
import com.waveq.app.ui.theme.AppTypography
import com.waveq.app.ui.theme.Dimens
import com.waveq.app.ui.theme.appExtraColors

/**
 * Says whether this device is the one wired to the hardware.
 *
 * "Which phone is actually connected to the sensor?" is the first question
 * anyone asks about this feature, and with several identical handsets on a table
 * it is otherwise unanswerable. Any device that can reach the probe bridges -
 * there is no elected leader - so more than one badge showing "bridge" at once
 * is correct, not a bug.
 */
@Composable
fun SensorBridgeBadge(status: SensorStatus, modifier: Modifier = Modifier) {
    val extra = MaterialTheme.appExtraColors
    val (icon, label, tint) = when {
        !status.isConfigured -> Triple(
            Icons.Filled.SensorsOff,
            "Sensor off - receiving sensor alerts via mesh only",
            extra.textTertiary,
        )
        status.isBridge -> Triple(
            Icons.Filled.Hub,
            "Sensor bridge - relaying ${status.host} to the mesh",
            extra.statusOnline,
        )
        else -> Triple(
            Icons.Filled.WaterDrop,
            "Sensor unreachable - receiving sensor alerts via mesh",
            extra.severityHigh,
        )
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.badgeRadius))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = AppTypography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
