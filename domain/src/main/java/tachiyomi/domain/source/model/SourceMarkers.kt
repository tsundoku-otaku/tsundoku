package tachiyomi.domain.source.model

import eu.kanade.tachiyomi.source.Source

/** Suffix JS plugins and custom novel sources append to their display string. */
const val JS_SOURCE_MARKER = " (JS)"

/**
 * Whether a source renders with the JS marker. Asking the source how it renders keeps stubs and
 * domain models in step with the loaded source without the lower layers knowing its concrete type.
 * Novel sources also ship as Kotlin extensions, so [Source.isNovelSource] is not the same question.
 */
fun Source.hasJsMarker(): Boolean = toString().endsWith(JS_SOURCE_MARKER)
