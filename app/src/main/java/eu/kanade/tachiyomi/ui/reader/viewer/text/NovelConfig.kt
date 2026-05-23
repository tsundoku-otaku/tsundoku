package eu.kanade.tachiyomi.ui.reader.viewer.text

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerConfig
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.CenterNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelConfig(
    scope: CoroutineScope,
    readerPreferences: ReaderPreferences = Injekt.get(),
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
    }

    override var navigator: ViewerNavigation = defaultNavigation()
        set(value) {
            field = value.also { it.invertMode = tappingInverted }
        }

    override fun defaultNavigation(): ViewerNavigation {
        return LNavigation()
    }

    override fun updateNavigation(navigationMode: Int) {
        this.navigator = when (navigationMode) {
            0 -> defaultNavigation()
            1 -> LNavigation()
            2 -> KindlishNavigation()
            3 -> EdgeNavigation()
            4 -> RightAndLeftNavigation()
            ReaderPreferences.TAPZONE_CENTER_INDEX -> CenterNavigation()
            ReaderPreferences.TAPZONE_DISABLED_INDEX -> DisabledNavigation()
            else -> defaultNavigation()
        }
        if (initialNavigationConsumed) {
            navigationModeChangedListener?.invoke()
        } else {
            initialNavigationConsumed = true
        }
    }
}
