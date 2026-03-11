@file:Suppress("ktlint:standard:max-line-length", "ktlint:standard:property-naming")

package tachiyomi.data.manga

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibraryMangaForUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.DuplicateGroup
import tachiyomi.domain.manga.repository.DuplicatePair
import tachiyomi.domain.manga.repository.MangaRepository
import java.time.LocalDate
import java.time.ZoneId

class MangaRepositoryImpl(
    private val handler: DatabaseHandler,
) : MangaRepository {

    override suspend fun getMangaById(id: Long): Manga {
        return handler.awaitOne {
            mangasQueries.getMangaById(id) {
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
                ->
                MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel)
            }
        }
    }

    override suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga> {
        return handler.subscribeToOne {
            mangasQueries.getMangaById(id) {
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
                ->
                MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel)
            }
        }
    }

    override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
        return handler.awaitOneOrNull {
            mangasQueries.getMangaByUrlAndSource(
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
                ->
                MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel)
            }
        }
    }

    override suspend fun getLiteMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
        return handler.awaitOneOrNull {
            mangasQueries.getLiteMangaByUrlAndSource(url, sourceId) {
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
            }
        }
    }

    override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> {
        return handler.subscribeToOneOrNull {
            mangasQueries.getMangaByUrlAndSource(
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
                ->
                MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel)
            }
        }
    }

    override suspend fun getFavorites(): List<Manga> {
        return handler.awaitList {
            mangasQueries.getFavorites {
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
                ->
                MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel)
            }
        }
    }

    override suspend fun getFavoritesEntry(): List<Manga> {
        return handler.awaitList {
            mangasQueries.getFavoritesEntry {
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
            }
        }
    }

    override fun getFavoritesEntryBySourceId(sourceId: Long): Flow<List<Manga>> {
        return handler.subscribeToList {
            mangasQueries.getFavoritesEntryBySourceId(sourceId) {
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
            }
        }
    }

    override suspend fun getFavoriteSourceAndUrl(): List<Pair<Long, String>> {
        val now = System.currentTimeMillis()
        if (cachedFavoriteSourceUrl != null && now - favoriteSourceUrlCacheTimestamp < FAVORITE_URL_CACHE_VALIDITY_MS) {
            return cachedFavoriteSourceUrl!!
        }

        val result = handler.awaitList {
            mangasQueries.getFavoriteSourceAndUrl { source, url -> source to url }
        }

        cachedFavoriteSourceUrl = result
        favoriteSourceUrlCacheTimestamp = now
        return result
    }

    override suspend fun getFavoriteIdAndUrl(): List<Pair<Long, String>> {
        return handler.awaitList {
            mangasQueries.getFavoriteIdAndUrl { id, url -> id to url }
        }
    }

    override suspend fun getFavoriteIdAndGenre(): List<Pair<Long, List<String>?>> {
        return handler.awaitList {
            mangasQueries.getFavoriteIdAndGenre { id, genre ->
                id to genre
            }
        }
    }

    override suspend fun getFavoriteIdAndTotalCount(): List<Pair<Long, Long>> {
        return handler.awaitList {
            mangasQueries.getFavoriteIdAndTotalCount { id, totalCount ->
                id to totalCount
            }
        }
    }

    override suspend fun getReadMangaNotInLibrary(): List<Manga> {
        return handler.awaitList {
            mangasQueries.getReadMangaNotInLibrary {
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
                ->
                MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel)
            }
        }
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
        val result = handler.awaitList {
            mangasQueries.libraryGrid {
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
            }
        }

        val queryDuration = System.currentTimeMillis() - queryStart
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.getLibraryManga: Query completed in ${queryDuration}ms, returned ${result.size} items"
        }

        // Update cache
        cachedLibraryManga = result
        cacheTimestamp = now
        return result
    }

    override suspend fun getLibraryMangaById(mangaId: Long): LibraryManga? {
        return handler.awaitOneOrNull {
            mangasQueries.libraryGridById(mangaId) {
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
            }
        }
    }

    override suspend fun getLibraryMangaByIds(mangaIds: List<Long>): List<LibraryManga> {
        if (mangaIds.isEmpty()) return emptyList()
        return handler.awaitList {
            mangasQueries.libraryGridByIds(mangaIds) {
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
            }
        }
    }

    override suspend fun getLibraryMangaForUpdate(): List<LibraryMangaForUpdate> {
        logcat(LogPriority.INFO) { "MangaRepositoryImpl.getLibraryMangaForUpdate: Executing lightweight query" }
        val queryStart = System.currentTimeMillis()
        val result = handler.awaitList {
            mangasQueries.libraryForUpdate {
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
            }
        }
        val queryDuration = System.currentTimeMillis() - queryStart
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.getLibraryMangaForUpdate: Query completed in ${queryDuration}ms, returned ${result.size} items"
        }
        return result
    }

    override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> {
        logcat(LogPriority.INFO) { "MangaRepositoryImpl.getLibraryMangaAsFlow: Creating new Flow subscription" }
        return handler.subscribeToList {
            logcat(LogPriority.INFO) { "MangaRepositoryImpl.getLibraryMangaAsFlow: Executing libraryGrid query" }
            mangasQueries.libraryGrid {
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
            }
        }
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
        return handler.subscribeToList {
            mangasQueries.getFavoriteBySourceId(sourceId) {
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
                ->
                MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel)
            }
        }
    }

    override suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount> {
        return handler.awaitList {
            mangasQueries.getDuplicateLibraryManga(id, title) {
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
            }
        }
    }

    override suspend fun findDuplicatesExact(): List<DuplicateGroup> {
        return handler.awaitList {
            mangasQueries.findDuplicatesExact { normalizedTitle, ids, count ->
                DuplicateGroup(
                    normalizedTitle = normalizedTitle ?: "",
                    ids =
                    ids?.let { idString -> idString.split(",").mapNotNull { id -> id.toLongOrNull() } }
                        ?: emptyList(),
                    count = count.toInt(),
                )
            }
        }
    }

    override suspend fun findDuplicatesContains(): List<DuplicatePair> {
        return handler.awaitList {
            mangasQueries.findDuplicatesContains { idA, titleA, idB, titleB ->
                DuplicatePair(
                    idA = idA,
                    titleA = titleA,
                    idB = idB,
                    titleB = titleB,
                )
            }
        }
    }

    override suspend fun getFavoriteIdAndTitle(): List<Pair<Long, String>> {
        return handler.awaitList {
            mangasQueries.getFavoriteIdAndTitle { id, title ->
                id to title
            }
        }
    }

    override suspend fun findDuplicatesByUrl(): List<DuplicateGroup> {
        return handler.awaitList {
            mangasQueries.findDuplicatesByUrl { url, source, ids, count ->
                DuplicateGroup(
                    normalizedTitle = url, // Using URL as the group key
                    ids =
                    ids?.let { idString -> idString.split(",").mapNotNull { id -> id.toLongOrNull() } }
                        ?: emptyList(),
                    count = count.toInt(),
                )
            }
        }
    }

    override suspend fun getFavoriteGenres(): List<Pair<Long, List<String>?>> {
        logcat(LogPriority.INFO) { "MangaRepositoryImpl.getFavoriteGenres: Executing lightweight genres query" }
        val queryStart = System.currentTimeMillis()
        val result = handler.awaitList {
            mangasQueries.getFavoriteGenres { id, genre ->
                // genre is already List<String>? via StringListColumnAdapter
                id to genre
            }
        }
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
        val result = handler.awaitList {
            mangasQueries.getFavoriteGenresWithSource { id, source, genre ->
                Triple(id, source, genre)
            }
        }
        val queryDuration = System.currentTimeMillis() - queryStart
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.getFavoriteGenresWithSource: Query completed in ${queryDuration}ms, returned ${result.size} items"
        }
        return result
    }

    override suspend fun getFavoriteSourceUrlPairs(): List<Pair<Long, String>> {
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.getFavoriteSourceUrlPairs: Executing ultra-lightweight source+url query"
        }
        val queryStart = System.currentTimeMillis()
        val result = handler.awaitList {
            mangasQueries.getFavoriteSourceAndUrlPairs { source, url ->
                source to url
            }
        }
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
        val result = handler.awaitList {
            mangasQueries.getFavoriteSourceIds()
        }
        val queryDuration = System.currentTimeMillis() - queryStart
        logcat(LogPriority.INFO) {
            "MangaRepositoryImpl.getFavoriteSourceIds: Query completed in ${queryDuration}ms, returned ${result.size} sources"
        }
        return result
    }

    override suspend fun getMangaWithCounts(ids: List<Long>): List<MangaWithChapterCount> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(500).flatMap { chunk ->
            handler.awaitList {
                mangasQueries.getMangaWithCounts(chunk) {
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
                }
            }
        }
    }

    override suspend fun getMangaWithCountsLight(ids: List<Long>): List<MangaWithChapterCount> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(500).flatMap { chunk ->
            handler.awaitList {
                mangasQueries.getMangaWithCountsLight(chunk) {
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
                }
            }
        }
    }

    override suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>> {
        val epochMillis = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000
        return handler.subscribeToList {
            mangasQueries.getUpcomingManga(epochMillis, statuses) {
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
                ->
                MangaMapper.mapManga(id, source, url, artist, author, description, genre, title, alternative_titles, status, thumbnail_url, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, is_syncing, notes, is_novel)
            }
        }
    }

    override suspend fun resetViewerFlags(): Boolean {
        return try {
            handler.await { mangasQueries.resetViewerFlags() }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) {
        handler.await(inTransaction = true) {
            mangas_categoriesQueries.deleteMangaCategoryByMangaId(mangaId)
            categoryIds.map { categoryId ->
                mangas_categoriesQueries.insert(mangaId, categoryId)
            }
        }
        // Categories are now JOINed from mangas_categories at query time, no cache update needed
        invalidateLibraryCacheInternal()
    }

    override suspend fun setMangasCategories(mangaIds: List<Long>, categoryIds: List<Long>) {
        handler.await(inTransaction = true) {
            // Delete all existing categories for the mangas first
            mangaIds.forEach { mangaId ->
                mangas_categoriesQueries.deleteMangaCategoryByMangaId(mangaId)
            }
            mangaIds.forEach { mangaId ->
                categoryIds.forEach { categoryId ->
                    mangas_categoriesQueries.insertBulkMangaCategory(mangaId, categoryId)
                }
            }
        }
        invalidateLibraryCacheInternal()
    }

    override suspend fun addMangasCategories(mangaIds: List<Long>, categoryIds: List<Long>) {
        handler.await(inTransaction = true) {
            mangaIds.forEach { mangaId ->
                categoryIds.forEach { categoryId ->
                    mangas_categoriesQueries.insertBulkMangaCategory(mangaId, categoryId)
                }
            }
        }
        invalidateLibraryCacheInternal()
    }

    override suspend fun removeMangasCategories(mangaIds: List<Long>, categoryIds: List<Long>) {
        if (mangaIds.isEmpty() || categoryIds.isEmpty()) return
        handler.await(inTransaction = true) {
            mangas_categoriesQueries.deleteBulkMangaCategories(mangaIds, categoryIds)
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
        return handler.await(inTransaction = true) {
            manga.map {
                mangasQueries.insertNetworkManga(
                    source = it.source,
                    url = it.url,
                    artist = it.artist,
                    author = it.author,
                    description = it.description,
                    genre = it.genre,
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
                    updateTitle = it.title.isNotBlank(),
                    updateCover = !it.thumbnailUrl.isNullOrBlank(),
                    updateDetails = it.initialized,
                    mapper = MangaMapper::mapMangaFull,
                )
                    .executeAsOne()
            }
        }
    }

    private suspend fun partialUpdate(vararg mangaUpdates: MangaUpdate) {
        handler.await(inTransaction = true) {
            mangaUpdates.forEach { value ->
                mangasQueries.update(
                    source = value.source,
                    url = value.url,
                    artist = value.artist,
                    author = value.author,
                    description = value.description,
                    genre = value.genre?.let(StringListColumnAdapter::encode),
                    title = value.title,
                    alternativeTitles = value.alternativeTitles?.let(StringListColumnAdapter::encode),
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
                )
            }
            val favoriteChangedIds = mangaUpdates.filter { it.favorite != null }.map { it.id }
            if (favoriteChangedIds.isNotEmpty()) {
                favoriteChangedIds.forEach { id ->
                    mangasQueries.recomputeAggregatesForManga(id)
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
            val duplicates = handler.awaitList {
                mangasQueries.getPotentialNormalizationDuplicates()
            }

            if (duplicates.isNotEmpty()) {
                logcat(LogPriority.WARN) {
                    "Found ${duplicates.size} potential duplicate URL conflicts - skipping normalization"
                }
                return 0
            }

            handler.await(inTransaction = true) {
                mangasQueries.normalizeUrls()
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
            handler.await(inTransaction = true) {
                val allManga = mangasQueries.getAllManga(MangaMapper::mapMangaFull).executeAsList()
                allManga.forEach { manga ->
                    var normalizedUrl = manga.url.trimEnd('/').substringBefore('#')

                    // Remove double slashes if enabled (but preserve protocol ://)
                    if (removeDoubleSlashes) {
                        // First, temporarily replace :// with a placeholder
                        val placeholder = "###PROTOCOL###"
                        normalizedUrl = normalizedUrl.replace("://", placeholder)
                        // Then remove double slashes
                        normalizedUrl = normalizedUrl.replace("//", "/")
                        // Restore protocol
                        normalizedUrl = normalizedUrl.replace(placeholder, "://")
                    }

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
                            mangasQueries.update(
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

            handler.await(inTransaction = true) {
                val allManga = mangasQueries.getAllManga(MangaMapper::mapMangaFull).executeAsList()

                // First pass: identify which manga would be kept (first occurrence of each normalized URL)
                allManga.forEach { manga ->
                    var normalizedUrl = manga.url.trimEnd('/').substringBefore('#')

                    if (removeDoubleSlashes) {
                        val placeholder = "###PROTOCOL###"
                        normalizedUrl = normalizedUrl.replace("://", placeholder)
                        normalizedUrl = normalizedUrl.replace("//", "/")
                        normalizedUrl = normalizedUrl.replace(placeholder, "://")
                    }

                    val key = manga.source to normalizedUrl
                    if (key !in seenNormalizedUrls) {
                        seenNormalizedUrls[key] = manga.id
                    }
                }

                // Second pass: collect manga IDs that are duplicates (not the first occurrence)
                allManga.forEach { manga ->
                    if (!manga.favorite) return@forEach // Skip non-favorites

                    var normalizedUrl = manga.url.trimEnd('/').substringBefore('#')

                    if (removeDoubleSlashes) {
                        val placeholder = "###PROTOCOL###"
                        normalizedUrl = normalizedUrl.replace("://", placeholder)
                        normalizedUrl = normalizedUrl.replace("//", "/")
                        normalizedUrl = normalizedUrl.replace(placeholder, "://")
                    }

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
                    mangasQueries.deleteByIds(idsToDelete)
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
        handler.await(inTransaction = true) {
            mangasQueries.recomputeAllAggregates()
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
        handler.await(inTransaction = true) {
            mangasQueries.recomputeAllAggregates()
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
        handler.await(inTransaction = true) {
            mangasQueries.recomputeAggregatesForManga(mangaId)
        }
    }

    override suspend fun normalizeAllTags(): Int {
        logcat(LogPriority.INFO) { "MangaRepositoryImpl.normalizeAllTags: Starting tag normalization" }
        return try {
            val favoriteGenres = getFavoriteIdAndGenre()
            var count = 0
            handler.await(inTransaction = true) {
                favoriteGenres.forEach { (mangaId, genres) ->
                    if (!genres.isNullOrEmpty()) {
                        // Normalize: trim, title-case, remove duplicates (keeping first occurrence)
                        val seen = mutableSetOf<String>()
                        val normalized = genres
                            .map { tag ->
                                // Trim whitespace and title-case each word
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
                                if (lowercased in seen) {
                                    false
                                } else {
                                    seen.add(lowercased)
                                    true
                                }
                            }

                        // Only update if there's a difference
                        if (normalized != genres) {
                            mangasQueries.update(
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
}
