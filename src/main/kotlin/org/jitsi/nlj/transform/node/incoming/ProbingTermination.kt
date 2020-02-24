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
package org.jitsi.nlj.transform.node.incoming

import java.util.Collections
import java.util.TreeMap
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.LRUCache
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import kotlin.math.max

class ProbingTermination(parentLogger: Logger) : TransformerNode("Probing termination") {
    private val logger = createChildLogger(parentLogger)
    private val replayContexts: MutableMap<Long, MutableSet<Int>> = TreeMap()
    private var numDuplicatePacketsDropped = 0
    private var numPaddingPacketsSeen = 0
    private var numPaddingOnlyPacketsDropped = 0

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        val replayContext = replayContexts.computeIfAbsent(rtpPacket.ssrc) {
            Collections.newSetFromMap(LRUCache(1500))
        }

        if (!replayContext.add(rtpPacket.sequenceNumber)) {
            numDuplicatePacketsDropped++
            return null
        }

        if (rtpPacket.hasPadding) {
            val paddingSize = rtpPacket.paddingSize
            rtpPacket.length = max(rtpPacket.length - paddingSize, rtpPacket.headerLength)
            rtpPacket.hasPadding = false
            numPaddingPacketsSeen++
        }

        if (rtpPacket.payloadLength <= 0) {
            logger.debug { "Dropping a padding-only packet: $rtpPacket" }
            numPaddingOnlyPacketsDropped++
            return null
        }

        return packetInfo
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_duplicate_packets_dropped", numDuplicatePacketsDropped)
            addNumber("num_padding_packets_seen", numPaddingPacketsSeen)
            addNumber("num_padding_only_packets_dropped", numPaddingOnlyPacketsDropped)
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
