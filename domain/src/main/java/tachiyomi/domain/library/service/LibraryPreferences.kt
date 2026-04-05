package tachiyomi.domain.library.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.manga.model.Manga

class LibraryPreferences(
    private val preferenceStore: PreferenceStore,
) {

    val displayMode: Preference<LibraryDisplayMode> = preferenceStore.getObjectFromString(
        "pref_display_mode_library",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    val sortingMode: Preference<LibrarySort> = preferenceStore.getObjectFromString(
        "library_sorting_mode",
        LibrarySort.default,
        LibrarySort.Serializer::serialize,
        LibrarySort.Serializer::deserialize,
    )

    val randomSortSeed: Preference<Int> = preferenceStore.getInt("library_random_sort_seed", 0)

    val portraitColumns: Preference<Int> = preferenceStore.getInt("pref_library_columns_portrait_key", 0)

    val landscapeColumns: Preference<Int> = preferenceStore.getInt("pref_library_columns_landscape_key", 0)

    val titleMaxLines: Preference<Int> = preferenceStore.getInt("pref_library_title_max_lines", 2)


    val lastUpdatedTimestamp: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("library_update_last_timestamp"),
        0L,
    )
    val lastUpdatesClearedTimestamp: Preference<Long> = preferenceStore.getLong(Preference.appStateKey("updates_cleared_timestamp"), 0L)
    val autoUpdateInterval: Preference<Int> = preferenceStore.getInt("pref_library_update_interval_key", 0)

    val autoUpdateDeviceRestrictions: Preference<Set<String>> = preferenceStore.getStringSet(
        "library_update_restriction",
        setOf(
            DEVICE_ONLY_ON_WIFI,
        ),
    )
    val autoUpdateMangaRestrictions: Preference<Set<String>> = preferenceStore.getStringSet(
        "library_update_manga_restriction",
        setOf(
            MANGA_HAS_UNREAD,
            MANGA_NON_COMPLETED,
            MANGA_NON_READ,
            MANGA_OUTSIDE_RELEASE_PERIOD,
        ),
    )

    val skipUpdateTime: Preference<Int> = preferenceStore.getInt("pref_skip_update_time", SKIP_UPDATE_NONE)

    val autoUpdateMetadata: Preference<Boolean> = preferenceStore.getBoolean("auto_update_metadata", false)

    val autoUpdateThrottle: Preference<Int> = preferenceStore.getInt("pref_library_update_throttle_ms", 3000)

    val joinedLibrary: Preference<Boolean> = preferenceStore.getBoolean("pref_joined_library", true)

    val showContinueReadingButton: Preference<Boolean> = preferenceStore.getBoolean(
        "display_continue_reading_button",
        false,
    )

    val markDuplicateReadChapterAsRead: Preference<Set<String>> = preferenceStore.getStringSet(
        "mark_duplicate_read_chapter_read",
        emptySet(),
    )

    // region Filter

    val filterDownloaded: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_library_downloaded_v2",
        TriState.DISABLED,
    )

    val filterUnread: Preference<TriState> = preferenceStore.getEnum("pref_filter_library_unread_v2", TriState.DISABLED)

    val filterStarted: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_library_started_v2",
        TriState.DISABLED,
    )

    val filterBookmarked: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_library_bookmarked_v2",
        TriState.DISABLED,
    )

    val filterCompleted: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_library_completed_v2",
        TriState.DISABLED,
    )

    fun filterNovel() = preferenceStore.getEnum(
        "pref_filter_library_novel",
        TriState.DISABLED,
    )

    fun filterIntervalCustom() = preferenceStore.getEnum(
        "pref_filter_library_interval_custom",
        TriState.DISABLED,
    )

    fun filterTracking(id: Int): Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_library_tracked_${id}_v2",
        TriState.DISABLED,
    )

    val filterExtensions: Preference<Set<String>> = preferenceStore.getStringSet("pref_filter_library_extensions", emptySet())

    // Stores extension IDs that are excluded (unchecked) from the library filter
    val excludedExtensions: Preference<Set<String>> = preferenceStore.getStringSet("pref_excluded_library_extensions", emptySet())

    // Stores source IDs that should have their chapter list reversed
    val reversedChapterSources: Preference<Set<String>> = preferenceStore.getStringSet("pref_reversed_chapter_sources", emptySet())

    // Tag filtering - included tags (TriState: DISABLED = show all, ENABLED_IS = include, ENABLED_NOT = exclude)
    val includedTags: Preference<Set<String>> = preferenceStore.getStringSet("pref_filter_library_included_tags", emptySet())
    val excludedTags: Preference<Set<String>> = preferenceStore.getStringSet("pref_filter_library_excluded_tags", emptySet())
    fun filterNoTags() = preferenceStore.getEnum("pref_filter_library_no_tags", TriState.DISABLED)

    // Tag filter logic modes (true = AND, false = OR)
    val tagIncludeMode: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_tag_include_mode_and",
        false,
    ) // Default OR
    val tagExcludeMode: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_tag_exclude_mode_and",
        false,
    ) // Default OR

    // Tag sort preferences
    val tagSortByName: Preference<Boolean> = preferenceStore.getBoolean("pref_tag_sort_by_name", false) // Default sort by count
    val tagSortAscending: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_tag_sort_ascending",
        false,
    ) // Default descending

    // Tag case sensitivity (default insensitive)
    val tagCaseSensitive: Preference<Boolean> = preferenceStore.getBoolean("pref_tag_case_sensitive", false)

    // Manga detail page tag sorting (true = alphabetical, false = source order)
    val sortMangaTags: Preference<Boolean> = preferenceStore.getBoolean("pref_sort_manga_tags", false)

    // Search options - what to include in library search
    val searchChapterNames: Preference<Boolean> = preferenceStore.getBoolean("pref_search_chapter_names", false)
    val searchChapterContent: Preference<Boolean> = preferenceStore.getBoolean("pref_search_chapter_content", false)
    val searchByUrl: Preference<Boolean> = preferenceStore.getBoolean("pref_search_by_url", false)
    val useRegexSearch: Preference<Boolean> = preferenceStore.getBoolean("pref_use_regex_search", false)

    fun filterChapterCount() = preferenceStore.getEnum(
        "pref_filter_library_chapter_count",
        TriState.DISABLED,
    )
    val filterChapterCountThreshold: Preference<Int> = preferenceStore.getInt(
        "pref_filter_library_chapter_count_threshold",
        10,
    )

    // endregion

    // region Badges

    val downloadBadge: Preference<Boolean> = preferenceStore.getBoolean("display_download_badge", false)

    val unreadBadge: Preference<Boolean> = preferenceStore.getBoolean("display_unread_badge", true)

    val localBadge: Preference<Boolean> = preferenceStore.getBoolean("display_local_badge", true)

    val languageBadge: Preference<Boolean> = preferenceStore.getBoolean("display_language_badge", false)

    val showUrlInList: Preference<Boolean> = preferenceStore.getBoolean("display_url_in_list", false)

    val newShowUpdatesCount: Preference<Boolean> = preferenceStore.getBoolean("library_show_updates_count", true)
    val newUpdatesCount: Preference<Int> = preferenceStore.getInt(
        Preference.appStateKey("library_unseen_updates_count"),
        0,
    )

    // endregion

    // region History

    val historyGroupByNovel: Preference<Boolean> = preferenceStore.getBoolean("history_group_by_novel", true)

    // endregion

    // region Updates

    val updatesGroupByNovel: Preference<Boolean> = preferenceStore.getBoolean("updates_group_by_novel", true)

    // endregion

    // region Category

    val defaultCategory: Preference<Int> = preferenceStore.getInt(DEFAULT_CATEGORY_PREF_KEY, -1)

    val lastUsedCategory: Preference<Int> = preferenceStore.getInt(Preference.appStateKey("last_used_category"), 0)

    val categoryTabs: Preference<Boolean> = preferenceStore.getBoolean("display_category_tabs", true)

    val categoryNumberOfItems: Preference<Boolean> = preferenceStore.getBoolean("display_number_of_items", false)

    val categorizedDisplaySettings: Preference<Boolean> = preferenceStore.getBoolean("categorized_display", false)

    val updateCategories: Preference<Set<String>> = preferenceStore.getStringSet(
        LIBRARY_UPDATE_CATEGORIES_PREF_KEY,
        emptySet(),
    )

    val updateCategoriesExclude: Preference<Set<String>> = preferenceStore.getStringSet(
        LIBRARY_UPDATE_CATEGORIES_EXCLUDE_PREF_KEY,
        emptySet(),
    )

    // endregion

    // region Chapter

    val filterChapterByRead: Preference<Long> = preferenceStore.getLong(
        "default_chapter_filter_by_read",
        Manga.SHOW_ALL,
    )

    val filterChapterByDownloaded: Preference<Long> = preferenceStore.getLong(
        "default_chapter_filter_by_downloaded",
        Manga.SHOW_ALL,
    )

    val filterChapterByBookmarked: Preference<Long> = preferenceStore.getLong(
        "default_chapter_filter_by_bookmarked",
        Manga.SHOW_ALL,
    )

    // and upload date
    val sortChapterBySourceOrNumber: Preference<Long> = preferenceStore.getLong(
        "default_chapter_sort_by_source_or_number",
        Manga.CHAPTER_SORTING_SOURCE,
    )

    val displayChapterByNameOrNumber: Preference<Long> = preferenceStore.getLong(
        "default_chapter_display_by_name_or_number",
        Manga.CHAPTER_DISPLAY_BOTH,
    )

    val sortChapterByAscendingOrDescending: Preference<Long> = preferenceStore.getLong(
        "default_chapter_sort_by_ascending_or_descending",
        Manga.CHAPTER_SORT_DESC,
    )

    fun setChapterSettingsDefault(manga: Manga) {
        filterChapterByRead.set(manga.unreadFilterRaw)
        filterChapterByDownloaded.set(manga.downloadedFilterRaw)
        filterChapterByBookmarked.set(manga.bookmarkedFilterRaw)
        sortChapterBySourceOrNumber.set(manga.sorting)
        displayChapterByNameOrNumber.set(manga.displayMode)
        sortChapterByAscendingOrDescending.set(
            if (manga.sortDescending()) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC,
        )
    }

    val autoClearChapterCache: Preference<Boolean> = preferenceStore.getBoolean("auto_clear_chapter_cache", false)

    val hideMissingChapters: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_hide_missing_chapter_indicators",
        false,
    )

    val showMangaSourceName: Preference<Boolean> = preferenceStore.getBoolean("pref_show_manga_source_name", true)

    /**
     * Whether the library should auto-refresh when database changes occur.
     */
    val autoRefreshLibrary: Preference<Boolean> = preferenceStore.getBoolean("pref_auto_refresh_library", true)

    // endregion

    // region Swipe Actions

    val swipeToStartAction: Preference<ChapterSwipeAction> = preferenceStore.getEnum(
        "pref_chapter_swipe_end_action",
        ChapterSwipeAction.ToggleBookmark,
    )

    val swipeToEndAction: Preference<ChapterSwipeAction> = preferenceStore.getEnum(
        "pref_chapter_swipe_start_action",
        ChapterSwipeAction.ToggleRead,
    )

    val updateMangaTitles: Preference<Boolean> = preferenceStore.getBoolean("pref_update_library_manga_titles", false)

    val disallowNonAsciiFilenames: Preference<Boolean> = preferenceStore.getBoolean(
        "disallow_non_ascii_filenames",
        false,
    )

    val mangaReadProgress100: Preference<Boolean> = preferenceStore.getBoolean("pref_manga_read_progress_100", true)
    val novelReadProgress100: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_read_progress_100", true)

    /**
     * Source type priorities for duplicate detection.
     * Stored as semicolon-delimited "TYPE:PRIORITY" pairs, e.g. "JS:3;KT:1;CUSTOM:0;LOCAL:-2;STUB:-5"
     */
    val sourceTypePriorities: Preference<String> = preferenceStore.getString("source_type_priorities", "")

    /**
     * Specific source priorities for duplicate detection.
     * Stored as semicolon-delimited "SOURCE_ID:PRIORITY" pairs, e.g. "123456:3;789012:-2"
     */
    val specificSourcePriorities: Preference<String> = preferenceStore.getString("specific_source_priorities", "")

    // endregion

    enum class ChapterSwipeAction {
        ToggleRead,
        ToggleBookmark,
        Download,
        Disabled,
    }

    companion object {
        const val DEVICE_ONLY_ON_WIFI = "wifi"
        const val DEVICE_NETWORK_NOT_METERED = "network_not_metered"
        const val DEVICE_CHARGING = "ac"

        const val MANGA_NON_COMPLETED = "manga_ongoing"
        const val MANGA_HAS_UNREAD = "manga_fully_read"
        const val MANGA_NON_READ = "manga_started"
        const val MANGA_OUTSIDE_RELEASE_PERIOD = "manga_outside_release_period"

        const val SKIP_UPDATE_NONE = 0
        const val SKIP_UPDATE_1_DAY = 1
        const val SKIP_UPDATE_3_DAYS = 3
        const val SKIP_UPDATE_7_DAYS = 7
        const val SKIP_UPDATE_14_DAYS = 14
        const val SKIP_UPDATE_30_DAYS = 30
        const val SKIP_UPDATE_60_DAYS = 60
        const val SKIP_UPDATE_90_DAYS = 90

        const val MARK_DUPLICATE_CHAPTER_READ_NEW = "new"
        const val MARK_DUPLICATE_CHAPTER_READ_EXISTING = "existing"

        const val DEFAULT_CATEGORY_PREF_KEY = "default_category"
        private const val LIBRARY_UPDATE_CATEGORIES_PREF_KEY = "library_update_categories"
        private const val LIBRARY_UPDATE_CATEGORIES_EXCLUDE_PREF_KEY = "library_update_categories_exclude"
        val categoryPreferenceKeys = setOf(
            DEFAULT_CATEGORY_PREF_KEY,
            LIBRARY_UPDATE_CATEGORIES_PREF_KEY,
            LIBRARY_UPDATE_CATEGORIES_EXCLUDE_PREF_KEY,
        )
    }
}
