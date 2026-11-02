package com.flowz.android.core

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class HubState {
    IDLE,
    CONNECTING,
    CONNECTED,
    FAILED
}

/**
 * Single source of truth for the UI. All persistence goes through
 * SelectionStore; all network I/O runs on Dispatchers.IO.
 * Node values are never logged here.
 */
object CoreState {
    private val _status = MutableStateFlow(HubState.IDLE)
    val status: StateFlow<HubState> = _status

    private val _failureReason = MutableStateFlow("")
    val failureReason: StateFlow<String> = _failureReason

    private val _nodes = MutableStateFlow<List<DisplayNode>>(emptyList())
    val nodes: StateFlow<List<DisplayNode>> = _nodes

    private val _subscriptions = MutableStateFlow<List<SubEntry>>(emptyList())
    val subscriptions: StateFlow<List<SubEntry>> = _subscriptions

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag

    private val _mode = MutableStateFlow(ConfigBuilder.Mode.GLOBAL)
    val mode: StateFlow<ConfigBuilder.Mode> = _mode

    private val _traffic = MutableStateFlow(TrafficPoller.Traffic(0L, 0L, 0))
    val traffic: StateFlow<TrafficPoller.Traffic> = _traffic

    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince

    private val _nowTick = MutableStateFlow(System.currentTimeMillis())
    val nowTick: StateFlow<Long> = _nowTick

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _upHistory = MutableStateFlow<List<Long>>(emptyList())
    val upHistory: StateFlow<List<Long>> = _upHistory

    private val _downHistory = MutableStateFlow<List<Long>>(emptyList())
    val downHistory: StateFlow<List<Long>> = _downHistory

    private val _upTotal = MutableStateFlow(0L)
    val upTotal: StateFlow<Long> = _upTotal

    private val _downTotal = MutableStateFlow(0L)
    val downTotal: StateFlow<Long> = _downTotal

    private val _delays = MutableStateFlow<Map<String, DelayInfo>>(emptyMap())
    val delays: StateFlow<Map<String, DelayInfo>> = _delays

    private val _delayBusy = MutableStateFlow(false)
    val delayBusy: StateFlow<Boolean> = _delayBusy

    private var lastTrafficAt = 0L

    private val ioScope = CoroutineScope(Dispatchers.IO)

    private fun diagSummary(result: SubscriptionManager.SubResult): String {
        val d = result.diag
        val matched = if (d.matchedByScheme.isEmpty()) {
            "匹配 0 条"
        } else {
            "匹配 " + d.matchedByScheme.entries.joinToString(" / ") { it.key + " " + it.value }
        }
        return "解析：" + d.bytes + " 字节 · " + d.lines + " 行 · " + matched +
            " · 格式：" + d.kind +
            if (result.failed > 0) " · " + result.failed + " 条失败" else ""
    }

    fun setStatus(state: HubState, reason: String = "") {
        _status.value = state
        _failureReason.value = reason
        if (state == HubState.CONNECTED) {
            _connectedSince.value = System.currentTimeMillis()
            _upHistory.value = emptyList()
            _downHistory.value = emptyList()
            _upTotal.value = 0L
            _downTotal.value = 0L
            lastTrafficAt = 0L
        } else if (state == HubState.IDLE || state == HubState.FAILED) {
            _connectedSince.value = null
        }
    }

    fun onTraffic(t: TrafficPoller.Traffic) {
        _traffic.value = t
        _upHistory.value = (_upHistory.value + t.upBps).takeLast(30)
        _downHistory.value = (_downHistory.value + t.downBps).takeLast(30)
        val now = System.currentTimeMillis()
        if (lastTrafficAt > 0) {
            val dt = (now - lastTrafficAt) / 1000.0
            if (dt > 0) {
                _upTotal.value += (t.upBps * dt).toLong()
                _downTotal.value += (t.downBps * dt).toLong()
            }
        }
        lastTrafficAt = now
    }

    fun onTick(now: Long) {
        _nowTick.value = now
    }

    fun init(context: Context) {
        val state = SelectionStore.load(context)
        applyState(state)
    }

    private fun applyState(state: AppState) {
        _subscriptions.value = state.subscriptions
        _nodes.value = SelectionStore.displayNodes(state)
        val tags = _nodes.value.map { it.tag }
        _selectedTag.value = state.selectedTag?.takeIf { it in tags }
            ?: tags.firstOrNull()
        _mode.value = state.mode
    }

    private fun currentState(context: Context): AppState {
        val loaded = SelectionStore.load(context)
        return loaded.copy(
            selectedTag = _selectedTag.value,
            mode = _mode.value
        )
    }

    private fun persist(context: Context, mutate: (AppState) -> AppState) {
        val next = mutate(currentState(context))
        SelectionStore.save(context, next)
        applyState(next)
    }

    fun startService(context: Context) {
        context.startService(
            Intent(context, FlowzVpnService::class.java).apply {
                action = FlowzVpnService.ACTION_START
            }
        )
    }

    fun stopService(context: Context) {
        context.startService(
            Intent(context, FlowzVpnService::class.java).apply {
                action = FlowzVpnService.ACTION_STOP
            }
        )
    }

    fun reloadService(context: Context) {
        if (_status.value != HubState.CONNECTED) return
        context.startService(
            Intent(context, FlowzVpnService::class.java).apply {
                action = FlowzVpnService.ACTION_RELOAD
            }
        )
    }

    fun selectNode(context: Context, tag: String) {
        persist(context) { it.copy(selectedTag = tag) }
        reloadService(context)
    }

