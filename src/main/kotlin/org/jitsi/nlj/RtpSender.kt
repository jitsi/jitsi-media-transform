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

import org.jitsi.nlj.srtp.SrtpTransformers
import org.jitsi.nlj.stats.EndpointConnectionStats
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsSnapshot
import org.jitsi.rtp.rtcp.RtcpPacket

/**
 * Not an 'RtpSender' in the sense that it sends only RTP (and not
 * RTCP) but in the sense of a webrtc 'RTCRTPSender' which handles
 * all RTP and RTP control packets.
 */
abstract class RtpSender :
        EventHandler, Stoppable, NodeStatsProducer, EndpointConnectionStats.EndpointConnectionStatsListener {
    var numPacketsSent = 0
    var numBytesSent: Long = 0
    var firstPacketSentTime: Long = -1
    var lastPacketSentTime: Long = -1
    abstract fun sendPacket(packetInfo: PacketInfo)
    abstract fun sendRtcp(rtcpPacket: RtcpPacket)
    abstract fun sendProbing(mediaSsrc: Long, numBytes: Int): Int
    abstract fun onOutgoingPacket(handler: PacketHandler)
    abstract fun setSrtpTransformers(srtpTransformers: SrtpTransformers)
    abstract fun getStreamStats(): OutgoingStatisticsSnapshot
    abstract fun requestKeyframe(mediaSsrc: Long)
    abstract fun tearDown()
}
