package tachiyomi.domain.library.paging

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration

/** At most one trailing emission per [window], held back while [active] is false. */
fun Flow<Unit>.coalesceInvalidations(active: StateFlow<Boolean>, window: Duration): Flow<Unit> = channelFlow {
    val pending = Channel<Unit>(Channel.CONFLATED)
    launch {
        collect { pending.send(Unit) }
        pending.close()
    }
    for (signal in pending) {
        delay(window)
        active.first { it }
        pending.tryReceive()
        send(Unit)
    }
}
