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
    onConfirm: (Boolean, Boolean, Boolean, Boolean) -> Unit,
) {
    var removeFromLibrary by remember { mutableStateOf(false) }
    var deleteDownloads by remember { mutableStateOf(false) }
    var clearChaptersFromDb by remember { mutableStateOf(false) }
    var deleteTranslations by remember { mutableStateOf(false) }

    data class CheckboxItem(
        val label: StringResource,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
    )

    val checkboxItems = remember(containsLocalManga, removeFromLibrary, deleteDownloads, clearChaptersFromDb, deleteTranslations) {
        buildList {
            add(CheckboxItem(MR.strings.manga_from_library, removeFromLibrary) { removeFromLibrary = it })
            if (!containsLocalManga) {
                add(CheckboxItem(MR.strings.downloaded_chapters, deleteDownloads) { deleteDownloads = it })
            }
            add(CheckboxItem(TDMR.strings.chapters_from_database, clearChaptersFromDb) { clearChaptersFromDb = it })
            add(CheckboxItem(TDMR.strings.translated_chapters, deleteTranslations) { deleteTranslations = it })
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = removeFromLibrary || deleteDownloads || clearChaptersFromDb || deleteTranslations,
                onClick = {
                    onDismissRequest()
                    onConfirm(
                        removeFromLibrary,
                        deleteDownloads && !containsLocalManga,
                        clearChaptersFromDb,
                        deleteTranslations,
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
                    )
                }
            }
        },
    )
}
