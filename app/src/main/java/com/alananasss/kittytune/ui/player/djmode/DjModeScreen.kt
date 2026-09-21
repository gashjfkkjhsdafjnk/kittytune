package com.alananasss.kittytune.ui.player.djmode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.airbnb.lottie.compose.rememberLottieComposition
import com.alananasss.kittytune.R
import com.alananasss.kittytune.audio.automix.AutomixManager
import com.alananasss.kittytune.audio.automix.BeatAnalysisPriority
import com.alananasss.kittytune.data.local.AppDatabase
import com.alananasss.kittytune.data.local.BeatInfoEntity
import com.alananasss.kittytune.ui.player.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Fun, low-stakes "DJ mode": the app mascot dances along with the detected
 * beat grid of the currently playing track (falls back to a steady estimated
 * tempo when no real analysis is available yet, e.g. unsupported source).
 */
@Composable
fun DjModeScreen(viewModel: PlayerViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val track = viewModel.currentTrack

    var beatInfo by remember(track?.id) { mutableStateOf<BeatInfoEntity?>(null) }
    var isEstimated by remember(track?.id) { mutableStateOf(false) }
    var pulseTick by remember(track?.id) { mutableIntStateOf(0) }

    LaunchedEffect(track?.id) {
        val t = track ?: return@LaunchedEffect
        val db = AppDatabase.getDatabase(context)
        val songId = t.id.toString()
        var attempts = 0
        while (isActive && attempts < 25) {
            val cached = withContext(Dispatchers.IO) { db.beatInfoDao().getBeatInfo(songId) }
            if (cached != null && cached.bpm > 0f) {
                beatInfo = cached
                isEstimated = false
                return@LaunchedEffect
            }
            AutomixManager.maybeAnalyzeBeat(t, BeatAnalysisPriority.IMMEDIATE)
            attempts++
            delay(1000)
        }
        // No real analysis available (unsupported source, timed out, etc.) —
        // keep the cat moving with a plausible default so DJ mode never just
        // sits there frozen.
        if (isActive && beatInfo == null) {
            beatInfo = BeatInfoEntity(songId = songId, bpm = 96f, firstBeatOffsetMs = 0L, confidence = 0f)
            isEstimated = true
        }
    }

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

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .size(280.dp)
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
                        modifier = Modifier.size(220.dp)
                    )
                } else {
                    LottieAnimation(
                        composition = idleComposition,
                        iterations = LottieConstants.IterateForever,
                        modifier = Modifier.size(220.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

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

            Spacer(modifier = Modifier.weight(1f))

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
                IconButton(onClick = { viewModel.playNext(manual = true) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}
