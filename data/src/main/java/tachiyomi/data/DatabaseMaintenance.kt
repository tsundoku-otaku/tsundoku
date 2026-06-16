package tachiyomi.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Database maintenance operations that run raw PRAGMA/VACUUM statements directly on the driver.
 */
class DatabaseMaintenance(
    private val driver: SqlDriver,
) {

    /**
     * Reconcile the live schema with columns/tables that should exist but may be missing on DBs
     * left inconsistent by the mihon-merge migration renumbering (extension_store and the
     * TachiyomiX `memo` columns could be skipped). Idempotent and pragma-guarded so clean and
     * fresh DBs are untouched. Runs synchronously right after the driver is created, before any
     * generated query references these columns.
     */
    fun reconcileSchema() {
        ensureColumn("mangas", "memo", "BLOB NOT NULL DEFAULT '{}'")
        ensureColumn("chapters", "memo", "BLOB NOT NULL DEFAULT '{}'")
        driver.execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS extension_store(
                index_url TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                badge_label TEXT NOT NULL,
                signing_key TEXT NOT NULL,
                contact_website TEXT NOT NULL,
                contact_discord TEXT,
                is_legacy INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
            null,
        )
    }

    private fun columnExists(table: String, column: String): Boolean {
        var exists = false
        driver.executeQuery(null, "PRAGMA table_info($table)", { cursor ->
            while (cursor.next().value) {
                if (cursor.getString(1) == column) exists = true
            }
            QueryResult.Unit
        }, 0, null)
        return exists
    }

    private fun ensureColumn(table: String, column: String, definition: String) {
        if (!columnExists(table, column)) {
            driver.execute(null, "ALTER TABLE $table ADD COLUMN $column $definition", 0, null)
        }
    }

    /** Rebuild the database file, reclaiming unused space. Truncates WAL afterwards. */
    suspend fun vacuum() {
        withContext(Dispatchers.IO) {
            driver.execute(null, "VACUUM", 0, null)
            driver.execute(null, "PRAGMA wal_checkpoint(TRUNCATE)", 0, null)
        }
    }

    /** Rebuild all indexes. */
    suspend fun reindex() {
        withContext(Dispatchers.IO) {
            driver.execute(null, "REINDEX", 0, null)
        }
    }

    /** Update query-planner statistics. */
    suspend fun analyze() {
        withContext(Dispatchers.IO) {
            driver.execute(null, "ANALYZE", 0, null)
        }
    }

    /** Page size, page count, freelist count, total size. */
    suspend fun getDatabaseStats(): Map<String, Long> {
        return withContext(Dispatchers.IO) {
            val stats = mutableMapOf<String, Long>()

            driver.executeQuery(null, "PRAGMA page_size", { cursor ->
                if (cursor.next().value) stats["page_size"] = cursor.getLong(0) ?: 4096L
                QueryResult.Unit
            }, 0, null)

            driver.executeQuery(null, "PRAGMA page_count", { cursor ->
                if (cursor.next().value) stats["page_count"] = cursor.getLong(0) ?: 0L
                QueryResult.Unit
            }, 0, null)

            driver.executeQuery(null, "PRAGMA freelist_count", { cursor ->
                if (cursor.next().value) stats["freelist_count"] = cursor.getLong(0) ?: 0L
                QueryResult.Unit
            }, 0, null)

            val pageSize = stats["page_size"] ?: 4096L
            val pageCount = stats["page_count"] ?: 0L
            stats["total_size_bytes"] = pageSize * pageCount
            stats
        }
    }

    /** Detailed stats: per-table/index sizes (via dbstat), WAL info, average text sizes. */
    suspend fun getDetailedDatabaseStats(): Map<String, Any> {
        return withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, Any>()
            var actualPageSize = 4096L

            driver.executeQuery(null, "PRAGMA page_size", { cursor ->
                if (cursor.next().value) {
                    actualPageSize = cursor.getLong(0) ?: 4096L
                    result["page_size"] = actualPageSize
                }
                QueryResult.Unit
            }, 0, null)

            driver.executeQuery(null, "PRAGMA page_count", { cursor ->
                if (cursor.next().value) {
                    val count = cursor.getLong(0) ?: 0L
                    result["page_count"] = count
                    result["total_size_bytes"] = actualPageSize * count
                }
                QueryResult.Unit
            }, 0, null)

            driver.executeQuery(null, "PRAGMA freelist_count", { cursor ->
                if (cursor.next().value) {
                    val count = cursor.getLong(0) ?: 0L
                    result["freelist_count"] = count
                    result["freelist_size_bytes"] = actualPageSize * count
                }
                QueryResult.Unit
            }, 0, null)

            val tableCounts = mutableMapOf<String, Long>()
            listOf(
                "chapters", "mangas", "history", "library_cache", "categories",
                "mangas_categories", "manga_sync", "excluded_scanlators",
            ).forEach { table ->
                try {
                    driver.executeQuery(null, "SELECT count(*) FROM $table", { cursor ->
                        if (cursor.next().value) tableCounts[table] = cursor.getLong(0) ?: 0L
                        QueryResult.Unit
                    }, 0, null)
                } catch (e: Exception) {
                    tableCounts[table] = -1L
                }
            }
            result["table_row_counts"] = tableCounts

            val tableSizes = mutableMapOf<String, Long>()
            val indexSizes = mutableMapOf<String, Long>()
            try {
                driver.executeQuery(
                    null,
                    "SELECT name, SUM(pgsize) as size FROM dbstat GROUP BY name ORDER BY size DESC",
                    { cursor ->
                        while (cursor.next().value) {
                            val name = cursor.getString(0) ?: continue
                            val size = cursor.getLong(1) ?: 0L
                            if (name.contains("_index") || name.startsWith("sqlite_autoindex")) {
                                indexSizes[name] = size
                            } else {
                                tableSizes[name] = size
                            }
                        }
                        QueryResult.Unit
                    },
                    0,
                    null,
                )
                result["table_sizes_bytes"] = tableSizes
                result["index_sizes_bytes"] = indexSizes
            } catch (e: Exception) {
                result["dbstat_available"] = false
            }

            driver.executeQuery(null, "PRAGMA wal_checkpoint", { cursor ->
                if (cursor.next().value) {
                    result["wal_frames_total"] = cursor.getLong(1) ?: 0L
                    result["wal_frames_checkpointed"] = cursor.getLong(2) ?: 0L
                }
                QueryResult.Unit
            }, 0, null)

            val chapterCount = tableCounts["chapters"] ?: 0L
            if (chapterCount > 0) {
                driver.executeQuery(
                    null,
                    "SELECT AVG(length(url) + length(name) + coalesce(length(scanlator), 0)) FROM chapters LIMIT 1000",
                    { cursor ->
                        if (cursor.next().value) result["avg_chapter_text_bytes"] = cursor.getDouble(0) ?: 0.0
                        QueryResult.Unit
                    },
                    0,
                    null,
                )
            }

            val mangaCount = tableCounts["mangas"] ?: 0L
            if (mangaCount > 0) {
                driver.executeQuery(
                    null,
                    "SELECT AVG(coalesce(length(description), 0)) FROM mangas",
                    { cursor ->
                        if (cursor.next().value) result["avg_manga_description_bytes"] = cursor.getDouble(0) ?: 0.0
                        QueryResult.Unit
                    },
                    0,
                    null,
                )
            }

            result
        }
    }
}
