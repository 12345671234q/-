package com.privatecompanion.chat.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.privatecompanion.chat.model.LongTermMemory
import com.privatecompanion.chat.model.MemoryDraft
import org.json.JSONArray
import org.json.JSONObject

/**
 * Evidence-backed timeline memory store for the temporary build.
 *
 * The database deliberately separates "learned at" from "occurred at" and quarantines every
 * automatically extracted or legacy item. Only user-confirmed memories participate in recall.
 * This is implemented on Android's built-in SQLite layer so the travel build has no new binary
 * dependency; the schema maps one-to-one to the Room entities used by the final 0.2.x merge.
 */
class MemoryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = TimelineDb(appContext)
    private val legacyPrefs = appContext.getSharedPreferences("companion_memory", Context.MODE_PRIVATE)

    init {
        importLegacyAsQuarantinedCandidates()
    }

    fun load(): List<LongTermMemory> = db.readableDatabase.query(
        TABLE_MEMORY,
        MEMORY_COLUMNS,
        "status = ?",
        arrayOf(STATUS_ACTIVE),
        null,
        null,
        "verified ASC, pinned DESC, importance DESC, updated_at DESC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMemory()) } }

    fun count(): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $TABLE_MEMORY WHERE status = ?",
        arrayOf(STATUS_ACTIVE),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun pendingCount(): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $TABLE_MEMORY WHERE status = ? AND verified = 0",
        arrayOf(STATUS_ACTIVE),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun saveDrafts(drafts: List<MemoryDraft>, source: String = "auto") {
        if (drafts.isEmpty()) return
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            drafts.forEach { draft ->
                val content = draft.content.trim().take(MAX_MEMORY_CHARS)
                val evidence = draft.evidenceText.trim().take(MAX_EVIDENCE_CHARS)
                if (content.length < MIN_MEMORY_CHARS || draft.sourceMessageId == null || evidence.isBlank()) return@forEach
                val normalized = normalize(content)
                val existing = findByNormalized(database, normalized)
                if (existing?.verified == true) return@forEach
                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put("id", existing?.id ?: newId())
                    put("content", content)
                    put("normalized", normalized)
                    put("category", draft.category.ifBlank { "general" }.take(24))
                    put("importance", draft.importance.coerceIn(1, 10))
                    put("created_at", existing?.createdAt ?: now)
                    put("last_accessed_at", existing?.lastAccessedAt ?: now)
                    put("access_count", existing?.accessCount ?: 0)
                    put("layer", draft.category.ifBlank { "general" }.take(24))
                    put("source", source)
                    put("pinned", 0)
                    put("updated_at", now)
                    putNullableLong("occurred_at_start", draft.occurredAtStart)
                    putNullableLong("occurred_at_end", draft.occurredAtEnd)
                    put("source_message_id", draft.sourceMessageId)
                    put("evidence_text", evidence)
                    put("source_kind", SOURCE_AUTO_USER)
                    put("confidence", draft.confidence.coerceIn(0.0, 1.0))
                    put("verified", 0)
                    put("status", STATUS_ACTIVE)
                }
                database.insertWithOnConflict(TABLE_MEMORY, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            trimAutomaticCandidates(database)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun saveExplicit(text: String) = addManual(text, "user_requested", pinned = true, occurredAtStart = null)

    fun addManual(
        content: String,
        layer: String = "user_requested",
        pinned: Boolean = true,
        occurredAtStart: Long? = null,
    ) {
        val clean = content.trim().take(MAX_MEMORY_CHARS)
        if (clean.length < MIN_MEMORY_CHARS) return
        val now = System.currentTimeMillis()
        val normalized = normalize(clean)
        val existing = findByNormalized(db.readableDatabase, normalized)
        val values = ContentValues().apply {
            put("id", existing?.id ?: newId())
            put("content", clean)
            put("normalized", normalized)
            put("category", layer.ifBlank { "user_requested" }.take(24))
            put("importance", 10)
            put("created_at", existing?.createdAt ?: now)
            put("last_accessed_at", existing?.lastAccessedAt ?: now)
            put("access_count", existing?.accessCount ?: 0)
            put("layer", layer.ifBlank { "user_requested" }.take(24))
            put("source", "manual")
            put("pinned", if (pinned) 1 else 0)
            put("updated_at", now)
            putNullableLong("occurred_at_start", occurredAtStart ?: existing?.occurredAtStart)
            putNullableLong("occurred_at_end", occurredAtStart ?: existing?.occurredAtEnd)
            putNullableLong("source_message_id", existing?.sourceMessageId)
            put("evidence_text", existing?.evidenceText.orEmpty())
            put("source_kind", SOURCE_USER_CONFIRMED)
            put("confidence", 1.0)
            put("verified", 1)
            put("status", STATUS_ACTIVE)
        }
        db.writableDatabase.insertWithOnConflict(TABLE_MEMORY, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Editing a candidate is the explicit confirmation action. */
    fun update(id: Long, content: String, layer: String, pinned: Boolean, occurredAtStart: Long? = null) {
        val clean = content.trim().take(MAX_MEMORY_CHARS)
        if (clean.length < MIN_MEMORY_CHARS) return
        val values = ContentValues().apply {
            put("content", clean)
            put("normalized", normalize(clean))
            put("category", layer.ifBlank { "general" }.take(24))
            put("layer", layer.ifBlank { "general" }.take(24))
            put("pinned", if (pinned) 1 else 0)
            put("updated_at", System.currentTimeMillis())
            putNullableLong("occurred_at_start", occurredAtStart)
            putNullableLong("occurred_at_end", occurredAtStart)
            put("source_kind", SOURCE_USER_CONFIRMED)
            put("confidence", 1.0)
            put("verified", 1)
            put("status", STATUS_ACTIVE)
        }
        db.writableDatabase.update(TABLE_MEMORY, values, "id = ?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        db.writableDatabase.delete(TABLE_MEMORY, "id = ?", arrayOf(id.toString()))
    }

    fun forgetMatching(query: String) {
        val normalized = normalize(query)
        if (normalized.isBlank()) return
        val ids = load().filter {
            val content = normalize(it.content)
            content.contains(normalized) || normalized.contains(content)
        }.map { it.id }
        val database = db.writableDatabase
        ids.forEach { database.delete(TABLE_MEMORY, "id = ?", arrayOf(it.toString())) }
    }

    /** Pinned means protected from cleanup, not "inject on every turn". */
    fun relevantTo(message: String, limit: Int = 5): List<LongTermMemory> {
        val queryTokens = tokens(message)
        val explicitRecall = listOf("记得", "以前", "上次", "之前", "什么时候", "哪天", "发生过").any(message::contains)
        val candidates = load().filter { it.verified && it.status == STATUS_ACTIVE }
        val selected = candidates.map { memory ->
            val overlap = tokens(memory.content).intersect(queryTokens).size
            val exact = normalize(memory.content).let { it.contains(normalize(message)) || normalize(message).contains(it) }
            val score = overlap * 12 + (if (exact) 18 else 0) + memory.importance +
                memory.accessCount.coerceAtMost(8) / 2 + if (memory.pinned) 3 else 0
            memory to score
        }.filter { (memory, score) ->
            score >= memory.importance + 10 || (explicitRecall && score >= memory.importance + 3)
        }.sortedWith(
            compareByDescending<Pair<LongTermMemory, Int>> { it.second }
                .thenBy { it.first.occurredAtStart ?: Long.MAX_VALUE },
        ).take(limit).map { it.first }
        touch(selected.map { it.id })
        return selected.sortedBy { it.occurredAtStart ?: it.createdAt }
    }

    fun lastExtractionAt(): Long = metadataLong(KEY_LAST_EXTRACTION_AT)

    fun markExtracted(now: Long = System.currentTimeMillis()) = putMetadata(KEY_LAST_EXTRACTION_AT, now.toString())

    /** Only verified segments may be injected. Automatic summaries remain quarantined. */
    fun loadConversationSummary(): String = db.readableDatabase.query(
        TABLE_SUMMARY,
        arrayOf("summary", "range_start_at", "range_end_at"),
        "verified = 1",
        null,
        null,
        null,
        "range_end_at DESC",
        "3",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add("时间段 ${cursor.getLong(1)}—${cursor.getLong(2)}：${cursor.getString(0)}")
            }
        }.asReversed().joinToString("\n")
    }

    /** New model summaries are stored for export/review but never silently treated as truth. */
    fun saveConversationSummary(summary: String, compressedUntil: Long) {
        val clean = summary.trim().take(MAX_SUMMARY_CHARS)
        if (clean.isBlank()) return
        val startAt = metadataLong(KEY_COMPRESSED_UNTIL).takeIf { it > 0L } ?: 0L
        val values = ContentValues().apply {
            put("id", newId())
            put("summary", clean)
            put("range_start_at", startAt)
            put("range_end_at", compressedUntil)
            put("created_at", System.currentTimeMillis())
            put("verified", 0)
            put("source_kind", "MODEL_SUMMARY_UNVERIFIED")
        }
        db.writableDatabase.insert(TABLE_SUMMARY, null, values)
        putMetadata(KEY_COMPRESSED_UNTIL, compressedUntil.toString())
    }

    fun compressedUntil(): Long = metadataLong(KEY_COMPRESSED_UNTIL)

    fun clearConversationSummary() {
        db.writableDatabase.delete(TABLE_SUMMARY, null, null)
        db.writableDatabase.delete(TABLE_METADATA, "key = ?", arrayOf(KEY_COMPRESSED_UNTIL))
    }

    fun clear() {
        db.writableDatabase.delete(TABLE_MEMORY, null, null)
        db.writableDatabase.delete(TABLE_METADATA, "key = ?", arrayOf(KEY_LAST_EXTRACTION_AT))
    }

    fun exportJson(): JSONObject = JSONObject()
        .put("schemaVersion", DB_VERSION)
        .put("memories", JSONArray().apply {
            load().forEach { memory ->
                put(JSONObject()
                    .put("id", memory.id)
                    .put("content", memory.content)
                    .put("layer", memory.layer)
                    .put("importance", memory.importance)
                    .put("learnedAt", memory.createdAt)
                    .put("occurredAtStart", memory.occurredAtStart ?: JSONObject.NULL)
                    .put("occurredAtEnd", memory.occurredAtEnd ?: JSONObject.NULL)
                    .put("sourceMessageId", memory.sourceMessageId ?: JSONObject.NULL)
                    .put("evidenceText", memory.evidenceText)
                    .put("sourceKind", memory.sourceKind)
                    .put("confidence", memory.confidence)
                    .put("verified", memory.verified)
                    .put("pinned", memory.pinned))
            }
        })
        .put("summaryCandidates", JSONArray().apply {
            db.readableDatabase.query(TABLE_SUMMARY, null, null, null, null, null, "range_start_at ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    put(JSONObject()
                        .put("summary", cursor.getString(cursor.getColumnIndexOrThrow("summary")))
                        .put("rangeStartAt", cursor.getLong(cursor.getColumnIndexOrThrow("range_start_at")))
                        .put("rangeEndAt", cursor.getLong(cursor.getColumnIndexOrThrow("range_end_at")))
                        .put("verified", cursor.getInt(cursor.getColumnIndexOrThrow("verified")) == 1))
                }
            }
        })

    private fun importLegacyAsQuarantinedCandidates() {
        if (metadataLong(KEY_LEGACY_IMPORTED) == 1L) return
        runCatching {
            val array = JSONArray(legacyPrefs.getString("long_term_memories", "[]"))
            val database = db.writableDatabase
            database.beginTransaction()
            try {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val content = item.optString("content").trim().take(MAX_MEMORY_CHARS)
                    if (content.length < MIN_MEMORY_CHARS || findByNormalized(database, normalize(content)) != null) continue
                    val now = System.currentTimeMillis()
                    database.insert(TABLE_MEMORY, null, ContentValues().apply {
                        put("id", item.optLong("id", newId()))
                        put("content", content)
                        put("normalized", normalize(content))
                        put("category", item.optString("category", "legacy").take(24))
                        put("importance", item.optInt("importance", 5).coerceIn(1, 10))
                        put("created_at", item.optLong("createdAt", now))
                        put("last_accessed_at", now)
                        put("access_count", 0)
                        put("layer", item.optString("layer", "legacy").take(24))
                        put("source", "legacy")
                        put("pinned", 0)
                        put("updated_at", now)
                        putNull("occurred_at_start")
                        putNull("occurred_at_end")
                        putNull("source_message_id")
                        put("evidence_text", "")
                        put("source_kind", SOURCE_LEGACY)
                        put("confidence", 0.0)
                        put("verified", 0)
                        put("status", STATUS_ACTIVE)
                    })
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
        putMetadata(KEY_LEGACY_IMPORTED, "1")
    }

    private fun touch(ids: List<Long>) {
        if (ids.isEmpty()) return
        val database = db.writableDatabase
        ids.forEach { id ->
            database.execSQL(
                "UPDATE $TABLE_MEMORY SET last_accessed_at = ?, access_count = access_count + 1 WHERE id = ?",
                arrayOf(System.currentTimeMillis(), id),
            )
        }
    }

    private fun findByNormalized(database: SQLiteDatabase, normalized: String): LongTermMemory? = database.query(
        TABLE_MEMORY,
        MEMORY_COLUMNS,
        "normalized = ?",
        arrayOf(normalized),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toMemory() else null }

    private fun trimAutomaticCandidates(database: SQLiteDatabase) {
        database.execSQL(
            "DELETE FROM $TABLE_MEMORY WHERE id IN (" +
                "SELECT id FROM $TABLE_MEMORY WHERE verified = 0 AND pinned = 0 " +
                "ORDER BY updated_at DESC LIMIT -1 OFFSET $MAX_PENDING_MEMORIES)",
        )
    }

    private fun metadataLong(key: String): Long = db.readableDatabase.query(
        TABLE_METADATA,
        arrayOf("value"),
        "key = ?",
        arrayOf(key),
        null,
        null,
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).toLongOrNull() ?: 0L else 0L }

    private fun putMetadata(key: String, value: String) {
        db.writableDatabase.insertWithOnConflict(TABLE_METADATA, null, ContentValues().apply {
            put("key", key)
            put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun Cursor.toMemory() = LongTermMemory(
        id = getLong(getColumnIndexOrThrow("id")),
        content = getString(getColumnIndexOrThrow("content")),
        category = getString(getColumnIndexOrThrow("category")),
        importance = getInt(getColumnIndexOrThrow("importance")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        lastAccessedAt = getLong(getColumnIndexOrThrow("last_accessed_at")),
        accessCount = getInt(getColumnIndexOrThrow("access_count")),
        layer = getString(getColumnIndexOrThrow("layer")),
        source = getString(getColumnIndexOrThrow("source")),
        pinned = getInt(getColumnIndexOrThrow("pinned")) == 1,
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        occurredAtStart = longOrNull("occurred_at_start"),
        occurredAtEnd = longOrNull("occurred_at_end"),
        sourceMessageId = longOrNull("source_message_id"),
        evidenceText = getString(getColumnIndexOrThrow("evidence_text")),
        sourceKind = getString(getColumnIndexOrThrow("source_kind")),
        confidence = getDouble(getColumnIndexOrThrow("confidence")),
        verified = getInt(getColumnIndexOrThrow("verified")) == 1,
        status = getString(getColumnIndexOrThrow("status")),
    )

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun ContentValues.putNullableLong(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun tokens(value: String): Set<String> {
        val normalized = normalize(value)
        val words = Regex("[a-z0-9]{2,}").findAll(normalized).map { it.value }.toMutableSet()
        val chinese = normalized.filter { it in '\u4e00'..'\u9fff' }
        chinese.windowed(2, 1).forEach(words::add)
        return words
    }

    private fun normalize(value: String): String = value.lowercase().replace(Regex("\\s+"), "").trim()

    private fun newId(): Long = System.currentTimeMillis() * 1_000L + System.nanoTime() % 1_000L

    private class TimelineDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE $TABLE_MEMORY (
                    id INTEGER PRIMARY KEY,
                    content TEXT NOT NULL,
                    normalized TEXT NOT NULL UNIQUE,
                    category TEXT NOT NULL,
                    importance INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    last_accessed_at INTEGER NOT NULL,
                    access_count INTEGER NOT NULL,
                    layer TEXT NOT NULL,
                    source TEXT NOT NULL,
                    pinned INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    occurred_at_start INTEGER,
                    occurred_at_end INTEGER,
                    source_message_id INTEGER,
                    evidence_text TEXT NOT NULL,
                    source_kind TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    verified INTEGER NOT NULL,
                    status TEXT NOT NULL
                )""".trimIndent(),
            )
            db.execSQL(
                """CREATE TABLE $TABLE_SUMMARY (
                    id INTEGER PRIMARY KEY,
                    summary TEXT NOT NULL,
                    range_start_at INTEGER NOT NULL,
                    range_end_at INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    verified INTEGER NOT NULL,
                    source_kind TEXT NOT NULL
                )""".trimIndent(),
            )
            db.execSQL("CREATE TABLE $TABLE_METADATA (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("CREATE INDEX memory_verified_status ON $TABLE_MEMORY(verified, status)")
            db.execSQL("CREATE INDEX memory_occurred_at ON $TABLE_MEMORY(occurred_at_start)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val DB_NAME = "jimo_timeline_memory.db"
        const val DB_VERSION = 1
        const val TABLE_MEMORY = "memory_items"
        const val TABLE_SUMMARY = "summary_segments"
        const val TABLE_METADATA = "memory_metadata"
        const val STATUS_ACTIVE = "ACTIVE"
        const val SOURCE_AUTO_USER = "AUTO_USER_EVIDENCE"
        const val SOURCE_USER_CONFIRMED = "USER_CONFIRMED"
        const val SOURCE_LEGACY = "LEGACY_UNVERIFIED"
        const val KEY_LAST_EXTRACTION_AT = "last_memory_extraction_at"
        const val KEY_COMPRESSED_UNTIL = "compressed_context_until"
        const val KEY_LEGACY_IMPORTED = "legacy_imported_v1"
        const val MAX_MEMORY_CHARS = 240
        const val MAX_EVIDENCE_CHARS = 360
        const val MIN_MEMORY_CHARS = 4
        const val MAX_PENDING_MEMORIES = 120
        const val MAX_SUMMARY_CHARS = 2_000
        val MEMORY_COLUMNS = arrayOf(
            "id", "content", "category", "importance", "created_at", "last_accessed_at",
            "access_count", "layer", "source", "pinned", "updated_at", "occurred_at_start",
            "occurred_at_end", "source_message_id", "evidence_text", "source_kind", "confidence",
            "verified", "status",
        )
    }
}
