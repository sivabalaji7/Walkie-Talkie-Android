package com.example.walkietalkieapp.socket

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "SocketManager"
// Ensure this URL is correct and active in your ngrok terminal
private const val SERVER_URL = "https://unforgetting-melodie-overfiercely.ngrok-free.dev" 
private const val MAX_LOG_ENTRIES = 20

data class SocketUiState(
    val isConnected: Boolean = false,
    val status: String = "Disconnected",
    val detail: String = "Waiting to connect to server.",
    val eventLog: List<String> = listOf("Ready.")
)

object SocketManager {
    @Volatile
    private var socket: Socket? = null
    private var signalingListener: SignalingListener? = null

    private val _socketUiState = MutableStateFlow(SocketUiState())
    val socketUiState: StateFlow<SocketUiState> = _socketUiState.asStateFlow()

    fun setSignalingListener(listener: SignalingListener?) {
        signalingListener = listener
    }

    fun initialize() {
        if (socket != null) return

        synchronized(this) {
            if (socket != null) return@synchronized

            val opts = IO.Options().apply {
                forceNew = true
                reconnection = true
                reconnectionDelay = 1000
                timeout = 10000
            }

            runCatching { IO.socket(SERVER_URL, opts) }
                .onSuccess { createdSocket ->
                    socket = createdSocket
                    
                    createdSocket.on(Socket.EVENT_CONNECT) {
                        Log.d(TAG, "Socket connected")
                        updateState(true, "Connected", "Server connected via Internet. App is ready.", "Connected to server")
                    }

                    createdSocket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                        val err = args.joinToString { it.toString() }
                        Log.e(TAG, "Socket connect error: $err")
                        updateState(false, "Connection Error", "Cannot reach server. Check your internet/ngrok.", "Connect error: $err")
                    }

                    createdSocket.on(Socket.EVENT_DISCONNECT) { args ->
                        val reason = args.firstOrNull()?.toString() ?: "unknown"
                        Log.d(TAG, "Socket disconnected: $reason")
                        updateState(false, "Disconnected", "Disconnected from server.", "Disconnected: $reason")
                    }

                    // RESTORED: Listen for test messages to update the log
                    createdSocket.on("message") { args ->
                        val msg = args.firstOrNull()?.toString().orEmpty()
                        Log.d(TAG, "Message received: $msg")
                        updateState(true, "Connected", "Message received", "Msg: $msg")
                    }

                    createdSocket.on("offer") { args ->
                        val sdp = args.firstOrNull()?.toString().orEmpty()
                        signalingListener?.onOfferReceived(sdp)
                    }

                    createdSocket.on("answer") { args ->
                        val sdp = args.firstOrNull()?.toString().orEmpty()
                        signalingListener?.onAnswerReceived(sdp)
                    }

                    createdSocket.on("ice-candidate") { args ->
                        val candidate = args.firstOrNull()?.toString().orEmpty()
                        signalingListener?.onIceCandidateReceived(candidate)
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to initialize socket: ${e.message}")
                }
        }
    }

    fun connect() {
        initialize()
        socket?.let {
            if (!it.connected()) {
                Log.d(TAG, "Manually connecting socket...")
                it.connect()
            }
        }
    }

    fun disconnect() {
        Log.d(TAG, "Manually disconnecting socket...")
        socket?.disconnect()
    }

    fun sendMessage(msg: String) {
        if (socket?.connected() == true) {
            socket?.emit("message", msg)
            updateState(true, "Connected", "Message sent", "Sent: $msg")
        } else {
            updateState(false, "Disconnected", "Connect first", "Failed to send message")
        }
    }

    fun sendOffer(sdp: String) {
        socket?.emit("offer", sdp)
    }

    fun sendAnswer(sdp: String) {
        socket?.emit("answer", sdp)
    }

    fun sendIceCandidate(candidate: String) {
        socket?.emit("ice-candidate", candidate)
    }

    private fun updateState(isConnected: Boolean, status: String, detail: String, logEntry: String) {
        _socketUiState.update { currentState ->
            currentState.copy(
                isConnected = isConnected,
                status = status,
                detail = detail,
                eventLog = (listOf(withTimestamp(logEntry)) + currentState.eventLog).take(MAX_LOG_ENTRIES)
            )
        }
    }

    private fun withTimestamp(message: String): String {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        return "[$timestamp] $message"
    }
}