    fun setMode(context: Context, mode: ConfigBuilder.Mode) {
        persist(context) { it.copy(mode = mode) }
        reloadService(context)
    }

    fun addSubscription(context: Context, url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _message.value = "先填订阅链接"
            return
        }
        _busy.value = "拉取订阅中…"
        ioScope.launch {
            try {
                val result = SubscriptionManager.fetch(trimmed)
                val host = try {
                    java.net.URI(trimmed).host ?: trimmed
                } catch (_: Exception) {
                    trimmed
                }
                persist(context) { state ->
                    state.copy(
                        subscriptions = state.subscriptions + SubEntry(
                            name = host,
                            url = trimmed,
                            updatedAt = System.currentTimeMillis(),
                            traffic = result.traffic,
                            nodes = result.nodes,
                            diag = diagSummary(result)
                        )
                    )
                }
                _message.value = if (result.nodes.isEmpty()) {
                    "订阅拉到了，但一条节点都没解析出来（" + diagSummary(result) + "）"
                } else {
                    "订阅新增 ${result.nodes.size} 个节点" +
                        if (result.failed > 0) "，${result.failed} 个解析失败" else ""
                }
            } catch (e: Exception) {
                _message.value = "订阅拉取失败：${e.message ?: "未知错误"}"
            } finally {
                _busy.value = null
            }
        }
    }

    fun refreshSubscription(context: Context, index: Int) {
        val sub = _subscriptions.value.getOrNull(index) ?: return
        _busy.value = "更新订阅中…"
        ioScope.launch {
            try {
                val result = SubscriptionManager.fetch(sub.url)
                persist(context) { state ->
                    val updated = state.subscriptions.toMutableList()
                    if (index in updated.indices) {
                        updated[index] = sub.copy(
                            updatedAt = System.currentTimeMillis(),
                            traffic = result.traffic,
                            nodes = result.nodes,
                            diag = diagSummary(result)
                        )
                    }
                    state.copy(subscriptions = updated)
                }
                _message.value = if (result.nodes.isEmpty()) {
                    "订阅拉到了，但一条节点都没解析出来（" + diagSummary(result) + "）"
                } else {
                    "订阅已更新：${result.nodes.size} 个节点" +
                        if (result.failed > 0) "，${result.failed} 个解析失败" else ""
                }
            } catch (e: Exception) {
                _message.value = "订阅更新失败：${e.message ?: "未知错误"}"
            } finally {
                _busy.value = null
            }
        }
    }

    fun deleteSubscription(context: Context, index: Int) {
        persist(context) { state ->
            val updated = state.subscriptions.toMutableList()
            if (index in updated.indices) updated.removeAt(index)
            state.copy(subscriptions = updated)
        }
        _message.value = "订阅已删除"
    }

    fun addManualNode(context: Context, link: String) {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) {
            _message.value = "先粘贴节点链接"
            return
        }
        val node = try {
            NodeParser.parseLink(trimmed)
        } catch (_: Exception) {
            null
        }
        if (node == null) {
            _message.value = "这个链接解析不了，支持 vless / vmess / trojan / ss / hy2"
            return
        }
        persist(context) { state ->
            state.copy(manual = state.manual + node)
        }
        _message.value = "已添加节点 ${node.name}"
    }

    fun deleteManualNode(context: Context, manualIndex: Int) {
        persist(context) { state ->
            val updated = state.manual.toMutableList()
            if (manualIndex in updated.indices) updated.removeAt(manualIndex)
            state.copy(manual = updated)
        }
        _message.value = "手动节点已删除"
    }

    // Delay test (via the loopback clash API)
    enum class DelayState { UNTESTED, TESTING, OK, FAILED }
    data class DelayInfo(val state: DelayState, val ms: Long = 0)

    private fun fetchDelay(tag: String, timeoutMs: Int): DelayInfo {
        return try {
            val url = java.net.URL(
                "http://127.0.0.1:9090/proxies/" + tag + "/delay?url=" +
                    java.net.URLEncoder.encode("http://www.gstatic.com/generate_204", "UTF-8") +
                    "&timeout=" + timeoutMs
            )
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs + 1000
            conn.readTimeout = timeoutMs + 1000
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val ms = org.json.JSONObject(body).optLong("delay", -1)
                if (ms >= 0) DelayInfo(DelayState.OK, ms) else DelayInfo(DelayState.FAILED)
            } else {
                DelayInfo(DelayState.FAILED)
            }
        } catch (e: Exception) {
            DelayInfo(DelayState.FAILED)
        }
    }

    private fun setDelay(tag: String, info: DelayInfo) {
        _delays.value = _delays.value + (tag to info)
    }

    fun testDelay(tag: String) {
        if (_status.value != HubState.CONNECTED) {
            _message.value = "连接后才能测速"
            return
        }
        ioScope.launch {
            setDelay(tag, DelayInfo(DelayState.TESTING))
            setDelay(tag, fetchDelay(tag, 5000))
        }
    }

    fun testAllDelays() {
        if (_status.value != HubState.CONNECTED) {
            _message.value = "连接后才能测速"
            return
        }
        if (_delayBusy.value) return
        ioScope.launch {
            _delayBusy.value = true
            try {
                for (node in nodes.value) {
                    if (_status.value != HubState.CONNECTED) break
                    setDelay(node.tag, DelayInfo(DelayState.TESTING))
                    setDelay(node.tag, fetchDelay(node.tag, 3000))
                    kotlinx.coroutines.delay(100)
                }
            } finally {
                _delayBusy.value = false
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
