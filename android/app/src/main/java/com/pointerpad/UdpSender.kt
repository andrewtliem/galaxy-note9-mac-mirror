package com.pointerpad

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class UdpSender {
    @Volatile
    private var host: String = ""
    @Volatile
    private var port: Int = 50505
    @Volatile
    var token: String = ""

    private val socket = DatagramSocket()
    private val executor = Executors.newSingleThreadExecutor()

    fun updateTarget(newHost: String, newPort: Int) {
        host = newHost
        port = newPort
    }

    fun send(json: JSONObject) {
        val targetHost = host
        if (targetHost.isBlank()) return
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        executor.execute {
            try {
                val address = InetAddress.getByName(targetHost)
                val packet = DatagramPacket(payload, payload.size, address, port)
                socket.send(packet)
            } catch (_: Exception) {
                // Best-effort; ignore send errors to keep UI responsive.
            }
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
        executor.shutdownNow()
    }
}
