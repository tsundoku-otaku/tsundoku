package eu.kanade.tachiyomi.ui.category

import dev.icerock.moko.resources.StringResource
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.i18n.novel.TDMR

fun SetMangaCategories.UpdateResult.failureMessage(): StringResource? = when (this) {
    SetMangaCategories.UpdateResult.Saved -> null
    SetMangaCategories.UpdateResult.Partial -> TDMR.strings.duplicate_categories_partial
    SetMangaCategories.UpdateResult.Failed -> TDMR.strings.duplicate_categories_failed
}
