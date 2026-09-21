package com.alananasss.kittytune.audio.automix

import android.content.Context
import com.alananasss.kittytune.data.local.AppDatabase
import com.alananasss.kittytune.data.local.BeatInfoEntity
import com.alananasss.kittytune.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/**
 * The "brain" behind DJ Mode's auto-mix suggestions: harmonic (Camelot wheel)
 * and tempo compatibility scoring, candidate pool assembly (upcoming queue +
 * the user's own favorites), and lightweight background pre-analysis so
 * suggestions keep improving the longer DJ Mode stays open.
 *
 * Pure/stateless on purpose - [DjSessionController] owns the polling loop and
 * mutable state; this object only turns data into scores.
 */
object DjEngine {

    /** Standard Camelot Wheel position. Adjacent numbers / same-number-other-letter mix cleanly. */
    data class CamelotKey(val number: Int, val letter: Char) {
        override fun toString(): String = "$number$letter"
    }

    data class DjSuggestion(
        val track: Track,
        val score: Float,
        val tempoScore: Float,
        val keyScore: Float,
        val bpm: Float,
        val camelot: CamelotKey?,
        val genreMatch: Boolean,
        val fromQueue: Boolean,
        val queuePosition: Int? = null,
    )

    // Circle-of-fifths -> Camelot number for MAJOR keys, keyed by pitch class (0=C ... 11=B).
    private val MAJOR_CAMELOT = mapOf(
        0 to 8, 7 to 9, 2 to 10, 9 to 11, 4 to 12, 11 to 1,
        6 to 2, 1 to 3, 8 to 4, 3 to 5, 10 to 6, 5 to 7,
    )

    fun toCamelot(pitchClass: Int, isMinor: Boolean): CamelotKey {
        // A minor key's relative major sits 3 semitones up; the wheel position is shared.
        val majorPc = if (isMinor) (pitchClass + 3) % 12 else pitchClass
        val number = MAJOR_CAMELOT[((majorPc % 12) + 12) % 12] ?: 1
        return CamelotKey(number, if (isMinor) 'A' else 'B')
    }

    /**
     * 1.0 = perfect harmonic match, descending as the two keys drift further
     * apart on the wheel. Mirrors the mixing rules real DJs use: same code,
     * relative major/minor, one step around the wheel, or a same-letter
     * perfect-fifth "energy" jump all mix cleanly.
     */
    fun camelotCompatibility(a: CamelotKey, b: CamelotKey): Float {
        if (a.number == b.number && a.letter == b.letter) return 1.0f
        if (a.number == b.number && a.letter != b.letter) return 0.9f
        val diff = ((a.number - b.number + 12) % 12).let { min(it, 12 - it) }
        val sameLetter = a.letter == b.letter
        if (diff == 1 && sameLetter) return 0.82f
        if (diff == 1) return 0.5f
        val fifthDiff = (a.number - b.number + 12) % 12
        if ((fifthDiff == 7 || fifthDiff == 5) && sameLetter) return 0.6f
        if (diff == 2 && sameLetter) return 0.3f
        return (0.15f - diff * 0.01f).coerceAtLeast(0f)
    }

    /**
     * 1.0 = same tempo (allowing octave folding - a 174 BPM DnB track mixes
     * fine against an 87 BPM track), decaying to 0 past ~16% drift.
     */
    fun tempoCompatibility(bpmA: Float, bpmB: Float): Float {
        if (bpmA <= 0f || bpmB <= 0f) return 0f
        var bestDiffPct = Float.MAX_VALUE
        for (mult in floatArrayOf(0.5f, 1f, 2f)) {
            val diffPct = abs(bpmB * mult - bpmA) / bpmA
            if (diffPct < bestDiffPct) bestDiffPct = diffPct
        }
        return (1f - bestDiffPct / 0.16f).coerceIn(0f, 1f)
    }

