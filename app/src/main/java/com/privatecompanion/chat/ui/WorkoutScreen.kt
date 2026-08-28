package com.privatecompanion.chat.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val WORKOUT_PREFS = "workout_remote"
private const val PREF_BASE_URL = "base_url"
private const val PREF_TOKEN = "token"

private data class WorkoutPlanItem(
    val account: String,
    val actionId: String,
    val action: String,
    val level: Int,
    val levelLabel: String,
    val units: Double,
    val unitLabel: String,
    val estimatedDU: Double,
    val instruction: String,
    val tempo: String,
    val source: String,
)

private data class WorkoutAccount(
    val name: String,
    val outstandingDU: Double,
    val grossDU: Double,
    val paidDU: Double,
    val fatigue: Double,
    val bankrupt: Boolean,
    val reopenUsed: Boolean,
    val plan: List<WorkoutPlanItem>,
)

private data class WorkoutRemoteState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val enabled: Boolean = false,
    val matchTitle: String = "",
    val player: String = "",
    val mapNumber: Int = 1,
    val abs: WorkoutAccount = emptyAccount("abs"),
    val legs: WorkoutAccount = emptyAccount("legs"),
    val writtenOffDU: Double = 0.0,
    val lastUpdated: String = "",
    val error: String = "",
)

private fun emptyAccount(name: String) = WorkoutAccount(name, 0.0, 0.0, 0.0, 0.0, false, false, emptyList())

private class WorkoutRemoteClient {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun status(baseUrl: String, token: String): WorkoutRemoteState = withContext(Dispatchers.IO) {
        val request = requestBuilder(baseUrl, "/api/status", token).get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(180)}")
            parseState(JSONObject(text))
        }
    }

    suspend fun post(baseUrl: String, token: String, path: String, body: JSONObject): WorkoutRemoteState = withContext(Dispatchers.IO) {
        val request = requestBuilder(baseUrl, path, token)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                error(message.ifBlank { "HTTP ${response.code}" })
            }
            // Mutation routes return workout only, so refresh the authoritative status.
            status(baseUrl, token)
        }
    }

    private fun requestBuilder(baseUrl: String, path: String, token: String): Request.Builder {
        val root = baseUrl.trim().trimEnd('/')
        require(root.startsWith("http://") || root.startsWith("https://")) { "服务器地址要以 http:// 或 https:// 开头" }
        return Request.Builder()
            .url(root + path)
            .header("Authorization", "Bearer $token")
            .header("X-Live-Watch-App-Token", token)
    }

    private fun parseState(root: JSONObject): WorkoutRemoteState {
        val workout = root.optJSONObject("workout") ?: JSONObject()
        val accounts = workout.optJSONObject("accounts") ?: JSONObject()
        return WorkoutRemoteState(
            connected = true,
            running = root.optBoolean("running"),
            enabled = workout.optBoolean("enabled"),
            matchTitle = root.optString("matchTitle"),
            player = workout.optString("player").ifBlank { root.optString("player") },
            mapNumber = workout.optInt("mapNumber", 1),
            abs = parseAccount("abs", accounts.optJSONObject("abs")),
            legs = parseAccount("legs", accounts.optJSONObject("legs")),
            writtenOffDU = workout.optDouble("writtenOffDU", 0.0),
            lastUpdated = workout.optString("lastUpdated"),
        )
    }

    private fun parseAccount(name: String, obj: JSONObject?): WorkoutAccount {
        if (obj == null) return emptyAccount(name)
        val planJson = obj.optJSONArray("plan")
        val plan = buildList {
            if (planJson != null) {
                for (i in 0 until planJson.length()) {
                    val item = planJson.optJSONObject(i) ?: continue
                    add(
                        WorkoutPlanItem(
                            account = item.optString("account", name),
                            actionId = item.optString("actionId"),
                            action = item.optString("action"),
                            level = item.optInt("level", 1),
                            levelLabel = item.optString("levelLabel"),
                            units = item.optDouble("units", 0.0),
                            unitLabel = item.optString("unitLabel"),
                            estimatedDU = item.optDouble("estimatedDU", 0.0),
                            instruction = item.optString("instruction"),
                            tempo = item.optString("tempo"),
                            source = item.optString("source"),
                        )
                    )
                }
            }
        }
        return WorkoutAccount(
            name = name,
            outstandingDU = obj.optDouble("outstandingDU", 0.0),
            grossDU = obj.optDouble("grossDU", 0.0),
            paidDU = obj.optDouble("paidDU", 0.0),
            fatigue = obj.optDouble("fatigue", 0.0),
            bankrupt = obj.optBoolean("bankrupt"),
            reopenUsed = obj.optBoolean("reopenUsed"),
            plan = plan,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(WORKOUT_PREFS, Context.MODE_PRIVATE) }
    val client = remember { WorkoutRemoteClient() }
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf(prefs.getString(PREF_BASE_URL, "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString(PREF_TOKEN, "") ?: "") }
    var state by remember { mutableStateOf(WorkoutRemoteState()) }
    var busy by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf("") }

    suspend fun refresh() {
        if (baseUrl.isBlank()) return
        runCatching { client.status(baseUrl, token) }
            .onSuccess { state = it; connectionError = "" }
            .onFailure { connectionError = it.message.orEmpty(); state = state.copy(connected = false) }
    }

    fun mutate(path: String, body: JSONObject) {
        if (busy || baseUrl.isBlank()) return
        scope.launch {
            busy = true
            runCatching { client.post(baseUrl, token, path, body) }
                .onSuccess { state = it; connectionError = "" }
                .onFailure { connectionError = it.message.orEmpty() }
            busy = false
        }
    }

    LaunchedEffect(baseUrl, token) {
        while (true) {
            refresh()
            delay(2_000)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text("赛事锻炼") })
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("云端 Live Watch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("服务器地址") },
                        placeholder = { Text("https://watch.example.com") },
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("App Token") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            prefs.edit().putString(PREF_BASE_URL, baseUrl.trim()).putString(PREF_TOKEN, token.trim()).apply()
                            scope.launch { refresh() }
                        }) { Text("保存并连接") }
                        OutlinedButton(onClick = { scope.launch { refresh() } }, enabled = !busy) { Text("刷新") }
                        Text(if (state.connected) "● 已连接" else "○ 未连接", color = if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    if (connectionError.isNotBlank()) Text(connectionError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (state.connected) {
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.matchTitle.ifBlank { "当前比赛" }, style = MaterialTheme.typography.titleMedium)
                        Text("关注：${state.player.ifBlank { "未选择" }} · Map ${state.mapNumber}")
                        Text("监听：${if (state.running) "进行中" else "未运行"} · 锻炼模式：${if (state.enabled) "已开启" else "已关闭"}")
                        if (state.writtenOffDU > 0) Text("已核销 ${formatDu(state.writtenOffDU)} DU", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy,
                                onClick = { mutate("/api/workout/config", JSONObject().put("enabled", !state.enabled)) },
                            ) { Text(if (state.enabled) "关闭锻炼模式" else "开启锻炼模式") }
                        }
                    }
                }

                AccountCard(
                    title = "腹肌账户",
                    account = state.abs,
                    busy = busy,
                    onSettle = { item, units -> mutate("/api/workout/settle", JSONObject().put("account", "abs").put("actionId", item.actionId).put("level", item.level).put("units", units)) },
                    onFatigue = { level -> mutate("/api/workout/fatigue", JSONObject().put("account", "abs").put("level", level)) },
                    onBankrupt = { mutate("/api/workout/bankrupt", JSONObject().put("account", "abs")) },
                    onReopen = { mutate("/api/workout/reopen", JSONObject().put("account", "abs")) },
                )
                AccountCard(
                    title = "腿部账户",
                    account = state.legs,
                    busy = busy,
                    onSettle = { item, units -> mutate("/api/workout/settle", JSONObject().put("account", "legs").put("actionId", item.actionId).put("level", item.level).put("units", units)) },
                    onFatigue = { level -> mutate("/api/workout/fatigue", JSONObject().put("account", "legs").put("level", level)) },
                    onBankrupt = { mutate("/api/workout/bankrupt", JSONObject().put("account", "legs")) },
                    onReopen = { mutate("/api/workout/reopen", JSONObject().put("account", "legs")) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccountCard(
    title: String,
    account: WorkoutAccount,
    busy: Boolean,
    onSettle: (WorkoutPlanItem, Double) -> Unit,
    onFatigue: (Int) -> Unit,
    onBankrupt: () -> Unit,
    onReopen: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("欠 ${formatDu(account.outstandingDU)} DU · 已还 ${formatDu(account.paidDU)} DU", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    if (account.bankrupt) "停牌" else "疲劳 ${formatDu(account.fatigue)}/5",
                    color = if (account.bankrupt) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text("当前感觉", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..5).forEach { level ->
                    AssistChip(onClick = { onFatigue(level) }, enabled = !busy, label = { Text(level.toString()) })
                }
            }

            if (account.bankrupt) {
                Button(onClick = onReopen, enabled = !busy && !account.reopenUsed) {
                    Text(if (account.reopenUsed) "本场已用过复牌" else "恢复后复牌一次")
                }
            } else if (account.outstandingDU > 0.01) {
                OutlinedButton(onClick = onBankrupt, enabled = !busy) { Text("技术力竭 · 停牌转债") }
            }

            if (account.plan.isEmpty()) {
                Text(if (account.outstandingDU <= 0.01) "账单已清空。" else if (account.bankrupt) "账户已停牌，剩余债务等待转移。" else "正在等待动作规划…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("当前动作组合", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                account.plan.forEach { item ->
                    PlanItemCard(item = item, busy = busy, onSettle = onSettle)
                }
            }
        }
    }
}

