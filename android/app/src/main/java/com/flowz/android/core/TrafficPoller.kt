package com.flowz.android.core

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Polls the loopback clash API (/connections) while connected and
 * derives up/down rates from summed connection totals.
 * Any failure (API not up yet, refused) degrades to zeros, never a
 * crash: the status bar simply shows 0 until the core answers.
 */
object TrafficPoller {

    data class Traffic(val upBps: Long, val downBps: Long, val count: Int)

    private var job: Job? = null
    private var lastUp = -1L
    private var lastDown = -1L
    private var lastAt = 0L

    fun start(onTraffic: (Traffic) -> Unit, onTick: (Long) -> Unit) {
        stop()
        lastUp = -1L
        lastDown = -1L
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                onTick(now)
                val (up, down, count) = readTotals()
                if (lastUp >= 0 && now > lastAt) {
                    val secs = (now - lastAt) / 1000.0
                    val upBps = ((up - lastUp) / secs).toLong().coerceAtLeast(0L)
                    val downBps = ((down - lastDown) / secs).toLong().coerceAtLeast(0L)
                    onTraffic(Traffic(upBps, downBps, count))
                } else {
                    onTraffic(Traffic(0L, 0L, count))
                }
                lastUp = up
                lastDown = down
                lastAt = now
                delay(2000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun readTotals(): Triple<Long, Long, Int> {
        return try {
            val conn = URL("http://127.0.0.1:9090/connections").openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            try {
                if (conn.responseCode !in 200..299) return Triple(0L, 0L, 0)
                val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
                val arr = json.optJSONArray("connections") ?: return Triple(0L, 0L, 0)
                var up = 0L
                var down = 0L
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    up += c.optLong("upload")
                    down += c.optLong("download")
                }
                Triple(up, down, arr.length())
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            Triple(0L, 0L, 0)
        }
    }
}
