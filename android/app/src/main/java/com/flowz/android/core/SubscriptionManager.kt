package com.flowz.android.core

import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a subscription document and parses it into nodes.
 * Network I/O must run off the main thread (caller uses Dispatchers.IO).
 * Nothing fetched here is written to Muse-side memory or files; the
 * app keeps the user's own subscription in its private storage.
 *
 * Traffic info comes from the standard `subscription-userinfo`
 * response header (upload / download / total / expire), when the
 * provider sends one. Absent header -> null traffic, no bar shown.
 */
object SubscriptionManager {

    data class TrafficInfo(
        val upload: Long,
        val download: Long,
        val total: Long,
        val expire: Long
    ) {
        val used: Long get() = upload + download
    }

    data class SubResult(
        val nodes: List<ParsedNode>,
        val failed: Int,
        val traffic: TrafficInfo?,
        val diag: ParseDiag
    )

    fun fetch(url: String, timeoutMs: Int = 15000): SubResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty(
                "User-Agent",
                "FlowZ-Android/0.3 (sing-box)"
            )
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("订阅拉取失败：HTTP $code")
            }
            val traffic = parseUserinfo(conn.getHeaderField("subscription-userinfo"))
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val result = NodeParser.parseSubscription(text)
            return SubResult(result.nodes, result.failed, traffic, result.diag)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseUserinfo(header: String?): TrafficInfo? {
        if (header.isNullOrEmpty()) return null
        val map = mutableMapOf<String, Long>()
        for (part in header.split(";")) {
            val eq = part.indexOf('=')
            if (eq < 0) continue
            val value = part.substring(eq + 1).trim().toLongOrNull() ?: continue
            map[part.substring(0, eq).trim()] = value
        }
        return TrafficInfo(
            upload = map["upload"] ?: 0L,
            download = map["download"] ?: 0L,
            total = map["total"] ?: 0L,
            expire = map["expire"] ?: 0L
        )
    }
}
