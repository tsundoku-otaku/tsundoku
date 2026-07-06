package tachiyomi.core.common.preference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap

/**
 * Local-copy implementation of PreferenceStore mostly for test and preview purposes
 */
class InMemoryPreferenceStore(
    initialPreferences: Sequence<InMemoryPreference<*>> = sequenceOf(),
) : PreferenceStore {

    // Backs every Preference<T> returned for a given key, so a set() from one getX() call is
    // visible to a get() from another - previously each getX() call built its own disconnected
    // snapshot, silently discarding anything written after construction.
    private val data = ConcurrentHashMap<String, Any?>().apply {
        initialPreferences.forEach { pref -> if (pref.isSet()) put(pref.key(), pref.get()) }
    }

    override fun getString(key: String, defaultValue: String): Preference<String> = preference(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> = preference(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> = preference(key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = preference(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = preference(key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
        TODO("Not yet implemented")
    }

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = preference(key, defaultValue)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = preference(key, defaultValue)

    override fun getAll(): Map<String, *> = data

    private fun <T> preference(key: String, defaultValue: T): Preference<T> =
        InMemoryPreference(data, key, defaultValue)

    class InMemoryPreference<T> internal constructor(
        private val store: MutableMap<String, Any?>,
        private val key: String,
        private val defaultValue: T,
    ) : Preference<T> {
        constructor(key: String, data: T?, defaultValue: T) : this(
            ConcurrentHashMap<String, Any?>().apply { data?.let { put(key, it) } },
            key,
            defaultValue,
        )

        override fun key(): String = key

        @Suppress("UNCHECKED_CAST")
        override fun get(): T = store[key] as? T ?: defaultValue

        override fun isSet(): Boolean = store.containsKey(key)

        override fun delete() {
            store.remove(key)
        }

        override fun defaultValue(): T = defaultValue

        override fun changes(): Flow<T> = flow { emit(get()) }

        override fun stateIn(scope: CoroutineScope): StateFlow<T> {
            return changes().stateIn(scope, SharingStarted.Eagerly, get())
        }

        override fun set(value: T) {
            store[key] = value
        }
    }
}
