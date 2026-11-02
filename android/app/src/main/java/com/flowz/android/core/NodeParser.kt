package com.flowz.android.core

import android.util.Base64
import java.net.URI
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generic proxy-link parsers (vless / vmess / trojan / shadowsocks /
 * hysteria2) producing sing-box outbound objects.
 *
 * This file contains only parsing logic. It never holds, logs or
 * transmits any real node value; values live only in the user's
 * own subscription fetch on their device.
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

    fun parseSubscription(text: String): Pair<List<ParsedNode>, Int> {
        val body = decodeIfBase64(text.trim())
        val nodes = mutableListOf<ParsedNode>()
        var failed = 0
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            try {
                val node = parseLink(line) ?: continue
                nodes.add(node)
            } catch (_: Exception) {
                failed++
            }
        }
        return nodes to failed
    }

    private fun decodeIfBase64(text: String): String {
        if (text.contains("://")) return text
        return try {
            val clean = text.replace("\\s".toRegex(), "")
            val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
            String(Base64.decode(padded, Base64.URL_SAFE), Charsets.UTF_8)
        } catch (_: Exception) {
            text
        }
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
    private fun tlsObject(
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

    private fun transportObject(
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
}
