package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Reorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.quote.Quote
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

@Composable
fun QuotesSheet(
    quotes: List<Quote>,
    onDismiss: () -> Unit,
    onQuoteClick: (Quote) -> Unit,
    onQuoteDelete: (Quote) -> Unit,
    onQuoteUpdate: (Quote) -> Unit,
    onQuoteAdd: (String) -> Unit,
    onQuoteReorder: (List<Quote>) -> Unit,
) {
    // State to track which quote is being deleted
    val quoteToDelete = remember { mutableStateOf<Quote?>(null) }

    // State to track which quote is being viewed in detail
    val selectedQuote = remember { mutableStateOf<Quote?>(null) }

    // State to track which quote is being edited
    val editingQuote = remember { mutableStateOf<Quote?>(null) }
    val editedContent = remember { mutableStateOf("") }

    // State to track adding a new quote
    val showAddDialog = remember { mutableStateOf(false) }
    val newQuoteContent = remember { mutableStateOf("") }

    // State for reorder mode and drag
    val isReorderMode = remember { mutableStateOf(false) }
    val draggedIndex = remember { mutableIntStateOf(-1) }
    val targetIndex = remember { mutableIntStateOf(-1) }
    val dragOffset = remember { mutableFloatStateOf(0f) }
    val itemHeight = remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()

    // Mutable list for smooth reordering during drag
    val reorderedQuotes = remember { mutableStateListOf<Quote>() }

    // Initialize or update reorderedQuotes when quotes change
    if (reorderedQuotes.isEmpty() || reorderedQuotes.size != quotes.size) {
        reorderedQuotes.clear()
        reorderedQuotes.addAll(quotes)
    }

    // Show confirmation dialog if a quote is selected for deletion
    if (quoteToDelete.value != null) {
        AlertDialog(
            onDismissRequest = { quoteToDelete.value = null },
            title = { Text("Delete Quote") },
            text = { Text("Are you sure you want to delete this quote?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onQuoteDelete(quoteToDelete.value!!)
                        quoteToDelete.value = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { quoteToDelete.value = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Show quote detail dialog when a quote is selected
    if (selectedQuote.value != null) {
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

        AlertDialog(
            onDismissRequest = { selectedQuote.value = null },
            title = {
                Text(
                    text = selectedQuote.value?.chapterName ?: "",
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = selectedQuote.value?.content ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editingQuote.value = selectedQuote.value
                        editedContent.value = selectedQuote.value?.content ?: ""
                        selectedQuote.value = null
                    },
                ) {
                    Text("Edit")
                }
            },
            dismissButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            val quote = selectedQuote.value
                            if (quote != null) {
                                val textToCopy = "\"${quote.content}\"\n\n- ${quote.novelName}, ${quote.chapterName}"
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(textToCopy))
                            }
                            selectedQuote.value = null
                        },
                    ) {
                        Text("Copy")
                    }
                    TextButton(onClick = { selectedQuote.value = null }) {
                        Text("Close")
                    }
                }
            },
        )
    }

    // Show edit dialog when a quote is being edited
    if (editingQuote.value != null) {
        AlertDialog(
            onDismissRequest = { editingQuote.value = null },
            title = { Text("Edit Quote") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    TextField(
                        value = editedContent.value,
                        onValueChange = { editedContent.value = it },
                        label = { Text("Quote content") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updatedQuote = editingQuote.value?.copy(content = editedContent.value)
                        if (updatedQuote != null) {
                            onQuoteUpdate(updatedQuote)
                        }
                        editingQuote.value = null
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingQuote.value = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Show add quote dialog
    if (showAddDialog.value) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog.value = false
                newQuoteContent.value = ""
            },
            title = { Text("Add Quote") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    TextField(
                        value = newQuoteContent.value,
                        onValueChange = { newQuoteContent.value = it },
                        label = { Text("Quote content") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newQuoteContent.value.isNotBlank()) {
                            onQuoteAdd(newQuoteContent.value)
                        }
                        showAddDialog.value = false
                        newQuoteContent.value = ""
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddDialog.value = false
                        newQuoteContent.value = ""
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    // Function to handle drag move and update reorderedQuotes in real-time
    fun handleDragMove(dragAmount: Float) {
        dragOffset.floatValue += dragAmount
        val currentIndex = draggedIndex.intValue
        if (currentIndex >= 0 && itemHeight.floatValue > 0) {
            val newTargetIndex = (currentIndex + (dragOffset.floatValue / itemHeight.floatValue).toInt())
                .coerceIn(0, quotes.size - 1)
            
            if (newTargetIndex != targetIndex.intValue) {
                targetIndex.intValue = newTargetIndex
                // Update reorderedQuotes to show the item moving
                val item = reorderedQuotes.removeAt(currentIndex)
                reorderedQuotes.add(newTargetIndex, item)
                draggedIndex.intValue = newTargetIndex
                dragOffset.floatValue = 0f
            }
        }
    }

    // Function to handle drag end
    fun handleDragEnd() {
        // Properly compare lists to detect if reordering occurred
        val hasChanged = reorderedQuotes.size != quotes.size || 
            reorderedQuotes.zip(quotes).any { (reordered, original) -> reordered != original }
        
        if (hasChanged) {
            onQuoteReorder(reorderedQuotes.toList())
        }
        draggedIndex.intValue = -1
        targetIndex.intValue = -1
        dragOffset.floatValue = 0f
    }
    if (quotes.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
                    .padding(16.dp)
                    // Add bottom padding to keep quotes sheet above UI buttons
                    .padding(bottom = 80.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(TDMR.strings.action_quotes),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { isReorderMode.value = !isReorderMode.value },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Reorder,
                                contentDescription = "Reorder quotes",
                                tint = if (isReorderMode.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(
                            onClick = { showAddDialog.value = true },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "Add quote",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Icon(
                            painter = painterResource(R.drawable.ic_close_24dp),
                            contentDescription = stringResource(MR.strings.action_close),
                            modifier = Modifier
                                .size(24.dp)
                                .clickable(onClick = onDismiss),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // "No Quotes" message
                Text(
                    text = stringResource(TDMR.strings.quotes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
                    .padding(16.dp)
                    // Add bottom padding to keep quotes sheet above UI buttons
                    .padding(bottom = 80.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(TDMR.strings.action_quotes),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { showAddDialog.value = true },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "Add quote",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(
                            onClick = { isReorderMode.value = !isReorderMode.value },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Reorder,
                                contentDescription = "Reorder quotes",
                                tint = if (isReorderMode.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Icon(
                            painter = painterResource(R.drawable.ic_close_24dp),
                            contentDescription = stringResource(MR.strings.action_close),
                            modifier = Modifier
                                .size(24.dp)
                                .clickable(onClick = onDismiss),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Quotes list
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(reorderedQuotes) { index, quote ->
                        QuoteItem(
                            quote = quote,
                            index = index,
                            onQuoteClick = onQuoteClick,
                            onQuoteDelete = onQuoteDelete,
                            quoteToDelete = quoteToDelete,
                            selectedQuote = selectedQuote,
                            isReorderMode = isReorderMode.value,
                            draggedIndex = draggedIndex.intValue,
                            onDragStart = { 
                                draggedIndex.intValue = index
                                targetIndex.intValue = index
                            },
                            onDragEnd = { handleDragEnd() },
                            onDrag = { dragAmount -> handleDragMove(dragAmount) },
                            onItemHeightMeasured = { height -> itemHeight.floatValue = height },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteItem(
    quote: Quote,
    index: Int,
    onQuoteClick: (Quote) -> Unit,
    onQuoteDelete: (Quote) -> Unit,
    quoteToDelete: MutableState<Quote?>,
    selectedQuote: MutableState<Quote?>,
    isReorderMode: Boolean,
    draggedIndex: Int?,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (Float) -> Unit = {},
    onItemHeightMeasured: (Float) -> Unit = {},
) {
    val isBeingDragged = draggedIndex == index
    val elevation by animateDpAsState(
        targetValue = if (isBeingDragged) 16.dp else 0.dp,
        label = "elevation",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isBeingDragged) 1f else 0f)
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(8.dp),
                ambientColor = if (isBeingDragged) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent,
                spotColor = if (isBeingDragged) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent,
            )
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isBeingDragged) {
                    MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)
                } else {
                    MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                },
            )
            .onGloballyPositioned { coordinates ->
                onItemHeightMeasured(coordinates.size.height.toFloat())
            }
            .pointerInput(isReorderMode) {
                if (isReorderMode) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.y)
                        },
                    )
                }
            }
            .then(
                if (!isReorderMode) {
                    Modifier.clickable(onClick = { selectedQuote.value = quote })
                } else {
                    Modifier
                },
            )
            .padding(12.dp),
    ) {
        // Chapter info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isReorderMode) {
                    Icon(
                        imageVector = Icons.Outlined.Reorder,
                        contentDescription = "Drag to reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = quote.chapterName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!isReorderMode) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_24dp),
                    contentDescription = stringResource(MR.strings.action_delete),
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = { quoteToDelete.value = quote }),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Quote content
        Text(
            text = quote.content,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
