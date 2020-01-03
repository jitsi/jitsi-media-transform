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
package org.jitsi.nlj.transform.node

import java.net.Inet4Address
import org.jitsi.nlj.PacketInfo
import org.pcap4j.core.PcapDumper
import org.pcap4j.core.PcapHandle
import org.pcap4j.core.Pcaps
import org.pcap4j.packet.EthernetPacket
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.IpV4Rfc1349Tos
import org.pcap4j.packet.UdpPacket
import org.pcap4j.packet.UnknownPacket
import org.pcap4j.packet.namednumber.DataLinkType
import org.pcap4j.packet.namednumber.EtherType
import org.pcap4j.packet.namednumber.IpNumber
import org.pcap4j.packet.namednumber.IpVersion
import org.pcap4j.packet.namednumber.UdpPort
import org.pcap4j.util.MacAddress

class PcapWriter {
    var handle: PcapHandle? = null
    var writer: PcapDumper? = null

    companion object {
        private val localhost = Inet4Address.getByName("127.0.0.1") as Inet4Address
    }

    fun observeInternal(packetInfo: PacketInfo) {

        val udpPayload = UnknownPacket.Builder()
        // We can't pass offset/limit values to udpPayload.rawData, so we need to create an array that contains
        // only exactly what we want to write
        val subBuf = ByteArray(packetInfo.packet.length)
        System.arraycopy(packetInfo.packet.buffer, packetInfo.packet.offset, subBuf, 0, packetInfo.packet.length)
        udpPayload.rawData(subBuf)
        val udp = UdpPacket.Builder()
                .srcPort(UdpPort(123, "blah"))
                .dstPort(UdpPort(456, "blah"))
                .srcAddr(localhost)
                .dstAddr(localhost)
                .correctChecksumAtBuild(true)
                .correctLengthAtBuild(true)
                .payloadBuilder(udpPayload)

        val ipPacket = IpV4Packet.Builder()
                .srcAddr(localhost)
                .dstAddr(localhost)
                .protocol(IpNumber.UDP)
                .version(IpVersion.IPV4)
                .tos(IpV4Rfc1349Tos.newInstance(0))
                .correctLengthAtBuild(true)
                .payloadBuilder(udp)

        val eth = EthernetPacket.Builder()
                .srcAddr(MacAddress.ETHER_BROADCAST_ADDRESS)
                .dstAddr(MacAddress.ETHER_BROADCAST_ADDRESS)
                .type(EtherType.IPV4)
                .paddingAtBuild(true)
                .payloadBuilder(ipPacket)
                .build()

        writer?.dump(eth)
    }

    @Synchronized fun enable(filePath: String): PcapWriter {
        if (handle == null) handle = Pcaps.openDead(DataLinkType.EN10MB, 65536)
        if (writer == null) writer = handle?.dumpOpen(filePath)
        return this
    }

    @Synchronized fun disable(): PcapWriter {
        writer?.close()
        writer = null
        handle?.close()
        handle = null
        return this
    }

    fun newObserverNode(): ObserverNode {
        return object : ObserverNode("PCAP writer") {
            override fun observe(packetInfo: PacketInfo) {
                writer?.also { observeInternal(packetInfo) }
            }
        }
    }
}
