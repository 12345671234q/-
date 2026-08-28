package com.csworkout.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class AppPage { MATCHES, MATCH_DETAIL, WORKOUT }

data class WorkoutUiState(
    val page: AppPage = AppPage.MATCHES,
    val loading: Boolean = false,
    val error: String = "",
    val matches: List<ScheduleMatch> = emptyList(),
    val selectedMatch: ScheduleMatch? = null,
    val snapshot: MatchSnapshot? = null,
    val selectedPlayer: PlayerRow? = null,
    val workout: WorkoutPublicState = WorkoutPublicState(),
    val events: List<LiveEvent> = emptyList(),
    val lastSyncAt: Long = 0L,
)

class WorkoutViewModel(app: Application) : AndroidViewModel(app) {
    private val client = FiveEClient()
    private val engine = WorkoutEngine(app)
    var state by mutableStateOf(WorkoutUiState(workout = engine.publicState()))
        private set

    private var monitorJob: Job? = null
    private val seenVersions = mutableSetOf<Long>()
    private var firstKillSeen = false
    private var watchedRoundKills = 0
    private var watchedFirstDeath = false
    private var roundNumber: Int? = null
    private var watchedTeamSlot = ""
    private var watchedSide = ""
    private var previousSnapshot: MatchSnapshot? = null

    init {
        refreshMatches()
        val persisted = engine.publicState()
        if (persisted.enabled && persisted.matchId.isNotBlank() && persisted.player.isNotBlank()) {
            viewModelScope.launch { resumePersisted(persisted.matchId, persisted.player) }
        }
    }

    fun refreshMatches() {
        viewModelScope.launch {
            state = state.copy(loading = true, error = "")
            runCatching { client.schedule(1) }
                .onSuccess { state = state.copy(matches = it, loading = false, lastSyncAt = System.currentTimeMillis()) }
                .onFailure { state = state.copy(loading = false, error = friendlyError(it)) }
        }
    }

    fun openMatch(match: ScheduleMatch) {
        viewModelScope.launch {
            state = state.copy(page = AppPage.MATCH_DETAIL, selectedMatch = match, selectedPlayer = null, loading = true, error = "")
            runCatching { client.snapshot(match.id) }
                .onSuccess { snap -> state = state.copy(snapshot = snap, loading = false, lastSyncAt = System.currentTimeMillis()) }
                .onFailure { state = state.copy(loading = false, error = friendlyError(it)) }
        }
    }

    fun refreshSelectedMatch() {
        val id = state.selectedMatch?.id ?: state.snapshot?.matchId ?: return
        viewModelScope.launch {
            state = state.copy(loading = true, error = "")
            runCatching { client.snapshot(id) }
                .onSuccess { state = state.copy(snapshot = it, loading = false, lastSyncAt = System.currentTimeMillis()) }
                .onFailure { state = state.copy(loading = false, error = friendlyError(it)) }
        }
    }

    fun selectPlayer(player: PlayerRow) { state = state.copy(selectedPlayer = player) }

    fun startWorkout() {
        val matchId = state.snapshot?.matchId ?: state.selectedMatch?.id ?: return
        val player = state.selectedPlayer ?: return
        engine.startSession(matchId, player.name)
        engine.setMapNumber(state.snapshot?.mapNumber ?: 1)
        state = state.copy(page = AppPage.WORKOUT, workout = engine.publicState(), events = emptyList())
        startMonitor(matchId, player.name)
    }

    private suspend fun resumePersisted(matchId: String, playerName: String) {
        runCatching { client.snapshot(matchId) }.onSuccess { snap ->
            val p = snap.players.firstOrNull { sameName(it.name, playerName) }
                ?: PlayerRow("", playerName, "")
            state = state.copy(page = AppPage.WORKOUT, snapshot = snap, selectedPlayer = p, workout = engine.publicState())
            startMonitor(matchId, playerName)
        }
    }

