package tachiyomi.domain.library.paging

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class InvalidationsTest {

    @Test
    fun `a burst of changes becomes one emission at the end of the window`() = runTest {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val active = MutableStateFlow(true)
        var emitted = 0
        val job = launch { changes.coalesceInvalidations(active, 100.milliseconds).collect { emitted++ } }
        runCurrent()

        repeat(10) { changes.emit(Unit) }
        advanceTimeBy(50)
        emitted shouldBe 0
        repeat(10) { changes.emit(Unit) }
        advanceTimeBy(60)
        runCurrent()
        emitted shouldBe 1

        advanceTimeBy(500)
        emitted shouldBe 1

        changes.emit(Unit)
        advanceTimeBy(110)
        emitted shouldBe 2
        job.cancel()
    }

    @Test
    fun `changes while inactive wait and collapse into one`() = runTest {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val active = MutableStateFlow(false)
        var emitted = 0
        val job = launch { changes.coalesceInvalidations(active, 100.milliseconds).collect { emitted++ } }
        runCurrent()

        repeat(5) {
            changes.emit(Unit)
            advanceTimeBy(1_000)
        }
        emitted shouldBe 0

        active.value = true
        runCurrent()
        emitted shouldBe 1
        advanceTimeBy(1_000)
        emitted shouldBe 1
        job.cancel()
    }
}
