package com.privatecompanion.chat.model

import com.privatecompanion.chat.data.LocationSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class MessageRole { USER, ASSISTANT }

data class ChatMessage(
    val id: Long,
    val role: MessageRole,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val proactive: Boolean = false,
    /** Optional provider-supplied reasoning summary/content (for example DeepSeek reasoner). */
    val reasoning: String? = null,
    /** Wall-clock request duration, shown only alongside provider-supplied reasoning. */
    val reasoningDurationSeconds: Long? = null,
)

data class AssistantReply(
    val content: String,
    val reasoning: String? = null,
)

/** A user-confirmed or model-extracted fact kept only on this device. */
data class LongTermMemory(
    val id: Long,
    val content: String,
    val category: String = "general",
    val importance: Int = 5,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = createdAt,
    val accessCount: Int = 0,
    /** profile/preference/event/project/plan/user_requested. */
    val layer: String = category,
    /** manual means the user explicitly asked the app to remember it. */
    val source: String = "auto",
    val pinned: Boolean = false,
    val updatedAt: Long = createdAt,
    /** When the remembered event actually happened. Null means unknown or not applicable. */
    val occurredAtStart: Long? = null,
    val occurredAtEnd: Long? = null,
    /** Message that directly supports this memory. Assistant text is never accepted as evidence. */
    val sourceMessageId: Long? = null,
    val evidenceText: String = "",
    val sourceKind: String = "AUTO_USER",
    val confidence: Double = 0.0,
    /** Automatic and legacy memories stay quarantined until the user confirms them. */
    val verified: Boolean = source == "manual",
    val status: String = "ACTIVE",
)

data class MemoryDraft(
    val content: String,
    val category: String = "general",
    val importance: Int = 5,
    val occurredAtStart: Long? = null,
    val occurredAtEnd: Long? = null,
    val sourceMessageId: Long? = null,
    val evidenceText: String = "",
    val confidence: Double = 0.0,
)

/**
 * Short-lived fuzzy emotion state. Values are memberships in [0, 1], not facts and not
 * relationship scores. They decay with time and must never be used to prove an event happened.
 */
