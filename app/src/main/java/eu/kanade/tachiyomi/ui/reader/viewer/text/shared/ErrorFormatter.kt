package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import android.content.Context
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.novel.TDMR
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object ErrorFormatter {

    sealed class Category {
        object NetworkHostNotFound : Category()
        object NetworkTimeout : Category()
        object NetworkRefused : Category()
        object NetworkIO : Category()
        object StoragePermission : Category()
        object FileNotFound : Category()
        object SourceError : Category()
        object SourceUnsupported : Category()
        object OutOfMemory : Category()
        data class Unknown(val className: String) : Category()
    }

    data class FormattedError(
        val category: Category,
        val summary: String,
        val stackTrace: String,
    )

    fun format(error: Throwable): FormattedError {
        val root = unwrap(error)
        return FormattedError(
            category = categorize(root),
            summary = summarize(root),
            stackTrace = buildTrace(error),
        )
    }

    fun unwrap(e: Throwable): Throwable {
        var t = e
        repeat(10) {
            val cause = t.cause ?: return t
            if (t is InvocationTargetException || (t.message.isNullOrBlank() && cause !== t)) {
                t = cause
            } else {
                return t
            }
        }
        return t
    }

    fun categorize(e: Throwable): Category = when (e) {
        is UnknownHostException -> Category.NetworkHostNotFound
        is SocketTimeoutException -> Category.NetworkTimeout
        is ConnectException -> Category.NetworkRefused
        is FileNotFoundException -> Category.FileNotFound
        is IOException -> Category.NetworkIO
        is SecurityException -> Category.StoragePermission
        is IllegalStateException -> Category.SourceError
        is UnsupportedOperationException -> Category.SourceUnsupported
        is OutOfMemoryError -> Category.OutOfMemory
        else -> Category.Unknown(e.javaClass.simpleName)
    }

    fun summarize(e: Throwable): String {
        val msg = e.message?.trim()
        return if (msg.isNullOrBlank()) e.javaClass.simpleName else msg
    }

    fun buildTrace(e: Throwable): String = buildString {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 6) {
            if (depth > 0) appendLine("Caused by:")
            appendLine("${t.javaClass.name}: ${t.message ?: "(no message)"}")
            t.stackTrace.take(20).forEach { appendLine("\tat $it") }
            val next = t.cause
            t = if (next !== t) next else null
            depth++
        }
    }
}

fun ErrorFormatter.Category.localized(context: Context): String = when (this) {
    ErrorFormatter.Category.NetworkHostNotFound -> context.stringResource(TDMR.strings.novel_error_cat_network_host)
    ErrorFormatter.Category.NetworkTimeout -> context.stringResource(TDMR.strings.novel_error_cat_network_timeout)
    ErrorFormatter.Category.NetworkRefused -> context.stringResource(TDMR.strings.novel_error_cat_network_refused)
    ErrorFormatter.Category.NetworkIO -> context.stringResource(TDMR.strings.novel_error_cat_network_io)
    ErrorFormatter.Category.StoragePermission -> context.stringResource(TDMR.strings.novel_error_cat_storage_permission)
    ErrorFormatter.Category.FileNotFound -> context.stringResource(TDMR.strings.novel_error_cat_file_not_found)
    ErrorFormatter.Category.SourceError -> context.stringResource(TDMR.strings.novel_error_cat_source_error)
    ErrorFormatter.Category.SourceUnsupported -> context.stringResource(TDMR.strings.novel_error_cat_source_unsupported)
    ErrorFormatter.Category.OutOfMemory -> context.stringResource(TDMR.strings.novel_error_cat_out_of_memory)
    is ErrorFormatter.Category.Unknown -> context.stringResource(TDMR.strings.novel_error_cat_unknown, this.className)
}
