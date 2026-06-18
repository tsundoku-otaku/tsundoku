package tachiyomi.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

fun <T : Any> Query<T>.subscribeToList(
    context: CoroutineContext = EmptyCoroutineContext,
): Flow<List<T>> = asFlow().mapToList(context)

fun <T : Any> Query<T>.subscribeToOne(
    context: CoroutineContext = EmptyCoroutineContext,
): Flow<T> = asFlow().mapToOne(context)

fun <T : Any> Query<T>.subscribeToOneOrNull(
    context: CoroutineContext = EmptyCoroutineContext,
): Flow<T?> = asFlow().mapToOneOrNull(context)

/**
 * Like [subscribeToList] but debounces table-change notifications: the query only re-executes
 * after [window] of quiet, preventing cascading re-fires when triggers cause multiple table
 * writes. The first emission is immediate (zero debounce).
 */
fun <T : Any> Query<T>.subscribeToDebouncedList(
    window: Duration,
    context: CoroutineContext = EmptyCoroutineContext,
): Flow<List<T>> = flow {
    var isFirst = true
    asFlow()
        .debounce { if (isFirst) Duration.ZERO.also { isFirst = false } else window }
        .collect { emit(it) }
}.mapToList(context)
