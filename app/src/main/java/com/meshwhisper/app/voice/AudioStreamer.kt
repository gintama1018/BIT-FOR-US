package com.meshwhisper.app.voice

/**
 * Interface representing the real-time full-duplex audio stream for voice calls.
 * Isolating this interface allows complete unit-testability of the call state machine on JVM.
 */
interface AudioStreamer {
    /**
     * Begins capturing microphone input, encoding frames, and passing them to [onOutboundFrame].
     * Also starts playback audio pipeline.
     */
    fun startStreaming(onOutboundFrame: (sequenceNumber: Int, timestamp: Long, audioBytes: ByteArray) -> Unit)

    /**
     * Feeds an incoming real-time audio frame from the peer into the jitter buffer / playback pipeline.
     */
    fun onInboundFrame(sequenceNumber: Int, timestamp: Long, audioBytes: ByteArray)

    /**
     * Halts capture and playback, releasing hardware audio buffers.
     */
    fun stopStreaming()

    /**
     * Mutes or unmutes outbound microphone capture.
     */
    fun setMuted(muted: Boolean)

    /**
     * Routes audio output to speakerphone or earpiece.
     */
    fun setSpeakerOn(speakerOn: Boolean)
}
