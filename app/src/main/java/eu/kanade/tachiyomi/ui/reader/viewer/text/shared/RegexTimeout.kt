package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class RegexTimeoutException : RuntimeException("Regex timed out")

class RegexDisabledException : RuntimeException("Regex disabled after an earlier timeout")

const val DEFAULT_REGEX_TIMEOUT_MS = 2_000L

private const val DEADLINE_CHECK_MASK = 0x3FF

private val regexThreadCount = AtomicInteger()

private const val REGEX_WORKER_STACK_BYTES = 8L * 1024 * 1024

private val stuckRegexWorkers = AtomicInteger()

private const val TASK_PENDING = 0
private const val TASK_RUNNING = 1
private const val TASK_ABANDONED = 2
private const val TASK_DONE = 3

// Unbounded so rules of chapters processed at the same time never get rejected; idle workers are reused.
private val regexExecutor = ThreadPoolExecutor(
    0,
    Int.MAX_VALUE,
    30L,
    TimeUnit.SECONDS,
    SynchronousQueue(),
) { runnable ->
    Thread(null, runnable, "regex-worker-${regexThreadCount.incrementAndGet()}", REGEX_WORKER_STACK_BYTES)
        .apply { isDaemon = true }
}

// Only stops the worker on the JVM; Android's Matcher copies the input to a String and matches natively,
// so a timed-out match keeps its thread busy until it ends on its own.
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

internal fun stuckRegexWorkerCount(): Int = stuckRegexWorkers.get()

/**
 * Runs [block] on a worker for up to [timeoutMs]. When it times out but keeps running, [onLateFinish] is
 * called once it ends, with whether it completed (true) or failed.
 */
internal fun <T> runWithRegexTimeout(
    timeoutMs: Long,
    onLateFinish: (completed: Boolean) -> Unit = {},
    block: () -> T,
): T {
    val state = AtomicInteger(TASK_PENDING)
    val future = regexExecutor.submit(
        Callable {
            if (!state.compareAndSet(TASK_PENDING, TASK_RUNNING)) throw CancellationException()
            var completed = false
            try {
                block().also { completed = true }
            } finally {
                if (state.getAndSet(TASK_DONE) == TASK_ABANDONED) {
                    stuckRegexWorkers.decrementAndGet()
                    onLateFinish(completed)
                }
            }
        },
    )
    try {
        return future.get(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        if (abandon(state)) {
            try {
                return future.get()
            } catch (failed: ExecutionException) {
                throw failed.cause ?: failed
            }
        }
        future.cancel(true)
        throw RegexTimeoutException()
    } catch (e: InterruptedException) {
        abandon(state)
        future.cancel(true)
        throw e
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
}

// A task cancelled before it starts never runs its finally, so only a started one may count as stuck.
// True when the task already finished, so its result can still be used.
private fun abandon(state: AtomicInteger): Boolean {
    while (true) {
        when (state.get()) {
            TASK_PENDING -> if (state.compareAndSet(TASK_PENDING, TASK_ABANDONED)) return false
            TASK_RUNNING -> if (state.compareAndSet(TASK_RUNNING, TASK_ABANDONED)) {
                stuckRegexWorkers.incrementAndGet()
                return false
            }
            TASK_DONE -> return true
            else -> return false
        }
    }
}

fun Regex.replaceWithTimeout(
    input: CharSequence,
    replacement: String,
    timeoutMs: Long = DEFAULT_REGEX_TIMEOUT_MS,
    onLateFinish: (completed: Boolean) -> Unit = {},
): String = runWithRegexTimeout(timeoutMs, onLateFinish) {
    replace(DeadlineCharSequence(input, deadline(timeoutMs)), replacement)
}

fun Regex.replaceWithTimeout(
    input: CharSequence,
    timeoutMs: Long = DEFAULT_REGEX_TIMEOUT_MS,
    transform: (MatchResult) -> CharSequence,
): String = runWithRegexTimeout(timeoutMs) {
    replace(DeadlineCharSequence(input, deadline(timeoutMs)), transform)
}
