package com.alananasss.kittytune.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alananasss.kittytune.R
import com.alananasss.kittytune.data.remix.RemixCapabilities
import com.alananasss.kittytune.data.remix.RemixIntentMode
import com.alananasss.kittytune.data.remix.RemixModeAvailability

/**
 * Asked once, before the first mix: how should the request be read?
 *
 * Shown rather than decided silently because the modes differ in what they cost the listener -
 * a download, memory, battery - and that is not a trade the app should make on their behalf. The
 * device is inspected first so the question is answered with what this phone can actually do,
 * and the recommendation is preselected so anyone who does not care can simply confirm.
 */
@Composable
fun RemixModeDialog(
    report: RemixCapabilities.Report,
    onDismiss: () -> Unit,
    onConfirm: (RemixIntentMode) -> Unit,
) {
    var selected by remember { mutableStateOf(report.recommended) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remix_mode_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        R.string.remix_mode_device,
                        report.deviceName,
                        "%.1f".format(report.totalRamGb),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                RemixIntentMode.entries.forEach { mode ->
                    val availability = report.availability[mode] ?: RemixModeAvailability.DEVICE_TOO_SMALL
                    val usable = availability == RemixModeAvailability.READY
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = usable) { selected = mode }
                            .padding(vertical = 6.dp),
                    ) {
                        RadioButton(
                            selected = selected == mode,
                            onClick = { selected = mode },
                            enabled = usable,
                        )
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(
                                text = stringResource(mode.titleRes()),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (usable) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                text = stringResource(mode.subtitleRes()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // The reason is spelled out rather than implied by a greyed row: "your
                            // phone cannot" and "this is not built yet" are different answers, and
                            // only one of them is about the listener's device.
                            if (!usable) {
                                Text(
                                    text = stringResource(availability.reasonRes()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.remix_mode_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

private fun RemixIntentMode.titleRes(): Int = when (this) {
    RemixIntentMode.KEYWORD -> R.string.remix_mode_keyword
    RemixIntentMode.EMBEDDING -> R.string.remix_mode_embedding
    RemixIntentMode.LANGUAGE_MODEL -> R.string.remix_mode_llm
}

private fun RemixIntentMode.subtitleRes(): Int = when (this) {
    RemixIntentMode.KEYWORD -> R.string.remix_mode_keyword_desc
    RemixIntentMode.EMBEDDING -> R.string.remix_mode_embedding_desc
    RemixIntentMode.LANGUAGE_MODEL -> R.string.remix_mode_llm_desc
}

private fun RemixModeAvailability.reasonRes(): Int = when (this) {
    RemixModeAvailability.READY -> R.string.remix_mode_keyword
    RemixModeAvailability.NEEDS_MODEL -> R.string.remix_mode_needs_model
    RemixModeAvailability.DEVICE_TOO_SMALL -> R.string.remix_mode_device_too_small
}
