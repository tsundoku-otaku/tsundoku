package eu.kanade.tachiyomi.data.massimport

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/**
 * Detects whether a pasted URL matches a deeplink pattern the source's installed extension APK
 * declares - the same PackageManager mechanism Android itself uses to decide "open in app". A
 * pure manifest pattern match, no network request.
 */
interface DeeplinkResolver {
    fun isDeeplinkUrl(source: CatalogueSource, url: String): Boolean
}

// Construct one instance per import batch/preview and let it die with it: an app-wide singleton
// would go stale across extension install/update/uninstall without extra invalidation plumbing,
// while PackageManager queries are cheap enough that batch-scoped caching is all that's needed.
//
// The two lookups are injectable (defaulting to the real ExtensionManager/PackageManager) so the
// caching behavior can be verified with plain JVM fakes instead of Robolectric PackageManager
// shadows.
class PackageManagerDeeplinkResolver(
    // Defaults are lambdas, not eagerly-evaluated Injekt.get() calls, so passing only the other
    // param in tests never touches Injekt/Application.
    private val lookupPkgName: (sourceId: Long) -> String? = { sourceId ->
        Injekt.get<ExtensionManager>().getExtensionPackage(sourceId)
    },
    private val queryHostSupported: (pkgName: String, url: String) -> Boolean = { pkgName, url ->
        runCatching {
            Injekt.get<Application>().packageManager
                .queryIntentActivities(Intent(Intent.ACTION_VIEW, url.toUri()), PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.packageName == pkgName }
        }.getOrDefault(false)
    },
) : DeeplinkResolver {

    // ConcurrentHashMap forbids null values, so a source with no resolvable package is cached as
    // NO_PACKAGE rather than null.
    private val pkgNameCache = ConcurrentHashMap<Long, String>()

    // (pkgName, host) -> supported. Deeplink path patterns are near-always wildcarded, so caching
    // by host collapses a paste of thousands of same-host URLs into one PackageManager call.
    private val deeplinkCache = ConcurrentHashMap<Pair<String, String>, Boolean>()

    override fun isDeeplinkUrl(source: CatalogueSource, url: String): Boolean {
        val pkgName = pkgNameCache.getOrPut(source.id) { lookupPkgName(source.id) ?: NO_PACKAGE }
        if (pkgName == NO_PACKAGE) return false

        // java.net.URI, not android.net.Uri: keeps this cache-key path plain-JVM testable.
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return deeplinkCache.getOrPut(pkgName to host) { queryHostSupported(pkgName, url) }
    }

    private companion object {
        const val NO_PACKAGE = ""
    }
}

/**
 * If [url] matches a deeplink pattern the source declares, resolves it to the canonical [SManga]
 * via the extension's own URL-sniffing (KeiSource `getMangaByUrl`, or legacy manual sniffing
 * inside `fetchSearchManga`) - the same in-process call `GlobalSearchScreen`/`UrlActivity` make.
 * One request; returns null for a non-deeplink URL, an empty search result, a thrown exception,
 * or a timeout, so the caller can fall back to the guessed path.
 */
suspend fun resolveDeeplinkManga(
    source: CatalogueSource,
    url: String,
    deeplinkResolver: DeeplinkResolver,
    timeoutMs: Long,
): SManga? {
    if (!deeplinkResolver.isDeeplinkUrl(source, url)) return null
    return withTimeoutOrNull(timeoutMs) {
        runCatching { source.getSearchManga(1, url, source.getFilterList()).mangas.firstOrNull() }.getOrNull()
    }
}
