package com.csworkout.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

private fun r2(v: Double): Double = round(v * 100.0) / 100.0

private data class Variant(
    val level: Int,
    val label: String,
    val concentric: Int,
    val hold: Int,
    val eccentric: Int,
    val tension: String,
    val extra: Double,
)

private data class ActionDef(
    val id: String,
    val name: String,
    val account: String,
    val baseDU: Double,
    val unit: String = "rep",
    val side: Boolean = false,
    val weight: Double = 10.0,
    val maxShare: Double = 0.15,
    val region: String = "core",
    val tags: Set<String> = setOf("dynamic"),
    val streakMin: Int = 0,
    val continuousInstruction: String = "连续动作间不得完全卸力",
    val standardInstruction: String = "按标准完整幅度完成",
    val level4Instruction: String = "严格按节奏完成，全程不得卸力",
)

private data class DebtTranche(
    var du: Double,
    val principalDU: Double,
    val tendency: String,
    val source: String,
    val account: String,
    val transferSource: String? = null,
    val transferMultiplier: Double = 1.0,
    val eligibleBuyback: Boolean = false,
)

private data class AccountInternal(
    var grossDU: Double = 0.0,
    var paidDU: Double = 0.0,
    var fatigue: Double = 0.0,
    var bankrupt: Boolean = false,
    var reopenUsed: Boolean = false,
    val tranches: MutableList<DebtTranche> = mutableListOf(),
    val paidByAction: MutableMap<String, Double> = mutableMapOf(),
    val paidByRegion: MutableMap<String, Double> = mutableMapOf(),
) {
    fun outstanding(): Double = r2(tranches.sumOf { it.du })
}

