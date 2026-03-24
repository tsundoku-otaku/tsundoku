package eu.kanade.presentation.reader.appbars

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class BottomBarItem(val id: String) {
    PREV_CHAPTER("prev_chapter"),
    NEXT_CHAPTER("next_chapter"),
    SCROLL_TO_TOP("scroll_to_top"),
    TRANSLATE("translate"),
    AUTO_SCROLL("auto_scroll"),
    TTS("tts"),
    ORIENTATION("orientation"),
    SETTINGS("settings"),
    EDIT("edit"),
    QUOTES("quotes"),
}

data class BottomBarItemState(
    val item: BottomBarItem,
    val enabled: Boolean = true, // whether it appears in the bar
)

// Default ordering & visibility
val DefaultBottomBarItems = listOf(
    BottomBarItemState(BottomBarItem.PREV_CHAPTER),
    BottomBarItemState(BottomBarItem.SCROLL_TO_TOP),
    BottomBarItemState(BottomBarItem.TRANSLATE),
    BottomBarItemState(BottomBarItem.AUTO_SCROLL),
    BottomBarItemState(BottomBarItem.TTS),
    BottomBarItemState(BottomBarItem.QUOTES),
    BottomBarItemState(BottomBarItem.ORIENTATION),
    BottomBarItemState(BottomBarItem.SETTINGS),
    BottomBarItemState(BottomBarItem.EDIT),
    BottomBarItemState(BottomBarItem.NEXT_CHAPTER),
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