    /**
     * Combines the next few queued-up tracks with a slice of the user's own
     * favorites (their "wo er denkt, das könnte reinpassen" pool) into one
     * de-duplicated candidate list, most-recently-liked favorites first.
     */
    fun buildCandidatePool(
        queueLookahead: List<Track>,
        favorites: List<Track>,
        excludeIds: Set<Long>,
        maxFavorites: Int = 30,
    ): List<Track> {
        val seen = HashSet<Long>(excludeIds)
        val pool = ArrayList<Track>(queueLookahead.size + maxFavorites)
        for (t in queueLookahead) {
            if (seen.add(t.id)) pool.add(t)
        }
        var favCount = 0
        for (t in favorites) {
            if (favCount >= maxFavorites) break
            if (seen.add(t.id)) {
                pool.add(t)
                favCount++
            }
        }
        return pool
    }

    /** Kicks off (idempotent, throttled by AutomixManager itself) background analysis for candidates that still lack a beat grid. */
    suspend fun warmUpAnalysis(context: Context, pool: List<Track>, limit: Int = 6) {
        if (pool.isEmpty()) return
        val db = AppDatabase.getDatabase(context)
        val ids = pool.map { it.id.toString() }
        val known = withContext(Dispatchers.IO) { db.beatInfoDao().getBeatInfoForSongs(ids) }
            .associateBy { it.songId }
        var kicked = 0
        for (t in pool) {
            if (kicked >= limit) break
            val existing = known[t.id.toString()]
            if (existing == null) {
                AutomixManager.maybeAnalyzeBeat(t, BeatAnalysisPriority.LOOKAHEAD)
                kicked++
            }
        }
    }

    /**
     * Scores every candidate against the currently playing track and returns
     * the best matches, highest first. Candidates with no beat grid yet are
     * skipped for this pass (they'll appear once [warmUpAnalysis] finishes
     * analyzing them on a later tick).
     */
    suspend fun rankCandidates(
        context: Context,
        currentTrack: Track,
        currentBeat: BeatInfoEntity,
        pool: List<Track>,
        queueLookahead: List<Track>,
        maxResults: Int = 6,
    ): List<DjSuggestion> {
        if (pool.isEmpty() || currentBeat.bpm <= 0f) return emptyList()
        val db = AppDatabase.getDatabase(context)
        val ids = pool.map { it.id.toString() }
        val beatById = withContext(Dispatchers.IO) { db.beatInfoDao().getBeatInfoForSongs(ids) }
            .associateBy { it.songId }
        val queuePositions = queueLookahead.withIndex().associate { (i, t) -> t.id to (i + 1) }

        val currentCamelot = currentBeat.keyPitchClass?.let { pc ->
            toCamelot(pc, currentBeat.keyIsMinor == true)
        }

        val results = ArrayList<DjSuggestion>(pool.size)
        for (t in pool) {
            val beat = beatById[t.id.toString()] ?: continue
            if (beat.bpm <= 0f || beat.confidence < 0.25f) continue

            val tempoScore = tempoCompatibility(currentBeat.bpm, beat.bpm)
            val candidateCamelot = beat.keyPitchClass?.let { pc -> toCamelot(pc, beat.keyIsMinor == true) }
            val keyScore = if (currentCamelot != null && candidateCamelot != null) {
                camelotCompatibility(currentCamelot, candidateCamelot)
            } else {
                0.5f // unknown key: neutral, don't punish or reward
            }

            val genreMatch = !currentTrack.genre.isNullOrBlank() && currentTrack.genre == t.genre
            val genreBonus = if (genreMatch) 0.06f else 0f
            val popularityBonus = (min(t.likesCount, 50_000) / 50_000f) * 0.04f

            val total = (tempoScore * 0.5f + keyScore * 0.36f + genreBonus + popularityBonus).coerceIn(0f, 1f)

            results += DjSuggestion(
                track = t,
                score = total,
                tempoScore = tempoScore,
                keyScore = keyScore,
                bpm = beat.bpm,
                camelot = candidateCamelot,
                genreMatch = genreMatch,
                fromQueue = queuePositions.containsKey(t.id),
                queuePosition = queuePositions[t.id],
            )
        }

        return results.sortedByDescending { it.score }.take(maxResults)
    }
}
