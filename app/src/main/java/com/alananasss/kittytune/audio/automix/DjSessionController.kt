package com.alananasss.kittytune.audio.automix

import android.content.Context
import android.util.Log
import com.alananasss.kittytune.data.LikeRepository
import com.alananasss.kittytune.data.MusicManager
import com.alananasss.kittytune.data.local.AppDatabase
import com.alananasss.kittytune.data.local.BeatInfoEntity
import com.alananasss.kittytune.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the live "DJ Mode" session: keeps the current track's beat grid warm,
 * drives the beat-quantized bar-loop ("Takte wiederholen"), and continuously
 * re-ranks what to mix in next from the upcoming queue plus the user's own
 * favorites via [DjEngine]. Started when DJ Mode opens, stopped when it closes.
 *
 * A singleton (like [AutomixManager]) since there is only ever one active
 * player/session in this app.
 */
object DjSessionController {

    private const val TAG = "DjSessionController"
    private const val BEATS_PER_BAR = 4 // 4/4 time signature assumption - true for the vast majority of DJ-mixed genres

    data class LoopState(
        val active: Boolean = false,
        val bars: Int = 4,
        val startMs: Long = 0L,
        val endMs: Long = 0L,
    )

    private val _loopState = MutableStateFlow(LoopState())
    val loopState = _loopState.asStateFlow()

    private val _currentBeatInfo = MutableStateFlow<BeatInfoEntity?>(null)
    val currentBeatInfo = _currentBeatInfo.asStateFlow()

    private val _suggestions = MutableStateFlow<List<DjEngine.DjSuggestion>>(emptyList())
    val suggestions = _suggestions.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing = _isAnalyzing.asStateFlow()

    // beatInfoLoop() and suggestionsLoop() run as separate coroutines on Dispatchers.Default
    // (potentially different threads), so this needs to be safe for concurrent add/read.
    private val sessionPlayedIds: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private var scope: CoroutineScope? = null
    private var appContext: Context? = null
    private var lookaheadFn: (() -> List<Track>)? = null
    private var onMixIn: ((Track) -> Unit)? = null

    fun start(context: Context, queueLookahead: () -> List<Track>, onMixIn: (Track) -> Unit) {
        stop()
        appContext = context.applicationContext
        lookaheadFn = queueLookahead
        this.onMixIn = onMixIn
        sessionPlayedIds.clear()
        MusicManager.currentTrack?.id?.let { sessionPlayedIds.add(it) }

        val s = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = s
        s.launch { beatInfoLoop() }
        s.launch { loopTickerLoop() }
        s.launch { suggestionsLoop() }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        lookaheadFn = null
        onMixIn = null
        _loopState.value = LoopState()
        _suggestions.value = emptyList()
        _currentBeatInfo.value = null
        _isAnalyzing.value = false
    }

    // ───────────────────────── Beat grid tracking ─────────────────────────

    private suspend fun beatInfoLoop() {
        var lastTrackId: Long? = null
        var attemptsForCurrentTrack = 0
        while (true) {
            val ctx = appContext ?: return
            val t = MusicManager.currentTrack
            if (t?.id != lastTrackId) {
                lastTrackId = t?.id
                attemptsForCurrentTrack = 0
                _currentBeatInfo.value = null
                exitLoop()
                if (t != null) sessionPlayedIds.add(t.id)
            }
            if (t != null && _currentBeatInfo.value == null) {
                val db = AppDatabase.getDatabase(ctx)
                val cached = withContext(Dispatchers.IO) { db.beatInfoDao().getBeatInfo(t.id.toString()) }
                if (cached != null && cached.bpm > 0f) {
                    _currentBeatInfo.value = cached
                } else if (attemptsForCurrentTrack >= 20) {
                    // Analysis never landed (unsupported source, timed out, ...). Fall back to a
                    // plausible default grid so the loop/suggestions/cat animation still have
                    // something to work with instead of just sitting there inert. confidence=0
                    // marks it as an estimate for the UI.
                    _currentBeatInfo.value = BeatInfoEntity(
                        songId = t.id.toString(),
                        bpm = 96f,
                        firstBeatOffsetMs = 0L,
                        confidence = 0f,
                    )
                } else {
                    AutomixManager.maybeAnalyzeBeat(t, BeatAnalysisPriority.IMMEDIATE)
                    attemptsForCurrentTrack++
                }
            }
            delay(1000)
        }
    }

