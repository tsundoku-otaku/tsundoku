package eu.kanade.tachiyomi.ui.library.duplicate

import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount

class DuplicateDetectionViewModelTest {

    private fun manga(
        id: Long,
        title: String = "Dup",
        source: Long = 1L,
        genre: List<String>? = null,
        status: Long = 0L,
        isNovel: Boolean = false,
    ): Manga = Manga.create().copy(
        id = id,
        title = title,
        source = source,
        genre = genre,
        status = status,
        isNovel = isNovel,
    )

    private fun entry(
        id: Long,
        title: String = "Dup",
        source: Long = 1L,
        genre: List<String>? = null,
        status: Long = 0L,
        isNovel: Boolean = false,
        chapterCount: Long = 0L,
        readCount: Long = 0L,
    ): MangaWithChapterCount = MangaWithChapterCount(
        manga = manga(id, title, source, genre, status, isNovel),
        chapterCount = chapterCount,
        readCount = readCount,
    )

    private fun baseState(
        groups: Map<String, List<MangaWithChapterCount>>,
        listingMode: Boolean = false,
        contentType: DuplicateDetectionViewModel.ContentType = DuplicateDetectionViewModel.ContentType.ALL,
        novelSourceIds: Set<Long> = emptySet(),
        selectedCategoryFilters: Set<Long> = emptySet(),
        excludedCategoryFilters: Set<Long> = emptySet(),
        filterByGroupCategory: Boolean = false,
        categoryIncludeMode: DuplicateDetectionViewModel.CategoryIncludeMode =
            DuplicateDetectionViewModel.CategoryIncludeMode.ANY,
        mangaCategoryIdSets: Map<Long, Set<Long>> = emptyMap(),
        applyLibraryFilters: Boolean = false,
        libraryFilterSnapshot: DuplicateDetectionViewModel.LibraryFilterSnapshot =
            DuplicateDetectionViewModel.LibraryFilterSnapshot(),
        mangaDownloadCounts: Map<Long, Int> = emptyMap(),
    ) = DuplicateDetectionViewModel.State(
        hasStartedAnalysis = true,
        duplicateGroups = groups,
        listingMode = listingMode,
        contentType = contentType,
        novelSourceIds = novelSourceIds,
        selectedCategoryFilters = selectedCategoryFilters,
        excludedCategoryFilters = excludedCategoryFilters,
        filterByGroupCategory = filterByGroupCategory,
        categoryIncludeMode = categoryIncludeMode,
        mangaCategoryIdSets = mangaCategoryIdSets,
        applyLibraryFilters = applyLibraryFilters,
        libraryFilterSnapshot = libraryFilterSnapshot,
        mangaDownloadCounts = mangaDownloadCounts,
    )

    // contentType x listingMode (the "list all" vs regular min-group-size=2 interaction)

    @Test
    fun `content type filters group members, listing mode allows singletons`() {
        val groups = mapOf(
            "dup" to listOf(
                entry(id = 1, source = 100), // novel
                entry(id = 2, source = 200), // manga
                entry(id = 3, source = 101), // novel
            ),
        )
        val novelIds = setOf(100L, 101L)

        val novelOnly =
            baseState(groups, contentType = DuplicateDetectionViewModel.ContentType.NOVEL, novelSourceIds = novelIds)
        assertEquals(2, novelOnly.computeFilteredGroups()["dup"]?.size)

        val mangaOnlyStrict =
            baseState(groups, contentType = DuplicateDetectionViewModel.ContentType.MANGA, novelSourceIds = novelIds)
        assertTrue(
            mangaOnlyStrict.computeFilteredGroups().isEmpty(),
            "single manga entry should not count as a duplicate",
        )

        val mangaOnlyListing = baseState(
            groups,
            listingMode = true,
            contentType = DuplicateDetectionViewModel.ContentType.MANGA,
            novelSourceIds = novelIds,
        )
        assertEquals(1, mangaOnlyListing.computeFilteredGroups()["dup"]?.size, "listing mode allows singleton groups")
    }

    // Included/excluded tags, OR vs AND, case sensitivity

