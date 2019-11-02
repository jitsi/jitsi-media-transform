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

package org.jitsi.nlj.transform.node.incoming

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import io.kotlintest.IsolationMode
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.shouldBe
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.resources.node.onOutput
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpReportBlock
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.RtcpSrPacketBuilder
import org.jitsi.rtp.rtcp.SenderInfoBuilder
import org.jitsi.rtp.rtcp.SenderInfoParser

class RtcpTerminationTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val rtcpEventNotifier: RtcpEventNotifier = mock()
    private val rtcpTermination = RtcpTermination(rtcpEventNotifier, StdoutLogger())

    init {
        "An SR packet" {
            val senderInfo = SenderInfoBuilder(
                ntpTimestampMsw = 0xDEADBEEF,
                ntpTimestampLsw = 0xBEEFDEAD,
                rtpTimestamp = 12345L,
                sendersPacketCount = 42,
                sendersOctetCount = 4242
            )
            "with a receiver report block" {
                val reportBlock = RtcpReportBlock(
                    ssrc = 12345,
                    fractionLost = 42,
                    cumulativePacketsLost = 4242,
                    seqNumCycles = 1,
                    seqNum = 42,
                    interarrivalJitter = 4242,
                    lastSrTimestamp = 23456,
                    delaySinceLastSr = 34567
                )

                val srPacket = RtcpSrPacketBuilder(
                    RtcpHeaderBuilder(
                        senderSsrc = 12345L
                    ),
                    senderInfo,
                    mutableListOf(reportBlock)
                ).build()

                val compoundRtcpPacket = CompoundRtcpPacket(srPacket.buffer, srPacket.offset, srPacket.length)

                whenever(rtcpEventNotifier.notifyRtcpReceived(any<RtcpPacket>(), any())).thenAnswer {
                    (it.getArgument(0) as RtcpSrPacket).reportBlocks
                }

                should("remove all of the report blocks") {
                    rtcpTermination.onOutput { packetInfo ->
                        with(packetInfo.packet as RtcpSrPacket) {
                            length shouldBe (RtcpHeader.SIZE_BYTES + SenderInfoParser.SIZE_BYTES)
                            reportCount shouldBe 0
                            reportBlocks.size shouldBe 0
                        }
                    }
                    rtcpTermination.processPacket(PacketInfo(compoundRtcpPacket))
                }
            }
        }
    }
}