@Composable
private fun PlanItemCard(item: WorkoutPlanItem, busy: Boolean, onSettle: (WorkoutPlanItem, Double) -> Unit) {
    var partial by remember(item.actionId, item.units, item.level) { mutableStateOf("") }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${item.action} · ${item.levelLabel}", fontWeight = FontWeight.Bold)
                Text("≈ ${formatDu(item.estimatedDU)} DU", color = MaterialTheme.colorScheme.primary)
            }
            Text(item.unitLabel)
            if (item.tempo.isNotBlank()) Text("节奏：${item.tempo}", style = MaterialTheme.typography.bodySmall)
            if (item.instruction.isNotBlank()) Text(item.instruction, style = MaterialTheme.typography.bodySmall)
            if (item.source.isNotBlank()) Text("来源：${item.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onSettle(item, item.units) }, enabled = !busy) { Text("完成整单") }
                OutlinedTextField(
                    value = partial,
                    onValueChange = { partial = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    modifier = Modifier.width(100.dp),
                    singleLine = true,
                    label = { Text("完成量") },
                )
                TextButton(
                    onClick = { partial.toDoubleOrNull()?.takeIf { it > 0 }?.let { onSettle(item, it.coerceAtMost(item.units)) } },
                    enabled = !busy && partial.toDoubleOrNull()?.let { it > 0 } == true,
                ) { Text("部分结算") }
            }
        }
    }
}

private fun formatDu(value: Double): String = if (kotlin.math.abs(value - value.toInt()) < 0.01) value.toInt().toString() else "%.1f".format(value)
