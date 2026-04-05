package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomBarEditorSheet(
    items: List<BottomBarItemState>,
    onItemsChange: (List<BottomBarItemState>) -> Unit,
    onDismiss: () -> Unit,
    // Pass in a lambda to resolve display info per item (icon + label)
    // so this composable stays generic and free of reader-specific context
    itemInfo: @Composable (BottomBarItem) -> Pair<ImageVector, String>,
) {
    val mutableItems = remember(items) { items.toMutableStateList() }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        mutableItems.add(to.index, mutableItems.removeAt(from.index))
    }

    ModalBottomSheet(
        onDismissRequest = {
            onItemsChange(mutableItems.toList())
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            text = "Customize Toolbar",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(mutableItems, key = { it.item.id }) { itemState ->
                ReorderableItem(reorderState, key = itemState.item.id) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
                    val (icon, label) = itemInfo(itemState.item)

                    Surface(shadowElevation = elevation) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DragHandle,
                                contentDescription = "Drag to reorder",
                                modifier = Modifier
                                    .draggableHandle()
                                    .padding(end = 12.dp),
                            )
                            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                            Text(label, modifier = Modifier.weight(1f))
                            Switch(
                                checked = itemState.enabled,
                                onCheckedChange = { checked ->
                                    val idx = mutableItems.indexOf(itemState)
                                    if (idx != -1) mutableItems[idx] = itemState.copy(enabled = checked)
                                },
                            )
                        }
                    }
                }
            }
        }

        TextButton(
            onClick = {
                mutableItems.clear()
                mutableItems.addAll(DefaultBottomBarItems.map { it.copy(enabled = it.defaultEnabled) })
            },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 8.dp),
        ) {
            Text(stringResource(MR.strings.label_default))
        }

        Spacer(Modifier.height(24.dp))
    }
}
