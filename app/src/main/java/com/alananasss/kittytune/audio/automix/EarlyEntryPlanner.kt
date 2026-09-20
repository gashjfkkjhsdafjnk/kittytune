package com.alananasss.kittytune.audio.automix

import android.util.Log
import com.alananasss.kittytune.data.local.BeatInfoEntity
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Decides whether to go into the next track before the plan said to.
 *
 * The plan picks a safe place: late enough that the current track has been heard, early enough
 * that the transition fits. A DJ does not always wait for it. When two tracks sit close in tempo
 * and key, the blend can be long and start early, and starting early is what keeps a set moving
 * instead of playing every record to its end.
 *
 * So this only ever moves the trigger earlier, only by whole phrases, and only when the pair is
 * a good enough match that the longer overlap is an improvement rather than a mess. Everything
 * else is left to the plan.
 */
object EarlyEntryPlanner {

    private const val TAG = "EarlyEntry"

    private const val BEATS_PER_BAR = 4
    private const val BARS_PER_PHRASE = 8

    /**
     * How much of the track has to have played before it may be left.
     *
     * A set that leaves every track at the halfway mark is not mixing, it is skipping. Two
     * thirds is roughly where a track has made its point.
     */
    private const val MIN_PLAYED_FRACTION = 0.62f

    /** Tempo difference, in percent, at which the pair stops being worth an early blend. */
    private const val TEMPO_TOLERANCE_PERCENT = 6f

    /**
     * Returns a trigger earlier than [plannedTriggerMs], or null to leave the plan alone.
     *
     * [intensity] is the same control that drives the rework: someone who asked for a heavier
     * hand gets earlier entries, someone who left it off gets none at all.
     */
    fun earlierTrigger(
        plannedTriggerMs: Long,
        durationMs: Long,
        outBeat: BeatInfoEntity?,
        inBeat: BeatInfoEntity?,
        intensity: Float,
    ): Long? {
        if (intensity <= 0.01f) return null
        if (outBeat == null || inBeat == null) return null
        if (outBeat.bpm <= 0f || inBeat.bpm <= 0f) return null
        if (durationMs <= 0L) return null

        val tempoGap = abs(outBeat.bpm - inBeat.bpm) / outBeat.bpm * 100f
        if (tempoGap > TEMPO_TOLERANCE_PERCENT) {
            Log.d(TAG, "Tempo gap %.1f%% is too wide for an early entry".format(tempoGap))
            return null
        }

        val keyDistance = keyDistance(outBeat, inBeat)

        // Two phrases early asks a lot of the pair: the overlap runs long, so both tracks are
        // audible together for most of it, and anything less than a close match turns muddy.
        // One phrase is forgiving enough for a merely good one.
        val phrases = when {
            tempoGap <= 2f && keyDistance <= 1 -> 2
            tempoGap <= 4f && keyDistance <= 2 -> 1
            else -> {
                Log.d(TAG, "Pair not close enough (tempo %.1f%%, key %d)".format(tempoGap, keyDistance))
                return null
            }
        }

        val phraseMs = (60_000f / outBeat.bpm) * BEATS_PER_BAR * BARS_PER_PHRASE
        val pulled = (phraseMs * phrases * intensity).roundToLong()
        val candidate = plannedTriggerMs - pulled

        val floorMs = (durationMs * MIN_PLAYED_FRACTION).toLong()
        if (candidate <= floorMs) {
            Log.d(TAG, "Earlier entry would cut the track short, leaving the plan")
            return null
        }

        // Landed on the phrase the outgoing track is actually counting, not merely earlier by a
        // duration: an entry a beat off its own grid is the thing this was meant to avoid.
        val snapped = snapToPhrase(candidate, outBeat.firstBeatOffsetMs, phraseMs)
        if (snapped <= floorMs || snapped >= plannedTriggerMs) return null

        Log.d(
            TAG,
            "Entering %d phrase(s) early at %dms instead of %dms (tempo %.1f%%, key %d)"
                .format(phrases, snapped, plannedTriggerMs, tempoGap, keyDistance)
        )
        return snapped
    }

    /**
     * Distance between the two keys around the circle of fifths, in steps.
     *
     * Minor keys are read as their relative major first, which is how they are compared for
     * mixing: A minor and C major share their notes and sit in the same place on the wheel.
     */
    private fun keyDistance(out: BeatInfoEntity, incoming: BeatInfoEntity): Int {
        val a = out.keyPitchClass ?: return 3
        val b = incoming.keyPitchClass ?: return 3
        val aEff = if (out.keyIsMinor == true) (a + 3) % 12 else a
        val bEff = if (incoming.keyIsMinor == true) (b + 3) % 12 else b
        // Seven semitones is one step around the circle, so counting in fifths means counting
        // how many times seven has to be added to get from one to the other.
        var best = 12
        var walk = aEff
        for (steps in 0..6) {
            if (walk == bEff) { best = steps; break }
            walk = (walk + 7) % 12
        }
        var back = aEff
        for (steps in 0..6) {
            if (back == bEff) { best = minOf(best, steps); break }
            back = (back + 5) % 12
        }
        return best
    }

    /** Moves [timeMs] back to the start of the phrase it falls inside. */
    private fun snapToPhrase(timeMs: Long, firstBeatMs: Long, phraseMs: Float): Long {
        val since = (timeMs - firstBeatMs).coerceAtLeast(0L)
        val phrases = (since / phraseMs).toInt()
        return firstBeatMs + (phrases * phraseMs).roundToLong()
    }
}
