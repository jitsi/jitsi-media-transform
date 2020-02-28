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

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import kotlin.math.max

class PaddingTermination(parentLogger: Logger) : TransformerNode("Probing termination") {
    private val logger = createChildLogger(parentLogger)
    private var numPaddedPacketsSeen = 0
    private var numPaddingOnlyPacketsSeen = 0

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()

        if (rtpPacket.hasPadding) {
            val paddingSize = rtpPacket.paddingSize
            rtpPacket.length = max(rtpPacket.length - paddingSize, rtpPacket.headerLength)
            rtpPacket.hasPadding = false
            numPaddedPacketsSeen++
            if (rtpPacket.length == 0) {
                numPaddingOnlyPacketsSeen++
                packetInfo.shouldDiscard = true
            }
        }

        return packetInfo
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_padded_packets_seen", numPaddedPacketsSeen)
            addNumber("num_padding_only_packets_seen", numPaddingOnlyPacketsSeen)
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