    private fun startMonitor(matchId: String, playerName: String) {
        monitorJob?.cancel()
        seenVersions.clear()
        firstKillSeen = false
        watchedRoundKills = 0
        watchedFirstDeath = false
        roundNumber = null
        previousSnapshot = state.snapshot
        updateWatchedSide(state.snapshot, playerName)
        monitorJob = viewModelScope.launch {
            runCatching { client.eventRows(matchId) }.onSuccess { rows -> rows.forEach { seenVersions += it.updateVersion } }
            var ticks = 0
            while (isActive) {
                ticks++
                runCatching { pollEvents(matchId, playerName) }.onFailure { setBackgroundError(it) }
                if (ticks % 3 == 0) runCatching { pollSnapshot(matchId, playerName) }.onFailure { setBackgroundError(it) }
                delay(1_000)
            }
        }
    }

    private suspend fun pollEvents(matchId: String, playerName: String) {
        val rows = client.eventRows(matchId)
        val fresh = rows.filter { seenVersions.add(it.updateVersion) }
        fresh.sortedBy { it.updateVersion }.forEach { processEvent(it, playerName) }
        if (seenVersions.size > 1500) {
            seenVersions.clear(); rows.forEach { seenVersions += it.updateVersion }
        }
        state = state.copy(workout = engine.publicState(), lastSyncAt = System.currentTimeMillis())
    }

    private suspend fun pollSnapshot(matchId: String, playerName: String) {
        val snap = client.snapshot(matchId)
        val prev = previousSnapshot
        if (prev != null && prev.boutKey.isNotBlank() && snap.boutKey.isNotBlank() && prev.boutKey != snap.boutKey) {
            val watchedWon = mapWinnerForWatched(prev, playerName)
            val otBlocks = overtimeBlocks(prev.team1MapScore, prev.team2MapScore)
            engine.onMapEnd(watchedWon, otBlocks)
            addUiEvent("MAP_END", "Map ${prev.mapNumber} 结束", "${prev.team1.name} ${prev.team1MapScore}:${prev.team2MapScore} ${prev.team2.name}${if (otBlocks > 0) " · 加时 $otBlocks 档" else ""}")
        }
        previousSnapshot = snap
        engine.setMapNumber(snap.mapNumber)
        updateWatchedSide(snap, playerName)
        state = state.copy(snapshot = snap, workout = engine.publicState(), lastSyncAt = System.currentTimeMillis())
    }

    private fun processEvent(row: EventRow, playerName: String) {
        val info = runCatching { JSONObject(row.rawLog) }.getOrNull() ?: return
        val roundStart = info.optJSONObject("round_start")
        if (roundStart != null && (roundStart.has("round_num") || roundStart.has("map") || roundStart.has("bout_num"))) {
            roundNumber = roundStart.optString("round_num").toIntOrNull() ?: roundNumber?.plus(1)
            firstKillSeen = false
            watchedRoundKills = 0
            watchedFirstDeath = false
            addUiEvent("ROUND_START", "第 ${roundNumber ?: "?"} 回合开始", row.mapName)
        }

        val kill = info.optJSONObject("kill")
        if (kill != null) {
            val killer = kill.optString("killer_nick").ifBlank { kill.optString("killer_name") }
            val victim = kill.optString("victim_nick").ifBlank { kill.optString("victim_name") }
            if (killer.isNotBlank() && victim.isNotBlank()) {
                val first = !firstKillSeen
                firstKillSeen = true
                if (sameName(killer, playerName)) {
                    watchedRoundKills++
                    engine.recordKill()
                    addUiEvent("KILL", "$playerName 击杀 $victim", "+2 DU · 击杀")
                }
                if (sameName(victim, playerName) && first) {
                    watchedFirstDeath = true
                    engine.recordFirstDeath()
                    addUiEvent("FIRST_DEATH", "$playerName 成为首死", "连续首死会递增")
                }
            }
        }

        val roundEnd = info.optJSONObject("round_end")
        if (roundEnd != null && roundEnd.has("winner")) {
            val winner = roundEnd.optString("winner")
            val won = watchedSide.isNotBlank() && winner.equals(watchedSide, ignoreCase = true)
            engine.recordRoundEnd(watchedRoundKills, watchedFirstDeath, won)
            val label = when {
                watchedRoundKills >= 5 -> "ACE"
                watchedRoundKills == 4 -> "4K"
                watchedRoundKills == 3 -> "3K"
                watchedRoundKills == 2 -> "2K"
                else -> ""
            }
            addUiEvent(
                if (won) "ROUND_WIN" else "ROUND_END",
                "第 ${roundNumber ?: "?"} 回合${if (won) "获胜" else "结束"}",
                listOf("$watchedRoundKills 杀", if (watchedFirstDeath) "首死" else "", label).filter { it.isNotBlank() }.joinToString(" · "),
            )
            watchedRoundKills = 0
            watchedFirstDeath = false
            firstKillSeen = false
        }
    }

