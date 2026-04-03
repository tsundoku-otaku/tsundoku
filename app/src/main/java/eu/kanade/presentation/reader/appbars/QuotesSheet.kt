package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.quote.Quote
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun QuotesSheet(
    quotes: List<Quote>,
    onDismiss: () -> Unit,
    onQuoteClick: (Quote) -> Unit,
    onQuoteDelete: (Quote) -> Unit,
    onQuoteUpdate: (Quote) -> Unit,
) {
    // State to track which quote is being deleted
    val quoteToDelete = remember { mutableStateOf<Quote?>(null) }

    // State to track which quote is being viewed in detail
    val selectedQuote = remember { mutableStateOf<Quote?>(null) }

    // State to track which quote is being edited
    val editingQuote = remember { mutableStateOf<Quote?>(null) }
    val editedContent = remember { mutableStateOf("") }

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
        AlertDialog(
            onDismissRequest = { selectedQuote.value = null },
            title = { Text(selectedQuote.value?.chapterName ?: "") },
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
                TextButton(onClick = { selectedQuote.value = null }) {
                    Text("Close")
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
                    Icon(
                        painter = painterResource(R.drawable.ic_close_24dp),
                        contentDescription = stringResource(MR.strings.action_close),
                        modifier = Modifier
                            .size(24.dp)
                            .clickable(onClick = onDismiss),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
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
                    Icon(
                        painter = painterResource(R.drawable.ic_close_24dp),
                        contentDescription = stringResource(MR.strings.action_close),
                        modifier = Modifier
                            .size(24.dp)
                            .clickable(onClick = onDismiss),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Quotes list
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(quotes) { quote ->
                        QuoteItem(
                            quote = quote,
                            onQuoteClick = onQuoteClick,
                            onQuoteDelete = onQuoteDelete,
                            quoteToDelete = quoteToDelete,
                            selectedQuote = selectedQuote,
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
    onQuoteClick: (Quote) -> Unit,
    onQuoteDelete: (Quote) -> Unit,
    quoteToDelete: MutableState<Quote?>,
    selectedQuote: MutableState<Quote?>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
            .clickable(onClick = { selectedQuote.value = quote })
            .padding(12.dp),
    ) {
        // Chapter info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = quote.chapterName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                painter = painterResource(R.drawable.ic_close_24dp),
                contentDescription = stringResource(MR.strings.action_delete),
                modifier = Modifier
                    .size(20.dp)
                    .clickable(onClick = { quoteToDelete.value = quote }),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
