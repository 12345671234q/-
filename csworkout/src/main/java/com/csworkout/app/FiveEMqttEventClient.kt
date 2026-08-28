package com.csworkout.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.math.min

class FiveEMqttEventClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun subscribe(
        matchId: String,
        onEvent: (EventRow) -> Unit,
        onStatus: (Boolean, String) -> Unit,
    ): Closeable {
        return EventSubscription(client, matchId, onEvent, onStatus)
    }
}

private class EventSubscription(
    private val client: OkHttpClient,
    private val matchId: String,
    private val onEvent: (EventRow) -> Unit,
    private val onStatus: (Boolean, String) -> Unit,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val topic = "csgo/product/event/log/$matchId"
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    private var pingJob: Job? = null

    init {
        scope.launch { reconnectLoop() }
    }

    override fun close() {
        closed = true
        pingJob?.cancel()
        socket?.close(1000, "closed")
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun reconnectLoop() {
        var attempt = 0
        while (scope.isActive && !closed) {
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            try {
                val credentials = fetchCredentials(topic)
                val request = Request.Builder()
                    .url("wss://post-cn-7mz2e5hc90i.mqtt.aliyuncs.com:443/mqtt")
                    .header("Sec-WebSocket-Protocol", "mqtt")
                    .header("Origin", "https://event.5eplay.com")
                    .build()
                val listener = object : WebSocketListener() {
                    private var subscribed = false

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        socket = webSocket
                        webSocket.send(connectPacket(credentials).toByteString())
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        runCatching {
                            decodePackets(bytes.toByteArray()).forEach { packet ->
                                when (packet.type) {
                                    2 -> {
                                        if (packet.payload.size < 2 || packet.payload[1].toInt() != 0) {
                                            error("MQTT CONNACK rejected")
                                        }
                                        webSocket.send(subscribePacket(topic).toByteString())
                                    }
                                    9 -> {
                                        if (!subscribed) {
                                            subscribed = true
                                            attempt = 0
                                            startPing(webSocket)
                                            safeStatus(true, "实时推送已连接")
                                        }
                                    }
                                    3 -> if (packet.topic == topic) {
                                        parseEventPayload(packet.payload)?.let(onEvent)
                                    }
                                    13 -> Unit
                                }
                            }
                        }.onFailure {
                            safeStatus(false, "实时推送解析异常，切换HTTP兜底")
                            webSocket.close(1002, "protocol error")
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        pingJob?.cancel()
                        if (socket === webSocket) socket = null
                        safeStatus(false, "实时推送断开，使用HTTP兜底")
                        done.complete(Unit)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        pingJob?.cancel()
                        if (socket === webSocket) socket = null
                        if (!closed) safeStatus(false, "实时推送断开，正在重连")
                        done.complete(Unit)
                    }
                }
                client.newWebSocket(request, listener)
                done.await()
            } catch (_: Throwable) {
                safeStatus(false, "实时推送暂不可用，使用HTTP兜底")
            }
            if (closed) break
            val waits = longArrayOf(1_000, 2_000, 5_000, 10_000, 20_000, 30_000)
            val wait = waits[min(attempt, waits.lastIndex)]
            attempt++
            delay(wait)
        }
    }

    private fun startPing(webSocket: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && !closed && socket === webSocket) {
                delay(20_000)
                if (!webSocket.send(byteArrayOf(0xC0.toByte(), 0x00).toByteString())) break
            }
        }
    }

    private fun safeStatus(connected: Boolean, text: String) {
        runCatching { onStatus(connected, text) }
    }

    private suspend fun fetchCredentials(topic: String): Credentials = withContext(Dispatchers.IO) {
        val body = JSONObject().put("topic", topic).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://www.5eplay.com/api/restrict/matchscore")
            .post(body)
            .header("Accept", "application/json")
            .header("User-Agent", "CSWorkout/0.2")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("MQTT credential HTTP ${response.code}")
            val root = JSONObject(text)
            if (!root.optBoolean("success", false)) error("MQTT credential rejected")
            val data = root.optJSONObject("data") ?: error("MQTT credential missing data")
            Credentials(
                clientId = data.optString("client_id").ifBlank { error("MQTT client_id missing") },
                username = data.optString("username").ifBlank { error("MQTT username missing") },
                password = data.optString("password").ifBlank { error("MQTT password missing") },
            )
        }
    }

    private fun parseEventPayload(bytes: ByteArray): EventRow? {
        val root = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return null
        val data = root.optJSONObject("data") ?: return null
        val info = data.optJSONObject("info") ?: return null
        if (info.optString("match_id") != matchId) return null
        val version = info.optString("update_version").toLongOrNull() ?: return null
        val raw = when (val log = info.opt("log_info")) {
            is String -> log
            is JSONObject -> log.toString()
            else -> return null
        }
        return EventRow(version, raw, info.optString("map_name"))
    }
}

