package eu.kanade.presentation.manga.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.library.components.EpubExportOptions
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ExportEpubDialog(
    manga: Manga,
    chapters: List<Chapter>,
    onDismissRequest: () -> Unit,
    onExport: (Uri, EpubExportOptions) -> Unit,
) {
    var filename by remember { mutableStateOf(sanitizeFilename(manga.title) + ".epub") }
    var downloadedOnly by remember { mutableStateOf(false) }
    var translationMode by remember { mutableStateOf(TranslationMode.ORIGINAL) }
    var includeChapterCount by remember { mutableStateOf(false) }
    var includeChapterRange by remember { mutableStateOf(false) }
    var includeStatus by remember { mutableStateOf(false) }

    // "Both" mode produces two EPUB files → request a ZIP container
    val mimeType = if (translationMode == TranslationMode.BOTH) "application/zip" else "application/epub+zip"

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(mimeType),
    ) { uri ->
        if (uri != null) {
            onExport(
                uri,
                EpubExportOptions(
                    downloadedOnly = downloadedOnly,
                    translationMode = translationMode,
                    includeChapterCount = includeChapterCount,
                    includeChapterRange = includeChapterRange,
                    includeStatus = includeStatus,
                ),
            )
            onDismissRequest()
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(TDMR.strings.action_export_epub)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(TDMR.strings.epub_chapters_export_count, chapters.size),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = filename,
                    onValueChange = { filename = it },
                    label = { Text(stringResource(TDMR.strings.epub_filename)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Export options
                Text(
                    text = stringResource(TDMR.strings.epub_content_options),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                CheckboxItem(
                    label = stringResource(TDMR.strings.epub_downloaded_only),
                    checked = downloadedOnly,
                    onClick = { downloadedOnly = !downloadedOnly },
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(TDMR.strings.epub_translation_mode),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                RadioItem(
                    label = stringResource(TDMR.strings.epub_translation_original),
                    selected = translationMode == TranslationMode.ORIGINAL,
                    onClick = { translationMode = TranslationMode.ORIGINAL },
                )

                RadioItem(
                    label = stringResource(TDMR.strings.epub_translation_translated),
                    selected = translationMode == TranslationMode.TRANSLATED,
                    onClick = { translationMode = TranslationMode.TRANSLATED },
                )

                RadioItem(
                    label = stringResource(TDMR.strings.epub_translation_both),
                    selected = translationMode == TranslationMode.BOTH,
                    onClick = { translationMode = TranslationMode.BOTH },
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(TDMR.strings.epub_filename_options),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                CheckboxItem(
                    label = stringResource(TDMR.strings.epub_include_chapter_count),
                    checked = includeChapterCount,
                    onClick = { includeChapterCount = !includeChapterCount },
                )

                CheckboxItem(
                    label = stringResource(TDMR.strings.epub_include_chapter_range),
                    checked = includeChapterRange,
                    onClick = { includeChapterRange = !includeChapterRange },
                )

                CheckboxItem(
                    label = stringResource(TDMR.strings.epub_include_status),
                    checked = includeStatus,
                    onClick = { includeStatus = !includeStatus },
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (downloadedOnly) {
                        stringResource(TDMR.strings.epub_downloaded_only_info)
                    } else {
                        stringResource(TDMR.strings.epub_source_info)
                    } + when (translationMode) {
                        TranslationMode.TRANSLATED ->
                            " " +
                                stringResource(TDMR.strings.epub_translation_translated_info)
                        TranslationMode.BOTH -> " " + stringResource(TDMR.strings.epub_translation_both_info)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val launchName = if (translationMode == TranslationMode.BOTH) {
                        filename.removeSuffix(".epub") + ".zip"
                    } else {
                        filename
                    }
                    launcher.launch(launchName)
                },
            ) {
                Text(stringResource(TDMR.strings.action_export_epub))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

private fun sanitizeFilename(name: String): String {
    return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .take(200) // Limit filename length
}
