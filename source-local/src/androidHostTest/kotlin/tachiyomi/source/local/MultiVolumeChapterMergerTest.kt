package tachiyomi.source.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Collections.emptyList

class MultiVolumeChapterMergerTest {

    private data class FakeChapter(
        val name: String,
        val volumeKey: String?,
        val order: Int,
    )

    private fun regroup(chapters: List<FakeChapter>): List<FakeChapter> {
        return groupChaptersByVolume(
            chapters = chapters,
            volumeKeyOf = { it.volumeKey },
            orderOf = { it.order },
            withOrder = { chapter, newOrder -> chapter.copy(order = newOrder) },
        )
    }

    @Test
    fun `regroup keeps single-volume chapters in their original order`() {
        val chapters = listOf(
            FakeChapter("A", "novel/vol1.epub", 0),
            FakeChapter("B", "novel/vol1.epub", 1),
            FakeChapter("C", "novel/vol1.epub", 2),
        )

        val result = regroup(chapters)

        assertEquals(listOf("A", "B", "C"), result.map { it.name })
        assertEquals(listOf(0, 1, 2), result.map { it.order })
    }

    @Test
    fun `regroup splits interleaved volumes into contiguous blocks`() {
        val chapters = listOf(
            FakeChapter("vol1-ch1", "novel/001 - vol1.epub", 0),
            FakeChapter("vol2-ch1", "novel/002 - vol2.epub", 1),
            FakeChapter("vol3-ch1", "novel/003 - vol3.epub", 2),
            FakeChapter("vol1-ch2", "novel/001 - vol1.epub", 3),
            FakeChapter("vol2-ch2", "novel/002 - vol2.epub", 4),
            FakeChapter("vol3-ch2", "novel/003 - vol3.epub", 5),
            FakeChapter("vol1-ch3", "novel/001 - vol1.epub", 6),
            FakeChapter("vol2-ch3", "novel/002 - vol2.epub", 7),
            FakeChapter("vol3-ch3", "novel/003 - vol3.epub", 8),
        )

        val result = regroup(chapters)

        assertEquals(
            listOf(
                "vol1-ch1", "vol1-ch2", "vol1-ch3",
                "vol2-ch1", "vol2-ch2", "vol2-ch3",
                "vol3-ch1", "vol3-ch2", "vol3-ch3",
            ),
            result.map { it.name },
        )
        assertEquals((0..8).toList(), result.map { it.order })
    }

    @Test
    fun `regroup emits volumes in natural-order regardless of input order`() {
        val chapters = listOf(
            FakeChapter("vol3-ch1", "novel/003 - vol3.epub", 0),
            FakeChapter("vol2-ch1", "novel/002 - vol2.epub", 1),
            FakeChapter("vol1-ch1", "novel/001 - vol1.epub", 2),
        )

        val result = regroup(chapters)

        assertEquals(
            listOf("vol1-ch1", "vol2-ch1", "vol3-ch1"),
            result.map { it.name },
        )
    }

    @Test
    fun `regroup uses natural numeric ordering for unpadded volume names`() {
        val chapters = listOf(
            FakeChapter("c2", "novel/vol2.epub", 0),
            FakeChapter("c10", "novel/vol10.epub", 1),
            FakeChapter("c1", "novel/vol1.epub", 2),
        )

        val result = regroup(chapters)

        assertEquals(listOf("c1", "c2", "c10"), result.map { it.name })
    }

    @Test
    fun `regroup preserves within-volume order across interleaved blocks`() {
        val chapters = listOf(
            FakeChapter("v1-c4", "vol1", 30),
            FakeChapter("v2-c1", "vol2", 5),
            FakeChapter("v1-c1", "vol1", 1),
            FakeChapter("v2-c2", "vol2", 6),
            FakeChapter("v1-c3", "vol1", 20),
            FakeChapter("v1-c2", "vol1", 10),
        )

        val result = regroup(chapters)

        assertEquals(
            listOf("v1-c1", "v1-c2", "v1-c3", "v1-c4", "v2-c1", "v2-c2"),
            result.map { it.name },
        )
    }

    @Test
    fun `regroup appends chapters with null volumeKey after grouped chapters`() {
        val chapters = listOf(
            FakeChapter("orphan-a", null, 0),
            FakeChapter("v1-c1", "vol1", 1),
            FakeChapter("orphan-b", null, 2),
            FakeChapter("v1-c2", "vol1", 3),
        )

        val result = regroup(chapters)

        assertEquals(
            listOf("v1-c1", "v1-c2", "orphan-a", "orphan-b"),
            result.map { it.name },
        )
        assertEquals((0..3).toList(), result.map { it.order })
    }

    @Test
    fun `regroup returns input unchanged when no chapters have volumeKey`() {
        val chapters = listOf(
            FakeChapter("a", null, 0),
            FakeChapter("b", null, 1),
        )

        val result = regroup(chapters)

        assertEquals(chapters, result)
    }

    @Test
    fun `regroup returns empty list for empty input`() {
        assertEquals(emptyList<FakeChapter>(), regroup(emptyList()))
    }

    @Test
    fun `merger output supports both join and split volume export modes`() {
        val chapters = listOf(
            FakeChapter("vol1-ch1", "novel/001 - vol1.epub", 0),
            FakeChapter("vol2-ch1", "novel/002 - vol2.epub", 1),
            FakeChapter("vol3-ch1", "novel/003 - vol3.epub", 2),
            FakeChapter("vol1-ch2", "novel/001 - vol1.epub", 3),
            FakeChapter("vol2-ch2", "novel/002 - vol2.epub", 4),
            FakeChapter("vol3-ch2", "novel/003 - vol3.epub", 5),
        )

        val merged = regroup(chapters)

        val joined = merged
        assertEquals(
            listOf("vol1-ch1", "vol1-ch2", "vol2-ch1", "vol2-ch2", "vol3-ch1", "vol3-ch2"),
            joined.map { it.name },
        )

        val splitByVolume = merged.groupBy { it.volumeKey }
        assertEquals(3, splitByVolume.size)
        assertEquals(
            listOf("vol1-ch1", "vol1-ch2"),
            splitByVolume["novel/001 - vol1.epub"]?.map { it.name },
        )
        assertEquals(
            listOf("vol2-ch1", "vol2-ch2"),
            splitByVolume["novel/002 - vol2.epub"]?.map { it.name },
        )
        assertEquals(
            listOf("vol3-ch1", "vol3-ch2"),
            splitByVolume["novel/003 - vol3.epub"]?.map { it.name },
        )
    }
}
