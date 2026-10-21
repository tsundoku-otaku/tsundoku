package tachiyomi.domain.library.paging

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Turns a stream of change signals into at most one emission per [window], each trailing the burst
 * that caused it. While [active] is false emissions are held back, and everything that arrived in
 * the meantime collapses into a single emission once it turns true again.
 */
fun Flow<Unit>.coalesceInvalidations(active: StateFlow<Boolean>, window: Duration): Flow<Unit> = channelFlow {
    val pending = Channel<Unit>(Channel.CONFLATED)
    launch {
        collect { pending.send(Unit) }
        pending.close()
    }
    for (signal in pending) {
        delay(window)
        active.first { it }
        // This emission reloads the current state, so it covers whatever arrived while waiting.
        pending.tryReceive()
        send(Unit)
    }
}