class WorkoutEngine(context: Context) {
    companion object {
        private const val PREFS = "cs_workout_engine"
        private const val KEY_STATE = "state_v1"
        private val MAP_NEW_MULTIPLIER = mapOf(1 to 1.0, 2 to 1.0, 3 to 0.7)
        private val MAP_ROLLOVER = mapOf(1 to 1.2, 2 to 1.25)
        private const val BANKRUPTCY_TRANSFER = 1.2
        private val LEG_REGION_CAPS = mapOf("quad" to .45, "posterior" to .30, "inner" to .20, "calf" to .20, "outer" to .15)

        private val VARIANTS = mapOf(
            1 to Variant(1, "标准", 1, 0, 2, "rest", 0.0),
            2 to Variant(2, "慢速", 2, 1, 3, "continuous", 0.0),
            3 to Variant(3, "困难", 3, 2, 4, "continuous", 0.08),
            4 to Variant(4, "地狱", 3, 3, 5, "no-contact", 0.12),
        )
        private val STATIC_LEVELS = mapOf(1 to 1.0, 2 to 1.2, 3 to 1.6, 4 to 2.0)

        private val ACTIONS = listOf(
            ActionDef("crunch", "卷腹", "abs", 1.0, weight = 18.0, maxShare = .20, streakMin = 5, tags = setOf("dynamic", "control"), continuousInstruction = "肩胛保持悬空，连续动作间不得完全躺回"),
            ActionDef("reverse_crunch", "反向卷腹", "abs", 1.25, weight = 14.0, maxShare = .18, streakMin = 5, tags = setOf("dynamic", "control", "lower")),
            ActionDef("v_up", "两头起", "abs", 2.2, weight = 9.0, maxShare = .15, streakMin = 3, tags = setOf("hard", "boss", "dynamic"), continuousInstruction = "肩和双腿在连续区块内不得完全触地", level4Instruction = "3s 起 / 顶停 3s / 5s 下；肩和双腿不得触地"),
            ActionDef("leg_raise", "直腿举腿", "abs", 1.5, weight = 13.0, maxShare = .18, streakMin = 3, tags = setOf("lower", "hard", "continuous"), continuousInstruction = "双腿下降到低位但不得触地"),
            ActionDef("knee_raise", "屈膝举腿", "abs", 1.1, weight = 7.0, maxShare = .12, streakMin = 4, tags = setOf("lower", "dynamic")),
            ActionDef("bicycle", "自行车卷腹", "abs", .65, side = true, weight = 8.0, maxShare = .12, streakMin = 6, tags = setOf("dynamic", "control")),
            ActionDef("alt_leg_raise", "交替直腿抬放", "abs", .85, side = true, weight = 5.0, maxShare = .10, streakMin = 6, tags = setOf("lower", "continuous")),
            ActionDef("dead_bug", "死虫", "abs", 1.0, unit = "seconds", weight = 8.0, maxShare = .12, tags = setOf("static", "control"), standardInstruction = "腰背贴稳，按计时持续控制"),
            ActionDef("scissor", "剪刀腿", "abs", 1.0, unit = "seconds", weight = 7.0, maxShare = .12, tags = setOf("continuous", "static", "lower"), standardInstruction = "双腿低位悬空，小幅交替，不触地"),
            ActionDef("hollow", "Hollow Hold", "abs", .9, unit = "seconds", weight = 11.0, maxShare = .15, tags = setOf("static", "control", "boss"), standardInstruction = "腰始终贴稳，肩胛与双腿按等级悬空"),

            ActionDef("squat", "徒手深蹲", "legs", 1.0, weight = 16.0, maxShare = .20, region = "quad", streakMin = 6, continuousInstruction = "顶部不锁膝休息，连续保持张力"),
            ActionDef("narrow_squat", "窄距深蹲", "legs", 1.1, weight = 10.0, maxShare = .16, region = "quad", streakMin = 6, tags = setOf("hard", "dynamic")),
            ActionDef("reverse_lunge", "反向箭步蹲", "legs", 1.3, side = true, weight = 13.0, maxShare = .18, region = "quad", streakMin = 4, tags = setOf("dynamic", "control")),
            ActionDef("split_squat", "分腿蹲", "legs", 1.4, side = true, weight = 10.0, maxShare = .16, region = "quad", streakMin = 4, tags = setOf("hard", "control")),
            ActionDef("bulgarian", "保加利亚分腿蹲", "legs", 1.85, side = true, weight = 8.0, maxShare = .14, region = "quad", streakMin = 3, tags = setOf("hard", "boss"), level4Instruction = "4s 下蹲、底停 3s、起一半再下去，再完整起身；借力则本次不计"),
            ActionDef("side_lunge", "侧弓步", "legs", 1.35, side = true, weight = 9.0, maxShare = .14, region = "inner", streakMin = 4, tags = setOf("control", "dynamic")),
            ActionDef("single_leg_rdl", "单腿 RDL", "legs", 1.45, side = true, weight = 8.0, maxShare = .14, region = "posterior", streakMin = 4, tags = setOf("control", "hard")),
            ActionDef("heel_bridge", "脚跟压地屈膝桥", "legs", 1.15, weight = 9.0, maxShare = .14, region = "posterior", streakMin = 5, tags = setOf("dynamic", "continuous"), continuousInstruction = "臀部不完全落地，脚跟持续压地"),
            ActionDef("wall_sit", "靠墙静蹲", "legs", 1.0, unit = "seconds", weight = 12.0, maxShare = .16, region = "quad", tags = setOf("static", "continuous")),
            ActionDef("squat_hold", "深蹲底部保持", "legs", 1.2, unit = "seconds", weight = 9.0, maxShare = .14, region = "quad", tags = setOf("static", "hard")),
            ActionDef("calf_raise", "双腿提踵", "legs", .55, weight = 7.0, maxShare = .12, region = "calf", streakMin = 8),
            ActionDef("single_calf", "单腿提踵", "legs", .9, side = true, weight = 8.0, maxShare = .14, region = "calf", streakMin = 5, tags = setOf("hard", "control")),
            ActionDef("side_adduction", "侧躺下侧腿抬起", "legs", .9, side = true, weight = 6.0, maxShare = .10, region = "inner", streakMin = 6, tags = setOf("dynamic", "control")),
            ActionDef("side_abduction", "侧躺抬腿", "legs", .8, side = true, weight = 5.0, maxShare = .10, region = "outer", streakMin = 6, tags = setOf("dynamic", "control")),
        )
        private val ACTION_MAP = ACTIONS.associateBy { it.id }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var enabled = false
    private var matchId = ""
    private var player = ""
    private var mapNumber = 1
    private var consecutiveFirstDeaths = 0
    private var writtenOffDU = 0.0
    private var sessionSeed = System.currentTimeMillis().toString()
    private val abs = AccountInternal()
    private val legs = AccountInternal()

    init { load() }

    fun startSession(matchId: String, player: String) {
        val changed = this.matchId != matchId || this.player != player
        if (changed) {
            resetInternal()
            this.matchId = matchId
            this.player = player
            enabled = true
            sessionSeed = "${System.currentTimeMillis()}-$matchId-$player"
        } else enabled = true
        save()
    }

    fun setEnabled(value: Boolean) { enabled = value; save() }

    fun resetSession() {
        val oldEnabled = enabled
        resetInternal()
        enabled = oldEnabled
        save()
    }

    private fun resetInternal() {
        abs.tranches.clear(); abs.grossDU = 0.0; abs.paidDU = 0.0; abs.fatigue = 0.0; abs.bankrupt = false; abs.reopenUsed = false; abs.paidByAction.clear(); abs.paidByRegion.clear()
        legs.tranches.clear(); legs.grossDU = 0.0; legs.paidDU = 0.0; legs.fatigue = 0.0; legs.bankrupt = false; legs.reopenUsed = false; legs.paidByAction.clear(); legs.paidByRegion.clear()
        matchId = ""; player = ""; mapNumber = 1; consecutiveFirstDeaths = 0; writtenOffDU = 0.0
    }

    fun setMapNumber(value: Int) { mapNumber = value.coerceIn(1, 3); save() }

    private fun account(name: String) = if (name == "abs") abs else legs

    private fun chooseAccountForNewDebt(map: Int): String {
        if (map == 1) return "abs"
        if (map == 2) return "legs"
        val candidates = listOf("abs", "legs").filterNot { account(it).bankrupt && account(it).reopenUsed }
        return candidates.minByOrNull { account(it).fatigue * 20 + account(it).outstanding() }.orEmpty()
    }

    fun addDebt(du: Double, tendency: String = "dynamic", source: String = "比赛事件", targetAccount: String = "") {
        if (!enabled) return
        val amount = r2(max(0.0, du) * (MAP_NEW_MULTIPLIER[mapNumber] ?: 1.0))
        if (amount <= 0) return
        val target = targetAccount.ifBlank { chooseAccountForNewDebt(mapNumber) }
        if (target.isBlank()) { writtenOffDU = r2(writtenOffDU + amount); save(); return }
        val acc = account(target)
        if (acc.bankrupt) {
            val fallback = if (target == "abs") "legs" else "abs"
            val other = account(fallback)
            if (!(other.bankrupt && other.reopenUsed) && !other.bankrupt) {
                addDebt(amount * BANKRUPTCY_TRANSFER, tendency, "$source（停牌转债）", fallback)
            } else writtenOffDU = r2(writtenOffDU + amount)
            save(); return
        }
        acc.tranches += DebtTranche(amount, amount, tendency, source, target)
        acc.grossDU = r2(acc.grossDU + amount)
        save()
    }

    fun recordKill() = addDebt(2.0, "dynamic", "击杀")

    fun recordFirstDeath() {
        consecutiveFirstDeaths += 1
        val du = if (consecutiveFirstDeaths >= 3) 6.0 else if (consecutiveFirstDeaths == 2) 4.0 else 3.0
        addDebt(du, "continuous", if (consecutiveFirstDeaths >= 2) "连续首死 ×$consecutiveFirstDeaths" else "首死")
    }

    fun recordRoundEnd(kills: Int, firstDeath: Boolean, won: Boolean) {
        if (!enabled) return
        if (!firstDeath) consecutiveFirstDeaths = 0
        if (won) addDebt(1.0, "static", "赢下回合")
        when {
            kills >= 5 -> addDebt(12.0, "boss", "ACE")
            kills == 4 -> addDebt(8.0, "continuous", "4K")
            kills == 3 -> addDebt(5.0, "hard", "3K")
            kills == 2 -> addDebt(2.0, "hard", "2K")
        }
        save()
    }

    fun onMapEnd(watchedWon: Boolean?, overtimeBlocks: Int = 0) {
        if (!enabled) return
        val otRate = min(.30, max(0, overtimeBlocks) * .10)
        if (otRate > 0) applyTax(1 + otRate, "加时税")
        if (watchedWon == false) applyTax(1.10, "败方税")
        val multiplier = MAP_ROLLOVER[mapNumber]
        if (multiplier != null) {
            val nextMap = min(3, mapNumber + 1)
            val target = if (nextMap == 2) "legs" else chooseAccountForNewDebt(3)
            if (target.isNotBlank()) rollAllOutstanding(target, multiplier)
            mapNumber = nextMap
        }
        save()
    }

    private fun applyTax(multiplier: Double, source: String) {
        listOf("abs" to abs, "legs" to legs).forEach { (name, acc) ->
            val extra = r2(acc.outstanding() * (multiplier - 1))
            if (extra > 0) {
                acc.tranches += DebtTranche(extra, extra, "hard", source, name)
                acc.grossDU = r2(acc.grossDU + extra)
            }
        }
    }

    private fun rollAllOutstanding(targetName: String, multiplier: Double) {
        val target = account(targetName)
        val collected = mutableListOf<DebtTranche>()
        listOf(abs, legs).forEach { acc -> collected += acc.tranches.map { it.copy() }; acc.tranches.clear() }
        collected.forEach { t ->
            val transferred = r2(t.du * multiplier)
            target.tranches += t.copy(du = transferred, principalDU = t.du, account = targetName, transferSource = null, transferMultiplier = multiplier, eligibleBuyback = false, source = "${t.source} · 跨图转债")
            target.grossDU = r2(target.grossDU + transferred)
        }
    }

    fun setFatigue(accountName: String, level: Double) {
        val acc = account(accountName)
        acc.fatigue = level.coerceIn(0.0, 5.0)
        if (acc.fatigue >= 5 && !acc.bankrupt) bankrupt(accountName) else save()
    }

    fun bankrupt(accountName: String) {
        val source = account(accountName)
        if (source.bankrupt) return
        source.fatigue = 5.0
        source.bankrupt = true
        val principal = source.outstanding()
        if (principal <= 0) { save(); return }
        val targetName = if (accountName == "abs") "legs" else "abs"
        val target = account(targetName)
        if (target.bankrupt && target.reopenUsed) {
            writtenOffDU = r2(writtenOffDU + principal)
            source.tranches.clear(); save(); return
        }
        source.tranches.forEach { t ->
            val transferred = r2(t.du * BANKRUPTCY_TRANSFER)
            target.tranches += t.copy(
                du = transferred,
                principalDU = t.du,
                account = targetName,
                source = "${t.source} · ${if (accountName == "abs") "腹" else "腿"}债转入",
                transferSource = accountName,
                transferMultiplier = BANKRUPTCY_TRANSFER,
                eligibleBuyback = true,
            )
            target.grossDU = r2(target.grossDU + transferred)
        }
        source.tranches.clear()
        save()
    }

    fun reopen(accountName: String) {
        val source = account(accountName)
        if (!source.bankrupt) return
        if (source.reopenUsed) error("本场已经用过一次复牌")
        val target = account(if (accountName == "abs") "legs" else "abs")
        val remain = mutableListOf<DebtTranche>()
        target.tranches.forEach { t ->
            if (t.eligibleBuyback && t.transferSource == accountName) {
                val principal = r2(t.du / max(1.0, t.transferMultiplier))
                source.tranches += t.copy(du = principal, principalDU = principal, account = accountName, source = "${t.source.substringBefore(" · ")} · 原价回购", transferSource = null, transferMultiplier = 1.0, eligibleBuyback = false)
            } else remain += t
        }
        target.tranches.clear(); target.tranches += remain
        source.bankrupt = false
        source.reopenUsed = true
        source.fatigue = min(source.fatigue, 4.0)
        save()
    }

    fun settle(accountName: String, actionId: String, level: Int, units: Double): Double {
        val def = ACTION_MAP[actionId] ?: error("未知动作")
        require(def.account == accountName)
        val variant = variant(def, level)
        val performed = actionDU(def, variant, units)
        val paid = payAccount(accountName, performed, actionId)
        save()
        return paid
    }

    private fun payAccount(accountName: String, amount: Double, actionId: String): Double {
        val acc = account(accountName)
        var remaining = max(0.0, amount)
        val requested = remaining
        acc.tranches.forEach { t ->
            if (remaining > 0) {
                val used = min(remaining, t.du)
                t.du = r2(t.du - used)
                remaining = r2(remaining - used)
            }
        }
        acc.tranches.removeAll { it.du <= .005 }
        val paid = r2(requested - remaining)
        acc.paidDU = r2(acc.paidDU + paid)
        acc.paidByAction[actionId] = r2((acc.paidByAction[actionId] ?: 0.0) + paid)
        ACTION_MAP[actionId]?.let { def -> acc.paidByRegion[def.region] = r2((acc.paidByRegion[def.region] ?: 0.0) + paid) }
        return paid
    }

    fun publicState(): WorkoutPublicState = WorkoutPublicState(
        enabled = enabled,
        matchId = matchId,
        player = player,
        mapNumber = mapNumber,
        abs = publicAccount("abs", abs),
        legs = publicAccount("legs", legs),
        writtenOffDU = writtenOffDU,
    )

    private fun publicAccount(name: String, acc: AccountInternal) = WorkoutAccountState(
        outstandingDU = acc.outstanding(),
        grossDU = acc.grossDU,
        paidDU = acc.paidDU,
        fatigue = acc.fatigue,
        bankrupt = acc.bankrupt,
        reopenUsed = acc.reopenUsed,
        plan = plan(name, acc),
    )

    private data class VariantCalc(val level: Int, val label: String, val modifier: Double, val instruction: String, val streakMin: Int, val tempo: String)

    private fun variant(def: ActionDef, levelRaw: Int): VariantCalc {
        val level = levelRaw.coerceIn(1, 4)
        val v = VARIANTS[level]!!
        if (def.unit == "seconds") {
            return VariantCalc(level, v.label, STATIC_LEVELS[level]!!, if (level == 4) def.level4Instruction else if (level >= 2) def.continuousInstruction else def.standardInstruction, 0, "按计时持续保持")
        }
        val tension = if (v.tension == "no-contact") .10 else if (v.tension == "continuous") .06 else 0.0
        val mod = (1 + max(0, v.concentric - 1) * .06 + max(0, v.eccentric - 2) * .05 + max(0, v.hold) * .07 + tension + v.extra).coerceIn(.65, 2.2)
        return VariantCalc(level, v.label, mod, if (level == 4) def.level4Instruction else if (level >= 2 && def.streakMin > 0) def.continuousInstruction else def.standardInstruction, if (level >= 2) def.streakMin else 0, "${v.concentric}s 起 / ${v.hold}s 停 / ${v.eccentric}s 下")
    }

    private fun actionDU(def: ActionDef, variant: VariantCalc, unitsRaw: Double): Double {
        if (def.unit == "seconds") {
            var remaining = max(0.0, unitsRaw)
            val chunks = listOf(20.0 to 1.0, 20.0 to 1.08, 20.0 to 1.15, 30.0 to 1.25, Double.POSITIVE_INFINITY to 1.35)
            var du = 0.0
            for ((size, factor) in chunks) {
                if (remaining <= 0) break
                val used = min(remaining, size)
                du += used / 10.0 * def.baseDU * variant.modifier * factor
                remaining -= used
            }
            return r2(du)
        }
        val reps = floor(max(0.0, unitsRaw)).toInt()
        if (reps == 0) return 0.0
        if (variant.streakMin <= 0) return r2(reps * def.baseDU * variant.modifier)
        val block = variant.streakMin
        val full = reps / block
        val rem = reps % block
        var du = 0.0
        repeat(full) { idx -> du += block * def.baseDU * variant.modifier * min(1.45, 1.15 + idx * .10) }
        du += rem * def.baseDU
        return r2(du)
    }

    private fun tendencyBoost(def: ActionDef, tendency: String): Double = when {
        def.tags.contains(tendency) -> 2.4
        tendency == "boss" && def.tags.contains("hard") -> 1.7
        tendency == "hard" && def.tags.contains("dynamic") -> 1.35
        tendency == "static" && def.tags.contains("control") -> 1.35
        tendency == "continuous" && def.tags.contains("lower") -> 1.35
        else -> .85
    }

    private fun hash01(text: String): Double {
        var h = 0x811c9dc5.toInt()
        text.forEach { c -> h = (h xor c.code) * 16777619 }
        return (h.toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }

    private fun chooseLevel(tendency: String, fatigue: Double, seed: String): Int {
        var base = when (tendency) { "boss" -> 4; "hard", "continuous" -> 3; "static", "control" -> 2; else -> 1 }
        if (hash01(seed) > .62) base++
        if (fatigue >= 4) base--
        if (fatigue >= 4.7) base--
        return base.coerceIn(1, 4)
    }

    private fun plan(name: String, acc: AccountInternal): List<WorkoutPlanItem> {
        val outstanding = acc.outstanding()
        if (outstanding <= 0 || acc.bankrupt) return emptyList()
        val available = ACTIONS.filter { it.account == name }
        val totalGross = max(1.0, max(acc.grossDU, outstanding))
        val tendencies = acc.tranches.groupBy { it.tendency }.mapValues { e -> e.value.sumOf { it.du } }.entries.sortedByDescending { it.value }.map { it.key }
        val tendencyBag = if (tendencies.isEmpty()) listOf("dynamic") else tendencies
        val plannedByAction = mutableMapOf<String, Double>()
        val plannedByRegion = mutableMapOf<String, Double>()
        val result = mutableListOf<WorkoutPlanItem>()
        var remaining = outstanding
        var lastAction = ""
        var repeated = 0
        var attempt = 0
        val blocked = mutableSetOf<String>()
        val maxItems = min(12, max(1, ceil(remaining / 8.0).toInt()))
        while (remaining > .8 && result.size < maxItems && attempt < 80) {
            attempt++
            val tendency = tendencyBag[result.size % tendencyBag.size]
            val candidates = available.mapNotNull { def ->
                if (blocked.contains(def.id)) return@mapNotNull null
                val capLeft = totalGross * def.maxShare - (acc.paidByAction[def.id] ?: 0.0) - (plannedByAction[def.id] ?: 0.0)
                if (capLeft <= .5) return@mapNotNull null
                var regionLeft = Double.POSITIVE_INFINITY
                if (name == "legs") {
                    val cap = totalGross * (LEG_REGION_CAPS[def.region] ?: .25)
                    regionLeft = cap - (acc.paidByRegion[def.region] ?: 0.0) - (plannedByRegion[def.region] ?: 0.0)
                    if (regionLeft <= .5) return@mapNotNull null
                }
                var score = def.weight * tendencyBoost(def, tendency)
                if (lastAction == def.id) score *= if (repeated >= 1) .08 else .28
                if (acc.fatigue >= 4 && def.tags.contains("boss")) score *= .45
                score *= .82 + hash01("$sessionSeed:${result.size}:${def.id}:$remaining") * .36
                Triple(def, score, Pair(capLeft, regionLeft))
            }.sortedByDescending { it.second }
            val chosen = candidates.firstOrNull() ?: break
            val def = chosen.first
            val level = chooseLevel(tendency, acc.fatigue, "$sessionSeed:${result.size}:${def.id}")
            val variant = variant(def, level)
            val desired = min(remaining, min(chosen.third.first, min(chosen.third.second, 6 + hash01("${def.id}:$remaining") * 6)))
            var units = if (def.unit == "seconds") {
                max(10.0, min(50.0, round(desired / max(.1, def.baseDU * variant.modifier) * 10 / 5) * 5))
            } else {
                var raw = max(1.0, round(desired / max(.1, def.baseDU * variant.modifier)))
                if (variant.streakMin > 0) raw = max(variant.streakMin.toDouble(), round(raw / variant.streakMin) * variant.streakMin)
                min(raw, if (def.side) 12.0 else 18.0)
            }
            var itemDU = actionDU(def, variant, units)
            val hardCap = min(remaining * 1.05, min(chosen.third.first * 1.05, chosen.third.second * 1.05))
            while (itemDU > hardCap && units > 1) {
                units -= if (def.unit == "seconds") 5 else 1
                itemDU = actionDU(def, variant, units)
            }
            if (variant.streakMin > 0 && units > 0.0 && units < variant.streakMin.toDouble()) {
                units = variant.streakMin.toDouble(); itemDU = actionDU(def, variant, units)
                if (itemDU > hardCap * 1.15) { blocked += def.id; continue }
            }
            if (itemDU <= 0) break
            plannedByAction[def.id] = r2((plannedByAction[def.id] ?: 0.0) + itemDU)
            plannedByRegion[def.region] = r2((plannedByRegion[def.region] ?: 0.0) + itemDU)
            result += WorkoutPlanItem(
                actionId = def.id,
                action = def.name,
                account = name,
                level = level,
                levelLabel = variant.label,
                units = units,
                unitLabel = if (def.unit == "seconds") "${round(units).toInt()} 秒" else "${round(units).toInt()} 次${if (def.side) "/边" else ""}",
                estimatedDU = itemDU,
                instruction = variant.instruction,
                tempo = variant.tempo,
                source = acc.tranches.maxByOrNull { it.du }?.source ?: "历史欠债",
            )
            remaining = r2(max(0.0, remaining - itemDU))
            if (lastAction == def.id) repeated++ else { lastAction = def.id; repeated = 0 }
        }
        return result
    }

    private fun save() {
        val root = JSONObject()
            .put("enabled", enabled)
            .put("matchId", matchId)
            .put("player", player)
            .put("mapNumber", mapNumber)
            .put("consecutiveFirstDeaths", consecutiveFirstDeaths)
            .put("writtenOffDU", writtenOffDU)
            .put("sessionSeed", sessionSeed)
            .put("abs", accountJson(abs))
            .put("legs", accountJson(legs))
        prefs.edit().putString(KEY_STATE, root.toString()).apply()
    }

    private fun accountJson(acc: AccountInternal): JSONObject {
        val tranches = JSONArray()
        acc.tranches.forEach { t -> tranches.put(JSONObject().put("du", t.du).put("principal", t.principalDU).put("tendency", t.tendency).put("source", t.source).put("account", t.account).put("transferSource", t.transferSource).put("transferMultiplier", t.transferMultiplier).put("eligibleBuyback", t.eligibleBuyback)) }
        return JSONObject()
            .put("gross", acc.grossDU).put("paid", acc.paidDU).put("fatigue", acc.fatigue)
            .put("bankrupt", acc.bankrupt).put("reopenUsed", acc.reopenUsed).put("tranches", tranches)
            .put("paidByAction", JSONObject(acc.paidByAction as Map<*, *>))
            .put("paidByRegion", JSONObject(acc.paidByRegion as Map<*, *>))
    }

    private fun load() {
        val text = prefs.getString(KEY_STATE, null) ?: return
        runCatching {
            val root = JSONObject(text)
            enabled = root.optBoolean("enabled")
            matchId = root.optString("matchId")
            player = root.optString("player")
            mapNumber = root.optInt("mapNumber", 1)
            consecutiveFirstDeaths = root.optInt("consecutiveFirstDeaths", 0)
            writtenOffDU = root.optDouble("writtenOffDU", 0.0)
            sessionSeed = root.optString("sessionSeed").ifBlank { System.currentTimeMillis().toString() }
            readAccount(root.optJSONObject("abs"), abs)
            readAccount(root.optJSONObject("legs"), legs)
        }
    }

    private fun readAccount(obj: JSONObject?, acc: AccountInternal) {
        if (obj == null) return
        acc.grossDU = obj.optDouble("gross", 0.0); acc.paidDU = obj.optDouble("paid", 0.0); acc.fatigue = obj.optDouble("fatigue", 0.0)
        acc.bankrupt = obj.optBoolean("bankrupt"); acc.reopenUsed = obj.optBoolean("reopenUsed")
        acc.tranches.clear()
        val arr = obj.optJSONArray("tranches") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            acc.tranches += DebtTranche(t.optDouble("du"), t.optDouble("principal"), t.optString("tendency", "dynamic"), t.optString("source"), t.optString("account"), t.optString("transferSource").ifBlank { null }, t.optDouble("transferMultiplier", 1.0), t.optBoolean("eligibleBuyback"))
        }
        acc.paidByAction.clear(); obj.optJSONObject("paidByAction")?.keys()?.forEach { key -> acc.paidByAction[key] = obj.getJSONObject("paidByAction").optDouble(key) }
        acc.paidByRegion.clear(); obj.optJSONObject("paidByRegion")?.keys()?.forEach { key -> acc.paidByRegion[key] = obj.getJSONObject("paidByRegion").optDouble(key) }
    }
}
