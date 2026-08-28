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
import java.io.Closeable
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
    val liveTransport: String = "",
    val lastSyncAt: Long = 0L,
)

class WorkoutViewModel(app: Application) : AndroidViewModel(app) {
    private val client = FiveEClient()
    private val mqttClient = FiveEMqttEventClient()
    private val engine = WorkoutEngine(app)
    var state by mutableStateOf(WorkoutUiState(workout = engine.publicState()))
        private set

    private var monitorJob: Job? = null
    private var previewJob: Job? = null
    private var eventStream: Closeable? = null
    private val seenVersions = mutableSetOf<Long>()
    private var mqttConnected = false
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
        stopPreview()
        viewModelScope.launch {
            state = state.copy(page = AppPage.MATCH_DETAIL, selectedMatch = match, selectedPlayer = null, loading = true, error = "", liveTransport = "正在读取5E实时状态…")
            runCatching { client.snapshot(match.id) }
                .onSuccess { snap ->
                    state = state.copy(snapshot = snap, loading = false, lastSyncAt = System.currentTimeMillis(), liveTransport = "详情自动刷新中")
                    startPreview(match.id)
                }
                .onFailure { state = state.copy(loading = false, error = friendlyError(it), liveTransport = "等待5E数据") }
        }
    }

    private fun startPreview(matchId: String) {
        stopPreview()
        previewJob = viewModelScope.launch {
            while (isActive && state.page == AppPage.MATCH_DETAIL) {
                delay(1_500)
                runCatching { client.snapshot(matchId) }
                    .onSuccess { snap ->
                        val oldSelected = state.selectedPlayer
                        val selected = oldSelected?.let { old -> snap.players.firstOrNull { sameName(it.name, old.name) } ?: old }
                        state = state.copy(
                            snapshot = snap,
                            selectedPlayer = selected,
                            lastSyncAt = System.currentTimeMillis(),
                            liveTransport = "详情自动刷新中",
                        )
                    }
                    .onFailure { setPassiveNetworkState(it) }
            }
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
    }

    fun refreshSelectedMatch() {
        val id = state.selectedMatch?.id ?: state.snapshot?.matchId ?: return
        viewModelScope.launch {
            state = state.copy(loading = true, error = "")
            runCatching { client.snapshot(id) }
                .onSuccess { snap ->
                    val oldSelected = state.selectedPlayer
                    val selected = oldSelected?.let { old -> snap.players.firstOrNull { sameName(it.name, old.name) } ?: old }
                    state = state.copy(snapshot = snap, selectedPlayer = selected, loading = false, lastSyncAt = System.currentTimeMillis())
                }
                .onFailure { state = state.copy(loading = false, error = friendlyError(it)) }
        }
    }

    fun selectPlayer(player: PlayerRow) { state = state.copy(selectedPlayer = player) }

    fun startWorkout() {
        val matchId = state.snapshot?.matchId ?: state.selectedMatch?.id ?: return
        val player = state.selectedPlayer ?: return
        stopPreview()
        engine.startSession(matchId, player.name)
        engine.setMapNumber(state.snapshot?.mapNumber ?: 1)
        state = state.copy(page = AppPage.WORKOUT, workout = engine.publicState(), events = emptyList(), liveTransport = "正在建立实时推送…")
        startMonitor(matchId, player.name)
    }

    private suspend fun resumePersisted(matchId: String, playerName: String) {
        runCatching { client.snapshot(matchId) }.onSuccess { snap ->
            val p = snap.players.firstOrNull { sameName(it.name, playerName) }
                ?: PlayerRow("", playerName, "")
            state = state.copy(page = AppPage.WORKOUT, snapshot = snap, selectedPlayer = p, workout = engine.publicState(), liveTransport = "正在恢复实时监听…")
            startMonitor(matchId, playerName)
        }
    }

    private fun startMonitor(matchId: String, playerName: String) {
        monitorJob?.cancel()
        eventStream?.close()
        eventStream = null
        seenVersions.clear()
        mqttConnected = false
        firstKillSeen = false
        watchedRoundKills = 0
        watchedFirstDeath = false
        roundNumber = state.snapshot?.currentRound
        previousSnapshot = state.snapshot
        updateWatchedSide(state.snapshot, playerName)

        monitorJob = viewModelScope.launch {
            val baseline = runCatching { client.eventRows(matchId, 60) }.getOrElse { emptyList() }
            baseline.forEach { seenVersions += it.updateVersion }
            seedRoundContext(baseline, playerName)

            eventStream = mqttClient.subscribe(
                matchId = matchId,
                onEvent = { row ->
                    viewModelScope.launch {
                        if (seenVersions.add(row.updateVersion)) {
                            processEvent(row, playerName)
                            state = state.copy(workout = engine.publicState(), lastSyncAt = System.currentTimeMillis())
                        }
                    }
                },
                onStatus = { connected, label ->
                    viewModelScope.launch {
                        mqttConnected = connected
                        state = state.copy(liveTransport = label)
                    }
                },
            )

            var ticks = 0
            while (isActive) {
                ticks++
                // MQTT is the fast path. HTTP periodically heals any missed event, and becomes the fallback when MQTT is down.
                val shouldBackfillEvents = if (mqttConnected) ticks % 8 == 0 else ticks % 2 == 0
                if (shouldBackfillEvents) {
                    runCatching { pollEvents(matchId, playerName) }.onFailure { setPassiveNetworkState(it) }
                }
                if (ticks % 2 == 0) {
                    runCatching { pollSnapshot(matchId, playerName) }.onFailure { setPassiveNetworkState(it) }
                }
                delay(1_000)
            }
        }
    }

    private suspend fun pollEvents(matchId: String, playerName: String) {
        val rows = client.eventRows(matchId, 30)
        val fresh = rows.filter { seenVersions.add(it.updateVersion) }
        fresh.sortedBy { it.updateVersion }.forEach { processEvent(it, playerName) }
        if (seenVersions.size > 2500) {
            val keep = rows.map { it.updateVersion }.toSet()
            seenVersions.retainAll(keep)
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
            firstKillSeen = false
            watchedRoundKills = 0
            watchedFirstDeath = false
            roundNumber = snap.currentRound
        } else if (snap.currentRound != null) {
            roundNumber = snap.currentRound
        }
        previousSnapshot = snap
        engine.setMapNumber(snap.mapNumber)
        updateWatchedSide(snap, playerName)
        state = state.copy(snapshot = snap, workout = engine.publicState(), lastSyncAt = System.currentTimeMillis())
    }

    private fun seedRoundContext(rows: List<EventRow>, playerName: String) {
        if (rows.isEmpty()) return
        val currentMap = state.snapshot?.mapName.orEmpty()
        var localRound = roundNumber
        var localFirstKill = false
        var localKills = 0
        var localFirstDeath = false
        rows.filter { currentMap.isBlank() || it.mapName.equals(currentMap, ignoreCase = true) }
            .sortedBy { it.updateVersion }
            .forEach { row ->
                val info = runCatching { JSONObject(row.rawLog) }.getOrNull() ?: return@forEach
                val roundStart = info.optJSONObject("round_start")
                if (roundStart != null && roundStart.length() > 0) {
                    localRound = roundStart.optString("round_num").toIntOrNull() ?: localRound?.plus(1)
                    localFirstKill = false
                    localKills = 0
                    localFirstDeath = false
                }
                val kill = info.optJSONObject("kill")
                if (kill != null && kill.length() > 0) {
                    val killer = kill.optString("killer_nick").ifBlank { kill.optString("killer_name") }
                    val victim = kill.optString("victim_nick").ifBlank { kill.optString("victim_name") }
                    val first = !localFirstKill
                    localFirstKill = true
                    if (sameName(killer, playerName)) localKills++
                    if (sameName(victim, playerName) && first) localFirstDeath = true
                }
                val roundEnd = info.optJSONObject("round_end")
                if (roundEnd != null && roundEnd.length() > 0) {
                    localFirstKill = false
                    localKills = 0
                    localFirstDeath = false
                }
            }
        roundNumber = localRound ?: state.snapshot?.currentRound
        firstKillSeen = localFirstKill
        watchedRoundKills = localKills
        watchedFirstDeath = localFirstDeath
    }

    private fun processEvent(row: EventRow, playerName: String) {
        val info = runCatching { JSONObject(row.rawLog) }.getOrNull() ?: return
        val roundStart = info.optJSONObject("round_start")
        if (roundStart != null && roundStart.length() > 0) {
            roundNumber = roundStart.optString("round_num").toIntOrNull()
                ?: state.snapshot?.currentRound
                ?: roundNumber?.plus(1)
            firstKillSeen = false
            watchedRoundKills = 0
            watchedFirstDeath = false
            addUiEvent("ROUND_START", "第 ${roundNumber ?: "?"} 回合开始", row.mapName)
        }

        val kill = info.optJSONObject("kill")
        if (kill != null && kill.length() > 0) {
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
        if (roundEnd != null && roundEnd.length() > 0 && roundEnd.has("winner")) {
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
            val displayRound = roundNumber ?: state.snapshot?.currentRound
            addUiEvent(
                if (won) "ROUND_WIN" else "ROUND_END",
                "第 ${displayRound ?: "?"} 回合${if (won) "获胜" else "结束"}",
                listOf("$watchedRoundKills 杀", if (watchedFirstDeath) "首死" else "", label).filter { it.isNotBlank() }.joinToString(" · "),
            )
            watchedRoundKills = 0
            watchedFirstDeath = false
            firstKillSeen = false
        }
    }

    private fun updateWatchedSide(snap: MatchSnapshot?, playerName: String) {
        if (snap == null) return
        val foundSlot = snap.players.firstOrNull { sameName(it.name, playerName) }?.teamSlot.orEmpty()
        if (foundSlot.isNotBlank()) watchedTeamSlot = foundSlot
        watchedSide = when (watchedTeamSlot) {
            "t1" -> snap.t1Side
            "t2" -> snap.t2Side
            else -> watchedSide
        }
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
        eventStream?.close(); eventStream = null
        stopPreview()
        mqttConnected = false
        state = state.copy(page = AppPage.MATCHES, selectedMatch = null, snapshot = null, selectedPlayer = null, liveTransport = "")
        refreshMatches()
    }

    fun backToMatch() {
        if (state.page == AppPage.WORKOUT) return
        stopPreview()
        state = state.copy(page = AppPage.MATCHES, selectedMatch = null, snapshot = null, selectedPlayer = null, liveTransport = "")
    }

    override fun onCleared() {
        super.onCleared()
        monitorJob?.cancel()
        previewJob?.cancel()
        eventStream?.close()
    }

    private fun addUiEvent(type: String, title: String, detail: String = "") {
        state = state.copy(events = (listOf(LiveEvent(type, title, detail)) + state.events).take(80))
    }

    private fun setPassiveNetworkState(t: Throwable) {
        val raw = t.message.orEmpty()
        val note = when {
            raw.contains("Unable to resolve host", ignoreCase = true) -> "网络暂时连不上5E，后台重试中"
            raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
                if (mqttConnected) "实时推送正常 · HTTP补偿接口偶尔超时" else "5E接口超时 · 自动重试中"
            else -> if (mqttConnected) "实时推送已连接" else "5E数据暂时不稳定 · 自动重试中"
        }
        if (state.liveTransport != note) state = state.copy(liveTransport = note)
    }

    private fun friendlyError(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            raw.contains("Unable to resolve host", ignoreCase = true) -> "网络连不上5E数据源，稍后再试。"
            raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) -> "5E数据响应超时，稍后会自动重试。"
            else -> raw.ifBlank { "发生了一个未知错误" }
        }
    }

    private fun sameName(a: String, b: String): Boolean = normalizeName(a) == normalizeName(b)
    private fun normalizeName(v: String): String = v.lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "")
}