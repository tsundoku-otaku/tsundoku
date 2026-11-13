package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import kotlinx.coroutines.flow.MutableStateFlow
import tachiyomi.core.common.util.system.logcat

data class ReaderChapter(val chapter: Chapter) {

    val stateFlow = MutableStateFlow<State>(State.Wait)
    var state: State
        get() = stateFlow.value
        set(value) {
            stateFlow.value = value
        }

    val pages: List<ReaderPage>?
        get() = (state as? State.Loaded)?.pages

    var pageLoader: PageLoader? = null

    var requestedPage: Int = 0

    private var references = 0

    private val retiredPageLoaders = mutableListOf<PageLoader>()

    constructor(chapter: tachiyomi.domain.chapter.model.Chapter) : this(chapter.toDbChapter())

    fun ref() {
        references++
    }

    fun unref() {
        references--
        if (references == 0) {
            if (pageLoader != null) {
                logcat { "Recycling chapter ${chapter.name}" }
            }
            pageLoader?.recycle()
            pageLoader = null
            recycleRetiredPageLoaders()
            state = State.Wait
        }
    }

    /**
     * Recycles a [loader] replaced by a reload once this chapter is unreferenced, since a viewer
     * that kept the old content on screen may still read from it.
     */
    fun retirePageLoader(loader: PageLoader) {
        if (loader === pageLoader || loader.isRecycled || loader in retiredPageLoaders) return
        retiredPageLoaders += loader
        if (references <= 0) recycleRetiredPageLoaders()
    }

    private fun recycleRetiredPageLoaders() {
        retiredPageLoaders.forEach { if (!it.isRecycled) it.recycle() }
        retiredPageLoaders.clear()
    }

    sealed interface State {
        data object Wait : State
        data object Loading : State
        data class Error(val error: Throwable) : State
        data class Loaded(val pages: List<ReaderPage>) : State
    }
}
