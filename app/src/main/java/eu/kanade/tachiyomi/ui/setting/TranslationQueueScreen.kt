package eu.kanade.tachiyomi.ui.setting

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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.translation.TranslationService
import tachiyomi.domain.translation.model.TranslationTask
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object TranslationQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val translationService = remember { Injekt.get<TranslationService>() }
        val progress by translationService.progressState.collectAsState()
        val isPaused by translationService.isPaused.collectAsState()
        val queueItems by translationService.queueState.collectAsState()

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_translation_queue),
                    navigateUp = navigator::pop,
                    navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (progress.isRunning && !progress.isCancelling) {
                            IconButton(
                                onClick = {
                                    if (isPaused) translationService.resume() else translationService.pause()
                                },
                            ) {
                                Icon(
                                    imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                    contentDescription = if (isPaused) {
                                        stringResource(MR.strings.pref_translation_resume)
                                    } else {
                                        stringResource(MR.strings.pref_translation_pause)
                                    },
                                )
                            }
                            IconButton(onClick = { translationService.cancel() }) {
                                Icon(
                                    imageVector = Icons.Filled.Stop,
                                    contentDescription = stringResource(MR.strings.pref_translation_cancel),
                                )
                            }
                        }
                        if (queueItems.isNotEmpty()) {
                            IconButton(onClick = { translationService.clearQueue() }) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteSweep,
                                    contentDescription = stringResource(MR.strings.pref_translation_clear_queue),
                                )
                            }
                        }
                    },
                )
            },
        ) { contentPadding ->
            if (queueItems.isEmpty() && !progress.isRunning) {
                EmptyScreen(
                    stringRes = MR.strings.pref_translation_status_idle,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
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
                                isPaused -> stringResource(MR.strings.pref_translation_status_paused, progress.completedChapters, progress.totalChapters)
                                else -> {
                                    val current = progress.currentChapterName ?: "..."
                                    val chunkInfo = if (progress.totalChunks > 1) {
                                        stringResource(MR.strings.pref_translation_status_chunk_info, progress.currentChunkIndex, progress.totalChunks)
                                    } else {
                                        ""
                                    }
                                    stringResource(MR.strings.pref_translation_status_translating, current, chunkInfo, progress.completedChapters, progress.totalChapters)
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

                // Queue list
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = queueItems,
                        key = { it.chapterId },
                    ) { task ->
                        TranslationQueueItem(
                            task = task,
                            isCurrentlyTranslating = progress.isRunning &&
                                task.status == tachiyomi.domain.translation.model.TranslationStatus.QUEUED &&
                                queueItems.indexOf(task) == 0,
                            onRemove = { translationService.dequeue(task.chapterId) },
                        )
                    }
                }
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
}