    // ───────────────────────── Bar loop ("Takte wiederholen") ─────────────────────────

    /** Toggles the loop on/off, quantizing the start to the nearest bar boundary under the playhead. */
    fun toggleLoop() {
        if (_loopState.value.active) {
            exitLoop()
            return
        }
        val beat = _currentBeatInfo.value ?: return
        val bars = _loopState.value.bars
        setLoopWindow(beat, bars)
    }

    fun exitLoop() {
        _loopState.value = _loopState.value.copy(active = false)
    }

    fun halveLoop() {
        val state = _loopState.value
        val newBars = (state.bars / 2).coerceAtLeast(1)
        applyBars(newBars)
    }

    fun doubleLoop() {
        val state = _loopState.value
        val newBars = (state.bars * 2).coerceAtMost(32)
        applyBars(newBars)
    }

    private fun applyBars(bars: Int) {
        val beat = _currentBeatInfo.value
        val state = _loopState.value
        if (beat == null || !state.active) {
            _loopState.value = state.copy(bars = bars)
            return
        }
        // Keep the loop's start point fixed - only its length changes, exactly like halve/double on a real deck.
        val barMs = barLengthMs(beat)
        val endMs = state.startMs + (barMs * bars).toLong()
        _loopState.value = state.copy(bars = bars, endMs = endMs)
    }

    private fun setLoopWindow(beat: BeatInfoEntity, bars: Int) {
        val periodMs = 60_000.0 / beat.bpm
        val barMs = periodMs * BEATS_PER_BAR
        val pos = try {
            MusicManager.player.currentPosition.toDouble()
        } catch (_: Exception) {
            return
        }
        val beatsFromAnchor = (pos - beat.firstBeatOffsetMs) / periodMs
        val barsFromAnchor = kotlin.math.floor(beatsFromAnchor / BEATS_PER_BAR)
        val startMs = (beat.firstBeatOffsetMs + barsFromAnchor * barMs).toLong().coerceAtLeast(0L)
        val endMs = startMs + (barMs * bars).toLong()
        _loopState.value = LoopState(active = true, bars = bars, startMs = startMs, endMs = endMs)
    }

    private fun barLengthMs(beat: BeatInfoEntity): Double = 60_000.0 / beat.bpm * BEATS_PER_BAR

    private suspend fun loopTickerLoop() {
        while (true) {
            val state = _loopState.value
            if (state.active && state.endMs > state.startMs) {
                try {
                    val pos = MusicManager.player.currentPosition
                    // Also guard against a seek elsewhere in the app landing us before the loop
                    // (e.g. user drags the scrubber) - jump back in on the next tick either way.
                    if (pos >= state.endMs || pos < state.startMs - 250) {
                        MusicManager.player.seekTo(state.startMs)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Loop seek failed: ${e.message}")
                }
                delay(30L)
            } else {
                delay(250L)
            }
        }
    }

    // ───────────────────────── Auto-mix suggestions ─────────────────────────

    private suspend fun suggestionsLoop() {
        while (true) {
            val ctx = appContext
            val t = MusicManager.currentTrack
            val beat = _currentBeatInfo.value
            val lookahead = lookaheadFn?.invoke().orEmpty()
            if (ctx != null && t != null && beat != null && beat.bpm > 0f) {
                try {
                    _isAnalyzing.value = true
                    val favorites = LikeRepository.likedTracks.value
                    val pool = DjEngine.buildCandidatePool(
                        queueLookahead = lookahead,
                        favorites = favorites,
                        excludeIds = sessionPlayedIds + t.id,
                    )
                    _suggestions.value = DjEngine.rankCandidates(ctx, t, beat, pool, lookahead)
                    DjEngine.warmUpAnalysis(ctx, pool)
                } catch (e: Exception) {
                    Log.w(TAG, "Suggestion ranking failed: ${e.message}")
                } finally {
                    _isAnalyzing.value = false
                }
            }
            delay(5000L)
        }
    }

    /** Queues [track] as the very next track and marks it played so it won't be re-suggested. */
    fun mixIn(track: Track) {
        sessionPlayedIds.add(track.id)
        onMixIn?.invoke(track)
    }
}
