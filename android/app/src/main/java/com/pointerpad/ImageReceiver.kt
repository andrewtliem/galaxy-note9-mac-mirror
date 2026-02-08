package com.pointerpad

import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.Executors

class ImageReceiver(
    private val port: Int = 50506,
    private val onImage: (ImagePacket) -> Unit
) {
    private val maxHeaderBytes = 16 * 1024
    private val maxImageBytes = 10 * 1024 * 1024

    data class ImagePacket(
        val id: String,
        val token: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val bytes: ByteArray
    )

    @Volatile
    private var running = false
    private val executor = Executors.newSingleThreadExecutor()
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                while (running) {
                    val socket = serverSocket?.accept() ?: break
                    try {
                        val input = DataInputStream(socket.getInputStream())
                        val headerLen = input.readInt()
                        if (headerLen <= 0 || headerLen > maxHeaderBytes) {
                            continue
                        }
                        val headerBytes = ByteArray(headerLen)
                        input.readFully(headerBytes)
                        val header = JSONObject(String(headerBytes, Charsets.UTF_8))

                        val imageLen = input.readInt()
                        if (imageLen <= 0 || imageLen > maxImageBytes) {
                            continue
                        }
                        val imageBytes = ByteArray(imageLen)
                        input.readFully(imageBytes)

                        val id = header.optString("id")
                        val token = header.optString("token")
                        val x = header.optDouble("x", 0.0).toFloat()
                        val y = header.optDouble("y", 0.0).toFloat()
                        val width = header.optDouble("width", 0.0).toFloat()
                        val height = header.optDouble("height", 0.0).toFloat()

                        if (id.isNotEmpty()) {
                            onImage(ImagePacket(id, token, x, y, width, height, imageBytes))
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }
}
