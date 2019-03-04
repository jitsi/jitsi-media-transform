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
package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtcp.RetransmissionRequester
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtp.RtpPacket
import java.util.concurrent.ScheduledExecutorService

class RetransmissionRequesterNode(
    rtcpSender: (RtcpPacket) -> Unit,
    scheduler: ScheduledExecutorService
) : ObserverNode("Retransmission requester") {
    private val retransmissionRequester = RetransmissionRequester(rtcpSender, scheduler)

    override fun observe(packetInfo: PacketInfo) {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        retransmissionRequester.packetReceived(rtpPacket.header.ssrc, rtpPacket.header.sequenceNumber)
    }

    override fun stop() {
        super.stop()
        retransmissionRequester.stop()
    }
}
