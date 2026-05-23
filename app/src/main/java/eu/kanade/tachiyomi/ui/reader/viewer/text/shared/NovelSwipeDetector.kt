package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import android.view.MotionEvent
import kotlin.math.abs

private const val SWIPE_THRESHOLD = 150
private const val SWIPE_VELOCITY_THRESHOLD = 200
private const val DIRECTION_RATIO = 1.5f

fun handleNovelFlingGesture(
    e1: MotionEvent?,
    e2: MotionEvent,
    velocityX: Float,
    _velocityY: Float,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Boolean {
    if (e1 == null) return false
    val diffX = e2.x - e1.x
    val diffY = e2.y - e1.y
    val absDiffX = abs(diffX)
    val absDiffY = abs(diffY)
    if (absDiffX > absDiffY * DIRECTION_RATIO &&
        absDiffX > SWIPE_THRESHOLD &&
        abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
    ) {
        if (diffX > 0) onPrevious() else onNext()
        return true
    }
    return false
}
