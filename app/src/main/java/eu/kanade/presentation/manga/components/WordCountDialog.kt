package eu.kanade.presentation.manga.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.domain.manga.model.WordDensity
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.NumberFormat

/**
 * Shows the progress and outcome of counting a novel's downloaded chapters.
 *
 * @param result null while counting is still running.
 */
@Composable
fun WordCountDialog(
    checkedChapters: Int,
    chaptersToCount: Int,
    result: WordDensity?,
    onDismissRequest: () -> Unit,
) {
    val isCounting = result == null && chaptersToCount > 0
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                val label = if (isCounting) MR.strings.action_cancel else MR.strings.action_ok
                Text(text = stringResource(label))
            }
        },
        title = { Text(text = stringResource(TDMR.strings.action_word_count)) },
        text = {
            when {
                chaptersToCount == 0 -> Text(text = stringResource(TDMR.strings.word_count_no_downloads))
                result == null -> CountingProgress(checkedChapters, chaptersToCount)
                result.countedChapters == 0 -> Text(text = stringResource(TDMR.strings.word_count_unreadable))
                else -> WordCountResult(result)
            }
        },
    )
}

@Composable
private fun CountingProgress(checkedChapters: Int, chaptersToCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = stringResource(TDMR.strings.word_count_progress, checkedChapters, chaptersToCount))
        LinearProgressIndicator(
            progress = { checkedChapters.toFloat() / chaptersToCount },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WordCountResult(result: WordDensity) {
    val numberFormat = remember { NumberFormat.getIntegerInstance() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatRow(
            label = stringResource(TDMR.strings.word_count_total_words),
            value = numberFormat.format(result.totalWords),
        )
        StatRow(
            label = stringResource(TDMR.strings.word_count_average_words),
            value = numberFormat.format(result.averageWords),
        )
        StatRow(
            label = stringResource(TDMR.strings.word_count_chapters_counted),
            value = stringResource(
                TDMR.strings.word_count_chapters_counted_value,
                numberFormat.format(result.countedChapters),
                numberFormat.format(result.totalChapters),
            ),
        )
        if (result.notDownloadedChapters > 0) {
            CoverageNote(
                text = stringResource(
                    TDMR.strings.word_count_not_downloaded,
                    numberFormat.format(result.notDownloadedChapters),
                    numberFormat.format(result.totalChapters),
                ),
            )
        }
        if (result.unreadableChapters > 0) {
            CoverageNote(
                text = stringResource(
                    TDMR.strings.word_count_unreadable_some,
                    numberFormat.format(result.unreadableChapters),
                    numberFormat.format(result.totalChapters),
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WordDensityBadge(tier = result.tier)
            Text(
                text = wordDensityTierDescription(result.tier, numberFormat),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CoverageNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Text(text = value, fontWeight = FontWeight.SemiBold)
    }
}

/** Square tier indicator, one tier per [WordDensity.TIER_STEP_WORDS] average words. */
@Composable
private fun WordDensityBadge(tier: Int) {
    val description = stringResource(TDMR.strings.word_density_indicator, tier)
    Box(
        modifier = Modifier
            .size(40.dp)
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = tier.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun wordDensityTierDescription(tier: Int, numberFormat: NumberFormat): String {
    return stringResource(
        TDMR.strings.word_density_indicator_range,
        tier,
        numberFormat.format(WordDensity.tierMinWords(tier)),
        numberFormat.format(WordDensity.tierMaxWords(tier)),
    )
}
