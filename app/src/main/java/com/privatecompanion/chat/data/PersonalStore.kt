package com.privatecompanion.chat.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.privatecompanion.chat.model.ApiSettings
import com.privatecompanion.chat.model.ChatMessage
import com.privatecompanion.chat.model.MessageRole
import com.privatecompanion.chat.model.LocationClaimKind
import com.privatecompanion.chat.model.LocationExpectation
import com.privatecompanion.chat.model.MapSettings
import com.privatecompanion.chat.model.Persona
import com.privatecompanion.chat.model.ProactiveSettings
import com.privatecompanion.chat.model.RelationshipEvent
import com.privatecompanion.chat.model.RelationshipProfile
import com.privatecompanion.chat.model.StudyExpectation
import org.json.JSONArray
import org.json.JSONObject

/** Local-only storage. The API key is kept in Android Keystore-backed preferences. */
class PersonalStore(context: Context) {
    private val plain = context.getSharedPreferences("companion_local", Context.MODE_PRIVATE)

    private val secure = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "companion_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { plain }

    fun loadPersona(): Persona {
        val storedName = plain.getString("persona_name", null)
        val migratedName = storedName?.takeUnless { it.isBlank() || it == "小伴" || it == "Mi Companion" } ?: "寂墨"
        return Persona(
            name = migratedName,
            instructions = plain.getString("persona_instructions", Persona().instructions) ?: Persona().instructions,
            shareTime = plain.getBoolean("persona_share_time", true),
            shareLocation = plain.getBoolean("persona_share_location", true),
            shareAppUsage = plain.getBoolean("persona_share_app_usage", true),
            matureMode = plain.getBoolean("persona_mature_mode", false),
            matureInstructions = plain.getString("persona_mature_instructions", Persona().matureInstructions)
                ?: Persona().matureInstructions,
        )
    }

    fun savePersona(persona: Persona) {
        plain.edit()
            .putString("persona_name", persona.name.trim())
            .putString("persona_instructions", persona.instructions.trim())
            .putBoolean("persona_share_time", persona.shareTime)
            .putBoolean("persona_share_location", persona.shareLocation)
            .putBoolean("persona_share_app_usage", persona.shareAppUsage)
            .putBoolean("persona_mature_mode", persona.matureMode)
            .putString("persona_mature_instructions", persona.matureInstructions.trim())
            .apply()
    }

    fun loadApiSettings() = ApiSettings(
        baseUrl = plain.getString("api_base_url", ApiSettings().baseUrl) ?: ApiSettings().baseUrl,
        model = plain.getString("api_model", ApiSettings().model) ?: ApiSettings().model,
        apiKey = secure.getString("api_key", "") ?: "",
    )

    fun saveApiSettings(settings: ApiSettings) {
        plain.edit()
            .putString("api_base_url", settings.baseUrl.trim().trimEnd('/'))
            .putString("api_model", settings.model.trim())
            .apply()
        secure.edit().putString("api_key", settings.apiKey.trim()).apply()
    }

    fun loadMapSettings() = MapSettings(
        amapWebServiceKey = secure.getString("amap_web_service_key", "") ?: "",
    )

    fun saveMapSettings(settings: MapSettings) {
        secure.edit().putString("amap_web_service_key", settings.amapWebServiceKey.trim()).apply()
    }

    fun loadProactiveSettings() = ProactiveSettings(
        enabled = plain.getBoolean("proactive_enabled", true),
        studyChecksEnabled = plain.getBoolean("proactive_study_enabled", true),
        travelChecksEnabled = plain.getBoolean("proactive_travel_enabled", true),
        dailyLimit = plain.getInt("proactive_daily_limit", 8).coerceIn(1, 20),
        minIntervalMinutes = plain.getInt("proactive_min_interval", 15).coerceIn(5, 180),
    )

    fun saveProactiveSettings(settings: ProactiveSettings) {
        plain.edit()
            .putBoolean("proactive_enabled", settings.enabled)
            .putBoolean("proactive_study_enabled", settings.studyChecksEnabled)
            .putBoolean("proactive_travel_enabled", settings.travelChecksEnabled)
            .putInt("proactive_daily_limit", settings.dailyLimit.coerceIn(1, 20))
            .putInt("proactive_min_interval", settings.minIntervalMinutes.coerceIn(5, 180))
            .apply()
    }

    fun loadRelationshipProfile(): RelationshipProfile = runCatching {
        val raw = plain.getString(KEY_RELATIONSHIP, null) ?: return defaultTravelRelationship()
        val item = JSONObject(raw)
        val eventArray = item.optJSONArray("events") ?: JSONArray()
        val events = buildList {
            for (index in 0 until eventArray.length()) {
                val event = eventArray.optJSONObject(index) ?: continue
                val content = event.optString("content").trim()
                if (content.isNotBlank()) add(
                    RelationshipEvent(
                        id = event.optLong("id", System.nanoTime()),
                        content = content,
                        createdAt = event.optLong("createdAt", System.currentTimeMillis()),
                        type = event.optString("type", "GENERAL"),
                    ),
                )
            }
        }
        RelationshipProfile(
            affection = item.optInt("affection", 0).coerceIn(0, 1000),
            firstChatAt = item.optLong("firstChatAt", 0L),
            lastInteractionAt = item.optLong("lastInteractionAt", 0L),
            lastEvaluatedAt = item.optLong("lastEvaluatedAt", 0L),
            dailyGainDay = item.optLong("dailyGainDay", Long.MIN_VALUE),
            dailyGain = item.optInt("dailyGain", 0).coerceIn(0, 8),
            events = events.takeLast(30),
        )
    }.getOrDefault(defaultTravelRelationship())

