package eu.kanade.tachiyomi.ui.reader.viewer.navigation

import android.graphics.RectF
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation

/**
 * Center zone that maps to the MENU action (show app bars). Everything outside the
 * zone is left untouched (no NEXT/PREV) so continuous scrolling isn't interrupted.
 *
 * @param large Use the medium-sized zone instead of the default small one.
 */
class CenterNavigation(large: Boolean = false) : ViewerNavigation() {

    override var regionList: List<Region> = listOf(
        Region(
            rectF = if (large) RectF(0.3f, 0.3f, 0.7f, 0.7f) else RectF(0.4f, 0.4f, 0.6f, 0.6f),
            type = NavigationRegion.MENU,
        ),
    )
}
