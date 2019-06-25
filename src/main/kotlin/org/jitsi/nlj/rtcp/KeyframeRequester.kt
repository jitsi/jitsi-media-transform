/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.rtcp

import org.jitsi.nlj.Event
import org.jitsi.nlj.LocalSsrcAssociationEvent
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.ReceiveSsrcAddedEvent
import org.jitsi.nlj.ReceiveSsrcRemovedEvent
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.SetLocalSsrcEvent
import org.jitsi.nlj.SsrcAssociationEvent
import org.jitsi.nlj.format.VideoPayloadType
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.cdebug
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacketBuilder
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacketBuilder
import org.jitsi.utils.MediaType
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * [KeyframeRequester] handles a few things around keyframes:
 * 1) The bridge requesting a keyframe (e.g. in order to switch) via the [KeyframeRequester#requestKeyframe]
 * method which will create a new keyframe request and forward it
 * 2) PLI/FIR translation.  If a PLI or FIR packet is forwarded through here, this class may translate it depending
 * on what the client supports
 * 3) Aggregation.  This class will pace outgoing requests such that we don't spam the sender
 */
class KeyframeRequester : TransformerNode("Keyframe Requester") {

    companion object {
        private const val DEFAULT_WAIT_INTERVAL_MS = 100
    }

    // The timestamp of when we last requested a keyframe
    private var lastKeyframeRequestTimeMs: Long = 0
    private val firCommandSequenceNumber: AtomicInteger = AtomicInteger(0)
    private val keyframeRequestsSyncRoot = Any()
    private var localSsrc: Long? = null
    private val receiveVideoSsrcs = mutableSetOf<Long>()
    /**
     * The SSRC we'll use to request keyframes.  We use only a single SSRC because, currently, we:
     * 1) only support a single video 'source' at a time
     * 2) all clients we support will send a keyframe on all simulcast SSRCs anytime we request
     *    a keyframe on one of them.
     * Because of this, we'll determine an SSRC to use based on the [ReceiveSsrcAddedEvent]s we
     * receive (as well as the [SsrcAssociationEvent]s, to make sure we don't use any 'secondary'
     * SSRCs such as RTX or FEC SSRCs) and use that SSRC in all keyframe requests.
     */
    private var keyframeSsrc: Long? = null

    // Stats

    // Number of PLI/FIRs received and forwarded to the endpoint.
    private var numPlisForwarded: Int = 0
    private var numFirsForwarded: Int = 0
    // Number of PLI/FIRs received but dropped due to throttling.
    private var numPlisDropped: Int = 0
    private var numFirsDropped: Int = 0
    // Number of PLI/FIRs generated as a result of an API request or due to translation between PLI/FIR.
    private var numPlisGenerated: Int = 0
    private var numFirsGenerated: Int = 0
    // Number of calls to requestKeyframe
    private var numApiRequests: Int = 0
    // Number of calls to requestKeyframe ignored due to throttling
    private var numApiRequestsDropped: Int = 0

    private var hasPliSupport: Boolean = false
    private var hasFirSupport: Boolean = true
    private var waitIntervalMs = DEFAULT_WAIT_INTERVAL_MS

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val packet = packetInfo.packet.let {
            when (it) {
                is CompoundRtcpPacket -> {
                    it.packets.first { compoundPacket ->
                        compoundPacket is RtcpFbPliPacket || compoundPacket is RtcpFbFirPacket
                    }
                }
                is RtcpFbFirPacket -> it
                is RtcpFbPliPacket -> it
                else -> return@transform packetInfo
            }
        }

        val now = System.currentTimeMillis()
        val sourceSsrc: Long
        val canSend = canSendKeyframeRequest(now)
        val forward: Boolean
        when (packet) {
            is RtcpFbPliPacket -> {
                sourceSsrc = packet.mediaSourceSsrc
                forward = canSend && hasPliSupport
                if (forward) numPlisForwarded++
                if (!canSend) numPlisDropped++
            }
            is RtcpFbFirPacket -> {
                sourceSsrc = packet.mediaSenderSsrc
                // When both are supported, we favor generating a PLI rather than forwarding a FIR
                forward = canSend && hasFirSupport && !hasPliSupport
                if (forward) {
                    // When we forward a FIR we need to update the seq num.
                    packet.seqNum = firCommandSequenceNumber.incrementAndGet()
                    // We manage the seq num space, so we should use the same SSRC
                    localSsrc?.let { packet.mediaSenderSsrc = it }
                    numFirsForwarded++
                }
                if (!canSend) numFirsDropped++
            }
            // This is now possible, but the compiler doesn't know it.
            else -> throw IllegalStateException("Packet is neither PLI nor FIR")
        }

