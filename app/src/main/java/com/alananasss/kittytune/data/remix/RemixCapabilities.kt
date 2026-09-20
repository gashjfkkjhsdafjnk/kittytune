package com.alananasss.kittytune.data.remix

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * How a typed request is turned into something to search for.
 *
 * Ordered by what they cost, not by what they can do, because the cheap one already handles what
 * people mostly type. A listener who writes "Techno" is served identically by all three.
 */
enum class RemixIntentMode {
    /** A table: the app's own genres and moods, plus the words people use for them. */
    KEYWORD,

    /** A small sentence model: the prompt and the genres are embedded, the nearest one wins. */
    EMBEDDING,

    /** A small language model, prompted to name a genre and a tempo range. */
    LANGUAGE_MODEL,
}

/** Why a mode is not on offer, so the picker can say so rather than grey a row out in silence. */
enum class RemixModeAvailability {
    /** Ready to use now. */
    READY,

    /** The device could run it, but the model that makes it work is not part of the app. */
    NEEDS_MODEL,

    /** The device does not have the memory for it. */
    DEVICE_TOO_SMALL,
}

/**
 * What this particular phone can do with a typed request.
 *
 * Judged on memory rather than on brand. A model runs or it does not, and which company assembled
 * the phone says very little about that - gating on the maker would shut out capable devices and
 * promise the feature on weak ones from the right manufacturer.
 *
 * The thresholds are deliberately generous. Being told a mode is unavailable is a small
 * disappointment; having it accepted and then stall the phone is a large one.
 */
object RemixCapabilities {

    private const val TAG = "RemixCapabilities"

    /** Total RAM a sentence model needs before it is worth offering, in gigabytes. */
    private const val EMBEDDING_MIN_RAM_GB = 3f

    /** Total RAM a small language model needs before it is worth offering, in gigabytes. */
    private const val LANGUAGE_MODEL_MIN_RAM_GB = 6f

    data class Report(
        val deviceName: String,
        val androidRelease: String,
        val totalRamGb: Float,
        val availability: Map<RemixIntentMode, RemixModeAvailability>,
    ) {
        /** The best mode that is actually usable right now. */
        val recommended: RemixIntentMode
            get() = RemixIntentMode.entries.lastOrNull {
                availability[it] == RemixModeAvailability.READY
            } ?: RemixIntentMode.KEYWORD
    }

    fun inspect(context: Context): Report {
        val ramGb = totalRamGb(context)
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        // No model ships with the app yet, so the two model-backed modes report what the device
        // could do rather than claiming to work. That distinction is the point of this screen:
        // a listener told "your phone can run this, it is not built yet" learns something, and
        // one told nothing learns nothing.
        val availability = mapOf(
            RemixIntentMode.KEYWORD to RemixModeAvailability.READY,
            RemixIntentMode.EMBEDDING to when {
                ramGb < EMBEDDING_MIN_RAM_GB -> RemixModeAvailability.DEVICE_TOO_SMALL
                else -> RemixModeAvailability.NEEDS_MODEL
            },
            RemixIntentMode.LANGUAGE_MODEL to when {
                ramGb < LANGUAGE_MODEL_MIN_RAM_GB -> RemixModeAvailability.DEVICE_TOO_SMALL
                else -> RemixModeAvailability.NEEDS_MODEL
            },
        )

        Log.d(TAG, "Device '$device', Android ${Build.VERSION.RELEASE}, ${"%.1f".format(ramGb)} GB -> $availability")
        return Report(
            deviceName = device,
            androidRelease = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            totalRamGb = ramGb,
            availability = availability,
        )
    }

    /**
     * Total physical memory, in gigabytes.
     *
     * Total rather than available: what is free right now says more about the last app the
     * listener opened than about the phone, and a model is loaded once and kept.
     */
    private fun totalRamGb(context: Context): Float {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem / (1024f * 1024f * 1024f)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read memory, assuming a small device", e)
            0f
        }
    }
}
