package com.alananasss.kittytune.audio.automix

import android.util.Log
import com.alananasss.kittytune.ui.player.audio.RemixAudioProcessor
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Decides when to rework the track that is playing.
 *
 * The processor knows how to loop, filter and echo; this knows when, which is the part that makes
 * it music rather than an effect. Everything is placed on the bar, because a roll that starts
 * half a beat late does not sound like a roll, it sounds like a fault.
 *
 * What it does is built around the phrase. Dance music is written in eights and sixteens of bars,
 * and the interesting places are the last bar before a phrase turns over - which is exactly where
 * a DJ reaches for the filter. So the director counts bars from the track's first beat and acts
 * only on the last one of a phrase.
 */
class RemixDirector(
    private val processor: RemixAudioProcessor,
) {

    /** How hard to work the track: 0 leaves it alone, 1 reworks every phrase. */
    @Volatile
    var intensity: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            processor.setEnabled(field > 0.01f)
            if (field <= 0.01f) reset()
        }

    private var bpm = 0f
    private var firstBeatMs = 0L
    private var lastBarIndex = -1
    private var lastActionBar = -1

    /**
     * Gives the director the grid it works on.
     *
     * Without a usable tempo it does nothing at all rather than guessing: an effect placed on a
     * wrong grid is worse than no effect, because it fights the track instead of following it.
     */
    fun setGrid(bpm: Float?, firstBeatOffsetMs: Long?) {
        this.bpm = bpm?.takeIf { it > 40f && it < 220f } ?: 0f
        this.firstBeatMs = firstBeatOffsetMs ?: 0L
        lastBarIndex = -1
        lastActionBar = -1
        if (this.bpm <= 0f) {
            reset()
            Log.d(TAG, "No usable tempo, standing down")
        }
    }

    /**
     * Called as playback moves, with where it stands and how much of the track is left.
     *
     * Position-driven rather than timer-driven so it stays right when the listener seeks, and so
     * it stops by itself when playback does.
     */
    fun onPosition(positionMs: Long, remainingMs: Long) {
        if (intensity <= 0.01f || bpm <= 0f) return

        val beatMs = 60_000f / bpm
        val barMs = beatMs * BEATS_PER_BAR
        val sinceFirst = (positionMs - firstBeatMs).coerceAtLeast(0L)
        val bar = floor(sinceFirst / barMs).toInt()
        val intoBar = (sinceFirst % barMs) / barMs

        if (bar != lastBarIndex) {
            lastBarIndex = bar
            // Nothing is done in the opening or closing bars. The start of a track is where a
            // listener decides whether they like it, and the end belongs to the transition,
            // which has its own plan for the same audio.
            val settled = positionMs > barMs * 2 && remainingMs > barMs * 4
            val phraseEnd = (bar + 1) % barsPerPhrase() == 0
            if (settled && phraseEnd && bar != lastActionBar) {
                lastActionBar = bar
                Log.d(TAG, "Bar $bar closes a phrase, working it")
            } else if (!phraseEnd) {
                // Left open between phrases, so the track is itself most of the time. An effect
                // that never lifts stops being an effect and becomes the sound of the app.
                processor.setLoop(0)
                processor.setFilter(0f)
                processor.setEcho(0f)
            }
        }

        if (bar == lastActionBar) {
            shapePhraseEnd(intoBar, beatMs)
        }
    }

    /**
     * The last bar of a phrase, as it passes.
     *
     * A build in three parts: the filter closes over the whole bar, the loop halves twice across
     * the back half, and an echo is left hanging into the first beat of what follows. Reading
     * [intoBar] rather than scheduling means seeking lands in the right part of the shape instead
     * of replaying it from the start.
     */
    private fun shapePhraseEnd(intoBar: Float, beatMs: Float) {
        val strength = intensity

        // A high pass climbing through the bar: the usual way a phrase is lifted, and the one
        // that survives a small speaker, where a low pass mostly removes what little there was.
        processor.setFilter(intoBar * 0.75f * strength)

        when {
            intoBar < 0.5f -> processor.setLoop(0)
            intoBar < 0.75f -> processor.setLoop((beatMs * 2).roundToInt())
            intoBar < 0.9f -> processor.setLoop(beatMs.roundToInt())
            else -> processor.setLoop((beatMs / 2).roundToInt())
        }

        processor.setEcho(if (intoBar > 0.85f) strength * 0.6f else 0f)
    }

    /** Longer phrases at low intensity, so the track is worked less often rather than less hard. */
    private fun barsPerPhrase(): Int = if (intensity < 0.5f) 16 else 8

    fun reset() {
        processor.setLoop(0)
        processor.setFilter(0f)
        processor.setEcho(0f)
        lastBarIndex = -1
        lastActionBar = -1
    }

    private companion object {
        const val TAG = "RemixDirector"
        const val BEATS_PER_BAR = 4
    }
}
