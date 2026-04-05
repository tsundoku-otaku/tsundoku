package eu.kanade.domain.track.service

import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class TrackPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun trackUsername(tracker: Tracker) = preferenceStore.getString(
        Preference.privateKey("pref_mangasync_username_${tracker.id}"),
        "",
    )

    fun trackPassword(tracker: Tracker) = preferenceStore.getString(
        Preference.privateKey("pref_mangasync_password_${tracker.id}"),
        "",
    )

    fun trackAuthExpired(tracker: Tracker) = preferenceStore.getBoolean(
        Preference.privateKey("pref_tracker_auth_expired_${tracker.id}"),
        false,
    )

    fun setCredentials(tracker: Tracker, username: String, password: String) {
        trackUsername(tracker).set(username)
        trackPassword(tracker).set(password)
        trackAuthExpired(tracker).set(false)
    }

    fun trackToken(tracker: Tracker) = preferenceStore.getString(Preference.privateKey("track_token_${tracker.id}"), "")

    val anilistScoreType: Preference<String> = preferenceStore.getString("anilist_score_type", Anilist.POINT_10)

    val autoUpdateTrack: Preference<Boolean> = preferenceStore.getBoolean("pref_auto_update_manga_sync_key", true)

    val autoUpdateTrackOnMarkRead: Preference<AutoTrackState> = preferenceStore.getEnum(
        "pref_auto_update_manga_on_mark_read",
        AutoTrackState.ALWAYS,
    )

    // NovelUpdates Settings
    val novelUpdatesMarkChaptersAsRead: Preference<Boolean> = preferenceStore.getBoolean(
        "novelupdates_mark_chapters_read",
        true,
    )

    val novelUpdatesSyncReadingList: Preference<Boolean> = preferenceStore.getBoolean(
        "novelupdates_sync_reading_list",
        true,
    )

    // NovelUpdates Custom List Mapping
    val novelUpdatesUseCustomListMapping: Preference<Boolean> = preferenceStore.getBoolean(
        "novelupdates_use_custom_list_mapping",
        false,
    )

    val novelUpdatesCustomListMapping: Preference<String> = preferenceStore.getString(
        "novelupdates_custom_list_mapping",
        "{}",
    )

    val novelUpdatesCachedLists: Preference<String> = preferenceStore.getString(
        "novelupdates_cached_lists",
        "[]",
    )

    val novelUpdatesLastListRefresh: Preference<Long> = preferenceStore.getLong("novelupdates_last_list_refresh", 0L)

    // NovelList Settings
    val novelListMarkChaptersAsRead: Preference<Boolean> = preferenceStore.getBoolean(
        "novellist_mark_chapters_read",
        true,
    )

    val novelListSyncReadingList: Preference<Boolean> = preferenceStore.getBoolean("novellist_sync_reading_list", true)

    // Minimum chapters before tracking
    val minChaptersBeforeTrackingManga: Preference<String> = preferenceStore.getString(
        "min_chapters_before_tracking_manga",
        "0",
    )

    val minChaptersBeforeTrackingNovel: Preference<String> = preferenceStore.getString(
        "min_chapters_before_tracking_novel",
        "0",
    )
}
