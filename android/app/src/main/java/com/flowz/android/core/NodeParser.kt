package com.flowz.android.core

import android.util.Base64
import java.net.URI
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Proxy subscription parsers: plain link lists, base64 link lists,
 * sing-box JSON configs, JSON wrappers, and minimal Clash YAML.
 *
 * Diagnostics only ever carry counts and format guesses, never any
 * node value: the app must be able to say "0 nodes, why" without
 * showing or storing a single server address, password or key.
 *
 * Node identity: stableTag() derives a tag from type + server + port
 * + credential, so a tag survives subscription refreshes that only
 * reorder nodes. (32-bit hash: a collision between two different
 * nodes is theoretically possible but negligible in practice.)
 */

data class ParsedNode(
    val name: String,
    val outbound: JSONObject
)

data class ParseDiag(
    val bytes: Int,
    val lines: Int,
    val matchedByScheme: Map<String, Int>,
    val failed: Int,
    val kind: String
)

data class ParseResult(
    val nodes: List<ParsedNode>,
    val failed: Int,
    val diag: ParseDiag
)

object NodeParser {

    fun stableTag(outbound: JSONObject): String {
        val type = outbound.optString("type")
        val server = outbound.optString("server")
        val port = outbound.optInt("server_port", 0)
        val cred = outbound.optString("uuid")
            .ifEmpty { outbound.optString("password") }
            .ifEmpty { outbound.optString("id") }
        val key = "$type|$server|$port|$cred"
        return "n" + (key.hashCode() and 0x7fffffff)
    }

    fun parseSubscription(rawText: String): ParseResult {
        var text = rawText.trim().removePrefix("\uFEFF")
        // Whole-body URL encoding (some panels return %3A%2F%2F bodies).
        if (text.contains("%3A", ignoreCase = true) && !text.contains("://")) {
            try {
                text = URLDecoder.decode(text, "UTF-8")
            } catch (_: Exception) {
            }
        }
        val bytes = text.length

        // JSON config or JSON wrapper.
        if (text.startsWith("{")) {
            val jsonResult = tryJson(text, bytes)
            if (jsonResult != null) return jsonResult
        }
        // JSON string wrapping a document.
        if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) {
            try {
                val inner = JSONObject("{\"v\":$text}").optString("v")
                if (inner.isNotEmpty() && inner != text) {
                    val r = parseSubscription(inner)
                    if (r.nodes.isNotEmpty()) return r
                }
            } catch (_: Exception) {
            }
        }

        // Minimal Clash YAML (proxies: list). Only used when it
        // actually yields nodes; otherwise fall through to link parsing.
        if (text.contains("proxies:")) {
            val yamlNodes = parseClashYaml(text)
            if (yamlNodes.isNotEmpty()) {
                return ParseResult(
                    yamlNodes, 0,
                    ParseDiag(bytes, text.lines().count { it.isNotBlank() }, mapOf("yaml" to yamlNodes.size), 0, "Clash 订阅")
                )
            }
        }

