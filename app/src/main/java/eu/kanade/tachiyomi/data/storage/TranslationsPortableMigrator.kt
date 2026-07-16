package eu.kanade.tachiyomi.data.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.translation.model.TranslatedChapter
import tachiyomi.domain.translation.model.TranslationLocator
import tachiyomi.domain.translation.repository.TranslatedChapterRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Converts translations from the legacy flat, id-keyed layout
 * (translations/{chapterId}_{lang}.html) to the portable source/novel/language/chapter
 * layout. Invoked manually from Advanced settings; idempotent (only touches flat files).
 */
object TranslationsPortableMigrator {
    private val flatFileRegex = Regex("^(\\d+)_(.+?)\\.html(\\.tmp)?$")
    private val metaRegex = Regex("^<!-- tsundoku-meta:(.+?):(-?\\d+)(?::[a-f0-9]{64})? -->\\n?")

    suspend fun run(): Int = withContext(Dispatchers.IO) {
        val storageManager = Injekt.get<StorageManager>()
        val translatedChapterRepository = Injekt.get<TranslatedChapterRepository>()
        val chapterRepository = Injekt.get<ChapterRepository>()
        val mangaRepository = Injekt.get<MangaRepository>()
        val sourceManager = Injekt.get<SourceManager>()

        val translationsDir = storageManager.getTranslationsDirectory() ?: return@withContext 0

        val flatFiles = translationsDir.listFiles()
            ?.filter { !it.isDirectory && flatFileRegex.matches(it.name.orEmpty()) }
            .orEmpty()

        var migrated = 0
        for (file in flatFiles) {
            val name = file.name ?: continue
            val match = flatFileRegex.matchEntire(name) ?: continue
            val chapterId = match.groupValues[1].toLongOrNull() ?: continue
            val lang = match.groupValues[2]
            val isTmp = match.groupValues[3].isNotEmpty()
            try {
                val chapter = chapterRepository.getChapterById(chapterId)
                if (chapter == null) {
                    // Chapter no longer exists, so the translation can't be relocated; drop it.
                    file.delete()
                    continue
                }
                val manga = mangaRepository.getMangaByIdOrNull(chapter.mangaId) ?: continue
                val locator = TranslationLocator(
                    sourceName = sourceManager.getOrStub(manga.source).toString(),
                    novelTitle = manga.title,
                    chapterName = chapter.name,
                    chapterUrl = chapter.url,
                )

                val raw = file.openInputStream().bufferedReader().use { it.readText() }
                val metaMatch = metaRegex.find(raw)
                val engineId = metaMatch?.groupValues?.get(1) ?: "unknown"
                val date = metaMatch?.groupValues?.get(2)?.toLongOrNull() ?: 0L
                val content = if (metaMatch != null) raw.removeRange(metaMatch.range).trimStart('\n') else raw

                val translated = TranslatedChapter(
                    chapterId = 0,
                    mangaId = 0,
                    targetLanguage = lang,
                    engineId = engineId,
                    translatedContent = content,
                    dateTranslated = date,
                )
                if (isTmp) {
                    translatedChapterRepository.upsertTmpTranslation(locator, translated)
                } else {
                    translatedChapterRepository.upsertTranslation(locator, translated)
                }
                if (!file.delete()) {
                    logcat(LogPriority.WARN) { "Migrated $name but could not remove the legacy file" }
                }
                migrated++
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to migrate translation file $name" }
            }
        }
        migrated
    }
}
