package com.pointerpad

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.Executors

class ControlReceiver(
    private val port: Int = 50507,
    private val onMessage: (JSONObject) -> Unit
) {
    @Volatile
    private var running = false
    private val executor = Executors.newSingleThreadExecutor()
    private var socket: DatagramSocket? = null

    fun start() {
        if (running) return
        running = true
        executor.execute {
            try {
                val sock = DatagramSocket(port)
                socket = sock
                val buffer = ByteArray(8192)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    sock.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    try {
                        val json = JSONObject(text)
                        onMessage(json)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }
}
