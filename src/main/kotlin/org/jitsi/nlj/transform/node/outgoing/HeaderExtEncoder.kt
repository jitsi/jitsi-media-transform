package org.jitsi.nlj.transform.node.outgoing

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.transform.node.ModifierNode
import org.jitsi.rtp.rtp.RtpPacket

class HeaderExtEncoder : ModifierNode("Header extension encoder") {
    override fun modify(packetInfo: PacketInfo): PacketInfo {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()

        rtpPacket.encodeHeaderExtensions()

        return packetInfo
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
