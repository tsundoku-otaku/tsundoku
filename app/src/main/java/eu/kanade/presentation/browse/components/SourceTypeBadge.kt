package eu.kanade.presentation.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.source.SourceTypeTag
import eu.kanade.tachiyomi.source.typeTag
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun SourceTypeBadge(source: Source, modifier: Modifier = Modifier) {
    val typeTag = remember(source.id) {
        Injekt.get<SourceManager>().get(source.id)?.typeTag()
            ?: SourceTypeTag.JS.takeIf { source.isJsSource }
    } ?: return

    Text(
        text = typeTag.label,
        modifier = modifier
            .padding(start = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelSmall,
    )
}
