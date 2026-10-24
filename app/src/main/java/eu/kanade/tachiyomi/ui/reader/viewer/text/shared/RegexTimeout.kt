package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class RegexTimeoutException : RuntimeException("Regex timed out")

class RegexBusyException : RuntimeException("No regex worker available")

const val DEFAULT_REGEX_TIMEOUT_MS = 2_000L

private const val DEADLINE_CHECK_MASK = 0x3FF

private val regexThreadCount = AtomicInteger()

internal const val MAX_REGEX_WORKERS = 4

private val regexExecutor = ThreadPoolExecutor(
    0,
    MAX_REGEX_WORKERS,
    30L,
    TimeUnit.SECONDS,
    SynchronousQueue(),
) { runnable ->
    Thread(runnable, "regex-worker-${regexThreadCount.incrementAndGet()}").apply { isDaemon = true }
}

// Only stops the worker on the JVM; Android's Matcher copies the input to a String and matches natively,
// so a timed-out match keeps its thread busy until the process ends. After MAX_REGEX_WORKERS of them
// every regex rule is skipped.
private class DeadlineCharSequence(
    private val inner: CharSequence,
    private val deadlineNanos: Long,
) : CharSequence {
    private var reads = 0

    override val length: Int get() = inner.length

    override fun get(index: Int): Char {
        if ((reads++ and DEADLINE_CHECK_MASK) == 0 && System.nanoTime() - deadlineNanos > 0) {
            throw RegexTimeoutException()
        }
        return inner[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DeadlineCharSequence(inner.subSequence(startIndex, endIndex), deadlineNanos)

    override fun toString(): String = inner.toString()
}

private fun deadline(timeoutMs: Long): Long = System.nanoTime() + timeoutMs * 1_000_000

internal fun <T> runWithRegexTimeout(timeoutMs: Long, block: () -> T): T {
    val future = try {
        regexExecutor.submit(Callable(block))
    } catch (e: RejectedExecutionException) {
        throw RegexBusyException()
    }
    try {
        return future.get(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        // A timed-out native match can't be interrupted; the thread is abandoned and the caller disables the rule.
        future.cancel(true)
        throw RegexTimeoutException()
    } catch (e: InterruptedException) {
        future.cancel(true)
        throw e
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
}

fun Regex.replaceWithTimeout(
    input: CharSequence,
    replacement: String,
    timeoutMs: Long = DEFAULT_REGEX_TIMEOUT_MS,
): String = runWithRegexTimeout(timeoutMs) {
    replace(DeadlineCharSequence(input, deadline(timeoutMs)), replacement)
}

fun Regex.replaceWithTimeout(
    input: CharSequence,
    timeoutMs: Long = DEFAULT_REGEX_TIMEOUT_MS,
    transform: (MatchResult) -> CharSequence,
): String = runWithRegexTimeout(timeoutMs) {
    replace(DeadlineCharSequence(input, deadline(timeoutMs)), transform)
}