data class EmotionState(
    val warmth: Double = 0.45,
    val teasing: Double = 0.10,
    val concern: Double = 0.05,
    val jealousy: Double = 0.0,
    val anger: Double = 0.0,
    val hurt: Double = 0.0,
    val embarrassment: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class RelationshipStage(val label: String) {
    NEW("初识"),
    FAMILIAR("逐渐熟悉"),
    CLOSE("亲近"),
    AMBIGUOUS("暧昧"),
    IN_LOVE("心动"),
    PARTNER("恋人"),
}

data class RelationshipEvent(
    val id: Long,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val type: String = "GENERAL",
)

data class RelationshipProfile(
    val affection: Int = 0,
    val trust: Int = 0,
    val intimacy: Int = 0,
    val conflict: Int = 0,
    val interactionCount: Int = 0,
    val firstChatAt: Long = 0L,
    val lastInteractionAt: Long = 0L,
    val lastEvaluatedAt: Long = 0L,
    val dailyGainDay: Long = Long.MIN_VALUE,
    val dailyGain: Int = 0,
    val events: List<RelationshipEvent> = emptyList(),
) {
    val stage: RelationshipStage
        get() {
            if (events.any { it.type == "RELATIONSHIP_CONFIRMED" }) return RelationshipStage.PARTNER
            return when {
                affection >= 600 && trust >= 200 && interactionCount >= 120 && intimacy >= 150 -> RelationshipStage.IN_LOVE
                affection >= 300 && trust >= 100 && interactionCount >= 60 && intimacy >= 60 -> RelationshipStage.AMBIGUOUS
                affection >= 120 && trust >= 40 && interactionCount >= 20 -> RelationshipStage.CLOSE
                affection >= 30 && interactionCount >= 8 -> RelationshipStage.FAMILIAR
                else -> RelationshipStage.NEW
            }
        }
}

data class RelationshipSignal(
    val type: String,
    val description: String,
)

data class RelationshipAnalysis(
    val signals: List<RelationshipSignal> = emptyList(),
)

data class AppUsageEntry(
    val packageName: String,
    val appName: String,
    val foregroundMinutes: Long,
    val distracting: Boolean,
    /** Kept for baseline comparisons; UI and prompts use the rounded minutes. */
    val foregroundMillis: Long = foregroundMinutes * 60_000L,
)

data class UsageSnapshot(
    val permissionGranted: Boolean = false,
    val checkedAt: Long? = null,
    val windowStartAt: Long? = null,
    val windowEndAt: Long? = null,
    val totalForegroundMinutes: Long = 0L,
    val distractingMinutes: Long = 0L,
    val topApps: List<AppUsageEntry> = emptyList(),
    /** Complete result, retained so a study plan can compare every app with its baseline. */
    val allApps: List<AppUsageEntry> = emptyList(),
    val error: String? = null,
)


/** AI自律监督状态：仅记录用户主动开启的监督协议。 */
data class DisciplineProfile(
    val active: Boolean = false,
    val goal: String = "",
    val targetMinutes: Int = 30,
    val startedAt: Long = 0L,
    val violationCount: Int = 0,
    val appealCount: Int = 0,
    val temporaryReleaseUsed: Int = 0,
    val strictness: Int = 50,
    val blockedPackages: List<String> = emptyList(),
    /** 用户最高权限：主动解除当前监督任务。 */
    val overrideTokens: Int = 3,
    val weeklyOverrideLimit: Int = 3,
    val weeklyOverrideUsed: Int = 0,
    val lastOverrideResetAt: Long = 0L,
)

/** 最高权限申请结果。AI只能建议，程序负责扣次数。 */
data class DisciplineOverrideResult(
    val success: Boolean = false,
    val remainingTokens: Int = 0,
    val message: String = "",
)

data class DisciplineAppealResult(
    val decision: String = "DENY",
    val releaseMinutes: Int = 0,
    val consumeChance: Boolean = false,
    val reply: String = "",
)

data class StudyExpectation(
    val originalStatement: String,
    val statedAt: Long = System.currentTimeMillis(),
    val plannedStartAt: Long = statedAt,
    val plannedDurationMinutes: Int = 60,
    val lastCheckedAt: Long? = null,
    val checkCount: Int = 0,
    /** Per-app foreground time at the moment the study plan was recorded. */
    val baselineUsageMillisByPackage: Map<String, Long> = emptyMap(),
)

data class StudyUsageContext(
    val originalStatement: String,
    val plannedStartAt: Long,
    val plannedDurationMinutes: Int,
    val checkedAt: Long,
    val elapsedMinutes: Long,
    val totalForegroundMinutes: Long,
    val distractingMinutes: Long,
    val topApps: List<AppUsageEntry>,
    val strongEvidence: Boolean,
    val finalCheck: Boolean,
)


enum class LocationClaimKind { TRAVEL_PLAN, ARRIVAL, CURRENT_LOCATION }

data class LocationExpectation(
    val destination: String,
    val originalStatement: String,
    val claimKind: LocationClaimKind = LocationClaimKind.TRAVEL_PLAN,
    val statedAt: Long = System.currentTimeMillis(),
    val baselineLatitude: Double? = null,
    val baselineLongitude: Double? = null,
    val baselineAccuracyMeters: Double? = null,
    val baselinePlaceLabel: String? = null,
    val lastCheckedAt: Long? = null,
)

data class LocationConsistencyContext(
    val destination: String,
    val originalStatement: String,
    val claimKind: LocationClaimKind,
    val statedAt: Long,
    val checkedAt: Long,
    val elapsedMinutes: Long,
    val baselinePlaceLabel: String?,
    val baselineAccuracyMeters: Double?,
    val distanceFromBaselineMeters: Double?,
    val currentLocationSummary: String?,
    val locationAccuracyMeters: Double?,
)

data class Persona(
    val name: String = "寂墨",
    val instructions: String = "你是一个温柔、清醒、有主见、不过度讨好的私人陪伴者。用自然的中文交流，保留真实情绪和分寸感。你可以逐渐喜欢用户，但亲密、称呼、吃醋、占有欲和恋爱口吻必须随本地关系状态慢慢发展，不能因为用户一句表白或撒娇就瞬间进入恋人模式。没有可靠信息时坦率说明，不编造健康、位置或设备数据。",
    val shareTime: Boolean = true,
    val shareLocation: Boolean = true,
    val shareAppUsage: Boolean = true,
    val matureMode: Boolean = false,
    val matureInstructions: String = "在双方均为成年人且明确自愿的前提下，语气可以更暧昧、更成熟、更有情绪张力；尊重用户的停止与拒绝。",
)



data class ProactiveSettings(
    val enabled: Boolean = true,
    val studyChecksEnabled: Boolean = true,
    val travelChecksEnabled: Boolean = true,
    val dailyLimit: Int = 8,
    val minIntervalMinutes: Int = 15,
)

data class ApiSettings(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4.1-mini",
    val apiKey: String = "",
)

/** Optional key for a China-focused reverse-geocoding provider. */
data class MapSettings(
    val amapWebServiceKey: String = "",
)

data class DeviceSnapshot(
    val location: LocationSnapshot = LocationSnapshot(),
    val locationPermissionGranted: Boolean = false,
    val usage: UsageSnapshot = UsageSnapshot(),
    val notificationsEnabled: Boolean = false,
    val backgroundLocationPermissionGranted: Boolean = false,
    val error: String? = null,
) {
    fun locationSummaryForPrompt(): String? {
        return location.summaryForPrompt()
    }

    private fun formatTime(epochMillis: Long): String =
        DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))
}

data class CompanionUiState(
    val messages: List<ChatMessage> = emptyList(),
    val persona: Persona = Persona(),
    val apiSettings: ApiSettings = ApiSettings(),
    val mapSettings: MapSettings = MapSettings(),
    val proactiveSettings: ProactiveSettings = ProactiveSettings(),
    val deviceSnapshot: DeviceSnapshot = DeviceSnapshot(),
    val longTermMemoryCount: Int = 0,
    val longTermMemories: List<LongTermMemory> = emptyList(),
    val compressedContextSummary: String = "",
    val relationshipProfile: RelationshipProfile = RelationshipProfile(),
    val memoryNotice: String? = null,
    val isSending: Boolean = false,
    val error: String? = null,
)