    private fun updateWatchedSide(snap: MatchSnapshot?, playerName: String) {
        if (snap == null) return
        watchedTeamSlot = snap.players.firstOrNull { sameName(it.name, playerName) }?.teamSlot.orEmpty()
        watchedSide = when (watchedTeamSlot) { "t1" -> snap.t1Side; "t2" -> snap.t2Side; else -> "" }
    }

    private fun mapWinnerForWatched(snap: MatchSnapshot, playerName: String): Boolean? {
        val slot = snap.players.firstOrNull { sameName(it.name, playerName) }?.teamSlot ?: watchedTeamSlot
        if (snap.team1MapScore == snap.team2MapScore || slot.isBlank()) return null
        val t1Won = snap.team1MapScore > snap.team2MapScore
        return if (slot == "t1") t1Won else !t1Won
    }

    private fun overtimeBlocks(t1: Int, t2: Int): Int {
        val high = max(t1, t2)
        val low = min(t1, t2)
        if (low < 12 || high <= 13) return 0
        return max(1, ceil((high - 12) / 3.0).toInt())
    }

    fun settle(account: String, item: WorkoutPlanItem, units: Double = item.units) {
        runCatching { engine.settle(account, item.actionId, item.level, units) }
            .onSuccess { paid ->
                addUiEvent("PAY", "完成 ${item.action}", "-${"%.1f".format(paid)} DU")
                state = state.copy(workout = engine.publicState())
            }
            .onFailure { state = state.copy(error = friendlyError(it)) }
    }

    fun setFatigue(account: String, level: Int) {
        engine.setFatigue(account, level.toDouble())
        state = state.copy(workout = engine.publicState())
    }

    fun bankrupt(account: String) {
        engine.bankrupt(account)
        state = state.copy(workout = engine.publicState())
        addUiEvent("BANKRUPT", if (account == "abs") "腹肌账户停牌" else "腿部账户停牌", "剩余债务按 ×1.2 转债")
    }

    fun reopen(account: String) {
        runCatching { engine.reopen(account) }
            .onSuccess {
                state = state.copy(workout = engine.publicState())
                addUiEvent("REOPEN", if (account == "abs") "腹肌账户复牌" else "腿部账户复牌", "尚未偿还的转债按原价回购")
            }
            .onFailure { state = state.copy(error = friendlyError(it)) }
    }

    fun clearError() { state = state.copy(error = "") }

    fun goHome() {
        monitorJob?.cancel(); monitorJob = null
        state = state.copy(page = AppPage.MATCHES, selectedMatch = null, snapshot = null, selectedPlayer = null)
        refreshMatches()
    }

    fun backToMatch() {
        if (state.page == AppPage.WORKOUT) return
        state = state.copy(page = AppPage.MATCHES, selectedMatch = null, snapshot = null, selectedPlayer = null)
    }

    private fun addUiEvent(type: String, title: String, detail: String = "") {
        state = state.copy(events = (listOf(LiveEvent(type, title, detail)) + state.events).take(80))
    }

    private fun setBackgroundError(t: Throwable) {
        val message = friendlyError(t)
        if (message != state.error) state = state.copy(error = message)
    }

    private fun friendlyError(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            raw.contains("Unable to resolve host", ignoreCase = true) -> "网络连不上 5E 数据源，稍后再试。"
            raw.contains("timeout", ignoreCase = true) -> "5E 数据响应超时，正在等下一次刷新。"
            else -> raw.ifBlank { "发生了一个未知错误" }
        }
    }

    private fun sameName(a: String, b: String): Boolean = normalizeName(a) == normalizeName(b)
    private fun normalizeName(v: String): String = v.lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "")
}
