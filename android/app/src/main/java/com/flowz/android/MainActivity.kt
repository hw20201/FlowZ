package com.flowz.android

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowz.android.core.ConfigBuilder
import com.flowz.android.core.CoreState
import com.flowz.android.core.DisplayNode
import com.flowz.android.core.HubState
import com.flowz.android.core.SubEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Teal = Color(0xFF0E98A4)
private val TealLight = Color(0xFFE0F2F3)
private val StopRed = Color(0xFFD32F2F)

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
                    }
                }
                var tab by remember { mutableStateOf(0) }
                Scaffold(
                    bottomBar = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TextButton(onClick = { tab = 0 }) {
                                Text(
                                    "主页",
                                    color = if (tab == 0) Teal else Color.Gray
                                )
                            }
                            TextButton(onClick = { tab = 1 }) {
                                Text(
                                    "节点",
                                    color = if (tab == 1) Teal else Color.Gray
                                )
                            }
                        }
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        if (tab == 0) {
                            HomeScreen(
                                onConnectClick = {
                                    val intent = VpnService.prepare(this@MainActivity)
                                    if (intent == null) {
                                        CoreState.startService(this@MainActivity)
                                    } else {
                                        launcher.launch(intent)
                                    }
                                }
                            )
                        } else {
                            NodesScreen()
                        }
                    }
                }
            }
        }
    }
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
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun formatDuration(sinceMs: Long, nowMs: Long): String {
    val secs = ((nowMs - sinceMs) / 1000).coerceAtLeast(0L)
    val mm = secs / 60
    val ss = secs % 60
    return String.format(Locale.US, "%02d:%02d", mm, ss)
}

private fun formatDate(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMs))
}

private fun formatDateTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(epochMs))
}

