@file:Suppress("ktlint:standard:max-line-length", "ktlint:standard:property-naming")

package tachiyomi.data.manga

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject
import logcat.LogPriority
import mihon.core.common.extensions.EMPTY
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.AlternativeTitlesColumnAdapter
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOne
import tachiyomi.data.subscribeToOneOrNull
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibraryMangaForUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaSelectionMetric
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.DuplicateGroup
import tachiyomi.domain.manga.repository.DuplicatePair
import tachiyomi.domain.manga.repository.FavoriteMetadataMatches
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate
import java.time.ZoneId

class MangaRepositoryImpl(
    private val database: Database,
) : MangaRepository {

    private data class UrlMaintenanceRow(
        val id: Long,
        val source: Long,
        val url: String,
        val title: String,
        val favorite: Boolean,
    )

    private fun normalizeUrlForMaintenance(url: String, removeDoubleSlashes: Boolean): String {
        var normalizedUrl = url.trimEnd('/').substringBefore('#')
        if (removeDoubleSlashes) {
            val placeholder = "###PROTOCOL###"
            normalizedUrl = normalizedUrl.replace("://", placeholder)
            normalizedUrl = normalizedUrl.replace("//", "/")
            normalizedUrl = normalizedUrl.replace(placeholder, "://")
        }
        return normalizedUrl
    }

    override suspend fun getMangaById(id: Long): Manga {
        return database.mangasQueries.getMangaById(id) {
                id,
                source,
                url,
                artist,
                author,
                description,
                genre,
                title,
                alternative_titles,
                status,
                thumbnail_url,
                favorite,
                last_update,
                next_update,
                initialized,
                viewer,
                chapter_flags,
                cover_last_modified,
                date_added,
                update_strategy,
                calculate_interval,
                last_modified_at,
                favorite_modified_at,
                version,
                is_syncing,
                notes,
                is_novel,
                _,
                _,
                _,
                _,
                _,
                _,
                memo,
            ->
            MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel).copy(
                memo = memo,
            )
        }.awaitAsOne()
    }

    override suspend fun getMangaByIdOrNull(id: Long): Manga? = getMangasByIds(listOf(id)).firstOrNull()

    override suspend fun getMangasByIds(ids: List<Long>): List<Manga> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(SQLITE_VARIABLE_LIMIT).flatMap { chunk ->
            database.mangasQueries.getMangasByIds(chunk) {
                    id,
                    source,
                    url,
                    artist,
                    author,
                    description,
                    genre,
                    title,
                    alternative_titles,
                    status,
                    thumbnail_url,
                    favorite,
                    last_update,
                    next_update,
                    initialized,
                    viewer,
                    chapter_flags,
                    cover_last_modified,
                    date_added,
                    update_strategy,
                    calculate_interval,
                    last_modified_at,
                    favorite_modified_at,
                    version,
                    is_syncing,
                    notes,
                    is_novel,
                    _,
                    _,
                    _,
                    _,
                    _,
                    _,
                    _,
                ->
                MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel)
            }.awaitAsList()
        }
    }

    override suspend fun clearDescriptionsForMangaIds(ids: List<Long>) {
        clearFieldsForMangaIds(ids) { database.mangasQueries.clearDescriptionsForIds(it) }
    }

    override suspend fun clearGenresForMangaIds(ids: List<Long>) {
        clearFieldsForMangaIds(ids) { database.mangasQueries.clearGenresForIds(it) }
    }

    override suspend fun clearDescriptionsAndGenresForMangaIds(ids: List<Long>) {
        clearFieldsForMangaIds(ids) { database.mangasQueries.clearDescriptionsAndGenresForIds(it) }
    }

    override suspend fun clearCoversForMangaIds(ids: List<Long>, coverLastModified: Long) {
        clearFieldsForMangaIds(ids) { database.mangasQueries.clearThumbnailsForIds(coverLastModified, it) }
    }

    private suspend fun clearFieldsForMangaIds(ids: List<Long>, statement: suspend (List<Long>) -> Unit) {
        if (ids.isEmpty()) return
        try {
            database.transaction {
                ids.chunked(SQLITE_VARIABLE_LIMIT).forEach { statement(it) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    override suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga> {
        return database.mangasQueries.getMangaById(id) {
                id,
                source,
                url,
                artist,
                author,
                description,
                genre,
                title,
                alternative_titles,
                status,
                thumbnail_url,
                favorite,
                last_update,
                next_update,
                initialized,
                viewer,
                chapter_flags,
                cover_last_modified,
                date_added,
                update_strategy,
                calculate_interval,
                last_modified_at,
                favorite_modified_at,
                version,
                is_syncing,
                notes,
                is_novel,
                _,
                _,
                _,
                _,
                _,
                _,
                memo,
            ->
            MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel).copy(
                memo = memo,
            )
        }.subscribeToOne()
    }

    override suspend fun getMemo(mangaId: Long): JsonObject {
        return database.mangasQueries.getMemoById(mangaId).awaitAsOneOrNull() ?: JsonObject.EMPTY
    }

    override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
        return database.mangasQueries.getMangaByUrlAndSource(
            url,
            sourceId,
        ) {
                id,
                source,
                url,
                artist,
                author,
                description,
                genre,
                title,
                alternative_titles,
                status,
                thumbnail_url,
                favorite,
                last_update,
                next_update,
                initialized,
                viewer,
                chapter_flags,
                cover_last_modified,
                date_added,
                update_strategy,
                calculate_interval,
                last_modified_at,
                favorite_modified_at,
                version,
                is_syncing,
                notes,
                is_novel,
                _,
                _,
                _,
                _,
                _,
                _,
                memo,
            ->
            MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel).copy(
                memo = memo,
            )
        }.awaitAsOneOrNull()
    }

    override suspend fun getLiteMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
        return database.mangasQueries.getLiteMangaByUrlAndSource(url, sourceId) {
                id,
                source,
                url,
                _,
                _,
                _,
                _,
                title,
                _,
                status,
                thumbnailUrl,
                favorite,
                last_update,
                next_update,
                _,
                _,
                _,
                cover_last_modified,
                date_added,
                _,
                _,
                _,
                _,
                _,
                _,
                notes,
                is_novel,
            ->
            MangaMapper.mapManga(
                id = id,
                source = source,
                url = url,
                artist = null,
                author = null,
                description = null,
                genre = null,
                title = title,
                alternativeTitles = null,
                status = status,
                thumbnailUrl = thumbnailUrl,
                favorite = favorite,
                lastUpdate = last_update ?: 0,
                nextUpdate = next_update ?: 0,
                initialized = false,
                viewerFlags = 0,
                chapterFlags = 0,
                coverLastModified = cover_last_modified,
                dateAdded = date_added,
                updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
                calculateInterval = 0,
                lastModifiedAt = 0,
                favoriteModifiedAt = null,
                version = 0,
                isSyncing = 0,
                notes = notes,
                isNovel = false,
            )
        }.awaitAsOneOrNull()
    }

    override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> {
        return database.mangasQueries.getMangaByUrlAndSource(
            url,
            sourceId,
        ) {
                id,
                source,
                url,
                artist,
                author,
                description,
                genre,
                title,
                alternative_titles,
                status,
                thumbnail_url,
                favorite,
                last_update,
                next_update,
                initialized,
                viewer,
                chapter_flags,
                cover_last_modified,
                date_added,
                update_strategy,
                calculate_interval,
                last_modified_at,
                favorite_modified_at,
                version,
                is_syncing,
                notes,
                is_novel,
                _,
                _,
                _,
                _,
                _,
                _,
                memo,
            ->
            MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel).copy(
                memo = memo,
            )
        }.subscribeToOneOrNull()
    }

    override suspend fun getFavorites(): List<Manga> {
        return database.mangasQueries.getFavorites {
                id,
                source,
                url,
                artist,
                author,
                description,
                genre,
                title,
                alternative_titles,
                status,
                thumbnail_url,
                favorite,
                last_update,
                next_update,
                initialized,
                viewer,
                chapter_flags,
                cover_last_modified,
                date_added,
                update_strategy,
                calculate_interval,
                last_modified_at,
                favorite_modified_at,
                version,
                is_syncing,
                notes,
                is_novel,
                _,
                _,
                _,
                _,
                _,
                _,
                memo,
            ->
            MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel).copy(
                memo = memo,
            )
        }.awaitAsList()
    }

    override suspend fun getFavoritesPaged(limit: Long, offset: Long): List<Manga> {
        return database.mangasQueries.getFavoritesPaged(limit, offset) {
                id,
                source,
                url,
                artist,
                author,
                description,
                genre,
                title,
                alternative_titles,
                status,
                thumbnail_url,
                favorite,
                last_update,
                next_update,
                initialized,
                viewer,
                chapter_flags,
                cover_last_modified,
                date_added,
                update_strategy,
                calculate_interval,
                last_modified_at,
                favorite_modified_at,
                version,
                is_syncing,
                notes,
                is_novel,
                _,
                _,
                _,
                _,
                _,
                _,
                memo,
            ->
            MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel).copy(
                memo = memo,
            )
        }.awaitAsList()
    }

    override suspend fun getFavoritesCount(): Long {
        return database.mangasQueries.getFavoritesCount().awaitAsOne()
    }

    override suspend fun getFavoritesEntry(): List<Manga> {
        return database.mangasQueries.getFavoritesEntry {
                id,
                source,
                url,
                title,
                artist,
                author,
                thumbnail_url,
                cover_last_modified,
                favorite,
                is_novel,
            ->
            MangaMapper.mapManga(
                id = id,
                source = source,
                url = url,
                artist = artist,
                author = author,
                description = null,
                genre = null,
                title = title,
                alternativeTitles = null,
                status = 0,
                thumbnailUrl = thumbnail_url,
                favorite = favorite,
                lastUpdate = 0,
                nextUpdate = 0,
                initialized = false,
                viewerFlags = 0,
                chapterFlags = 0,
                coverLastModified = cover_last_modified,
                dateAdded = 0,
                updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
                calculateInterval = 0,
                lastModifiedAt = 0,
                favoriteModifiedAt = null,
                version = 0,
                isSyncing = 0,
                notes = "",
                isNovel = is_novel,
            )
        }.awaitAsList()
    }

    override suspend fun getFavoritesEntryPaged(afterId: Long, limit: Long): List<Manga> {
        return database.mangasQueries.getFavoritesEntryPaged(afterId, limit) {
                id,
                source,
                url,
                title,
                artist,
                author,
                thumbnail_url,
                cover_last_modified,
                favorite,
                is_novel,
            ->
            MangaMapper.mapManga(
                id = id,
                source = source,
                url = url,
                artist = artist,
                author = author,
                description = null,
                genre = null,
                title = title,
                alternativeTitles = null,
                status = 0,
                thumbnailUrl = thumbnail_url,
                favorite = favorite,
                lastUpdate = 0,
                nextUpdate = 0,
                initialized = false,
                viewerFlags = 0,
                chapterFlags = 0,
                coverLastModified = cover_last_modified,
                dateAdded = 0,
                updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
                calculateInterval = 0,
                lastModifiedAt = 0,
                favoriteModifiedAt = null,
                version = 0,
                isSyncing = 0,
                notes = "",
                isNovel = is_novel,
            )
        }.awaitAsList()
    }

    override fun getFavoritesEntryBySourceId(sourceId: Long): Flow<List<Manga>> {
        return database.mangasQueries.getFavoritesEntryBySourceId(sourceId) {
                id,
                source,
                url,
                title,
                artist,
                author,
                thumbnail_url,
                cover_last_modified,
                favorite,
                is_novel,
            ->
            MangaMapper.mapManga(
                id = id,
                source = source,
                url = url,
                artist = artist,
                author = author,
                description = null,
                genre = null,
                title = title,
                alternativeTitles = null,
                status = 0,
                thumbnailUrl = thumbnail_url,
                favorite = favorite,
                lastUpdate = 0,
                nextUpdate = 0,
                initialized = false,
                viewerFlags = 0,
                chapterFlags = 0,
                coverLastModified = cover_last_modified,
                dateAdded = 0,
                updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
                calculateInterval = 0,
                lastModifiedAt = 0,
                favoriteModifiedAt = null,
                version = 0,
                isSyncing = 0,
                notes = "",
                isNovel = is_novel,
            )
        }.subscribeToList()
    }

    override suspend fun getFavoriteSourceAndUrl(): List<Pair<Long, String>> {
        val now = System.currentTimeMillis()
        if (cachedFavoriteSourceUrl != null && now - favoriteSourceUrlCacheTimestamp < FAVORITE_URL_CACHE_VALIDITY_MS) {
            return cachedFavoriteSourceUrl!!
        }

        val result = database.mangasQueries.getFavoriteSourceAndUrl { source, url -> source to url }.awaitAsList()

        cachedFavoriteSourceUrl = result
        favoriteSourceUrlCacheTimestamp = now
        return result
    }

    override suspend fun getFavoriteIdAndUrl(): List<Pair<Long, String>> {
        return database.mangasQueries.getFavoriteIdAndUrl { id, url -> id to url }.awaitAsList()
    }

    override suspend fun getFavoriteIdAndGenre(): List<Pair<Long, List<String>?>> {
        return database.mangasQueries.getFavoriteIdAndGenre { id, genre ->
            id to genre
        }.awaitAsList()
    }

    override suspend fun getFavoriteIdAndTotalCount(): List<Pair<Long, Long>> {
        return database.mangasQueries.getFavoriteIdAndTotalCount { id, totalCount ->
            id to totalCount
        }.awaitAsList()
    }

    override suspend fun getReadMangaNotInLibrary(): List<Manga> {
        return database.mangasQueries.getReadMangaNotInLibrary {
                id,
                source,
                url,
                artist,
                author,
                description,
                genre,
                title,
                alternative_titles,
                status,
                thumbnail_url,
                favorite,
                last_update,
                next_update,
                initialized,
                viewer,
                chapter_flags,
                cover_last_modified,
                date_added,
                update_strategy,
                calculate_interval,
                last_modified_at,
                favorite_modified_at,
                version,
                is_syncing,
                notes,
                is_novel,
                _,
                _,
                _,
                _,
                _,
                _,
                memo,
            ->
            MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel).copy(
                memo = memo,
            )
        }.awaitAsList()
    }

    @Volatile
    private var cachedLibraryManga: List<LibraryManga>? = null

    @Volatile
    private var cacheTimestamp: Long = 0
    private val CACHE_VALIDITY_MS = Long.MAX_VALUE

    @Volatile
    private var cachedFavoriteSourceUrl: List<Pair<Long, String>>? = null

    @Volatile
    private var favoriteSourceUrlCacheTimestamp: Long = 0
    private val FAVORITE_URL_CACHE_VALIDITY_MS = 10000L // 10 seconds

    private fun invalidateLibraryCacheInternal() {
        cachedLibraryManga = null
        cacheTimestamp = 0
    }

    override fun invalidateLibraryCache() {
        invalidateLibraryCacheInternal()
    }

    private fun invalidateFavoriteUrlCache() {
        cachedFavoriteSourceUrl = null
        favoriteSourceUrlCacheTimestamp = 0
    }

    override suspend fun getLibraryManga(): List<LibraryManga> {
        val caller = Thread.currentThread().stackTrace.take(15).joinToString("\\n  ") {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        }
        val now = System.currentTimeMillis()
        if (cachedLibraryManga != null && now - cacheTimestamp < CACHE_VALIDITY_MS) {
            logcat(LogPriority.DEBUG) {
                "MangaRepositoryImpl.getLibraryManga: Using CACHE (age=${now - cacheTimestamp}ms, size=${cachedLibraryManga?.size})"
            }
            return cachedLibraryManga!!
        }

        logcat(LogPriority.WARN) {
            "MangaRepositoryImpl.getLibraryManga: Executing DB query (cache invalid/expired)\\nFull call stack:\\n  $caller"
        }
        val queryStart = System.currentTimeMillis()
        val result = database.mangasQueries.libraryGrid {
                id,
                source,
                url,
                _,
                _,
                _,
                genre,
                title,
                _,
                status,
                thumbnailUrl,
                favorite,
                lastUpdate,
                nextUpdate,
                _,
                _,
                _,
                coverLastModified,
                dateAdded,
                _,
                _,
                _,
                _,
                _,
                _,
                notes,
                isNovel,
                totalCount,
                readCount,
                latestUpload,
                chapterFetchedAt,
                lastRead,
                bookmarkCount,
                categories,
            ->
            MangaMapper.mapLibraryManga(
                id = id,
                source = source,
                url = url,
                artist = null,
                author = null,
                description = null,
                genre = genre,
                title = title,
                alternativeTitles = null,
                status = status,
                thumbnailUrl = thumbnailUrl,
                favorite = favorite,
                lastUpdate = lastUpdate,
                nextUpdate = nextUpdate,
                initialized = false,
                viewerFlags = 0,
                chapterFlags = 0,
                coverLastModified = coverLastModified,
                dateAdded = dateAdded,
                updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
                calculateInterval = 0,
                lastModifiedAt = 0,
                favoriteModifiedAt = null,
                version = 0,
                isSyncing = 0,
                notes = notes,
                isNovel = isNovel,
                totalCount = totalCount,
                readCount = readCount,
                latestUpload = latestUpload,
                chapterFetchedAt = chapterFetchedAt,
                lastRead = lastRead,
                bookmarkCount = bookmarkCount,
                categories = categories,
            )
        }.awaitAsList()

        val queryDuration = System.currentTimeMillis() - queryStart
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.getLibraryManga: Query completed in ${queryDuration}ms, returned ${result.size} items"
        }

        // Update cache
        cachedLibraryManga = result
        cacheTimestamp = now
        return result
    }

    override suspend fun getLibraryMangaPage(
        categoryId: Long,
        isNovel: Boolean,
        limit: Long,
        offset: Long,
        spec: tachiyomi.domain.library.model.LibraryPageSpec,
    ): List<LibraryManga> {
        // NOT IN () is invalid, so pad an empty exclusion list with a non-existent source id.
        val excluded = spec.excludedSourceIds.ifEmpty { listOf(-1L) }
        return database.mangasQueries.libraryPageFiltered(
            isNovel = isNovel,
            categoryId = categoryId,
            filterUnread = spec.filterUnread.toLong(),
            filterStarted = spec.filterStarted.toLong(),
            filterBookmarked = spec.filterBookmarked.toLong(),
            filterCompleted = spec.filterCompleted.toLong(),
            filterIntervalCustom = spec.filterIntervalCustom.toLong(),
            filterChapterCount = spec.filterChapterCount.toLong(),
            chapterCountThreshold = spec.filterChapterCountThreshold.toLong(),
            excludedSourceIds = excluded,
            searchTerm = spec.searchTerm,
            searchAltTitles = if (spec.searchAlternativeTitles) 1L else 0L,
            includedTagsCsv = spec.includedTagsCsv,
            sortType = spec.sortType.toLong(),
            sortAscending = if (spec.sortAscending) 1L else 0L,
            limit = limit,
            offset = offset,
        ) {
                id,
                source,
                url,
                _,
                _,
                _,
                genre,
                title,
                _,
                status,
                thumbnailUrl,
                favorite,
                lastUpdate,
                nextUpdate,
                _,
                _,
                _,
                coverLastModified,
                dateAdded,
                _,
                _,
                _,
                _,
                _,
                _,
                notes,
                isNovel,
                totalCount,
                readCount,
                latestUpload,
                chapterFetchedAt,
                lastRead,
                bookmarkCount,
                categories,
            ->
            MangaMapper.mapLibraryManga(
                id = id,
                source = source,
                url = url,
                artist = null,
                author = null,
                description = null,
                genre = genre,
                title = title,
                alternativeTitles = null,
                status = status,
                thumbnailUrl = thumbnailUrl,
                favorite = favorite,
                lastUpdate = lastUpdate,
                nextUpdate = nextUpdate,
                initialized = false,
                viewerFlags = 0,
                chapterFlags = 0,
                coverLastModified = coverLastModified,
                dateAdded = dateAdded,
                updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
                calculateInterval = 0,
                lastModifiedAt = 0,
                favoriteModifiedAt = null,
                version = 0,
                isSyncing = 0,
                notes = notes,
                isNovel = isNovel,
                totalCount = totalCount,
                readCount = readCount,
                latestUpload = latestUpload,
                chapterFetchedAt = chapterFetchedAt,
                lastRead = lastRead,
                bookmarkCount = bookmarkCount,
                categories = categories,
            )
        }.awaitAsList()
    }

    override suspend fun getLibraryMangaById(mangaId: Long): LibraryManga? {
        return database.mangasQueries.libraryGridById(mangaId) {
                id,
                source,
                url,
                _,
                _,
                _,
                genre,
                title,
                _,
                status,
                thumbnailUrl,
                favorite,
                lastUpdate,
                nextUpdate,
                _,
                _,
                _,
                coverLastModified,
                dateAdded,
                _,
                _,
                _,
                _,
                _,
                _,
                notes,
                isNovel,
                totalCount,
                readCount,
                latestUpload,
                chapterFetchedAt,
                lastRead,
                bookmarkCount,
                categories,
            ->
            MangaMapper.mapLibraryManga(
                id = id,
                source = source,
                url = url,
                artist = null,
                author = null,
                description = null,
                genre = genre,
                title = title,
                alternativeTitles = null,
                status = status,
                thumbnailUrl = thumbnailUrl,
                favorite = favorite,
                lastUpdate = lastUpdate,
                nextUpdate = nextUpdate,
                initialized = false,
                viewerFlags = 0,
                chapterFlags = 0,
                coverLastModified = coverLastModified,
                dateAdded = dateAdded,
                updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
                calculateInterval = 0,
                lastModifiedAt = 0,
                favoriteModifiedAt = null,
                version = 0,
                isSyncing = 0,
                notes = notes,
                isNovel = isNovel,
                totalCount = totalCount,
                readCount = readCount,
                latestUpload = latestUpload,
                chapterFetchedAt = chapterFetchedAt,
                lastRead = lastRead,
                bookmarkCount = bookmarkCount,
                categories = categories,
            )
        }.awaitAsOneOrNull()
    }

    override suspend fun getLibraryMangaByIds(mangaIds: List<Long>): List<LibraryManga> {
        if (mangaIds.isEmpty()) return emptyList()
        return database.mangasQueries.libraryGridByIds(mangaIds) {
                id,
                source,
                url,
                _,
                _,
                _,
                genre,
                title,
                _,
                status,
                thumbnailUrl,
                favorite,
                lastUpdate,
                nextUpdate,
                _,
                _,
                _,
                coverLastModified,
                dateAdded,
                _,
                _,
                _,
                _,
                _,
                _,
                notes,
                isNovel,
                totalCount,
                readCount,
                latestUpload,
                chapterFetchedAt,
                lastRead,
                bookmarkCount,
                categories,
            ->
            MangaMapper.mapLibraryManga(
                id = id,
                source = source,
                url = url,
                artist = null,
                author = null,
                description = null,
                genre = genre,
                title = title,
                alternativeTitles = null,
                status = status,
                thumbnailUrl = thumbnailUrl,
                favorite = favorite,
                lastUpdate = lastUpdate,
                nextUpdate = nextUpdate,
                initialized = false,
                viewerFlags = 0,
                chapterFlags = 0,
                coverLastModified = coverLastModified,
                dateAdded = dateAdded,
                updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
                calculateInterval = 0,
                lastModifiedAt = 0,
                favoriteModifiedAt = null,
                version = 0,
                isSyncing = 0,
                notes = notes,
                isNovel = isNovel,
                totalCount = totalCount,
                readCount = readCount,
                latestUpload = latestUpload,
                chapterFetchedAt = chapterFetchedAt,
                lastRead = lastRead,
                bookmarkCount = bookmarkCount,
                categories = categories,
            )
        }.awaitAsList()
    }

    override suspend fun getLibraryMangaForUpdate(): List<LibraryMangaForUpdate> {
        logcat(LogPriority.INFO) { "MangaRepositoryImpl.getLibraryMangaForUpdate: Executing lightweight query" }
        val queryStart = System.currentTimeMillis()
        val result = database.mangasQueries.libraryForUpdate {
                id,
                source,
                url,
                title,
                status,
                favorite,
                lastUpdate,
                nextUpdate,
                updateStrategy,
                totalCount,
                readCount,
                categories,
            ->
            MangaMapper.mapLibraryMangaForUpdate(
                id = id,
                source = source,
                url = url,
                title = title,
                status = status,
                favorite = favorite,
                lastUpdate = lastUpdate,
                nextUpdate = nextUpdate,
                updateStrategy = updateStrategy,
                totalCount = totalCount,
                readCount = readCount,
                categories = categories,
            )
        }.awaitAsList()
        val queryDuration = System.currentTimeMillis() - queryStart
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.getLibraryMangaForUpdate: Query completed in ${queryDuration}ms, returned ${result.size} items"
        }
        return result
    }

    override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> {
        logcat(LogPriority.INFO) { "MangaRepositoryImpl.getLibraryMangaAsFlow: Creating new Flow subscription" }
        logcat(LogPriority.INFO) { "MangaRepositoryImpl.getLibraryMangaAsFlow: Executing libraryGrid query" }
        return database.mangasQueries.libraryGrid {
                id,
                source,
                url,
                _,
                _,
                _,
                genre,
                title,
                _,
                status,
                thumbnailUrl,
                favorite,
                lastUpdate,
                nextUpdate,
                _,
                _,
                _,
                coverLastModified,
                dateAdded,
                _,
                _,
                _,
                _,
                _,
                _,
                notes,
                isNovel,
                totalCount,
                readCount,
                latestUpload,
                chapterFetchedAt,
                lastRead,
                bookmarkCount,
                categories,
            ->
            MangaMapper.mapLibraryManga(
                id = id,
                source = source,
                url = url,
                artist = null,
                author = null,
                description = null,
                genre = genre,
                title = title,
                alternativeTitles = null,
                status = status,
                thumbnailUrl = thumbnailUrl,
                favorite = favorite,
                lastUpdate = lastUpdate,
                nextUpdate = nextUpdate,
                initialized = false,
                viewerFlags = 0,
                chapterFlags = 0,
                coverLastModified = coverLastModified,
                dateAdded = dateAdded,
                updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
                calculateInterval = 0,
                lastModifiedAt = 0,
                favoriteModifiedAt = null,
                version = 0,
                isSyncing = 0,
                notes = notes,
                isNovel = isNovel,
                totalCount = totalCount,
                readCount = readCount,
                latestUpload = latestUpload,
                chapterFetchedAt = chapterFetchedAt,
                lastRead = lastRead,
                bookmarkCount = bookmarkCount,
                categories = categories,
            )
        }.subscribeToList()
            // Log when debounce passes through
            .onEach { list ->
                logcat(LogPriority.INFO) {
                    "MangaRepositoryImpl.getLibraryMangaAsFlow: Flow emitting ${list.size} items (after debounce)"
                }
            }
            // Debounce to prevent rapid re-queries when multiple table changes occur
            // (e.g., adding manga triggers changes to mangas, chapters, mangas_categories)
            // 500ms allows batched operations to complete before triggering query
            .debounce(500)
            // Skip emissions if the resulting list hasn't changed
            .distinctUntilChanged()
    }

    override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>> {
        return database.mangasQueries.getFavoriteBySourceId(sourceId) {
                id,
                source,
                url,
                artist,
                author,
                description,
                genre,
                title,
                alternative_titles,
                status,
                thumbnail_url,
                favorite,
                last_update,
                next_update,
                initialized,
                viewer,
                chapter_flags,
                cover_last_modified,
                date_added,
                update_strategy,
                calculate_interval,
                last_modified_at,
                favorite_modified_at,
                version,
                is_syncing,
                notes,
                is_novel,
                _,
                _,
                _,
                _,
                _,
                _,
                memo,
            ->
            MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel).copy(
                memo = memo,
            )
        }.subscribeToList()
    }

    override suspend fun getDuplicateLibraryManga(
        id: Long,
        title: String,
        altTitles: List<String>,
    ): List<MangaWithChapterCount> {
        val altTitlesEncoded = altTitles.takeIf {
            it.isNotEmpty()
        }?.let(AlternativeTitlesColumnAdapter::encode).orEmpty()
        return database.mangasQueries.getDuplicateLibraryManga(id, title, altTitlesEncoded) {
                id,
                source,
                url,
                artist,
                author,
                description,
                genre,
                title,
                alternative_titles,
                status,
                thumbnail_url,
                favorite,
                last_update,
                next_update,
                initialized,
                viewer,
                chapter_flags,
                cover_last_modified,
                date_added,
                update_strategy,
                calculate_interval,
                last_modified_at,
                favorite_modified_at,
                version,
                is_syncing,
                notes,
                is_novel,
                totalCount,
                readCount,
            ->
            MangaMapper.mapMangaWithChapterCount(
                id = id,
                source = source,
                url = url,
                artist = artist,
                author = author,
                description = description,
                genre = genre,
                title = title,
                alternativeTitles = alternative_titles,
                status = status,
                thumbnailUrl = thumbnail_url,
                favorite = favorite,
                lastUpdate = last_update,
                nextUpdate = next_update,
                initialized = initialized,
                viewerFlags = viewer,
                chapterFlags = chapter_flags,
                coverLastModified = cover_last_modified,
                dateAdded = date_added,
                updateStrategy = update_strategy,
                calculateInterval = calculate_interval,
                lastModifiedAt = last_modified_at,
                favoriteModifiedAt = favorite_modified_at,
                version = version,
                isSyncing = is_syncing,
                notes = notes,
                isNovel = is_novel,
                totalCount = totalCount,
                readCount = readCount,
            )
        }.awaitAsList()
    }

    override suspend fun findDuplicatesExact(): List<DuplicateGroup> {
        return database.mangasQueries.findDuplicatesExact { normalizedTitle, ids, count ->
            DuplicateGroup(
                normalizedTitle = normalizedTitle ?: "",
                ids =
                ids?.let { idString -> idString.split(",").mapNotNull { id -> id.toLongOrNull() } }
                    ?: emptyList(),
                count = count.toInt(),
            )
        }.awaitAsList()
    }

    override suspend fun findDuplicatesContains(): List<DuplicatePair> {
        return database.mangasQueries.findDuplicatesContains { idA, titleA, idB, titleB ->
            DuplicatePair(
                idA = idA,
                titleA = titleA,
                idB = idB,
                titleB = titleB,
            )
        }.awaitAsList()
    }

    override suspend fun findFavoriteIdsMatchingMetadata(
        matchAuthor: ((String) -> Boolean)?,
        matchArtist: ((String) -> Boolean)?,
        matchDescription: ((String) -> Boolean)?,
        matchAltTitle: ((List<String>) -> Boolean)?,
    ): FavoriteMetadataMatches {
        val authorIds = HashSet<Long>()
        val artistIds = HashSet<Long>()
        val descriptionIds = HashSet<Long>()
        val altTitleIds = HashSet<Long>()
        // Side-effecting mapper: each row's strings are matched and discarded as the cursor
        // streams, so the full favorite metadata is never held in memory at once.
        if (matchAltTitle != null) {
            database.mangasQueries.getFavoriteMetadataForSearch { id, author, artist, description, altTitles ->
                if (matchAuthor != null && author != null && matchAuthor(author)) authorIds.add(id)
                if (matchArtist != null && artist != null && matchArtist(artist)) artistIds.add(id)
                if (matchDescription != null && description != null && matchDescription(description)) {
                    descriptionIds.add(id)
                }
                if (!altTitles.isNullOrEmpty() && matchAltTitle(altTitles)) altTitleIds.add(id)
            }.awaitAsList()
        } else {
            // Skips the alternative_titles column entirely so its adapter never decodes when
            // alt-title matching wasn't requested (pref disabled or a field-scoped search).
            database.mangasQueries.getFavoriteMetadataForSearchBasic { id, author, artist, description ->
                if (matchAuthor != null && author != null && matchAuthor(author)) authorIds.add(id)
                if (matchArtist != null && artist != null && matchArtist(artist)) artistIds.add(id)
                if (matchDescription != null && description != null && matchDescription(description)) {
                    descriptionIds.add(id)
                }
            }.awaitAsList()
        }
        return FavoriteMetadataMatches(authorIds, artistIds, descriptionIds, altTitleIds)
    }

    override suspend fun getFavoriteIdAndTitle(): List<Pair<Long, String>> {
        return database.mangasQueries.getFavoriteIdAndTitle { id, title ->
            id to title
        }.awaitAsList()
    }

    override suspend fun getFavoriteIdsForCategory(categoryId: Long): List<Long> {
        return database.mangasQueries.getFavoriteIdsForCategory(categoryId).awaitAsList()
    }

    override suspend fun getFavoriteSelectionMetrics(
        categoryIds: List<Long>,
        limit: Long,
    ): List<MangaSelectionMetric> {
        val mapper = { id: Long, source: Long, title: String, totalCount: Long, readCount: Long ->
            MangaSelectionMetric(
                id = id,
                groupKey = title.trim().lowercase().ifBlank { id.toString() },
                source = source,
                chapterCount = totalCount.toInt(),
                readCount = readCount.toInt(),
            )
        }
        return if (categoryIds.isEmpty()) {
            database.mangasQueries.getFavoriteSelectionMetrics(limit, mapper).awaitAsList()
        } else {
            database.mangasQueries.getFavoriteSelectionMetricsInCategories(categoryIds, limit, mapper).awaitAsList()
        }
    }

    override suspend fun findDuplicatesByUrl(): List<DuplicateGroup> {
        return database.mangasQueries.findDuplicatesByUrl { url, source, ids, count ->
            DuplicateGroup(
                normalizedTitle = url, // Using URL as the group key
                ids = ids.split(",").mapNotNull { id -> id.toLongOrNull() },
                count = count.toInt(),
            )
        }.awaitAsList()
    }

    override suspend fun getFavoriteGenres(): List<Pair<Long, List<String>?>> {
        logcat(LogPriority.INFO) { "MangaRepositoryImpl.getFavoriteGenres: Executing lightweight genres query" }
        val queryStart = System.currentTimeMillis()
        val result = database.mangasQueries.getFavoriteGenres { id, genre ->
            // genre is already List<String>? via StringListColumnAdapter
            id to genre
        }.awaitAsList()
        val queryDuration = System.currentTimeMillis() - queryStart
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.getFavoriteGenres: Query completed in ${queryDuration}ms, returned ${result.size} items"
        }
        return result
    }

    override suspend fun getFavoriteGenresWithSource(): List<Triple<Long, Long, List<String>?>> {
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.getFavoriteGenresWithSource: Executing lightweight genres with source query"
        }
        val queryStart = System.currentTimeMillis()
        val result = database.mangasQueries.getFavoriteGenresWithSource { id, source, genre ->
            Triple(id, source, genre)
        }.awaitAsList()
        val queryDuration = System.currentTimeMillis() - queryStart
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.getFavoriteGenresWithSource: Query completed in ${queryDuration}ms, returned ${result.size} items"
        }
        return result
    }

    override suspend fun getFavoriteGenreTagCounts(
        novelSourceIds: Set<Long>,
        wantNovel: Boolean?,
    ): Pair<Map<String, Int>, Int> {
        val counts = HashMap<String, Int>()
        var noTagsCount = 0
        // Side-effecting mapper: fold each row as the cursor streams it. awaitAsList still
        // builds a List<Unit> (one cheap singleton ref per row), but the genre sublists are
        // decoded and discarded per row instead of all being held simultaneously.
        database.mangasQueries.getFavoriteGenresWithSource { _, source, genre ->
            val isNovel = source in novelSourceIds
            if (wantNovel == null || wantNovel == isNovel) {
                if (genre.isNullOrEmpty()) {
                    noTagsCount++
                } else {
                    for (raw in genre) {
                        val tag = raw.trim()
                        if (tag.isNotEmpty()) counts[tag] = (counts[tag] ?: 0) + 1
                    }
                }
            }
        }.awaitAsList()
        return counts to noTagsCount
    }

    override suspend fun getFavoriteSourceUrlPairs(): List<Pair<Long, String>> {
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.getFavoriteSourceUrlPairs: Executing ultra-lightweight source+url query"
        }
        val queryStart = System.currentTimeMillis()
        val result = database.mangasQueries.getFavoriteSourceAndUrlPairs { source, url ->
            source to url
        }.awaitAsList()
        val queryDuration = System.currentTimeMillis() - queryStart
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.getFavoriteSourceUrlPairs: Query completed in ${queryDuration}ms, returned ${result.size} items"
        }
        return result
    }

    override suspend fun getFavoriteSourceIds(): List<Long> {
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.getFavoriteSourceIds: Executing ultra-lightweight source IDs query"
        }
        val queryStart = System.currentTimeMillis()
        val result = database.mangasQueries.getFavoriteSourceIds().awaitAsList()
        val queryDuration = System.currentTimeMillis() - queryStart
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.getFavoriteSourceIds: Query completed in ${queryDuration}ms, returned ${result.size} sources"
        }
        return result
    }

    override suspend fun getMangaWithCounts(ids: List<Long>): List<MangaWithChapterCount> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(500).flatMap { chunk ->
            database.mangasQueries.getMangaWithCounts(chunk) {
                    id,
                    source,
                    url,
                    artist,
                    author,
                    description,
                    genre,
                    title,
                    alternative_titles,
                    status,
                    thumbnail_url,
                    favorite,
                    last_update,
                    next_update,
                    initialized,
                    viewer,
                    chapter_flags,
                    cover_last_modified,
                    date_added,
                    update_strategy,
                    calculate_interval,
                    last_modified_at,
                    favorite_modified_at,
                    version,
                    is_syncing,
                    notes,
                    is_novel,
                    totalCount,
                    readCount,
                ->
                MangaMapper.mapMangaWithChapterCount(
                    id = id,
                    source = source,
                    url = url,
                    artist = artist,
                    author = author,
                    description = description,
                    genre = genre,
                    title = title,
                    alternativeTitles = alternative_titles,
                    status = status,
                    thumbnailUrl = thumbnail_url,
                    favorite = favorite,
                    lastUpdate = last_update,
                    nextUpdate = next_update,
                    initialized = initialized,
                    viewerFlags = viewer,
                    chapterFlags = chapter_flags,
                    coverLastModified = cover_last_modified,
                    dateAdded = date_added,
                    updateStrategy = update_strategy,
                    calculateInterval = calculate_interval,
                    lastModifiedAt = last_modified_at,
                    favoriteModifiedAt = favorite_modified_at,
                    version = version,
                    isSyncing = is_syncing,
                    notes = notes,
                    isNovel = is_novel,
                    totalCount = totalCount,
                    readCount = readCount,
                )
            }.awaitAsList()
        }
    }

    override suspend fun getMangaWithCountsLight(ids: List<Long>): List<MangaWithChapterCount> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(500).flatMap { chunk ->
            database.mangasQueries.getMangaWithCountsLight(chunk) {
                    id,
                    source,
                    url,
                    artist,
                    author,
                    description,
                    genre,
                    title,
                    alternative_titles,
                    status,
                    thumbnail_url,
                    favorite,
                    last_update,
                    next_update,
                    initialized,
                    viewer,
                    chapter_flags,
                    cover_last_modified,
                    date_added,
                    update_strategy,
                    calculate_interval,
                    last_modified_at,
                    favorite_modified_at,
                    version,
                    is_syncing,
                    notes,
                    is_novel,
                    totalCount,
                    readCount,
                ->
                MangaMapper.mapMangaWithChapterCount(
                    id = id,
                    source = source,
                    url = url,
                    artist = artist,
                    author = author,
                    description = description,
                    genre = genre,
                    title = title,
                    alternativeTitles = alternative_titles,
                    status = status,
                    thumbnailUrl = thumbnail_url,
                    favorite = favorite,
                    lastUpdate = last_update,
                    nextUpdate = next_update,
                    initialized = initialized,
                    viewerFlags = viewer,
                    chapterFlags = chapter_flags,
                    coverLastModified = cover_last_modified,
                    dateAdded = date_added,
                    updateStrategy = update_strategy,
                    calculateInterval = calculate_interval,
                    lastModifiedAt = last_modified_at,
                    favoriteModifiedAt = favorite_modified_at,
                    version = version,
                    isSyncing = is_syncing,
                    notes = notes,
                    isNovel = is_novel,
                    totalCount = totalCount,
                    readCount = readCount,
                )
            }.awaitAsList()
        }
    }

    override suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>> {
        val epochMillis = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000
        return database.mangasQueries.getUpcomingManga(epochMillis, statuses) {
                id,
                source,
                url,
                artist,
                author,
                description,
                genre,
                title,
                alternative_titles,
                status,
                thumbnail_url,
                favorite,
                last_update,
                next_update,
                initialized,
                viewer,
                chapter_flags,
                cover_last_modified,
                date_added,
                update_strategy,
                calculate_interval,
                last_modified_at,
                favorite_modified_at,
                version,
                is_syncing,
                notes,
                is_novel,
                _,
                _,
                _,
                _,
                _,
                _,
                memo,
            ->
            MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel).copy(
                memo = memo,
            )
        }.subscribeToList()
    }

    override suspend fun resetViewerFlags(): Boolean {
        return try {
            database.mangasQueries.resetViewerFlags()
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) {
        database.transaction {
            database.mangas_categoriesQueries.deleteMangaCategoryByMangaId(mangaId)
            categoryIds.map { categoryId ->
                database.mangas_categoriesQueries.insert(mangaId, categoryId)
            }
        }
        // Categories are now JOINed from mangas_categories at query time, no cache update needed
        invalidateLibraryCacheInternal()
    }

    override suspend fun setMangasCategories(mangaIds: List<Long>, categoryIds: List<Long>) {
        database.transaction {
            // Delete all existing categories for the mangas first
            mangaIds.forEach { mangaId ->
                database.mangas_categoriesQueries.deleteMangaCategoryByMangaId(mangaId)
            }
            mangaIds.forEach { mangaId ->
                categoryIds.forEach { categoryId ->
                    database.mangas_categoriesQueries.insertBulkMangaCategory(mangaId, categoryId)
                }
            }
        }
        invalidateLibraryCacheInternal()
    }

    override suspend fun addMangasCategories(mangaIds: List<Long>, categoryIds: List<Long>) {
        database.transaction {
            mangaIds.forEach { mangaId ->
                categoryIds.forEach { categoryId ->
                    database.mangas_categoriesQueries.insertBulkMangaCategory(mangaId, categoryId)
                }
            }
        }
        invalidateLibraryCacheInternal()
    }

    override suspend fun removeMangasCategories(mangaIds: List<Long>, categoryIds: List<Long>) {
        if (mangaIds.isEmpty() || categoryIds.isEmpty()) return
        database.transaction {
            database.mangas_categoriesQueries.deleteBulkMangaCategories(mangaIds, categoryIds)
        }
        invalidateLibraryCacheInternal()
    }

    override suspend fun update(update: MangaUpdate): Boolean {
        return try {
            partialUpdate(update)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return try {
            partialUpdate(*mangaUpdates.toTypedArray())
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun insertNetworkManga(manga: List<Manga>): List<Manga> {
        val normalizeTags = normalizeTagsOnUpdate
        return database.transactionWithResult {
            manga.map {
                database.mangasQueries.insertNetworkManga(
                    source = it.source,
                    url = it.url,
                    artist = it.artist,
                    author = it.author,
                    description = it.description,
                    genre = if (normalizeTags) it.genre?.let { g -> normalizeTagList(g) } else it.genre,
                    title = it.title,
                    alternativeTitles = it.alternativeTitles,
                    status = it.status,
                    thumbnailUrl = it.thumbnailUrl,
                    favorite = it.favorite,
                    lastUpdate = it.lastUpdate,
                    nextUpdate = it.nextUpdate,
                    calculateInterval = it.fetchInterval.toLong(),
                    initialized = it.initialized,
                    viewerFlags = it.viewerFlags,
                    chapterFlags = it.chapterFlags,
                    coverLastModified = it.coverLastModified,
                    dateAdded = it.dateAdded,
                    updateStrategy = it.updateStrategy,
                    version = it.version,
                    isNovel = it.isNovel,
                    memo = it.memo,
                    updateTitle = it.title.isNotBlank(),
                    updateCover = !it.thumbnailUrl.isNullOrBlank() && it.thumbnailUrl!!.contains("://"),
                    updateDetails = it.initialized,
                    mapper = MangaMapper::mapMangaFull,
                )
                    .awaitAsOne()
            }
        }
    }

    private suspend fun partialUpdate(vararg mangaUpdates: MangaUpdate) {
        // Read the pref once per batch, not per row.
        val normalizeTags = normalizeTagsOnUpdate
        database.transaction {
            mangaUpdates.forEach { value ->
                val genre = if (normalizeTags) value.genre?.let { normalizeTagList(it) } else value.genre
                database.mangasQueries.update(
                    source = value.source,
                    url = value.url,
                    artist = value.artist,
                    author = value.author,
                    description = value.description,
                    genre = genre?.let(StringListColumnAdapter::encode),
                    title = value.title,
                    alternativeTitles = value.alternativeTitles?.let(AlternativeTitlesColumnAdapter::encode),
                    status = value.status,
                    thumbnailUrl = value.thumbnailUrl,
                    favorite = value.favorite,
                    lastUpdate = value.lastUpdate,
                    nextUpdate = value.nextUpdate,
                    calculateInterval = value.fetchInterval?.toLong(),
                    initialized = value.initialized,
                    viewer = value.viewerFlags,
                    chapterFlags = value.chapterFlags,
                    coverLastModified = value.coverLastModified,
                    dateAdded = value.dateAdded,
                    mangaId = value.id,
                    updateStrategy = value.updateStrategy?.let(UpdateStrategyColumnAdapter::encode),
                    version = value.version,
                    isSyncing = 0,
                    notes = value.notes,
                    isNovel = value.isNovel,
                    memo = value.memo?.let(MemoColumnAdapter::encode),
                )
            }
            val favoriteChangedIds = mangaUpdates.filter { it.favorite != null }.map { it.id }
            if (favoriteChangedIds.isNotEmpty()) {
                favoriteChangedIds.forEach { id ->
                    database.mangasQueries.recomputeAggregatesForManga(id)
                }
            }
        }
        // Always invalidate library cache since libraryGrid reads title, genre, cover,
        // status, etc. directly from mangas table - any field change may affect library display
        invalidateLibraryCacheInternal()
        if (mangaUpdates.any { it.favorite != null }) {
            invalidateFavoriteUrlCache()
        }
    }

    override suspend fun normalizeAllUrls(): Int {
        return try {
            // First check for potential duplicates
            val duplicates = database.mangasQueries.getPotentialNormalizationDuplicates().awaitAsList()

            if (duplicates.isNotEmpty()) {
                logcat(LogPriority.WARN) {
                    "Found ${duplicates.size} potential duplicate URL conflicts - skipping normalization"
                }
                return 0
            }

            database.transaction {
                database.mangasQueries.normalizeUrls()
            }

            // Return approximate count (we don't have exact count from UPDATE)
            // Could query before/after but that's additional overhead
            logcat(LogPriority.INFO) { "URL normalization completed" }
            1 // Return 1 to indicate success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to normalize URLs" }
            0
        }
    }

    override suspend fun normalizeAllUrlsAdvanced(removeDoubleSlashes: Boolean): Pair<Int, List<MangaRepository.DuplicateUrlInfo>> {
        return try {
            var count = 0
            val duplicates = mutableListOf<MangaRepository.DuplicateUrlInfo>()
            val seen = mutableSetOf<Pair<Long, String>>()
            database.transaction {
                val allManga = database.mangasQueries.getAllMangaUrlMaintenanceRows {
                        id,
                        source,
                        url,
                        title,
                        favorite,
                    ->
                    UrlMaintenanceRow(
                        id = id,
                        source = source,
                        url = url,
                        title = title,
                        favorite = favorite,
                    )
                }.awaitAsList()

                allManga.forEach { manga ->
                    val normalizedUrl = normalizeUrlForMaintenance(manga.url, removeDoubleSlashes)

                    if (normalizedUrl != manga.url) {
                        val key = manga.source to normalizedUrl
                        if (key in seen) {
                            // This would create a duplicate - include manga ID for direct deletion
                            duplicates.add(
                                MangaRepository.DuplicateUrlInfo(manga.id, manga.title, manga.url, normalizedUrl),
                            )
                            logcat(LogPriority.WARN) {
                                "Skipping duplicate: ${manga.title} (${manga.url}) would conflict with existing normalized URL"
                            }
                        } else {
                            database.mangasQueries.update(
                                source = null,
                                url = normalizedUrl,
                                artist = null,
                                author = null,
                                description = null,
                                genre = null,
                                title = null,
                                alternativeTitles = null,
                                status = null,
                                thumbnailUrl = null,
                                favorite = null,
                                lastUpdate = null,
                                nextUpdate = null,
                                calculateInterval = null,
                                initialized = null,
                                viewer = null,
                                chapterFlags = null,
                                coverLastModified = null,
                                dateAdded = null,
                                mangaId = manga.id,
                                updateStrategy = null,
                                version = null,
                                isSyncing = null,
                                notes = null,
                                isNovel = null,
                                memo = null,
                            )
                            seen.add(key)
                            count++
                        }
                    } else {
                        seen.add(manga.source to manga.url)
                    }
                }
            }
            Pair(count, duplicates)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to normalize URLs" }
            Pair(0, emptyList())
        }
    }

    override suspend fun removePotentialDuplicates(removeDoubleSlashes: Boolean): Pair<Int, List<Triple<String, String, String>>> {
        return try {
            var removedCount = 0
            val removedItems = mutableListOf<Triple<String, String, String>>()
            val seenNormalizedUrls = mutableMapOf<Pair<Long, String>, Long>()
            val idsToDelete = mutableListOf<Long>()

            database.transaction {
                val allManga = database.mangasQueries.getAllMangaUrlMaintenanceRows {
                        id,
                        source,
                        url,
                        title,
                        favorite,
                    ->
                    UrlMaintenanceRow(
                        id = id,
                        source = source,
                        url = url,
                        title = title,
                        favorite = favorite,
                    )
                }.awaitAsList()

                // First pass: identify which manga would be kept (first occurrence of each normalized URL)
                allManga.forEach { manga ->
                    val normalizedUrl = normalizeUrlForMaintenance(manga.url, removeDoubleSlashes)

                    val key = manga.source to normalizedUrl
                    if (key !in seenNormalizedUrls) {
                        seenNormalizedUrls[key] = manga.id
                    }
                }

                // Second pass: collect manga IDs that are duplicates (not the first occurrence)
                allManga.forEach { manga ->
                    if (!manga.favorite) return@forEach // Skip non-favorites

                    val normalizedUrl = normalizeUrlForMaintenance(manga.url, removeDoubleSlashes)

                    val key = manga.source to normalizedUrl
                    val firstOccurrenceId = seenNormalizedUrls[key]

                    // If this manga is not the first occurrence of this normalized URL, mark for deletion
                    if (firstOccurrenceId != null && firstOccurrenceId != manga.id) {
                        idsToDelete.add(manga.id)
                        removedItems.add(Triple(manga.title, manga.url, normalizedUrl))
                        removedCount++
                        logcat(LogPriority.INFO) {
                            "Marked for deletion: ${manga.title} (${manga.url}) - conflicts with normalized URL"
                        }
                    }
                }

                // Delete all duplicate manga in one batch
                // Chapters and categories are automatically deleted via ON DELETE CASCADE
                if (idsToDelete.isNotEmpty()) {
                    database.mangasQueries.deleteByIds(idsToDelete)
                }
            }

            logcat(LogPriority.INFO) { "Deleted $removedCount duplicate manga entries" }
            Pair(removedCount, removedItems)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to remove potential duplicates" }
            Pair(0, emptyList())
        }
    }

    override suspend fun refreshLibraryCache() {
        logcat(LogPriority.INFO) { "MangaRepositoryImpl.refreshLibraryCache: Recomputing all aggregates" }
        val queryStart = System.currentTimeMillis()
        database.transaction {
            database.mangasQueries.recomputeAllAggregates()
        }
        val queryDuration = System.currentTimeMillis() - queryStart
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.refreshLibraryCache: Aggregates recomputed in ${queryDuration}ms"
        }
        invalidateLibraryCacheInternal()
    }

    override suspend fun refreshLibraryCacheIncremental() {
        logcat(LogPriority.INFO) { "MangaRepositoryImpl.refreshLibraryCacheIncremental: Recomputing all aggregates" }
        val queryStart = System.currentTimeMillis()
        database.transaction {
            database.mangasQueries.recomputeAllAggregates()
        }
        val queryDuration = System.currentTimeMillis() - queryStart
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.refreshLibraryCacheIncremental: Completed in ${queryDuration}ms"
        }
        invalidateLibraryCacheInternal()
    }

    override suspend fun refreshLibraryCacheForManga(mangaId: Long) {
        logcat(LogPriority.DEBUG) {
            "MangaRepositoryImpl.refreshLibraryCacheForManga: Recomputing aggregates for manga $mangaId"
        }
        database.transaction {
            database.mangasQueries.recomputeAggregatesForManga(mangaId)
        }
    }

    override suspend fun refreshLibraryCacheForMangas(mangaIds: List<Long>) {
        if (mangaIds.isEmpty()) return
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.refreshLibraryCacheForMangas: Recomputing aggregates for ${mangaIds.size} manga"
        }
        database.transaction {
            database.mangasQueries.recomputeAggregatesForMangas(mangaIds)
        }
    }

    /**
     * Canonical tag normalization: split each entry on `,` / `;` (sources may merge tags), trim,
     * title-case each word, drop blanks, dedupe case-insensitively keeping first occurrence. Shared
     * by [normalizeAllTags] and the on-write normalization gated by
     * [LibraryPreferences.normalizeTagsOnUpdate].
     */
    private fun normalizeTagList(genres: List<String>): List<String> {
        val seen = HashSet<String>()
        return genres
            .flatMap { tag -> tag.split(",", ";").map { it.trim() } }
            .map { tag ->
                tag.trim()
                    .split(" ")
                    .joinToString(" ") { word ->
                        word.lowercase().replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase() else it.toString()
                        }
                    }
            }
            .filter { it.isNotBlank() }
            .filter { tag ->
                val lowercased = tag.lowercase()
                seen.add(lowercased)
            }
    }

    private val normalizeTagsOnUpdatePref by lazy {
        runCatching { Injekt.get<LibraryPreferences>().normalizeTagsOnUpdate }.getOrNull()
    }
    private val normalizeTagsOnUpdate: Boolean
        get() = normalizeTagsOnUpdatePref?.get() ?: false

    override suspend fun normalizeAllTags(): Int {
        logcat(LogPriority.INFO) { "MangaRepositoryImpl.normalizeAllTags: Starting tag normalization" }
        return try {
            val favoriteGenres = getFavoriteIdAndGenre()
            var count = 0
            database.transaction {
                favoriteGenres.forEach { (mangaId, genres) ->
                    if (!genres.isNullOrEmpty()) {
                        val normalized = normalizeTagList(genres)

                        // Only update if there's a difference
                        if (normalized != genres) {
                            database.mangasQueries.update(
                                source = null,
                                url = null,
                                artist = null,
                                author = null,
                                description = null,
                                genre = normalized.ifEmpty { null }?.let(StringListColumnAdapter::encode),
                                title = null,
                                alternativeTitles = null,
                                status = null,
                                thumbnailUrl = null,
                                favorite = null,
                                lastUpdate = null,
                                nextUpdate = null,
                                initialized = null,
                                viewer = null,
                                chapterFlags = null,
                                coverLastModified = null,
                                dateAdded = null,
                                updateStrategy = null,
                                calculateInterval = null,
                                version = null,
                                isSyncing = null,
                                notes = null,
                                isNovel = null,
                                memo = null,
                                mangaId = mangaId,
                            )
                            count++
                        }
                    }
                }
            }
            logcat(LogPriority.INFO) { "MangaRepositoryImpl.normalizeAllTags: Normalized tags for $count manga" }
            count
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to normalize tags" }
            0
        }
    }

    override suspend fun checkLibraryCacheIntegrity(): Pair<Long, Long> {
        // Aggregates live directly on the mangas table, no separate cache exists.
        // Always report valid to avoid unnecessary recomputation on startup.
        return 0L to 0L
    }

    companion object {
        private const val SQLITE_VARIABLE_LIMIT = 900
    }
}
