package tachiyomi.data

import app.cash.sqldelight.ColumnAdapter
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import logcat.LogPriority
import logcat.logcat
import java.util.Date

object DateColumnAdapter : ColumnAdapter<Date, Long> {
    override fun decode(databaseValue: Long): Date = Date(databaseValue)
    override fun encode(value: Date): Long = value.time
}

private const val LIST_OF_STRINGS_SEPARATOR = ", "
private const val MAX_DECODED_STRING_LIST_LENGTH = 1_000_000
private const val MAX_DECODED_STRING_LIST_ITEMS = 2_000
object StringListColumnAdapter : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String): List<String> {
        if (databaseValue.isEmpty()) return emptyList()

        if (databaseValue.length > MAX_DECODED_STRING_LIST_LENGTH) {
            logcat(LogPriority.WARN) {
                "StringListColumnAdapter: dropping oversized value (len=${databaseValue.length}) to avoid OOM"
            }
            return emptyList()
        }

        val values = databaseValue
            .splitToSequence(LIST_OF_STRINGS_SEPARATOR)
            .filter { it.isNotBlank() }
            .take(MAX_DECODED_STRING_LIST_ITEMS + 1)
            .toList()

        return if (values.size > MAX_DECODED_STRING_LIST_ITEMS) {
            logcat(LogPriority.WARN) {
                "StringListColumnAdapter: truncating oversized list (${values.size} items)"
            }
            values.take(MAX_DECODED_STRING_LIST_ITEMS)
        } else {
            values
        }
    }

    override fun encode(value: List<String>) = value.joinToString(
        separator = LIST_OF_STRINGS_SEPARATOR,
    )
}

// Alternative titles frequently contain ", " (e.g. "Vol. 1, Part 2"), which the
// legacy ", " separator would wrongly split into multiple entries. Use the
// non-printable unit separator so any punctuation inside a title is preserved.
private const val ALT_TITLES_SEPARATOR = ""
object AlternativeTitlesColumnAdapter : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String): List<String> {
        if (databaseValue.isEmpty()) return emptyList()

        if (databaseValue.length > MAX_DECODED_STRING_LIST_LENGTH) {
            logcat(LogPriority.WARN) {
                "AlternativeTitlesColumnAdapter: dropping oversized value (len=${databaseValue.length}) to avoid OOM"
            }
            return emptyList()
        }

        val values = databaseValue
            .splitToSequence(ALT_TITLES_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_DECODED_STRING_LIST_ITEMS + 1)
            .toList()

        return if (values.size > MAX_DECODED_STRING_LIST_ITEMS) {
            values.take(MAX_DECODED_STRING_LIST_ITEMS)
        } else {
            values
        }
    }

    override fun encode(value: List<String>) = value.joinToString(separator = ALT_TITLES_SEPARATOR)
}

object UpdateStrategyColumnAdapter : ColumnAdapter<UpdateStrategy, Long> {
    override fun decode(databaseValue: Long): UpdateStrategy =
        UpdateStrategy.entries.getOrElse(databaseValue.toInt()) { UpdateStrategy.ALWAYS_UPDATE }

    override fun encode(value: UpdateStrategy): Long = value.ordinal.toLong()
}

object MemoColumnAdapter : ColumnAdapter<JsonObject, ByteArray> {
    override fun decode(databaseValue: ByteArray): JsonObject {
        return Json.decodeFromString<JsonObject>(databaseValue.decodeToString())
    }

    override fun encode(value: JsonObject): ByteArray {
        return value.toString().encodeToByteArray()
    }
}
