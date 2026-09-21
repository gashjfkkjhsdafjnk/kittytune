package com.alananasss.kittytune.ui.dj

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.alananasss.kittytune.R
import com.alananasss.kittytune.data.local.PlayerPreferences
import com.alananasss.kittytune.ui.player.PlayerViewModel
import com.alananasss.kittytune.ui.player.RemixDeckPanel

/**
 * The deck: what is playing, what is coming, and how hard the engine is working.
 *
 * A category of its own rather than settings spread through the app, because the things it turns
 * on only make sense together - reworking a track but cutting hard on a skip is neither a player
 * nor a deck. One switch decides which of the two the app is being.
 *
 * It shows its own state rather than only offering controls. Whether a grid was found for the
 * playing track decides whether any of this does anything, and a listener who cannot see that is
 * left guessing why the deck sometimes behaves like a plain player.
 */
@Composable
fun DjScreen(viewModel: PlayerViewModel) {
    val context = LocalContext.current
    val prefs = remember { PlayerPreferences(context) }

    var djOn by remember { mutableStateOf(prefs.getDjMode()) }
    var intensity by remember { mutableFloatStateOf(prefs.getRemixRework().takeIf { it > 0f } ?: 0.4f) }

    val track = viewModel.currentTrack
    val next = viewModel.queue.getOrNull(viewModel.currentQueueIndex + 1)
    val outBpm = viewModel.remixGridBpmPublic

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 24.dp, bottom = 180.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Album, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Column {
                Text(
                    stringResource(R.string.dj_headline),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.dj_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(if (djOn) R.string.dj_on else R.string.dj_off),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Switch(
                        checked = djOn,
                        onCheckedChange = {
                            djOn = it
                            prefs.setDjMode(it)
                            // The intensity follows the switch, so turning the deck on does
                            // something on its own rather than needing a second control found.
                            prefs.setRemixRework(if (it) intensity else 0f)
                        },
                    )
                }

                if (djOn) {
                    Text(
                        stringResource(R.string.dj_intensity),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    Slider(
                        value = intensity,
                        onValueChange = { intensity = it; prefs.setRemixRework(it) },
                        valueRange = 0.1f..1f,
                    )
                }
            }
        }

        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    stringResource(R.string.dj_now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )
                if (track == null) {
                    Text(
                        stringResource(R.string.dj_no_track),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else {
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        ) {
                            AsyncImage(track.artworkUrl, null, modifier = Modifier.size(56.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                track.title.orEmpty(),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                track.displayArtist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (outBpm > 0f) {
                            Text(
                                "%.0f".format(outBpm),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }

                    // Without a grid nothing here does anything, so it says so instead of
                    // leaving the listener to wonder why the deck behaves like a plain player.
                    if (outBpm <= 0f) {
                        Text(
                            stringResource(
                                if (djOn) R.string.dj_grid_waiting else R.string.dj_grid_none
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }

        RemixDeckPanel(
            nextTitle = next?.title,
            nextArtist = next?.displayArtist,
            nextArtwork = next?.artworkUrl,
            outBpm = outBpm.takeIf { it > 0f },
            inBpm = viewModel.nextDeckBpm,
            inKey = viewModel.nextDeckKey,
            mixInSeconds = null,
            skipInMs = viewModel.mixedSkipInMs,
            earlyEntry = viewModel.earlyEntryActive,
            textColor = MaterialTheme.colorScheme.onSurface,
        )
    }
}
