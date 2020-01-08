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

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.collections.shouldHaveSingleElement
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.matchers.types.shouldBeInstanceOf
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.shouldBe
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.resources.node.onOutput
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtcp.RtcpByePacket
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.rtcp.UnsupportedRtcpPacket

class CompoundRtcpParserTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val parser = CompoundRtcpParser()

    private val invalidRtcpData = byteArrayOf(
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00
    )

    private val unsupportedRtcpData = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        // V=2, PT=195, length = 2
        0x80, 0xC3, 0x00, 0x02,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00
    )

    private val rtcpByeNoReason = RtcpHeaderBuilder(
        packetType = RtcpByePacket.PT,
        reportCount = 1,
        senderSsrc = 12345L,
        length = 1
    ).build()

    init {
        "A compound RTCP packet with invalid data" {
            "at the beginning of the packet" {
                val packet = DummyPacket(invalidRtcpData)
                val packetInfo = PacketInfo(packet)
                should("drop the packet entirely") {
                    parser.onOutput { it shouldBe null }
                    parser.processPacket(packetInfo)
                }
            }
            "after a valid packet" {
                val packet = DummyPacket(rtcpByeNoReason + invalidRtcpData)
                val packetInfo = PacketInfo(packet)
                should("drop the packet entirely") {
                    parser.onOutput { it shouldBe null }
                    parser.processPacket(packetInfo)
                }
            }
        }
        "A compound RTCP packet with an unsupported RTCP type" {
            "at the beginning of the packet" {
                val packet = DummyPacket(unsupportedRtcpData)
                val packetInfo = PacketInfo(packet)
                should("parse it as UnsupportedRtcpPacket") {
                    parser.onOutput {
                        it.packet.shouldBeInstanceOf<CompoundRtcpPacket>()
                        val compoundPacket = it.packetAs<CompoundRtcpPacket>()
                        compoundPacket.packets shouldHaveSize 1
                        compoundPacket.packets[0].shouldBeInstanceOf<UnsupportedRtcpPacket>()
                    }
                    parser.processPacket(packetInfo)
                }
            }
            "in between other supported RTCP packets" {
                val packet = DummyPacket(rtcpByeNoReason + unsupportedRtcpData + rtcpByeNoReason)
                val packetInfo = PacketInfo(packet)
                should("parse it as UnsupportedRtcpPacket") {
                    parser.onOutput {
                        it.packet.shouldBeInstanceOf<CompoundRtcpPacket>()
                        val compoundPacket = it.packetAs<CompoundRtcpPacket>()
                        compoundPacket.packets shouldHaveSize 3
                        compoundPacket.packets[0].shouldBeInstanceOf<RtcpByePacket>()
                        compoundPacket.packets[1].shouldBeInstanceOf<UnsupportedRtcpPacket>()
                        compoundPacket.packets[2].shouldBeInstanceOf<RtcpByePacket>()
                    }
                    parser.processPacket(packetInfo)
                }
            }
        }
    }

    private class DummyPacket(
        buf: ByteArray
    ) : Packet(buf, 0, buf.size) {
        override fun clone(): Packet = DummyPacket(cloneBuffer(0))
    }
}