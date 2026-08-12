package tachiyomi.domain.source.model

data class Source(
    val id: Long,
    val lang: String,
    val name: String,
    val supportsLatest: Boolean,
    val isStub: Boolean,
    val isNovelSource: Boolean = false,
    // True only for a stub whose real type could not be determined (see StubSource.isInvalid) -
    // e.g. a backup referencing an extension never installed on this device. Such sources should
    // surface in both the manga and novel migrate lists rather than being silently dropped from
    // one, since we can't otherwise tell which list they truly belong in.
    val isTypeUnknown: Boolean = false,
    val isJsSource: Boolean = false,
    val pin: Pins = Pins.unpinned,
    val pinnedGroups: Set<String> = emptySet(),
    val isUsedLast: Boolean = false,
) {

    val visualName: String
        get() = when {
            lang.isEmpty() -> name
            else -> "$name (${lang.uppercase()})"
        }

    val key: () -> String = {
        when {
            isUsedLast -> "$id-lastused"
            else -> "$id"
        }
    }
}
