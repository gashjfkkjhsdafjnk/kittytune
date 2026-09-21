package com.alananasss.kittytune.data.remix

import android.content.Context
import android.util.Log
import com.alananasss.kittytune.data.GenreData
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the request with a sentence model instead of a table.
 *
 * The model embeds the request and every category the app knows, and the nearest one wins. What
 * this buys over the table is wording nobody wrote down: the table has to have thought of
 * "einschlafen" in advance, while the model places it near sleep because that is where the
 * sentence sits.
 *
 * It does not replace the table, it sits in front of it. A nearest match can still be a poor one,
 * and a poor match confidently applied is worse than an honest miss - so anything below
 * [MIN_SIMILARITY] is handed back to [fallback], which will at least search for what was typed.
 *
 * The model that ships here is trained on English. A German request is therefore the case to
 * watch: where it works it works because the two languages share a word, not because the model
 * understands German, and that is a thin foundation. The fallback exists partly for this.
 */
class EmbeddingIntentResolver(
    private val context: Context,
    private val fallback: RemixIntentResolver,
) : RemixIntentResolver {

    override val mode = RemixIntentMode.EMBEDDING

    private var embedder: TextEmbedder? = null
    private var categoryVectors: List<Pair<CategoryRef, Embedding>> = emptyList()

    private data class CategoryRef(val id: String, val title: String, val query: String)

    /**
     * Loads the model and embeds the categories once.
     *
     * The categories do not change while the app runs, and embedding forty of them costs more
     * than embedding one request, so paying it once at the first request keeps every later one
     * to a single pass.
     */
    private suspend fun ensureReady(): Boolean = withContext(Dispatchers.Default) {
        if (embedder != null) return@withContext true
        try {
            val options = TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                .setL2Normalize(true)
                .build()
            val created = TextEmbedder.createFromOptions(context, options)

            val categories = (GenreData.getGenres(context) + GenreData.getMoods(context))
                .map { CategoryRef(it.id, it.title, it.query) }

            categoryVectors = categories.mapNotNull { ref ->
                // Embedded by its search term rather than its translated title: the term is what
                // SoundCloud indexes, and an English-trained model reads it far better than a
                // localised label it has never seen.
                val result = runCatching { created.embed(ref.query) }.getOrNull() ?: return@mapNotNull null
                val vector = result.embeddingResult().embeddings().firstOrNull() ?: return@mapNotNull null
                ref to vector
            }

            embedder = created
            Log.d(TAG, "Ready with ${categoryVectors.size} categories")
            categoryVectors.isNotEmpty()
        } catch (e: Throwable) {
            // Throwable, not Exception: a missing native library arrives as an Error, and a
            // device that cannot load it should fall back rather than crash the home screen.
            Log.w(TAG, "Could not start the sentence model, falling back to keywords", e)
            embedder = null
            false
        }
    }

    override suspend fun resolve(prompt: String): RemixIntent {
        val text = prompt.trim()
        if (text.isEmpty()) return fallback.resolve(prompt)
        if (!ensureReady()) return fallback.resolve(prompt)

        return try {
            withContext(Dispatchers.Default) {
                val active = embedder ?: return@withContext fallback.resolve(prompt)
                val asked = active.embed(text).embeddingResult().embeddings().firstOrNull()
                    ?: return@withContext fallback.resolve(prompt)

                val ranked = categoryVectors
                    .map { (ref, vector) -> TextEmbedder.cosineSimilarity(asked, vector) to ref }
                    .sortedByDescending { it.first }

                val best = ranked.firstOrNull()
                Log.d(TAG, "'$text' -> " + ranked.take(3).joinToString { "%.2f %s".format(it.first, it.second.query) })

                if (best == null || best.first < MIN_SIMILARITY) {
                    return@withContext fallback.resolve(prompt)
                }

                // The runner-up rides along when it is nearly as close. Two near-equal readings
                // usually means the request sits between them, and a mix drawn from both is a
                // better answer than committing to whichever won by a hundredth.
                val second = ranked.getOrNull(1)?.takeIf { it.first > best.first - CLOSE_ENOUGH }
                val queries = listOfNotNull(best.second.query, second?.second?.query, text)

                RemixIntent(
                    queries = queries,
                    tempo = PACE_BY_CATEGORY[best.second.id],
                    label = best.second.title,
                    mode = mode,
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Embedding failed for '$text'", e)
            fallback.resolve(prompt)
        }
    }

    fun close() {
        runCatching { embedder?.close() }
        embedder = null
        categoryVectors = emptyList()
    }

    private companion object {
        const val TAG = "EmbeddingIntent"
        const val MODEL_ASSET = "remix_text_embedder.tflite"

        /**
         * How close the nearest category has to be before it is trusted.
         *
         * Set by what the cost of being wrong is rather than by measurement: a wrong genre plays
         * for the next hour, while falling back merely searches for what was typed. Worth
         * revisiting once there is evidence from real requests.
         */
        const val MIN_SIMILARITY = 0.25f

        /** How near the runner-up has to be to be taken along as well. */
        const val CLOSE_ENOUGH = 0.05f

        /** Shared with the keyword resolver: the pace a mood implies, where it implies one. */
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
    }
}
