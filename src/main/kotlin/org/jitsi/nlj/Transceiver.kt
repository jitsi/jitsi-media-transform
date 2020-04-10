/*
 * Copyright @ 2018 - Present, 8x8 Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.nlj

import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.TransportCcEngine
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.rtp.bandwidthestimation.GoogleCcEstimator
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.nlj.stats.EndpointConnectionStats
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.stats.PacketIOActivity
import org.jitsi.nlj.stats.TransceiverStats
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.util.LocalSsrcAssociation
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.SsrcAssociation
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.cinfo
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi_modified.impl.neomedia.rtp.MediaStreamTrackDesc
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

// This is an API class, so its usages will largely be outside of this library
@Suppress("unused")
/**
 * Handles all packets (incoming and outgoing) for a particular stream.
 * (TODO: 'stream' defined as what, exactly, here?)
 * Handles the DTLS negotiation
 *
 * Incoming packets should be written via [handleIncomingPacket].  Outgoing
 * packets are put in [outgoingQueue] (and should be read by something)
 * TODO: maybe we want to have this 'push' the outgoing packets somewhere
 * else instead (then we could have all senders push to a single queue and
 * have the one thread just read from the queue and send, rather than that thread
 * having to read from a bunch of individual queues)
 */
class Transceiver(
    private val id: String,
    receiverExecutor: ExecutorService,
    senderExecutor: ExecutorService,
    /**
     * A [ScheduledExecutorService] which can be used for less important
     * background tasks, or tasks that need to execute at some fixed delay/rate
     */
    backgroundExecutor: ScheduledExecutorService,
    diagnosticContext: DiagnosticContext,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC()
) : Stoppable, NodeStatsProducer {
    private val logger = createChildLogger(parentLogger)
    val packetIOActivity = PacketIOActivity()
    private val endpointConnectionStats = EndpointConnectionStats(logger)
    private val streamInformationStore = StreamInformationStoreImpl()
    val readOnlyStreamInformationStore: ReadOnlyStreamInformationStore = streamInformationStore
    /**
     * A central place to subscribe to be notified on the reception or transmission of RTCP packets for
     * this transceiver.  This is intended to be used by internal entities: mainly logic for things like generating
     * SRs and RRs and calculating RTT.  Since it is used for both send and receive, it is held here and passed to
     * the sender and receive so each can push or subscribe to updates.
     */
    private val rtcpEventNotifier = RtcpEventNotifier()

    private var mediaStreamTracks = MediaStreamTracks()

    private val bandwidthEstimator: BandwidthEstimator = GoogleCcEstimator(diagnosticContext, logger)

    private val transportCcEngine = TransportCcEngine(bandwidthEstimator, logger)

    /**
     * Whether this [Transceiver] is receiving audio from the remote endpoint.
     */
    fun isReceivingAudio(): Boolean = rtpReceiver.isReceivingAudio()

    /**
     * Whether this [Transceiver] is receiving video from the remote endpoint.
     */
    fun isReceivingVideo(): Boolean = rtpReceiver.isReceivingVideo()

    private val rtpSender: RtpSender = RtpSenderImpl(
        id,
        transportCcEngine,
        rtcpEventNotifier,
        senderExecutor,
        backgroundExecutor,
        streamInformationStore,
        logger,
        diagnosticContext
    )
    private val rtpReceiver: RtpReceiver =
        RtpReceiverImpl(
            id,
            { rtcpPacket ->
                if (rtcpPacket.length >= 1500) {
                    logger.warn("Sending large locally-generated RTCP packet of size ${rtcpPacket.length}, first packet of type ${rtcpPacket.packetType}.")
                }
                rtpSender.processPacket(PacketInfo(rtcpPacket))
            },
            rtcpEventNotifier,
            receiverExecutor,
            backgroundExecutor,
            streamInformationStore,
            logger,
            diagnosticContext
        )

    init {
        rtcpEventNotifier.addRtcpEventListener(endpointConnectionStats)

        rtcpEventNotifier.addRtcpEventListener(transportCcEngine)

        endpointConnectionStats.addListener(rtpSender)
        endpointConnectionStats.addListener(rtpReceiver)
    }

    fun onBandwidthEstimateChanged(listener: BandwidthEstimator.Listener) {
        bandwidthEstimator.addListener(listener)
        rtpReceiver.onBandwidthEstimateChanged(listener)
    }

    /**
     * Handle an incoming [PacketInfo] (that is, a packet received by the endpoint
     * this transceiver is associated with) to be processed by the receiver pipeline.
     */
    fun handleIncomingPacket(p: PacketInfo) {
        packetIOActivity.lastRtpPacketReceivedInstant = clock.instant()
        rtpReceiver.enqueuePacket(p)
    }

    /**
     * Send packets to the endpoint this transceiver is associated with by
     * passing them out the sender's outgoing pipeline
     */
    fun sendPacket(packetInfo: PacketInfo) {
        packetIOActivity.lastRtpPacketSentInstant = clock.instant()
        rtpSender.processPacket(packetInfo)
    }

    fun sendProbing(mediaSsrc: Long, numBytes: Int): Int = rtpSender.sendProbing(mediaSsrc, numBytes)

    /**
     * Set a handler to be invoked when incoming RTP packets have finished
     * being processed.
     */
    fun setIncomingPacketHandler(rtpHandler: PacketHandler) {
        rtpReceiver.packetHandler = rtpHandler
    }

    /**
     * Set a handler to be invoked when outgoing packets have finished
     * being processed (and are ready to be sent)
     */
    fun setOutgoingPacketHandler(outgoingPacketHandler: PacketHandler) {
        rtpSender.onOutgoingPacket(outgoingPacketHandler)
    }

    fun addReceiveSsrc(ssrc: Long, mediaType: MediaType) {
        logger.cdebug { "${hashCode()} adding receive ssrc $ssrc of type $mediaType" }
        streamInformationStore.addReceiveSsrc(ssrc, mediaType)
    }

    fun removeReceiveSsrc(ssrc: Long) {
        logger.cinfo { "Transceiver ${hashCode()} removing receive ssrc $ssrc" }
        streamInformationStore.removeReceiveSsrc(ssrc)
    }

    /**
     * Set the 'local' bridge SSRC to [ssrc] for [mediaType]
     */
    fun setLocalSsrc(mediaType: MediaType, ssrc: Long) {
        val localSsrcSetEvent = SetLocalSsrcEvent(mediaType, ssrc)
        rtpSender.handleEvent(localSsrcSetEvent)
        rtpReceiver.handleEvent(localSsrcSetEvent)
    }

    fun receivesSsrc(ssrc: Long): Boolean = streamInformationStore.receiveSsrcs.contains(ssrc)

    fun setMediaStreamTracks(mediaStreamTracks: Array<MediaStreamTrackDesc>): Boolean {
        logger.cdebug { "$id setting media stream tracks: ${mediaStreamTracks.joinToString()}" }
        val ret = this.mediaStreamTracks.setMediaStreamTracks(mediaStreamTracks)
        rtpReceiver.handleEvent(SetMediaStreamTracksEvent(this.mediaStreamTracks.getMediaStreamTracks()))
        return ret
    }

    // TODO(brian): we should only expose an immutable version of this, but Array doesn't have that.  Go in
    // and change all the storage of the media stream tracks to use a list
    fun getMediaStreamTracks(): Array<MediaStreamTrackDesc> = mediaStreamTracks.getMediaStreamTracks()

    @JvmOverloads
    fun requestKeyFrame(mediaSsrc: Long? = null) = rtpSender.requestKeyframe(mediaSsrc)

    fun addPayloadType(payloadType: PayloadType) {
        logger.cdebug { "Payload type added: $payloadType" }
        streamInformationStore.addRtpPayloadType(payloadType)
    }

    fun clearPayloadTypes() {
        logger.cinfo { "All payload types being cleared" }
        streamInformationStore.clearRtpPayloadTypes()
    }

    fun addRtpExtension(rtpExtension: RtpExtension) {
        logger.cdebug { "Adding RTP extension: $rtpExtension" }
        streamInformationStore.addRtpExtensionMapping(rtpExtension)
    }

    fun clearRtpExtensions() {
        logger.cinfo { "Clearing all RTP extensions" }
        // TODO: ignoring this for now, since we'll have conflicts from each channel calling it
//        val rtpExtensionClearEvent = RtpExtensionClearEvent()
//        rtpReceiver.handleEvent(rtpExtensionClearEvent)
//        rtpSender.handleEvent(rtpExtensionClearEvent)
//        rtpExtensions.clear()
    }

    fun setAudioLevelListener(audioLevelListener: AudioLevelListener) {
        logger.cdebug { "Setting audio level listener $audioLevelListener" }
        rtpReceiver.setAudioLevelListener(audioLevelListener)
    }

    // TODO(brian): we may want to handle local and remote ssrc associations differently, as different parts of the
    // code care about one or the other, but currently there is no issue treating them the same.
    fun addSsrcAssociation(ssrcAssociation: SsrcAssociation) {
        logger.cdebug {
            val location = if (ssrcAssociation is LocalSsrcAssociation) "local" else "remote"
            "Adding $location SSRC association: $ssrcAssociation"
        }
        streamInformationStore.addSsrcAssociation(ssrcAssociation)
    }

    fun setSrtpInformation(chosenSrtpProtectionProfile: Int, tlsRole: TlsRole, keyingMaterial: ByteArray) {
        val srtpProfileInfo =
            SrtpUtil.getSrtpProfileInformationFromSrtpProtectionProfile(chosenSrtpProtectionProfile)
        logger.cdebug { "Transceiver $id creating transformers with:\n" +
                "profile info:\n$srtpProfileInfo\n" +
                "tls role: $tlsRole" }
        val srtpTransformers = SrtpUtil.initializeTransformer(
            srtpProfileInfo,
            keyingMaterial,
            tlsRole,
            logger
        )

        rtpReceiver.setSrtpTransformers(srtpTransformers)
        rtpSender.setSrtpTransformers(srtpTransformers)
    }

    /**
     * Forcibly mute or unmute the incoming audio stream
     */
    fun forceMuteAudio(shouldMute: Boolean) {
        when (shouldMute) {
            true -> logger.info("Muting incoming audio")
            false -> logger.info("Unmuting incoming audio")
        }
        rtpReceiver.forceMuteAudio(shouldMute)
    }

    /**
     * Get stats about this transceiver's pipeline nodes
     */
    override fun getNodeStats(): NodeStatsBlock {
        return NodeStatsBlock("Transceiver $id").apply {
            addBlock(streamInformationStore.getNodeStats())
            addBlock(mediaStreamTracks.getNodeStats())
            addString("endpointConnectionStats", endpointConnectionStats.getSnapshot().toString())
            addJson("Bandwidth Estimation", bandwidthEstimator.getStats(clock.instant()).toJson())
            addBlock(rtpReceiver.getNodeStats())
            addBlock(rtpSender.getNodeStats())
            addJson("transportCcEngine", transportCcEngine.getStatistics().toJson())
        }
    }

    /**
     * Get various media and network stats
     */
    fun getTransceiverStats(): TransceiverStats {
        return TransceiverStats(
            endpointConnectionStats.getSnapshot(),
            rtpReceiver.getStreamStats(),
            rtpReceiver.getPacketStreamStats(),
            rtpSender.getStreamStats(),
            rtpSender.getPacketStreamStats(),
            bandwidthEstimator.getStats(clock.instant()))
    }

    override fun stop() {
        rtpReceiver.stop()
        rtpSender.stop()
    }

    fun teardown() {
        logger.info("Tearing down")
        rtpReceiver.tearDown()
        rtpSender.tearDown()
    }

    fun setFeature(feature: Features, enabled: Boolean) {
        val featureToggleEvent = FeatureToggleEvent(feature, enabled)
        rtpReceiver.handleEvent(featureToggleEvent)
        rtpSender.handleEvent(featureToggleEvent)
    }

    companion object {
        init {
//            Node.plugins.add(BufferTracePlugin)
//            Node.PLUGINS_ENABLED = true
        }
    }
}
