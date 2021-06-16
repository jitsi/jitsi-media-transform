package org.jitsi.nlj.transform.node.outgoing

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.transform.node.ModifierNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.rtp.RtpPacket

/**
 * Strip all hop-by-hop header extensions.  Currently this leaves only ssrc-audio-level.
 */
class HeaderExtStripper(
    streamInformationStore: ReadOnlyStreamInformationStore
) : ModifierNode("Strip header extensions") {
    private var retainedExts: Set<Int> = emptySet()

    init {
        streamInformationStore.onRtpExtensionMapping(RtpExtensionType.SSRC_AUDIO_LEVEL) {
            retainedExts = if (it != null) setOf(it) else emptySet()
        }
    }

    override fun modify(packetInfo: PacketInfo): PacketInfo {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()

        rtpPacket.removeHeaderExtensionsExcept(retainedExts)

        return packetInfo
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