    @Test
    fun `included tags OR mode keeps entries with any included tag`() {
        val groups = mapOf(
            "dup" to listOf(
                entry(id = 1, genre = listOf("Action", "Comedy")),
                entry(id = 2, genre = listOf("Romance")),
            ),
        )
        val snapshot = DuplicateDetectionViewModel.LibraryFilterSnapshot(includedTags = setOf("comedy"))
        // listingMode=true: a group filtered down to a single entry is still shown (min size 1).
        val filtered = baseState(
            groups,
            listingMode = true,
            applyLibraryFilters = true,
            libraryFilterSnapshot = snapshot,
        )
            .computeFilteredGroups()
        assertEquals(listOf(1L), filtered["dup"]?.map { it.manga.id })
    }

    @Test
    fun `included tags AND mode requires every tag on the same entry`() {
        val groups = mapOf(
            "dup" to listOf(
                entry(id = 1, genre = listOf("Action", "Comedy")),
                entry(id = 2, genre = listOf("Comedy", "Romance")),
            ),
        )
        val snapshot = DuplicateDetectionViewModel.LibraryFilterSnapshot(
            includedTags = setOf("comedy", "romance"),
            tagIncludeModeAnd = true,
        )
        val filtered = baseState(
            groups,
            listingMode = true,
            applyLibraryFilters = true,
            libraryFilterSnapshot = snapshot,
        )
            .computeFilteredGroups()
        assertEquals(listOf(2L), filtered["dup"]?.map { it.manga.id })
    }

