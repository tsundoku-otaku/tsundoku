package tachiyomi.presentation.core.components.material

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Pull must reach 1.5× the normal threshold to enter force-refresh zone.
private const val FORCE_THRESHOLD = 1.5f

// Milliseconds the user must hold in the force zone to trigger force refresh.
private const val FORCE_HOLD_MS = 3_000L

// Releasing within this window after entering the force zone counts as a normal refresh.
private const val FORCE_GRACE_MS = 1_000L

// Matches Material3's internal SpinnerContainerSize used in IndicatorBox.
private val SpinnerContainerSize = 40.dp

// Indicator ring drawn around the pull indicator when in the force zone.
private val ForceRingSize = 48.dp

/**
 * @param refreshing Whether the layout is currently refreshing
 * @param onRefresh Lambda which is invoked when a swipe to refresh gesture is completed.
 * @param enabled Whether the layout should react to swipe gestures or not.
 * @param indicatorPadding Content padding for the indicator, to inset the indicator in if required.
 * @param onForceRefresh Optional lambda invoked when the user holds the pull in the force zone for
 *   3 seconds. When null, the force-refresh path is disabled entirely.
 * @param content The content containing a vertically scrollable composable.
 */
@Composable
fun PullRefresh(
    refreshing: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorPadding: PaddingValues = PaddingValues(0.dp),
    onForceRefresh: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()
    val density = LocalDensity.current

    // Pre-computed in composition; read lazily inside graphicsLayer to avoid recomposition.
    val maxDistancePx = with(density) { PullToRefreshDefaults.PositionalThreshold.roundToPx().toFloat() }
    val spinnerSizePx = with(density) { SpinnerContainerSize.roundToPx().toFloat() }

    val inForceZone by remember(onForceRefresh) {
        derivedStateOf { onForceRefresh != null && state.distanceFraction >= FORCE_THRESHOLD }
    }

    var forceProgress by remember { mutableFloatStateOf(0f) }
    var holdEnteredAt by remember { mutableLongStateOf(0L) }
    // Safety flag: timer completed and force refresh was already called — ignore the upcoming release.
    var forceTriggered by remember { mutableStateOf(false) }

    LaunchedEffect(inForceZone) {
        if (inForceZone) {
            holdEnteredAt = System.currentTimeMillis()
            val start = holdEnteredAt
            while (true) {
                val elapsed = System.currentTimeMillis() - start
                forceProgress = (elapsed.toFloat() / FORCE_HOLD_MS).coerceIn(0f, 1f)
                if (elapsed >= FORCE_HOLD_MS) {
                    forceTriggered = true
                    onForceRefresh!!()
                    break
                }
                delay(16)
            }
        } else {
            forceProgress = 0f
            holdEnteredAt = 0L
        }
    }

    val wrappedOnRefresh = {
        when {
            forceTriggered -> forceTriggered = false // timer already fired, ignore release
            inForceZone -> {
                val heldMs = if (holdEnteredAt > 0L) System.currentTimeMillis() - holdEnteredAt else 0L
                if (heldMs < FORCE_GRACE_MS) onRefresh() // accidental overpull — treat as normal
                // else: deliberate hold that didn't reach 3 s — cancel silently
            }
            else -> onRefresh()
        }
    }

    val containerColor by animateColorAsState(
        targetValue = if (inForceZone) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(200),
        label = "pullIndicatorContainer",
    )
    val indicatorColor by animateColorAsState(
        targetValue = if (inForceZone) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "pullIndicatorContent",
    )

    // State objects for deferred reads inside graphicsLayer (avoids full recomposition per frame).
    val ringAlphaState = animateFloatAsState(
        targetValue = if (inForceZone && !refreshing) 1f else 0f,
        animationSpec = tween(200),
        label = "forceRingAlpha",
    )

    Box(
        modifier = modifier.pullToRefresh(
            isRefreshing = refreshing,
            state = state,
            enabled = enabled,
            onRefresh = wrappedOnRefresh,
        ),
    ) {
        content()

        // Fixed-size container prevents layout jumps when the ring appears/disappears.
        // Size matches ForceRingSize so the indicator stays centered at (4dp, 4dp) within it.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(indicatorPadding)
                .size(ForceRingSize),
            contentAlignment = Alignment.Center,
        ) {
            PullToRefreshDefaults.Indicator(
                isRefreshing = refreshing,
                state = state,
                containerColor = containerColor,
                color = indicatorColor,
            )

            // Progress ring drawn around the indicator.
            // Applies the same graphicsLayer translation that Material3's IndicatorBox uses
            // internally so the ring tracks the indicator as it slides in/out with the pull gesture.
            // Formula from M3 1.4.0 PullToRefresh.kt IndicatorBox:
            //   translationY = distanceFraction * maxDistance.roundToPx() - containerSize
            // With our 48dp ring centered in the fixed box over the 40dp indicator, the centers
            // stay aligned throughout the pull motion.
            CircularProgressIndicator(
                progress = { forceProgress },
                modifier = Modifier
                    .size(ForceRingSize)
                    .graphicsLayer {
                        alpha = ringAlphaState.value
                        translationY = state.distanceFraction * maxDistancePx - spinnerSizePx
                    },
                color = MaterialTheme.colorScheme.error,
                trackColor = Color.Transparent,
                strokeWidth = 3.dp,
            )
        }
    }
}
