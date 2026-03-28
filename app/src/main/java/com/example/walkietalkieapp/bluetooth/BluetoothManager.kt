package com.example.walkietalkieapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothManager(
    private val onConnected: (BluetoothDevice, Boolean) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    private enum class ConnectionState {
        IDLE,
        LISTENING,
        CONNECTING,
        CONNECTED
    }

    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val serviceName = "WalkieTalkieApp"

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var acceptThread: Thread? = null
    private var listenThread: Thread? = null

    @Volatile
    private var shuttingDown = false

    @Volatile
    private var connectionState = ConnectionState.IDLE
    private var connectedDeviceAddress: String? = null

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startServer(adapter: BluetoothAdapter) {
        bluetoothAdapter = adapter

        if (isConnected() || connectionState == ConnectionState.CONNECTING || acceptThread?.isAlive == true) {
            return
        }

        shuttingDown = false
        connectionState = ConnectionState.LISTENING
        closeServerSocket()
        acceptThread = Thread {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(serviceName, uuid)
                val incomingSocket = serverSocket?.accept()
                if (incomingSocket != null && !shuttingDown) {
                    bindSocket(incomingSocket, true)
                }
            } catch (_: Exception) {
                if (!shuttingDown) {
                    connectionState = ConnectionState.IDLE
                    onError("Incoming Bluetooth connection stopped.")
                }
            } finally {
                closeServerSocket()
                acceptThread = null
            }
        }.apply {
            name = "BluetoothAcceptThread"
            start()
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connect(adapter: BluetoothAdapter, device: BluetoothDevice) {
        if (connectionState == ConnectionState.CONNECTING) {
            return
        }
        if (isConnected() && connectedDeviceAddress == device.address) {
            return
        }

        bluetoothAdapter = adapter
        shuttingDown = false
        connectionState = ConnectionState.CONNECTING

        Thread {
            try {
                adapter.cancelDiscovery()
                closeServerSocket()
                closeCurrentSocket(notifyDisconnect = false)
                val clientSocket = device.createRfcommSocketToServiceRecord(uuid)
                clientSocket.connect()
                bindSocket(clientSocket, false)
            } catch (error: Exception) {
                connectionState = ConnectionState.IDLE
                closeCurrentSocket(notifyDisconnect = false)
                onError("Could not connect to ${device.name ?: device.address}.")
                startServer(adapter)
            }
        }.apply {
            name = "BluetoothConnectThread"
            start()
        }
    }

    fun send(data: ByteArray) {
        try {
            outputStream?.write(data)
        } catch (_: Exception) {
            onError("Bluetooth audio send failed.")
            closeCurrentSocket()
        }
    }

    fun isConnected(): Boolean = socket?.isConnected == true

    fun stop() {
        shuttingDown = true
        connectionState = ConnectionState.IDLE
        connectedDeviceAddress = null
        closeServerSocket()
        closeCurrentSocket(notifyDisconnect = false)
    }

    private fun bindSocket(connectedSocket: BluetoothSocket, incoming: Boolean) {
        closeCurrentSocket(notifyDisconnect = false)
        socket = connectedSocket
        outputStream = connectedSocket.outputStream
        connectedDeviceAddress = connectedSocket.remoteDevice.address
        connectionState = ConnectionState.CONNECTED
        closeServerSocket()

        startListening()
        onConnected(connectedSocket.remoteDevice, incoming)
    }

    private fun startListening() {
        listenThread?.interrupt()
        listenThread = Thread {
            val buffer = ByteArray(2048)
            val input: InputStream = socket?.inputStream ?: return@Thread
            while (true) {
                try {
                    val bytes = input.read(buffer)
                    if (bytes <= 0) {
                        break
                    }
                    onAudioReceived(buffer.copyOf(bytes))
                } catch (_: Exception) {
                    break
                }
            }
            closeCurrentSocket()
        }.apply {
            name = "BluetoothListenThread"
            start()
        }
    }

    private fun closeServerSocket() {
        serverSocket?.runCatching { close() }
        serverSocket = null
    }

    private fun closeCurrentSocket(notifyDisconnect: Boolean = true) {
        listenThread = null
        outputStream = null
        socket?.runCatching { close() }
        socket = null
        connectedDeviceAddress = null
        if (!shuttingDown) {
            connectionState = ConnectionState.IDLE
        }

        if (!shuttingDown && notifyDisconnect) {
            onDisconnected()
            bluetoothAdapter?.let { startServer(it) }
        }
    }
}
