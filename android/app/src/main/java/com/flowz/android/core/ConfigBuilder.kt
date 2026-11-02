package com.flowz.android.core

/**
 * Skeleton demo config: TUN up, everything direct. It proves the whole
 * pipeline (VpnService -> libbox -> TUN) without any real node.
 * Subscription / node parsing and real outbounds come in v1.
 */
object ConfigBuilder {
    fun demoConfig(): String {
        return """
            {
              "log": { "level": "info" },
              "dns": {
                "servers": [
                  { "tag": "remote", "address": "223.5.5.5", "detour": "direct" }
                ],
                "final": "remote"
              },
              "inbounds": [
                {
                  "type": "tun",
                  "tag": "tun-in",
                  "inet4_address": "172.19.0.0/30",
                  "mtu": 9000,
                  "auto_route": true,
                  "stack": "system"
                }
              ],
              "outbounds": [
                { "type": "direct", "tag": "direct" }
              ],
              "route": {
                "final": "direct",
                "auto_detect_interface": true
              }
            }
        """.trimIndent()
    }
}
