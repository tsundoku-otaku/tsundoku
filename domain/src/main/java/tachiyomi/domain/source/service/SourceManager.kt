package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.download.service.RateLimitCandidate
import tachiyomi.domain.source.model.StubSource

interface SourceManager {

    val isInitialized: StateFlow<Boolean>

    val sources: Flow<List<Source>>

    fun get(sourceKey: Long): Source?

    fun getOrStub(sourceKey: Long): Source

    fun getAll(): List<Source>

    fun getOnlineSources(): List<HttpSource>

    /**
     * Every installed source that exposes a base URL and can therefore be paced by
     * [tachiyomi.domain.download.service.SourceRateLimitPolicy] - a superset of
     * [getOnlineSources], since non-[HttpSource] source types (e.g. JS-plugin sources) can
     * still need per-host request pacing.
     */
    fun getRateLimitCandidates(): List<RateLimitCandidate>

    fun getStubSources(): List<StubSource>
}
