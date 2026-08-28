package com.csworkout.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CSWorkoutApp(vm: WorkoutViewModel = viewModel()) {
    val state = vm.state
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        if (state.error.isNotBlank()) {
            snackbar.showSnackbar(state.error)
            vm.clearError()
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            when (state.page) {
                AppPage.MATCHES -> MatchListScreen(state, vm)
                AppPage.MATCH_DETAIL -> MatchDetailScreen(state, vm)
                AppPage.WORKOUT -> WorkoutScreen(state, vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchListScreen(state: WorkoutUiState, vm: WorkoutViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("CS 赛事锻炼", fontWeight = FontWeight.Bold)
                    Text("打开就选比赛，别再填服务器地址了 😈", style = MaterialTheme.typography.labelSmall)
                }
            },
            actions = { TextButton(onClick = vm::refreshMatches) { Text("刷新") } },
        )
        if (state.loading && state.matches.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        val live = state.matches.filter { it.status == MatchStatus.LIVE }
        val upcoming = state.matches.filter { it.status == MatchStatus.UPCOMING }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("正在进行", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("数据来自 5E 赛事列表；点进去会自动读取当前阵容。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (live.isEmpty()) item { EmptyCard("现在没有识别到进行中的比赛。下面还有即将开始的。") }
            items(live, key = { it.id }) { MatchCard(it, onClick = { vm.openMatch(it) }) }
            item { Spacer(Modifier.height(6.dp)); Text("即将开始", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            if (upcoming.isEmpty()) item { EmptyCard("当前列表里没有即将开始的比赛。") }
            items(upcoming, key = { it.id }) { MatchCard(it, onClick = { vm.openMatch(it) }) }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun MatchCard(match: ScheduleMatch, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (match.status == MatchStatus.LIVE) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (match.status == MatchStatus.LIVE) "🔴 LIVE" else formatTime(match.scheduledAtMillis), fontWeight = FontWeight.Bold)
                Text("BO${match.bestOf}${match.currentMap?.let { " · Map $it" }.orEmpty()}", style = MaterialTheme.typography.labelLarge)
            }
            Text("${match.team1.name}  ${match.team1.seriesScore} : ${match.team2.seriesScore}  ${match.team2.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(match.tournament, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (match.stage.isNotBlank()) Text(match.stage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchDetailScreen(state: WorkoutUiState, vm: WorkoutViewModel) {
    val snap = state.snapshot
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("选比赛里的那个人") },
            navigationIcon = { TextButton(onClick = vm::backToMatch) { Text("‹ 返回") } },
            actions = { TextButton(onClick = vm::refreshSelectedMatch) { Text("刷新") } },
        )
        if (state.loading && snap == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        if (snap == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text("暂时拿不到这场比赛的数据。") }
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(snap.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(snap.tournament, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Map ${snap.mapNumber} · ${snap.mapName.ifBlank { "尚未确定地图" }}")
                        Text("${snap.team1.name} ${snap.team1MapScore} : ${snap.team2MapScore} ${snap.team2.name}", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            item {
                Text("选择你要关注的选手", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("选好以后，击杀、首死、多杀和回合胜负都会直接在手机本地记 DU。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (snap.players.isEmpty()) {
                item {
                    EmptyCard("5E 当前还没有给出选手阵容。比赛开始后点右上角“刷新”就会自己出来，不用再贴链接。")
                }
            } else {
                item { TeamRoster("${snap.team1.name} · ${snap.t1Side.ifBlank { "未分边" }}", snap.players.filter { it.teamSlot == "t1" }, state.selectedPlayer, vm::selectPlayer) }
                item { TeamRoster("${snap.team2.name} · ${snap.t2Side.ifBlank { "未分边" }}", snap.players.filter { it.teamSlot == "t2" }, state.selectedPlayer, vm::selectPlayer) }
            }
            if (state.selectedPlayer != null) {
                item {
                    Button(onClick = vm::startWorkout, modifier = Modifier.fillMaxWidth()) {
                        Text("关注 ${state.selectedPlayer.name} · 开始记账 😈")
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TeamRoster(title: String, players: List<PlayerRow>, selected: PlayerRow?, onSelect: (PlayerRow) -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            players.forEach { p ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(p) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected?.name == p.name) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (selected?.name == p.name) "✓ ${p.name}" else p.name, fontWeight = FontWeight.SemiBold)
                        Text("${p.kills} / ${p.deaths}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutScreen(state: WorkoutUiState, vm: WorkoutViewModel) {
    val workout = state.workout
    val snap = state.snapshot
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(workout.player.ifBlank { "赛事锻炼" }, fontWeight = FontWeight.Bold)
                    Text("${snap?.title.orEmpty()} · Map ${workout.mapNumber}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            actions = { TextButton(onClick = vm::goHome) { Text("换比赛") } },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("实时比赛", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${snap?.team1?.name ?: "?"} ${snap?.team1MapScore ?: 0} : ${snap?.team2MapScore ?: 0} ${snap?.team2?.name ?: "?"}")
                        Text("${snap?.mapName.orEmpty()} · 每秒检查新事件", style = MaterialTheme.typography.bodySmall)
                        if (workout.writtenOffDU > 0) Text("已核销 ${fmt(workout.writtenOffDU)} DU", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { AccountCard("腹肌账户", "abs", workout.abs, vm) }
            item { AccountCard("腿部账户", "legs", workout.legs, vm) }
            item {
                Text("最近发生", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (state.events.isEmpty()) item { EmptyCard("已经在监听。下一次击杀、首死或回合结束会自己跳出来。") }
            items(state.events.take(20), key = { "${it.createdAt}-${it.type}-${it.title}" }) { event -> EventCard(event) }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun AccountCard(title: String, accountName: String, acc: WorkoutAccountState, vm: WorkoutViewModel) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("欠 ${fmt(acc.outstandingDU)} DU · 已还 ${fmt(acc.paidDU)} DU")
                }
                Text(if (acc.bankrupt) "停牌" else "疲劳 ${fmt(acc.fatigue)}/5", color = if (acc.bankrupt) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Text("现在这块肌肉什么感觉？", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..5).forEach { level ->
                    AssistChip(onClick = { vm.setFatigue(accountName, level) }, label = { Text(level.toString()) })
                }
            }
            if (acc.bankrupt) {
                Button(onClick = { vm.reopen(accountName) }, enabled = !acc.reopenUsed) {
                    Text(if (acc.reopenUsed) "本场复牌已用过" else "恢复一些了 · 原价复牌一次")
                }
            } else if (acc.outstandingDU > .01) {
                OutlinedButton(onClick = { vm.bankrupt(accountName) }) { Text("技术力竭 · 停牌转债") }
            }
            if (acc.plan.isEmpty()) {
                Text(if (acc.outstandingDU <= .01) "当前没有欠账。" else "正在重新组合动作…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("系统给你的组合", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                acc.plan.forEach { item -> PlanCard(accountName, item, vm) }
            }
        }
    }
}

@Composable
private fun PlanCard(accountName: String, item: WorkoutPlanItem, vm: WorkoutViewModel) {
    var partialOpen by remember(item.actionId, item.level, item.units) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${item.action} · ${item.levelLabel}", fontWeight = FontWeight.Bold)
                Text(item.unitLabel, fontWeight = FontWeight.Bold)
            }
            Text("约 ${fmt(item.estimatedDU)} DU · 来源：${item.source}", style = MaterialTheme.typography.bodySmall)
            Text(item.tempo, style = MaterialTheme.typography.bodySmall)
            Text(item.instruction, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.settle(accountName, item) }) { Text("完成") }
                OutlinedButton(onClick = { partialOpen = true }) { Text("部分完成") }
            }
        }
    }
    if (partialOpen) PartialDialog(item, onDismiss = { partialOpen = false }) { units ->
        vm.settle(accountName, item, units)
        partialOpen = false
    }
}

@Composable
private fun PartialDialog(item: WorkoutPlanItem, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("部分完成 · ${item.action}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("原账单：${item.unitLabel}")
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                    label = { Text(if (item.unitLabel.contains("秒")) "实际完成多少秒" else "实际完成多少次") },
                    singleLine = true,
                )
                Text("只按真正完成的量扣 DU；连续困难动作没达到起做量时，会自动降级计价。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = (value.toDoubleOrNull() ?: 0.0) > 0, onClick = { onConfirm(value.toDoubleOrNull() ?: 0.0) }) { Text("结算") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun EventCard(event: LiveEvent) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(event.title, fontWeight = FontWeight.SemiBold)
            if (event.detail.isNotBlank()) Text(event.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(text, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fmt(v: Double): String = if (kotlin.math.abs(v - v.toInt()) < .01) v.toInt().toString() else "%.1f".format(Locale.US, v)

private fun formatTime(epochMillis: Long): String {
    if (epochMillis <= 0) return "待定"
    return DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
}
