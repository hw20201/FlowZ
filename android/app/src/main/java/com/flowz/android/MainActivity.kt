package com.flowz.android

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowz.android.core.ConfigBuilder
import com.flowz.android.core.CoreState
import com.flowz.android.core.DisplayNode
import com.flowz.android.core.HubState
import com.flowz.android.core.SubEntry
import com.flowz.android.core.TrafficPoller
import kotlinx.coroutines.delay
import java.util.Locale

private val Teal = Color(0xFF0E98A4)
private val Green = Color(0xFF2E7D32)
private val Orange = Color(0xFFEF6C00)
private val Red = Color(0xFFD32F2F)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val ctx = LocalContext.current
                LaunchedEffect(Unit) {
                    CoreState.init(ctx)
                }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        CoreState.startService(this@MainActivity)
                    } else {
                        CoreState.setStatus(HubState.IDLE)
                    }
                }
                var tab by remember { mutableStateOf(0) }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FlowzApp(
                        tab = tab,
                        onTab = { tab = it },
                        onConnectClick = {
                            CoreState.setStatus(HubState.CONNECTING)
                            val intent = VpnService.prepare(this@MainActivity)
                            if (intent != null) {
                                launcher.launch(intent)
                            } else {
                                CoreState.startService(this@MainActivity)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowzApp(
    tab: Int,
    onTab: (Int) -> Unit,
    onConnectClick: () -> Unit
) {
    val ctx = LocalContext.current
    val status by CoreState.status.collectAsState()
    val failureReason by CoreState.failureReason.collectAsState()
    val traffic by CoreState.traffic.collectAsState()
    val nodes by CoreState.nodes.collectAsState()
    val selectedTag by CoreState.selectedTag.collectAsState()
    val mode by CoreState.mode.collectAsState()
    val message by CoreState.message.collectAsState()
    val connectedSince by CoreState.connectedSince.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            CoreState.onTick(now)
            delay(1000)
        }
    }

    val currentNode = nodes.firstOrNull { it.tag == selectedTag }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (tab == 0) {
                HomeScreen(
                    status = status,
                    failureReason = failureReason,
                    currentNode = currentNode,
                    mode = mode,
                    traffic = traffic,
                    connectedSince = connectedSince,
                    now = now,
                    connectEnabled = nodes.isNotEmpty() && selectedTag != null,
                    onConnectClick = onConnectClick,
                    onStopClick = { CoreState.stopService(ctx) },
                    onModeClick = { CoreState.setMode(ctx, it) }
                )
            } else {
                NodesScreen()
            }
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message!!, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { CoreState.clearMessage() }) { Text("知道了") }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = { onTab(0) }) {
                Text("主页", color = if (tab == 0) Teal else Color.Gray)
            }
            TextButton(onClick = { onTab(1) }) {
                Text("节点", color = if (tab == 1) Teal else Color.Gray)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    status: HubState,
    failureReason: String,
    currentNode: DisplayNode?,
    mode: ConfigBuilder.Mode,
    traffic: TrafficPoller.Traffic,
    connectedSince: Long?,
    now: Long,
    connectEnabled: Boolean,
    onConnectClick: () -> Unit,
    onStopClick: () -> Unit,
    onModeClick: (ConfigBuilder.Mode) -> Unit
) {
    val upHistory by CoreState.upHistory.collectAsState()
    val downHistory by CoreState.downHistory.collectAsState()
    val upTotal by CoreState.upTotal.collectAsState()
    val downTotal by CoreState.downTotal.collectAsState()

    val pillText = when (status) {
        HubState.CONNECTED -> "已连接"
        HubState.CONNECTING -> "连接中…"
        HubState.IDLE -> "未连接"
        HubState.FAILED -> "连接失败"
    }
    val pillColor = when (status) {
        HubState.CONNECTED -> Green
        HubState.CONNECTING -> Teal
        HubState.IDLE -> Color.Gray
        HubState.FAILED -> Red
    }
    val running = status == HubState.CONNECTED || status == HubState.CONNECTING

    // Status card: FlClash-style big card + FlowZ teal
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .background(pillColor, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(pillText, color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                    if (connectedSince != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatDuration(now - connectedSince),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    currentNode?.name ?: "还没有节点",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                if (currentNode != null) {
                    Text(
                        currentNode.type.uppercase(Locale.US) + " · " + currentNode.transport + " · " + currentNode.source,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                } else {
                    Text(
                        "去节点页添加订阅或手动节点",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            val btnColor = if (running) Red else if (connectEnabled) Teal else Color.Gray
            val btnModifier = if (running || connectEnabled) {
                Modifier
                    .size(72.dp)
                    .background(btnColor, CircleShape)
                    .clickable {
                        if (running) onStopClick() else onConnectClick()
                    }
            } else {
                Modifier
                    .size(72.dp)
                    .background(btnColor, CircleShape)
            }
            Box(btnModifier, contentAlignment = Alignment.Center) {
                Text(
                    if (running) "⏸" else "▶",
                    color = Color.White,
                    fontSize = 28.sp
                )
            }
        }
    }

    if (status == HubState.FAILED && failureReason.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(failureReason, style = MaterialTheme.typography.bodyMedium, color = Red)
    }

    Spacer(Modifier.height(16.dp))

    // Speed cards with sparklines (FlClash)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SpeedCard(
            label = "↑ 上传",
            rate = formatRate(traffic.upBps),
            total = "累计 " + formatBytes(upTotal),
            points = upHistory,
            color = Orange,
            modifier = Modifier.weight(1f)
        )
        SpeedCard(
            label = "↓ 下载",
            rate = formatRate(traffic.downBps),
            total = "累计 " + formatBytes(downTotal),
            points = downHistory,
            color = Teal,
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(Modifier.height(16.dp))

    // Mode pill bar (FlClash three-segment, FlowZ names)
    Text("分流模式", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeSegment("全局", mode == ConfigBuilder.Mode.GLOBAL, Modifier.weight(1f)) {
            onModeClick(ConfigBuilder.Mode.GLOBAL)
        }
        ModeSegment("直连", mode == ConfigBuilder.Mode.DIRECT, Modifier.weight(1f)) {
            onModeClick(ConfigBuilder.Mode.DIRECT)
        }
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.weight(1f)
        ) {
            Text("智能 · 后排")
        }
    }

    Spacer(Modifier.height(16.dp))

    // Bottom status strip (FlowZ)
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            statusLine(status, currentNode, traffic, connectedSince, now),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun ModeSegment(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun SpeedCard(
    label: String,
    rate: String,
    total: String,
    points: List<Long>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            Spacer(Modifier.height(4.dp))
            Text(rate, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(total, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            Sparkline(points, color)
        }
    }
}

@Composable
private fun Sparkline(points: List<Long>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(32.dp)) {
        if (points.size < 2) return@Canvas
        val max = (points.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val stepX = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v.toFloat() / max) * size.height * 0.85f - size.height * 0.05f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun NodesScreen() {
    val ctx = LocalContext.current
    val subs by CoreState.subscriptions.collectAsState()
    val nodes by CoreState.nodes.collectAsState()
    val selectedTag by CoreState.selectedTag.collectAsState()
    val status by CoreState.status.collectAsState()
    val busy by CoreState.busy.collectAsState()
    val delays by CoreState.delays.collectAsState()
    val delayBusy by CoreState.delayBusy.collectAsState()
    var subUrl by remember { mutableStateOf("") }
    var manualLink by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var protoFilter by remember { mutableStateOf("全部") }
    var sortAsc by remember { mutableStateOf(true) }

    Text("订阅", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = subUrl,
        onValueChange = { subUrl = it },
        label = { Text("订阅链接") },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            CoreState.addSubscription(ctx, subUrl)
            subUrl = ""
        },
        enabled = busy == null && subUrl.isNotBlank()
    ) {
        Text("添加订阅")
    }
    Spacer(Modifier.height(12.dp))
    for ((index, sub) in subs.withIndex()) {
        SubscriptionCard(
            sub = sub,
            onRefresh = { CoreState.refreshSubscription(ctx, index) },
            onDelete = { CoreState.deleteSubscription(ctx, index) },
            busy = busy != null
        )
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(16.dp))
    Text("手动添加", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = manualLink,
        onValueChange = { manualLink = it },
        label = { Text("节点链接（vless/vmess/trojan/ss/hy2）") },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            CoreState.addManualNode(ctx, manualLink)
            manualLink = ""
        },
        enabled = busy == null && manualLink.isNotBlank()
    ) {
        Text("添加节点")
    }

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("节点", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = { CoreState.testAllDelays() },
            enabled = status == HubState.CONNECTED && !delayBusy
        ) {
            Text(if (delayBusy) "测速中…" else "全部测速")
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { sortAsc = !sortAsc }) {
            Text(if (sortAsc) "名称 ↑" else "名称 ↓")
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("搜索节点名") },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (proto in listOf("全部", "vless", "vmess", "trojan", "ss", "hy2")) {
            if (proto == protoFilter) {
                Button(onClick = {}) { Text(proto) }
            } else {
                OutlinedButton(onClick = { protoFilter = proto }) { Text(proto) }
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    val shown = nodes
        .filter { protoFilter == "全部" || it.type == protoFilter }
        .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        .let { if (sortAsc) it.sortedBy { n -> n.name } else it.sortedByDescending { n -> n.name } }

    LazyColumn(
        modifier = Modifier.height(420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(shown, key = { it.tag }) { node ->
            NodeCard(
                node = node,
                selected = node.tag == selectedTag,
                delay = delays[node.tag],
                onSelect = { CoreState.selectNode(ctx, node.tag) },
                onTestDelay = { CoreState.testDelay(node.tag) },
                onDeleteManual = if (node.manualIndex >= 0) {
                    { CoreState.deleteManualNode(ctx, node.manualIndex) }
                } else {
                    null
                }
            )
        }
    }
}

@Composable
private fun SubscriptionCard(
    sub: SubEntry,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    busy: Boolean
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sub.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !busy) { Text("更新节点") }
                TextButton(onClick = onDelete, enabled = !busy) { Text("删除") }
            }
            if (sub.traffic != null) {
                val t = sub.traffic!!
                val used = t.upload + t.download
                val frac = if (t.total > 0) (used.toFloat() / t.total.toFloat()).coerceIn(0f, 1f) else 0f
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(Color.LightGray, RoundedCornerShape(4.dp))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(frac)
                            .height(8.dp)
                            .background(Teal, RoundedCornerShape(4.dp))
                    )
                }
                Spacer(Modifier.height(4.dp))
                var line = "已用 " + formatBytes(used) + " / 共 " + formatBytes(t.total)
                if (t.expire > 0) {
                    val days = (t.expire * 1000 - System.currentTimeMillis()) / 86400000
                    line += if (days < 0) " · 已过期" else " · 剩余 " + days + " 天"
                }
                Text(line, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "共 " + sub.nodes.size + " 个节点" +
                    if (sub.updatedAt > 0) " · 更新于 " + formatDateTime(sub.updatedAt) else "",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            if (sub.nodes.isEmpty() && sub.diag != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    sub.diag!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun NodeCard(
    node: DisplayNode,
    selected: Boolean,
    delay: CoreState.DelayInfo?,
    onSelect: () -> Unit,
    onTestDelay: () -> Unit,
    onDeleteManual: (() -> Unit)?
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (selected) Teal else Color.Gray, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    node.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    Text("当前", style = MaterialTheme.typography.bodySmall, color = Teal)
                }
                if (onDeleteManual != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDeleteManual) { Text("删除") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(Teal, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(node.type.uppercase(Locale.US), color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    node.transport + " · " + node.source,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.weight(1f)
                )
                when (delay?.state) {
                    CoreState.DelayState.TESTING -> CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    CoreState.DelayState.OK -> Text(
                        delay.ms.toString() + "ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = delayColor(delay.ms),
                        modifier = Modifier.clickable { onTestDelay() }
                    )
                    CoreState.DelayState.FAILED -> Text(
                        "超时",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.clickable { onTestDelay() }
                    )
                    else -> Text(
                        "未测",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.clickable { onTestDelay() }
                    )
                }
            }
        }
    }
}

private fun delayColor(ms: Long): Color {
    return when {
        ms < 200 -> Green
        ms < 500 -> Orange
        else -> Red
    }
}

private fun statusLine(
    status: HubState,
    node: DisplayNode?,
    traffic: TrafficPoller.Traffic,
    connectedSince: Long?,
    now: Long
): String {
    val base = when (status) {
        HubState.CONNECTED -> "已连接"
        HubState.CONNECTING -> "连接中…"
        HubState.IDLE -> "未连接"
        HubState.FAILED -> "连接失败"
    }
    val nodePart = if (node != null) " · " + node.name else ""
    val connPart = if (status == HubState.CONNECTED) " · " + traffic.count + " 连接" else ""
    val timePart = if (connectedSince != null) " · 已运行 " + formatDuration(now - connectedSince) else ""
    return base + nodePart + connPart + timePart
}

private fun formatRate(bps: Long): String {
    return when {
        bps < 1024 -> "$bps B/s"
        bps < 1024 * 1024 -> String.format(Locale.US, "%.1f KB/s", bps / 1024.0)
        else -> String.format(Locale.US, "%.2f MB/s", bps / (1024.0 * 1024.0))
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    } else {
        String.format(Locale.US, "%02d:%02d", m, sec)
    }
}

private fun formatDateTime(ms: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = ms
    return String.format(
        Locale.US,
        "%02d-%02d %02d:%02d",
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE)
    )
}
