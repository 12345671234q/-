package com.privatecompanion.chat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privatecompanion.chat.CompanionViewModel
import com.privatecompanion.chat.R
import com.privatecompanion.chat.model.ApiSettings
import com.privatecompanion.chat.model.ChatMessage
import com.privatecompanion.chat.model.CompanionUiState
import com.privatecompanion.chat.model.MessageRole
import com.privatecompanion.chat.model.LongTermMemory
import com.privatecompanion.chat.model.MapSettings
import com.privatecompanion.chat.model.Persona
import com.privatecompanion.chat.model.ProactiveSettings
import com.privatecompanion.chat.notifications.CompanionNotifications
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class AppTab(val title: String, val symbol: String) {
    CHAT("聊天", "✦"),
    DEVICE("设备", "⌚"),
    SETTINGS("设定", "⚙"),
}

private const val CHAT_TIMESTAMP_INTERVAL_MS = 5 * 60_000L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompanionApp(
    viewModel: CompanionViewModel = viewModel(),
    openChatRequest: Int = 0,
) {
    var selectedTab by remember { mutableStateOf(AppTab.CHAT) }
    val snackbar = remember { SnackbarHostState() }
    val state = viewModel.state
    val keyboardVisible = WindowInsets.isImeVisible
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(openChatRequest) {
        if (openChatRequest > 0) selectedTab = AppTab.CHAT
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.syncFromStore()
                viewModel.refreshUsageStats()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action == CompanionNotifications.ACTION_NEW_MESSAGE) {
                    viewModel.syncFromStore()
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(CompanionNotifications.ACTION_NEW_MESSAGE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.memoryNotice) {
        state.memoryNotice?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMemoryNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // While typing, use the freed space for the conversation and keep the composer
            // directly above the keyboard instead of stacking a navigation bar below it.
            if (!keyboardVisible) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Text(tab.symbol) },
                            label = { Text(tab.title) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val locationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            if (permissions.values.any { it }) {
                viewModel.refreshLocation()
            }
        }
        val usageAccessLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) {
            viewModel.syncFromStore()
            viewModel.refreshUsageStats()
        }
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) {
            viewModel.syncFromStore()
        }
        val exportBackupLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(viewModel.exportPortableBackup())
                    } ?: error("无法写入所选文件")
                }.onSuccess {
                    Toast.makeText(context, "聊天与记忆备份已导出", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "导出失败：${it.message.orEmpty()}", Toast.LENGTH_LONG).show()
                }
            }
        }
        when (selectedTab) {
            AppTab.CHAT -> ChatScreen(
                state = state,
                onSend = viewModel::send,
                onRegenerate = viewModel::regenerateLastReply,
                modifier = Modifier.padding(padding),
            )
            AppTab.DEVICE -> DeviceScreen(
                state = state,
                onRefresh = viewModel::refreshDeviceSnapshot,
                onRequestLocation = {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                },
                onOpenUsageAccess = {
                    val packageIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    runCatching { usageAccessLauncher.launch(packageIntent) }
                        .onFailure {
                            usageAccessLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                },
                onRefreshUsage = viewModel::refreshUsageStats,
                onRequestNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        usageAccessLauncher.launch(intent)
                    }
                },
                onOpenNotificationSettings = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    usageAccessLauncher.launch(intent)
                },
                onOpenBackgroundLocationSettings = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    usageAccessLauncher.launch(intent)
                },
                onTestNotification = {
                    CompanionNotifications.showMessage(
                        context = context,
                        senderName = state.persona.name,
                        content = "主动消息通知测试。能看到这条，后台抓包的弹窗就能正常显示。",
                        messageId = System.nanoTime(),
                    )
                },
                modifier = Modifier.padding(padding),
            )
            AppTab.SETTINGS -> SettingsScreen(
                state = state,
                onSavePersona = viewModel::savePersona,
                onSaveApi = viewModel::saveApiSettings,
                onSaveMapSettings = viewModel::saveMapSettings,
                onSaveProactive = viewModel::saveProactiveSettings,
                onClearHistory = viewModel::clearHistory,
                onClearLongTermMemories = viewModel::clearLongTermMemories,
                onAddMemory = viewModel::addMemory,
                onUpdateMemory = viewModel::updateMemory,
                onDeleteMemory = viewModel::deleteMemory,
                onExportData = {
                    exportBackupLauncher.launch("jimo-travel-backup-${LocalDate.now()}.json")
                },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChatScreen(
    state: CompanionUiState,
    onSend: (String) -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.isSending) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher),
                        contentDescription = null,
                        modifier = Modifier.size(38.dp).clip(CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(state.persona.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${state.relationshipProfile.stage.label} · 亲密度 ${state.relationshipProfile.affection}/1000",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
        )
        if (state.messages.isEmpty()) {
            WelcomePane(state.persona.name, Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
                    val previous = state.messages.getOrNull(index - 1)
                    if (previous == null || message.createdAt - previous.createdAt >= CHAT_TIMESTAMP_INTERVAL_MS) {
                        ChatTimestamp(message.createdAt)
                    }
                    MessageBubble(
                        message = message,
                        showRegenerate = !state.isSending &&
                            message.role == MessageRole.ASSISTANT &&
                            message.id == state.messages.lastOrNull()?.id,
                        onRegenerate = onRegenerate,
                    )
                }
                if (state.isSending) item { TypingBubble(state.persona.name) }
            }
        }
        Composer(
            value = draft,
            enabled = !state.isSending,
            onValueChange = { draft = it },
            onSend = {
                onSend(draft)
                draft = ""
            },
        )
    }
}

