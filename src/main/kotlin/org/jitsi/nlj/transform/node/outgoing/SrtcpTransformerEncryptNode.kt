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
package org.jitsi.nlj.transform.node.outgoing

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.forEachAs
import org.jitsi.nlj.transform.node.AbstractSrtpTransformerNode
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi_modified.impl.neomedia.transform.SinglePacketTransformer
import java.nio.ByteBuffer

class SrtcpTransformerEncryptNode : AbstractSrtpTransformerNode("SRTCP Encrypt wrapper") {
    override fun doTransform(pkts: List<PacketInfo>, transformer: SinglePacketTransformer): List<PacketInfo> {
        val encryptedPackets = mutableListOf<PacketInfo>()
        pkts.forEachAs<RtcpPacket> { pktInfo, rtcpPacket ->
            transformer.transform(rtcpPacket)?.let { srtcpPacket ->
                pktInfo.packet = srtcpPacket
                encryptedPackets.add(pktInfo)
            } ?: run {
                logger.error("Error encrypting RTCP")
            }
        }
        return encryptedPackets
    }
}
