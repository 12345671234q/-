from pathlib import Path

root = Path('v04src/csworkout')
app = root / 'src/main/java/com/csworkout/app'

def replace_once(path, old, new):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f'patch anchor missing: {p}: {old[:80]!r}')
    p.write_text(s.replace(old, new, 1))

# version
p = root / 'build.gradle.kts'
s = p.read_text().replace('versionCode = 4', 'versionCode = 5', 1).replace('versionName = "0.4.0"', 'versionName = "0.4.1"', 1)
p.write_text(s)
(root / 'VERSION.txt').write_text('0.4.1\n')

# FiveEClient: static roster -> live stats -> event fallback
p = app / 'FiveEClient.kt'
s = p.read_text()
s = s.replace('.header("User-Agent", "CSWorkout/0.2")', '.header("User-Agent", "CSWorkout/0.4.1")', 1)
s = s.replace(
'''        val players = mutableListOf<PlayerRow>()
        appendPlayers(players, bout?.optJSONArray("t1_pr_stats"), "t1")
        appendPlayers(players, bout?.optJSONArray("t2_pr_stats"), "t2")
''',
'''        val players = mutableListOf<PlayerRow>()
        // 选人不再依赖当前地图 pr_stats。5E 有些比赛会晚 1~2 回合才初始化实时统计。
        // 先从比赛/战队静态信息里尽量拿阵容，再让实时统计覆盖 K/D。
        appendStaticRoster(players, match, info, t1Info, "t1")
        appendStaticRoster(players, match, info, t2Info, "t2")
        appendPlayers(players, bout?.optJSONArray("t1_pr_stats"), "t1", preferStats = true)
        appendPlayers(players, bout?.optJSONArray("t2_pr_stats"), "t2", preferStats = true)
''', 1)
old = '''    private fun appendPlayers(target: MutableList<PlayerRow>, array: JSONArray?, slot: String) {
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
'''
new = '''    private fun appendPlayers(
        target: MutableList<PlayerRow>,
        array: JSONArray?,
        slot: String,
        preferStats: Boolean = false,
    ) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            val name = playerName(p)
            if (name.isBlank()) continue
            upsertPlayer(
                target,
                PlayerRow(
                    id = playerId(p),
                    name = name,
                    teamSlot = slot,
                    kills = numberValue(p, "kill", "kills", "frag", "frags"),
                    deaths = numberValue(p, "death", "deaths"),
                ),
                preferStats = preferStats,
            )
        }
    }

    private fun appendStaticRoster(
        target: MutableList<PlayerRow>,
        match: JSONObject,
        info: JSONObject,
        teamInfo: JSONObject,
        slot: String,
    ) {
        scanRosterNode(target, teamInfo, slot, depth = 0, trusted = true)
        scanTeamScopedKeys(target, info, slot)
        scanTeamScopedKeys(target, match, slot)
    }

    private fun scanTeamScopedKeys(target: MutableList<PlayerRow>, obj: JSONObject, slot: String) {
        val prefix = if (slot == "t1") "t1" else "t2"
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val lower = key.lowercase()
            if (!lower.startsWith(prefix) || !looksLikeRosterKey(lower)) continue
            scanRosterNode(target, obj.opt(key), slot, depth = 0, trusted = true)
        }
    }

    private fun scanRosterNode(
        target: MutableList<PlayerRow>, node: Any?, slot: String, depth: Int, trusted: Boolean,
    ) {
        if (node == null || node == JSONObject.NULL || depth > 5) return
        when (node) {
            is JSONArray -> for (i in 0 until minOf(node.length(), 20)) {
                scanRosterNode(target, node.opt(i), slot, depth + 1, trusted)
            }
            is JSONObject -> {
                val name = playerName(node)
                val playerish = trusted && name.isNotBlank() && (
                    playerId(node).isNotBlank() || node.has("nick_name") || node.has("nickname") ||
                        node.has("player_name") || node.has("steam_id") || node.has("steamid") || node.has("avatar")
                    )
                if (playerish && !looksLikeTeamObject(node)) {
                    upsertPlayer(target, PlayerRow(
                        id = playerId(node), name = name, teamSlot = slot,
                        kills = numberValue(node, "kill", "kills", "frag", "frags"),
                        deaths = numberValue(node, "death", "deaths"),
                    ), preferStats = false)
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val nextTrusted = trusted || looksLikeRosterKey(key)
                    if (nextTrusted) scanRosterNode(target, node.opt(key), slot, depth + 1, nextTrusted)
                }
            }
        }
    }

    private fun looksLikeRosterKey(key: String): Boolean {
        val k = key.lowercase()
        return listOf("player", "players", "pr_", "_pr", "roster", "lineup", "member", "members", "squad").any { it in k }
    }

    private fun looksLikeTeamObject(obj: JSONObject): Boolean =
        obj.has("logo") && (obj.has("disp_name") || obj.has("team_name"))

    private fun playerName(p: JSONObject): String {
        for (key in listOf("name", "nick_name", "nickname", "player_name", "nick", "username")) {
            val value = p.optString(key).trim()
            if (value.isNotBlank() && value != "null") return value
        }
        if (!p.has("logo")) return p.optString("disp_name").trim()
        return ""
    }

    private fun playerId(p: JSONObject): String {
        for (key in listOf("id", "player_id", "pid", "pr_id", "steam_id", "steamid")) {
            val value = p.optString(key).trim()
            if (value.isNotBlank() && value != "null" && value != "0") return value
        }
        return ""
    }

    private fun numberValue(p: JSONObject, vararg keys: String): Int {
        for (key in keys) p.optString(key).toIntOrNull()?.let { return it }
        return 0
    }

    private fun upsertPlayer(target: MutableList<PlayerRow>, player: PlayerRow, preferStats: Boolean) {
        val normalized = player.name.trim().lowercase()
        val index = target.indexOfFirst { existing ->
            existing.teamSlot == player.teamSlot && (
                (player.id.isNotBlank() && existing.id.isNotBlank() && existing.id == player.id) ||
                    existing.name.trim().lowercase() == normalized
                )
        }
        if (index < 0) {
            if (target.count { it.teamSlot == player.teamSlot } < 8) target += player
        } else if (preferStats) {
            val old = target[index]
            target[index] = player.copy(id = player.id.ifBlank { old.id }, name = player.name.ifBlank { old.name })
        }
    }

    fun playersFromEvents(rows: List<EventRow>, snapshot: MatchSnapshot): List<PlayerRow> {
        val found = mutableListOf<PlayerRow>()
        rows.forEach { row ->
            val info = runCatching { JSONObject(row.rawLog) }.getOrNull() ?: return@forEach
            val kill = info.optJSONObject("kill") ?: return@forEach
            addEventPerson(found, kill, "killer", snapshot)
            addEventPerson(found, kill, "victim", snapshot)
        }
        return found
    }

    private fun addEventPerson(target: MutableList<PlayerRow>, kill: JSONObject, prefix: String, snap: MatchSnapshot) {
        val name = kill.optString("${prefix}_nick").ifBlank { kill.optString("${prefix}_name") }.trim()
        if (name.isBlank()) return
        val sideOrTeam = listOf("${prefix}_team", "${prefix}_side", "${prefix}_role")
            .map { kill.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        val slot = inferTeamSlot(sideOrTeam, snap)
        if (slot.isNotBlank()) upsertPlayer(target, PlayerRow("", name, slot), preferStats = false)
    }

    private fun inferTeamSlot(raw: String, snap: MatchSnapshot): String {
        val value = raw.lowercase()
        if (value == "t1" || value == "team1" || value == "1") return "t1"
        if (value == "t2" || value == "team2" || value == "2") return "t2"
        if (snap.t1Side.isNotBlank() && value == snap.t1Side.lowercase()) return "t1"
        if (snap.t2Side.isNotBlank() && value == snap.t2Side.lowercase()) return "t2"
        return ""
    }
'''
if old not in s: raise SystemExit('FiveEClient appendPlayers anchor missing')
s = s.replace(old, new, 1)
p.write_text(s)

