package eu.kanade.tachiyomi.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.domain.translation.model.TranslationProgress
import tachiyomi.domain.translation.model.TranslationTask
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun TranslationQueueContent(
    progress: TranslationProgress,
    isPaused: Boolean,
    queueItems: List<TranslationTask>,
    currentTranslatingChapterId: Long?,
    onMoveMangaToTop: (Long) -> Unit,
    onRemoveAllForManga: (Long) -> Unit,
    onRemoveTask: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Status bar
        if (progress.isRunning) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val statusText = when {
                        progress.isCancelling -> stringResource(MR.strings.pref_translation_status_cancelling)
                        isPaused -> stringResource(
                            MR.strings.pref_translation_status_paused,
                            progress.completedChapters,
                            progress.totalChapters,
                        )
                        else -> {
                            val current = progress.currentChapterName ?: "..."
                            val chunkInfo = if (progress.totalChunks > 1) {
                                stringResource(
                                    MR.strings.pref_translation_status_chunk_info,
                                    progress.currentChunkIndex,
                                    progress.totalChunks,
                                )
                            } else {
                                ""
                            }
                            stringResource(
                                MR.strings.pref_translation_status_translating,
                                current,
                                chunkInfo,
                                progress.completedChapters,
                                progress.totalChapters,
                            )
                        }
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (progress.currentChapterProgress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress.currentChapterProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        // Queue list grouped by series
        val grouped = remember(queueItems) { queueItems.groupBy { it.mangaId } }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            grouped.forEach { (mangaId, tasks) ->
                item(key = "manga-$mangaId") {
                    TranslationQueueGroupHeader(
                        title = tasks.first().mangaTitle.ifBlank { "#$mangaId" },
                        count = tasks.size,
                        onMoveToTop = { onMoveMangaToTop(mangaId) },
                        onRemoveAll = { onRemoveAllForManga(mangaId) },
                    )
                }
                items(
                    items = tasks,
                    key = { it.chapterId },
                ) { task ->
                    TranslationQueueItem(
                        task = task,
                        isCurrentlyTranslating = progress.isRunning &&
                            task.chapterId == currentTranslatingChapterId,
                        onRemove = { onRemoveTask(task.chapterId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TranslationQueueGroupHeader(
    title: String,
    count: Int,
    onMoveToTop: () -> Unit,
    onRemoveAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onMoveToTop) {
            Icon(
                imageVector = Icons.Filled.VerticalAlignTop,
                contentDescription = stringResource(MR.strings.action_move_to_top_all_for_series),
            )
        }
        IconButton(onClick = onRemoveAll) {
            Icon(
                imageVector = Icons.Filled.DeleteSweep,
                contentDescription = stringResource(MR.strings.cancel_all_for_series),
            )
        }
    }
}

@Composable
private fun TranslationQueueItem(
    task: TranslationTask,
    isCurrentlyTranslating: Boolean,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentlyTranslating) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = task.chapterName.ifBlank { "#${task.chapterId}" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${task.sourceLanguage} → ${task.targetLanguage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (task.errorMessage != null) {
                    Text(
                        text = task.errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(MR.strings.action_remove),
                )
            }
        }
    }
}
