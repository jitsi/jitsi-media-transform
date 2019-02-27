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

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpExtensionAddedEvent
import org.jitsi.nlj.RtpExtensionClearEvent
import org.jitsi.nlj.forEachAs
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.toRawPacket
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.TccHeaderExtension
import org.jitsi.service.neomedia.RTPExtension
import org.jitsi_modified.impl.neomedia.rtp.TransportCCEngine
import unsigned.toUInt

class TccSeqNumTagger(
    private val transportCcEngine: TransportCCEngine? = null
) : Node("TCC sequence number tagger") {
    private var currTccSeqNum: Int = 1
    private var tccExtensionId: Int? = null

    override fun doProcessPackets(p: List<PacketInfo>): List<PacketInfo> {
        p.forEachAs<RtpPacket> { _, pkt ->
            tccExtensionId?.let { tccExtId ->
                val ext = TccHeaderExtension(tccExtId, currTccSeqNum++)
                pkt.header.addExtension(tccExtId, ext)
            }
        }
        transportCcEngine?.egressEngine?.rtpTransformer?.transform(p.map { it.packet.toRawPacket() }.toTypedArray())
        return p
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpExtensionAddedEvent -> {
                if (RTPExtension.TRANSPORT_CC_URN.equals(event.rtpExtension.uri.toString())) {
                    tccExtensionId = event.extensionId.toUInt()
                    logger.cinfo { "TCC seq num tagger setting extension ID to $tccExtensionId" }
                }
            }
            is RtpExtensionClearEvent -> tccExtensionId = null
        }
    }

}
