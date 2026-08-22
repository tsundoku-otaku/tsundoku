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


class PackageManagerDeeplinkResolver(

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

    private val pkgNameCache = ConcurrentHashMap<Long, String>()

    private val deeplinkCache = ConcurrentHashMap<Pair<String, String>, Boolean>()

    override fun isDeeplinkUrl(source: CatalogueSource, url: String): Boolean {
        val pkgName = pkgNameCache.getOrPut(source.id) { lookupPkgName(source.id) ?: NO_PACKAGE }
        if (pkgName == NO_PACKAGE) return false

        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return deeplinkCache.getOrPut(pkgName to host) { queryHostSupported(pkgName, url) }
    }

    private companion object {
        const val NO_PACKAGE = ""
    }
}

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
