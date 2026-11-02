package com.flowz.android.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a full sing-box config from parsed nodes.
 *
 * Modes (FlowZ desktop parity, first cut):
 * - GLOBAL: everything goes through the selected node (via selector).
 * - DIRECT: everything direct.
 * - SMART: same default as GLOBAL for now; rule-based splitting comes
 *   later (the selector stays in place so rules can be added without
 *   restructuring). The v1 UI only offers GLOBAL / DIRECT.
 *
 * Node tags come from NodeParser.stableTag(), so a selection survives
 * subscription refreshes that only reorder nodes.
 *
 * experimental.clash_api is enabled on loopback only so the app can
 * read live traffic and connection counts for the status bar (and,
 * later, the connections page). No secret: it never leaves the device.
 */
object ConfigBuilder {

    enum class Mode { GLOBAL, SMART, DIRECT }

    fun build(
        nodes: List<ParsedNode>,
        selectedTag: String?,
        mode: Mode
    ): String {
        val outbounds = JSONArray()
        for (node in nodes) {
            val obj = JSONObject(node.outbound.toString())
            obj.put("tag", NodeParser.stableTag(node.outbound))
            outbounds.put(obj)
        }
        val tags = nodes.map { NodeParser.stableTag(it.outbound) }
        val defaultTag = selectedTag?.takeIf { it in tags } ?: tags.firstOrNull()
        if (tags.isNotEmpty()) {
            outbounds.put(JSONObject().apply {
                put("type", "selector")
                put("tag", "proxy")
                put("outbounds", JSONArray().apply {
                    for (t in tags) put(t)
                })
                if (defaultTag != null) put("default", defaultTag)
            })
        }
        outbounds.put(JSONObject().apply {
            put("type", "direct")
            put("tag", "direct")
        })

        val finalTag = when (mode) {
            Mode.DIRECT -> "direct"
            else -> if (tags.isNotEmpty()) "proxy" else "direct"
        }
        return JSONObject().apply {
            put("log", JSONObject().apply { put("level", "info") })
            put("experimental", JSONObject().apply {
                put("clash_api", JSONObject().apply {
                    put("external_controller", "127.0.0.1:9090")
                })
            })
            put("dns", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("tag", "remote")
                        put("address", "223.5.5.5")
                        put("detour", "direct")
                    })
                })
                put("final", "remote")
            })
            put("inbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("inet4_address", "172.19.0.0/30")
                    put("mtu", 9000)
                    put("auto_route", true)
                    put("stack", "system")
                })
            })
            put("outbounds", outbounds)
            put("route", JSONObject().apply {
                put("final", finalTag)
                put("auto_detect_interface", true)
            })
        }.toString()
    }
}
