package eu.kanade.tachiyomi.ui.reader.quote

import android.content.Context
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException

/**
 * Manager for handling quote storage and retrieval
 */
class QuoteManager(private val context: Context) {

    companion object {
        private const val QUOTES_DIR = "quotes"
    }

    private val jsonFormat = Json { prettyPrint = true }

    private val quotesDir: File by lazy {
        File(context.filesDir, QUOTES_DIR).apply { mkdirs() }
    }

    /**
     * Get the file for storing quotes for a specific novel
     */
    private fun getQuotesFile(novelId: Long): File {
        return File(quotesDir, "novel_$novelId.json")
    }

    /**
     * Save quotes for a novel
     */
    fun saveQuotes(novelId: Long, quotes: List<Quote>) {
        try {
            val novelQuotes = NovelQuotes(novelId, quotes)
            val json = jsonFormat.encodeToString(novelQuotes)
            val file = getQuotesFile(novelId)
            file.writeText(json)
            logcat(LogPriority.DEBUG) { "Quotes saved for novel $novelId: ${quotes.size} quotes" }
        } catch (e: IOException) {
            logcat(LogPriority.ERROR) { "Failed to save quotes for novel $novelId: ${e.message}" }
        } catch (e: SerializationException) {
            logcat(LogPriority.ERROR) { "Failed to serialize quotes for novel $novelId: ${e.message}" }
        }
    }

    /**
     * Load quotes for a novel
     */
    fun loadQuotes(novelId: Long): List<Quote> {
        return try {
            val file = getQuotesFile(novelId)
            if (!file.exists()) {
                emptyList()
            } else {
                val json = file.readText()
                val novelQuotes = jsonFormat.decodeFromString<NovelQuotes>(json)
                novelQuotes.quotes
            }
        } catch (e: IOException) {
            logcat(LogPriority.ERROR) { "Failed to load quotes for novel $novelId: ${e.message}" }
            emptyList()
        } catch (e: SerializationException) {
            logcat(LogPriority.ERROR) { "Failed to deserialize quotes for novel $novelId: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Add a new quote for a novel
     */
    fun addQuote(novelId: Long, quote: Quote) {
        val existingQuotes = loadQuotes(novelId).toMutableList()
        existingQuotes.add(quote)
        saveQuotes(novelId, existingQuotes)
    }

    /**
     * Remove a quote by ID
     */
    fun removeQuote(novelId: Long, quoteId: String) {
        val existingQuotes = loadQuotes(novelId).toMutableList()
        existingQuotes.removeAll { it.id == quoteId }
        saveQuotes(novelId, existingQuotes)
    }

    /**
     * Update an existing quote
     */
    fun updateQuote(novelId: Long, updatedQuote: Quote) {
        val existingQuotes = loadQuotes(novelId).toMutableList()
        val index = existingQuotes.indexOfFirst { it.id == updatedQuote.id }
        if (index >= 0) {
            existingQuotes[index] = updatedQuote
            saveQuotes(novelId, existingQuotes)
        }
    }

    /**
     * Get all quotes for a novel, sorted by timestamp (newest first)
     */
    fun getQuotes(novelId: Long): List<Quote> {
        return loadQuotes(novelId).sortedByDescending { it.timestamp }
    }

    /**
     * Clear all quotes for a novel
     */
    fun clearQuotes(novelId: Long) {
        val file = getQuotesFile(novelId)
        if (file.exists()) {
            file.delete()
        }
    }

    /**
     * Get quote count for a novel
     */
    fun getQuoteCount(novelId: Long): Int {
        return loadQuotes(novelId).size
    }
}

/**
 * Get QuoteManager instance
 */
val Context.quoteManager: QuoteManager
    get() = QuoteManager(this)
