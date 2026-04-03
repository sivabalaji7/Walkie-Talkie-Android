package com.example.walkietalkieapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.walkietalkieapp.socket.SignalingListener
import com.example.walkietalkieapp.socket.SocketUiState
import com.example.walkietalkieapp.socket.SocketManager
import com.example.walkietalkieapp.webrtc.WebRTCManager
import com.example.walkietalkieapp.ui.theme.WalkieTalkieAppTheme

private const val TAG = "WalkieTalkieApp"

class MainActivity : ComponentActivity(), SignalingListener {
    private var hasAudioPermission by mutableStateOf(false)
    private var hasRequestedAudioPermission by mutableStateOf(false)
    private var shouldShowAudioPermissionRationale by mutableStateOf(false)
    
    private var webRTCManager: WebRTCManager? = null

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            hasRequestedAudioPermission = true
            hasAudioPermission = isGranted
            shouldShowAudioPermissionRationale =
                !isGranted && shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

            if (isGranted) {
                initializeWebRTC()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshAudioPermissionState()
        
        if (hasAudioPermission) {
            initializeWebRTC()
        }
        
        SocketManager.setSignalingListener(this)
        SocketManager.initialize()
        SocketManager.connect()

        setContent {
            val socketUiState by SocketManager.socketUiState.collectAsState()

            WalkieTalkieAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WalkieTalkieScreen(
                        hasAudioPermission = hasAudioPermission,
                        hasRequestedAudioPermission = hasRequestedAudioPermission,
                        shouldShowAudioPermissionRationale = shouldShowAudioPermissionRationale,
                        socketUiState = socketUiState,
                        onRequestAudioPermission = ::requestAudioPermission,
                        onConnectSocket = { SocketManager.connect() },
                        onDisconnectSocket = { SocketManager.disconnect() },
                        onSendTestMessage = {
                            SocketManager.sendMessage("hello")
                        },
                        onStartPushToTalk = ::startPushToTalk,
                        onStopPushToTalk = ::stopPushToTalk
                    )
                }
            }
        }
    }

    private fun initializeWebRTC() {
        if (webRTCManager != null) return
        
        try {
            webRTCManager = WebRTCManager(this).apply {
                init()
                initialize()
                createPeerConnection()
            }
            Log.d(TAG, "WebRTC initialization completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC: ${e.message}", e)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAudioPermissionState()
    }

    override fun onDestroy() {
        SocketManager.setSignalingListener(null)
        SocketManager.disconnect()
        webRTCManager?.cleanup()
        super.onDestroy()
    }

    private fun requestAudioPermission() {
        if (hasAudioPermission) return
        hasRequestedAudioPermission = true
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun refreshAudioPermissionState() {
        hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        shouldShowAudioPermissionRationale =
            !hasAudioPermission && shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
    }

    override fun onOfferReceived(sdp: String) {
        runOnUiThread {
            Log.d(TAG, "Offer received, handling on UI thread")
            webRTCManager?.handleOffer(sdp)
        }
    }

    override fun onAnswerReceived(sdp: String) {
        runOnUiThread {
            Log.d(TAG, "Answer received, handling on UI thread")
            webRTCManager?.handleAnswer(sdp)
        }
    }

    override fun onIceCandidateReceived(candidate: String) {
        runOnUiThread {
            Log.d(TAG, "ICE Candidate received, handling on UI thread")
            webRTCManager?.handleIceCandidate(candidate)
        }
    }
    
    private fun startPushToTalk() {
        if (!hasAudioPermission) {
            requestAudioPermission()
            return
        }
        
        if (webRTCManager == null) {
            initializeWebRTC()
        }
        
        Log.d(TAG, "Push-to-talk started")
        webRTCManager?.startAudioCapture()
        webRTCManager?.createOffer()
    }
    
    private fun stopPushToTalk() {
        Log.d(TAG, "Push-to-talk stopped")
        webRTCManager?.stopAudio()
    }
}

@Composable
fun WalkieTalkieScreen(
    hasAudioPermission: Boolean,
    hasRequestedAudioPermission: Boolean,
    shouldShowAudioPermissionRationale: Boolean,
    socketUiState: SocketUiState,
    onRequestAudioPermission: () -> Unit,
    onConnectSocket: () -> Unit,
    onDisconnectSocket: () -> Unit,
    onSendTestMessage: () -> Unit,
    onStartPushToTalk: () -> Unit,
    onStopPushToTalk: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var wasPressed by remember { mutableStateOf(false) }
    val screenScrollState = rememberScrollState()

    LaunchedEffect(isPressed) {
        if (isPressed) {
            wasPressed = true
            onStartPushToTalk()
        } else if (wasPressed) {
            wasPressed = false
            onStopPushToTalk()
        }
    }

    val buttonContainerColor = when {
        isPressed -> MaterialTheme.colorScheme.primaryContainer
        hasAudioPermission -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val buttonContentColor = when {
        isPressed -> MaterialTheme.colorScheme.onPrimaryContainer
        hasAudioPermission -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(screenScrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Walkie Talkie App",
            style = MaterialTheme.typography.headlineMedium
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (socketUiState.isConnected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Server: ${socketUiState.status}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (socketUiState.isConnected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = socketUiState.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (socketUiState.isConnected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (socketUiState.isConnected) {
                        "App is ready. Hold the button to talk."
                    } else {
                        "Waiting for connection..."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (socketUiState.isConnected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onConnectSocket,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = if (socketUiState.isConnected) "Reconnect" else "Connect")
            }
            Button(
                onClick = onDisconnectSocket,
                modifier = Modifier.weight(1f),
                enabled = socketUiState.isConnected
            ) {
                Text(text = "Disconnect")
            }
        }
        Button(
            onClick = {
                if (!hasAudioPermission) {
                    onRequestAudioPermission()
                }
            },
            modifier = Modifier.size(220.dp),
            shape = CircleShape,
            interactionSource = interactionSource,
            contentPadding = PaddingValues(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = buttonContainerColor,
                contentColor = buttonContentColor
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 8.dp,
                pressedElevation = 2.dp
            )
        ) {
            Text(
                text = "Hold to Talk",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
        }
        Text(
            text = permissionStatusText(
                hasAudioPermission = hasAudioPermission,
                hasRequestedAudioPermission = hasRequestedAudioPermission,
                shouldShowAudioPermissionRationale = shouldShowAudioPermissionRationale
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (hasAudioPermission) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            textAlign = TextAlign.Center
        )
        Button(
            onClick = onSendTestMessage,
            modifier = Modifier.fillMaxWidth(),
            enabled = socketUiState.isConnected
        ) {
            Text(text = "Send Test Message")
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Test Log",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                socketUiState.eventLog.forEach { logLine ->
                    Text(
                        text = logLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun permissionStatusText(
    hasAudioPermission: Boolean,
    hasRequestedAudioPermission: Boolean,
    shouldShowAudioPermissionRationale: Boolean
): String {
    return when {
        hasAudioPermission -> "Microphone ready."
        shouldShowAudioPermissionRationale -> "Microphone access is needed."
        hasRequestedAudioPermission -> "Microphone permission denied."
        else -> "Microphone access required."
    }
}
