package eu.kanade.tachiyomi.ui.reader.quote

import android.content.Context
import com.hippo.unifile.UniFile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat
import tachiyomi.domain.storage.service.StorageManager
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

    private val storageManager: StorageManager by lazy {
        Injekt.get<StorageManager>()
    }

    private val quotesDir: UniFile? by lazy {
        storageManager.getQuotesDirectory()
    }

    /**
     * Get the file for storing quotes for a specific novel
     */
    private fun getQuotesFile(novelId: Long): UniFile? {
        return quotesDir?.findFile("novel_$novelId.json")
    }

    /**
     * Save quotes for a novel
     */
    fun saveQuotes(novelId: Long, quotes: List<Quote>) {
        try {
            val novelQuotes = NovelQuotes(novelId, quotes)
            val json = jsonFormat.encodeToString(novelQuotes)
            
            // Delete existing file first to avoid duplicate files
            val existingFile = getQuotesFile(novelId)
            if (existingFile?.exists() == true) {
                existingFile.delete()
            }
            
            val file = quotesDir?.createFile("novel_$novelId.json") ?: return
            file.openOutputStream().use { outputStream ->
                outputStream.write(json.toByteArray())
            }
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
            if (file == null || !file.exists()) {
                emptyList()
            } else {
                val json = file.openInputStream().use { inputStream ->
                    String(inputStream.readBytes())
                }
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
     * Get all quotes for a novel, preserving the stored order
     */
    fun getQuotes(novelId: Long): List<Quote> {
        return loadQuotes(novelId)
    }

    /**
     * Clear all quotes for a novel
     */
    fun clearQuotes(novelId: Long) {
        val file = getQuotesFile(novelId)
        if (file?.exists() == true) {
            file.delete()
        }
    }

    /**
     * Get quote count for a novel
     */
    fun getQuoteCount(novelId: Long): Int {
        return loadQuotes(novelId).size
    }

    /**
     * Reorder quotes for a novel
     */
    fun reorderQuotes(novelId: Long, quotes: List<Quote>) {
        saveQuotes(novelId, quotes)
        logcat(LogPriority.DEBUG) { "Quotes reordered for novel $novelId: ${quotes.size} quotes" }
    }
}

/**
 * Get QuoteManager instance
 */
val Context.quoteManager: QuoteManager
    get() = QuoteManager(this)
