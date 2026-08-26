package eu.kanade.tachiyomi.ui.reader.viewer.navigation

import android.graphics.RectF
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation

class BottomNavigation(heightFraction: Float = 0.12f) : ViewerNavigation() {

    override var regionList: List<Region> = listOf(
        Region(
            rectF = rect(heightFraction),
            type = NavigationRegion.MENU,
        ),
    )

    companion object {
        fun rect(heightFraction: Float): RectF = RectF(0f, 1f - heightFraction.coerceIn(0.02f, 0.5f), 1f, 1f)
    }
}