# ViewModel: enrich incomplete roster with already-seen kill events
p = app / 'WorkoutViewModel.kt'
s = p.read_text()
s = s.replace('runCatching { client.snapshot(match.id) }', 'runCatching { snapshotWithRoster(match.id) }', 1)
s = s.replace('runCatching { client.snapshot(matchId) }\n                    .onSuccess { snap ->', 'runCatching { snapshotWithRoster(matchId) }\n                    .onSuccess { snap ->', 1)
s = s.replace('runCatching { client.snapshot(id) }\n                .onSuccess { snap ->', 'runCatching { snapshotWithRoster(id) }\n                .onSuccess { snap ->', 1)
anchor = '''    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
    }
'''
helper = '''    private suspend fun snapshotWithRoster(matchId: String): MatchSnapshot {
        val snap = client.snapshot(matchId)
        val t1Count = snap.players.count { it.teamSlot == "t1" }
        val t2Count = snap.players.count { it.teamSlot == "t2" }
        if (t1Count >= 5 && t2Count >= 5) return snap
        val rows = runCatching { client.eventRows(matchId, 100) }.getOrDefault(emptyList())
        val eventPlayers = client.playersFromEvents(rows, snap)
        if (eventPlayers.isEmpty()) return snap
        val merged = snap.players.toMutableList()
        eventPlayers.forEach { p ->
            if (merged.none { it.teamSlot == p.teamSlot && sameName(it.name, p.name) }) merged += p
        }
        return snap.copy(players = merged)
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
    }
'''
if anchor not in s: raise SystemExit('WorkoutViewModel stopPreview anchor missing')
s = s.replace(anchor, helper, 1)
p.write_text(s)

# UI copy
p = app / 'WorkoutApp.kt'
s = p.read_text()
s = s.replace(
    'EmptyCard("5E 当前还没有给出选手阵容。比赛开始后点右上角“刷新”就会自己出来，不用再贴链接。")',
    'EmptyCard("正在从 5E 的静态阵容、实时统计和比赛事件里补选手。页面会自动更新，不用一直点刷新。")',
    1,
)
p.write_text(s)