        val decoded = decodeBase64Robust(text)
        val body = decoded ?: text
        val kind = if (decoded != null) "Base64 链接" else "链接列表"
        return parseLinks(body, bytes, kind)
    }

    private fun tryJson(text: String, bytes: Int): ParseResult? {
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        // Full sing-box config: take its outbounds as nodes.
        val outbounds = json.optJSONArray("outbounds")
        if (outbounds != null && outbounds.length() > 0) {
            val nodes = mutableListOf<ParsedNode>()
            for (i in 0 until outbounds.length()) {
                try {
                    val obj = outbounds.getJSONObject(i)
                    val type = obj.optString("type")
                    if (type.isEmpty() || type == "selector" || type == "direct" || type == "block" || type == "dns") continue
                    val server = obj.optString("server")
                    val port = obj.optInt("server_port", 0)
                    val name = obj.optString("tag").takeIf { it.isNotEmpty() }
                        ?: if (server.isNotEmpty()) "$server:$port" else "节点"
                    nodes.add(ParsedNode(name, obj))
                } catch (_: Exception) {
                }
            }
            if (nodes.isNotEmpty()) {
                return ParseResult(
                    nodes, 0,
                    ParseDiag(bytes, 0, mapOf("json" to nodes.size), 0, "JSON 配置")
                )
            }
        }
        // Wrapper: a string field holding the real document.
        for (key in listOf("data", "subscription", "content", "result", "sub", "config")) {
            val inner = json.optString(key)
            if (inner.isNotEmpty() && inner != text) {
                val r = parseSubscription(inner)
                if (r.nodes.isNotEmpty()) return r
            }
        }
        return ParseResult(
            emptyList(), 0,
            ParseDiag(bytes, text.lines().count { it.isNotBlank() }, emptyMap(), 0, "JSON（未识别）")
        )
    }

    private fun decodeBase64Robust(text: String): String? {
        if (text.contains("://")) return null
        val clean = text.replace("\\s".toRegex(), "")
        if (clean.isEmpty()) return null
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        for (flags in listOf(Base64.URL_SAFE, Base64.DEFAULT)) {
            try {
                val decoded = String(Base64.decode(padded, flags), Charsets.UTF_8)
                if (decoded.isNotEmpty()) return decoded
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun parseLinks(body: String, bytes: Int, kind: String): ParseResult {
        val nodes = mutableListOf<ParsedNode>()
        val matched = mutableMapOf<String, Int>()
        var failed = 0
        var lines = 0
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            lines++
            try {
                val node = parseLink(line)
                if (node != null) {
                    nodes.add(node)
                    val scheme = line.substringBefore("://", "").lowercase()
                    matched[scheme] = (matched[scheme] ?: 0) + 1
                }
            } catch (_: Exception) {
                failed++
            }
        }
        return ParseResult(
            nodes, failed,
            ParseDiag(bytes, lines, matched, failed, kind)
        )
    }

    fun parseLink(line: String): ParsedNode? {
        val scheme = line.substringBefore("://", "").lowercase()
        return when (scheme) {
            "vless" -> parseVless(line)
            "vmess" -> parseVmess(line)
            "trojan" -> parseTrojan(line)
            "ss" -> parseShadowsocks(line)
            "hysteria2", "hy2" -> parseHysteria2(line)
            else -> null
        }
    }

    private fun queryParams(uri: URI): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val q = uri.rawQuery ?: return out
        for (pair in q.split("&")) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            out[decode(pair.substring(0, eq))] = decode(pair.substring(eq + 1))
        }
        return out
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun nameOf(uri: URI, fallback: String): String {
        val frag = uri.rawFragment
        return if (!frag.isNullOrEmpty()) decode(frag) else fallback
    }

    /**
     * TLS object for vless/vmess/trojan/hysteria2.
     * - security "tls" -> plain TLS; "reality" -> TLS + reality block.
     * - fp -> uTLS fingerprint (NOT a certificate fingerprint).
     * - insecure -> skip certificate verification (per-node field).
     */
    internal fun tlsObject(
        security: String?,
        sni: String?,
        host: String,
        alpn: String?,
        fp: String?,
        insecure: Boolean = false,
        realityKey: String? = null,
        realityShortId: String? = null
    ): JSONObject? {
        if (security != "tls" && security != "reality") return null
        return JSONObject().apply {
            put("enabled", true)
            put("server_name", sni?.takeIf { it.isNotEmpty() } ?: host)
            if (insecure) put("insecure", true)
            if (!alpn.isNullOrEmpty()) {
                put("alpn", JSONArray().apply {
                    for (a in alpn.split(",")) put(a.trim())
                })
            }
            if (!fp.isNullOrEmpty()) {
                put("utls", JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", fp)
                })
            }
            if (security == "reality" && !realityKey.isNullOrEmpty()) {
                put("reality", JSONObject().apply {
                    put("enabled", true)
                    put("public_key", realityKey)
                    if (!realityShortId.isNullOrEmpty()) put("short_id", realityShortId)
                })
            }
        }
    }

    internal fun transportObject(
        type: String?,
        host: String?,
        path: String?,
        serviceName: String?
    ): JSONObject? {
        return when (type?.lowercase()) {
            "ws" -> JSONObject().apply {
                put("type", "ws")
                put("path", path?.takeIf { it.isNotEmpty() } ?: "/")
                if (!host.isNullOrEmpty()) {
                    put("headers", JSONObject().apply { put("Host", host) })
                }
            }
            "grpc" -> JSONObject().apply {
                put("type", "grpc")
                if (!serviceName.isNullOrEmpty()) put("service_name", serviceName)
            }
            "httpupgrade", "http-upgrade" -> JSONObject().apply {
                put("type", "httpupgrade")
                if (!path.isNullOrEmpty()) put("path", path)
                if (!host.isNullOrEmpty()) put("host", host)
            }
            else -> null
        }
    }

    private fun parseVless(line: String): ParsedNode? {
        val uri = URI(line)
        val params = queryParams(uri)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else return null
        val uuid = uri.userInfo ?: return null
        val out = JSONObject().apply {
            put("type", "vless")
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            val flow = params["flow"]
            if (!flow.isNullOrEmpty() && flow != "none") put("flow", flow)
            tlsObject(
                params["security"], params["sni"], host, params["alpn"],
                params["fp"], insecure = false,
                realityKey = params["pbk"], realityShortId = params["sid"]
            )?.let { put("tls", it) }
            transportObject(params["type"], params["host"], params["path"], params["serviceName"])?.let {
                put("transport", it)
            }
        }
        return ParsedNode(nameOf(uri, "$host:$port"), out)
    }

    private fun parseVmess(line: String): ParsedNode? {
        val payload = line.substringAfter("vmess://", "")
        if (payload.isEmpty()) return null
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val decoded = try {
            Base64.decode(padded, Base64.DEFAULT)
        } catch (_: Exception) {
            try {
                Base64.decode(padded, Base64.URL_SAFE)
            } catch (_: Exception) {
                return null
            }
        }
        val json = JSONObject(String(decoded, Charsets.UTF_8))
        val host = json.optString("add").takeIf { it.isNotEmpty() } ?: return null
        val port = json.optInt("port", -1).takeIf { it > 0 } ?: return null
        val out = JSONObject().apply {
            put("type", "vmess")
            put("server", host)
            put("server_port", port)
            put("uuid", json.optString("id"))
            put("security", json.optString("scy", "auto").takeIf { it.isNotEmpty() } ?: "auto")
            put("alter_id", json.optInt("aid", 0))
            tlsObject(
                if (json.optString("tls") == "tls") "tls" else null,
                json.optString("sni").takeIf { it.isNotEmpty() },
                host,
                json.optString("alpn").takeIf { it.isNotEmpty() },
                json.optString("fp").takeIf { it.isNotEmpty() }
            )?.let { put("tls", it) }
            transportObject(
                json.optString("net").takeIf { it.isNotEmpty() },
                json.optString("host").takeIf { it.isNotEmpty() },
                json.optString("path").takeIf { it.isNotEmpty() },
                null
            )?.let { put("transport", it) }
        }
        return ParsedNode(json.optString("ps").takeIf { it.isNotEmpty() } ?: "$host:$port", out)
    }

    private fun parseTrojan(line: String): ParsedNode? {
        val uri = URI(line)
        val params = queryParams(uri)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else return null
        val password = uri.userInfo ?: return null
        val out = JSONObject().apply {
            put("type", "trojan")
            put("server", host)
            put("server_port", port)
            put("password", password)
            tlsObject("tls", params["sni"], host, params["alpn"], params["fp"])?.let {
                put("tls", it)
            }
            transportObject(params["type"], params["host"], params["path"], params["serviceName"])?.let {
                put("transport", it)
            }
        }
        return ParsedNode(nameOf(uri, "$host:$port"), out)
    }

    private fun parseShadowsocks(line: String): ParsedNode? {
        val rest = line.substringAfter("ss://", "")
        if (rest.isEmpty()) return null
        val frag = rest.substringAfter("#", "")
        val main = rest.substringBefore("#")
        val name = if (frag.isNotEmpty()) decode(frag) else ""
        // Two common shapes:
        //   ss://BASE64(method:password@host:port)
        //   ss://BASE64(method:password)@host:port
        val atParts = main.split("@")
        val (method, password, hostPort) = if (atParts.size >= 2) {
            val user = try {
                val p = atParts[0]
                val pad = p + "=".repeat((4 - p.length % 4) % 4)
                String(Base64.decode(pad, Base64.URL_SAFE), Charsets.UTF_8)
            } catch (_: Exception) {
                atParts[0]
            }
            val colon = user.indexOf(':')
            if (colon < 0) return null
            Triple(user.substring(0, colon), user.substring(colon + 1), atParts.drop(1).joinToString("@"))
        } else {
            val decoded = try {
                val pad = main + "=".repeat((4 - main.length % 4) % 4)
                String(Base64.decode(pad, Base64.URL_SAFE), Charsets.UTF_8)
            } catch (_: Exception) {
                return null
            }
            val at = decoded.lastIndexOf('@')
            val colon = decoded.indexOf(':')
            if (at < 0 || colon < 0 || colon > at) return null
            Triple(decoded.substring(0, colon), decoded.substring(colon + 1, at), decoded.substring(at + 1))
        }
        val host = hostPort.substringBefore(":")
        val port = hostPort.substringAfter(":", "").toIntOrNull() ?: return null
        if (host.isEmpty()) return null
        val out = JSONObject().apply {
            put("type", "shadowsocks")
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }
        return ParsedNode(name.ifEmpty { "$host:$port" }, out)
    }

    private fun parseHysteria2(line: String): ParsedNode? {
        val uri = URI(line)
        val params = queryParams(uri)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else return null
        val password = uri.userInfo ?: return null
        val insecure = params["insecure"] == "1" || params["skip-cert-verify"] == "true"
        val out = JSONObject().apply {
            put("type", "hysteria2")
            put("server", host)
            put("server_port", port)
            put("password", password)
            tlsObject("tls", params["sni"], host, params["alpn"] ?: "h3", params["fp"], insecure)?.let {
                put("tls", it)
            }
            if (params["obfs"] == "salamander") {
                put("obfs", JSONObject().apply {
                    put("type", "salamander")
                    put("password", params["obfs-password"] ?: "")
                })
            }
        }
        return ParsedNode(nameOf(uri, "$host:$port"), out)
    }

    // ---- Minimal Clash YAML (proxies: list only) ----

    private fun putKv(map: MutableMap<String, String>, s: String) {
        val i = s.indexOf(':')
        if (i < 0) return
        val k = s.substring(0, i).trim()
        var v = s.substring(i + 1).trim()
        if (v.length >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length - 1)
        }
        if (k.isNotEmpty()) map[k] = v
    }

    private fun parseClashYaml(text: String): List<ParsedNode> {
        val blocks = mutableListOf<Map<String, String>>()
        var cur: MutableMap<String, String>? = null
        var inProxies = false
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            val t = line.trim()
            if (t == "proxies:") {
                inProxies = true
                continue
            }
            if (!inProxies) continue
            if (t.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                // A new top-level section ends the proxies list.
                if (!t.startsWith("-")) {
                    inProxies = false
                    continue
                }
            }
            if (t.startsWith("- ")) {
                cur = mutableMapOf()
                blocks.add(cur)
                val rest = t.removePrefix("- ").trim()
                if (rest.isNotEmpty()) putKv(cur, rest)
            } else if (cur != null && t.contains(":")) {
                putKv(cur, t)
            }
        }
        val nodes = mutableListOf<ParsedNode>()
        for (b in blocks) {
            try {
                yamlBlockToNode(b)?.let { nodes.add(it) }
            } catch (_: Exception) {
            }
        }
        return nodes
    }

    private fun yamlBlockToNode(b: Map<String, String>): ParsedNode? {
        val type = b["type"]?.lowercase() ?: return null
        val server = b["server"]?.takeIf { it.isNotEmpty() } ?: return null
        val port = b["port"]?.toIntOrNull() ?: return null
        val name = b["name"]?.takeIf { it.isNotEmpty() } ?: "$server:$port"
        val tlsOn = b["tls"] == "true"
        val sni = b["sni"] ?: b["servername"]
        val insecure = b["skip-cert-verify"] == "true"
        val out = JSONObject()
        when (type) {
            "ss", "shadowsocks" -> {
                out.put("type", "shadowsocks")
                out.put("server", server)
                out.put("server_port", port)
                out.put("method", b["cipher"] ?: "aes-256-gcm")
                out.put("password", b["password"] ?: "")
            }
            "vmess" -> {
                out.put("type", "vmess")
                out.put("server", server)
                out.put("server_port", port)
                out.put("uuid", b["uuid"] ?: "")
                out.put("security", b["cipher"] ?: "auto")
                out.put("alter_id", b["alterId"]?.toIntOrNull() ?: 0)
                if (tlsOn) {
                    tlsObject("tls", sni, server, b["alpn"], null, insecure)?.let { out.put("tls", it) }
                }
                val net = b["network"]
                if (net == "ws") {
                    transportObject("ws", b["Host"], b["ws-path"], null)?.let { out.put("transport", it) }
                } else if (net == "grpc") {
                    transportObject("grpc", null, null, b["grpc-service-name"])?.let { out.put("transport", it) }
                }
            }
            "trojan" -> {
                out.put("type", "trojan")
                out.put("server", server)
                out.put("server_port", port)
                out.put("password", b["password"] ?: "")
                tlsObject("tls", sni, server, b["alpn"], null, insecure)?.let { out.put("tls", it) }
                val net = b["network"]
                if (net == "ws") {
                    transportObject("ws", b["Host"], b["ws-path"], null)?.let { out.put("transport", it) }
                } else if (net == "grpc") {
                    transportObject("grpc", null, null, b["grpc-service-name"])?.let { out.put("transport", it) }
                }
            }
            "vless" -> {
                out.put("type", "vless")
                out.put("server", server)
                out.put("server_port", port)
                out.put("uuid", b["uuid"] ?: "")
                val flow = b["flow"]
                if (!flow.isNullOrEmpty() && flow != "none") out.put("flow", flow)
                val reality = b["reality-opts"] != null || b["public-key"] != null
                tlsObject(
                    if (tlsOn) (if (reality) "reality" else "tls") else null,
                    sni, server, b["alpn"], b["client-fingerprint"], insecure,
                    b["public-key"], b["short-id"]
                )?.let { out.put("tls", it) }
                val net = b["network"]
                if (net == "ws") {
                    transportObject("ws", b["Host"], b["ws-path"], null)?.let { out.put("transport", it) }
                } else if (net == "grpc") {
                    transportObject("grpc", null, null, b["grpc-service-name"])?.let { out.put("transport", it) }
                }
            }
            else -> return null
        }
        return ParsedNode(name, out)
    }
}
