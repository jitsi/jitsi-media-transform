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
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.cdebug
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacketBuilder
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacketBuilder

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
        private const val WAIT_INTERVAL_MS = 100
    }

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val compoundRtcp = packetInfo.packetAs<CompoundRtcpPacket>()

        var drop = false
        for (pkt in compoundRtcp.packets)
        {
            if (pkt !is RtcpFbFirPacket || pkt !is RtcpFbPliPacket) {
                continue
            }

            val firCommandSeqNum = generateFirCommandSequenceNumber(pkt.mediaSourceSsrc, System.currentTimeMillis())
            if (firCommandSequenceNumber < 0)
            {
                // NOTE(george): dropping the packet here should work fine as long as we don't
                // receive 2 RTCP packets: one we want to drop and one we want to forward in the
                // same compound packet.
                drop = true
            }
            else if ((pkt is RtcpFbPliPacket && !hasPliSupport) || (pkt is RtcpFbFirPacket) && !hasFirSupport)
            {
                // NOTE(george): same as above
                drop = true

                requestKeyframeInternal(pkt.mediaSenderSsrc, firCommandSequenceNumber)
            }
            else if (pkt is RtcpFbFirPacket)
            {
                RtcpFbFirPacket.setSeqNum(pkt.buffer, pkt.offset, firCommandSeqNum)
            }
        }

        return if (drop) null else packetInfo
    }

    // Map a SSRC to the timestamp (in ms) of when we last requested a keyframe for it
    private val keyframeRequests = mutableMapOf<Long, Long>()
    private var firCommandSequenceNumber: Int = 0
    private val keyframeRequestsSyncRoot = Object()

    // Stats
    private var numKeyframesRequestedByBridge: Int = 0
    private var numKeyframeRequestsDropped: Int = 0

    private var hasPliSupport: Boolean = false
    private var hasFirSupport: Boolean = true

    private fun generateFirCommandSequenceNumber(mediaSsrc: Long, nowMs: Long): Int {
        synchronized (keyframeRequestsSyncRoot) {
            return if (nowMs - keyframeRequests.getOrDefault(mediaSsrc, 0) < WAIT_INTERVAL_MS) {
                logger.cdebug { "Sent a keyframe request less than ${WAIT_INTERVAL_MS}ms ago for $mediaSsrc, " +
                        "ignoring request" }
                numKeyframeRequestsDropped++
                -1
            } else {
                keyframeRequests[mediaSsrc] = nowMs
                logger.cdebug { "Keyframe requester requesting keyframe with FIR for $mediaSsrc" }
                numKeyframesRequestedByBridge++
                firCommandSequenceNumber++
            }
        }
    }

    fun requestKeyframe(mediaSsrc: Long) {
        val firCommandSeqNum = generateFirCommandSequenceNumber(mediaSsrc, System.currentTimeMillis())
        if (firCommandSeqNum < 0) {
            return
        }

        requestKeyframeInternal(mediaSsrc, firCommandSeqNum)
    }

    private fun requestKeyframeInternal(mediaSsrc: Long, firCommandSeqNum: Int)
    {
        val pkt = if (hasPliSupport) RtcpFbPliPacketBuilder(
                mediaSenderSsrc = mediaSsrc
        ).build() else RtcpFbFirPacketBuilder(
                mediaSenderSsrc = mediaSsrc,
                firCommandSeqNum = firCommandSeqNum
        ).build()

        next(PacketInfo(pkt))
    }

    override fun handleEvent(event: Event) {
        //TODO: rtcpfb events so we can tell what is supported (pli, fir)
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat("num keyframes requested by the bridge: $numKeyframesRequestedByBridge")
            addStat("num keyframes dropped due to throttling: $numKeyframeRequestsDropped")
        }
    }
}