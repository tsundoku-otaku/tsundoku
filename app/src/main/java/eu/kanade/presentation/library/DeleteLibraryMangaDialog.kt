package eu.kanade.presentation.library

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun DeleteLibraryMangaDialog(
    containsLocalManga: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (
        removeFromLibrary: Boolean,
        deleteDownloads: Boolean,
        clearChaptersFromDb: Boolean,
        deleteTranslations: Boolean,
        clearCovers: Boolean,
        clearDescriptions: Boolean,
        clearTags: Boolean,
    ) -> Unit,
) {
    var removeFromLibrary by remember { mutableStateOf(false) }
    var deleteDownloads by remember { mutableStateOf(false) }
    var clearChaptersFromDb by remember { mutableStateOf(false) }
    var deleteTranslations by remember { mutableStateOf(false) }
    var clearCovers by remember { mutableStateOf(false) }
    var clearDescriptions by remember { mutableStateOf(false) }
    var clearTags by remember { mutableStateOf(false) }

    // Keep destructive clear options disabled when removing from library.
    // They are redundant because removing from library already clears associated data.
    fun onRemoveFromLibraryChanged(checked: Boolean) {
        removeFromLibrary = checked
        if (checked) {
            clearChaptersFromDb = false
            clearCovers = false
            clearDescriptions = false
            clearTags = false
        }
    }

    data class CheckboxItem(
        val label: StringResource,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
        val enabled: Boolean = true,
    )

    val checkboxItems = remember(
        containsLocalManga,
        removeFromLibrary,
        deleteDownloads,
        clearChaptersFromDb,
        deleteTranslations,
        clearCovers,
        clearDescriptions,
        clearTags,
    ) {
        buildList {
            add(CheckboxItem(MR.strings.manga_from_library, removeFromLibrary, { onRemoveFromLibraryChanged(it) }))
            if (!containsLocalManga) {
                add(CheckboxItem(MR.strings.downloaded_chapters, deleteDownloads, { deleteDownloads = it }))
            }
            add(
                CheckboxItem(TDMR.strings.chapters_from_database, clearChaptersFromDb || removeFromLibrary, {
                    clearChaptersFromDb =
                        it
                }, enabled = !removeFromLibrary),
            )
            add(CheckboxItem(TDMR.strings.translated_chapters, deleteTranslations, { deleteTranslations = it }))
            add(
                CheckboxItem(TDMR.strings.action_clear_covers, clearCovers || removeFromLibrary, {
                    clearCovers = it
                }, enabled = !removeFromLibrary),
            )
            add(
                CheckboxItem(TDMR.strings.action_clear_descriptions, clearDescriptions || removeFromLibrary, {
                    clearDescriptions =
                        it
                }, enabled = !removeFromLibrary),
            )
            add(
                CheckboxItem(TDMR.strings.action_clear_tags, clearTags || removeFromLibrary, {
                    clearTags = it
                }, enabled = !removeFromLibrary),
            )
        }
    }

    val anyChecked = removeFromLibrary || deleteDownloads || clearChaptersFromDb ||
        deleteTranslations || clearCovers || clearDescriptions || clearTags

    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = anyChecked,
                onClick = {
                    onDismissRequest()
                    onConfirm(
                        removeFromLibrary,
                        deleteDownloads && !containsLocalManga,
                        clearChaptersFromDb,
                        deleteTranslations,
                        clearCovers,
                        clearDescriptions,
                        clearTags,
                    )
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_remove))
        },
        text = {
            Column {
                checkboxItems.forEach { item ->
                    LabeledCheckbox(
                        label = stringResource(item.label),
                        checked = item.checked,
                        onCheckedChange = item.onCheckedChange,
                        enabled = item.enabled,
                    )
                }
            }
        },
    )
}
