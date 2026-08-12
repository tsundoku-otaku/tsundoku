package tachiyomi.domain.source.model

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate

data class StubSource(
    override val id: Long,
    override val lang: String,
    override val name: String,
    override val isNovelSource: Boolean = false,
    val isJsSource: Boolean = false,
) : Source {

    // True for a stub whose real type was never learned - the extension it belongs to has
    // neither been loaded on this device before (registerStubSource) nor is present in an
    // installed extension repo's listing (extensionManager.getSourceData). Backup restores that
    // reference such a source can't know if it's a manga or novel source.
    val isInvalid: Boolean = name.isBlank() || lang.isBlank()

    override val supportsLatest: Boolean = false

    override suspend fun getPopularManga(page: Int): MangasPage = throw SourceNotInstalledException()

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw SourceNotInstalledException()

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        throw SourceNotInstalledException()

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = throw SourceNotInstalledException()

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        throw SourceNotInstalledException()

    // Must match the loaded source's toString() so quotes/translations resolve to the same on-disk
    // directory whether the source is currently loaded or only stubbed (plugin not yet loaded).
    // JS plugins and custom novel sources render as "Name (LANG) (JS)"; the built-in local
    // sources (ids 0/1) override toString() to their bare name with no lang/marker.
    override fun toString(): String = when {
        isInvalid -> id.toString()
        id == LOCAL_SOURCE_ID || id == LOCAL_NOVEL_SOURCE_ID -> name
        isJsSource -> "$name (${lang.uppercase()})$JS_SOURCE_MARKER"
        else -> "$name (${lang.uppercase()})"
    }

    companion object {
        // Kept in sync with LocalSource.ID (0) / LocalNovelSource.ID (1) in :source-local, which
        // :domain cannot depend on. Their toString() is the bare name, unlike installed sources.
        private const val LOCAL_SOURCE_ID = 0L
        private const val LOCAL_NOVEL_SOURCE_ID = 1L

        fun from(source: Source): StubSource {
            return StubSource(
                id = source.id,
                lang = source.lang,
                name = source.name,
                isNovelSource = source.isNovelSource(),
                isJsSource = source.hasJsMarker(),
            )
        }
    }
}

class SourceNotInstalledException : Exception()
