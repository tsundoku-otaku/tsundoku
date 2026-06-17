package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.browse.extension.NovelExtensionReposScreen
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.authenticate
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import mihon.domain.extension.interactor.GetExtensionStoreCountAsFlow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsBrowseScreen : SearchableSettings {

    override val supportsReset: Boolean get() = true

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.browse

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
        val getExtensionStoreCountAsFlow = remember { Injekt.get<GetExtensionStoreCountAsFlow>() }

        val reposCount by getExtensionStoreCountAsFlow().collectAsState(0)

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_sources),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.hideInLibraryItems,
                        title = stringResource(MR.strings.pref_hide_in_library_items),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = sourcePreferences.pageLoadDelay,
                        title = "Page load delay",
                        subtitle = "Delay between loading pages (helps with rate limits)",
                        entries = (0..15).associate { it to "${it}s" }.toMap(),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.showPageNumber,
                        title = "Show page number",
                        subtitle = "Display current page number with jump navigation",
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.skipCoverLoading,
                        title = "Skip cover loading",
                        subtitle = "Don't load cover images in browse to save bandwidth",
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = sourcePreferences.confirmBackAfterPages,
                        title = "Confirm back after pages",
                        subtitle = "Show confirmation dialog when going back after loading many pages",
                        entries = mapOf(
                            0 to "Disabled",
                            3 to "3 pages",
                            5 to "5 pages",
                            10 to "10 pages",
                            20 to "20 pages",
                        ).toMap(),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.extensionStores),
                        subtitle = pluralStringResource(MR.plurals.num_repos, reposCount.toInt(), reposCount),
                        onClick = {
                            navigator.push(ExtensionStoresScreen())
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_nsfw_content),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.showNsfwSource,
                        title = stringResource(MR.strings.pref_show_nsfw_source),
                        subtitle = stringResource(MR.strings.requires_app_restart),
                        onValueChanged = {
                            (context as FragmentActivity).authenticate(
                                title = context.stringResource(MR.strings.pref_category_nsfw_content),
                            )
                        },
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.parental_controls_info)),
                ),
            ),
        )
    }
}
