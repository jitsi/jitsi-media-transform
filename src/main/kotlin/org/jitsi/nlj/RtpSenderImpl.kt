/*
 * Copyright @ 2018 Atlassian Pty Ltd
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

import org.jitsi.nlj.rtcp.KeyframeRequester
import org.jitsi.nlj.rtcp.NackHandler
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.rtcp.RtcpSrUpdater
import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeEventVisitor
import org.jitsi.nlj.transform.NodeStatsVisitor
import org.jitsi.nlj.transform.NodeTeardownVisitor
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.PacketCacher
import org.jitsi.nlj.transform.node.SrtpTransformerNode
import org.jitsi.nlj.transform.node.outgoing.AbsSendTime
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsTracker
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsSnapshot
import org.jitsi.nlj.transform.node.outgoing.ProbingDataSender
import org.jitsi.nlj.transform.node.outgoing.RetransmissionSender
import org.jitsi.nlj.transform.node.outgoing.SentRtcpStats
import org.jitsi.nlj.transform.node.outgoing.TccSeqNumTagger
import org.jitsi.nlj.transform.pipeline
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.nlj.util.addMbps
import org.jitsi.nlj.util.addRatio
import org.jitsi.nlj.util.cerror
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.utils.logging.Logger
import org.jitsi.utils.MediaType
import org.jitsi_modified.impl.neomedia.rtp.TransportCCEngine
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

class RtpSenderImpl(
    val id: String,
    transportCcEngine: TransportCCEngine? = null,
    private val rtcpEventNotifier: RtcpEventNotifier,
    /**
     * The executor this class will use for its primary work (i.e. critical path
     * packet processing).  This [RtpSender] will execute a blocking queue read
     * on this executor.
     */
    val executor: ExecutorService,
    /**
     * A [ScheduledExecutorService] which can be used for less important
     * background tasks, or tasks that need to execute at some fixed delay/rate
     */
    val backgroundExecutor: ScheduledExecutorService,
    logLevelDelegate: Logger? = null
) : RtpSender() {
    protected val logger = getLogger(classLogger, logLevelDelegate)
    private val outgoingRtpRoot: Node
    private val outgoingRtxRoot: Node
    private val outgoingRtcpRoot: Node
    private val incomingPacketQueue = PacketInfoQueue("rtp-sender-incoming-packet-queue", executor, this::processPacket)
    var numIncomingBytes: Long = 0
    var firstPacketWrittenTime = -1L
    var lastPacketWrittenTime = -1L
    var running = true
    private var localVideoSsrc: Long? = null
    private var localAudioSsrc: Long? = null
    // TODO(brian): this is changed to a handler instead of a queue because we want to use
    // a PacketQueue, and the handler for a PacketQueue must be set at the time of creation.
    // since we want the handler to be another entity (something in jvb) we just use
    // a generic handler here and then the bridge can put it into its PacketQueue and have
    // its handler (likely in another thread) grab the packet and send it out
    private var outgoingPacketHandler: PacketHandler? = null

    private var firstQueueReadTime: Long = -1
    private var lastQueueReadTime: Long = -1
    private var numQueueReads: Long = 0

    private val srtpEncryptWrapper = SrtpTransformerNode("SRTP encrypt")
    private val srtcpEncryptWrapper = SrtpTransformerNode("SRTCP encrypt")
    private val outgoingPacketCache = PacketCacher()
    private val absSendTime = AbsSendTime()
    private val statTracker = OutgoingStatisticsTracker()
    private val rtcpSrUpdater = RtcpSrUpdater(statTracker)
    private val keyframeRequester = KeyframeRequester()
    private val probingDataSender: ProbingDataSender

    private val nackHandler: NackHandler

    private val outputPipelineTerminationNode = object : ConsumerNode("Output pipeline termination node") {
        override fun consume(packetInfo: PacketInfo) {
            if (packetInfo.timeline.totalDelay() > Duration.ofMillis(100)) {
                logger.cerror { "Packet took >100ms to get through bridge:\n${packetInfo.timeline}" }
            }
            // While there's no handler set we're effectively dropping packets, so their buffers
            // should be returned.
            outgoingPacketHandler?.let {
                it.processPacket(packetInfo)
            } ?: let {
                packetDiscarded(packetInfo)
            }
        }
    }

    companion object {
        private val classLogger: Logger = Logger.getLogger(this::class.java)
        private const val PACKET_QUEUE_ENTRY_EVENT = "Entered RTP sender incoming queue"
        private const val PACKET_QUEUE_EXIT_EVENT = "Exited RTP sender incoming queue"

        // Constants for the [NodeStatsBlock] stat names
        private const val INCOMING_BYTES = "incoming_bytes"
        private const val INCOMING_DURATION_MS = "incoming_duration_ms"
        private const val SENT_BYTES = "sent_bytes"
        private const val SENT_DURATION_MS = "sent_duration_ms"
        private const val QUEUE_NUM_READS = "queue_num_reads"
        private const val QUEUE_READ_DURATION_S = "queue_read_duration_s"
    }

    init {
        logger.cinfo { "Sender $id using executor ${executor.hashCode()}" }

        outgoingRtpRoot = pipeline {
            node(outgoingPacketCache)
            node(absSendTime)
            node(statTracker)
            node(TccSeqNumTagger(transportCcEngine))
            node(srtpEncryptWrapper)
            node(outputPipelineTerminationNode)
        }

        outgoingRtxRoot = pipeline {
            node(RetransmissionSender())
            // We want RTX packets to hook into the main RTP pipeline starting at AbsSendTime
            node(absSendTime)
        }

        nackHandler = NackHandler(outgoingPacketCache.getPacketCache(), outgoingRtxRoot)
        rtcpEventNotifier.addRtcpEventListener(nackHandler)

        // TODO: are we setting outgoing rtcp sequence numbers correctly? just add a simple node here to rewrite them
        outgoingRtcpRoot = pipeline {
            node(keyframeRequester)
            node(SentRtcpStats())
            // TODO(brian): not sure this is a great idea.  it works as a catch-call but can also be error-prone
            // (i found i was accidentally clobbering the sender ssrc for SRs which caused issues).  I think
            // it'd be better to notify everything creating RTCP the bridge SSRCs and then everything should be
            // responsible for setting it themselves
            simpleNode("RTCP sender ssrc setter") { packetInfo ->
                val senderSsrc = localVideoSsrc ?: return@simpleNode null
                val rtcpPacket = packetInfo.packetAs<RtcpPacket>()
                if (rtcpPacket.senderSsrc == 0L) {
                    rtcpPacket.senderSsrc = senderSsrc
                }
                packetInfo
            }
            node(rtcpSrUpdater)
            node(srtcpEncryptWrapper)
            node(outputPipelineTerminationNode)
        }

        probingDataSender = ProbingDataSender(outgoingPacketCache.getPacketCache(), outgoingRtxRoot, absSendTime)
    }

    override fun onRttUpdate(newRtt: Double) {
        nackHandler.onRttUpdate(newRtt)
        keyframeRequester.onRttUpdate(newRtt)
    }

    override fun sendPacket(packetInfo: PacketInfo) {
        numIncomingBytes += packetInfo.packet.length
        packetInfo.addEvent(PACKET_QUEUE_ENTRY_EVENT)
        incomingPacketQueue.add(packetInfo)
        if (firstPacketWrittenTime == -1L) {
            firstPacketWrittenTime = System.currentTimeMillis()
        }
        lastPacketWrittenTime = System.currentTimeMillis()
    }

    override fun sendRtcp(rtcpPacket: RtcpPacket) {
        rtcpEventNotifier.notifyRtcpSent(rtcpPacket)
        // TODO: do we want to allow for PacketInfo to be passed in to sendRtcp?
        sendPacket(PacketInfo(rtcpPacket))
    }

    override fun sendProbing(mediaSsrc: Long, numBytes: Int): Int = probingDataSender.sendProbing(mediaSsrc, numBytes)

    override fun onOutgoingPacket(handler: PacketHandler) {
        outgoingPacketHandler = handler
    }

    override fun setSrtpTransformers(srtpTransformers: SrtpTransformers) {
        srtpEncryptWrapper.transformer = srtpTransformers.srtpEncryptTransformer
        srtcpEncryptWrapper.transformer = srtpTransformers.srtcpEncryptTransformer
    }

    override fun requestKeyframe(mediaSsrc: Long) {
        keyframeRequester.requestKeyframe(mediaSsrc)
    }

    private fun processPacket(packetInfo: PacketInfo): Boolean {
        if (running) {
            val now = System.currentTimeMillis()
            if (firstQueueReadTime == -1L) {
                firstQueueReadTime = now
            }
            numQueueReads++
            lastQueueReadTime = now
            packetInfo.addEvent(PACKET_QUEUE_EXIT_EVENT)

            val root = when (packetInfo.packet) {
                is RtcpPacket -> outgoingRtcpRoot
                else -> outgoingRtpRoot
            }
            root.processPacket(packetInfo)
            return true
        }
        return false
    }

    override fun getStreamStats(): OutgoingStatisticsSnapshot {
        return statTracker.getSnapshot()
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetLocalSsrcEvent -> {
                when (event.mediaType) {
                    MediaType.VIDEO -> localVideoSsrc = event.ssrc
                    MediaType.AUDIO -> localAudioSsrc = event.ssrc
                    else -> {}
                }
            }
        }
        NodeEventVisitor(event).reverseVisit(outputPipelineTerminationNode)
        probingDataSender.handleEvent(event)
    }

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("RTP sender $id").apply {
        addNumber(INCOMING_BYTES, numIncomingBytes)
        addNumber(INCOMING_DURATION_MS, lastPacketWrittenTime - firstPacketWrittenTime)
        addMbps("incoming_bitrate_mbps", INCOMING_BYTES, INCOMING_BYTES)

        addNumber("sent_packets", numPacketsSent)
        addNumber(SENT_DURATION_MS, lastPacketSentTime - firstPacketSentTime)
        addNumber(SENT_BYTES, numBytesSent)
        addMbps("sent_bitrate_mbps", SENT_BYTES, SENT_DURATION_MS)

        addNumber(QUEUE_NUM_READS, numQueueReads)
        addNumber(QUEUE_READ_DURATION_S, (lastQueueReadTime - firstQueueReadTime).toDouble() / 1000)
        addRatio("queue_average_reads_per_second", QUEUE_NUM_READS, QUEUE_READ_DURATION_S)

        addBlock(nackHandler.getNodeStats())
        addBlock(probingDataSender.getNodeStats())
        NodeStatsVisitor(this).reverseVisit(outputPipelineTerminationNode)

        addString("running", running.toString())
        addString("localVideoSsrc", localVideoSsrc?.toString() ?: "null")
        addString("localAudioSsrc", localAudioSsrc?.toString() ?: "null")
    }

    override fun stop() {
        running = false
        incomingPacketQueue.close()
    }

    override fun tearDown() {
        NodeTeardownVisitor().reverseVisit(outputPipelineTerminationNode)
    }
}