private data class Credentials(
    val clientId: String,
    val username: String,
    val password: String,
)

private data class MqttPacket(
    val type: Int,
    val flags: Int,
    val topic: String?,
    val payload: ByteArray,
)

private fun mqttString(value: String): ByteArray {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size <= 65535)
    return byteArrayOf((bytes.size shr 8).toByte(), (bytes.size and 0xff).toByte()) + bytes
}

private fun remainingLength(value: Int): ByteArray {
    var remaining = value
    val out = mutableListOf<Byte>()
    do {
        var digit = remaining % 128
        remaining /= 128
        if (remaining > 0) digit = digit or 0x80
        out += digit.toByte()
    } while (remaining > 0)
    return out.toByteArray()
}

private fun packet(header: Int, body: ByteArray): ByteArray {
    return byteArrayOf(header.toByte()) + remainingLength(body.size) + body
}

private fun connectPacket(credentials: Credentials): ByteArray {
    val variable = mqttString("MQTT") + byteArrayOf(4, 0xC2.toByte(), 0, 30)
    val payload = mqttString(credentials.clientId) + mqttString(credentials.username) + mqttString(credentials.password)
    return packet(0x10, variable + payload)
}

private fun subscribePacket(topic: String): ByteArray {
    val body = byteArrayOf(0, 1) + mqttString(topic) + byteArrayOf(0)
    return packet(0x82, body)
}

private fun decodePackets(bytes: ByteArray): List<MqttPacket> {
    val packets = mutableListOf<MqttPacket>()
    var offset = 0
    while (offset < bytes.size) {
        val first = bytes[offset++].toInt() and 0xff
        var multiplier = 1
        var length = 0
        var digit: Int
        do {
            if (offset >= bytes.size) error("incomplete MQTT remaining length")
            digit = bytes[offset++].toInt() and 0xff
            length += (digit and 0x7f) * multiplier
            multiplier *= 128
            if (multiplier > 128 * 128 * 128 * 128) error("invalid MQTT length")
        } while ((digit and 0x80) != 0)
        val end = offset + length
        if (end > bytes.size) error("incomplete MQTT packet")
        val type = first shr 4
        val flags = first and 0x0f
        var payload = bytes.copyOfRange(offset, end)
        var topic: String? = null
        if (type == 3) {
            if (payload.size < 2) error("invalid MQTT publish")
            val topicLength = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
            val topicEnd = 2 + topicLength
            if (topicEnd > payload.size) error("invalid MQTT topic")
            topic = payload.copyOfRange(2, topicEnd).toString(Charsets.UTF_8)
            val qos = (flags shr 1) and 0x03
            val bodyStart = topicEnd + if (qos > 0) 2 else 0
            if (bodyStart > payload.size) error("invalid MQTT publish body")
            payload = payload.copyOfRange(bodyStart, payload.size)
        }
        packets += MqttPacket(type, flags, topic, payload)
        offset = end
    }
    return packets
}