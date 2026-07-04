package eu.kanade.presentation.more.settings.screen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.reader.DefaultStatusBarOrder
import eu.kanade.presentation.reader.StatusBarItem
import eu.kanade.presentation.reader.deserializeStatusBarOrder
import eu.kanade.presentation.reader.serializeStatusBarOrder
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class StatusBarElementsScreen : Screen {

    private data class ElementRow(val item: StatusBarItem, val enabled: Boolean)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val preferences = remember { Injekt.get<ReaderPreferences>() }

        // Chapter visibility is the OR of two independent flags (number/title, see
        // ReaderActivity's showChapterSegment), so this row's switch reads and writes both
        // together rather than aliasing to just one of them.
        fun isItemEnabled(item: StatusBarItem): Boolean = when (item) {
            StatusBarItem.TIME -> preferences.novelStatusBarShowTime.get()
            StatusBarItem.CHAPTER ->
                preferences.novelStatusBarShowChapterNumber.get() ||
                    preferences.novelStatusBarShowChapterTitle.get()
            StatusBarItem.PROGRESS -> preferences.novelStatusBarShowProgress.get()
            StatusBarItem.BATTERY -> preferences.novelStatusBarShowBattery.get()
        }

        fun setItemEnabled(item: StatusBarItem, enabled: Boolean) {
            when (item) {
                StatusBarItem.TIME -> preferences.novelStatusBarShowTime.set(enabled)
                StatusBarItem.CHAPTER -> {
                    preferences.novelStatusBarShowChapterNumber.set(enabled)
                    preferences.novelStatusBarShowChapterTitle.set(enabled)
                }
                StatusBarItem.PROGRESS -> preferences.novelStatusBarShowProgress.set(enabled)
                StatusBarItem.BATTERY -> preferences.novelStatusBarShowBattery.set(enabled)
            }
        }

        val rows = remember {
            preferences.novelStatusBarOrder.get().deserializeStatusBarOrder()
                .map { ElementRow(it, isItemEnabled(it)) }
                .toMutableStateList()
        }

        fun persistOrder() {
            preferences.novelStatusBarOrder.set(rows.map { it.item }.serializeStatusBarOrder())
        }

        val lazyListState = rememberLazyListState()
        val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
            rows.add(to.index, rows.removeAt(from.index))
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(TDMR.strings.pref_novel_status_bar_customize)) },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                rows.clear()
                                rows.addAll(DefaultStatusBarOrder.map { ElementRow(it, true) })
                                persistOrder()
                                DefaultStatusBarOrder.forEach { setItemEnabled(it, true) }
                            },
                        ) {
                            Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                        }
                    },
                )
            },
        ) { paddingValues ->
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = paddingValues,
            ) {
                items(rows, key = { it.item.id }) { row ->
                    ReorderableItem(reorderState, key = row.item.id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
                        Surface(shadowElevation = elevation) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DragHandle,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .draggableHandle(onDragStopped = { persistOrder() })
                                        .padding(end = 12.dp),
                                )
                                Text(elementLabel(row.item), modifier = Modifier.weight(1f))
                                Switch(
                                    checked = row.enabled,
                                    onCheckedChange = { checked ->
                                        val idx = rows.indexOfFirst { it.item == row.item }
                                        if (idx != -1) {
                                            rows[idx] = row.copy(enabled = checked)
                                            setItemEnabled(row.item, checked)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun elementLabel(item: StatusBarItem): String = stringResource(
        when (item) {
            StatusBarItem.TIME -> TDMR.strings.novel_status_bar_element_time
            StatusBarItem.CHAPTER -> TDMR.strings.novel_status_bar_element_chapter
            StatusBarItem.PROGRESS -> TDMR.strings.novel_status_bar_element_progress
            StatusBarItem.BATTERY -> TDMR.strings.novel_status_bar_element_battery
        },
    )
}
