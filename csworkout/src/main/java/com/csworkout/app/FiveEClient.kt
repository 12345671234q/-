package com.csworkout.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class FiveEClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "CSWorkout/0.1")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("5E 接口 HTTP ${response.code}")
            val root = JSONObject(text)
            if (root.optBoolean("success", true).not()) {
                error(root.optString("message").ifBlank { "5E 接口返回失败" })
            }
            root
        }
    }

    suspend fun schedule(page: Int = 1): List<ScheduleMatch> {
        val root = getJson(
            "https://app.5eplay.com/api/tournament/session_list?game_status=1&game_type=1&grades=&page=$page&limit=20"
        )
        val matches = root.optJSONObject("data")?.optJSONArray("matches") ?: JSONArray()
        val result = mutableListOf<ScheduleMatch>()
        for (i in 0 until matches.length()) {
            val row = matches.optJSONObject(i) ?: continue
            runCatching { parseScheduleRow(row) }.getOrNull()?.let(result::add)
        }
        return result.sortedWith(
            compareBy<ScheduleMatch> { if (it.status == MatchStatus.LIVE) 0 else 1 }
                .thenBy { it.scheduledAtMillis }
        )
    }

    private fun parseScheduleRow(row: JSONObject): ScheduleMatch? {
        val info = row.optJSONObject("mc_info") ?: return null
        val state = row.optJSONObject("state") ?: return null
        val statusCode = state.optString("status").toIntOrNull() ?: 0
        if (statusCode == 2) return null
        val maps = state.optJSONArray("bout_states") ?: JSONArray()
        var liveMap: Int? = null
        for (i in 0 until maps.length()) {
            val map = maps.optJSONObject(i) ?: continue
            if (map.optString("status") == "1") liveMap = map.optString("bout_num").toIntOrNull()
        }
        val effectiveLive = statusCode == 1 || liveMap != null
        val t1 = info.optJSONObject("t1_info") ?: JSONObject()
        val t2 = info.optJSONObject("t2_info") ?: JSONObject()
        val tt = row.optJSONObject("tt_info") ?: JSONObject()
        val id = info.optString("id")
        if (!id.startsWith("csgo_mc_")) return null
        return ScheduleMatch(
            id = id,
            team1 = MatchTeam(
                id = t1.optString("id"),
                name = t1.optString("disp_name", "队伍 1"),
                logo = t1.optString("logo"),
                seriesScore = state.optString("t1_score").toIntOrNull() ?: 0,
            ),
            team2 = MatchTeam(
                id = t2.optString("id"),
                name = t2.optString("disp_name", "队伍 2"),
                logo = t2.optString("logo"),
                seriesScore = state.optString("t2_score").toIntOrNull() ?: 0,
            ),
            tournament = tt.optString("disp_name", "未知赛事"),
            stage = info.optString("tt_stage_desc").ifBlank { info.optString("tt_stage") },
            bestOf = info.optString("format").toIntOrNull() ?: 3,
            scheduledAtMillis = (info.optString("plan_ts").toLongOrNull() ?: 0L) * 1000L,
            status = if (effectiveLive) MatchStatus.LIVE else MatchStatus.UPCOMING,
            currentMap = liveMap,
        )
    }

    suspend fun snapshot(matchId: String): MatchSnapshot {
        val root = getJson("https://esports-data.5eplaycdn.com/v1/api/csgo/matches/$matchId/data")
        val match = root.optJSONObject("data")?.optJSONObject("match") ?: error("比赛数据为空")
        val info = match.optJSONObject("mc_info") ?: JSONObject()
        val tt = match.optJSONObject("tt_info") ?: JSONObject()
        val global = match.optJSONObject("global_state") ?: JSONObject()
        val bouts = match.optJSONArray("bouts_state") ?: JSONArray()
        val bout = pickCurrentBout(bouts)
        val t1Info = info.optJSONObject("t1_info") ?: JSONObject()
        val t2Info = info.optJSONObject("t2_info") ?: JSONObject()
        val t1Stats = bout?.optJSONObject("t1_stats") ?: JSONObject()
        val t2Stats = bout?.optJSONObject("t2_stats") ?: JSONObject()
        val players = mutableListOf<PlayerRow>()
        appendPlayers(players, bout?.optJSONArray("t1_pr_stats"), "t1")
        appendPlayers(players, bout?.optJSONArray("t2_pr_stats"), "t2")
        val boutNum = bout?.optString("bout_num")?.toIntOrNull() ?: 1
        val boutKey = "${bout?.optString("bout_num")}:${bout?.optString("map_name")}"
        return MatchSnapshot(
            matchId = matchId,
            title = "${t1Info.optString("disp_name", "队伍 1")} VS ${t2Info.optString("disp_name", "队伍 2")}",
            tournament = tt.optString("disp_name"),
            mapNumber = boutNum,
            mapName = bout?.optString("map_name").orEmpty(),
            mapStatus = bout?.optString("status").orEmpty(),
            team1 = MatchTeam(
                id = t1Info.optString("id"),
                name = t1Info.optString("disp_name", "队伍 1"),
                logo = t1Info.optString("logo"),
                seriesScore = global.optString("t1_score").toIntOrNull() ?: 0,
            ),
            team2 = MatchTeam(
                id = t2Info.optString("id"),
                name = t2Info.optString("disp_name", "队伍 2"),
                logo = t2Info.optString("logo"),
                seriesScore = global.optString("t2_score").toIntOrNull() ?: 0,
            ),
            team1MapScore = t1Stats.optString("all_score").toIntOrNull() ?: 0,
            team2MapScore = t2Stats.optString("all_score").toIntOrNull() ?: 0,
            t1Side = t1Stats.optString("role"),
            t2Side = t2Stats.optString("role"),
            players = players,
            boutKey = boutKey,
        )
    }

    private fun pickCurrentBout(bouts: JSONArray): JSONObject? {
        var fallback: JSONObject? = null
        for (i in 0 until bouts.length()) {
            val item = bouts.optJSONObject(i) ?: continue
            if (item.optString("status") == "1") return item
            if (item.optString("display") == "1") fallback = item
            if (fallback == null) fallback = item
        }
        return fallback ?: bouts.optJSONObject((bouts.length() - 1).coerceAtLeast(0))
    }

    private fun appendPlayers(target: MutableList<PlayerRow>, array: JSONArray?, slot: String) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            val name = p.optString("name").trim()
            if (name.isBlank()) continue
            target += PlayerRow(
                id = p.optString("id"),
                name = name,
                teamSlot = slot,
                kills = p.optString("kill").toIntOrNull() ?: 0,
                deaths = p.optString("death").toIntOrNull() ?: 0,
            )
        }
    }

    suspend fun eventRows(matchId: String, limit: Int = 60): List<EventRow> {
        val root = getJson(
            "https://esports-data.5eplaycdn.com/v1/api/csgo/match/$matchId/event/log?update_version=0&limit=$limit"
        )
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val rows = mutableListOf<EventRow>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val version = item.optString("update_version").toLongOrNull() ?: continue
            val raw = when (val log = item.opt("log_info")) {
                is String -> log
                is JSONObject -> log.toString()
                else -> continue
            }
            rows += EventRow(version, raw, item.optString("map_name"))
        }
        return rows.sortedBy { it.updateVersion }
    }
}