    private fun defaultTravelRelationship() = RelationshipProfile(
        affection = 1000,
        trust = 500,
        intimacy = 500,
        interactionCount = 120,
        firstChatAt = System.currentTimeMillis(),
        events = listOf(
            RelationshipEvent(
                id = -1L,
                content = "用户已将本应用设定为长期恋爱陪伴关系",
                type = "RELATIONSHIP_CONFIRMED",
            ),
        ),
    )

    fun saveRelationshipProfile(profile: RelationshipProfile) {
        val events = JSONArray()
        profile.events.takeLast(30).forEach { event ->
            events.put(
                JSONObject()
                    .put("id", event.id)
                    .put("content", event.content)
                    .put("createdAt", event.createdAt)
                    .put("type", event.type),
            )
        }
        val item = JSONObject()
            .put("affection", profile.affection.coerceIn(0, 1000))
            .put("firstChatAt", profile.firstChatAt)
            .put("lastInteractionAt", profile.lastInteractionAt)
            .put("lastEvaluatedAt", profile.lastEvaluatedAt)
            .put("dailyGainDay", profile.dailyGainDay)
            .put("dailyGain", profile.dailyGain.coerceIn(0, 8))
            .put("events", events)
        plain.edit().putString(KEY_RELATIONSHIP, item.toString()).apply()
    }

    fun resetRelationshipProfile() = plain.edit().remove(KEY_RELATIONSHIP).apply()

    @Synchronized
    fun appendProactiveAssistantMessage(content: String): ChatMessage {
        val message = ChatMessage(
            id = System.nanoTime(),
            role = MessageRole.ASSISTANT,
            content = content.trim(),
            proactive = true,
        )
        saveMessages(loadMessages() + message)
        return message
    }

    fun canSendProactive(settings: ProactiveSettings, now: Long = System.currentTimeMillis()): Boolean {
        if (!settings.enabled) return false
        val today = java.time.LocalDate.now().toEpochDay()
        val storedDay = plain.getLong("proactive_budget_day", Long.MIN_VALUE)
        val sentToday = if (storedDay == today) plain.getInt("proactive_sent_today", 0) else 0
        if (sentToday >= settings.dailyLimit) return false

        val lastSentAt = plain.getLong("proactive_last_sent_at", 0L)
        if (lastSentAt > 0L && now - lastSentAt < settings.minIntervalMinutes * 60_000L) return false

        val messages = loadMessages()
        val lastUserAt = messages.lastOrNull { it.role == MessageRole.USER }?.createdAt ?: 0L
        if (lastSentAt > lastUserAt) return false
        val lastMessage = messages.lastOrNull()
        if (lastMessage?.role == MessageRole.USER && now - lastMessage.createdAt < 2 * 60_000L) return false
        return true
    }

    fun recordProactiveSent(now: Long = System.currentTimeMillis()) {
        val today = java.time.LocalDate.now().toEpochDay()
        val storedDay = plain.getLong("proactive_budget_day", Long.MIN_VALUE)
        val sentToday = if (storedDay == today) plain.getInt("proactive_sent_today", 0) else 0
        plain.edit()
            .putLong("proactive_budget_day", today)
            .putInt("proactive_sent_today", sentToday + 1)
            .putLong("proactive_last_sent_at", now)
            .apply()
    }


    @Synchronized
    fun commitProactiveMessage(
        content: String,
        settings: ProactiveSettings,
        now: Long = System.currentTimeMillis(),
    ): ChatMessage? {
        if (!canSendProactive(settings, now)) return null
        val message = ChatMessage(
            id = System.nanoTime(),
            role = MessageRole.ASSISTANT,
            content = content.trim(),
            createdAt = now,
            proactive = true,
        )
        saveMessages(loadMessages() + message)
        val today = java.time.LocalDate.now().toEpochDay()
        val storedDay = plain.getLong("proactive_budget_day", Long.MIN_VALUE)
        val sentToday = if (storedDay == today) plain.getInt("proactive_sent_today", 0) else 0
        plain.edit()
            .putLong("proactive_budget_day", today)
            .putInt("proactive_sent_today", sentToday + 1)
            .putLong("proactive_last_sent_at", now)
            .apply()
        return message
    }

    fun proactiveSentToday(): Int {
        val today = java.time.LocalDate.now().toEpochDay()
        return if (plain.getLong("proactive_budget_day", Long.MIN_VALUE) == today) {
            plain.getInt("proactive_sent_today", 0)
        } else {
            0
        }
    }

