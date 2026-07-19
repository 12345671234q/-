package com.privatecompanion.chat

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.privatecompanion.chat.data.LocationRepository
import com.privatecompanion.chat.data.MemoryRepository
import com.privatecompanion.chat.data.EmotionRepository
import com.privatecompanion.chat.data.LocationSnapshot
import com.privatecompanion.chat.data.OpenAiCompatibleClient
import com.privatecompanion.chat.data.PersonalStore
import com.privatecompanion.chat.data.UsageStatsRepository
import com.privatecompanion.chat.background.ProactiveScheduler
import com.privatecompanion.chat.notifications.CompanionNotifications
import com.privatecompanion.chat.model.ApiSettings
import com.privatecompanion.chat.model.ChatMessage
import com.privatecompanion.chat.model.CompanionUiState
import com.privatecompanion.chat.model.DeviceSnapshot
import com.privatecompanion.chat.model.LocationClaimKind
import com.privatecompanion.chat.model.LocationConsistencyContext
import com.privatecompanion.chat.model.LocationExpectation
import com.privatecompanion.chat.model.LongTermMemory
import com.privatecompanion.chat.model.MapSettings
import com.privatecompanion.chat.model.MessageRole
import com.privatecompanion.chat.model.Persona
import com.privatecompanion.chat.model.ProactiveSettings
import com.privatecompanion.chat.model.RelationshipEvent
import com.privatecompanion.chat.model.RelationshipProfile
import com.privatecompanion.chat.model.StudyExpectation
import com.privatecompanion.chat.model.StudyUsageContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class CompanionViewModel(application: Application) : AndroidViewModel(application) {
    private val store = PersonalStore(application)
    private val location = LocationRepository(application)
    private val usage = UsageStatsRepository(application)
    private val memory = MemoryRepository(application)
    private val emotion = EmotionRepository(application)
    private val api = OpenAiCompatibleClient()
    private var lastLocationAttemptAt: Long = 0L
    private var locationExpectation: LocationExpectation? = store.loadLocationExpectation()
    private var studyExpectation: StudyExpectation? = store.loadStudyExpectation()
    private var lastGeneratedContext: Pair<Long, ContextRefreshResult>? = null

    var state: CompanionUiState by mutableStateOf(
        CompanionUiState(
            messages = store.loadMessages(),
            persona = store.loadPersona(),
            apiSettings = store.loadApiSettings(),
            mapSettings = store.loadMapSettings(),
            proactiveSettings = store.loadProactiveSettings(),
            longTermMemoryCount = memory.count(),
            longTermMemories = memory.load(),
            compressedContextSummary = memory.loadConversationSummary(),
            relationshipProfile = store.loadRelationshipProfile(),
        ),
    )
        private set

    init {
        refreshPermissionStates()
        refreshLocation()
        refreshUsageStats()
    }

    fun savePersona(persona: Persona) {
        store.savePersona(persona)
        if (!persona.shareAppUsage) setStudyExpectation(null)
        if (!persona.shareLocation) setLocationExpectation(null)
        state = state.copy(persona = persona, error = null)
    }

    fun saveApiSettings(settings: ApiSettings) {
        store.saveApiSettings(settings)
        state = state.copy(apiSettings = settings, error = null)
    }

    fun saveMapSettings(settings: MapSettings) {
        store.saveMapSettings(settings)
        state = state.copy(mapSettings = settings, error = null)
        refreshLocation()
    }

    fun saveProactiveSettings(settings: ProactiveSettings) {
        store.saveProactiveSettings(settings)
        state = state.copy(proactiveSettings = settings, error = null)
        if (!settings.enabled) {
            ProactiveScheduler.cancelAll(getApplication())
        } else {
            studyExpectation?.let { ProactiveScheduler.scheduleStudy(getApplication(), it) }
            locationExpectation?.let { ProactiveScheduler.scheduleTravel(getApplication(), it) }
        }
    }

    fun syncFromStore() {
        locationExpectation = store.loadLocationExpectation()
        studyExpectation = store.loadStudyExpectation()
        state = state.copy(
            messages = store.loadMessages(),
            proactiveSettings = store.loadProactiveSettings(),
            longTermMemoryCount = memory.count(),
            longTermMemories = memory.load(),
            compressedContextSummary = memory.loadConversationSummary(),
            relationshipProfile = store.loadRelationshipProfile(),
        )
        refreshPermissionStates()
    }

    fun refreshPermissionStates() {
        val app = getApplication<Application>()
        val backgroundLocationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        state = state.copy(
            deviceSnapshot = state.deviceSnapshot.copy(
                notificationsEnabled = CompanionNotifications.canNotify(app),
                backgroundLocationPermissionGranted = backgroundLocationGranted,
            ),
        )
    }

    fun refreshDeviceSnapshot() {
        refreshPermissionStates()
        refreshLocation()
        refreshUsageStats()
    }

    fun refreshUsageStats() {
        viewModelScope.launch {
            val usageSnapshot = usage.queryToday()
            state = state.copy(
                deviceSnapshot = state.deviceSnapshot.copy(usage = usageSnapshot),
            )
        }
    }

    fun refreshLocation() {
        viewModelScope.launch {
            val locSnapshot = location.getCurrentLocation(state.mapSettings.amapWebServiceKey)
            lastLocationAttemptAt = System.currentTimeMillis()
            state = state.copy(
                deviceSnapshot = state.deviceSnapshot.copy(
                    location = locSnapshot,
                    locationPermissionGranted = locSnapshot.isAvailable,
                ),
            )
        }
    }

    private val locationKeywords = setOf(
        "在哪", "哪里", "位置", "定位", "附近", "周边",
        "天气", "导航", "距离", "多远", "这边", "这里",
        "去哪儿", "路线", "地图", "地区", "外面", "出门",
        "回家", "到家", "回去", "打车", "开车", "聚会",
        "喝酒", "酒吧", "夜店", "宿舍", "学校", "公司",
    )

    private val knownPlaceWords = setOf(
        "图书馆", "宿舍", "寝室", "教室", "学校", "学院", "大学", "食堂", "餐厅",
        "公司", "办公室", "实验室", "咖啡馆", "咖啡店", "商场", "超市", "医院",
        "诊所", "健身房", "体育馆", "操场", "酒吧", "夜店", "KTV", "电影院",
        "朋友家", "同学家", "家里", "家", "机场", "火车站", "高铁站", "汽车站",
        "地铁站", "公园", "酒店", "宾馆",
    )

    private fun textMentionsLocation(text: String): Boolean = locationKeywords.any { text.contains(it) }

    private fun isLateNight(): Boolean {
        val hour = LocalTime.now().hour
        return hour >= 22 || hour < 6
    }

    private fun locationIsStale(snapshot: LocationSnapshot, maxAgeMs: Long = LOCATION_REFRESH_INTERVAL_MS): Boolean {
        val timestamp = snapshot.timestamp ?: return true
        val age = max(0L, System.currentTimeMillis() - timestamp)
        return age >= maxAgeMs
    }

    private fun canRetryLocationNow(): Boolean =
        System.currentTimeMillis() - lastLocationAttemptAt >= LOCATION_REFRESH_INTERVAL_MS

    private suspend fun refreshContextForMessage(text: String): ContextRefreshResult {
        var snapshot = state.deviceSnapshot
        val now = System.currentTimeMillis()

        expireOldExpectation(now)
        expireOldStudyExpectation(now)

        var studyUsageContext: StudyUsageContext? = null
        var newlyRecordedStudyPlan: StudyExpectation? = null

        when {
            completesStudyPlan(text) -> {
                val expectation = studyExpectation
                if (state.persona.shareAppUsage && expectation != null) {
                    val result = checkStudyUsage(expectation, snapshot, now, finalCheck = true)
                    snapshot = result.snapshot
                    studyUsageContext = result.context
                }
                setStudyExpectation(null)
            }

            cancelsStudyPlan(text) -> setStudyExpectation(null)

            else -> {
                val parsedStudyPlan = parseStudyPlan(text)
                if (parsedStudyPlan != null) {
                    val baselineUsage = if (state.persona.shareAppUsage) usage.queryToday() else null
                    if (baselineUsage != null) snapshot = snapshot.copy(usage = baselineUsage)
                    newlyRecordedStudyPlan = StudyExpectation(
                        originalStatement = text,
                        statedAt = now,
                        plannedStartAt = now + parsedStudyPlan.startDelayMinutes * 60_000L,
                        plannedDurationMinutes = parsedStudyPlan.durationMinutes,
                        baselineUsageMillisByPackage = baselineUsage
                            ?.takeIf { it.permissionGranted && it.error == null }
                            ?.allApps
                            ?.associate { it.packageName to it.foregroundMillis }
                            .orEmpty(),
                    )
                    setStudyExpectation(newlyRecordedStudyPlan)
                    ProactiveScheduler.scheduleStudy(getApplication(), newlyRecordedStudyPlan)
                } else if (state.persona.shareAppUsage) {
                    val expectation = studyExpectation
                    if (expectation != null && studyCheckIsDue(expectation, now)) {
                        val result = checkStudyUsage(expectation, snapshot, now, finalCheck = false)
                        snapshot = result.snapshot
                        studyUsageContext = result.context
                        val usageReadSucceeded = result.snapshot.usage.permissionGranted &&
                            result.snapshot.usage.error == null
                        val updated = expectation.copy(
                            lastCheckedAt = now,
                            checkCount = expectation.checkCount + if (usageReadSucceeded) 1 else 0,
                        )
                        val planEnd = expectation.plannedStartAt + expectation.plannedDurationMinutes * 60_000L
                        if (updated.checkCount >= MAX_STUDY_CHECKS || now > planEnd + STUDY_GRACE_PERIOD_MS) {
                            setStudyExpectation(null)
                        } else {
                            setStudyExpectation(updated)
                        }
                    }
                }
            }
        }

        if (cancelsLocationPlan(text)) {
            setLocationExpectation(null)
        }

        val claim = parseLocationClaim(text)
        var consistencyContext: LocationConsistencyContext? = null
        var newlyRecordedTravelPlan: LocationExpectation? = null

        if (state.persona.shareLocation && claim != null) {
            snapshot = ensureLocationForClaim(snapshot, claim)
            when (claim.kind) {
                LocationClaimKind.TRAVEL_PLAN -> {
                    newlyRecordedTravelPlan = LocationExpectation(
                        destination = claim.destination,
                        originalStatement = text,
                        claimKind = claim.kind,
                        statedAt = now,
                        baselineLatitude = snapshot.location.latitude,
                        baselineLongitude = snapshot.location.longitude,
                        baselineAccuracyMeters = snapshot.location.accuracy,
                        baselinePlaceLabel = snapshot.location.bestPlaceLabel(),
                    )
                    setLocationExpectation(newlyRecordedTravelPlan)
                    ProactiveScheduler.scheduleTravel(getApplication(), newlyRecordedTravelPlan)
                }

                LocationClaimKind.ARRIVAL, LocationClaimKind.CURRENT_LOCATION -> {
                    consistencyContext = buildConsistencyContext(
                        expectation = LocationExpectation(
                            destination = claim.destination,
                            originalStatement = text,
                            claimKind = claim.kind,
                            statedAt = now,
                        ),
                        current = snapshot.location,
                        checkedAt = now,
                    )
                    setLocationExpectation(null)
                }
            }
        } else if (state.persona.shareLocation) {
            val expectation = locationExpectation
            if (expectation != null && expectationIsDue(expectation, now)) {
                val locSnapshot = location.getCurrentLocation(state.mapSettings.amapWebServiceKey)
                lastLocationAttemptAt = now
                snapshot = snapshot.copy(
                    location = locSnapshot,
                    locationPermissionGranted = locSnapshot.isAvailable,
                )
                if (locSnapshot.isAvailable) {
                    consistencyContext = buildConsistencyContext(expectation, locSnapshot, now)
                    setLocationExpectation(null)
                } else {
                    setLocationExpectation(expectation.copy(lastCheckedAt = now))
                }
            }
        }

        val proactiveLocationCheck = state.persona.shareLocation && isLateNight()
        val shouldFetchGeneralLocation = state.persona.shareLocation &&
            consistencyContext == null && newlyRecordedTravelPlan == null && (
            textMentionsLocation(text) ||
                (proactiveLocationCheck && locationIsStale(snapshot.location) && canRetryLocationNow())
            )

        if (shouldFetchGeneralLocation) {
            val locSnapshot = location.getCurrentLocation(state.mapSettings.amapWebServiceKey)
            lastLocationAttemptAt = System.currentTimeMillis()
            snapshot = snapshot.copy(
                location = locSnapshot,
                locationPermissionGranted = locSnapshot.isAvailable,
            )
        }

        state = state.copy(deviceSnapshot = snapshot)
        return ContextRefreshResult(
            snapshot = snapshot,
            proactiveLocationCheck = proactiveLocationCheck,
            locationConsistencyContext = consistencyContext,
            newlyRecordedTravelPlan = newlyRecordedTravelPlan,
            studyUsageContext = studyUsageContext,
            newlyRecordedStudyPlan = newlyRecordedStudyPlan,
        )
    }

    private suspend fun ensureLocationForClaim(
        snapshot: DeviceSnapshot,
        claim: ParsedLocationClaim,
    ): DeviceSnapshot {
        val maxAge = if (claim.kind == LocationClaimKind.TRAVEL_PLAN) BASELINE_MAX_AGE_MS else 0L
        if (!locationIsStale(snapshot.location, maxAge)) return snapshot
        val locSnapshot = location.getCurrentLocation(state.mapSettings.amapWebServiceKey)
        lastLocationAttemptAt = System.currentTimeMillis()
        return snapshot.copy(
            location = locSnapshot,
            locationPermissionGranted = locSnapshot.isAvailable,
        )
    }

    private fun expectationIsDue(expectation: LocationExpectation, now: Long): Boolean {
        val delay = if (expectation.claimKind == LocationClaimKind.TRAVEL_PLAN) {
            EXPECTATION_MIN_DELAY_MS
        } else {
            0L
        }
        if (now - expectation.statedAt < delay) return false
        val lastCheck = expectation.lastCheckedAt ?: return true
        return now - lastCheck >= EXPECTATION_RETRY_DELAY_MS
    }

    private fun expireOldExpectation(now: Long) {
        val expectation = locationExpectation ?: return
        if (now - expectation.statedAt > EXPECTATION_EXPIRY_MS) {
            setLocationExpectation(null)
        }
    }

    private fun setLocationExpectation(expectation: LocationExpectation?) {
        locationExpectation = expectation
        store.saveLocationExpectation(expectation)
        if (expectation == null) ProactiveScheduler.cancelTravel(getApplication())
    }

    private fun buildConsistencyContext(
        expectation: LocationExpectation,
        current: LocationSnapshot,
        checkedAt: Long,
    ): LocationConsistencyContext {
        val distance = distanceMeters(
            expectation.baselineLatitude,
            expectation.baselineLongitude,
            current.latitude,
            current.longitude,
        )
        return LocationConsistencyContext(
            destination = expectation.destination,
            originalStatement = expectation.originalStatement,
            claimKind = expectation.claimKind,
            statedAt = expectation.statedAt,
            checkedAt = checkedAt,
            elapsedMinutes = max(0L, (checkedAt - expectation.statedAt) / 60_000L),
            baselinePlaceLabel = expectation.baselinePlaceLabel,
            baselineAccuracyMeters = expectation.baselineAccuracyMeters,
            distanceFromBaselineMeters = distance,
            currentLocationSummary = current.summaryForPrompt(),
            locationAccuracyMeters = current.accuracy,
        )
    }

    private fun studyCheckIsDue(expectation: StudyExpectation, now: Long): Boolean {
        if (expectation.checkCount >= MAX_STUDY_CHECKS) return false
        if (now < expectation.plannedStartAt + STUDY_MIN_CHECK_DELAY_MS) return false
        val lastCheck = expectation.lastCheckedAt ?: return true
        return now - lastCheck >= STUDY_RECHECK_INTERVAL_MS
    }

    private fun expireOldStudyExpectation(now: Long) {
        val expectation = studyExpectation ?: return
        val planEnd = expectation.plannedStartAt + expectation.plannedDurationMinutes * 60_000L
        if (now > planEnd + STUDY_EXPECTATION_EXPIRY_MS) {
            setStudyExpectation(null)
        }
    }

    private fun setStudyExpectation(expectation: StudyExpectation?) {
        studyExpectation = expectation
        store.saveStudyExpectation(expectation)
        if (expectation == null) ProactiveScheduler.cancelStudy(getApplication())
    }

    private suspend fun checkStudyUsage(
        expectation: StudyExpectation,
        snapshot: DeviceSnapshot,
        checkedAt: Long,
        finalCheck: Boolean,
    ): StudyCheckResult {
        val usageSnapshot = if (expectation.baselineUsageMillisByPackage.isEmpty()) {
            usage.query(expectation.plannedStartAt, checkedAt)
        } else {
            usage.sinceBaseline(usage.queryToday(), expectation.baselineUsageMillisByPackage)
        }
        val updatedSnapshot = snapshot.copy(usage = usageSnapshot)
        if (!usageSnapshot.permissionGranted || usageSnapshot.error != null) {
            return StudyCheckResult(updatedSnapshot, null)
        }

        val elapsedMinutes = max(1L, (checkedAt - expectation.plannedStartAt) / 60_000L)
        val minimumDistractingMinutes = max(3L, elapsedMinutes / 5L)
        val hasSuspiciousUsage = usageSnapshot.distractingMinutes >= minimumDistractingMinutes
        if (!hasSuspiciousUsage) {
            return StudyCheckResult(updatedSnapshot, null)
        }

        val strongEvidence = usageSnapshot.distractingMinutes >= max(8L, elapsedMinutes / 3L)
        return StudyCheckResult(
            snapshot = updatedSnapshot,
            context = StudyUsageContext(
                originalStatement = expectation.originalStatement,
                plannedStartAt = expectation.plannedStartAt,
                plannedDurationMinutes = expectation.plannedDurationMinutes,
                checkedAt = checkedAt,
                elapsedMinutes = elapsedMinutes,
                totalForegroundMinutes = usageSnapshot.totalForegroundMinutes,
                distractingMinutes = usageSnapshot.distractingMinutes,
                topApps = usageSnapshot.topApps.take(5),
                strongEvidence = strongEvidence,
                finalCheck = finalCheck,
            ),
        )
    }

    private fun parseStudyPlan(text: String): ParsedStudyPlan? {
        val compact = text.replace(" ", "")
        if (cancelsStudyPlan(compact) || completesStudyPlan(compact)) return null
        if (listOf("\u4e0d\u60f3\u5b66", "\u4e0d\u60f3\u590d\u4e60", "\u61d2\u5f97\u5b66", "\u4e0d\u613f\u610f\u5b66").any { compact.contains(it) }) {
            return null
        }

        val studyWords = listOf("\u5b66\u4e60", "\u81ea\u4e60", "\u590d\u4e60", "\u770b\u4e66", "\u5199\u4f5c\u4e1a", "\u5237\u9898", "\u505a\u9898", "\u80cc\u4e66")
        if (studyWords.none { compact.contains(it) }) return null

        val looksLikePlan = listOf("\u6211\u53bb", "\u53bb", "\u5f00\u59cb", "\u51c6\u5907", "\u6253\u7b97", "\u6211\u8981", "\u9a6c\u4e0a", "\u73b0\u5728", "\u5f85\u4f1a", "\u7b49\u4f1a", "\u4e00\u4f1a")
            .any { compact.contains(it) } || DURATION_PATTERN.containsMatchIn(compact)
        if (!looksLikePlan) return null

        var startDelayMinutes = when {
            compact.contains("\u5f85\u4f1a") || compact.contains("\u7b49\u4f1a") || compact.contains("\u4e00\u4f1a") -> 10
            else -> 0
        }
        var durationMinutes: Int? = null

        DURATION_PATTERN.findAll(compact).forEach { match ->
            val numberToken = match.groupValues[1]
            val unit = match.groupValues[2]
            val following = compact.substring(match.range.last + 1)
            val amount = parseNumberToken(numberToken, unit) ?: return@forEach
            if (following.startsWith("\u540e")) {
                startDelayMinutes = amount
            } else if (durationMinutes == null) {
                durationMinutes = amount
            }
        }

        return ParsedStudyPlan(
            startDelayMinutes = startDelayMinutes.coerceIn(0, 240),
            durationMinutes = (durationMinutes ?: DEFAULT_STUDY_DURATION_MINUTES).coerceIn(10, 480),
        )
    }

    private fun parseNumberToken(token: String, unit: String): Int? {
        if (token == "\u534a") return if (unit == "\u5c0f\u65f6") 30 else null
        val value = token.toIntOrNull() ?: parseChineseNumber(token) ?: return null
        return if (unit == "\u5c0f\u65f6") value * 60 else value
    }

    private fun parseChineseNumber(token: String): Int? {
        val digits = mapOf(
            '\u96f6' to 0, '\u4e00' to 1, '\u4e8c' to 2, '\u4e24' to 2, '\u4e09' to 3,
            '\u56db' to 4, '\u4e94' to 5, '\u516d' to 6, '\u4e03' to 7, '\u516b' to 8, '\u4e5d' to 9,
        )
        if (token.length == 1) return digits[token[0]]
        if (token == "\u5341") return 10
        if ('\u5341' in token) {
            val parts = token.split('\u5341')
            val tens = parts.first().takeIf { it.isNotBlank() }?.firstOrNull()?.let(digits::get) ?: 1
            val ones = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.firstOrNull()?.let(digits::get) ?: 0
            return tens * 10 + ones
        }
        return null
    }

    private fun cancelsStudyPlan(text: String): Boolean {
        val compact = text.replace(" ", "")
        return listOf(
            "\u4e0d\u5b66\u4e86", "\u4e0d\u5b66\u4e60\u4e86", "\u4e0d\u590d\u4e60\u4e86", "\u4eca\u5929\u4e0d\u5b66", "\u5148\u4e0d\u5b66",
            "\u53d6\u6d88\u5b66\u4e60", "\u4e0d\u5199\u4f5c\u4e1a\u4e86", "\u6539\u5929\u518d\u5b66",
        ).any { compact.contains(it) }
    }

    private fun completesStudyPlan(text: String): Boolean {
        val compact = text.replace(" ", "")
        return listOf(
            "\u5b66\u5b8c\u4e86", "\u5b66\u4e60\u5b8c\u4e86", "\u590d\u4e60\u5b8c\u4e86", "\u770b\u5b8c\u4e86", "\u5199\u5b8c\u4f5c\u4e1a",
            "\u4f5c\u4e1a\u5199\u5b8c\u4e86", "\u5237\u5b8c\u9898", "\u505a\u5b8c\u9898", "\u7ed3\u675f\u5b66\u4e60",
        ).any { compact.contains(it) }
    }

    private fun parseLocationClaim(text: String): ParsedLocationClaim? {
        val compact = text.replace(" ", "")
        if (cancelsLocationPlan(compact)) return null

        val arrivalPatterns = listOf(
            Regex("(?:我)?(?:已经|刚刚|刚)?(?:到了|到达了|到达)([^，。！？,.!?\n]{1,24})"),
            Regex("(?:我)?(?:已经|刚刚|刚)?到([^，。！？,.!?\n]{1,20})(?:了|啦|咯)$"),
        )
        arrivalPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(compact)?.groupValues?.getOrNull(1)?.let(::cleanDestination)
        }?.let { return ParsedLocationClaim(it, LocationClaimKind.ARRIVAL) }

        val currentPattern = Regex("(?:我)?(?:现在|目前)?在([^，。！？,.!?\\n]{1,20})")
        currentPattern.find(compact)?.groupValues?.getOrNull(1)?.let(::cleanDestination)?.let { destination ->
            if (knownPlaceWords.any { destination.contains(it) }) {
                return ParsedLocationClaim(destination, LocationClaimKind.CURRENT_LOCATION)
            }
        }

        val travelPatterns = listOf(
            Regex("(?:我)?(?:现在|马上|待会儿|待会|等会儿|等会|一会儿|一会|晚点|准备|打算|想|要|得)?(?:去|前往)([^，。！？,.!?\\n]{1,24})"),
            Regex("(?:我)?(?:现在|马上|准备|打算|要)?到([^，。！？,.!?\\n]{1,24})去"),
        )
        travelPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(compact)?.groupValues?.getOrNull(1)?.let(::cleanDestination)
        }?.let { return ParsedLocationClaim(it, LocationClaimKind.TRAVEL_PLAN) }

        return null
    }

    private fun cleanDestination(raw: String): String? {
        var destination = raw.trim().trim('，', '。', '！', '？', ',', '.', '!', '?')
        val suffixes = listOf(
            "的路上", "学习", "自习", "看书", "写作业", "上课", "吃饭", "逛逛", "逛", "玩玩",
            "玩", "办事", "见朋友", "找朋友", "待着", "待会", "一下", "一趟", "然后",
            "呢", "啊", "呀", "啦", "吧", "咯", "了",
        )
        val cutAt = suffixes.map { destination.indexOf(it) }.filter { it > 0 }.minOrNull()
        if (cutAt != null) destination = destination.substring(0, cutAt)
        destination = destination.trim().trimEnd('的')

        val invalid = setOf(
            "", "哪", "哪里", "哪儿", "什么地方", "干嘛", "吃饭", "睡觉", "洗澡", "学习",
            "自习", "玩", "看看", "一下", "一趟", "上课", "时候", "到处",
        )
        if (destination in invalid || destination.length > 20) return null
        return destination
    }

    private fun cancelsLocationPlan(text: String): Boolean {
        val compact = text.replace(" ", "")
        return listOf("不去了", "不去", "没去", "取消了", "取消行程", "改天再去", "不打算去", "不准备去")
            .any { compact.contains(it) }
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

    fun send(text: String) {
        val cleanText = text.trim()
        if (cleanText.isBlank() || state.isSending) return

        val userMessage = ChatMessage(
            id = System.nanoTime(),
            role = MessageRole.USER,
            content = cleanText,
        )
        val messagesWithUser = state.messages + userMessage
        val currentEmotion = emotion.observeUserMessage(cleanText, userMessage.createdAt)
        val responseTemperature = emotion.dynamicTemperature(cleanText, currentEmotion)
        val memoryNotice = handleExplicitMemoryCommand(cleanText)
        store.saveMessages(messagesWithUser)
        state = state.copy(
            messages = messagesWithUser,
            longTermMemoryCount = memory.count(),
            longTermMemories = memory.load(),
            memoryNotice = memoryNotice,
            isSending = true,
            error = null,
        )

        viewModelScope.launch {
            val requestStartedAt = System.currentTimeMillis()
            val context = refreshContextForMessage(cleanText)
            lastGeneratedContext = userMessage.id to context
            val relevantMemories = memory.relevantTo(cleanText)

            runCatching {
                api.complete(
                    settings = state.apiSettings,
                    persona = state.persona,
                    history = messagesWithUser,
                    deviceSnapshot = context.snapshot,
                    proactiveLocationCheck = context.proactiveLocationCheck,
                    locationConsistencyContext = context.locationConsistencyContext,
                    newlyRecordedTravelPlan = context.newlyRecordedTravelPlan,
                    studyUsageContext = context.studyUsageContext,
                    newlyRecordedStudyPlan = context.newlyRecordedStudyPlan,
                    longTermMemories = relevantMemories,
                    compressedContextSummary = memory.loadConversationSummary(),
                    relationshipProfile = state.relationshipProfile,
                    emotionState = currentEmotion,
                    temperature = responseTemperature,
                )
            }.onSuccess { reply ->
                val elapsedSeconds = ((System.currentTimeMillis() - requestStartedAt + 500L) / 1_000L).coerceAtLeast(1L)
                val updated = messagesWithUser + ChatMessage(
                    id = System.nanoTime(),
                    role = MessageRole.ASSISTANT,
                    content = reply.content,
                    reasoning = reply.reasoning,
                    reasoningDurationSeconds = elapsedSeconds.takeIf { !reply.reasoning.isNullOrBlank() },
                )
                store.saveMessages(updated)
                state = state.copy(messages = updated, isSending = false)
                extractLongTermMemoriesIfDue(updated)
                compressContextIfDue(updated)
                // The travel build does not let model-generated relationship analysis create
                // factual events. Relationship tone remains configured, while history comes
                // only from confirmed timeline memory.
            }.onFailure { throwable ->
                state = state.copy(
                    isSending = false,
                    error = throwable.message ?: "发送失败，请检查网络和接口配置。",
                )
            }
        }
    }

    fun regenerateLastReply() {
        if (state.isSending) return
        val originalMessages = state.messages
        if (originalMessages.lastOrNull()?.role != MessageRole.ASSISTANT) return
        val historyWithoutReply = originalMessages.dropLast(1)
        val lastUserMessage = historyWithoutReply.lastOrNull()?.takeIf { it.role == MessageRole.USER } ?: return

        store.saveMessages(historyWithoutReply)
        state = state.copy(messages = historyWithoutReply, isSending = true, error = null)

        viewModelScope.launch {
            val requestStartedAt = System.currentTimeMillis()
            val context = lastGeneratedContext
                ?.takeIf { it.first == lastUserMessage.id }
                ?.second
                ?: refreshContextForMessage(lastUserMessage.content)
            lastGeneratedContext = lastUserMessage.id to context
            val relevantMemories = memory.relevantTo(lastUserMessage.content)
            val currentEmotion = emotion.load()

            runCatching {
                api.complete(
                    settings = state.apiSettings,
                    persona = state.persona,
                    history = historyWithoutReply,
                    deviceSnapshot = context.snapshot,
                    proactiveLocationCheck = context.proactiveLocationCheck,
                    locationConsistencyContext = context.locationConsistencyContext,
                    newlyRecordedTravelPlan = context.newlyRecordedTravelPlan,
                    studyUsageContext = context.studyUsageContext,
                    newlyRecordedStudyPlan = context.newlyRecordedStudyPlan,
                    longTermMemories = relevantMemories,
                    compressedContextSummary = memory.loadConversationSummary(),
                    relationshipProfile = state.relationshipProfile,
                    emotionState = currentEmotion,
                    temperature = emotion.dynamicTemperature(lastUserMessage.content, currentEmotion),
                )
            }.onSuccess { reply ->
                val elapsedSeconds = ((System.currentTimeMillis() - requestStartedAt + 500L) / 1_000L).coerceAtLeast(1L)
                val updated = historyWithoutReply + ChatMessage(
                    id = System.nanoTime(),
                    role = MessageRole.ASSISTANT,
                    content = reply.content,
                    reasoning = reply.reasoning,
                    reasoningDurationSeconds = elapsedSeconds.takeIf { !reply.reasoning.isNullOrBlank() },
                )
                store.saveMessages(updated)
                state = state.copy(messages = updated, isSending = false)
            }.onFailure { throwable ->
                store.saveMessages(originalMessages)
                state = state.copy(
                    messages = originalMessages,
                    isSending = false,
                    error = throwable.message ?: "重新生成失败，请检查网络和接口配置。",
                )
            }
        }
    }

    fun clearHistory() {
        store.clearMessages()
        setLocationExpectation(null)
        setStudyExpectation(null)
        lastGeneratedContext = null
        memory.clearConversationSummary()
        ProactiveScheduler.cancelAll(getApplication())
        state = state.copy(messages = emptyList(), compressedContextSummary = "", error = null)
    }

    fun clearLongTermMemories() {
        memory.clear()
        state = state.copy(longTermMemoryCount = 0, longTermMemories = emptyList(), error = null)
    }

    fun addMemory(content: String, layer: String, pinned: Boolean, occurredOn: String) {
        memory.addManual(content, layer, pinned, parseOccurredDate(occurredOn))
        refreshMemoryState("已添加长期记忆")
    }

    fun updateMemory(id: Long, content: String, layer: String, pinned: Boolean, occurredOn: String) {
        memory.update(id, content, layer, pinned, parseOccurredDate(occurredOn))
        refreshMemoryState("已更新并确认长期记忆")
    }

    private fun parseOccurredDate(value: String): Long? = value.trim().takeIf { it.isNotBlank() }?.let { raw ->
        runCatching {
            LocalDate.parse(raw).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    fun deleteMemory(id: Long) {
        memory.delete(id)
        refreshMemoryState("已删除长期记忆")
    }

    /** Portable, API-key-free backup for merging the travel build into the final app. */
    fun exportPortableBackup(): String {
        val messages = JSONArray().apply {
            state.messages.forEach { message ->
                put(JSONObject()
                    .put("id", message.id)
                    .put("role", message.role.name)
                    .put("content", message.content)
                    .put("createdAt", message.createdAt)
                    .put("proactive", message.proactive))
            }
        }
        val persona = JSONObject()
            .put("name", state.persona.name)
            .put("instructions", state.persona.instructions)
            .put("matureMode", state.persona.matureMode)
            .put("matureInstructions", state.persona.matureInstructions)
        return JSONObject()
            .put("format", "jimo-portable-backup")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("messages", messages)
            .put("persona", persona)
            .put("api", JSONObject()
                .put("baseUrl", state.apiSettings.baseUrl)
                .put("model", state.apiSettings.model)
                .put("apiKeyIncluded", false))
            .put("timelineMemory", memory.exportJson())
            .put("emotionState", emotion.exportJson())
            .toString(2)
    }

    private fun refreshMemoryState(notice: String? = null) {
        state = state.copy(
            longTermMemoryCount = memory.count(),
            longTermMemories = memory.load(),
            compressedContextSummary = memory.loadConversationSummary(),
            memoryNotice = notice,
        )
    }

    fun clearMemoryNotice() {
        state = state.copy(memoryNotice = null)
    }

    private fun handleExplicitMemoryCommand(text: String): String? {
        val compact = text.trim()
        val forgetMarkers = listOf("忘记", "请忘记", "别再记得", "不要再记", "删掉这条记忆")
        forgetMarkers.firstOrNull { compact.contains(it) }?.let { marker ->
            val after = cleanMemoryFact(compact.substringAfter(marker))
            val before = cleanMemoryFact(compact.substringBefore(marker))
            val target = after.takeIf { it.length >= 2 } ?: before
            if (target.isNotBlank()) {
                memory.forgetMatching(target)
                return "已按你的要求删除相关长期记忆"
            }
        }

        val rememberMarkers = listOf("帮我记住", "请记住", "长期记住", "一定要记住", "要记住", "记住", "别忘了")
        val marker = rememberMarkers.firstOrNull { compact.contains(it) } ?: return null
        val after = cleanMemoryFact(compact.substringAfter(marker))
        val before = cleanMemoryFact(compact.substringBefore(marker))
        val fact = when {
            after.length >= 4 -> after
            before.length >= 4 -> before
            else -> return null
        }
        if (fact.length < 4) return null
        memory.saveExplicit(fact)
        return "已加入长期记忆：${fact.take(36)}"
    }

    private fun cleanMemoryFact(raw: String): String = raw
        .trim('：', ':', '，', ',', '。', ' ', '！', '!', '？', '?')
        .replace(Regex("[，,]?(这件事|这点|这个)?(你|寂墨)?$"), "")
        .removePrefix("把")
        .trim('：', ':', '，', ',', '。', ' ')

    private suspend fun extractLongTermMemoriesIfDue(history: List<ChatMessage>) {
        val lastExtracted = memory.lastExtractionAt()
        val userMessagesSinceLastExtraction = history.count { it.role == MessageRole.USER && it.createdAt > lastExtracted }
        if (userMessagesSinceLastExtraction < MEMORY_EXTRACTION_MESSAGE_INTERVAL) return
        val drafts = api.extractMemoryDrafts(state.apiSettings, history, memory.load())
        memory.saveDrafts(drafts)
        memory.markExtracted()
        refreshMemoryState(if (drafts.isNotEmpty()) "发现 ${drafts.size} 条记忆候选，请到长期记忆中确认" else null)
    }

    private suspend fun compressContextIfDue(history: List<ChatMessage>) {
        if (history.size <= RECENT_CONTEXT_MESSAGES + CONTEXT_COMPRESSION_BATCH) return
        val candidates = history.dropLast(RECENT_CONTEXT_MESSAGES)
            .filter { it.createdAt > memory.compressedUntil() }
        if (candidates.size < CONTEXT_COMPRESSION_BATCH) return
        val summary = api.compressConversationContext(
            settings = state.apiSettings,
            previousSummary = "",
            messages = candidates,
        )
        if (summary.isNotBlank()) {
            memory.saveConversationSummary(summary, candidates.last().createdAt)
            refreshMemoryState()
        }
    }

    /** Relationship progress is computed locally; there is intentionally no public score setter. */
    private suspend fun advanceRelationship(history: List<ChatMessage>) {
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toEpochDay()
        var profile = state.relationshipProfile
        val oldStage = profile.stage
        val gainToday = if (profile.dailyGainDay == today) profile.dailyGain else 0
        profile = profile.copy(
            firstChatAt = profile.firstChatAt.takeIf { it > 0L } ?: now,
            lastInteractionAt = now,
            dailyGainDay = today,
            dailyGain = gainToday,
        )

        val userMessagesSinceEvaluation = history.count {
            it.role == MessageRole.USER && it.createdAt > profile.lastEvaluatedAt
        }
        profile = profile.copy(interactionCount = profile.interactionCount + userMessagesSinceEvaluation)
        var newestEvent: String? = null
        if (userMessagesSinceEvaluation >= RELATIONSHIP_EVALUATION_INTERVAL) {
            val analysis = api.analyzeRelationship(state.apiSettings, profile, history)
            val remaining = (RELATIONSHIP_DAILY_GAIN_LIMIT - profile.dailyGain).coerceAtLeast(0)
            val existingNormalized = profile.events.map { it.content.lowercase().replace(Regex("\\s+"), "") }.toSet()
            val acceptedSignals = analysis.signals.filter { signal ->
                val candidate = signal.description.lowercase().replace(Regex("\\s+"), "")
                candidate.isNotBlank() && existingNormalized.none { old -> old.contains(candidate) || candidate.contains(old) }
            }.take(3)
            val approvedExtraGain = acceptedSignals.sumOf { relationshipGainFor(it.type) }.coerceAtMost(remaining)
            var trustGain = 0
            var intimacyGain = 0
            acceptedSignals.forEach {
                when (it.type) {
                    "DEEP_TALK", "PROMISE_KEPT", "APOLOGY_ACCEPTED" -> trustGain += 2
                    "MUTUAL_FLIRT", "NICKNAME_ACCEPTED" -> intimacyGain += 2
                }
            }
            val newEvents = acceptedSignals.map { signal ->
                RelationshipEvent(
                    id = System.nanoTime() + signal.description.hashCode(),
                    content = signal.description.take(80),
                    createdAt = now,
                    type = signal.type,
                )
            }
            newestEvent = newEvents.lastOrNull()?.content
            profile = profile.copy(
                affection = (profile.affection + approvedExtraGain).coerceAtMost(1000),
                trust = (profile.trust + trustGain).coerceAtMost(500),
                intimacy = (profile.intimacy + intimacyGain).coerceAtMost(500),
                dailyGain = profile.dailyGain + approvedExtraGain,
                lastEvaluatedAt = now,
                events = (profile.events + newEvents).takeLast(30),
            )
        }
        store.saveRelationshipProfile(profile)
        val notice = when {
            profile.stage != oldStage -> "关系进展：进入「${profile.stage.label}」阶段"
            newestEvent != null -> "关系发生了一点变化：${newestEvent.take(32)}"
            else -> state.memoryNotice
        }
        state = state.copy(relationshipProfile = profile, memoryNotice = notice)
    }

    private fun relationshipGainFor(type: String): Int = when (type) {
        "DEEP_TALK" -> 2
        "USER_SHOWED_CARE" -> 1
        "PROMISE_KEPT" -> 2
        "APOLOGY_ACCEPTED" -> 1
        "NICKNAME_ACCEPTED" -> 1
        "MUTUAL_FLIRT" -> 1
        "RELATIONSHIP_CONFIRMED" -> 2
        else -> 0
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    private data class ParsedLocationClaim(
        val destination: String,
        val kind: LocationClaimKind,
    )

    private data class ParsedStudyPlan(
        val startDelayMinutes: Int,
        val durationMinutes: Int,
    )

    private data class StudyCheckResult(
        val snapshot: DeviceSnapshot,
        val context: StudyUsageContext?,
    )

    private data class ContextRefreshResult(
        val snapshot: DeviceSnapshot,
        val proactiveLocationCheck: Boolean,
        val locationConsistencyContext: LocationConsistencyContext?,
        val newlyRecordedTravelPlan: LocationExpectation?,
        val studyUsageContext: StudyUsageContext?,
        val newlyRecordedStudyPlan: StudyExpectation?,
    )

    private companion object {
        const val MEMORY_EXTRACTION_MESSAGE_INTERVAL = 12
        const val RECENT_CONTEXT_MESSAGES = 36
        const val CONTEXT_COMPRESSION_BATCH = 18
        const val RELATIONSHIP_EVALUATION_INTERVAL = 4
        const val RELATIONSHIP_DAILY_GAIN_LIMIT = 20
        const val LOCATION_REFRESH_INTERVAL_MS = 10 * 60 * 1000L
        const val BASELINE_MAX_AGE_MS = 2 * 60 * 1000L
        const val EXPECTATION_MIN_DELAY_MS = 3 * 60 * 1000L
        const val EXPECTATION_RETRY_DELAY_MS = 10 * 60 * 1000L
        const val EXPECTATION_EXPIRY_MS = 6 * 60 * 60 * 1000L
        const val DEFAULT_STUDY_DURATION_MINUTES = 60
        const val MAX_STUDY_CHECKS = 4
        const val STUDY_MIN_CHECK_DELAY_MS = 5 * 60 * 1000L
        const val STUDY_RECHECK_INTERVAL_MS = 15 * 60 * 1000L
        const val STUDY_GRACE_PERIOD_MS = 30 * 60 * 1000L
        const val STUDY_EXPECTATION_EXPIRY_MS = 2 * 60 * 60 * 1000L
        val DURATION_PATTERN = Regex("([0-9\u96f6\u4e00\u4e8c\u4e24\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341]+|\u534a)(?:\u4e2a)?(\u5c0f\u65f6|\u5206\u949f)")
    }
}
