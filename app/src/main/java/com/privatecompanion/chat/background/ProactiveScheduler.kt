package com.privatecompanion.chat.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.privatecompanion.chat.data.PersonalStore
import com.privatecompanion.chat.model.LocationExpectation
import com.privatecompanion.chat.model.StudyExpectation
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

object ProactiveScheduler {
    private const val TAG_STUDY = "proactive-study"
    private const val TAG_TRAVEL = "proactive-travel"

    fun scheduleStudy(context: Context, expectation: StudyExpectation) {
        val settings = PersonalStore(context).loadProactiveSettings()
        cancelStudy(context)
        if (!settings.enabled || !settings.studyChecksEnabled) return

        val now = System.currentTimeMillis()
        val durationMs = expectation.plannedDurationMinutes * 60_000L
        if (expectation.plannedDurationMinutes >= 30) {
            val midOffsetMinutes = min(30, max(10, expectation.plannedDurationMinutes / 3))
            enqueue(
                context = context,
                uniqueName = "study-mid-${expectation.statedAt}",
                tag = TAG_STUDY,
                type = ProactiveCheckWorker.TYPE_STUDY_MID,
                eventId = expectation.statedAt,
                runAt = expectation.plannedStartAt + midOffsetMinutes * 60_000L,
                now = now,
            )
        }
        enqueue(
            context = context,
            uniqueName = "study-end-${expectation.statedAt}",
            tag = TAG_STUDY,
            type = ProactiveCheckWorker.TYPE_STUDY_END,
            eventId = expectation.statedAt,
            runAt = expectation.plannedStartAt + durationMs,
            now = now,
        )
    }

    fun scheduleTravel(context: Context, expectation: LocationExpectation) {
        val settings = PersonalStore(context).loadProactiveSettings()
        cancelTravel(context)
        if (!settings.enabled || !settings.travelChecksEnabled) return

        val now = System.currentTimeMillis()
        enqueue(
            context = context,
            uniqueName = "travel-first-${expectation.statedAt}",
            tag = TAG_TRAVEL,
            type = ProactiveCheckWorker.TYPE_TRAVEL_FIRST,
            eventId = expectation.statedAt,
            runAt = expectation.statedAt + 12 * 60_000L,
            now = now,
        )
        enqueue(
            context = context,
            uniqueName = "travel-second-${expectation.statedAt}",
            tag = TAG_TRAVEL,
            type = ProactiveCheckWorker.TYPE_TRAVEL_SECOND,
            eventId = expectation.statedAt,
            runAt = expectation.statedAt + 30 * 60_000L,
            now = now,
        )
    }

    fun cancelStudy(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG_STUDY)
    }

    fun cancelTravel(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG_TRAVEL)
    }

    fun cancelAll(context: Context) {
        cancelStudy(context)
        cancelTravel(context)
    }

    private fun enqueue(
        context: Context,
        uniqueName: String,
        tag: String,
        type: String,
        eventId: Long,
        runAt: Long,
        now: Long,
    ) {
        val input = Data.Builder()
            .putString(ProactiveCheckWorker.KEY_TYPE, type)
            .putLong(ProactiveCheckWorker.KEY_EVENT_ID, eventId)
            .build()
        val request = OneTimeWorkRequestBuilder<ProactiveCheckWorker>()
            .setInputData(input)
            .setInitialDelay(max(0L, runAt - now), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .addTag(tag)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
    }
}
