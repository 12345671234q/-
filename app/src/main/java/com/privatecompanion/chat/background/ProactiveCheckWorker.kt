package com.privatecompanion.chat.background

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.privatecompanion.chat.data.LocationRepository
import com.privatecompanion.chat.data.LocationSnapshot
import com.privatecompanion.chat.data.OpenAiCompatibleClient
import com.privatecompanion.chat.data.PersonalStore
import com.privatecompanion.chat.data.UsageStatsRepository
import com.privatecompanion.chat.model.LocationExpectation
import com.privatecompanion.chat.model.ProactiveSettings
import com.privatecompanion.chat.model.StudyExpectation
import com.privatecompanion.chat.notifications.CompanionNotifications
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProactiveCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val store = PersonalStore(appContext)
    private val usage = UsageStatsRepository(appContext)
    private val location = LocationRepository(appContext)
    private val api = OpenAiCompatibleClient()

    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_TYPE) ?: return Result.failure()
        val eventId = inputData.getLong(KEY_EVENT_ID, Long.MIN_VALUE)
        if (eventId == Long.MIN_VALUE) return Result.failure()

        val settings = store.loadProactiveSettings()
        if (!settings.enabled || !CompanionNotifications.canNotify(applicationContext)) {
            return Result.success()
        }

        return generationMutex.withLock {
            if (!store.canSendProactive(settings)) return@withLock Result.success()
            when (type) {
                TYPE_STUDY_MID -> checkStudy(eventId, finalCheck = false, settings)
                TYPE_STUDY_END -> checkStudy(eventId, finalCheck = true, settings)
                TYPE_TRAVEL_FIRST -> checkTravel(eventId, finalCheck = false, settings)
                TYPE_TRAVEL_SECOND -> checkTravel(eventId, finalCheck = true, settings)
                else -> Result.failure()
            }
        }
    }

    private suspend fun checkStudy(
        eventId: Long,
        finalCheck: Boolean,
        settings: ProactiveSettings,
    ): Result {
        if (!settings.studyChecksEnabled) return Result.success()
        val expectation = store.loadStudyExpectation() ?: return Result.success()
        if (expectation.statedAt != eventId) return Result.success()

        val now = System.currentTimeMillis()
        if (now < expectation.plannedStartAt + MIN_STUDY_EVIDENCE_MS) return Result.success()
        val snapshot = if (expectation.baselineUsageMillisByPackage.isEmpty()) {
            // Compatibility for plans that were saved before baseline support existed.
            usage.query(expectation.plannedStartAt, now)
        } else {
            usage.sinceBaseline(usage.queryToday(), expectation.baselineUsageMillisByPackage)
        }
        if (!snapshot.permissionGranted || snapshot.error != null) return Result.success()

        val elapsedMinutes = max(1L, (now - expectation.plannedStartAt) / 60_000L)
        val minimumDistracting = max(3L, elapsedMinutes / 5L)
        val suspicious = snapshot.distractingMinutes >= minimumDistracting
        val strong = snapshot.distractingMinutes >= max(8L, elapsedMinutes / 3L)

        if (!suspicious) {
            if (finalCheck) {
                store.saveStudyExpectation(null)
                if (store.canSendProactive(settings)) {
                    deliver(
                        "${expectation.plannedDurationMinutes}分钟到了。学得怎么样？别跟我说你把这事忘了。",
                        settings,
                    )
                }
            } else {
                store.saveStudyExpectation(
                    expectation.copy(
                        lastCheckedAt = now,
                        checkCount = expectation.checkCount + 1,
                    ),
                )
            }
            return Result.success()
        }

        val topApps = snapshot.topApps
            .filter { it.distracting }
            .take(4)
            .joinToString("、") { "${it.appName}${it.foregroundMinutes}分钟" }
            .ifBlank { "娱乐类应用${snapshot.distractingMinutes}分钟" }
        val prompt = buildString {
            append("【学习监督主动抓包】\n")
            append("小晴之前说：『${expectation.originalStatement}』。\n")
            append("计划学习${expectation.plannedDurationMinutes}分钟，目前过去约${elapsedMinutes}分钟。\n")
            append("从她说要学习之后，手机前台可见使用约${snapshot.totalForegroundMinutes}分钟，")
            append("其中娱乐、游戏、短视频或社交约${snapshot.distractingMinutes}分钟；主要是：$topApps。\n")
            append(if (strong) "证据较强，可以直接抓包，语气凶一点。" else "证据一般，用抽查和质问语气，不要断言她完全没学习。")
            append("不要提后台、权限、统计或系统；可以自然点出App名和大概时长。只输出一条简短消息。")
        }
        val fallback = if (strong) {
            "不是说去学习？$topApps 倒是用得挺认真。现在告诉我，学了多少。"
        } else {
            "学习学到$topApps 上去了？说说，刚才到底学了多久。"
        }
        val generated = generate(prompt, fallback)
        if (!store.canSendProactive(settings)) return Result.success()
        if (finalCheck) {
            store.saveStudyExpectation(null)
        } else {
            store.saveStudyExpectation(
                expectation.copy(
                    lastCheckedAt = now,
                    checkCount = expectation.checkCount + 1,
                ),
            )
        }
        deliver(generated, settings)
        return Result.success()
    }

    private suspend fun checkTravel(
        eventId: Long,
        finalCheck: Boolean,
        settings: ProactiveSettings,
    ): Result {
        if (!settings.travelChecksEnabled) return Result.success()
        val expectation = store.loadLocationExpectation() ?: return Result.success()
        if (expectation.statedAt != eventId) return Result.success()
        if (!hasBackgroundLocationPermission()) return Result.success()

        val now = System.currentTimeMillis()
        val current = location.getCurrentLocation(store.loadMapSettings().amapWebServiceKey)
        if (!current.isAvailable) {
            if (finalCheck) store.saveLocationExpectation(null)
            return Result.success()
        }

        val distance = distanceMeters(
            expectation.baselineLatitude,
            expectation.baselineLongitude,
            current.latitude,
            current.longitude,
        )
        val currentLabel = current.bestPlaceLabel()
        val arrived = currentLabel?.contains(expectation.destination, ignoreCase = true) == true
        val baselineAccuracy = expectation.baselineAccuracyMeters ?: 999.0
        val currentAccuracy = current.accuracy ?: 999.0
        val stillNearOriginLimit = max(80.0, max(baselineAccuracy * 2.0, currentAccuracy * 2.0))
        val preciseEnough = baselineAccuracy <= 150.0 && currentAccuracy <= 150.0
        val stillAtOrigin = preciseEnough && distance != null && distance <= stillNearOriginLimit

        if (arrived) {
            store.saveLocationExpectation(null)
            return Result.success()
        }
        if (!stillAtOrigin) {
            if (finalCheck) {
                store.saveLocationExpectation(null)
            } else {
                store.saveLocationExpectation(expectation.copy(lastCheckedAt = now))
            }
            return Result.success()
        }
        if (!store.canSendProactive(settings)) return Result.success()

        val elapsed = max(1L, (now - expectation.statedAt) / 60_000L)
        val prompt = buildString {
            append("【行程主动抓包】\n")
            append("小晴约${elapsed}分钟前说：『${expectation.originalStatement}』，目的地是“${expectation.destination}”。\n")
            expectation.baselinePlaceLabel?.let { append("她当时在：$it。\n") }
            currentLabel?.let { append("现在仍显示在：$it。\n") }
            distance?.let { append("当前位置距离出发时约${"%.0f".format(it)}米，两次定位精度正常，基本仍在原地。\n") }
            append("请像男朋友当场抓包一样主动发消息，语气可以凶一点、带调侃，但不要说定位、系统、后台或坐标。")
            append("不要声称你能现实中去找她。只输出一条简短消息。")
        }
        val place = currentLabel ?: expectation.baselinePlaceLabel ?: "原地"
        val generated = generate(
            prompt,
            "不是说去${expectation.destination}？${elapsed}分钟了怎么还在$place。现在解释。",
        )
        if (!store.canSendProactive(settings)) return Result.success()
        if (finalCheck) {
            store.saveLocationExpectation(null)
        } else {
            store.saveLocationExpectation(expectation.copy(lastCheckedAt = now))
        }
        deliver(generated, settings)
        return Result.success()
    }

    private suspend fun generate(prompt: String, fallback: String): String = runCatching {
        api.completeProactive(
            settings = store.loadApiSettings(),
            persona = store.loadPersona(),
            recentHistory = store.loadMessages(),
            triggerPrompt = prompt,
            relationshipProfile = store.loadRelationshipProfile(),
        )
    }.getOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: fallback

    private fun deliver(content: String, settings: ProactiveSettings) {
        val message = store.commitProactiveMessage(content, settings) ?: return
        CompanionNotifications.showMessage(
            context = applicationContext,
            senderName = store.loadPersona().name,
            content = message.content,
            messageId = message.id,
        )
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun LocationSnapshot.bestPlaceLabel(): String? = address?.let {
        it.nearestPoi ?: it.aoi ?: it.building ?: it.neighborhood ?: it.formattedAddress
    }

    private fun distanceMeters(
        lat1: Double?,
        lon1: Double?,
        lat2: Double?,
        lon2: Double?,
    ): Double? {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return null
        val earthRadius = 6_371_000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    companion object {
        const val KEY_TYPE = "type"
        const val KEY_EVENT_ID = "event_id"
        const val TYPE_STUDY_MID = "study_mid"
        const val TYPE_STUDY_END = "study_end"
        const val TYPE_TRAVEL_FIRST = "travel_first"
        const val TYPE_TRAVEL_SECOND = "travel_second"
        private const val MIN_STUDY_EVIDENCE_MS = 5 * 60_000L
        private val generationMutex = Mutex()
    }
}
