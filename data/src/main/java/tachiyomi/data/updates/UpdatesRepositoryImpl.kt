package tachiyomi.data.updates

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

class UpdatesRepositoryImpl(
    private val database: Database,
) : UpdatesRepository {

    override suspend fun awaitWithRead(
        read: Boolean,
        after: Long,
        limit: Long,
        offset: Long,
    ): List<UpdatesWithRelations> {
        return database.updatesQueries.getUpdatesByReadStatus(
            read = read,
            after = after,
            limit = limit,
            offset = offset,
            mapper = ::mapUpdatesWithRelations,
        ).awaitAsList()
    }

    override fun subscribeAll(
        after: Long,
        limit: Long,
        offset: Long,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
    ): Flow<List<UpdatesWithRelations>> {
        return database.updatesQueries.getRecentUpdates(
            after = after,
            limit = limit,
            offset = offset,
            filterUnread = unread,
            filterStarted = started,
            filterBookmarked = bookmarked,
            hideExcludedScanlators = hideExcludedScanlators.toLong(),
            mapper = ::mapUpdatesWithRelations,
        ).subscribeToList()
    }

    override fun subscribeWithRead(
        read: Boolean,
        after: Long,
        limit: Long,
        offset: Long,
    ): Flow<List<UpdatesWithRelations>> {
        return database.updatesQueries.getUpdatesByReadStatus(
            read = read,
            after = after,
            limit = limit,
            offset = offset,
            mapper = ::mapUpdatesWithRelations,
        ).subscribeToList()
    }

    private fun mapUpdatesWithRelations(
        mangaId: Long,
        mangaTitle: String,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        chapterUrl: String,
        read: Boolean,
        bookmark: Boolean,
        lastPageRead: Long,
        sourceId: Long,
        favorite: Boolean,
        thumbnailUrl: String?,
        coverLastModified: Long,
        dateUpload: Long,
        dateFetch: Long,
        excludedScanlator: String?,
    ): UpdatesWithRelations = UpdatesWithRelations(
        mangaId = mangaId,
        mangaTitle = mangaTitle,
        chapterId = chapterId,
        chapterName = chapterName,
        scanlator = scanlator,
        chapterUrl = chapterUrl,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        sourceId = sourceId,
        dateFetch = dateFetch,
        coverData = MangaCover(
            mangaId = mangaId,
            sourceId = sourceId,
            isMangaFavorite = favorite,
            url = thumbnailUrl,
            lastModified = coverLastModified,
        ),
    )
}
