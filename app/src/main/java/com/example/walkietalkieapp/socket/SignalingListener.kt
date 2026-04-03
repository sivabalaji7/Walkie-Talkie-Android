package com.example.walkietalkieapp.socket

interface SignalingListener {
    fun onOfferReceived(sdp: String)
    fun onAnswerReceived(sdp: String)
    fun onIceCandidateReceived(candidate: String)
}
