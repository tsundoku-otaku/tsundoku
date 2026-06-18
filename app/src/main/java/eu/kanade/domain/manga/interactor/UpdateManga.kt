package eu.kanade.domain.manga.interactor

import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.source.SourceTrackerDispatcher
import eu.kanade.tachiyomi.data.translation.TranslationEngineManager
import eu.kanade.tachiyomi.source.model.SManga
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.ZonedDateTime

class UpdateManga(
    private val mangaRepository: MangaRepository,
    private val fetchInterval: FetchInterval,
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
) {

    private val sourceTrackerDispatcher: SourceTrackerDispatcher by lazy { Injekt.get() }
    private val getManga: GetManga by lazy { Injekt.get() }

    private suspend fun notifySourceTrackerFavorite(mangaId: Long, favorite: Boolean) {
        try {
            val manga = getManga.await(mangaId) ?: return
            if (favorite) {
                sourceTrackerDispatcher.notifyFavorited(manga)
            } else {
                sourceTrackerDispatcher.notifyUnfavorited(manga)
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "SourceTrackerDispatcher favorite fan-out failed" }
        }
    }

    suspend fun await(mangaUpdate: MangaUpdate): Boolean {
        return mangaRepository.update(mangaUpdate)
    }

    suspend fun awaitAll(mangaUpdates: List<MangaUpdate>): Boolean {
        val result = mangaRepository.updateAll(mangaUpdates)
        if (result && mangaUpdates.any { it.favorite != null }) {
            val favoriteChanges = mangaUpdates.filter { it.favorite != null }
            val toAdd = favoriteChanges.filter { it.favorite == true }.map { it.id }
            val toRemove = favoriteChanges.filter { it.favorite == false }.map { it.id }
            if (toAdd.isNotEmpty()) {
                getLibraryManga.addToLibraryBulk(toAdd)
            }
            if (toRemove.isNotEmpty()) {
                getLibraryManga.removeFromLibrary(toRemove)
            }
            toAdd.forEach { notifySourceTrackerFavorite(it, true) }
            toRemove.forEach { notifySourceTrackerFavorite(it, false) }
        }
        return result
    }

    suspend fun awaitUpdateFromSource(
        localManga: Manga,
        remoteManga: SManga,
        manualFetch: Boolean,
        coverCache: CoverCache = Injekt.get(),
        libraryPreferences: LibraryPreferences = Injekt.get(),
        downloadManager: DownloadManager = Injekt.get(),
    ): Boolean {
        val remoteTitle = try {
            remoteManga.title
        } catch (_: UninitializedPropertyAccessException) {
            ""
        }

        // if the manga isn't a favorite (or 'update titles' preference is enabled), set its title from source and update in db
        val title =
            if (remoteTitle.isNotEmpty() && (!localManga.favorite || libraryPreferences.updateMangaTitles.get())) {
                remoteTitle
            } else {
                null
            }

        val coverLastModified =
            when {
                // Never refresh covers if the url is empty to avoid "losing" existing covers
                remoteManga.thumbnail_url.isNullOrEmpty() -> null
                !manualFetch && localManga.thumbnailUrl == remoteManga.thumbnail_url -> null
                localManga.isLocal() -> Instant.now().toEpochMilli()
                localManga.hasCustomCover(coverCache) -> {
                    coverCache.deleteFromCache(localManga, false)
                    null
                }
                else -> {
                    coverCache.deleteFromCache(localManga, false)
                    Instant.now().toEpochMilli()
                }
            }

        val thumbnailUrl = remoteManga.thumbnail_url?.takeIf { it.isNotEmpty() }

        // Don't overwrite manually edited metadata unless the preference is enabled
        val updateMetadata = libraryPreferences.updateMangaMetadata.get()
        val status = remoteManga.status.toLong()

        // Alternative titles are always merged additively (never removed), so they don't clash with
        // manual edits and aren't gated by updateMetadata.
        val remoteAltTitles = try {
            remoteManga.altTitles
        } catch (_: Exception) {
            emptyList()
        }
        val effectiveTitle = title ?: localManga.title
        val mergedAltTitles = if (remoteAltTitles.isNotEmpty()) {
            (localManga.alternativeTitles + remoteAltTitles)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != effectiveTitle }
                .distinct()
                .takeIf { it != localManga.alternativeTitles }
        } else {
            null
        }

        val success = mangaRepository.update(
            MangaUpdate(
                id = localManga.id,
                title = title,
                coverLastModified = coverLastModified,
                author = remoteManga.author.takeIf { updateMetadata },
                artist = remoteManga.artist.takeIf { updateMetadata },
                description = remoteManga.description.takeIf { updateMetadata },
                genre = remoteManga.getGenres().takeIf { updateMetadata },
                alternativeTitles = mergedAltTitles,
                thumbnailUrl = thumbnailUrl,
                status = status.takeIf { updateMetadata },
                updateStrategy = remoteManga.update_strategy,
                initialized = true,
            ),
        )
        if (success && title != null) {
            downloadManager.renameManga(localManga, title)
        }
        if (success && localManga.favorite) {
            getLibraryManga.applyMangaDetailUpdate(localManga.id) { manga ->
                manga.copy(
                    title = title ?: manga.title,
                    thumbnailUrl = thumbnailUrl ?: manga.thumbnailUrl,
                    coverLastModified = coverLastModified ?: manga.coverLastModified,
                    status = if (updateMetadata) status else manga.status,
                    alternativeTitles = mergedAltTitles ?: manga.alternativeTitles,
                )
            }
        }
        return success
    }

    suspend fun awaitUpdateFetchInterval(
        manga: Manga,
        dateTime: ZonedDateTime = ZonedDateTime.now(),
        window: Pair<Long, Long> = fetchInterval.getWindow(dateTime),
    ): Boolean {
        return mangaRepository.update(
            fetchInterval.toMangaUpdate(manga, dateTime, window),
        )
    }

    suspend fun awaitUpdateLastUpdate(mangaId: Long): Boolean {
        return mangaRepository.update(MangaUpdate(id = mangaId, lastUpdate = Instant.now().toEpochMilli()))
    }

    suspend fun awaitUpdateCoverLastModified(mangaId: Long): Boolean {
        return mangaRepository.update(MangaUpdate(id = mangaId, coverLastModified = Instant.now().toEpochMilli()))
    }

    suspend fun awaitUpdateFavorite(mangaId: Long, favorite: Boolean): Boolean {
        val dateAdded = when (favorite) {
            true -> Instant.now().toEpochMilli()
            false -> 0
        }
        val result = mangaRepository.update(
            MangaUpdate(id = mangaId, favorite = favorite, dateAdded = dateAdded),
        )
        if (result) {
            if (favorite) {
                getLibraryManga.addToLibrary(mangaId)
            } else {
                getLibraryManga.removeFromLibrary(mangaId)
            }
            notifySourceTrackerFavorite(mangaId, favorite)
        }
        return result
    }

    suspend fun awaitUpdateAlternativeTitles(mangaId: Long, alternativeTitles: List<String>): Boolean {
        return mangaRepository.update(
            MangaUpdate(id = mangaId, alternativeTitles = alternativeTitles),
        )
    }

    suspend fun awaitUpdateNotes(mangaId: Long, notes: String): Boolean {
        return mangaRepository.update(
            MangaUpdate(id = mangaId, notes = notes),
        )
    }

    suspend fun awaitUpdateGenre(mangaId: Long, genre: List<String>): Boolean {
        return mangaRepository.update(
            MangaUpdate(id = mangaId, genre = genre),
        )
    }

    suspend fun awaitUpdateTitle(mangaId: Long, title: String): Boolean {
        val result = mangaRepository.update(
            MangaUpdate(id = mangaId, title = title),
        )
        if (result) {
            getLibraryManga.applyMangaDetailUpdate(mangaId) { it.copy(title = title) }
        }
        return result
    }

    suspend fun awaitUpdateAuthor(mangaId: Long, author: String): Boolean {
        return mangaRepository.update(
            MangaUpdate(id = mangaId, author = author),
        )
    }

    suspend fun awaitUpdateArtist(mangaId: Long, artist: String): Boolean {
        return mangaRepository.update(
            MangaUpdate(id = mangaId, artist = artist),
        )
    }

    suspend fun awaitUpdateStatus(mangaId: Long, status: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(id = mangaId, status = status),
        )
    }

    suspend fun awaitUpdateDescription(mangaId: Long, description: String): Boolean {
        return mangaRepository.update(
            MangaUpdate(id = mangaId, description = description),
        )
    }

    suspend fun awaitUpdateUrl(mangaId: Long, url: String): Boolean {
        return mangaRepository.update(
            MangaUpdate(id = mangaId, url = url),
        )
    }

    /**
     * Translate manga title and/or tags based on translation preferences.
     *
     * If replaceTitle is enabled: translated title becomes the main title, original is added to alternative titles.
     * If saveTranslatedTitleAsAlternative is enabled: translated title is added to alternative titles.
     * If translateTags is enabled: translated tags are merged with original tags.
     *
     * @param manga The manga to translate metadata for
     * @param sourceLanguage The source language (or "auto" for auto-detect)
     * @param targetLanguage The target language
     * @return true if any updates were made
     */
    suspend fun translateMangaMetadata(
        manga: Manga,
        sourceLanguage: String = "auto",
        targetLanguage: String = "en",
    ): Boolean {
        val translationPreferences: TranslationPreferences = Injekt.get()
        val engineManager: TranslationEngineManager = Injekt.get()

        if (!translationPreferences.translationEnabled().get()) {
            return false
        }

        val engine = engineManager.getEngine() ?: run {
            logcat(LogPriority.WARN) { "Translation engine not configured" }
            return false
        }

        val replaceTitle = translationPreferences.replaceTitle().get()
        val saveAsAlternative = translationPreferences.saveTranslatedTitleAsAlternative().get()
        val translateTags = translationPreferences.translateTags().get()
        val replaceTags = translationPreferences.replaceTagsInsteadOfMerge().get()

        if (!replaceTitle && !saveAsAlternative && !translateTags) {
            return false
        }

        var updatedTitle: String? = null
        var updatedAlternativeTitles: List<String>? = null
        var updatedGenre: List<String>? = null

        // Translate title if needed
        if (replaceTitle || saveAsAlternative) {
            val titleResult = engine.translateSingle(manga.title, sourceLanguage, targetLanguage)
            when (titleResult) {
                is TranslationResult.Success -> {
                    val translatedTitle = titleResult.translatedTexts.firstOrNull()
                    if (!translatedTitle.isNullOrBlank() && translatedTitle != manga.title) {
                        if (replaceTitle) {
                            // Translated becomes main title, original goes to alternatives
                            updatedTitle = translatedTitle
                            val currentAlternatives = manga.alternativeTitles.toMutableList()
                            if (!currentAlternatives.contains(manga.title)) {
                                currentAlternatives.add(0, manga.title) // Add original at start
                            }
                            updatedAlternativeTitles = currentAlternatives
                        } else if (saveAsAlternative) {
                            // Keep original title, add translated to alternatives
                            val currentAlternatives = manga.alternativeTitles.toMutableList()
                            if (!currentAlternatives.contains(translatedTitle)) {
                                currentAlternatives.add(translatedTitle)
                            }
                            updatedAlternativeTitles = currentAlternatives
                        }
                    }
                }
                is TranslationResult.Error -> {
                    logcat(LogPriority.WARN) { "Failed to translate title: ${titleResult.message}" }
                }
            }
        }

        // Translate tags if needed
        val genres = manga.genre
        if (translateTags && !genres.isNullOrEmpty()) {
            val tagsResult = engine.translate(genres, sourceLanguage, targetLanguage)
            when (tagsResult) {
                is TranslationResult.Success -> {
                    val translatedTags = tagsResult.translatedTexts
                    if (translatedTags.isNotEmpty()) {
                        // Either replace original tags or merge them based on preference
                        val resultTags = if (replaceTags) {
                            translatedTags.distinct()
                        } else {
                            // Merge original and translated tags (remove duplicates)
                            (genres + translatedTags).distinct()
                        }
                        if (resultTags != genres) {
                            updatedGenre = resultTags
                        }
                    }
                }
                is TranslationResult.Error -> {
                    logcat(LogPriority.WARN) { "Failed to translate tags: ${tagsResult.message}" }
                }
            }
        }

        // Apply updates
        if (updatedTitle == null && updatedAlternativeTitles == null && updatedGenre == null) {
            return false
        }

        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                title = updatedTitle,
                alternativeTitles = updatedAlternativeTitles,
                genre = updatedGenre,
            ),
        )
    }
}
