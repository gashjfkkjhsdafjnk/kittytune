package com.alananasss.kittytune.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.alananasss.kittytune.R

/**
 * What a deck shows and a player does not: where the mix is going.
 *
 * A listener who can see the next track, its tempo and its key can tell whether the transition
 * about to happen will work - which is the difference between trusting the mix and being
 * surprised by it. The numbers are the ones the engine is actually deciding on, not decoration.
 */
@Composable
fun RemixDeckPanel(
    nextTitle: String?,
    nextArtist: String?,
    nextArtwork: String?,
    outBpm: Float?,
    inBpm: Float?,
    inKey: String?,
    mixInSeconds: Int?,
    skipInMs: Long?,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = nextTitle != null, modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = textColor.copy(alpha = 0.08f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.remix_deck_next),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    )
                    // The skip's countdown takes the slot when one is queued: while the listener
                    // is waiting for a press to happen, that is the only number that matters.
                    val status = when {
                        skipInMs != null -> stringResource(R.string.remix_deck_skip_in, (skipInMs / 100) / 10f)
                        mixInSeconds != null -> stringResource(R.string.remix_deck_mix_in, mixInSeconds)
                        else -> null
                    }
                    if (status != null) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(textColor.copy(alpha = 0.1f))
                    ) {
                        if (!nextArtwork.isNullOrBlank()) {
                            AsyncImage(
                                model = nextArtwork,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = nextTitle.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = nextArtist.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        // Both tempos, not just the incoming one: the gap between them is what
                        // says whether this transition is a nudge or a stretch.
                        if (outBpm != null && inBpm != null) {
                            Text(
                                text = "%.0f → %.0f".format(outBpm, inBpm),
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = textColor,
                            )
                        }
                        if (inKey != null) {
                            Text(
                                text = inKey,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = textColor.copy(alpha = 0.7f),
                            )
                        }
                    }
                }

                if (skipInMs != null) {
                    val barMs = 2_000f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(textColor.copy(alpha = 0.15f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((1f - (skipInMs / barMs)).coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(textColor)
                        )
                    }
                }
            }
        }
    }
}