    @Test
    fun `regular mode drops the whole group once a member filter leaves fewer than 2 entries`() {
        val groups = mapOf(
            "dup" to listOf(
                entry(id = 1, genre = listOf("Action", "Comedy")),
                entry(id = 2, genre = listOf("Romance")),
            ),
        )
        val snapshot = DuplicateDetectionViewModel.LibraryFilterSnapshot(includedTags = setOf("comedy"))
        val filtered = baseState(
            groups,
            applyLibraryFilters = true,
            libraryFilterSnapshot = snapshot,
        ).computeFilteredGroups()
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `included tags AND mode with no entry satisfying all tags drops the whole group`() {
        val groups = mapOf(
            "dup" to listOf(
                entry(id = 1, genre = listOf("Action")),
                entry(id = 2, genre = listOf("Romance")),
            ),
        )
        val snapshot = DuplicateDetectionViewModel.LibraryFilterSnapshot(
            includedTags = setOf("action", "romance"),
            tagIncludeModeAnd = true,
        )
        val filtered = baseState(
            groups,
            applyLibraryFilters = true,
            libraryFilterSnapshot = snapshot,
        ).computeFilteredGroups()
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `excluded tags OR mode drops any entry carrying an excluded tag`() {
        val groups = mapOf(
            "dup" to listOf(
                entry(id = 1, genre = listOf("Spoiler")),
                entry(id = 2, genre = listOf("Action")),
            ),
        )
        val snapshot = DuplicateDetectionViewModel.LibraryFilterSnapshot(excludedTags = setOf("spoiler"))
        val filtered = baseState(
            groups,
            listingMode = true,
            applyLibraryFilters = true,
            libraryFilterSnapshot = snapshot,
        )
            .computeFilteredGroups()
        assertEquals(listOf(2L), filtered["dup"]?.map { it.manga.id })
    }

    @Test
    fun `excluded tags AND mode only drops entries carrying every excluded tag`() {
        val groups = mapOf(
            "dup" to listOf(
                entry(id = 1, genre = listOf("Spoiler")),
                entry(id = 2, genre = listOf("Spoiler", "NSFW")),
            ),
        )
        val snapshot = DuplicateDetectionViewModel.LibraryFilterSnapshot(
            excludedTags = setOf("spoiler", "nsfw"),
            tagExcludeModeAnd = true,
        )
        val filtered = baseState(
            groups,
            listingMode = true,
            applyLibraryFilters = true,
            libraryFilterSnapshot = snapshot,
        )
            .computeFilteredGroups()
        // Only entry 2 carries both excluded tags, so only it is dropped.
        assertEquals(listOf(1L), filtered["dup"]?.map { it.manga.id })
    }

    @Test
    fun `tag case sensitivity`() {
        val genre = listOf("Action")
        val insensitive = DuplicateDetectionViewModel.LibraryFilterSnapshot(includedTags = setOf("action"))
        assertTrue(DuplicateDetectionViewModel.matchesTagFilter(genre, insensitive))

        val sensitive = insensitive.copy(tagCaseSensitive = true)
        assertFalse(DuplicateDetectionViewModel.matchesTagFilter(genre, sensitive))

        val sensitiveExactCase = sensitive.copy(includedTags = setOf("Action"))
        assertTrue(DuplicateDetectionViewModel.matchesTagFilter(genre, sensitiveExactCase))
    }

    @Test
    fun `filterNoTags IS and NOT`() {
        val noTags = DuplicateDetectionViewModel.LibraryFilterSnapshot(filterNoTags = TriState.ENABLED_IS)
        assertTrue(DuplicateDetectionViewModel.matchesTagFilter(null, noTags))
        assertTrue(DuplicateDetectionViewModel.matchesTagFilter(emptyList(), noTags))
        assertFalse(DuplicateDetectionViewModel.matchesTagFilter(listOf("Action"), noTags))

        val hasTags = noTags.copy(filterNoTags = TriState.ENABLED_NOT)
        assertFalse(DuplicateDetectionViewModel.matchesTagFilter(null, hasTags))
        assertTrue(DuplicateDetectionViewModel.matchesTagFilter(listOf("Action"), hasTags))
    }

    // Excluded extensions

    @Test
    fun `excluded extensions remove entries from that source only`() {
        val groups = mapOf(
            "dup" to listOf(
                entry(id = 1, source = 10),
                entry(id = 2, source = 20),
            ),
        )
        val snapshot = DuplicateDetectionViewModel.LibraryFilterSnapshot(excludedExtensions = setOf(10L))
        val filtered = baseState(
            groups,
            listingMode = true,
            applyLibraryFilters = true,
            libraryFilterSnapshot = snapshot,
        )
            .computeFilteredGroups()
        assertEquals(listOf(2L), filtered["dup"]?.map { it.manga.id })
    }

    // Unread / started / completed / chapter-count status filters

    @Test
    fun `unread and started filters use chapter and read counts`() {
        val groups = mapOf(
            "dup" to listOf(
                entry(id = 1, chapterCount = 10, readCount = 0), // unread, not started
                entry(id = 2, chapterCount = 10, readCount = 10), // read, started
            ),
        )

        val unreadOnly = DuplicateDetectionViewModel.LibraryFilterSnapshot(filterUnread = TriState.ENABLED_IS)
        assertEquals(
            listOf(1L),
            baseState(groups, listingMode = true, applyLibraryFilters = true, libraryFilterSnapshot = unreadOnly)
                .computeFilteredGroups()["dup"]?.map { it.manga.id },
        )

        val startedOnly = DuplicateDetectionViewModel.LibraryFilterSnapshot(filterStarted = TriState.ENABLED_IS)
        assertEquals(
            listOf(2L),
            baseState(groups, listingMode = true, applyLibraryFilters = true, libraryFilterSnapshot = startedOnly)
                .computeFilteredGroups()["dup"]?.map { it.manga.id },
        )

        val notStarted = DuplicateDetectionViewModel.LibraryFilterSnapshot(filterStarted = TriState.ENABLED_NOT)
        assertEquals(
            listOf(1L),
            baseState(groups, listingMode = true, applyLibraryFilters = true, libraryFilterSnapshot = notStarted)
                .computeFilteredGroups()["dup"]?.map { it.manga.id },
        )
    }

    @Test
    fun `completed filter uses manga status, chapter-count filter uses threshold`() {
        val groups = mapOf(
            "dup" to listOf(
                entry(id = 1, status = SManga.COMPLETED.toLong(), chapterCount = 3),
                entry(id = 2, status = SManga.ONGOING.toLong(), chapterCount = 30),
            ),
        )

        val completedOnly = DuplicateDetectionViewModel.LibraryFilterSnapshot(filterCompleted = TriState.ENABLED_IS)
        assertEquals(
            listOf(1L),
            baseState(groups, listingMode = true, applyLibraryFilters = true, libraryFilterSnapshot = completedOnly)
                .computeFilteredGroups()["dup"]?.map { it.manga.id },
        )

        val minChapters = DuplicateDetectionViewModel.LibraryFilterSnapshot(
            filterChapterCount = TriState.ENABLED_IS,
            filterChapterCountThreshold = 10,
        )
        assertEquals(
            listOf(2L),
            baseState(groups, listingMode = true, applyLibraryFilters = true, libraryFilterSnapshot = minChapters)
                .computeFilteredGroups()["dup"]?.map { it.manga.id },
        )

        val belowThreshold = minChapters.copy(filterChapterCount = TriState.ENABLED_NOT)
        assertEquals(
            listOf(1L),
            baseState(groups, listingMode = true, applyLibraryFilters = true, libraryFilterSnapshot = belowThreshold)
                .computeFilteredGroups()["dup"]?.map { it.manga.id },
        )
    }

    @Test
    fun `combined include and exclude extension plus status filters can empty a group`() {
        val groups = mapOf(
            "dup" to listOf(
                entry(id = 1, source = 10, genre = listOf("Spoiler"), chapterCount = 5, readCount = 0),
                entry(id = 2, source = 20, genre = listOf("Action"), chapterCount = 5, readCount = 5),
            ),
        )
        val snapshot = DuplicateDetectionViewModel.LibraryFilterSnapshot(
            excludedExtensions = setOf(10L),
            excludedTags = setOf("action"),
        )
        // Entry 1 dropped by excluded extension, entry 2 dropped by excluded tag -> group empties out.
        val filtered = baseState(
            groups,
            applyLibraryFilters = true,
            libraryFilterSnapshot = snapshot,
        ).computeFilteredGroups()
        assertTrue(filtered.isEmpty())
    }

    // Category include/exclude, ANY vs ALL, strict vs flexible group matching

    @Test
    fun `strict group category match requires every member to pass`() {
        val groups = mapOf(
            "dup" to listOf(entry(id = 1), entry(id = 2)),
        )
        val categoryIdSets = mapOf(1L to setOf(1L, 2L), 2L to setOf(2L, 3L))

        val strict = baseState(
            groups,
            selectedCategoryFilters = setOf(1L),
            filterByGroupCategory = false,
            mangaCategoryIdSets = categoryIdSets,
        )
        assertTrue(
            strict.computeFilteredGroups().isEmpty(),
            "member 2 lacks category 1, strict mode drops the whole group",
        )

        val flexible = baseState(
            groups,
            selectedCategoryFilters = setOf(1L),
            filterByGroupCategory = true,
            mangaCategoryIdSets = categoryIdSets,
        )
        assertEquals(
            2,
            flexible.computeFilteredGroups()["dup"]?.size,
            "flexible mode keeps the group if any member matches",
        )
    }

    @Test
    fun `category ALL include mode requires every selected category on the same entry`() {
        val groups = mapOf(
            "dup" to listOf(entry(id = 1), entry(id = 2)),
        )
        val categoryIdSets = mapOf(1L to setOf(1L, 2L), 2L to setOf(2L, 3L))

        val allMode = baseState(
            groups,
            selectedCategoryFilters = setOf(1L, 3L),
            categoryIncludeMode = DuplicateDetectionViewModel.CategoryIncludeMode.ALL,
            filterByGroupCategory = false,
            mangaCategoryIdSets = categoryIdSets,
        )
        assertTrue(allMode.computeFilteredGroups().isEmpty(), "neither entry has both category 1 and 3")
    }

    @Test
    fun `excluded category drops entries carrying it regardless of include mode`() {
        val groups = mapOf(
            "dup" to listOf(entry(id = 1), entry(id = 2)),
        )
        val categoryIdSets = mapOf(1L to setOf(2L), 2L to setOf(2L, 3L))

        val strict = baseState(
            groups,
            excludedCategoryFilters = setOf(2L),
            filterByGroupCategory = false,
            mangaCategoryIdSets = categoryIdSets,
        )
        assertTrue(strict.computeFilteredGroups().isEmpty(), "both entries carry the excluded category")
    }

    // Disabled filters are a no-op

    @Test
    fun `disabled library filters do not change the result`() {
        val groups = mapOf(
            "dup" to listOf(entry(id = 1, genre = listOf("Anything"), source = 999), entry(id = 2)),
        )
        val unfiltered = baseState(groups).computeFilteredGroups()
        val filtersOffButEnabled = baseState(
            groups,
            applyLibraryFilters = true,
            libraryFilterSnapshot = DuplicateDetectionViewModel.LibraryFilterSnapshot(),
        ).computeFilteredGroups()
        assertEquals(
            unfiltered.mapValues {
                it.value.map { m -> m.manga.id }
            },
            filtersOffButEnabled.mapValues { it.value.map { m -> m.manga.id } },
        )
    }
}
