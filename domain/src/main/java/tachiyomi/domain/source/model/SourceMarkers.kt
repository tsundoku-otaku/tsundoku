package tachiyomi.domain.source.model

import eu.kanade.tachiyomi.source.Source

const val JS_SOURCE_MARKER = " (JS)"

// Asked of the source rather than derived from its type, so stubs and domain models stay in step
// with the loaded source's own rendering.
fun Source.hasJsMarker(): Boolean = toString().endsWith(JS_SOURCE_MARKER)
