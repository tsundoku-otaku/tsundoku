package tachiyomi.core.common.preference

sealed class CheckboxState<T>(open val value: T) {

    abstract fun next(): CheckboxState<T>

    sealed class State<T>(override val value: T) : CheckboxState<T>(value) {
        data class Checked<T>(override val value: T) : State<T>(value)
        data class None<T>(override val value: T) : State<T>(value)

        val isChecked: Boolean
            get() = this is Checked

        override fun next(): CheckboxState<T> {
            return when (this) {
                is Checked -> None(value)
                is None -> Checked(value)
            }
        }
    }

    sealed class TriState<T>(override val value: T) : CheckboxState<T>(value) {
        data class Include<T>(override val value: T) : TriState<T>(value)
        data class Exclude<T>(override val value: T) : TriState<T>(value)
        data class None<T>(override val value: T) : TriState<T>(value)

        override fun next(): CheckboxState<T> {
            return when (this) {
                is Exclude -> None(value)
                is Include -> Exclude(value)
                is None -> Include(value)
            }
        }
    }
}

inline fun <T> T.asCheckboxState(condition: (T) -> Boolean): CheckboxState.State<T> {
    return if (condition(this)) {
        CheckboxState.State.Checked(this)
    } else {
        CheckboxState.State.None(this)
    }
}

inline fun <T> List<T>.mapAsCheckboxState(condition: (T) -> Boolean): List<CheckboxState.State<T>> {
    return this.map { it.asCheckboxState(condition) }
}

/**
 * Common/mixed/none tri-state for a bulk category-selection dialog: an item is [CheckboxState.State.Checked]
 * only when present in every one of [perEntryIds], [CheckboxState.TriState.None] (mixed) when present in
 * some but not all, otherwise [CheckboxState.State.None]. Shared so every bulk "move to category" style
 * dialog computes this the same way.
 */
fun <T> List<T>.toCommonCheckboxState(idOf: (T) -> Long, perEntryIds: List<Set<Long>>): List<CheckboxState<T>> {
    if (perEntryIds.isEmpty()) return this.map { CheckboxState.State.None(it) }
    val common = perEntryIds.reduce { a, b -> a intersect b }
    val union = perEntryIds.flatten().toSet()
    return this.map {
        val id = idOf(it)
        when {
            id in common -> CheckboxState.State.Checked(it)
            id in union -> CheckboxState.TriState.None(it)
            else -> CheckboxState.State.None(it)
        }
    }
}