    fun loadMessages(): List<ChatMessage> = runCatching {
        val array = JSONArray(plain.getString("chat_history", "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    ChatMessage(
                        id = item.getLong("id"),
                        role = MessageRole.valueOf(item.getString("role")),
                        content = item.getString("content"),
                        createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                        proactive = item.optBoolean("proactive", false),
                        reasoning = item.optString("reasoning").takeIf { it.isNotBlank() },
                        reasoningDurationSeconds = item.optLong("reasoningDurationSeconds", -1L).takeIf { it >= 0L },
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    fun saveMessages(messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(MAX_STORED_MESSAGES).forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("role", message.role.name)
                    .put("content", message.content)
                    .put("createdAt", message.createdAt)
                    .put("proactive", message.proactive)
                    .put("reasoning", message.reasoning ?: JSONObject.NULL)
                    .put("reasoningDurationSeconds", message.reasoningDurationSeconds ?: JSONObject.NULL),
            )
        }
        plain.edit().putString("chat_history", array.toString()).apply()
    }

    fun clearMessages() = plain.edit().remove("chat_history").apply()

    fun loadLocationExpectation(): LocationExpectation? = runCatching {
        val raw = plain.getString("location_expectation", null) ?: return null
        val item = JSONObject(raw)
        LocationExpectation(
            destination = item.getString("destination"),
            originalStatement = item.getString("originalStatement"),
            claimKind = LocationClaimKind.valueOf(item.optString("claimKind", LocationClaimKind.TRAVEL_PLAN.name)),
            statedAt = item.optLong("statedAt", System.currentTimeMillis()),
            baselineLatitude = item.optNullableDouble("baselineLatitude"),
            baselineLongitude = item.optNullableDouble("baselineLongitude"),
            baselineAccuracyMeters = item.optNullableDouble("baselineAccuracyMeters"),
            baselinePlaceLabel = item.optNullableString("baselinePlaceLabel"),
            lastCheckedAt = item.optNullableLong("lastCheckedAt"),
        )
    }.getOrNull()

    fun saveLocationExpectation(expectation: LocationExpectation?) {
        if (expectation == null) {
            plain.edit().remove("location_expectation").apply()
            return
        }
        val item = JSONObject()
            .put("destination", expectation.destination)
            .put("originalStatement", expectation.originalStatement)
            .put("claimKind", expectation.claimKind.name)
            .put("statedAt", expectation.statedAt)
            .put("baselineLatitude", expectation.baselineLatitude ?: JSONObject.NULL)
            .put("baselineLongitude", expectation.baselineLongitude ?: JSONObject.NULL)
            .put("baselineAccuracyMeters", expectation.baselineAccuracyMeters ?: JSONObject.NULL)
            .put("baselinePlaceLabel", expectation.baselinePlaceLabel ?: JSONObject.NULL)
            .put("lastCheckedAt", expectation.lastCheckedAt ?: JSONObject.NULL)
        plain.edit().putString("location_expectation", item.toString()).apply()
    }

    fun loadStudyExpectation(): StudyExpectation? = runCatching {
        val raw = plain.getString("study_expectation", null) ?: return null
        val item = JSONObject(raw)
        StudyExpectation(
            originalStatement = item.getString("originalStatement"),
            statedAt = item.optLong("statedAt", System.currentTimeMillis()),
            plannedStartAt = item.optLong("plannedStartAt", System.currentTimeMillis()),
            plannedDurationMinutes = item.optInt("plannedDurationMinutes", 60).coerceIn(10, 480),
            lastCheckedAt = item.optNullableLong("lastCheckedAt"),
            checkCount = item.optInt("checkCount", 0).coerceAtLeast(0),
            baselineUsageMillisByPackage = item.optJSONObject("baselineUsageMillisByPackage")
                ?.keys()
                ?.asSequence()
                ?.associateWith { packageName -> item.getJSONObject("baselineUsageMillisByPackage").optLong(packageName, 0L) }
                ?.filterValues { it >= 0L }
                .orEmpty(),
        )
    }.getOrNull()

    fun saveStudyExpectation(expectation: StudyExpectation?) {
        if (expectation == null) {
            plain.edit().remove("study_expectation").apply()
            return
        }
        val item = JSONObject()
            .put("originalStatement", expectation.originalStatement)
            .put("statedAt", expectation.statedAt)
            .put("plannedStartAt", expectation.plannedStartAt)
            .put("plannedDurationMinutes", expectation.plannedDurationMinutes)
            .put("lastCheckedAt", expectation.lastCheckedAt ?: JSONObject.NULL)
            .put("checkCount", expectation.checkCount)
            .put(
                "baselineUsageMillisByPackage",
                JSONObject(expectation.baselineUsageMillisByPackage),
            )
        plain.edit().putString("study_expectation", item.toString()).apply()
    }

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key)

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private companion object {
        // Keep enough raw evidence for timeline review and portable export.
        const val MAX_STORED_MESSAGES = 300
        // v2 intentionally starts fresh: v1 incorrectly awarded points for every ordinary message.
        const val KEY_RELATIONSHIP = "relationship_profile_v2"
    }
}
