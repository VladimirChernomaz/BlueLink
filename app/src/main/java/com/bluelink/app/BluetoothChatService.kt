package com.bluelink.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Manages Bluetooth connections: listens for incoming connections (server side)
 * and can also open an outgoing connection (client side). Runs entirely with
 * plain threads and a Handler to post results back to the UI thread.
 */
object BluetoothChatService {

    private lateinit var appContext: android.content.Context

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    // Custom UUID identifying this app's chat channel.
    private val APP_UUID: UUID = UUID.fromString("27b7d1da-08c7-4505-a6d1-2459987e5e2d")
    private const val APP_NAME = "BlueLink"

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    @Volatile private var connectedThread: ConnectedThread? = null

    @Volatile var isConnected: Boolean = false
        private set

    @Volatile var connectedDeviceName: String? = null
        private set

    // Listener the currently visible screen can attach to receive events.
    interface Listener {
        fun onIncomingRequest(device: BluetoothDevice) {}
        fun onConnected(deviceName: String) {}
        fun onConnectionFailed() {}
        fun onDisconnected() {}
        fun onMessageReceived(text: String) {}
    }

    var listener: Listener? = null

    private var pendingIncomingSocket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (acceptThread == null) {
            acceptThread = AcceptThread().also { it.start() }
        }
    }

    fun stopListening() {
        acceptThread?.cancel()
        acceptThread = null
    }

    @SuppressLint("MissingPermission")
    fun connectTo(device: BluetoothDevice) {
        if (isConnected) return
        connectThread?.cancel()
        connectThread = ConnectThread(device).also { it.start() }
    }

    /** Called from the UI after the user taps the accept (✅) button. */
    fun acceptIncoming() {
        val socket = pendingIncomingSocket ?: return
        pendingIncomingSocket = null
        startConnected(socket)
    }

    /** Called from the UI after the user taps the reject (❌) button. */
    fun rejectIncoming() {
        try {
            pendingIncomingSocket?.close()
        } catch (_: IOException) {
        }
        pendingIncomingSocket = null
    }

    fun sendMessage(text: String): Boolean {
        val thread = connectedThread ?: return false
        thread.write(text)
        return true
    }

    fun disconnect() {
        connectedThread?.cancel()
        connectedThread = null
        isConnected = false
        connectedDeviceName = null
    }

    @SuppressLint("MissingPermission")
    private fun startConnected(socket: BluetoothSocket) {
        connectThread?.cancel()
        connectThread = null

        connectedThread?.cancel()
        connectedThread = ConnectedThread(socket).also { it.start() }

        val name = try {
            socket.remoteDevice.name ?: socket.remoteDevice.address
        } catch (_: SecurityException) {
            "Устройство"
        }
        isConnected = true
        connectedDeviceName = name
        handler.post { listener?.onConnected(name) }
    }

    // ---- Server side: waits for another phone to connect to us ----
    private class AcceptThread : Thread() {
        private var serverSocket: BluetoothServerSocket? = null

        @SuppressLint("MissingPermission")
        override fun run() {
            try {
                serverSocket = adapter?.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
            } catch (_: IOException) {
                return
            } catch (_: SecurityException) {
                return
            }

            while (true) {
                val socket: BluetoothSocket = try {
                    serverSocket?.accept() ?: return
                } catch (_: IOException) {
                    return
                }

                if (isConnected) {
                    // Already busy with another chat - politely refuse extra incoming sockets.
                    try {
                        socket.close()
                    } catch (_: IOException) {
                    }
                    continue
                }

                // Ask the current screen to show an accept/reject prompt.
                pendingIncomingSocket = socket
                val device = socket.remoteDevice
                handler.post { listener?.onIncomingRequest(device) }
            }
        }

        fun cancel() {
            try {
                serverSocket?.close()
            } catch (_: IOException) {
            }
        }
    }

    // ---- Client side: we are the one initiating the connection ----
    private class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private var socket: BluetoothSocket? = null

        @SuppressLint("MissingPermission")
        override fun run() {
            try {
                socket = device.createRfcommSocketToServiceRecord(APP_UUID)
            } catch (_: IOException) {
                handler.post { listener?.onConnectionFailed() }
                return
            } catch (_: SecurityException) {
                handler.post { listener?.onConnectionFailed() }
                return
            }

            try {
                socket?.connect()
            } catch (_: IOException) {
                try {
                    socket?.close()
                } catch (_: IOException) {
                }
                handler.post { listener?.onConnectionFailed() }
                return
            }

            socket?.let { startConnected(it) }
        }

        fun cancel() {
            try {
                socket?.close()
            } catch (_: IOException) {
            }
        }
    }

    // ---- Active connection: reads and writes plain-text messages ----
    private class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val input: InputStream? = try {
            socket.inputStream
        } catch (_: IOException) {
            null
        }
        private val output: OutputStream? = try {
            socket.outputStream
        } catch (_: IOException) {
            null
        }

        override fun run() {
            val buffer = ByteArray(4096)
            while (true) {
                var failureReason = "EOF"
                val bytesRead = try {
                    input?.read(buffer) ?: -1
                } catch (e: IOException) {
                    failureReason = e.message ?: e.javaClass.simpleName
                    -1
                }
                if (bytesRead <= 0) {
                    handler.post {
                        android.widget.Toast.makeText(
                            appContext,
                            "🔴 Разрыв чтения: $failureReason",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        isConnected = false
                        connectedDeviceName = null
                        listener?.onDisconnected()
                    }
                    return
                }
                val text = String(buffer, 0, bytesRead, Charsets.UTF_8)
                handler.post { listener?.onMessageReceived(text) }
            }
        }

        fun write(text: String) {
            try {
                output?.write(text.toByteArray(Charsets.UTF_8))
            } catch (_: IOException) {
            }
        }

        fun cancel() {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }
}
