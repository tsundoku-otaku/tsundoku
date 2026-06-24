package eu.kanade.tachiyomi.source.custom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class CustomSourceManagerTest {

    private fun config(
        name: String = "Example",
        baseUrl: String = "https://example.com",
        id: Long? = 1L,
        popularUrl: String = "https://example.com/popular/{page}",
        latestUrl: String? = null,
        searchUrl: String = "https://example.com/?s={query}",
        popularList: String = ".novel-item",
        latestList: String? = null,
        searchList: String? = null,
        detailsTitle: String = "h1.title",
        chaptersList: String = ".chapter-list li",
        chaptersPattern: String? = null,
        contentPrimary: String = ".chapter-content",
        basedOnSourceId: Long? = null,
    ) = CustomSourceConfig(
        name = name,
        baseUrl = baseUrl,
        id = id,
        popularUrl = popularUrl,
        latestUrl = latestUrl,
        searchUrl = searchUrl,
        basedOnSourceId = basedOnSourceId,
        selectors = SourceSelectors(
            popular = MangaListSelectors(list = popularList),
            latest = latestList?.let { MangaListSelectors(list = it) },
            search = searchList?.let { MangaListSelectors(list = it) },
            details = DetailSelectors(title = detailsTitle),
            chapters = ChapterSelectors(list = chaptersList, urlPattern = chaptersPattern),
            content = ContentSelectors(primary = contentPrimary),
        ),
    )

    @Test
    fun `valid full config has no errors`() {
        assertTrue(customSourceValidationErrors(config()).isEmpty())
    }

    @Test
    fun `latest-only config does not require popular or search url`() {
        // Popular and search blank, only latest set: previously this wrongly failed with
        // "Popular URL is required" / "Search URL is required".
        val errors = customSourceValidationErrors(
            config(
                popularUrl = "",
                searchUrl = "",
                latestUrl = "https://example.com/latest/{page}",
                latestList = ".novel-item",
            ),
        )
        assertTrue(errors.isEmpty(), "Unexpected errors: $errors")
    }

    @Test
    fun `config with no listing url fails`() {
        val errors = customSourceValidationErrors(config(popularUrl = "", searchUrl = "", latestUrl = null))
        assertTrue(errors.any { it.contains("listing URL") })
    }

    @Test
    fun `chapter url pattern satisfies chapter requirement without list selector`() {
        val errors = customSourceValidationErrors(
            config(chaptersList = "", chaptersPattern = "{novelUrl}/chapter-{n}"),
        )
        assertTrue(errors.isEmpty(), "Unexpected errors: $errors")
    }

    @Test
    fun `missing chapter list and pattern fails`() {
        val errors = customSourceValidationErrors(config(chaptersList = "", chaptersPattern = null))
        assertTrue(errors.any { it.contains("chapter") })
    }

    @Test
    fun `extension-based config skips selector requirements`() {
        val errors = customSourceValidationErrors(
            config(
                popularUrl = "",
                searchUrl = "",
                popularList = "",
                detailsTitle = "",
                chaptersList = "",
                contentPrimary = "",
                basedOnSourceId = 999L,
            ),
        )
        assertTrue(errors.isEmpty(), "Unexpected errors: $errors")
    }

    @Test
    fun `duplicate name and base url are detected against existing`() {
        val existing = listOf(config(name = "Dup", baseUrl = "https://dup.com", id = 1L))
        val incoming = config(name = "Dup", baseUrl = "https://dup.com", id = 2L)
        val errors = customSourceValidationErrors(incoming, existing)
        assertTrue(errors.any { it.contains("name already exists") })
        assertTrue(errors.any { it.contains("base URL already exists") })
    }

    @Test
    fun `same id is not treated as duplicate of itself`() {
        val existing = listOf(config(name = "Self", baseUrl = "https://self.com", id = 7L))
        val incoming = config(name = "Self", baseUrl = "https://self.com", id = 7L)
        assertFalse(customSourceValidationErrors(incoming, existing).any { it.contains("already exists") })
    }

    private val json = kotlinx.serialization.json.Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `blank template passes validation`() {
        assertTrue(customSourceValidationErrors(customSourceBlankTemplate()).isEmpty())
    }

    @Test
    fun `blank template json round-trips`() {
        val encoded = json.encodeToString(
            CustomSourceConfig.serializer(),
            customSourceBlankTemplate(),
        )
        val decoded = json.decodeFromString(CustomSourceConfig.serializer(), encoded)
        assertEquals(customSourceBlankTemplate(), decoded)
        // Removed fields must not appear in the exported template.
        assertFalse(encoded.contains("useCloudflare"))
        assertFalse(encoded.contains("sourceType"))
        assertFalse(encoded.contains("chapterAjax"))
        assertFalse(encoded.contains("novelId"))
    }

    @Test
    fun `stable id is derived from name and base url`() {
        val firstId = stableCustomSourceId("Custom Source", "https://example.com")
        val secondId = stableCustomSourceId("Custom Source", "https://example.com")
        val differentId = stableCustomSourceId("Custom Source", "https://other.example")

        assertEquals(firstId, secondId)
        assertTrue(firstId > 0)
        assertTrue(firstId != differentId)
    }

    @Test
    fun `storage helper returns stable and legacy file names`() {
        val tempDir = Files.createTempDirectory("custom-source-manager-test").toFile()

        try {
            val candidates = customSourceStorageFileCandidates(tempDir, 123L, "My Custom Source")

            assertEquals(
                listOf(
                    File(tempDir, "123.json"),
                    File(tempDir, "My_Custom_Source.json"),
                ),
                candidates,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
