package com.flowz.android.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the user's own subscriptions, manual nodes, selection and
 * mode in the app's private SharedPreferences. This is device-local
 * app data; nothing here is sent anywhere by this app.
 */

data class SubEntry(
    val name: String,
    val url: String,
    val updatedAt: Long,
    val traffic: SubscriptionManager.TrafficInfo?,
    val nodes: List<ParsedNode>
)

data class DisplayNode(
    val name: String,
    val type: String,
    val transport: String,
    val tag: String,
    val source: String,
    val manualIndex: Int = -1
)

data class AppState(
    val subscriptions: List<SubEntry>,
    val manual: List<ParsedNode>,
    val selectedTag: String?,
    val mode: ConfigBuilder.Mode
)

object SelectionStore {

    private const val PREFS = "flowz_v1"
    private const val KEY = "state"

    fun load(context: Context): AppState {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return AppState(emptyList(), emptyList(), null, ConfigBuilder.Mode.GLOBAL)
        return try {
            val json = JSONObject(raw)
            val subs = mutableListOf<SubEntry>()
            val subArr = json.optJSONArray("subscriptions") ?: JSONArray()
            for (i in 0 until subArr.length()) {
                val s = subArr.getJSONObject(i)
                val t = s.optJSONObject("traffic")
                val traffic = if (t != null) {
                    SubscriptionManager.TrafficInfo(
                        upload = t.optLong("upload"),
                        download = t.optLong("download"),
                        total = t.optLong("total"),
                        expire = t.optLong("expire")
                    )
                } else {
                    null
                }
                val nodes = mutableListOf<ParsedNode>()
                val nodeArr = s.optJSONArray("nodes") ?: JSONArray()
                for (j in 0 until nodeArr.length()) {
                    val n = nodeArr.getJSONObject(j)
                    nodes.add(ParsedNode(n.optString("name"), JSONObject(n.optString("outbound"))))
                }
                subs.add(
                    SubEntry(
                        name = s.optString("name"),
                        url = s.optString("url"),
                        updatedAt = s.optLong("updatedAt"),
                        traffic = traffic,
                        nodes = nodes
                    )
                )
            }
            val manual = mutableListOf<ParsedNode>()
            val manArr = json.optJSONArray("manual") ?: JSONArray()
            for (i in 0 until manArr.length()) {
                val n = manArr.getJSONObject(i)
                manual.add(ParsedNode(n.optString("name"), JSONObject(n.optString("outbound"))))
            }
            val mode = try {
                ConfigBuilder.Mode.valueOf(json.optString("mode", "GLOBAL"))
            } catch (_: Exception) {
                ConfigBuilder.Mode.GLOBAL
            }
            AppState(subs, manual, json.optString("selectedTag").takeIf { it.isNotEmpty() }, mode)
        } catch (_: Exception) {
            AppState(emptyList(), emptyList(), null, ConfigBuilder.Mode.GLOBAL)
        }
    }

    fun save(context: Context, state: AppState) {
        val json = JSONObject().apply {
            put("selectedTag", state.selectedTag ?: "")
            put("mode", state.mode.name)
            put("subscriptions", JSONArray().apply {
                for (s in state.subscriptions) {
                    put(JSONObject().apply {
                        put("name", s.name)
                        put("url", s.url)
                        put("updatedAt", s.updatedAt)
                        s.traffic?.let { t ->
                            put("traffic", JSONObject().apply {
                                put("upload", t.upload)
                                put("download", t.download)
                                put("total", t.total)
                                put("expire", t.expire)
                            })
                        }
                        put("nodes", JSONArray().apply {
                            for (n in s.nodes) {
                                put(JSONObject().apply {
                                    put("name", n.name)
                                    put("outbound", n.outbound.toString())
                                })
                            }
                        })
                    })
                }
            })
            put("manual", JSONArray().apply {
                for (n in state.manual) {
                    put(JSONObject().apply {
                        put("name", n.name)
                        put("outbound", n.outbound.toString())
                    })
                }
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.toString()).apply()
    }

    fun allNodes(state: AppState): List<ParsedNode> {
        val out = mutableListOf<ParsedNode>()
        for (s in state.subscriptions) out.addAll(s.nodes)
        out.addAll(state.manual)
        return out
    }

    fun displayNodes(state: AppState): List<DisplayNode> {
        val out = mutableListOf<DisplayNode>()
        for (s in state.subscriptions) {
            for (n in s.nodes) out.add(toDisplay(n, s.name))
        }
        for ((i, n) in state.manual.withIndex()) {
            out.add(toDisplay(n, "手动", manualIndex = i))
        }
        return out
    }

    private fun toDisplay(node: ParsedNode, source: String, manualIndex: Int = -1): DisplayNode {
        val obj = node.outbound
        val type = obj.optString("type")
        val transport = when (type) {
            "shadowsocks" -> obj.optString("method").ifEmpty { "ss" }
            "hysteria2" -> "udp"
            else -> obj.optJSONObject("transport")?.optString("type")?.ifEmpty { "tcp" } ?: "tcp"
        }
        return DisplayNode(
            name = node.name,
            type = type,
            transport = transport,
            tag = NodeParser.stableTag(obj),
            source = source,
            manualIndex = manualIndex
        )
    }
}
