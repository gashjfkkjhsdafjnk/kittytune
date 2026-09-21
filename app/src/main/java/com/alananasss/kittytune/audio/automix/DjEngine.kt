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

    /** Desired energy trajectory for [planSequence] - build up, wind down, or keep it smooth (no strong bias either way). */
    enum class EnergyDirection { BUILD, WIND_DOWN, SMOOTH }

    data class SequenceStep(
        val track: Track,
        val bpm: Float,
        val camelot: CamelotKey?,
        val energyLevel: Float?,
        val transitionScore: Float,
        val energyFitScore: Float,
        val combinedScore: Float,
        val fromQueue: Boolean,
        val queuePosition: Int? = null,
    )

    data class SequencePlan(
        val direction: EnergyDirection,
        val steps: List<SequenceStep>,
        val score: Float,
    )

    /**
     * Looks two tracks ahead instead of judging only the very next transition: searches the
     * candidate pool for the pair of upcoming tracks whose combined tempo/key compatibility
     * *and* energy trajectory best match [direction] - e.g. for BUILD, each step should feel a
     * little more intense than the last, not just individually mix well. Pool size is capped
     * (~40 candidates), so the full pairwise search is cheap (at most ~1600 comparisons).
     */
    suspend fun planSequence(
        context: Context,
        currentTrack: Track,
        currentBeat: BeatInfoEntity,
        pool: List<Track>,
        queueLookahead: List<Track>,
        direction: EnergyDirection,
        depth: Int = 2,
    ): SequencePlan {
        if (pool.isEmpty() || currentBeat.bpm <= 0f) return SequencePlan(direction, emptyList(), 0f)
        val db = AppDatabase.getDatabase(context)
        val ids = pool.map { it.id.toString() }
        val beatById = withContext(Dispatchers.IO) { db.beatInfoDao().getBeatInfoForSongs(ids) }
            .associateBy { it.songId }
        val queuePositions = queueLookahead.withIndex().associate { (i, t) -> t.id to (i + 1) }
        val currentCamelot = currentBeat.keyPitchClass?.let { pc -> toCamelot(pc, currentBeat.keyIsMinor == true) }

        data class Candidate(val track: Track, val beat: BeatInfoEntity, val camelot: CamelotKey?)

        val candidates = pool.mapNotNull { t ->
            val b = beatById[t.id.toString()] ?: return@mapNotNull null
            if (b.bpm <= 0f || b.confidence < 0.25f) return@mapNotNull null
            Candidate(t, b, b.keyPitchClass?.let { pc -> toCamelot(pc, b.keyIsMinor == true) })
        }
        if (candidates.isEmpty()) return SequencePlan(direction, emptyList(), 0f)

        fun transitionScore(fromBpm: Float, fromCamelot: CamelotKey?, fromTrack: Track, to: Candidate): Float {
            val tempo = tempoCompatibility(fromBpm, to.beat.bpm)
            val key = if (fromCamelot != null && to.camelot != null) camelotCompatibility(fromCamelot, to.camelot) else 0.5f
            val genre = if (!fromTrack.genre.isNullOrBlank() && fromTrack.genre == to.track.genre) 0.06f else 0f
            val popularity = (min(to.track.likesCount, 50_000) / 50_000f) * 0.04f
            return (tempo * 0.5f + key * 0.36f + genre + popularity).coerceIn(0f, 1f)
        }

        fun energyFit(fromEnergy: Float?, toEnergy: Float?): Float {
            if (fromEnergy == null || toEnergy == null) return 0.5f
            val delta = toEnergy - fromEnergy
            return when (direction) {
                EnergyDirection.BUILD -> when {
                    delta in 0.02f..0.30f -> 1f
                    delta > 0.30f -> (1f - (delta - 0.30f) / 0.4f).coerceIn(0.3f, 1f)
                    delta in -0.05f..0.02f -> 0.6f
                    else -> (1f + delta * 2f).coerceIn(0f, 0.5f)
                }
                EnergyDirection.WIND_DOWN -> when {
                    delta in -0.30f..-0.02f -> 1f
                    delta < -0.30f -> (1f - (-delta - 0.30f) / 0.4f).coerceIn(0.3f, 1f)
                    delta in -0.02f..0.05f -> 0.6f
                    else -> (1f - delta * 2f).coerceIn(0f, 0.5f)
                }
                EnergyDirection.SMOOTH -> (1f - abs(delta) / 0.25f).coerceIn(0f, 1f)
            }
        }

        fun step(fromBpm: Float, fromCamelot: CamelotKey?, fromTrack: Track, fromEnergy: Float?, to: Candidate): SequenceStep {
            val transition = transitionScore(fromBpm, fromCamelot, fromTrack, to)
            val energy = energyFit(fromEnergy, to.beat.energyLevel)
            return SequenceStep(
                track = to.track,
                bpm = to.beat.bpm,
                camelot = to.camelot,
                energyLevel = to.beat.energyLevel,
                transitionScore = transition,
                energyFitScore = energy,
                combinedScore = (transition * 0.65f + energy * 0.35f).coerceIn(0f, 1f),
                fromQueue = queuePositions.containsKey(to.track.id),
                queuePosition = queuePositions[to.track.id],
            )
        }

        var bestSteps: List<SequenceStep> = emptyList()
        var bestScore = -1f

        for (c1 in candidates) {
            val step1 = step(currentBeat.bpm, currentCamelot, currentTrack, currentBeat.energyLevel, c1)

            if (depth <= 1) {
                if (step1.combinedScore > bestScore) {
                    bestScore = step1.combinedScore
                    bestSteps = listOf(step1)
                }
                continue
            }

            for (c2 in candidates) {
                if (c2.track.id == c1.track.id) continue
                val step2 = step(c1.beat.bpm, c1.camelot, c1.track, c1.beat.energyLevel, c2)
                val avg = (step1.combinedScore + step2.combinedScore) / 2f
                if (avg > bestScore) {
                    bestScore = avg
                    bestSteps = listOf(step1, step2)
                }
            }
        }

        return SequencePlan(direction, bestSteps, bestScore.coerceAtLeast(0f))
    }
}
