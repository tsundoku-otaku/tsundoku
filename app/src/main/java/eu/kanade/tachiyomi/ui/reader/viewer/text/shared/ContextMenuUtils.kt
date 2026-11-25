package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import android.app.PendingIntent
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.view.textclassifier.ConversationActions
import android.view.textclassifier.SelectionEvent
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextClassifierEvent
import android.view.textclassifier.TextLanguage
import android.view.textclassifier.TextLinks
import android.view.textclassifier.TextSelection
import androidx.annotation.RequiresApi
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.novel.TDMR
import java.text.BreakIterator
import java.util.Locale

object ContextMenuUtils {
    private const val MAX_DICTIONARY_WORDS = 2

    /**
     * Creates a [TextClassifier] that adds a custom Define action to system
     * text classifications when no dictionary entity is detected.
     *
     * @param context Context used to obtain the system classifier.
     * @return A text classifier that delegates to the system and adds Define when needed.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun createDictionaryTextClassifier(context: Context): TextClassifier {
        val appContext = context.applicationContext
        val system = appContext
            .getSystemService(TextClassificationManager::class.java)
            .textClassifier

        return object : TextClassifier {

            override fun classifyText(
                request: TextClassification.Request
            ): TextClassification {
                val original = system.classifyText(request)

                // Keep the original result if it already has a dictionary entity
                val hasDictionaryEntity = (0 until original.entityCount)
                    .any { original.getEntity(it) == "dictionary" }

                if (hasDictionaryEntity) return original

                val text = request.text
                    .subSequence(request.startIndex, request.endIndex)
                    .toString()

                if (text.isBlank()) return original

                val language = system.detectLanguage(
                    TextLanguage.Request.Builder(text).build(),
                )
                val locale = if (language.localeHypothesisCount > 0) {
                    language.getLocale(0).toLocale()
                } else {
                    Locale.ROOT
                }

                if (!shouldShowDictionary(text, locale)) return original

                val intent = Intent(Intent.ACTION_DEFINE)
                    .putExtra(Intent.EXTRA_TEXT, text)

                val canHandleDefine = intent.resolveActivity(appContext.packageManager) != null

                if (!canHandleDefine) return original

                val pendingIntent = PendingIntent.getActivity(
                    appContext,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or
                        PendingIntent.FLAG_UPDATE_CURRENT
                )

                val defineLabel = appContext.stringResource(TDMR.strings.action_define)

                val defineAction = RemoteAction(
                    Icon.createWithResource(
                        appContext,
                        android.R.drawable.ic_menu_search
                    ),
                    defineLabel,
                    "$defineLabel $text",
                    pendingIntent
                )

                val builder = TextClassification.Builder()
                    .setText(original.text ?: text)
                    .setId(original.id)
                    .setExtras(original.extras)

                for (i in 0 until original.entityCount) {
                    val type = original.getEntity(i)
                    builder.setEntityType(
                        type,
                        original.getConfidenceScore(type)
                    )
                }

                // Make Define the primary action.
                builder.addAction(defineAction)
                // Add actions from original classification
                original.actions.forEach(builder::addAction)

                return builder.build()
            }

            override fun suggestSelection(
                request: TextSelection.Request
            ): TextSelection = system.suggestSelection(request)

            override fun generateLinks(
                request: TextLinks.Request
            ): TextLinks = system.generateLinks(request)

            override fun getMaxGenerateLinksTextLength(): Int =
                system.maxGenerateLinksTextLength

            override fun onSelectionEvent(event: SelectionEvent) =
                system.onSelectionEvent(event)

            override fun onTextClassifierEvent(
                event: TextClassifierEvent
            ) = system.onTextClassifierEvent(event)

            override fun detectLanguage(
                request: TextLanguage.Request
            ): TextLanguage = system.detectLanguage(request)

            override fun suggestConversationActions(
                request: ConversationActions.Request
            ): ConversationActions = system.suggestConversationActions(request)
        }
    }

    /**
     * Determines whether the selected text contains up to two word-like segments
     * suitable for dictionary lookup.
     *
     * @param selection Selected text to evaluate.
     * @param locale Language of the selected content.
     * @return `true` if one or two words are selected, `false` otherwise.
     */
    private fun shouldShowDictionary(
        selection: String,
        locale: Locale
    ): Boolean {
        val text = selection.trim()
        if (text.isEmpty()) return false

        val iterator = BreakIterator.getWordInstance(locale)
        iterator.setText(text)

        var wordCount = 0
        var start = iterator.first()
        var end = iterator.next()

        while (end != BreakIterator.DONE) {
            val segment = text.substring(start, end)

            if (segment.any { Character.isLetterOrDigit(it) }) {
                wordCount++
                if (wordCount > MAX_DICTIONARY_WORDS) return false
            }

            start = end
            end = iterator.next()
        }

        return wordCount in 1..MAX_DICTIONARY_WORDS
    }

}
