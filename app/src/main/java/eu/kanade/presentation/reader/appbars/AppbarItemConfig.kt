package eu.kanade.presentation.reader.appbars

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class BottomBarItem(val id: String) {
    PREV_CHAPTER("prev_chapter"),
    NEXT_CHAPTER("next_chapter"),
    SCROLL_TO_TOP("scroll_to_top"),
    TRANSLATE("translate"),
    AUTO_SCROLL("auto_scroll"),
    TTS("tts"),
    TTS_VIEWPORT("tts_viewport"),
    ORIENTATION("orientation"),
    SETTINGS("settings"),
    EDIT("edit"),
    QUOTES("quotes"),
}

data class BottomBarItemState(
    val item: BottomBarItem,
    val enabled: Boolean = true, // whether it appears in the bar
    val defaultEnabled: Boolean = true, // default visibility state for reset
)

// Default ordering & visibility
// Items with defaultEnabled = false are hidden by default and won't appear when resetting to default
val DefaultBottomBarItems = listOf(
    BottomBarItemState(BottomBarItem.PREV_CHAPTER, defaultEnabled = true),
    BottomBarItemState(BottomBarItem.SCROLL_TO_TOP, defaultEnabled = true),
    BottomBarItemState(BottomBarItem.TRANSLATE, defaultEnabled = false),
    BottomBarItemState(BottomBarItem.AUTO_SCROLL, defaultEnabled = false),
    BottomBarItemState(BottomBarItem.TTS, defaultEnabled = true),
    BottomBarItemState(BottomBarItem.TTS_VIEWPORT, defaultEnabled = false),
    BottomBarItemState(BottomBarItem.QUOTES, defaultEnabled = true),
    BottomBarItemState(BottomBarItem.ORIENTATION, defaultEnabled = false),
    BottomBarItemState(BottomBarItem.SETTINGS, defaultEnabled = true),
    BottomBarItemState(BottomBarItem.EDIT, defaultEnabled = false),
    BottomBarItemState(BottomBarItem.NEXT_CHAPTER, defaultEnabled = true),
)

@Serializable
data class BottomBarItemStateSerialized(
    val id: String,
    val enabled: Boolean,
)

fun List<BottomBarItemState>.serialize(): String =
    Json.encodeToString(map { BottomBarItemStateSerialized(it.item.id, it.enabled) })

fun String.deserializeBottomBarItems(): List<BottomBarItemState> {
    val serialized = Json.decodeFromString<List<BottomBarItemStateSerialized>>(this)
    // Merge with DefaultBottomBarItems so new items added in future app updates
    // aren't silently dropped — they get appended at the end
    val savedIds = serialized.map { it.id }
    val savedItems = serialized.mapNotNull { s ->
        val item = BottomBarItem.entries.find { it.id == s.id } ?: return@mapNotNull null
        BottomBarItemState(item, s.enabled)
    }
    val newItems = DefaultBottomBarItems.filter { it.item.id !in savedIds }
    return savedItems + newItems
}
