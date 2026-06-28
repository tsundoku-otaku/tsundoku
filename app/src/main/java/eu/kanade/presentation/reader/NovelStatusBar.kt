package eu.kanade.presentation.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Battery0Bar
import androidx.compose.material.icons.outlined.Battery1Bar
import androidx.compose.material.icons.outlined.Battery2Bar
import androidx.compose.material.icons.outlined.Battery3Bar
import androidx.compose.material.icons.outlined.Battery4Bar
import androidx.compose.material.icons.outlined.Battery5Bar
import androidx.compose.material.icons.outlined.Battery6Bar
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-width reading status bar that overlays the bottom of the novel reading view.
 *
 * Displays time (left), chapter (center), and battery+progress (right). A small collapse
 * toggle at the far right lets the user hide the content — collapsed state shows only the
 * background strip matching the reader theme so it blends into the page.
 *
 * @param chapterText       Pre-formatted chapter string respecting novelChapterTitleDisplay.
 * @param progressPercent   Current scroll position, 0–100.
 * @param showTime          Whether to include the clock segment.
 * @param showBattery       Whether to include battery % + icon.
 * @param showChapter       Whether to include the chapter segment.
 * @param showProgress      Whether to include the progress % segment.
 * @param backgroundColor   Reader's background color (matches current novel theme).
 * @param textColor         Reader's foreground/text color (matches current novel theme).
 * @param isCollapsed       When true, content is hidden; only the toggle icon is visible.
 * @param onToggleCollapse  Called when the user taps the collapse/expand icon.
 */
@Composable
fun NovelStatusBar(
    chapterText: String?,
    progressPercent: Int,
    showTime: Boolean,
    showBattery: Boolean,
    showChapter: Boolean,
    showProgress: Boolean,
    backgroundColor: Color,
    textColor: Color,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp)
    val contentColor = textColor
    val dimColor = textColor.copy(alpha = 0.45f)

    // Clock — aligned to the minute boundary so it never lags
    var timeText by remember { mutableStateOf(currentTimeHHmm()) }
    LaunchedEffect(Unit) {
        val msUntilNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
        delay(msUntilNextMinute)
        timeText = currentTimeHHmm()
        while (true) {
            delay(60_000L)
            timeText = currentTimeHHmm()
        }
    }

    // Battery — ACTION_BATTERY_CHANGED is sticky so the current level is available immediately
    var batteryPercent by remember { mutableIntStateOf(-1) }
    val context = LocalContext.current
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) batteryPercent = level * 100 / scale
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) batteryPercent = level * 100 / scale
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // Left: time
        if (!isCollapsed && showTime) {
            Text(
                text = timeText,
                style = labelStyle,
                color = contentColor,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }

        // Center: chapter • progress
        if (!isCollapsed) {
            val centerText = buildString {
                if (showChapter && chapterText != null) append(chapterText)
                if (showChapter && chapterText != null && showProgress) append(" • ")
                if (showProgress) append("$progressPercent%")
            }
            if (centerText.isNotEmpty()) {
                Text(
                    text = centerText,
                    style = labelStyle,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 48.dp),
                )
            }
        }

        // Right: battery icon + % + toggle
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isCollapsed) {
                if (showBattery && batteryPercent >= 0) {
                    Icon(
                        imageVector = batteryIcon(batteryPercent),
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "$batteryPercent%",
                        style = labelStyle,
                        color = contentColor,
                    )
                }
                Spacer(Modifier.width(6.dp))
            }

            // Collapse/expand toggle — always visible
            Icon(
                imageVector = if (isCollapsed) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (isCollapsed) "Expand status bar" else "Collapse status bar",
                tint = dimColor,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(role = Role.Button, onClick = onToggleCollapse),
            )
        }
    }
}

private fun batteryIcon(percent: Int): ImageVector = when {
    percent <= 14 -> Icons.Outlined.Battery0Bar
    percent <= 28 -> Icons.Outlined.Battery1Bar
    percent <= 42 -> Icons.Outlined.Battery2Bar
    percent <= 57 -> Icons.Outlined.Battery3Bar
    percent <= 71 -> Icons.Outlined.Battery4Bar
    percent <= 85 -> Icons.Outlined.Battery5Bar
    else -> Icons.Outlined.Battery6Bar
}

private fun currentTimeHHmm(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