        if (!forward && canSend) {
            doRequestKeyframe(sourceSsrc)
        }

        return if (forward) packetInfo else null
    }

    /**
     * Returns 'true' when at least one method is supported, AND we haven't sent a request very recently.
     */
    private fun canSendKeyframeRequest(nowMs: Long): Boolean {
        if (!hasPliSupport && !hasFirSupport) {
            return false
        }
        synchronized(keyframeRequestsSyncRoot) {
            return if (nowMs - lastKeyframeRequestTimeMs < waitIntervalMs) {
                logger.cdebug { "Sent a keyframe request less than ${waitIntervalMs}ms ago, ignoring request" }
                false
            } else {
                lastKeyframeRequestTimeMs = nowMs
                true
            }
        }
    }

    @JvmOverloads
    fun requestKeyframe(now: Long = System.currentTimeMillis()) {
        numApiRequests++
        if (!canSendKeyframeRequest(now)) {
            numApiRequestsDropped++
            return
        }
        val keyframeSsrc = getKeyframeSsrc()
        logger.cdebug { "Keyframe requester requesting keyframe for $keyframeSsrc" }

        doRequestKeyframe(keyframeSsrc)
    }

    private fun doRequestKeyframe(mediaSsrc: Long) {
        val pkt = when {
            hasPliSupport -> {
                numPlisGenerated++
                RtcpFbPliPacketBuilder(mediaSourceSsrc = mediaSsrc).build()
            }
            hasFirSupport -> {
                numFirsGenerated++
                RtcpFbFirPacketBuilder(
                    mediaSenderSsrc = mediaSsrc,
                    firCommandSeqNum = firCommandSequenceNumber.incrementAndGet()
                ).build()
            }
            else -> {
                logger.warn("Can not send neither PLI nor FIR")
                return
            }
        }

        next(PacketInfo(pkt))
    }

    private fun getKeyframeSsrc(): Long {
        keyframeSsrc = keyframeSsrc ?: receiveVideoSsrcs.first()
        return keyframeSsrc!!
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpPayloadTypeAddedEvent -> {
                when (event.payloadType) {
                    is VideoPayloadType -> {
                        // Support for FIR and PLI is declared per-payload type, but currently
                        // our code which requests FIR and PLI is not payload-type aware. So
                        // until this changes we will just check if any of the PTs supports
                        // FIR and PLI. This means that we effectively always assume support for FIR.
                        hasPliSupport = hasPliSupport || event.payloadType.rtcpFeedbackSet.contains("nack pli")
                        hasFirSupport = hasFirSupport || event.payloadType.rtcpFeedbackSet.contains("ccm fir")
                    }
                }
            }
            is RtpPayloadTypeClearEvent -> {
                // Reset to the defaults.
                hasPliSupport = false
                hasFirSupport = true
            }
            is SetLocalSsrcEvent -> {
                if (event.mediaType == MediaType.VIDEO) {
                    localSsrc = event.ssrc
                }
            }
            is ReceiveSsrcAddedEvent -> {
                if (event.mediaType == MediaType.VIDEO) {
                    receiveVideoSsrcs.add(event.ssrc)
                }
            }
            is ReceiveSsrcRemovedEvent -> {
                receiveVideoSsrcs.remove(event.ssrc)
                if (event.ssrc == keyframeSsrc) {
                    keyframeSsrc = null
                }
            }
            is LocalSsrcAssociationEvent -> {
                if (event.type == SsrcAssociationType.RTX || event.type == SsrcAssociationType.FEC) {
                    receiveVideoSsrcs.remove(event.secondarySsrc)
                }
            }
        }
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addBoolean("hasPliSupport", hasPliSupport)
            addBoolean("hasFirSupport", hasFirSupport)
            addString("waitIntervalMs", waitIntervalMs.toString()) // use string to prevent aggregation
            addNumber("numApiRequests", numApiRequests)
            addNumber("numApiRequestsDropped", numApiRequestsDropped)
            addNumber("numFirsDropped", numFirsDropped)
            addNumber("numFirsGenerated", numFirsGenerated)
            addNumber("numFirsForwarded", numFirsForwarded)
            addNumber("numPlisDropped", numPlisDropped)
            addNumber("numPlisGenerated", numPlisGenerated)
            addNumber("numPlisForwarded", numPlisForwarded)
        }
    }

    fun onRttUpdate(newRtt: Double) {
        // avg(rtt) + stddev(rtt) would be more accurate than rtt + 10.
        waitIntervalMs = min(DEFAULT_WAIT_INTERVAL_MS, newRtt.toInt() + 10)
    }
}