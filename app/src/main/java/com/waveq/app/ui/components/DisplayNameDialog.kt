package com.waveq.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.waveq.app.settings.MAX_DISPLAY_NAME_LENGTH
import com.waveq.app.ui.theme.AppTypography
import com.waveq.app.ui.theme.Dimens
import com.waveq.app.ui.theme.appExtraColors

/**
 * Asks for the name this device announces on the mesh.
 *
 * [dismissible] is false on first launch: every message and every SOS beacon
 * carries this name, and messages were going out as "Anonymous" because nothing
 * ever asked. When the user is editing an existing name it becomes cancellable.
 */
@Composable
fun DisplayNameDialog(
    initialValue: String,
    dismissible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    val trimmed = value.trim()
    val isValid = trimmed.isNotBlank()

    AlertDialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        title = { Text(if (dismissible) "Change display name" else "Choose a display name") },
        text = {
            Column {
                Text(
                    "This is the name shown to other devices on the mesh - on your messages, " +
                        "and on an SOS beacon if you ever send one. Use something people nearby " +
                        "would recognise.",
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.cardSpacing))
                LabeledField(
                    label = "Display name",
                    value = value,
                    onValueChange = { if (it.length <= MAX_DISPLAY_NAME_LENGTH) value = it },
                    placeholder = "e.g. Priya, or Flat 4B",
                )
                if (!isValid) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "A name is required before you can send anything.",
                        style = AppTypography.bodySmall,
                        color = MaterialTheme.appExtraColors.textTertiary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = isValid) { Text("Save") }
        },
        dismissButton = {
            if (dismissible) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