@Composable
private fun ProtocolPill(text: String) {
    Box(
        modifier = Modifier
            .background(TealLight, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text.uppercase(Locale.US), color = Teal, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HomeScreen(onConnectClick: () -> Unit) {
    val ctx = LocalContext.current
    val status by CoreState.status.collectAsState()
    val reason by CoreState.failureReason.collectAsState()
    val nodes by CoreState.nodes.collectAsState()
    val selectedTag by CoreState.selectedTag.collectAsState()
    val mode by CoreState.mode.collectAsState()
    val traffic by CoreState.traffic.collectAsState()
    val connectedSince by CoreState.connectedSince.collectAsState()
    val nowTick by CoreState.nowTick.collectAsState()
    val selected = nodes.firstOrNull { it.tag == selectedTag }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("主页", style = MaterialTheme.typography.headlineSmall)
            Text(
                "TUN · " + if (mode == ConfigBuilder.Mode.DIRECT) "直连" else "全局",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE0E0E0))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("出口节点", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                if (selected != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (status == HubState.CONNECTED) Color(0xFF2E7D32) else Color.Gray,
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        ProtocolPill(selected.type)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            selected.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("未测", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        selected.transport + " · " + selected.source,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                } else {
                    Text("还没有节点，去节点页添加订阅或手动节点")
                }
            }
        }

        if (status == HubState.FAILED && reason.isNotEmpty()) {
            Text("连接失败：$reason", color = StopRed, style = MaterialTheme.typography.bodyMedium)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (status == HubState.CONNECTED || status == HubState.CONNECTING) {
                Button(
                    onClick = { CoreState.stopService(ctx) },
                    colors = ButtonDefaults.buttonColors(containerColor = StopRed),
                    shape = CircleShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (status == HubState.CONNECTING) "连接中…" else "停止")
                }
            } else {
                Button(
                    onClick = onConnectClick,
                    enabled = nodes.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (status == HubState.FAILED) "重试" else "连接")
                }
            }
        }

        Column {
            Text("分流策略", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val globalSelected = mode != ConfigBuilder.Mode.DIRECT
                if (globalSelected) {
                    Button(
                        onClick = {},
                        colors = ButtonDefaults.buttonColors(containerColor = Teal)
                    ) { Text("全局") }
                } else {
                    OutlinedButton(onClick = { CoreState.setMode(ctx, ConfigBuilder.Mode.GLOBAL) }) {
                        Text("全局")
                    }
                }
                if (!globalSelected) {
                    Button(
                        onClick = {},
                        colors = ButtonDefaults.buttonColors(containerColor = Teal)
                    ) { Text("直连") }
                } else {
                    OutlinedButton(onClick = { CoreState.setMode(ctx, ConfigBuilder.Mode.DIRECT) }) {
                        Text("直连")
                    }
                }
            }
            Text(
                "智能分流等规则来了再开",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE0E0E0))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (status == HubState.CONNECTED) Color(0xFF2E7D32) else Color.Gray,
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (status) {
                            HubState.CONNECTED -> "已连接"
                            HubState.CONNECTING -> "连接中"
                            HubState.FAILED -> "连接失败"
                            HubState.IDLE -> "未连接"
                        }
                    )
                    if (selected != null && status == HubState.CONNECTED) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            selected.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "↓ " + formatRate(traffic.downBps) + "  ↑ " + formatRate(traffic.upBps),
                        style = MaterialTheme.typography.bodySmall
                    )
                    val runtime = if (connectedSince != null) {
                        "运行 " + formatDuration(connectedSince!!, nowTick) + " · " + traffic.count + " 连接"
                    } else {
                        traffic.count.toString() + " 连接"
                    }
                    Text(runtime, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun NodesScreen() {
    val ctx = LocalContext.current
    val subs by CoreState.subscriptions.collectAsState()
    val nodes by CoreState.nodes.collectAsState()
    val selectedTag by CoreState.selectedTag.collectAsState()
    val busy by CoreState.busy.collectAsState()
    val message by CoreState.message.collectAsState()
    var subUrl by remember { mutableStateOf("") }
    var manualLink by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var protoFilter by remember { mutableStateOf("全部") }
    var sortByName by remember { mutableStateOf(false) }

    val filtered = nodes.filter { n ->
        (protoFilter == "全部" || n.type.equals(protoFilter, ignoreCase = true)) &&
            (query.isEmpty() || n.name.contains(query, ignoreCase = true))
    }.let { list ->
        if (sortByName) list.sortedBy { it.name } else list
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("节点", style = MaterialTheme.typography.headlineSmall)
            Text("共 " + nodes.size + " 个", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Spacer(Modifier.height(8.dp))
        if (busy != null) {
            Text(busy!!, style = MaterialTheme.typography.bodySmall, color = Teal)
            Spacer(Modifier.height(4.dp))
        }
        if (message != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    message!!,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { CoreState.clearMessage() }) { Text("知道了") }
            }
            Spacer(Modifier.height(4.dp))
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(subs.indices.toList()) { index ->
                SubscriptionCard(
                    sub = subs[index],
                    onRefresh = { CoreState.refreshSubscription(ctx, index) },
                    onDelete = { CoreState.deleteSubscription(ctx, index) }
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE0E0E0))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("添加订阅", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = subUrl,
                            onValueChange = { subUrl = it },
                            label = { Text("订阅链接") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                CoreState.addSubscription(ctx, subUrl)
                                subUrl = ""
                            },
                            enabled = busy == null
                        ) { Text("添加") }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE0E0E0))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("手动添加节点", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = manualLink,
                            onValueChange = { manualLink = it },
                            label = { Text("粘贴 vless / vmess / trojan / ss / hy2 链接") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                CoreState.addManualNode(ctx, manualLink)
                                manualLink = ""
                            }
                        ) { Text("添加节点") }
                    }
                }
            }

            item {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索节点名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (p in listOf("全部", "vless", "vmess", "trojan", "ss", "hysteria2")) {
                        val label = if (p == "hysteria2") "HY2" else p.uppercase(Locale.US)
                        if (protoFilter == p) {
                            Button(
                                onClick = {},
                                colors = ButtonDefaults.buttonColors(containerColor = Teal),
                                contentPadding = ButtonDefaults.ContentPadding
                            ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                        } else {
                            OutlinedButton(
                                onClick = { protoFilter = p },
                                contentPadding = ButtonDefaults.ContentPadding
                            ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = { sortByName = !sortByName }) {
                        Text(if (sortByName) "排序：名称" else "排序：默认")
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Text(
                        "没有符合条件的节点",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
            } else {
                items(filtered) { node ->
                    NodeCard(
                        node = node,
                        selected = node.tag == selectedTag,
                        onSelect = { CoreState.selectNode(ctx, node.tag) },
                        onDeleteManual = if (node.manualIndex >= 0) {
                            { CoreState.deleteManualNode(ctx, node.manualIndex) }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    sub: SubEntry,
    onRefresh: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE0E0E0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(sub.name, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                sub.url,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val t = sub.traffic
            if (t != null && t.total > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (t.used.toFloat() / t.total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    formatBytes(t.used) + " / " + formatBytes(t.total) +
                        if (t.expire > 0) " · 到期 " + formatDate(t.expire * 1000) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
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
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh) { Text("更新节点") }
                OutlinedButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun NodeCard(
    node: DisplayNode,
    selected: Boolean,
    onSelect: () -> Unit,
    onDeleteManual: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) Teal else Color(0xFFE0E0E0)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(if (selected) Color(0xFF2E7D32) else Color.Gray, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    node.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(Teal, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("当前", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProtocolPill(node.type)
                Spacer(Modifier.width(8.dp))
                Text(node.transport, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.width(8.dp))
                Text(node.source, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                if (onDeleteManual != null) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDeleteManual) { Text("删除") }
                }
            }
        }
    }
}
