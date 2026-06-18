package tachiyomi.domain.source.model

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate

class StubSource(
    override val id: Long,
    override val lang: String,
    override val name: String,
    override val isNovelSource: Boolean = false,
) : Source {

    private val isInvalid: Boolean = name.isBlank() || lang.isBlank()

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

    override fun toString(): String =
        if (!isInvalid) "$name (${lang.uppercase()})" else id.toString()

    companion object {
        fun from(source: Source): StubSource {
            return StubSource(
                id = source.id,
                lang = source.lang,
                name = source.name,
                isNovelSource = source.isNovelSource(),
            )
        }

        /**
         * Create a StubSource that preserves the full display name (including markers like "(JS)")
         */
        fun fromWithDisplayName(source: Source, displayName: String): StubSource {
            return StubSource(
                id = source.id,
                lang = source.lang,
                name = displayName,
                isNovelSource = source.isNovelSource(),
            )
        }
    }
}

class SourceNotInstalledException : Exception()
