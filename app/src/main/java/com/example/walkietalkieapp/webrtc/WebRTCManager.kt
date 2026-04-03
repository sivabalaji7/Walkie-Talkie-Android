package com.example.walkietalkieapp.webrtc

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.walkietalkieapp.socket.SocketManager
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

private const val TAG = "WebRTCManager"

class WebRTCManager(private val context: Context) {
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    
    private var isInitialized = false
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    
    companion object {
        private const val STUN_SERVER = "stun:stun.l.google.com:19302"
        private const val TURN_SERVER = "turn:openrelay.metered.ca:80"
        private const val TURN_USERNAME = "openrelayproject"
        private const val TURN_PASSWORD = "openrelayproject"
        
        @Volatile
        private var isLibraryInitialized = false
        
        fun initializeLibrary(context: Context) {
            synchronized(this) {
                if (!isLibraryInitialized) {
                    val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions()
                    PeerConnectionFactory.initialize(options)
                    isLibraryInitialized = true
                }
            }
        }
    }
    
    fun init() {
        if (isInitialized) return
        try {
            initializeLibrary(context)
            
            audioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory()

            isInitialized = true
            Log.d(TAG, "WebRTC init successful")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init WebRTC: ${e.message}")
        }
    }
    
    fun initialize() {
        if (!isInitialized) init()
        try {
            val audioConstraints = MediaConstraints()
            audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory?.createAudioTrack("101", audioSource)
            localAudioTrack?.setEnabled(false)
            Log.d(TAG, "Audio tracks initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing tracks: ${e.message}")
        }
    }
    
    fun createPeerConnection() {
        if (peerConnectionFactory == null) return
        
        peerConnection?.dispose()
        peerConnection = null
        pendingIceCandidates.clear()
        
        val iceServers = listOf(
            PeerConnection.IceServer.builder(STUN_SERVER).createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder(TURN_SERVER)
                .setUsername(TURN_USERNAME)
                .setPassword(TURN_PASSWORD)
                .createIceServer()
        )
        
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.ALL
        
        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    mainHandler.post {
                        try {
                            val json = JSONObject()
                            json.put("sdpMid", candidate.sdpMid)
                            json.put("sdpMLineIndex", candidate.sdpMLineIndex)
                            json.put("candidate", candidate.sdp)
                            SocketManager.sendIceCandidate(json.toString())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in onIceCandidate: ${e.message}")
                        }
                    }
                }
                
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "ICE Connection: $newState")
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(dc: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                    mainHandler.post {
                        val track = receiver.track()
                        if (track is AudioTrack) {
                            Log.d(TAG, "Remote audio track received")
                            enableLoudspeaker()
                            track.setEnabled(true)
                        }
                    }
                }
            }
        )
        
        localAudioTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("LOCAL_STREAM"))
        }
        Log.d(TAG, "PeerConnection established")
    }
    
    fun startAudioCapture() {
        localAudioTrack?.setEnabled(true)
        enableLoudspeaker()
        Log.d(TAG, "Push-to-Talk active")
    }
    
    fun stopAudio() {
        localAudioTrack?.setEnabled(false)
        disableLoudspeaker()
        Log.d(TAG, "Push-to-Talk inactive")
    }
    
    private fun enableLoudspeaker() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling speaker: ${e.message}")
        }
    }
    
    private fun disableLoudspeaker() {
        try {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling speaker: ${e.message}")
        }
    }
    
    fun createOffer() {
        val pc = peerConnection ?: run {
            createPeerConnection()
            peerConnection!!
        }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        SocketManager.sendOffer(sdp.description)
                    }
                }, sdp)
            }
        }, constraints)
    }
    
    fun handleOffer(sdp: String) {
        if (peerConnection == null) createPeerConnection()
        val pc = peerConnection ?: return
        
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                createAnswer()
                drainPendingCandidates()
            }
        }, sessionDescription)
    }
    
    private fun createAnswer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        pc.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        SocketManager.sendAnswer(sdp.description)
                    }
                }, sdp)
            }
        }, constraints)
    }
    
    fun handleAnswer(sdp: String) {
        val pc = peerConnection ?: return
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                drainPendingCandidates()
            }
        }, sessionDescription)
    }
    
    fun handleIceCandidate(candidateJson: String) {
        try {
            val json = JSONObject(candidateJson)
            val candidate = IceCandidate(
                json.getString("sdpMid"),
                json.getInt("sdpMLineIndex"),
                json.getString("candidate")
            )
            
            val pc = peerConnection
            if (pc != null && pc.remoteDescription != null) {
                pc.addIceCandidate(candidate)
            } else {
                pendingIceCandidates.add(candidate)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ICE Candidate Error: ${e.message}")
        }
    }
    
    private fun drainPendingCandidates() {
        val pc = peerConnection ?: return
        pendingIceCandidates.forEach { candidate ->
            pc.addIceCandidate(candidate)
        }
        pendingIceCandidates.clear()
    }
    
    fun cleanup() {
        stopAudio()
        peerConnection?.dispose()
        peerConnection = null
        isInitialized = false
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) { Log.e(TAG, "SDP Failure: $error") }
        override fun onSetFailure(error: String) { Log.e(TAG, "SDP Set Failure: $error") }
    }
}
