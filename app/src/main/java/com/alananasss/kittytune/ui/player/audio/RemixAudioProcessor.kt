package com.alananasss.kittytune.ui.player.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * The three things a DJ does to a track while it plays: repeat a piece of it, sweep a filter
 * across it, and throw an echo off the end of a phrase.
 *
 * Kept as one processor rather than three because they are used together and in a fixed order -
 * the loop decides what is heard, the filter shapes it, the echo trails it - and splitting them
 * would put two more buffer copies in a path that runs on every audio block.
 *
 * Everything is set from outside, on the beat grid, by whatever is directing the mix. Nothing in
 * here decides when to do anything: this only knows how.
 */
class RemixAudioProcessor : BaseAudioProcessor() {

    @Volatile private var enabled = false

    /** How much of the recent past is kept, and so the longest loop that can be taken. */
    private var history: ShortArray = ShortArray(0)
    private var historyWrite = 0
    private var historyFilled = 0

    @Volatile private var loopFrames = 0
    private var loopRead = 0
    private var loopStart = 0

    /** -1 sweeps a low pass down, +1 sweeps a high pass up, 0 leaves the sound alone. */
    @Volatile private var filterTarget = 0f
    private var filterCurrent = 0f
    private var lowState = FloatArray(2)

    @Volatile private var echoTarget = 0f
    private var echoCurrent = 0f
    private var echo: ShortArray = ShortArray(0)
    private var echoCursor = 0

    private var channels = 2
    private var sampleRate = 44_100

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            filterTarget = 0f
            echoTarget = 0f
            loopFrames = 0
        }
    }

    /**
     * Starts repeating the last [lengthMs] of audio, or stops when given zero.
     *
     * The loop is taken from what has already played rather than from what is coming, so it can
     * start on the beat it is asked for instead of a buffer later. Halving it again is how the
     * roll at the end of a phrase is built: the caller asks for a bar, then a half, then a
     * quarter.
     */
    fun setLoop(lengthMs: Int) {
        if (!enabled || lengthMs <= 0) {
            loopFrames = 0
            return
        }
        val wanted = (sampleRate * lengthMs / 1000)
        val available = historyFilled / channels
        val frames = wanted.coerceAtMost(available).coerceAtLeast(1)
        // Read from where the wanted stretch began, not from where the buffer happens to sit.
        loopStart = ((historyWrite - frames * channels) % history.size + history.size) % history.size
        loopRead = 0
        loopFrames = frames
    }

    /** -1 low pass, +1 high pass, 0 open. Moved towards over a few blocks, never jumped to. */
    fun setFilter(value: Float) {
        filterTarget = value.coerceIn(-1f, 1f)
    }

    /** 0 dry, 1 a full echo trail. */
    fun setEcho(value: Float) {
        echoTarget = value.coerceIn(0f, 1f)
    }

    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channels = max(1, format.channelCount)
        sampleRate = format.sampleRate
        history = ShortArray(sampleRate * channels * HISTORY_SECONDS)
        echo = ShortArray((sampleRate * ECHO_MS / 1000) * channels)
        lowState = FloatArray(channels)
        onFlush()
        return format
    }

    override fun onFlush() {
        historyWrite = 0
        historyFilled = 0
        loopFrames = 0
        loopRead = 0
        echoCursor = 0
        filterCurrent = filterTarget
        echoCurrent = echoTarget
        if (history.isNotEmpty()) history.fill(0)
        if (echo.isNotEmpty()) echo.fill(0)
        lowState.fill(0f)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (!enabled || history.isEmpty()) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        val out = replaceOutputBuffer(remaining)
        var channel = 0

        while (inputBuffer.hasRemaining()) {
            val incoming = inputBuffer.getShort()

            // The history is written from the input even while a loop plays, so the buffer holds
            // the real track. A loop fed from its own output would smear into itself within a
            // couple of repeats.
            history[historyWrite] = incoming
            historyWrite = (historyWrite + 1) % history.size
            if (historyFilled < history.size) historyFilled++

            val frames = loopFrames
            var sample = if (frames > 0) {
                val index = (loopStart + loopRead) % history.size
                loopRead++
                if (loopRead >= frames * channels) loopRead = 0
                history[index].toFloat()
            } else {
                incoming.toFloat()
            }

            // Both controls are moved towards their target a step per sample rather than set:
            // a filter that jumps clicks, and the click is louder than the sweep.
            if (channel == 0) {
                filterCurrent += (filterTarget - filterCurrent) * SMOOTHING
                echoCurrent += (echoTarget - echoCurrent) * SMOOTHING
            }

            sample = applyFilter(sample, channel)

            if (echoCurrent > 0.01f && echo.isNotEmpty()) {
                val delayed = echo[echoCursor].toFloat()
                val mixed = sample + delayed * echoCurrent * ECHO_FEEDBACK
                echo[echoCursor] = clamp(mixed)
                echoCursor = (echoCursor + 1) % echo.size
                sample = mixed
            }

            out.putShort(clamp(sample))

            channel++
            if (channel >= channels) channel = 0
        }
        out.flip()
    }

    /**
     * One pole, swept exponentially.
     *
     * A single pole is gentle for a filter sweep, but it costs two operations a sample and does
     * not ring, and on a phone both of those matter more than the steeper slope would. The cutoff
     * moves exponentially because pitch is heard that way: a linear sweep spends most of its
     * travel in a range nobody notices.
     */
    private fun applyFilter(sample: Float, channel: Int): Float {
        val amount = filterCurrent
        if (abs(amount) < 0.01f) {
            lowState[channel] = sample
            return sample
        }

        val cutoff = if (amount < 0f) {
            // Low pass: from open down towards a few hundred hertz.
            20_000f * exp(amount * 4.2f)
        } else {
            // High pass: the same travel, read the other way round.
            20f * exp(amount * 6.5f)
        }
        val coefficient = (1f - exp(-2f * PI.toFloat() * cutoff / sampleRate)).coerceIn(0.0001f, 1f)

        lowState[channel] += coefficient * (sample - lowState[channel])
        return if (amount < 0f) lowState[channel] else sample - lowState[channel]
    }

    private fun clamp(value: Float): Short =
        value.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()

    private companion object {
        /** Seconds of the recent past kept for looping. Four bars at any tempo a track uses. */
        const val HISTORY_SECONDS = 8

        const val ECHO_MS = 375
        const val ECHO_FEEDBACK = 0.55f

        /** How fast a control moves towards its target, per sample. */
        const val SMOOTHING = 0.00008f
    }
}
