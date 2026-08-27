package eu.kanade.tachiyomi.ui.reader.viewer.text

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerConfig
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.BottomNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.CenterNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelConfig(
    scope: CoroutineScope,
    private val readerPreferences: ReaderPreferences = Injekt.get(),
) : ViewerConfig(readerPreferences, scope) {

    // suppress tap-zone preview on initial flow emit during construction
    private var initialNavigationConsumed = false

    init {
        readerPreferences.navigationModeNovel
            .register({ navigationMode = it }, { updateNavigation(navigationMode) })

        readerPreferences.novelNavInverted
            .register({ tappingInverted = it }, { navigator.invertMode = it })
        readerPreferences.novelNavInverted.changes()
            .drop(1)
            .onEach { navigationModeChangedListener?.invoke() }
            .launchIn(scope)

        readerPreferences.novelBottomZoneHeight.changes()
            .drop(1)
            .onEach { if (navigationMode == ReaderPreferences.TAPZONE_BOTTOM_INDEX) updateNavigation(navigationMode) }
            .launchIn(scope)
    }

    override var navigator: ViewerNavigation = defaultNavigation()
        set(value) {
            field = value.also { it.invertMode = tappingInverted }
        }

    override fun defaultNavigation(): ViewerNavigation {
        return LNavigation()
    }

    override fun updateNavigation(navigationMode: Int) {
        coerceInvertForZoneOnlyMode(navigationMode)
        this.navigator = when (navigationMode) {
            0 -> defaultNavigation()
            1 -> LNavigation()
            2 -> KindlishNavigation()
            3 -> EdgeNavigation()
            4 -> RightAndLeftNavigation()
            ReaderPreferences.TAPZONE_CENTER_INDEX -> CenterNavigation()
            ReaderPreferences.TAPZONE_CENTER_LARGE_INDEX -> CenterNavigation(large = true)
            ReaderPreferences.TAPZONE_BOTTOM_INDEX ->
                BottomNavigation(readerPreferences.novelBottomZoneHeight.get() / 100f)
            ReaderPreferences.TAPZONE_DISABLED_INDEX -> DisabledNavigation()
            else -> defaultNavigation()
        }
        if (initialNavigationConsumed) {
            navigationModeChangedListener?.invoke()
        } else {
            initialNavigationConsumed = true
        }
    }

    /**
     * Zone-only modes expose a restricted invert choice in settings (center: none only;
     * bottom: none or vertical). A wider value carried over from a previous nav mode would
     * leave the settings chip row showing nothing selected while still shifting the zone.
     * Snap it to the nearest equivalent so the navigator and the chip row agree.
     */
    private fun coerceInvertForZoneOnlyMode(navigationMode: Int) {
        val pref = readerPreferences.novelNavInverted
        val current = pref.get()
        val target = when (navigationMode) {
            ReaderPreferences.TAPZONE_CENTER_INDEX,
            ReaderPreferences.TAPZONE_CENTER_LARGE_INDEX,
            -> ReaderPreferences.TappingInvertMode.NONE
            ReaderPreferences.TAPZONE_BOTTOM_INDEX -> when (current) {
                // Horizontal invert is a no-op on the full-width bottom rect; both == vertical.
                ReaderPreferences.TappingInvertMode.HORIZONTAL -> ReaderPreferences.TappingInvertMode.NONE
                ReaderPreferences.TappingInvertMode.BOTH -> ReaderPreferences.TappingInvertMode.VERTICAL
                else -> current
            }
            else -> return
        }
        if (target != current) {
            tappingInverted = target
            pref.set(target)
        }
    }
}
