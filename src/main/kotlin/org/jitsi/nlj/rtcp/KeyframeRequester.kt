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
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.format.VideoPayloadType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.cdebug
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacketBuilder
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacketBuilder
import java.lang.IllegalStateException
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

    var waitIntervalMs = DEFAULT_WAIT_INTERVAL_MS

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val packet = packetInfo.packet
        val pliOrFir = when (packet) {
            is CompoundRtcpPacket -> {
                packet.packets.first { it is RtcpFbPliPacket || it is RtcpFbFirPacket } as? RtcpFbPacket
            }
            is RtcpFbFirPacket -> packet
            is RtcpFbPliPacket -> packet
            else -> null
        } ?: return packetInfo

        val now = System.currentTimeMillis()
        val sourceSsrc = when (pliOrFir) {
            // PLIs use the generic "media source SSRC" field
            is RtcpFbPacket -> pliOrFir.mediaSourceSsrc
            // FIRs contain 0 for "media source SSRC" and use a field in the FCI instead.
            is RtcpFbFirPacket -> pliOrFir.mediaSenderSsrc
            else -> throw IllegalStateException("pliOrFir is neither pli nor fir?")
        }
        val canSend = canSendKeyframeRequest(sourceSsrc, now)
        val forward = when (pliOrFir) {
            is RtcpFbPliPacket -> canSend && hasPliSupport
            // When both are supported, we favor generating a PLI rather than forwarding a FIR
            is RtcpFbFirPacket -> canSend && hasFirSupport && !hasPliSupport
            else -> throw IllegalStateException("pliOrFir is neither pli nor fir?")
        }

        if (forward && pliOrFir is RtcpFbFirPacket) {
            // When we forward a FIR we need to update the seq num.
            pliOrFir.seqNum = firCommandSequenceNumber.incrementAndGet()
        }

        if (!forward && canSend) {
            requestKeyframe(sourceSsrc, now)
        }

        return if (forward) packetInfo else null
    }

    // Map a SSRC to the timestamp (in ms) of when we last requested a keyframe for it
    private val keyframeRequests = mutableMapOf<Long, Long>()
    private val firCommandSequenceNumber: AtomicInteger = AtomicInteger(0)
    private val keyframeRequestsSyncRoot = Any()

    // Stats
    private var numKeyframesRequestedByBridge: Int = 0
    private var numKeyframeRequestsDropped: Int = 0

    private var hasPliSupport: Boolean = false
    private var hasFirSupport: Boolean = true

    /**
     * Returns 'true' when at least one method is supported, AND we haven't sent a request very recently.
     */
    private fun canSendKeyframeRequest(mediaSsrc: Long, nowMs: Long): Boolean {
        if (!hasPliSupport && !hasFirSupport) {
            return false
        }
        synchronized(keyframeRequestsSyncRoot) {
            return if (nowMs - keyframeRequests.getOrDefault(mediaSsrc, 0) < waitIntervalMs) {
                logger.cdebug { "Sent a keyframe request less than ${waitIntervalMs}ms ago for $mediaSsrc, " +
                        "ignoring request" }
                numKeyframeRequestsDropped++
                false
            } else {
                keyframeRequests[mediaSsrc] = nowMs
                logger.cdebug { "Keyframe requester requesting keyframe with FIR for $mediaSsrc" }
                numKeyframesRequestedByBridge++
                true
            }
        }
    }

    fun requestKeyframe(mediaSsrc: Long, now: Long = System.currentTimeMillis()) {
        if (!canSendKeyframeRequest(mediaSsrc, now)) {
            return
        }

        val pkt = when {
            hasPliSupport -> RtcpFbPliPacketBuilder(mediaSourceSsrc = mediaSsrc).build()
            hasFirSupport -> RtcpFbFirPacketBuilder(
                mediaSenderSsrc = mediaSsrc,
                firCommandSeqNum = firCommandSequenceNumber.incrementAndGet()
            ).build()
            else -> {
                logger.warn("Can not send neither PLI nor FIR")
                return
            }
        }

        next(PacketInfo(pkt))
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
        }
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_keyframes_requested_by_the_bridge", numKeyframesRequestedByBridge)
            addNumber("num_keyframes_dropped_due_to_throttling", numKeyframeRequestsDropped)
        }
    }

    fun onRttUpdate(newRtt: Double) {
        // avg(rtt) + stddev(rtt) would be more accurate than rtt + 10.
        waitIntervalMs = min(DEFAULT_WAIT_INTERVAL_MS, newRtt.toInt() + 10)
    }
}