package eu.kanade.presentation.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Battery0Bar
import androidx.compose.material.icons.outlined.Battery1Bar
import androidx.compose.material.icons.outlined.Battery2Bar
import androidx.compose.material.icons.outlined.Battery3Bar
import androidx.compose.material.icons.outlined.Battery4Bar
import androidx.compose.material.icons.outlined.Battery5Bar
import androidx.compose.material.icons.outlined.Battery6Bar
import androidx.compose.material.icons.outlined.BatteryChargingFull
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
import androidx.compose.ui.layout.onSizeChanged
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
 * Full-width reading status bar that overlays the top or bottom of the novel reading view.
 *
 * Elements (time, chapter, progress, battery) are rendered left-to-right in [order], each
 * gated by its own show* flag. A collapse toggle is pinned to the far end. The battery
 * element optionally reflects charging state.
 *
 * @param chapterText       Pre-formatted chapter string respecting the display preference.
 * @param progressPercent   Current scroll position, 0-100.
 * @param order             Element ids in render order.
 * @param showTime          Whether to include the clock segment.
 * @param showChapter       Whether to include the chapter segment.
 * @param showProgress      Whether to include the progress % segment.
 * @param showBattery       Whether to include battery % + icon.
 * @param showCharging      When true, the battery icon reflects charging state.
 * @param backgroundColor   Reader's background color (matches current novel theme).
 * @param textColor         Reader's foreground/text color (matches current novel theme).
 * @param isCollapsed       When true, content is hidden; only the toggle icon is visible.
 * @param onToggleCollapse  Called when the user taps the collapse/expand icon.
 * @param onHeightChanged   Reports the measured pixel height so callers can offset overlays.
 */
@Composable
fun NovelStatusBar(
    chapterText: String?,
    progressPercent: Int,
    order: List<StatusBarItem>,
    showTime: Boolean,
    showChapter: Boolean,
    showProgress: Boolean,
    showBattery: Boolean,
    showCharging: Boolean,
    backgroundColor: Color,
    textColor: Color,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    size: String = "small",
    onHeightChanged: (Int) -> Unit = {},
) {
    val medium = size == "medium"
    val fontSize = if (medium) 14.sp else 11.sp
    val iconSize = if (medium) 18.dp else 14.dp
    val toggleSize = if (medium) 20.dp else 16.dp
    val verticalPadding = if (medium) 10.dp else 6.dp
    val labelStyle = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Normal, letterSpacing = 0.sp)
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
    var isCharging by remember { mutableStateOf(false) }
    val context = LocalContext.current
    DisposableEffect(context) {
        fun apply(intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) batteryPercent = level * 100 / scale
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent?.let(::apply)
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let(::apply)
        onDispose { context.unregisterReceiver(receiver) }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .onSizeChanged { onHeightChanged(it.height) }
            .padding(horizontal = 12.dp, vertical = verticalPadding),
    ) {
        // Cap chapter width so a long title ellipsizes instead of crowding fixed elements
        val chapterMaxWidth = maxWidth * 0.55f

        if (!isCollapsed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 22.dp)
                    .align(Alignment.Center),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                order.forEach { item ->
                    when (item) {
                        StatusBarItem.TIME -> if (showTime) {
                            Text(text = timeText, style = labelStyle, color = contentColor)
                        }

                        StatusBarItem.CHAPTER -> if (showChapter && chapterText != null) {
                            Text(
                                text = chapterText,
                                style = labelStyle,
                                color = contentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = chapterMaxWidth),
                            )
                        }

                        StatusBarItem.PROGRESS -> if (showProgress) {
                            Text(text = "$progressPercent%", style = labelStyle, color = contentColor)
                        }

                        StatusBarItem.BATTERY -> if (showBattery && batteryPercent >= 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = batteryIcon(batteryPercent, isCharging && showCharging),
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier.size(iconSize),
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(text = "$batteryPercent%", style = labelStyle, color = contentColor)
                            }
                        }
                    }
                }
            }
        }

        // Collapse/expand toggle, always visible, pinned to the right
        Icon(
            imageVector = if (isCollapsed) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (isCollapsed) "Expand status bar" else "Collapse status bar",
            tint = dimColor,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(toggleSize)
                .clickable(role = Role.Button, onClick = onToggleCollapse),
        )
    }
}

private fun batteryIcon(percent: Int, charging: Boolean): ImageVector = when {
    charging -> Icons.Outlined.BatteryChargingFull
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
