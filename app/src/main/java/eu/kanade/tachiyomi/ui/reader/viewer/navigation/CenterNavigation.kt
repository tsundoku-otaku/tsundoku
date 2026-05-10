package eu.kanade.tachiyomi.ui.reader.viewer.navigation

import android.graphics.RectF
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation

/**
 * Small center zone that maps to the MENU action (show app bars)
 */
class CenterNavigation : ViewerNavigation() {

    override var regionList: List<Region> = listOf(
        Region(
            rectF = RectF(0.4f, 0.4f, 0.6f, 0.6f),
            type = NavigationRegion.MENU,
        ),
    )
}
