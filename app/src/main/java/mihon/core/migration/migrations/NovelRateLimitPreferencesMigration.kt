package mihon.core.migration.migrations

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.NovelDownloadPreferences
import tachiyomi.domain.download.service.NovelDownloadPreferences.Companion.SourceOverride

/**
 * The per-request rate-limiting rework (RateLimiting branch) renamed/consolidated several
 * [NovelDownloadPreferences] keys:
 * - novel_download_throttling_enabled -> novel_request_throttling_enabled
 * - novel_download_delay_ms -> novel_request_delay_ms
 * - novel_source_overrides -> novel_source_overrides_v2 (shape changed too)
 *
 * Without this migration those old values are silently abandoned and every upgrading user's
 * customized throttling settings and per-source overrides quietly reset to defaults. Carries the
 * old values forward on a best-effort basis: the per-job update/mass-import delay fields have no
 * direct equivalent in the new unified per-request model and are intentionally not migrated.
 */
class NovelRateLimitPreferencesMigration : Migration {
    override val version: Float = 22f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val preferenceStore = migrationContext.get<PreferenceStore>() ?: return false
        val novelDownloadPreferences = migrationContext.get<NovelDownloadPreferences>() ?: return false

        val oldEnabled = preferenceStore.getBoolean("novel_download_throttling_enabled", true)
        if (oldEnabled.isSet()) {
            novelDownloadPreferences.enableRequestThrottling().set(oldEnabled.get())
        }

        val oldDelay = preferenceStore.getInt("novel_download_delay_ms", 3000)
        if (oldDelay.isSet()) {
            novelDownloadPreferences.requestDelay().set(oldDelay.get())
        }

        val oldOverridesJson = preferenceStore.getString("novel_source_overrides", "{}")
        if (oldOverridesJson.isSet() && oldOverridesJson.get().let { it.isNotEmpty() && it != "{}" }) {
            val rawJson = oldOverridesJson.get()
            val rootObject = runCatching { Json.parseToJsonElement(rawJson).jsonObject }.getOrNull()

            if (rootObject == null) {
                logcat(LogPriority.ERROR) {
                    "NovelRateLimitPreferencesMigration: novel_source_overrides isn't valid JSON, " +
                        "skipping override migration entirely"
                }
            } else {
                // Decode entry-by-entry rather than the whole map at once: one malformed entry
                // (e.g. written by a build with a slightly different shape) shouldn't cost the
                // user every other correctly-formed override in a one-time, unrepeatable migration.
                rootObject.forEach { (sourceIdKey, element) ->
                    val legacy = runCatching { Json.decodeFromJsonElement<LegacySourceOverride>(element) }
                        .onFailure {
                            logcat(LogPriority.ERROR) {
                                "NovelRateLimitPreferencesMigration: skipping unparsable override " +
                                    "for source $sourceIdKey: ${it.message}"
                            }
                        }
                        .getOrNull() ?: return@forEach

                    novelDownloadPreferences.setSourceOverride(
                        SourceOverride(
                            sourceId = legacy.sourceId,
                            delayMillis = legacy.downloadDelay,
                            jitterMillis = legacy.randomDelayRange,
                            permits = null,
                            enabled = legacy.enabled,
                        ),
                    )
                }
            }
        }

        return true
    }

    @Serializable
    private data class LegacySourceOverride(
        val sourceId: Long,
        val downloadDelay: Int? = null,
        val randomDelayRange: Int? = null,
        val updateDelay: Int? = null,
        val massImportDelay: Int? = null,
        val enabled: Boolean = true,
    )
}
