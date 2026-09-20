package com.alananasss.kittytune.data.remix

import android.content.Context
import android.util.Log
import com.alananasss.kittytune.data.GenreData

/**
 * What a typed request was understood to mean.
 *
 * [queries] are tried in order, so a confident reading can lead and a broad one can catch what it
 * misses. [tempo] narrows the mix where the request implies a pace - someone asking for something
 * to fall asleep to does not want 170 bpm, whatever genre it carries.
 */
data class RemixIntent(
    val queries: List<String>,
    val tempo: IntRange? = null,
    val label: String,
    val mode: RemixIntentMode,
)

/**
 * Turns what the listener typed into something to search for.
 *
 * Implemented once here, against a table; the other two modes replace this step and nothing else,
 * which is why they are an interface rather than a rewrite. Whatever reads the request, the mix is
 * assembled and played the same way afterwards.
 */
interface RemixIntentResolver {
    val mode: RemixIntentMode
    suspend fun resolve(prompt: String): RemixIntent
}

/**
 * Reads the request against the app's own genres and moods, plus the words people use for them.
 *
 * Unglamorous and hard to beat for what people actually type here, which is a genre. It costs
 * nothing to run, works without a network, and cannot be wrong in a way the listener will not
 * immediately see - all three of which matter more than handling a sentence nobody wrote.
 *
 * Where it gives up, it gives the prompt to search unchanged rather than guessing. A search for
 * words the app did not recognise still finds something; a guess finds the wrong thing
 * confidently.
 */
class KeywordIntentResolver(private val context: Context) : RemixIntentResolver {

    override val mode = RemixIntentMode.KEYWORD

    override suspend fun resolve(prompt: String): RemixIntent {
        val text = prompt.trim().lowercase()
        if (text.isEmpty()) {
            return RemixIntent(emptyList(), null, prompt, mode)
        }

        // The app's own categories first: their query strings are what SoundCloud indexes, and
        // they are already translated, so a German title matches without a German table.
        val categories = try {
            GenreData.getGenres(context) + GenreData.getMoods(context)
        } catch (e: Exception) {
            Log.w("RemixIntent", "Could not read the categories", e)
            emptyList()
        }

        val matched = categories.firstOrNull { category ->
            val title = category.title.lowercase()
            val query = category.query.lowercase()
            text == title || text == query || text.contains(title) || text.contains(query)
        }
        if (matched != null) {
            val pace = PACE_BY_CATEGORY[matched.id]
            return RemixIntent(
                queries = listOf(matched.query, prompt.trim()),
                tempo = pace,
                label = matched.title,
                mode = mode,
            )
        }

        // Then the words people reach for when they are describing an occasion rather than naming
        // a genre. Both languages the app is used in here, and the stems rather than whole words,
        // so "einschlafen", "schlafen" and "sleepy" all land on the same entry.
        val hit = MOOD_WORDS.entries.firstOrNull { (stem, _) -> text.contains(stem) }
        if (hit != null) {
            val (queries, pace) = hit.value
            return RemixIntent(
                queries = queries + prompt.trim(),
                tempo = pace,
                label = queries.first(),
                mode = mode,
            )
        }

        return RemixIntent(queries = listOf(prompt.trim()), tempo = null, label = prompt.trim(), mode = mode)
    }

    private companion object {
        /**
         * Tempo ranges for the app's own moods, where the mood implies a pace.
         *
         * Only the ones where it clearly does. A mood like "sad" spans a ballad and a drum and
         * bass track equally well, and narrowing it would throw away most of what fits.
         */
        val PACE_BY_CATEGORY: Map<String, IntRange> = mapOf(
            "sleep" to 60..90,
            "calm" to 60..100,
            "focus" to 70..110,
            "workout" to 130..175,
            "energy" to 125..175,
            "party" to 118..140,
            "gaming" to 120..175,
            "driving" to 100..135,
            "romance" to 65..105,
        )

        /** Occasion words to queries and a pace. German and English, matched as stems. */
        val MOOD_WORDS: Map<String, Pair<List<String>, IntRange?>> = mapOf(
            "einschlaf" to (listOf("Ambient", "Lo-Fi") to 60..85),
            "schlaf" to (listOf("Ambient", "Lo-Fi") to 60..85),
            "sleep" to (listOf("Ambient", "Lo-Fi") to 60..85),
            "entspann" to (listOf("Chillout", "Ambient") to 60..100),
            "chill" to (listOf("Chillout", "Lo-Fi") to 70..110),
            "relax" to (listOf("Chillout", "Ambient") to 60..100),
            "lern" to (listOf("Lo-Fi", "Study") to 70..110),
            "konzentr" to (listOf("Lo-Fi", "Ambient") to 70..110),
            "study" to (listOf("Lo-Fi", "Study") to 70..110),
            "focus" to (listOf("Lo-Fi", "Ambient") to 70..110),
            "sport" to (listOf("Workout", "Drum and Bass") to 130..175),
            "train" to (listOf("Workout", "Techno") to 130..175),
            "workout" to (listOf("Workout", "Drum and Bass") to 130..175),
            "gym" to (listOf("Workout", "Hardstyle") to 140..175),
            "lauf" to (listOf("Running", "Drum and Bass") to 150..180),
            "run" to (listOf("Running", "Drum and Bass") to 150..180),
            "party" to (listOf("Party", "House") to 118..140),
            "feier" to (listOf("Party", "House") to 118..140),
            "tanz" to (listOf("Dance", "House") to 118..140),
            "danc" to (listOf("Dance", "House") to 118..140),
            "auto" to (listOf("Driving", "Hip Hop") to 100..135),
            "fahr" to (listOf("Driving", "Hip Hop") to 100..135),
            "driv" to (listOf("Driving", "Hip Hop") to 100..135),
            "traurig" to (listOf("Sad", "Lo-Fi") to null),
            "sad" to (listOf("Sad", "Lo-Fi") to null),
            "melanchol" to (listOf("Sad", "Ambient") to null),
            "gute laune" to (listOf("Feel Good", "Funk") to null),
            "happy" to (listOf("Feel Good", "Funk") to null),
            "aggressiv" to (listOf("Hardstyle", "Dubstep") to 140..180),
            "hart" to (listOf("Hardstyle", "Techno") to 140..180),
            "aggressive" to (listOf("Hardstyle", "Dubstep") to 140..180),
            "abend" to (listOf("Chillout", "Deep House") to 90..122),
            "nacht" to (listOf("Deep House", "Techno") to 115..140),
            "night" to (listOf("Deep House", "Techno") to 115..140),
            "morgen" to (listOf("Feel Good", "Indie") to 85..120),
            "morning" to (listOf("Feel Good", "Indie") to 85..120),
        )
    }
}
