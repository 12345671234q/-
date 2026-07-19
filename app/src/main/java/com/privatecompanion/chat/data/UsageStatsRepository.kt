package com.privatecompanion.chat.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.privatecompanion.chat.model.AppUsageEntry
import com.privatecompanion.chat.model.UsageSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/** Reads Android's local app-usage history after the user grants Usage Access. */
class UsageStatsRepository(private val context: Context) {
    private val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
    private val appOpsManager = context.getSystemService(AppOpsManager::class.java)
    private val packageManager = context.packageManager

    fun hasPermission(): Boolean {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    suspend fun queryToday(): UsageSnapshot {
        val now = System.currentTimeMillis()
        val start = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return query(start, now)
    }

    suspend fun query(startAt: Long, endAt: Long = System.currentTimeMillis()): UsageSnapshot =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) {
                return@withContext UsageSnapshot(
                    permissionGranted = false,
                    checkedAt = System.currentTimeMillis(),
                    windowStartAt = startAt,
                    windowEndAt = endAt,
                    error = "\u8bf7\u5148\u5728\u7cfb\u7edf\u8bbe\u7f6e\u4e2d\u5f00\u542f\u201c\u4f7f\u7528\u60c5\u51b5\u8bbf\u95ee\u201d\u3002",
                )
            }

            runCatching {
                val safeStart = startAt.coerceAtMost(endAt)
                val durations = foregroundDurations(safeStart, endAt)
                val entries = durations
                    .asSequence()
                    .filter { (packageName, durationMs) ->
                        packageName != context.packageName && durationMs > MIN_VISIBLE_USAGE_MS
                    }
                    .map { (packageName, durationMs) ->
                        val info = applicationInfo(packageName)
                        val label = applicationLabel(packageName, info)
                        AppUsageEntry(
                            packageName = packageName,
                            appName = label,
                            foregroundMinutes = max(1L, durationMs / 60_000L),
                            distracting = isDistracting(packageName, label, info),
                            foregroundMillis = durationMs,
                        )
                    }
                    .sortedByDescending { it.foregroundMinutes }
                    .toList()

                UsageSnapshot(
                    permissionGranted = true,
                    checkedAt = System.currentTimeMillis(),
                    windowStartAt = safeStart,
                    windowEndAt = endAt,
                    totalForegroundMinutes = entries.sumOf { it.foregroundMinutes },
                    distractingMinutes = entries.filter { it.distracting }.sumOf { it.foregroundMinutes },
                    topApps = entries.take(MAX_TOP_APPS),
                    allApps = entries,
                )
            }.getOrElse { throwable ->
                UsageSnapshot(
                    permissionGranted = true,
                    checkedAt = System.currentTimeMillis(),
                    windowStartAt = startAt,
                    windowEndAt = endAt,
                    error = throwable.message ?: "\u65e0\u6cd5\u8bfb\u53d6\u624b\u673a\u4f7f\u7528\u60c5\u51b5\u3002",
                )
            }
        }

    /**
     * Returns only foreground time added after a saved per-app baseline.  Android's daily
     * usage history is cumulative, so this prevents time spent before a study plan from
     * being attributed to that plan.
     */
    fun sinceBaseline(snapshot: UsageSnapshot, baselineMillisByPackage: Map<String, Long>): UsageSnapshot {
        if (!snapshot.permissionGranted || snapshot.error != null || baselineMillisByPackage.isEmpty()) {
            return snapshot
        }
        val added = snapshot.allApps.mapNotNull { app ->
            val increase = (app.foregroundMillis - baselineMillisByPackage.getOrDefault(app.packageName, 0L))
                .coerceAtLeast(0L)
            if (increase < MIN_VISIBLE_USAGE_MS) null else app.copy(
                foregroundMillis = increase,
                foregroundMinutes = max(1L, increase / 60_000L),
            )
        }.sortedByDescending { it.foregroundMillis }
        return snapshot.copy(
            totalForegroundMinutes = added.sumOf { it.foregroundMinutes },
            distractingMinutes = added.filter { it.distracting }.sumOf { it.foregroundMinutes },
            topApps = added.take(MAX_TOP_APPS),
            allApps = added,
        )
    }

    private fun foregroundDurations(startAt: Long, endAt: Long): Map<String, Long> {
        val totals = mutableMapOf<String, Long>()
        val activeSince = mutableMapOf<String, Long>()
        val events = usageStatsManager.queryEvents(startAt, endAt)
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue
            when {
                isForegroundEvent(event.eventType) -> {
                    activeSince.putIfAbsent(packageName, event.timeStamp.coerceAtLeast(startAt))
                }

                isBackgroundEvent(event.eventType) -> {
                    val startedAt = activeSince.remove(packageName) ?: continue
                    val duration = (event.timeStamp - startedAt).coerceAtLeast(0L)
                    totals[packageName] = totals.getOrDefault(packageName, 0L) + duration
                }
            }
        }

        activeSince.forEach { (packageName, startedAt) ->
            val duration = (endAt - startedAt).coerceAtLeast(0L)
            totals[packageName] = totals.getOrDefault(packageName, 0L) + duration
        }
        return totals
    }

    private fun isForegroundEvent(eventType: Int): Boolean =
        eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                eventType == UsageEvents.Event.ACTIVITY_RESUMED)

    private fun isBackgroundEvent(eventType: Int): Boolean =
        eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                eventType == UsageEvents.Event.ACTIVITY_PAUSED)

    private fun applicationInfo(packageName: String): ApplicationInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
    }.getOrNull()

    private fun applicationLabel(packageName: String, info: ApplicationInfo?): String =
        info?.let { runCatching { packageManager.getApplicationLabel(it).toString() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: packageName.substringAfterLast('.')

    private fun isDistracting(
        packageName: String,
        label: String,
        info: ApplicationInfo?,
    ): Boolean {
        if (info?.category == ApplicationInfo.CATEGORY_GAME ||
            info?.category == ApplicationInfo.CATEGORY_VIDEO ||
            info?.category == ApplicationInfo.CATEGORY_SOCIAL
        ) {
            return true
        }

        val haystack = "$packageName $label".lowercase()
        return DISTRACTION_KEYWORDS.any { haystack.contains(it) }
    }

    private companion object {
        const val MIN_VISIBLE_USAGE_MS = 30_000L
        const val MAX_TOP_APPS = 8

        val DISTRACTION_KEYWORDS = setOf(
            "douyin", "aweme", "tiktok", "kuaishou", "gifmaker", "bilibili", "youtube",
            "instagram", "facebook", "twitter", "x.com", "reddit", "weibo", "xiaohongshu",
            "rednote", "game", "games", "steam", "mihoyo", "hoyoverse", "tencent.tmgp",
            "netease", "video", "shortvideo", "\u6296\u97f3", "\u5feb\u624b", "\u54d4\u54e9\u54d4\u54e9",
            "\u5c0f\u7ea2\u4e66", "\u5fae\u535a", "\u6e38\u620f", "\u89c6\u9891",
        )
    }
}