/** A WeChat-style separator: shown for the first message and after a meaningful chat gap. */
@Composable
private fun ChatTimestamp(createdAt: Long) {
    Text(
        text = formatChatTimestamp(createdAt),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private fun formatChatTimestamp(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val date = time.toLocalDate()
    val today = LocalDate.now(zone)
    val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA).format(time)
    return when {
        date == today -> "今天 $clock"
        date == today.minusDays(1) -> "昨天 $clock"
        date.year == today.year -> DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA).format(time)
        else -> DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA).format(time)
    }
}

@Composable
private fun WelcomePane(name: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)),
        )
        Spacer(Modifier.height(18.dp))
        Text("你好，我是$name", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "先在「设定」中填好 OpenAI 兼容接口。\n聊天记录、分层记忆和上下文摘要都只保存在本机。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    showRegenerate: Boolean,
    onRegenerate: () -> Unit,
) {
    val isUser = message.role == MessageRole.USER
    var reasoningOpen by remember(message.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.84f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            if (!isUser && !message.reasoning.isNullOrBlank()) {
                TextButton(onClick = { reasoningOpen = !reasoningOpen }) {
                    Text(
                        "已思考（用时 ${message.reasoningDurationSeconds ?: 1} 秒） ${if (reasoningOpen) "⌄" else "›"}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (reasoningOpen) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 1.dp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        Column(Modifier.padding(13.dp)) {
                            Text("思考过程", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(5.dp))
                            Text(message.reasoning.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(
                    topStart = 22.dp,
                    topEnd = 22.dp,
                    bottomStart = if (isUser) 22.dp else 6.dp,
                    bottomEnd = if (isUser) 6.dp else 22.dp,
                ),
                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                tonalElevation = if (isUser) 1.dp else 2.dp,
            ) {
                Text(message.content, modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp))
            }
            if (showRegenerate) {
                TextButton(
                    onClick = onRegenerate,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "重新生成回复",
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("重新生成", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun TypingBubble(name: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text("$name 正在思考…", modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp))
    }
}

@Composable
private fun Composer(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        // The activity uses adjustResize. Applying imePadding here as well creates a second
        // keyboard-height spacer, which was the blank area visible above the keyboard.
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            enabled = enabled,
            placeholder = { Text("说点什么…") },
            maxLines = 4,
        )
        Spacer(Modifier.width(8.dp))
        Button(enabled = enabled && value.isNotBlank(), onClick = onSend) { Text("发送") }
    }
}

@Composable
private fun DeviceScreen(
    state: CompanionUiState,
    onRefresh: () -> Unit,
    onRequestLocation: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onRefreshUsage: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenBackgroundLocationSettings: () -> Unit,
    onTestNotification: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notificationsEnabled = state.deviceSnapshot.notificationsEnabled
    val backgroundLocationGranted = state.deviceSnapshot.backgroundLocationPermissionGranted

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("你的设备", style = MaterialTheme.typography.headlineSmall)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔔", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("主动消息通知", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "后台抓包命中后，会像微信消息一样弹出横幅并进入通知中心。点通知直接回到聊天。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (notificationsEnabled) {
                    MetricCard("通知状态", "已允许")
                    Button(onClick = onTestNotification) { Text("发送测试通知") }
                    OutlinedButton(onClick = onOpenNotificationSettings) { Text("通知样式与声音") }
                } else {
                    StatusCard("需要通知权限", "Android 13 及以上会弹出授权框；如果曾拒绝，请到系统通知设置中重新开启。")
                    Button(onClick = onRequestNotifications) { Text("允许主动消息通知") }
                    TextButton(onClick = onOpenNotificationSettings) { Text("打开系统通知设置") }
                }
            }
        }

        // Location section
        Card {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("📍", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("位置信息", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "提到位置时会刷新；深夜聊天也会按需更新，用于自然查岗和安全提醒。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                val locSnapshot = state.deviceSnapshot.location
                when {
                    !locSnapshot.isAvailable && locSnapshot.error != null -> {
                        StatusCard("需要位置权限", locSnapshot.error ?: "请在授权弹窗中选择「仅在使用时允许」或「仅此一次」。")
                        Button(onClick = onRequestLocation) { Text("授权位置信息") }
                    }
                    locSnapshot.isAvailable -> {
                        MetricCard("纬度", "%.6f".format(Locale.US, locSnapshot.latitude))
                        MetricCard("经度", "%.6f".format(Locale.US, locSnapshot.longitude))
                        locSnapshot.accuracy?.let { acc ->
                            MetricCard("精度", "约${"%.0f".format(acc)}米")
                        }
                        locSnapshot.provider?.let { MetricCard("来源", it) }
                        locSnapshot.timestamp?.let { MetricCard("获取时间", formatDeviceTime(it)) }
                        locSnapshot.address?.let { address ->
                            address.formattedAddress?.let { MetricCard("具体地址（高德）", it) }
                            address.neighborhood?.let { MetricCard("小区 / 社区", it) }
                            address.building?.let { MetricCard("建筑", it) }
                            address.streetAddress?.let { MetricCard("道路门牌", it) }
                            address.aoi?.let { MetricCard("所属区域", it) }
                            address.nearestPoi?.let { poi ->
                                MetricCard(
                                    "最近 POI",
                                    poi + address.nearestPoiDistanceMeters?.let { "（约${it}米）" }.orEmpty(),
                                )
                            }
                        }
                        locSnapshot.addressError?.let {
                            StatusCard("位置名称状态", it)
                        }
                        Text(
                            "位置名称来自高德逆地理编码；地图没有返回的楼栋或门牌不会由聊天模型猜测。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !backgroundLocationGranted) {
                            StatusCard(
                                "行程后台抓包还缺一个权限",
                                "学习抓包不受影响；要在 App 放到后台后继续核对行程位置，请到应用权限里把位置改成“始终允许”。",
                            )
                            OutlinedButton(onClick = onOpenBackgroundLocationSettings) {
                                Text("设置后台位置")
                            }
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            MetricCard("后台行程检查", "已允许")
                        }
                        OutlinedButton(onClick = { onRefresh() }) { Text("刷新位置") }
                    }
                    else -> {
                        Text(
                            "尚未获取位置。请在聊天中提及位置相关内容，或手动授权。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onRequestLocation) { Text("授权位置信息") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("\ud83d\udcf1", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("\u624b\u673a\u4f7f\u7528\u60c5\u51b5", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "\u7528\u4e8e\u5b66\u4e60\u8ba1\u5212\u62bd\u67e5\u3002\u53ea\u5728\u672c\u5730\u7edf\u8ba1\uff0c\u89e6\u53d1\u6293\u5305\u65f6\u624d\u5411\u6a21\u578b\u53d1\u9001\u7cbe\u7b80\u6458\u8981\u3002",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val usage = state.deviceSnapshot.usage
                if (!usage.permissionGranted) {
                    StatusCard(
                        "\u9700\u8981\u4f7f\u7528\u60c5\u51b5\u8bbf\u95ee",
                        "点击后在系统页面找到寂墨并允许访问。这不是普通弹窗权限。",
                    )
                    Button(onClick = onOpenUsageAccess) { Text("\u6253\u5f00\u4f7f\u7528\u60c5\u51b5\u8bbe\u7f6e") }
                } else {
                    MetricCard("\u4eca\u65e5\u53ef\u89c1\u524d\u53f0\u4f7f\u7528", "${usage.totalForegroundMinutes}\u5206\u949f")
                    MetricCard("\u5a31\u4e50 / \u6e38\u620f / \u793e\u4ea4", "${usage.distractingMinutes}\u5206\u949f")
                    usage.checkedAt?.let { MetricCard("\u672c\u6b21\u68c0\u67e5", formatDeviceTime(it)) }
                    if (usage.topApps.isEmpty()) {
                        Text(
                            "\u6682\u65f6\u6ca1\u6709\u53ef\u663e\u793a\u7684\u4f7f\u7528\u8bb0\u5f55\u3002",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("\u4f7f\u7528\u8f83\u591a\u7684 App", style = MaterialTheme.typography.labelLarge)
                        usage.topApps.take(6).forEach { app ->
                            MetricCard(
                                app.appName,
                                "${app.foregroundMinutes}\u5206\u949f" + if (app.distracting) " \u00b7 \u5a31\u4e50\u7c7b" else "",
                            )
                        }
                    }
                    usage.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = onRefreshUsage) { Text("\u5237\u65b0\u4f7f\u7528\u60c5\u51b5") }
                }
            }
        }
    }
}

private fun formatDeviceTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

@Composable
private fun StatusCard(title: String, body: String) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String) {
    Card {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SettingsScreen(
    state: CompanionUiState,
    onSavePersona: (Persona) -> Unit,
    onSaveApi: (ApiSettings) -> Unit,
    onSaveMapSettings: (MapSettings) -> Unit,
    onSaveProactive: (ProactiveSettings) -> Unit,
    onClearHistory: () -> Unit,
    onClearLongTermMemories: () -> Unit,
    onAddMemory: (String, String, Boolean, String) -> Unit,
    onUpdateMemory: (Long, String, String, Boolean, String) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onExportData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var personaEditorOpen by remember { mutableStateOf(false) }
    var apiEditorOpen by remember { mutableStateOf(false) }
    var mapEditorOpen by remember { mutableStateOf(false) }
    var proactiveEditorOpen by remember { mutableStateOf(false) }
    var matureEditorOpen by remember { mutableStateOf(false) }
    var clearDialogOpen by remember { mutableStateOf(false) }
    var memoryManagerOpen by remember { mutableStateOf(false) }
    var relationshipOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("设定", style = MaterialTheme.typography.headlineSmall)
        SettingsItem("人格", "${state.persona.name} · 可设定语气、边界和可用上下文", { personaEditorOpen = true })
        SettingsItem(
            "关系档案",
            "${state.relationshipProfile.stage.label} · 亲密度 ${state.relationshipProfile.affection}/1000 · 旅途版保持当前恋爱关系",
            { relationshipOpen = true },
        )
        SettingsItem(
            "🔞 成人向模式",
            if (state.persona.matureMode) "已开启 · 使用独立成人向提示词" else "已关闭 · 不会注入成人向提示词",
            { matureEditorOpen = true },
        )
        SettingsItem("OpenAI 兼容 API", "${state.apiSettings.model} · ${state.apiSettings.baseUrl}", { apiEditorOpen = true })
        SettingsItem(
            "位置名称服务",
            if (state.mapSettings.amapWebServiceKey.isBlank()) "未配置高德 Web 服务 Key" else "已配置高德逆地理编码",
            { mapEditorOpen = true },
        )
        SettingsItem(
            "主动消息",
            if (state.proactiveSettings.enabled) {
                "已开启 · 每天最多${state.proactiveSettings.dailyLimit}条 · 最短间隔${state.proactiveSettings.minIntervalMinutes}分钟"
            } else {
                "已关闭"
            },
            { proactiveEditorOpen = true },
        )
        SettingsItem("聊天记录", "本地保存最近 300 条原始消息，便于核对时间线", { clearDialogOpen = true })
        SettingsItem(
            "长期记忆",
            "本机 ${state.longTermMemoryCount} 条 · ${state.longTermMemories.count { !it.verified }} 条待确认 · 未确认内容不会影响聊天",
            { memoryManagerOpen = true },
        )
        SettingsItem(
            "上下文压缩",
            "自动摘要只作待审核备份，不会直接作为历史事实注入",
            { memoryManagerOpen = true },
        )
        SettingsItem("导出临时版数据", "导出聊天、记忆证据和情绪状态；不包含 API Key", onExportData)
        Spacer(Modifier.height(8.dp))
        Text(
            "隐私说明：API Key 加密存放在这台手机；正常聊天和主动抓包命中时，模型服务商会收到必要的对话上下文与精简证据。手机使用明细默认留在本地，未命中时不会发送给模型。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (personaEditorOpen) PersonaEditor(state.persona, onSavePersona) { personaEditorOpen = false }
    if (matureEditorOpen) MatureModeEditor(state.persona, onSavePersona) { matureEditorOpen = false }
    if (apiEditorOpen) ApiEditor(state.apiSettings, onSaveApi) { apiEditorOpen = false }
    if (mapEditorOpen) MapEditor(state.mapSettings, onSaveMapSettings) { mapEditorOpen = false }
    if (proactiveEditorOpen) {
        ProactiveEditor(state.proactiveSettings, onSaveProactive) { proactiveEditorOpen = false }
    }
    if (relationshipOpen) RelationshipArchiveDialog(state = state, onDismiss = { relationshipOpen = false })
    if (clearDialogOpen) {
        AlertDialog(
            onDismissRequest = { clearDialogOpen = false },
            title = { Text("清除本地聊天记录？") },
            text = { Text("此操作只会删除此 App 本地保存的聊天记录。") },
            confirmButton = { TextButton(onClick = { onClearHistory(); clearDialogOpen = false }) { Text("清除") } },
            dismissButton = { TextButton(onClick = { clearDialogOpen = false }) { Text("取消") } },
        )
    }
    if (memoryManagerOpen) MemoryManagerDialog(
        memories = state.longTermMemories,
        contextSummary = state.compressedContextSummary,
        onAdd = onAddMemory,
        onUpdate = onUpdateMemory,
        onDelete = onDeleteMemory,
        onClear = onClearLongTermMemories,
        onDismiss = { memoryManagerOpen = false },
    )
}

@Composable
private fun RelationshipArchiveDialog(state: CompanionUiState, onDismiss: () -> Unit) {
    val profile = state.relationshipProfile
    val daysKnown = if (profile.firstChatAt <= 0L) 0L else
        ((System.currentTimeMillis() - profile.firstChatAt) / 86_400_000L).coerceAtLeast(0L) + 1L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关系档案") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(profile.stage.label, style = MaterialTheme.typography.headlineSmall)
                LinearProgressIndicator(
                    progress = { profile.affection / 1000f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("亲密度")
                    Text("${profile.affection} / 1000", style = MaterialTheme.typography.titleMedium)
                }
                MetricCard("认识时间", if (daysKnown == 0L) "尚未开始聊天" else "第${daysKnown}天")
                Text(
                    "亲密度不能手动修改。称呼、暧昧和其他关系表现没有固定解锁等级，由寂墨结合关系状态、共同经历和当时气氛自主判断。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("关系事件", style = MaterialTheme.typography.titleMedium)
                if (profile.events.isEmpty()) {
                    Text("还没有形成值得长期保存的关系事件。", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(profile.events.asReversed(), key = { _, item -> item.id }) { _, event ->
                            Card {
                                Column(Modifier.fillMaxWidth().padding(11.dp)) {
                                    Text(event.content, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        formatDeviceTime(event.createdAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun MemoryManagerDialog(
    memories: List<LongTermMemory>,
    contextSummary: String,
    onAdd: (String, String, Boolean, String) -> Unit,
    onUpdate: (Long, String, String, Boolean, String) -> Unit,
    onDelete: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var editorOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<LongTermMemory?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分层记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "自动整理的内容一律先隔离。点开并保存才算你亲自确认；未确认内容不会发给模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (contextSummary.isNotBlank()) {
                    StatusCard("旧上下文已压缩", contextSummary.take(180) + if (contextSummary.length > 180) "…" else "")
                }
                OutlinedButton(onClick = { selected = null; editorOpen = true }) { Text("＋ 添加记忆") }
                if (memories.isEmpty()) {
                    Text("还没有长期记忆。你可以手动添加，或在聊天中说“请记住……”")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 390.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(memories, key = { _, item -> item.id }) { _, item ->
                            Card(onClick = { selected = item; editorOpen = true }) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(item.content, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(5.dp))
                                    Text(
                                        "${memoryLayerLabel(item.layer)} · ${if (item.verified) "已确认" else "待确认"}${if (item.pinned) " · 固定" else ""}" +
                                            (item.occurredAtStart?.let { " · 发生于${formatDeviceTime(it)}" } ?: " · 发生时间未知"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                if (memories.isNotEmpty()) TextButton(onClick = { clearConfirm = true }) { Text("清除全部长期记忆") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
    if (editorOpen) MemoryEditorDialog(
        initial = selected,
        onSave = { content, layer, pinned, occurredOn ->
            selected?.let { onUpdate(it.id, content, layer, pinned, occurredOn) } ?: onAdd(content, layer, pinned, occurredOn)
            editorOpen = false
        },
        onDelete = selected?.let { item -> { onDelete(item.id); editorOpen = false } },
        onDismiss = { editorOpen = false },
    )
    if (clearConfirm) AlertDialog(
        onDismissRequest = { clearConfirm = false },
        title = { Text("清除全部长期记忆？") },
        text = { Text("不会删除聊天记录和已经生成的上下文摘要。") },
        confirmButton = { TextButton(onClick = { onClear(); clearConfirm = false }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } },
    )
}

@Composable
private fun MemoryEditorDialog(
    initial: LongTermMemory?,
    onSave: (String, String, Boolean, String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var content by remember(initial?.id) { mutableStateOf(initial?.content.orEmpty()) }
    var layer by remember(initial?.id) { mutableStateOf(initial?.layer ?: "user_requested") }
    var pinned by remember(initial?.id) { mutableStateOf(initial?.pinned ?: true) }
    var occurredOn by remember(initial?.id) {
        mutableStateOf(
            initial?.occurredAtStart?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            }.orEmpty(),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加长期记忆" else "修改长期记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(content, { content = it.take(240) }, label = { Text("记忆内容") }, minLines = 3)
                OutlinedTextField(
                    layer,
                    { layer = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(24) },
                    label = { Text("分层：profile / preference / event / project / plan") },
                    singleLine = true,
                )
                OutlinedTextField(
                    occurredOn,
                    { occurredOn = it.filter { ch -> ch.isDigit() || ch == '-' }.take(10) },
                    label = { Text("事情发生日期 YYYY-MM-DD（不确定可留空）") },
                    singleLine = true,
                )
                ToggleRow("固定记忆（检索时优先）", pinned) { pinned = it }
                if (initial != null && !initial.verified) {
                    Text("这是一条隔离候选。点击保存表示你确认内容无误；你也可以先修改。", style = MaterialTheme.typography.bodySmall)
                    if (initial.evidenceText.isNotBlank()) {
                        Text("用户原话证据：${initial.evidenceText}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                onDelete?.let { TextButton(onClick = it) { Text("删除这条记忆") } }
            }
        },
        confirmButton = {
            TextButton(enabled = content.trim().length >= 4, onClick = { onSave(content.trim(), layer.ifBlank { "general" }, pinned, occurredOn) }) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun memoryLayerLabel(layer: String): String = when (layer) {
    "profile" -> "用户资料"
    "preference" -> "偏好"
    "event" -> "重要经历"
    "project" -> "长期项目"
    "plan" -> "计划"
    "user_requested" -> "明确记忆"
    else -> layer.ifBlank { "其他" }
}

@Composable
private fun ProactiveEditor(
    initial: ProactiveSettings,
    onSave: (ProactiveSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var enabled by remember { mutableStateOf(initial.enabled) }
    var studyEnabled by remember { mutableStateOf(initial.studyChecksEnabled) }
    var travelEnabled by remember { mutableStateOf(initial.travelChecksEnabled) }
    var dailyLimit by remember { mutableStateOf(initial.dailyLimit.toString()) }
    var minInterval by remember { mutableStateOf(initial.minIntervalMinutes.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主动消息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleRow("开启后台主动消息", enabled) { enabled = it }
                ToggleRow("学习使用情况抓包", studyEnabled) { studyEnabled = it }
                ToggleRow("行程位置抓包", travelEnabled) { travelEnabled = it }
                OutlinedTextField(
                    value = dailyLimit,
                    onValueChange = { dailyLimit = it.filter(Char::isDigit).take(2) },
                    label = { Text("每天最多几条（1-20）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = minInterval,
                    onValueChange = { minInterval = it.filter(Char::isDigit).take(3) },
                    label = { Text("两条主动消息最短间隔（分钟）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Text(
                    "本地规则先判断，只有真正命中抓包时才调用 API。学习计划结束但没有抓到玩手机时，会用本地短消息询问进度，不消耗 API。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    ProactiveSettings(
                        enabled = enabled,
                        studyChecksEnabled = studyEnabled,
                        travelChecksEnabled = travelEnabled,
                        dailyLimit = (dailyLimit.toIntOrNull() ?: initial.dailyLimit).coerceIn(1, 20),
                        minIntervalMinutes = (minInterval.toIntOrNull() ?: initial.minIntervalMinutes).coerceIn(5, 180),
                    ),
                )
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MapEditor(initial: MapSettings, onSave: (MapSettings) -> Unit, onDismiss: () -> Unit) {
    var amapKey by remember { mutableStateOf(initial.amapWebServiceKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("位置名称服务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "填写高德开放平台申请的“Web 服务 API” Key。App 会用它把当前坐标解析为地址、小区、建筑和附近 POI。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = amapKey,
                    onValueChange = { amapKey = it },
                    label = { Text("高德 Web 服务 Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(MapSettings(amapKey)); onDismiss() }) { Text("保存并刷新位置") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SettingsItem(title: String, summary: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalAlignment = Alignment.Start) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            Spacer(Modifier.height(3.dp))
            Text(summary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun PersonaEditor(initial: Persona, onSave: (Persona) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var instructions by remember { mutableStateOf(initial.instructions) }
    var shareTime by remember { mutableStateOf(initial.shareTime) }
    var shareLocation by remember { mutableStateOf(initial.shareLocation) }
    var shareAppUsage by remember { mutableStateOf(initial.shareAppUsage) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("人格设定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(instructions, { instructions = it }, label = { Text("系统提示词 / 人格") }, minLines = 4)
                ToggleRow("聊天时提供当前时间", shareTime) { shareTime = it }
                ToggleRow("聊天时提供位置信息", shareLocation) { shareLocation = it }
                ToggleRow("学习监督时提供手机使用摘要", shareAppUsage) { shareAppUsage = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    Persona(
                        name = name.ifBlank { "寂墨" },
                        instructions = instructions.ifBlank { Persona().instructions },
                        shareTime = shareTime,
                        shareLocation = shareLocation,
                        shareAppUsage = shareAppUsage,
                        matureMode = initial.matureMode,
                        matureInstructions = initial.matureInstructions,
                    ),
                )
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MatureModeEditor(initial: Persona, onSave: (Persona) -> Unit, onDismiss: () -> Unit) {
    var enabled by remember { mutableStateOf(initial.matureMode) }
    var instructions by remember { mutableStateOf(initial.matureInstructions) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("成人向模式") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToggleRow("开启成人向模式", enabled) { enabled = it }
                if (enabled) {
                    OutlinedTextField(
                        value = instructions,
                        onValueChange = { instructions = it },
                        label = { Text("独立成人向提示词") },
                        supportingText = { Text("只有开启时才会注入；不会改动日常人格提示词。") },
                        minLines = 5,
                    )
                    Text(
                        "仅限明确成年、自愿且尊重边界的互动；程序仍会附加固定安全边界。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    StatusCard("当前已关闭", "成人向提示词不会出现在任何模型请求中，也不能在关闭状态下编辑。")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(
                    matureMode = enabled,
                    matureInstructions = instructions.ifBlank { Persona().matureInstructions },
                ))
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ApiEditor(initial: ApiSettings, onSave: (ApiSettings) -> Unit, onDismiss: () -> Unit) {
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenAI 兼容接口") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("会向 Base URL 的 /chat/completions 发起标准 Chat Completions 请求。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text("模型名") }, singleLine = true)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ApiSettings(baseUrl, model, apiKey)); onDismiss() }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
