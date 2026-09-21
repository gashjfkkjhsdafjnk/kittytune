package com.alananasss.kittytune.ui.player.djmode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.airbnb.lottie.compose.rememberLottieComposition
import com.alananasss.kittytune.R
import com.alananasss.kittytune.audio.automix.DjEngine
import com.alananasss.kittytune.audio.automix.DjSessionController
import com.alananasss.kittytune.ui.player.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Fun, low-stakes "DJ mode": the app mascot dances along with the detected
 * beat grid of the currently playing track (falls back to a steady estimated
 * tempo when no real analysis is available yet, e.g. unsupported source).
 */
@Composable
fun DjModeScreen(viewModel: PlayerViewModel, onClose: () -> Unit) {
    val track = viewModel.currentTrack

    val beatInfo by DjSessionController.currentBeatInfo.collectAsStateWithLifecycle()
    val loopState by DjSessionController.loopState.collectAsStateWithLifecycle()
    val suggestions by DjSessionController.suggestions.collectAsStateWithLifecycle()
    val isEstimated = (beatInfo?.confidence ?: 1f) <= 0f
    var pulseTick by remember(track?.id) { mutableIntStateOf(0) }

    LaunchedEffect(beatInfo?.bpm, viewModel.isPlaying, track?.id) {
        val info = beatInfo
        if (info == null || info.bpm <= 0f) return@LaunchedEffect
        val periodMs = (60_000f / info.bpm).toLong().coerceAtLeast(200L)
        var nextBeatAt = run {
            val pos = viewModel.currentPosition
            val k = ((pos - info.firstBeatOffsetMs).coerceAtLeast(0L) / periodMs) + 1
            info.firstBeatOffsetMs + k * periodMs
        }
        while (isActive && viewModel.isPlaying) {
            val waitMs = nextBeatAt - viewModel.currentPosition
            if (waitMs > 30L) {
                delay(waitMs.coerceAtMost(periodMs))
            } else {
                pulseTick++
                nextBeatAt += periodMs
                delay(20L)
            }
        }
    }

    val idleComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.dj_cat_idle))
    val danceComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.dj_cat_dance))
    val danceAnimatable = rememberLottieAnimatable()

    LaunchedEffect(pulseTick) {
        if (pulseTick == 0) return@LaunchedEffect
        val comp = danceComposition ?: return@LaunchedEffect
        danceAnimatable.snapTo(composition = comp, progress = 0f)
        danceAnimatable.animate(composition = comp, iterations = 1, speed = 1f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val artworkUrl = track?.fullResArtwork
        if (!artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(80.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.55f),
                        0.5f to Color.Black.copy(alpha = 0.35f),
                        1.0f to Color.Black.copy(alpha = 0.75f)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.btn_close), tint = Color.White)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.dj_mode_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.size(48.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (!artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.42f))
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray))
                }

                if (danceAnimatable.isPlaying) {
                    LottieAnimation(
                        composition = danceComposition,
                        progress = { danceAnimatable.value },
                        modifier = Modifier.size(160.dp)
                    )
                } else {
                    LottieAnimation(
                        composition = idleComposition,
                        iterations = LottieConstants.IterateForever,
                        modifier = Modifier.size(160.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = track?.title ?: stringResource(R.string.player_playing_now),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track?.user?.username ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            val bpmLabel = beatInfo?.bpm?.let { bpm ->
                val rounded = bpm.toInt()
                if (isEstimated) {
                    stringResource(R.string.dj_mode_bpm_estimated, rounded)
                } else {
                    stringResource(R.string.dj_mode_bpm, rounded)
                }
            } ?: stringResource(R.string.dj_mode_analyzing)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = bpmLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            LoopControlsRow(
                loopState = loopState,
                loopEnabled = beatInfo != null,
            )

            Spacer(modifier = Modifier.height(14.dp))

            SuggestionsSection(
                suggestions = suggestions,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.playPrevious() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Spacer(modifier = Modifier.size(24.dp))
                IconButton(
                    onClick = { viewModel.togglePlayPause() },
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                ) {
                    Icon(
                        imageVector = if (viewModel.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(modifier = Modifier.size(24.dp))
                Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    IconButton(
                        onClick = { viewModel.djMixToNext() },
                        enabled = !viewModel.isDjMixingToNext,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            null,
                            tint = if (viewModel.isDjMixingToNext) Color.White.copy(alpha = 0.35f) else Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    if (viewModel.isDjMixingToNext) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(44.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoopControlsRow(loopState: DjSessionController.LoopState, loopEnabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DjChip(
            label = if (loopState.active) {
                stringResource(R.string.dj_mode_loop_bars, loopState.bars)
            } else {
                stringResource(R.string.dj_mode_loop)
            },
            icon = if (loopState.active) Icons.Rounded.Stop else Icons.Rounded.Repeat,
            highlighted = loopState.active,
            enabled = loopEnabled,
            onClick = { DjSessionController.toggleLoop() },
        )
        if (loopState.active) {
            DjChip(
                label = "½",
                enabled = loopState.bars > 1,
                onClick = { DjSessionController.halveLoop() },
            )
            DjChip(
                label = "×2",
                enabled = loopState.bars < 32,
                onClick = { DjSessionController.doubleLoop() },
            )
        }
    }
}

@Composable
private fun DjChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (highlighted) Color.Black else Color.White.copy(alpha = if (enabled) 0.9f else 0.4f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.size(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (highlighted) Color.Black else Color.White.copy(alpha = if (enabled) 0.9f else 0.4f),
        )
    }
}

@Composable
private fun SuggestionsSection(suggestions: List<DjEngine.DjSuggestion>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.dj_mode_next_up),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (suggestions.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.dj_mode_suggestions_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                items(suggestions, key = { it.track.id }) { suggestion ->
                    SuggestionCard(suggestion)
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(suggestion: DjEngine.DjSuggestion) {
    val track = suggestion.track
    Column(
        modifier = Modifier
            .width(150.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .clickable { DjSessionController.mixIn(track) }
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .clip(RoundedCornerShape(10.dp))
        ) {
            val art = track.fullResArtwork
            if (art.isNotBlank()) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = track.title ?: "",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = track.displayArtist,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (suggestion.fromQueue) {
                stringResource(R.string.dj_mode_in_queue, suggestion.queuePosition ?: 0)
            } else {
                stringResource(R.string.dj_mode_from_favorites)
            },
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF8BE28B),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = suggestionReasonText(suggestion),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun suggestionReasonText(suggestion: DjEngine.DjSuggestion): String {
    val bpmInt = suggestion.bpm.toInt()
    val parts = ArrayList<String>(3)
    parts += when {
        suggestion.tempoScore > 0.9f -> stringResource(R.string.dj_mode_reason_tempo_perfect, bpmInt)
        suggestion.tempoScore > 0.6f -> stringResource(R.string.dj_mode_reason_tempo_close, bpmInt)
        else -> stringResource(R.string.dj_mode_reason_tempo_plain, bpmInt)
    }
    val camelot = suggestion.camelot
    if (camelot != null) {
        val code = camelot.toString()
        parts += when {
            suggestion.keyScore >= 0.9f -> stringResource(R.string.dj_mode_reason_key_perfect, code)
            suggestion.keyScore >= 0.75f -> stringResource(R.string.dj_mode_reason_key_compatible, code)
            suggestion.keyScore >= 0.5f -> stringResource(R.string.dj_mode_reason_key_energy, code)
            else -> stringResource(R.string.dj_mode_reason_key_plain, code)
        }
    }
    if (suggestion.genreMatch) parts += stringResource(R.string.dj_mode_reason_same_genre)
    return parts.joinToString(" · ")
}
