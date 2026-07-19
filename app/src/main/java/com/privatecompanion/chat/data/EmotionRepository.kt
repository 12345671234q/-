package com.privatecompanion.chat.data

import android.content.Context
import com.privatecompanion.chat.model.EmotionState
import kotlin.math.exp

/** Local fuzzy state used only for tone. It is short-lived and never becomes factual memory. */
class EmotionRepository(context: Context) {
    private val prefs = context.getSharedPreferences("companion_emotion_state", Context.MODE_PRIVATE)

    fun load(): EmotionState = EmotionState(
        warmth = prefs.getFloat("warmth", 0.45f).toDouble(),
        teasing = prefs.getFloat("teasing", 0.10f).toDouble(),
        concern = prefs.getFloat("concern", 0.05f).toDouble(),
        jealousy = prefs.getFloat("jealousy", 0f).toDouble(),
        anger = prefs.getFloat("anger", 0f).toDouble(),
        hurt = prefs.getFloat("hurt", 0f).toDouble(),
        embarrassment = prefs.getFloat("embarrassment", 0f).toDouble(),
        updatedAt = prefs.getLong("updated_at", System.currentTimeMillis()),
    )

    fun observeUserMessage(text: String, now: Long = System.currentTimeMillis()): EmotionState {
        val previous = decayed(load(), now)
        val state = previous.copy(
            warmth = bump(previous.warmth, text, WARM_MARKERS, 0.18),
            teasing = bump(previous.teasing, text, TEASING_MARKERS, 0.26),
            concern = bump(previous.concern, text, CONCERN_MARKERS, 0.32),
            jealousy = bump(previous.jealousy, text, JEALOUSY_MARKERS, 0.30),
            anger = bump(previous.anger, text, ANGER_MARKERS, 0.34),
            hurt = bump(previous.hurt, text, HURT_MARKERS, 0.32),
            embarrassment = bump(previous.embarrassment, text, EMBARRASSMENT_MARKERS, 0.38),
            updatedAt = now,
        ).let { raw ->
            // "不许提/闭嘴" alongside embarrassment is usually playful protest, not proof of anger.
            if (raw.embarrassment >= 0.45 && text.containsAny(PLAYFUL_PROTEST_MARKERS)) {
                raw.copy(teasing = (raw.teasing + 0.16).coerceAtMost(1.0), anger = (raw.anger * 0.55).coerceIn(0.0, 1.0))
            } else raw
        }
        save(state)
        return state
    }

    fun dynamicTemperature(text: String, state: EmotionState = load()): Double {
        val factualRecall = text.containsAny(FACT_RECALL_MARKERS)
        if (factualRecall) return 0.55
        return (0.72 + state.teasing * 0.12 + state.embarrassment * 0.06 + state.warmth * 0.04 -
            state.anger * 0.05 - state.concern * 0.03).coerceIn(0.62, 0.90)
    }

    fun exportJson() = org.json.JSONObject()
        .put("warmth", load().warmth)
        .put("teasing", load().teasing)
        .put("concern", load().concern)
        .put("jealousy", load().jealousy)
        .put("anger", load().anger)
        .put("hurt", load().hurt)
        .put("embarrassment", load().embarrassment)
        .put("updatedAt", load().updatedAt)

    private fun save(state: EmotionState) {
        prefs.edit()
            .putFloat("warmth", state.warmth.toFloat())
            .putFloat("teasing", state.teasing.toFloat())
            .putFloat("concern", state.concern.toFloat())
            .putFloat("jealousy", state.jealousy.toFloat())
            .putFloat("anger", state.anger.toFloat())
            .putFloat("hurt", state.hurt.toFloat())
            .putFloat("embarrassment", state.embarrassment.toFloat())
            .putLong("updated_at", state.updatedAt)
            .apply()
    }

    private fun decayed(state: EmotionState, now: Long): EmotionState {
        val hours = ((now - state.updatedAt).coerceAtLeast(0L) / 3_600_000.0)
        val shortDecay = exp(-hours / 4.0)
        val warmDecay = exp(-hours / 18.0)
        return state.copy(
            warmth = 0.42 + (state.warmth - 0.42) * warmDecay,
            teasing = state.teasing * shortDecay,
            concern = state.concern * shortDecay,
            jealousy = state.jealousy * shortDecay,
            anger = state.anger * shortDecay,
            hurt = state.hurt * shortDecay,
            embarrassment = state.embarrassment * shortDecay,
            updatedAt = now,
        )
    }

    private fun bump(base: Double, text: String, markers: List<String>, amount: Double): Double =
        if (text.containsAny(markers)) (base + amount).coerceAtMost(1.0) else base.coerceIn(0.0, 1.0)

    private fun String.containsAny(markers: List<String>): Boolean = markers.any(::contains)

    private companion object {
        val WARM_MARKERS = listOf("宝宝", "想你", "喜欢你", "爱你", "抱抱", "亲亲", "陪我", "乖")
        val TEASING_MARKERS = listOf("嘴硬", "逗你", "坏蛋", "流氓", "哼", "不理你", "你敢", "有本事")
        val CONCERN_MARKERS = listOf("难受", "不舒服", "生病", "疼", "害怕", "累死", "睡不着", "哭了")
        val JEALOUSY_MARKERS = listOf("别人", "男生", "男朋友", "前任", "吃醋", "约我", "追我")
        val ANGER_MARKERS = listOf("生气", "真的讨厌", "别烦我", "滚", "过分", "气死我了")
        val HURT_MARKERS = listOf("委屈", "伤心", "你不在乎", "你不喜欢", "不想理你", "难过")
        val EMBARRASSMENT_MARKERS = listOf("脸红", "害羞", "红着脸", "不许提", "闭嘴", "别说了", "丢死人")
        val PLAYFUL_PROTEST_MARKERS = listOf("不许提", "闭嘴", "别说了", "讨厌", "坏蛋")
        val FACT_RECALL_MARKERS = listOf("记得", "之前", "以前", "上次", "昨天", "前天", "哪天", "什么时候", "发生过", "说过", "答应过")
    }
}
