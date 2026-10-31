package com.lanrhyme.micyou.network

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PcMixerStatus(val available: Boolean, val music: Float)

object PcMixerClient {
    suspend fun request(music: Float? = null): PcMixerStatus = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 8556), 500)
            socket.soTimeout = 1500
            val request = JSONObject()
            if (music != null) request.put("music", music.toDouble())
            socket.getOutputStream().write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            val reader = socket.getInputStream().bufferedReader()
            val response = StringBuilder()
            while (response.length < 2048) {
                val next = reader.read()
                if (next == -1 || next == 10) break
                response.append(next.toChar())
            }
            val data = JSONObject(response.toString())
            val level = data.optDouble("music", 1.0).toFloat()
            PcMixerStatus(data.optBoolean("available", false),
                if (level.isFinite()) level.coerceIn(0f, 1f) else 1f)
        }
    }
}
